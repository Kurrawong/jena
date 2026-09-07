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

package org.apache.jena.query.text.embedding.jlama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jena.query.text.embedding.EmbeddingConfig;
import org.apache.jena.query.text.embedding.EmbeddingException;
import org.apache.jena.query.text.embedding.EmbeddingProviders;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Jlama provider that do not require a model.
 * <p>
 * Loading a real model means downloading hundreds of megabytes from HuggingFace, so the
 * forward pass is deliberately not exercised here. What is covered is everything that can
 * be wrong <em>without</em> the model: the ServiceLoader registration, the config
 * validation, and the query/document prefix inference — which is the piece most likely to
 * be silently wrong, since a mismatched prefix degrades retrieval without erroring.
 */
public class TestJlamaEmbeddingProvider {

    @Test
    public void providerIsDiscoverableByName() {
        assertTrue(EmbeddingProviders.availableNames().contains("jlama"),
            "jlama provider not registered; available: " + EmbeddingProviders.availableNames());
    }

    @Test
    public void missingModelIsRejectedBeforeAnyDownload() {
        EmbeddingException ex = assertThrows(EmbeddingException.class,
            () -> EmbeddingProviders.create(new EmbeddingConfig("jlama", null, null, 384)));
        assertTrue(ex.getMessage().contains("idx:model"), ex.getMessage());
    }

    @Test
    public void bgeGetsARetrievalInstructionOnQueriesOnly() {
        // BGE is asymmetric: the instruction belongs on the query side and must not appear
        // on documents. Applying it to both is a silent quality regression.
        assertTrue(JlamaEmbeddingProvider.defaultQueryPrefix("BAAI/bge-small-en-v1.5")
            .startsWith("Represent this sentence"));
        assertEquals("", JlamaEmbeddingProvider.defaultDocumentPrefix("BAAI/bge-small-en-v1.5"));
    }

    @Test
    public void e5GetsQueryAndPassagePrefixes() {
        assertEquals("query: ", JlamaEmbeddingProvider.defaultQueryPrefix("intfloat/e5-small-v2"));
        assertEquals("passage: ", JlamaEmbeddingProvider.defaultDocumentPrefix("intfloat/e5-small-v2"));
    }

    @Test
    public void unknownModelsGetNoPrefixes() {
        // A symmetric model must not have text prepended on either side.
        assertEquals("", JlamaEmbeddingProvider.defaultQueryPrefix("sentence-transformers/all-MiniLM-L6-v2"));
        assertEquals("", JlamaEmbeddingProvider.defaultDocumentPrefix("sentence-transformers/all-MiniLM-L6-v2"));
    }
}
