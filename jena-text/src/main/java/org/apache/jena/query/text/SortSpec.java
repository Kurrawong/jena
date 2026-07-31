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

import java.util.Locale;

/**
 * Sort specification for Lucene query results.
 * <p>
 * A spec is either <em>flat</em> — sort parents by a field on the parent doc — or a
 * <em>nested sort selector</em>: sort parents by a field on one of their block-join
 * child docs, choosing the child by a co-located discriminator. The selector is a
 * sort-key chooser, not a result filter: entities with no matching child stay in the
 * result set and are placed per {@link #missing()}.
 *
 * @param field         the field identifier (IRI) to sort by; for a selector spec this is
 *                      the child value field
 * @param descending    true for descending order, false for ascending
 * @param selectorField the co-located child discriminator field identifier (IRI), or null
 *                      for a flat sort
 * @param selectorValue the value {@code selectorField} must equal on the child supplying
 *                      the sort key; null for a flat sort
 * @param missing       placement of entities with no sort value, or null for the Lucene
 *                      default (selector specs default to {@link MissingPlacement#LAST})
 */
public record SortSpec(String field, boolean descending, String selectorField,
                       String selectorValue, MissingPlacement missing) {

    /** Where entities with no sort value land in the final result order. */
    public enum MissingPlacement { FIRST, LAST }

    /** Flat sort — no child selector, Lucene-default missing-value placement. */
    public SortSpec(String field, boolean descending) {
        this(field, descending, null, null, null);
    }

    /** True when this spec selects its sort key from a nested child doc. */
    public boolean hasSelector() {
        return selectorField != null;
    }

    public String toCanonical() {
        StringBuilder sb = new StringBuilder(field);
        if (selectorField != null) {
            sb.append('[').append(selectorField).append('=').append(selectorValue).append(']');
        }
        sb.append(descending ? ":desc" : ":asc");
        if (missing != null) {
            sb.append(":missing-").append(missing.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
