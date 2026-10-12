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

import org.apache.jena.graph.Graph;

/**
 * SPARQL property function returning figures about a SHACL index's Lucene contents
 * ({@code luc:stats}).
 * <p>
 * <b>Syntax:</b>
 * <pre>
 * (?s ?p ?o) luc:stats (indexSelector)
 * </pre>
 * <p>
 * Each subject slot is a variable or a constant, and the call matches like one triple
 * pattern over the figures {@link ShaclIndexStats} computes. Subjects are the index
 * resource and the field IRIs, the same nodes {@code luc:config} returns.
 */
public class ShaclStatsPF extends ShaclGraphPF {

    public ShaclStatsPF() {
        super("luc:stats");
    }

    @Override
    protected Graph graph(ShaclTextIndexLucene index, String selector) {
        return index.getStats();
    }
}
