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

package org.apache.jena.query.text.embedding;

/**
 * Turns text into a dense vector.
 * <p>
 * Deliberately the only embedding-related type {@code jena-text} needs, and deliberately
 * free of any ML runtime dependency: implementations live in separate modules
 * ({@code jena-text-embeddings} for Jlama) and are discovered through
 * {@link EmbeddingProviderFactory}. Keeping the interface here and the engines out means
 * a deployment that never configures a vector field pulls in no model code at all.
 * <p>
 * <b>Document and query text are embedded through different methods on purpose.</b> Most
 * modern retrieval models are asymmetric: BGE wants a retrieval instruction glued to the
 * query and nothing on the document, E5 wants {@code "query: "} and {@code "passage: "}
 * prefixes respectively. Getting that backwards does not fail, it quietly returns worse
 * results, so the distinction is pushed into the interface rather than left to callers.
 *
 * @see EmbeddingProviderFactory
 */
public interface EmbeddingProvider extends AutoCloseable {

    /**
     * Number of components in every vector this provider returns.
     * <p>
     * Lucene fixes a KNN field's dimension at index time, so this value is checked against
     * the field's declared {@code idx:dimension} when the index is assembled — a mismatch
     * is a configuration error, not something to discover on the first query.
     */
    int dimension();

    /**
     * Stable identifier for the model behind this provider, e.g.
     * {@code "BAAI/bge-small-en-v1.5"}.
     * <p>
     * Recorded in the index at build time and compared at startup. Index-time and
     * query-time models must match: a mismatch does not error, it returns plausible
     * garbage, which is far worse.
     */
    String modelId();

    /** Embed text that is being indexed. */
    float[] embedDocument(String text);

    /**
     * Embed text that is being searched for.
     * <p>
     * Defaults to {@link #embedDocument} for symmetric models, which is the correct
     * behaviour for anything that does not distinguish the two sides.
     */
    default float[] embedQuery(String text) {
        return embedDocument(text);
    }

    /**
     * Release any model resources. Overridden by implementations holding native or
     * off-heap state; the default does nothing so symmetric in-memory providers need
     * not implement it.
     */
    @Override
    default void close() {}
}
