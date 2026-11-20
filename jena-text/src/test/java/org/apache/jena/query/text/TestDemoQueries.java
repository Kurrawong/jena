/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jena.query.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.text.assembler.TextAssembler;
import org.apache.jena.query.text.assembler.TextVocab;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.sparql.core.assembler.AssemblerUtils;
import org.apache.jena.sparql.util.graph.GraphUtils;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs every query in {@code demo/queries/} against {@code demo/config.ttl} and compares
 * its row count with {@code demo/expected-rows.tsv}.
 * <p>
 * The demo image serves that configuration, and {@code demo/check.sh} runs the same
 * comparison against a running server. This test makes the comparison part of
 * {@code mvn test}, so a library change that alters a demo result fails here rather than
 * in the image build.
 * <p>
 * The dataset is assembled from the file, as Fuseki does: {@code ja:data} loads the
 * Turtle and {@code text:buildOnStartup} builds the in-memory index, including the review
 * children from the CSV source. The one difference is the CSV path. {@code idx:location}
 * is a string resolved against the working directory, which is {@code demo/} for the
 * server and the module directory for Surefire, so the test rewrites it to an absolute
 * path in the in-memory model.
 * <p>
 * The test fails, rather than skipping, when {@code demo/} is not found: a skipped check
 * here would leave the demo untested with a green build.
 */
public class TestDemoQueries {

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    private static final Property LOCATION =
        org.apache.jena.rdf.model.ResourceFactory.createProperty("urn:jena:lucene:index#location");

    private static Path demoDir;
    private static Dataset dataset;

    /** Walk up from the working directory to the repository's {@code demo/}. */
    private static Path findDemoDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("demo");
            if (Files.isRegularFile(candidate.resolve("config.ttl"))
                    && Files.isRegularFile(candidate.resolve("expected-rows.tsv"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    @BeforeAll
    static void assembleDemo() {
        demoDir = findDemoDir();
        assertNotNull(demoDir, "demo/config.ttl not found above " + Paths.get("").toAbsolutePath());

        Model spec = AssemblerUtils.readAssemblerFile(demoDir.resolve("config.ttl").toString());
        List<Statement> locations = spec.listStatements(null, LOCATION, (RDFNode) null).toList();
        assertTrue(!locations.isEmpty(), "demo/config.ttl has no idx:location; update this test");
        for (Statement stmt : locations) {
            Path resolved = demoDir.resolve(stmt.getString()).toAbsolutePath();
            assertTrue(Files.isRegularFile(resolved), "idx:location not found: " + resolved);
            spec.remove(stmt);
            spec.add(stmt.getSubject(), LOCATION, resolved.toString());
        }

        Resource root = GraphUtils.findRootByType(spec, TextVocab.textDataset);
        dataset = (Dataset) Assembler.general().open(root);
    }

    @AfterAll
    static void close() {
        if (dataset != null) {
            dataset.close();
        }
    }

    /** {@code expected-rows.tsv} as query name to row count, in file order. */
    private static Map<String, Integer> expectedRows() throws IOException {
        Map<String, Integer> expected = new LinkedHashMap<>();
        for (String line : Files.readAllLines(demoDir.resolve("expected-rows.tsv"), StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            assertEquals(2, parts.length, "malformed line in expected-rows.tsv: " + line);
            expected.put(parts[0], Integer.parseInt(parts[1]));
        }
        return expected;
    }

    private static int rowCount(Path queryFile) throws IOException {
        Query query = QueryFactory.create(Files.readString(queryFile, StandardCharsets.UTF_8));
        dataset.begin(org.apache.jena.query.ReadWrite.READ);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet rs = qexec.execSelect();
            int rows = 0;
            while (rs.hasNext()) {
                rs.next();
                rows++;
            }
            return rows;
        } finally {
            dataset.end();
        }
    }

    @TestFactory
    Stream<DynamicTest> everyQueryReturnsItsExpectedRows() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<String, Integer> e : expectedRows().entrySet()) {
            Path file = demoDir.resolve("queries").resolve(e.getKey() + ".rq");
            tests.add(DynamicTest.dynamicTest(e.getKey(), () -> {
                assertTrue(Files.isRegularFile(file), "no query file " + file);
                assertEquals(e.getValue().intValue(), rowCount(file), e.getKey() + " row count");
            }));
        }
        return tests.stream();
    }

    /** A query missing from expected-rows.tsv would never be checked. */
    @Test
    public void everyQueryIsListed() throws IOException {
        TreeSet<String> unlisted = new TreeSet<>();
        try (Stream<Path> files = Files.list(demoDir.resolve("queries"))) {
            files.map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".rq"))
                .map(n -> n.substring(0, n.length() - ".rq".length()))
                .forEach(unlisted::add);
        }
        unlisted.removeAll(expectedRows().keySet());
        assertTrue(unlisted.isEmpty(), "not listed in demo/expected-rows.tsv: " + unlisted);
    }
}
