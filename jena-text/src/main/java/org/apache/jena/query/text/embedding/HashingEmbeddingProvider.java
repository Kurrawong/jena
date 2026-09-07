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

import java.util.Locale;

/**
 * A dependency-free, fully deterministic {@link EmbeddingProvider} that hashes words into
 * vector components (the "hashing trick").
 * <p>
 * <b>This is not a semantic model.</b> It measures lexical overlap and nothing else:
 * "car" and "automobile" are as unrelated as "car" and "tuesday". It exists so that the
 * vector <em>plumbing</em> — field construction, filtered KNN, facet counts under KNN,
 * assembler parsing — can be tested and demonstrated without downloading a model, and so
 * that a first-time user can see the query shape work before deciding on an engine.
 * <p>
 * For anything resembling real use, configure {@code idx:provider "jlama"} from the
 * optional {@code jena-text-embeddings} module. The index records {@link #modelId()} as
 * {@code hashing-v1}, so an index built with this provider and queried with a real one
 * fails the model-identity check rather than silently returning nonsense.
 * <p>
 * Vectors are L2-normalised, which makes dot-product, cosine and Euclidean rank
 * identically — so the field's declared similarity function cannot change the answer
 * here, and a test asserting on order is not implicitly asserting on that choice.
 */
public class HashingEmbeddingProvider implements EmbeddingProvider {

    /** Bumped if the hashing scheme ever changes, since that invalidates existing indexes. */
    public static final String MODEL_ID = "hashing-v1";

    public static final int DEFAULT_DIMENSION = 64;

    private final int dimension;

    public HashingEmbeddingProvider() {
        this(DEFAULT_DIMENSION);
    }

    public HashingEmbeddingProvider(int dimension) {
        if (dimension <= 0) {
            throw new EmbeddingException("Hashing provider dimension must be positive, got " + dimension);
        }
        this.dimension = dimension;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public float[] embedDocument(String text) {
        float[] vector = new float[dimension];
        if (text == null || text.isBlank()) {
            // An all-zero vector has no direction, and Lucene rejects it outright under
            // DOT_PRODUCT (which requires unit length). Return a fixed unit vector so
            // entities whose source fields are all empty remain indexable — they simply
            // cluster together, far from everything with real content.
            vector[0] = 1.0f;
            return vector;
        }

        for (String token : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (token.isEmpty()) continue;
            int hash = token.hashCode();
            int bucket = Math.floorMod(hash, dimension);
            // The second hash bit picks the sign, so unrelated tokens colliding in a
            // bucket tend to cancel rather than reinforce.
            vector[bucket] += ((hash >>> 31) == 0) ? 1.0f : -1.0f;
        }
        return normalise(vector);
    }

    private static float[] normalise(float[] vector) {
        double sumSquares = 0.0;
        for (float v : vector) {
            sumSquares += (double) v * v;
        }
        if (sumSquares == 0.0) {
            // Every token cancelled out. Same reasoning as the blank-text case above.
            vector[0] = 1.0f;
            return vector;
        }
        float norm = (float) Math.sqrt(sumSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return vector;
    }
}
