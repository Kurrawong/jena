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
 * A search hit with a stable query-scoped identifier for joining with {@code luc:match}.
 * <p>
 * Each hit gets a blank node ID like {@code _:hit0}, {@code _:hit1}, etc.
 * that is stable within a single SPARQL query execution.
 */
public class SearchHit {
    private final Node hitId;
    private final int rank;
    private final Node entityNode;
    private final float score;
    private final int luceneDocId;
    private List<FieldMatch> fieldMatches;
    private List<NestedMatch> nestedMatches;

    public SearchHit(int index, Node entityNode, float score, int luceneDocId) {
        this.hitId = NodeFactory.createBlankNode("hit" + index);
        this.rank = index;
        this.entityNode = entityNode;
        this.score = score;
        this.luceneDocId = luceneDocId;
    }

    public Node getHitId() { return hitId; }

    /**
     * Position in the whole result set, counting from 0 — not within the page. A score
     * cannot stand in for this: a match-all query scores every document identically, and
     * relevance scores tie. Rank is what lets a consumer holding an unordered result set
     * reconstruct the order the search returned.
     */
    public int getRank() { return rank; }

    public Node getEntityNode() { return entityNode; }
    public float getScore() { return score; }
    public int getLuceneDocId() { return luceneDocId; }

    public List<FieldMatch> getFieldMatches() {
        return fieldMatches != null ? fieldMatches : Collections.emptyList();
    }

    public void setFieldMatches(List<FieldMatch> fieldMatches) {
        this.fieldMatches = fieldMatches != null ? new ArrayList<>(fieldMatches) : null;
    }

    /**
     * The block-join child documents that satisfied the query's filter, each carrying its
     * own grouping node. Empty when the filter had no clause on a nested scope — nothing
     * selected a child, so there is no "the child that matched" to report.
     */
    public List<NestedMatch> getNestedMatches() {
        return nestedMatches != null ? nestedMatches : Collections.emptyList();
    }

    public void setNestedMatches(List<NestedMatch> nestedMatches) {
        this.nestedMatches = nestedMatches != null ? new ArrayList<>(nestedMatches) : null;
    }

    @Override
    public String toString() {
        return "SearchHit{id=" + hitId + " entity=" + entityNode + " score=" + score + "}";
    }
}
