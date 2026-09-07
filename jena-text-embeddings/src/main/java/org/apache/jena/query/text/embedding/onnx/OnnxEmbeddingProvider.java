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

import java.io.File;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.jena.query.text.embedding.EmbeddingConfig;
import org.apache.jena.query.text.embedding.EmbeddingException;
import org.apache.jena.query.text.embedding.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.tjake.jlama.model.bert.BertTokenizer;
import com.github.tjake.jlama.safetensors.tokenizer.Tokenizer;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * {@link EmbeddingProvider} running an ONNX export of a sentence-embedding model through
 * ONNX Runtime.
 *
 * <h3>Why this exists alongside the Jlama provider</h3>
 *
 * The Jlama provider loads {@code BAAI/bge-small-en-v1.5} without error and returns
 * 384-dimensional vectors that are <b>not the model's output</b>. Measured against a
 * reference BERT forward pass over the same {@code model.safetensors} and the same token
 * ids, Jlama's vector for a given text has cosine <b>-0.06</b> with the correct one under
 * its default {@code MODEL} pooling, and 0.27 at best across its four pooling modes. Every
 * document ends up closer to an unrelated physics phrase than to a related document, so
 * retrieval is noise. Nothing throws: it is the "confident garbage" failure the
 * configuration docs warn about for a model mismatch, arriving through the engine instead.
 *
 * This provider produces vectors matching that same reference to four decimal places, and
 * does so roughly an order of magnitude faster, with no {@code jdk.incubator.vector}
 * requirement.
 *
 * <h3>The tokenizer is Jlama's</h3>
 *
 * Deliberately. Jlama's {@code BertTokenizer} was verified correct — proper WordPiece with
 * {@code [CLS]}/{@code [SEP]} and the right vocabulary ids — and it is pure Java, so
 * reusing it avoids adding a second native dependency for tokenisation alone. Only the
 * forward pass moves to ONNX Runtime. If the Jlama dependency is ever dropped, this is the
 * piece that needs replacing.
 *
 * <h3>Pooling is per-model and silently wrong if guessed</h3>
 *
 * BGE pools the {@code [CLS]} token; E5 and the MiniLM family average over tokens. Picking
 * the wrong one degrades retrieval without failing, so it is inferred from the model name
 * and overridable with the {@code pooling} option.
 */
public class OnnxEmbeddingProvider implements EmbeddingProvider {
    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingProvider.class);

    /** BERT's own limit; longer input is truncated rather than rejected. */
    private static final int MAX_TOKENS = 512;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final Tokenizer tokenizer;
    private final String modelId;
    private final int dimension;
    private final boolean clsPooling;
    private final boolean hasTokenTypeIds;
    private final String queryPrefix;
    private final String documentPrefix;

    OnnxEmbeddingProvider(EmbeddingConfig config) {
        this.modelId = config.model();
        if (modelId == null || modelId.isBlank()) {
            throw new EmbeddingException("The 'onnx' embedding provider requires idx:model, "
                + "a HuggingFace identifier such as \"BAAI/bge-small-en-v1.5\".");
        }

        String modelPath = config.modelPath() != null ? config.modelPath() : "models";
        File dir = OnnxModelFiles.resolve(modelPath, modelId);

        try {
            this.env = OrtEnvironment.getEnvironment();
            this.session = env.createSession(new File(dir, OnnxModelFiles.ONNX_FILE).getAbsolutePath(),
                new OrtSession.SessionOptions());
        } catch (Exception ex) {
            throw new EmbeddingException("Could not open the ONNX model for '" + modelId
                + "' in " + dir + ". Expected " + OnnxModelFiles.ONNX_FILE + " alongside the "
                + "tokenizer files.", ex);
        }

        this.hasTokenTypeIds = session.getInputNames().contains("token_type_ids");
        try {
            this.tokenizer = new BertTokenizer(Path.of(dir.getAbsolutePath()));
        } catch (Exception ex) {
            throw new EmbeddingException("Could not load the tokenizer for '" + modelId
                + "' from " + dir + "; tokenizer.json and tokenizer_config.json must be present.", ex);
        }

        this.clsPooling = "cls".equalsIgnoreCase(config.option("pooling", defaultPooling(modelId)));
        this.queryPrefix = config.option("queryPrefix", defaultQueryPrefix(modelId));
        this.documentPrefix = config.option("documentPrefix", defaultDocumentPrefix(modelId));

        // Pay the cold start here, during assembler initialisation, rather than in
        // whichever user query happens to arrive first — and learn the dimension from the
        // model rather than trusting the config.
        this.dimension = embed("warm up").length;

        log.info("ONNX embedding provider ready: model={} dimension={} pooling={}",
            modelId, dimension, clsPooling ? "cls" : "mean");
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
        long[] ids = tokenizer.encode(text);
        if (ids.length > MAX_TOKENS) {
            ids = Arrays.copyOf(ids, MAX_TOKENS);
        }
        int n = ids.length;
        long[] mask = new long[n];
        Arrays.fill(mask, 1L);
        long[] shape = { 1, n };

        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape));
            if (hasTokenTypeIds) {
                inputs.put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(new long[n]), shape));
            }
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] last = (float[][][]) result.get(0).getValue();
                return normalise(clsPooling ? last[0][0] : mean(last[0]));
            }
        } catch (Exception ex) {
            throw new EmbeddingException("Embedding failed for model '" + modelId + "'", ex);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    private static float[] mean(float[][] tokens) {
        // Every token is real: the input is a single unpadded sequence, so there is no
        // attention mask to weight by here.
        float[] out = new float[tokens[0].length];
        for (float[] token : tokens) {
            for (int i = 0; i < out.length; i++) out[i] += token[i];
        }
        for (int i = 0; i < out.length; i++) out[i] /= tokens.length;
        return out;
    }

    /** These models are trained for cosine similarity on unit vectors. */
    private static float[] normalise(float[] v) {
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm == 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    /** BGE pools CLS; E5 and the MiniLM/sentence-transformers family average. */
    static String defaultPooling(String modelId) {
        return modelId.toLowerCase(Locale.ROOT).contains("bge-") ? "cls" : "mean";
    }

    /**
     * The retrieval instruction BGE expects on queries and not on documents. Asymmetry is
     * the model's, not ours — using the same text on both sides degrades retrieval, and
     * silently.
     */
    static String defaultQueryPrefix(String modelId) {
        String id = modelId.toLowerCase(Locale.ROOT);
        if (id.contains("bge-")) return "Represent this sentence for searching relevant passages: ";
        if (id.contains("e5-")) return "query: ";
        return "";
    }

    static String defaultDocumentPrefix(String modelId) {
        return modelId.toLowerCase(Locale.ROOT).contains("e5-") ? "passage: " : "";
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ex) {
            log.warn("Failed to close the ONNX session for '{}': {}", modelId, ex.getMessage());
        }
    }
}
