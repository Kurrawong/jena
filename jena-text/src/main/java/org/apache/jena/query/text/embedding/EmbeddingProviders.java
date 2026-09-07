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

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves {@code idx:provider} names to {@link EmbeddingProvider} instances via
 * {@link ServiceLoader}.
 * <p>
 * {@link HashingEmbeddingProvider} is registered as a service like any other, so the
 * lookup has no special case for it.
 */
public class EmbeddingProviders {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingProviders.class);

    private EmbeddingProviders() {}

    /**
     * Build the provider named by {@code config}.
     *
     * @throws EmbeddingException if no factory claims the name, or the model fails to load
     */
    public static EmbeddingProvider create(EmbeddingConfig config) {
        String wanted = config.provider();
        for (EmbeddingProviderFactory factory : load()) {
            if (factory.name().equalsIgnoreCase(wanted)) {
                EmbeddingProvider provider = factory.create(config);
                log.info("Embedding provider '{}' ready: model={} dimension={}",
                    factory.name(), provider.modelId(), provider.dimension());
                return provider;
            }
        }
        throw new EmbeddingException(
            "No embedding provider named '" + wanted + "'. Available: " + availableNames()
            + ". The 'jlama' provider ships in the optional jena-text-embeddings module —"
            + " add it to the classpath to use it.");
    }

    /** Provider names visible on the current classpath, sorted, for error messages. */
    public static List<String> availableNames() {
        TreeSet<String> names = new TreeSet<>();
        for (EmbeddingProviderFactory factory : load()) {
            names.add(factory.name());
        }
        return new ArrayList<>(names);
    }

    /**
     * A broken provider jar must not take the whole index down when a working provider is
     * also present, so service-loading failures are logged and skipped rather than thrown.
     * If the skipped one was the provider actually named in the config, {@link #create}
     * still fails — with a message listing what did load, which is the more useful error.
     */
    private static List<EmbeddingProviderFactory> load() {
        List<EmbeddingProviderFactory> factories = new ArrayList<>();
        var iterator = ServiceLoader.load(EmbeddingProviderFactory.class).iterator();
        try {
            while (iterator.hasNext()) {
                factories.add(iterator.next());
            }
        } catch (ServiceConfigurationError err) {
            // Iteration cannot reliably resume past a failed provider — a retried hasNext()
            // re-throws for the same entry — so stop here and keep what already loaded.
            log.warn("Stopped loading EmbeddingProviderFactory services: {}", err.getMessage());
        }
        return factories;
    }
}
