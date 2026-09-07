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

package org.apache.jena.query.text.embedding.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.apache.jena.query.text.embedding.EmbeddingConfig;
import org.apache.jena.query.text.embedding.EmbeddingException;
import org.apache.jena.query.text.embedding.EmbeddingProvider;
import org.apache.jena.query.text.embedding.EmbeddingProviders;
import org.junit.jupiter.api.Test;

/**
 * Tests for the ONNX provider.
 * <p>
 * The registration, config validation and prefix/pooling inference run everywhere. The
 * forward-pass tests need a ~130MB model and are skipped unless one is already on disk —
 * set {@code jena.test.onnx.modelPath} to a directory holding an unpacked
 * {@code BAAI/bge-small-en-v1.5} ONNX export to run them.
 */
public class TestOnnxEmbeddingProvider {

    private static final String MODEL = "BAAI/bge-small-en-v1.5";

    /** Verified against an independent numpy BERT forward pass over the same weights. */
    private static final double BEEF_VS_PARAPHRASE = 0.6534;
    private static final double BEEF_VS_SALAD      = 0.5332;
    private static final double BEEF_VS_PHYSICS    = 0.2919;
    private static final double TOLERANCE          = 0.005;

    private static final String BEEF =
        "Beef shin braised four hours in red wine with root vegetables until it falls from the bone.";
    private static final String SALAD =
        "Shredded unripe papaya pounded with lime, chilli and peanuts. Sharp, crunchy and raw.";
    private static final String PHYSICS = "quantum chromodynamics lattice gauge theory";
    private static final String PARAPHRASE = "a slow-cooked meat dish in wine";

    @Test
    public void providerIsDiscoverableByName() {
        assertTrue(EmbeddingProviders.availableNames().contains("onnx"),
            "onnx provider not registered; available: " + EmbeddingProviders.availableNames());
    }

    @Test
    public void missingModelIsRejectedBeforeAnyDownload() {
        EmbeddingException ex = assertThrows(EmbeddingException.class,
            () -> EmbeddingProviders.create(new EmbeddingConfig("onnx", null, null, 384)));
        assertTrue(ex.getMessage().contains("idx:model"), ex.getMessage());
    }

    @Test
    public void bgePoolsClsAndInstructsOnlyTheQuery() {
        // Pooling and prefixes are both silent-quality settings: get them wrong and
        // retrieval degrades without anything failing.
        assertEquals("cls", OnnxEmbeddingProvider.defaultPooling(MODEL));
        assertTrue(OnnxEmbeddingProvider.defaultQueryPrefix(MODEL).startsWith("Represent this sentence"));
        assertEquals("", OnnxEmbeddingProvider.defaultDocumentPrefix(MODEL));
    }

    @Test
    public void e5MeanPoolsAndPrefixesBothSides() {
        assertEquals("mean", OnnxEmbeddingProvider.defaultPooling("intfloat/e5-small-v2"));
        assertEquals("query: ", OnnxEmbeddingProvider.defaultQueryPrefix("intfloat/e5-small-v2"));
        assertEquals("passage: ", OnnxEmbeddingProvider.defaultDocumentPrefix("intfloat/e5-small-v2"));
    }

    @Test
    public void unknownModelsMeanPoolWithNoPrefixes() {
        assertEquals("mean", OnnxEmbeddingProvider.defaultPooling("sentence-transformers/all-MiniLM-L6-v2"));
        assertEquals("", OnnxEmbeddingProvider.defaultQueryPrefix("sentence-transformers/all-MiniLM-L6-v2"));
        assertEquals("", OnnxEmbeddingProvider.defaultDocumentPrefix("sentence-transformers/all-MiniLM-L6-v2"));
    }

    /**
     * The claim this provider exists to make: its vectors are the model's actual output.
     * <p>
     * These three numbers come from a reference BERT forward pass written independently in
     * numpy, reading the same {@code model.safetensors} and the same token ids. The Jlama
     * provider fails every one of them — it puts the beef stew closer to lattice gauge
     * theory (0.71) than to its own paraphrase (0.77 vs a correct 0.65 / 0.29 split), and
     * its vector for a given text has cosine -0.06 with the correct one.
     */
    @Test
    public void embeddingsMatchTheReferenceForwardPass() {
        String path = System.getProperty("jena.test.onnx.modelPath");
        if (path == null || !new File(path).isDirectory()) {
            return;     // no model on disk; see the class comment
        }
        try (EmbeddingProvider p = EmbeddingProviders.create(
                new EmbeddingConfig("onnx", MODEL, path, 384))) {
            assertEquals(384, p.dimension());

            float[] beef = p.embedDocument(BEEF);
            float[] salad = p.embedDocument(SALAD);
            float[] physics = p.embedDocument(PHYSICS);
            float[] paraphrase = p.embedDocument(PARAPHRASE);

            assertEquals(BEEF_VS_PARAPHRASE, cosine(beef, paraphrase), TOLERANCE);
            assertEquals(BEEF_VS_SALAD, cosine(beef, salad), TOLERANCE);
            assertEquals(BEEF_VS_PHYSICS, cosine(beef, physics), TOLERANCE);

            // The ordering matters more than the absolute values: an unrelated technical
            // phrase must be the most distant thing in the set, which is exactly what the
            // broken engine got wrong.
            assertTrue(cosine(beef, physics) < cosine(beef, salad),
                "an unrelated physics phrase should be further from the stew than another recipe is");
            assertTrue(cosine(beef, salad) < cosine(beef, paraphrase),
                "a paraphrase should be the nearest neighbour");
        } catch (Exception ex) {
            throw new AssertionError("ONNX provider failed with a model present", ex);
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
