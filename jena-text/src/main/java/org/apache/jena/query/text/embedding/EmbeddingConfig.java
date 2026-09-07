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

import java.util.Map;
import java.util.Objects;

/**
 * The parsed {@code idx:embedding} block from an index profile — everything a
 * {@link EmbeddingProviderFactory} needs to build its provider.
 *
 * @param provider  short provider name, matched against {@link EmbeddingProviderFactory#name()}
 * @param model     model identifier, interpreted by the provider (Jlama reads it as a
 *                  HuggingFace {@code owner/name})
 * @param modelPath local directory holding (or caching) model files; may be null
 * @param dimension expected vector dimension, or 0 for "whatever the model reports"
 * @param options   provider-specific extras, never null
 */
public record EmbeddingConfig(String provider,
                              String model,
                              String modelPath,
                              int dimension,
                              Map<String, String> options) {

    public EmbeddingConfig {
        provider = Objects.requireNonNull(provider, "provider");
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public EmbeddingConfig(String provider, String model, String modelPath, int dimension) {
        this(provider, model, modelPath, dimension, Map.of());
    }

    /** Provider-specific option, or {@code fallback} when unset. */
    public String option(String key, String fallback) {
        return options.getOrDefault(key, fallback);
    }
}
