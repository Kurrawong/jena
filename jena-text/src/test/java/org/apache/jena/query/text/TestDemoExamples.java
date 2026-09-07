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

package org.apache.jena.query.text;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the demo's own saved searches against the demo's own configuration and data.
 * <p>
 * The demo used to be covered only by {@link TestDemoDataParsing}, which asserts that its
 * Turtle parses and nothing else, and by {@code TestDemoMiningScenarios}, which builds a
 * hand-written replica of the configuration in Java and never reads a demo file. So the
 * demo's config, its data and its examples could drift apart indefinitely without failing
 * a build, and they did: the nested identifier examples pointed at 207 boreholes of which
 * seven carried an identifier, and an author example filtered on a name held by two of
 * five hundred records.
 * <p>
 * This closes that gap by using the real files:
 * <ul>
 *   <li>{@code demo/test/config.ttl}, parsed by the production assembler</li>
 *   <li>{@code demo/test/data/*.ttl}, loaded whole</li>
 *   <li>{@code demo/test/tests.json}, the same saved searches the demo UI offers</li>
 * </ul>
 * Indexing goes through {@link ShaclBulkIndexer}, which is what {@code task index} runs,
 * so shapes declaring an {@code idx:externalSource} are covered here exactly as they are
 * in the demo. The change-listener path cannot build those.
 * <p>
 * Two assertions per example, and the second is the one that matters. An example must
 * return at least its declared {@code minResults}, and a declared filter must actually
 * change the result set. A filter that quietly does nothing passes a row-count floor,
 * because an unfiltered query returns more rows, not fewer. Three demo bugs presented
 * exactly that way.
 * <p>
 * The browser's own pipeline is checked separately by {@code demo/testing/check-examples.mjs},
 * which this cannot reach: URL encoding and the parse/rebuild round trip live in JavaScript.
 */
public class TestDemoExamples {

    private static final int LIMIT = 500;

    private static Path demoTestDir;
    private static ShaclTextIndexLucene textIndex;
    private static int baselineRows;
    private static JsonArray savedSearches;
    private static ShaclIndexMapping shaclMapping;

    /** Walk up from the working directory to the repository root, then into {@code demo/test}. */
    private static Path findDemoTestDir() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("demo/test");
            if (Files.isDirectory(candidate) && Files.isRegularFile(candidate.resolve("config.ttl"))) {
                return candidate;
            }
        }
        return null;
    }

    @BeforeAll
    static void buildIndexFromDemoConfig() throws IOException {
        demoTestDir = findDemoTestDir();
        assumeTrue(demoTestDir != null, "demo/test not found; skipping");

        Model config = RDFDataMgr.loadModel(demoTestDir.resolve("config.ttl").toUri().toString());

        // text:shapes is an RDF list on the index resource. Read it from the config rather
        // than naming the shapes here, so adding a shape to the demo is picked up.
        Resource shapesList = null;
        var it = config.listObjectsOfProperty(
            config.createProperty("http://jena.apache.org/text#shapes"));
        while (it.hasNext()) {
            RDFNode node = it.next();
            if (node.isResource()) {
                shapesList = node.asResource();
                break;
            }
        }
        assertNotNull(shapesList, "config.ttl declares no text:shapes list");

        // idx:location and idx:delta are written relative to demo/test, which is where
        // `task index` runs. Surefire's working directory is the module, and NIO fixes the
        // default directory at JVM start, so setting user.dir does not help. Rewrite them
        // to absolute paths in the in-memory model; the file on disk is untouched.
        makeExternalSourcePathsAbsolute(config, demoTestDir);

        ShaclIndexMapping mapping =
            ShaclIndexAssembler.parseShapes(Assembler.general(), shapesList.as(RDFList.class));

        shaclMapping = mapping;

        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);
        TextIndexConfig indexConfig = new TextIndexConfig(defn);
        indexConfig.setShaclMapping(mapping);
        indexConfig.setValueStored(false);

        textIndex = new ShaclTextIndexLucene(
            new ByteBuffersDirectory(), new ByteBuffersDirectory(), indexConfig);

        DatasetGraph base = DatasetGraphFactory.createTxnMem();
        Path dataDir = demoTestDir.resolve("data");
        try (var files = Files.list(dataDir)) {
            List<Path> turtle = new ArrayList<>(files
                .filter(f -> f.getFileName().toString().endsWith(".ttl"))
                .sorted()
                .toList());
            assertFalse(turtle.isEmpty(), "no Turtle in " + dataDir);
            base.begin(org.apache.jena.query.ReadWrite.WRITE);
            try {
                for (Path f : turtle) {
                    RDFDataMgr.read(base.getDefaultGraph(), f.toUri().toString());
                }
                base.commit();
            } finally {
                base.end();
            }
        }

        // The same path `task index` runs, so external-source shapes are built too.
        ShaclBulkIndexer indexer = new ShaclBulkIndexer(base, textIndex, mapping);
        indexer.setFreshIndex(true);
        indexer.index();

        savedSearches = JSON.parse(
            "{\"t\":" + Files.readString(demoTestDir.resolve("tests.json"), StandardCharsets.UTF_8) + "}")
            .get("t").getAsArray();

        baselineRows = rowCount("*", null);
        assertTrue(baselineRows > 0, "the demo index came out empty");
    }

    /** Resolve every {@code idx:location} and {@code idx:delta} against {@code baseDir}. */
    private static void makeExternalSourcePathsAbsolute(Model config, Path baseDir) {
        for (String prop : new String[] { "location", "delta" }) {
            var p = config.createProperty("urn:jena:lucene:index#" + prop);
            List<org.apache.jena.rdf.model.Statement> found =
                config.listStatements(null, p, (RDFNode) null).toList();
            for (var stmt : found) {
                if (!stmt.getObject().isLiteral()) continue;
                String value = stmt.getObject().asLiteral().getString();
                Path resolved = baseDir.resolve(value);
                if (!Files.isRegularFile(resolved)) continue;
                config.remove(stmt);
                config.add(stmt.getSubject(), p, resolved.toAbsolutePath().toString());
            }
        }
    }

    /**
     * Rewrite bare field names in a saved search's filter to canonical field IRIs.
     * <p>
     * {@code tests.json} is written in the browser app's dialect, where a filter may name
     * a field either way, and {@code buildCqlFilter} resolves the bare form against the
     * configuration before sending. The engine does not: an unresolvable property makes
     * the clause a residual, and residuals are discarded, so the filter silently vanishes
     * and the query returns everything. Doing the same resolution here tests the saved
     * searches as the app actually issues them.
     */
    private static String resolvePropertyNames(String filterJson) {
        JsonValue parsed = JSON.parse(filterJson);
        rewriteProperties(parsed);
        return parsed.toString();
    }

    private static void rewriteProperties(JsonValue node) {
        if (node.isArray()) {
            for (JsonValue child : node.getAsArray()) rewriteProperties(child);
            return;
        }
        if (!node.isObject()) return;
        JsonObject obj = node.getAsObject();
        for (String key : new ArrayList<>(obj.keys())) {
            JsonValue value = obj.get(key);
            if ("property".equals(key) && value.isString()) {
                String prop = value.getAsString().value();
                if (shaclMapping.findField(prop) == null) {
                    ShaclIndexMapping.FieldDef byName = shaclMapping.findFieldByName(prop);
                    if (byName != null && byName.getFieldIRI() != null) {
                        obj.put(key, byName.getFieldIRI().getURI());
                    }
                }
            } else {
                rewriteProperties(value);
            }
        }
    }

    private static int rowCount(String term, CqlExpression filter) {
        List<TextHit> hits = textIndex.queryWithCql(
            null, term, filter, null, null, null, LIMIT, null);
        return hits.size();
    }

    /** Read one URL query parameter out of a saved search's {@code params} string. */
    private static String param(String params, String name) {
        String qs = params.startsWith("?") ? params.substring(1) : params;
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (!pair.substring(0, eq).equals(name)) continue;
            String raw = pair.substring(eq + 1);
            try {
                return URLDecoder.decode(raw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return raw;   // written unescaped in tests.json, which is legal here
            }
        }
        return null;
    }

    @TestFactory
    List<DynamicTest> everySavedSearchStillWorks() {
        List<DynamicTest> tests = new ArrayList<>();
        String group = "";

        for (JsonValue entry : savedSearches) {
            JsonObject obj = entry.getAsObject();
            if (obj.hasKey("group")) {
                group = obj.get("group").getAsString().value();
                continue;
            }
            if (!obj.hasKey("label")) continue;

            final String label = obj.get("label").getAsString().value();
            final String params = obj.hasKey("params") ? obj.get("params").getAsString().value() : "";
            final int minResults = obj.hasKey("minResults")
                ? obj.get("minResults").getAsNumber().value().intValue() : 0;
            final String name = group + " / " + label;

            tests.add(DynamicTest.dynamicTest(name, () -> {
                String filterJson = param(params, "filter");
                String q = param(params, "q");
                String term = (q == null || q.isBlank()) ? "*" : q.replace('+', ' ');

                CqlExpression filter = null;
                if (filterJson != null && !filterJson.isBlank()) {
                    filter = CqlParser.parse(resolvePropertyNames(filterJson));
                    assertNotNull(filter, name + ": filter did not parse");
                }

                int rows = rowCount(term, filter);
                assertTrue(rows >= minResults,
                    name + ": got " + rows + " rows, minResults is " + minResults);

                // The check that catches a filter which quietly does nothing.
                if (filter != null) {
                    assertTrue(rows != baselineRows,
                        name + ": filter has no effect, still the unfiltered " + rows + " rows");
                }
            }));
        }

        assertFalse(tests.isEmpty(), "tests.json produced no saved searches");
        return tests;
    }
}
