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
 * Builds an {@link EmbeddingProvider} from configuration. Discovered with
 * {@link java.util.ServiceLoader}, so an engine is added by putting a jar on the
 * classpath rather than by naming a class in the assembler.
 * <p>
 * That indirection is the point: {@code idx:provider "jlama"} in a config file stays
 * valid whether or not the Jlama module is deployed, and the failure when it is absent
 * is a startup error naming the providers that <em>are</em> available — not a
 * {@code ClassNotFoundException} from a reflective lookup of a fully-qualified name
 * someone typed into RDF.
 *
 * @see EmbeddingProviders
 */
public interface EmbeddingProviderFactory {

    /**
     * Short name matched against {@code idx:provider}, e.g. {@code "jlama"}.
     * Compared case-insensitively.
     */
    String name();

    /**
     * Construct the provider. Implementations should load the model eagerly here rather
     * than on first use: a cold start costs seconds, and paying it during assembler
     * initialisation puts it in server startup where it belongs, instead of in whichever
     * user query happens to arrive first.
     *
     * @throws EmbeddingException if the model cannot be loaded
     */
    EmbeddingProvider create(EmbeddingConfig config);
}
