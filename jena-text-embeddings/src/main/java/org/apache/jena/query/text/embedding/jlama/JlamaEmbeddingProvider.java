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

import java.io.File;
import java.io.IOException;

import org.apache.jena.query.text.embedding.EmbeddingConfig;
import org.apache.jena.query.text.embedding.EmbeddingException;
import org.apache.jena.query.text.embedding.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.ModelSupport;
import com.github.tjake.jlama.model.functions.Generator;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.util.Downloader;

/**
 * {@link EmbeddingProvider} backed by <a href="https://github.com/tjake/Jlama">Jlama</a>.
 * <p>
 * Jlama is pure Java — no native libraries — which is why it was chosen here: it keeps the
 * UBI images clean and makes arm64 free. The cost is that it <b>requires the Vector API</b>
 * ({@code --add-modules jdk.incubator.vector}); without it Jlama is unusably slow, and
 * HNSW graph construction during bulk indexing is dominated by exactly that arithmetic.
 * <p>
 * <b>Jlama loads encoder-only BERT models.</b> That rules out the current generation of
 * embedding leaders — Qwen3-Embedding (decoder), EmbeddingGemma (Gemma3 with bidirectional
 * attention), and anything ModernBERT-based such as granite-embedding-r2. Within BERT the
 * practical choices are {@code BAAI/bge-small-en-v1.5} (the default this fork documents),
 * {@code Snowflake/snowflake-arctic-embed-s} and {@code intfloat/e5-small-v2}.
 * <p>
 * <b>Asymmetric models need prefixes.</b> BGE wants a retrieval instruction on the query
 * and nothing on the document; E5 wants {@code "query: "} and {@code "passage: "}. Getting
 * this wrong degrades results without failing, so the prefixes are configuration
 * ({@code queryPrefix} / {@code documentPrefix} options) with defaults inferred from the
 * model name.
 */
public class JlamaEmbeddingProvider implements EmbeddingProvider {
    private static final Logger log = LoggerFactory.getLogger(JlamaEmbeddingProvider.class);

    /** Where models are cached when {@code idx:modelPath} is not set. */
    private static final String DEFAULT_MODEL_CACHE = "models";

    private final AbstractModel model;
    private final String modelId;
    private final int dimension;
    private final String queryPrefix;
    private final String documentPrefix;
    private final Generator.PoolingType poolingType;

    JlamaEmbeddingProvider(EmbeddingConfig config) {
        this.modelId = config.model();
        if (modelId == null || modelId.isBlank()) {
            throw new EmbeddingException("The 'jlama' embedding provider requires idx:model, "
                + "a HuggingFace identifier such as \"BAAI/bge-small-en-v1.5\".");
        }

        String modelPath = config.modelPath() != null ? config.modelPath() : DEFAULT_MODEL_CACHE;
        File modelDir = resolveModel(modelPath, modelId);

        DType workingMemoryType = dtype(config.option("workingMemoryType", "F32"));
        DType workingQuantizationType = dtype(config.option("workingQuantizationType", "I8"));

        try {
            this.model = ModelSupport.loadEmbeddingModel(modelDir, workingMemoryType, workingQuantizationType);
        } catch (RuntimeException ex) {
            throw new EmbeddingException("Failed to load embedding model '" + modelId + "' from "
                + modelDir + ". Jlama supports encoder-only BERT models; decoder-based models "
                + "(Qwen3-Embedding), Gemma3-based models (EmbeddingGemma) and ModernBERT-based "
                + "models are not loadable by this provider.", ex);
        }

        this.dimension = model.getConfig().embeddingLength;
        this.poolingType = poolingType(config.option("pooling", "MODEL"));
        this.queryPrefix = config.option("queryPrefix", defaultQueryPrefix(modelId));
        this.documentPrefix = config.option("documentPrefix", defaultDocumentPrefix(modelId));

        // Cold start costs seconds. Pay it here, during assembler initialisation, rather
        // than in whichever user query happens to arrive first.
        warmUp();

        log.info("Jlama embedding provider ready: model={} dimension={} pooling={}",
            modelId, dimension, poolingType);
    }

    /**
     * Find the model on disk, downloading it from HuggingFace into the cache directory if
     * it is not already there.
     * <p>
     * A pre-populated directory is the intended production shape — the design note
     * describes baking the model into a derived image — so a present directory is used as
     * is and no network call is made. The download path exists for local development.
     */
    private static File resolveModel(String modelPath, String modelId) {
        File local = new File(modelPath);
        File direct = new File(local, modelId.replace('/', '_'));
        if (direct.isDirectory()) {
            return direct;
        }
        if (local.isDirectory() && new File(local, "config.json").isFile()) {
            // idx:modelPath points straight at an unpacked model rather than at a cache root.
            return local;
        }
        try {
            log.info("Model '{}' not present under {}; downloading from HuggingFace", modelId, modelPath);
            return new Downloader(modelPath, modelId).huggingFaceModel();
        } catch (IOException ex) {
            throw new EmbeddingException("Could not obtain model '" + modelId + "' under '" + modelPath
                + "'. Either pre-populate that directory (the recommended deployment: bake the model "
                + "into a derived image) or allow network access to huggingface.co.", ex);
        }
    }

    private void warmUp() {
        try {
            float[] probe = model.embed("warm up", poolingType);
            if (probe.length != dimension) {
                throw new EmbeddingException("Model '" + modelId + "' reports embeddingLength "
                    + dimension + " but produced a " + probe.length + "-dimensional vector");
            }
        } catch (EmbeddingException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new EmbeddingException("Model '" + modelId + "' loaded but could not embed text. "
                + "If this is a slow or failing vector operation, check that the JVM was started "
                + "with --add-modules jdk.incubator.vector.", ex);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public float[] embedDocument(String text) {
        return embed(documentPrefix + text);
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(queryPrefix + text);
    }

    private float[] embed(String text) {
        try {
            return model.embed(text, poolingType);
        } catch (RuntimeException ex) {
            throw new EmbeddingException("Embedding failed for model '" + modelId + "'", ex);
        }
    }

    @Override
    public void close() {
        try {
            model.close();
        } catch (Exception ex) {
            log.warn("Failed to close Jlama model '{}': {}", modelId, ex.getMessage());
        }
    }

    /**
     * The retrieval instruction BGE expects on queries and not on documents. Asymmetry is
     * the model's, not ours — using the same text on both sides measurably degrades
     * retrieval, and silently.
     */
    static String defaultQueryPrefix(String modelId) {
        String id = modelId.toLowerCase(java.util.Locale.ROOT);
        if (id.contains("bge-")) {
            return "Represent this sentence for searching relevant passages: ";
        }
        if (id.contains("e5-")) {
            return "query: ";
        }
        return "";
    }

    static String defaultDocumentPrefix(String modelId) {
        String id = modelId.toLowerCase(java.util.Locale.ROOT);
        if (id.contains("e5-")) {
            return "passage: ";
        }
        return "";
    }

    private static DType dtype(String name) {
        try {
            return DType.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new EmbeddingException("Unknown Jlama dtype '" + name + "'", ex);
        }
    }

    private static Generator.PoolingType poolingType(String name) {
        try {
            return Generator.PoolingType.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new EmbeddingException("Unknown Jlama pooling type '" + name
                + "'; expected one of MODEL, AVG, MAX, SUM", ex);
        }
    }
}
