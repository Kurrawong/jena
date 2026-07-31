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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

/**
 * One block-join child document that satisfied the query's filter, with its stored
 * fields projected back out.
 * <p>
 * The record identifier is what makes the projection usable: a hit whose filter matched
 * two children yields two {@code NestedMatch}es, and a consumer groups by
 * {@link #getRecordId()} to keep each child's fields together. Flattening the fields of
 * all matching children onto the hit loses that pairing irrecoverably — with clauses on
 * <em>role</em> and <em>agent</em>, or on <em>analyte</em> and <em>value</em>, the whole
 * point is which value went with which key on a single child.
 * <p>
 * Like {@link SearchHit#getHitId()}, the identifier is a query-scoped blank node derived
 * from the hit's rank and the child's ordinal. It is minted once, when the search runs,
 * so repeated evaluation of the property function over the same cached hits yields the
 * same node and joins hold.
 */
public class NestedMatch {
    private final Node recordId;
    private final String scope;
    private final List<FieldMatch> fieldMatches;

    public NestedMatch(int hitRank, int ordinal, String scope, List<FieldMatch> fieldMatches) {
        this.recordId = NodeFactory.createBlankNode("hit" + hitRank + "r" + ordinal);
        this.scope = scope;
        this.fieldMatches = fieldMatches != null
            ? Collections.unmodifiableList(new ArrayList<>(fieldMatches))
            : Collections.emptyList();
    }

    /** Query-scoped blank node grouping this child's field matches. */
    public Node getRecordId() { return recordId; }

    /**
     * The {@code idx:nested} scope this child belongs to — the stringified join path,
     * an internal identity rather than a name a query author would write.
     */
    public String getScope() { return scope; }

    public List<FieldMatch> getFieldMatches() { return fieldMatches; }

    @Override
    public String toString() {
        return "NestedMatch{id=" + recordId + " scope=" + scope + " fields=" + fieldMatches + "}";
    }
}
