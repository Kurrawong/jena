/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *   SPDX-License-Identifier: Apache-2.0
 */

package org.apache.jena.query.text;

import org.apache.jena.atlas.logging.Log;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.engine.ExecutionContext;

/**
 * Resolves the {@code indexSelector} argument of the SHACL-mode property functions to a
 * {@link ShaclTextIndexLucene}: through the {@link TextIndexRegistry} when the dataset has
 * one, otherwise to the single index of the dataset, which only answers to
 * {@link TextIndexRegistry#DEFAULT_ID}.
 */
final class ShaclIndexSelection {

    /**
     * @param identity the registry's canonical key for the index, used to key shared search state
     * @param index    the selected index
     */
    record Resolved(String identity, ShaclTextIndexLucene index) {}

    private ShaclIndexSelection() {}

    /** Returns null when the dataset has no text index at all. */
    static Resolved resolve(ExecutionContext execCxt, DatasetGraph dsg, String selector) {
        Object regObj = execCxt.getContext().get(TextQuery.textIndexRegistry);
        if (regObj instanceof TextIndexRegistry registry) {
            TextIndexRegistry.ResolvedIndex resolved = registry.resolve(selector);
            TextIndexLucene idx = resolved.index();
            if (idx instanceof ShaclTextIndexLucene shaclIdx) {
                return new Resolved(resolved.canonicalKey(), shaclIdx);
            }
            throw new TextIndexException("Selected text index is not SHACL-enabled: " + selector);
        }

        if (!TextIndexRegistry.DEFAULT_ID.equals(selector)) {
            throw new TextIndexException("Single-index datasets only support index selector \"" +
                TextIndexRegistry.DEFAULT_ID + "\", got: " + selector);
        }
        Object obj = execCxt.getContext().get(TextQuery.textIndex);
        if (obj instanceof ShaclTextIndexLucene shaclIdx) {
            return new Resolved(TextIndexRegistry.DEFAULT_ID, shaclIdx);
        }
        if (obj != null) {
            throw new TextIndexException("Configured text index is not SHACL-enabled");
        }
        if (dsg instanceof DatasetGraphText) {
            TextIndex ti = ((DatasetGraphText) dsg).getTextIndex();
            if (ti instanceof ShaclTextIndexLucene shaclIdx) {
                return new Resolved(TextIndexRegistry.DEFAULT_ID, shaclIdx);
            }
            throw new TextIndexException("Dataset text index is not SHACL-enabled");
        }
        Log.warn(ShaclIndexSelection.class, "Failed to find the text index");
        return null;
    }
}
