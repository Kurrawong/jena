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

package org.apache.jena.sparql;

import java.util.stream.Stream;

import org.junit.jupiter.api.*;

import org.apache.jena.arq.TestConsts;
import org.apache.jena.arq.junit.Scripts;
import org.apache.jena.sparql.engine.ref.QueryEngineRef;
import org.apache.jena.sparql.expr.E_Function;
import org.apache.jena.sparql.expr.NodeValue;

public class Scripts_RefEngine {

    @BeforeAll
    public static void beforeClass() {
        NodeValue.VerboseWarnings = false;
        E_Function.WarnOnUnknownFunction = false;
        QueryEngineRef.register();
    }

    @AfterAll
    public static void afterClass() {
        NodeValue.VerboseWarnings = true;
        E_Function.WarnOnUnknownFunction = true;
        QueryEngineRef.unregister();
    }

    @TestFactory
    @DisplayName("RefEngine - ARQ-SPARQL")
    public Stream<DynamicNode> testFactoryRefARQ() {
        return Scripts.manifestTestFactorySPARQL(TestConsts.testDirARQ+"manifest-ref-arq.ttl");
    }


    @TestFactory
    @DisplayName("RefEngine - SPARQL 1.0")
    public Stream<DynamicNode> testFactoryRefSPARQL10() {
        return Scripts.manifestTestFactorySPARQL(TestConsts.SPARQL10_TESTS_DIR+"manifest-evaluation.ttl");
    }

    @TestFactory
    @DisplayName("RefEngine - SPARQL 1.1")
    public Stream<DynamicNode> testFactoryRefSPARQL11() {
        return Scripts.manifestTestFactorySPARQL(TestConsts.SPARQL11_TESTS_DIR+"manifest-sparql11-query.ttl");
    }

    @TestFactory
    @DisplayName("RefEngine - SPARQL 1.2")
    public Stream<DynamicNode> testFactoryRefSPARQL12() {
        return Scripts.manifestTestFactorySPARQL(TestConsts.SPARQL12_TESTS_DIR+"manifest.ttl");
    }
}
