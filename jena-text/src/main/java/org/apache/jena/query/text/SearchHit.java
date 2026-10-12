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

import java.util.Collections;
import java.util.List;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

/**
 * A search hit with a query-scoped identifier for joining with {@code luc:match},
 * {@code luc:nestedMatch} and later {@code luc:fields}.
 * <p>
 * The identifier is a blank node labelled with the owning search's prefix and the hit's
 * rank, for example {@code s2h17}, so it is unique within one SPARQL query execution
 * and names both the search and the position.
 * <p>
 * Everything except the rank, score and doc ID is read later and only on request: the
 * entity IRI when the query binds {@code ?entity}, the match details when it uses
 * {@code luc:match}, the child records when it uses {@code luc:nestedMatch}.
 */
public class SearchHit {
    private final Node hitId;
    private final int rank;
    private final float score;
    private final int luceneDocId;
    private Node entityNode;
    private boolean entityRead;
    private List<FieldMatch> fieldMatches;
    private List<NestedMatch> nestedMatches;

    /**
     * @param labelPrefix prefix of the blank node label, distinct per search in a query
     * @param rank        position in the whole result set, counting from 0
     */
    public SearchHit(String labelPrefix, int rank, float score, int luceneDocId) {
        this.hitId = NodeFactory.createBlankNode(labelPrefix + rank);
        this.rank = rank;
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

    /**
     * The entity IRI, or null if it has not been read or the document has no entity
     * field. See {@link #isEntityRead()}.
     */
    public Node getEntityNode() { return entityNode; }

    /** Whether the entity field has been read for this hit. */
    public boolean isEntityRead() { return entityRead; }

    void setEntityNode(Node entityNode) {
        this.entityNode = entityNode;
        this.entityRead = true;
    }

    public float getScore() { return score; }
    public int getLuceneDocId() { return luceneDocId; }

    /** Match details, or an empty list if none were found or none have been computed. */
    public List<FieldMatch> getFieldMatches() {
        return fieldMatches != null ? fieldMatches : Collections.emptyList();
    }

    /** Whether match details have been computed for this hit. */
    public boolean hasFieldMatches() {
        return fieldMatches != null;
    }

    public void setFieldMatches(List<FieldMatch> fieldMatches) {
        this.fieldMatches = fieldMatches != null ? List.copyOf(fieldMatches) : null;
    }

    /**
     * The block-join child documents that satisfied the query's filter, each carrying its
     * own grouping node. Empty when the filter had no clause on a nested scope — nothing
     * selected a child, so there is no "the child that matched" to report — or when they
     * have not been computed. See {@link #hasNestedMatches()}.
     */
    public List<NestedMatch> getNestedMatches() {
        return nestedMatches != null ? nestedMatches : Collections.emptyList();
    }

    /** Whether the child records have been computed for this hit. */
    public boolean hasNestedMatches() {
        return nestedMatches != null;
    }

    public void setNestedMatches(List<NestedMatch> nestedMatches) {
        this.nestedMatches = nestedMatches != null ? List.copyOf(nestedMatches) : null;
    }

    @Override
    public String toString() {
        return "SearchHit{id=" + hitId + " entity=" + entityNode + " score=" + score + "}";
    }
}
