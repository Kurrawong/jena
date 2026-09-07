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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.text.assembler.TextAssembler;
import org.apache.jena.query.text.assembler.TextVocab;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.assembler.AssemblerUtils;
import org.apache.jena.sparql.util.graph.GraphUtils;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code demo/deploy/config.ttl} — the configuration the published demo image runs.
 * <p>
 * It has no database, no prebuilt index and no indexing step: an in-memory dataset from
 * {@code ja:data} and an in-memory index built by {@code text:buildOnStartup}. Every one
 * of those pieces can fail by producing an empty index rather than an error — a server
 * that starts cleanly and answers every search with nothing — so what is asserted here
 * is content, not assembly.
 * <p>
 * It is also a derivative of {@code demo/test/config.ttl}, kept in step by hand. The
 * field-set comparison below is the guard on that: a field added to the working
 * configuration and forgotten here would otherwise surface as one facet quietly missing
 * from the deployed app.
 */
public class TestDemoDeployConfig {

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    /** The four fields fed by the assay CSV, which the deployed demo leaves out. */
    private static final Set<String> EXTERNAL_ONLY_FIELDS =
        Set.of("analyte", "grade", "gradeUnits", "belowDetection");

    private static final Property FIELD_NAME =
        ResourceFactory.createProperty("urn:jena:lucene:index#fieldName");

    private Dataset dataset;

    @AfterEach
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
            dataset = null;
        }
    }

    /** Walk up from the working directory; null when run from outside the repository. */
    private static Path demoDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("demo");
            if (Files.isDirectory(candidate.resolve("deploy"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static Path deployConfig() {
        Path demo = demoDir();
        assumeTrue(demo != null, "demo/ not found from the working directory");
        Path config = demo.resolve("deploy/config.ttl");
        assumeTrue(Files.exists(config), "demo/deploy/config.ttl not found");
        return config;
    }

    /**
     * Assemble the deployed configuration exactly as Fuseki does — from the file, so the
     * {@code <../test/data/mining.ttl>} references resolve against the config's own
     * location and not the working directory.
     */
    private Dataset assembleDeployed() {
        Model spec = AssemblerUtils.readAssemblerFile(deployConfig().toString());
        Resource root = GraphUtils.findRootByType(spec, TextVocab.textDataset);
        return (Dataset) Assembler.general().open(root);
    }

    private static Set<String> fieldNames(Path configFile) {
        Model model = RDFDataMgr.loadModel(configFile.toString());
        Set<String> names = new TreeSet<>();
        StmtIterator it = model.listStatements(null, FIELD_NAME, (RDFNode) null);
        while (it.hasNext()) {
            names.add(it.nextStatement().getObject().asLiteral().getString());
        }
        return names;
    }

    @Test
    public void deployedConfigServesAPopulatedIndex() {
        dataset = assembleDeployed();
        TextIndex index = ((DatasetGraphText) dataset.asDatasetGraph()).getTextIndex();

        List<TextHit> hits = index.query(RDFS.label.asNode(), "copper", null, null, 1000);
        assertFalse(hits.isEmpty(),
            "the demo image would start clean and answer every search with nothing");
    }

    /** Facet counts need the taxonomy, which follows text:directory "mem" into memory. */
    @Test
    public void deployedConfigProducesFacetCounts() {
        dataset = assembleDeployed();
        ShaclTextIndexLucene index =
            (ShaclTextIndexLucene) ((DatasetGraphText) dataset.asDatasetGraph()).getTextIndex();

        Map<String, List<FacetValue>> facets = index.getFacetCounts(List.of("state"), 20);
        List<FacetValue> states = facets.get("state");
        assertTrue(states != null && !states.isEmpty(), "no counts for the state facet");
        assertTrue(states.get(0).getCount() > 0, "state facet counted nothing");
    }

    /**
     * The same facet counts through SPARQL, which is how the image's smoke test and the
     * app itself ask for them.
     * <p>
     * {@link #deployedConfigProducesFacetCounts()} calls the index API directly and so
     * says nothing about the property function's argument list. A {@code luc:facet} call
     * with the wrong arity, or with {@code facetFields} written as an RDF list rather
     * than a JSON array in a string literal, fails only here — and in the image build,
     * eight minutes later.
     */
    @Test
    public void deployedConfigProducesFacetCountsThroughSparql() {
        dataset = assembleDeployed();

        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?field ?value ?count WHERE {\n"
            + "  (?field ?value ?low ?high ?count) luc:facet (\n"
            + "    \"default\" \"default\" \"*\"\n"
            + "    '[\"urn:jena:lucene:field#state\"]'\n"
            + "    \"\" 20 0)\n"
            + "}";

        dataset.begin(org.apache.jena.query.ReadWrite.READ);
        try (QueryExecution qexec = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qexec.execSelect();
            int rows = 0;
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                assertTrue(qs.getLiteral("count").getInt() > 0,
                    "a facet row counted nothing");
                rows++;
            }
            assertTrue(rows > 0, "luc:facet returned no rows for the state facet");
        } finally {
            dataset.end();
        }
    }

    /**
     * Which shape each graph-driven nested block hangs off, keyed by its join path.
     * <p>
     * Blocks with no {@code idx:joinPath} are fed by an {@code idx:externalSource} and
     * keyed by {@code idx:nestedName} instead; the assay block is the only one, and the
     * deployed configuration leaves it out on purpose, so it is not comparable.
     */
    private static Map<String, String> nestedJoinPathsByShape(Path configFile) {
        Model model = RDFDataMgr.loadModel(configFile.toString());
        Property nested = ResourceFactory.createProperty("urn:jena:lucene:index#nested");
        Property joinPath = ResourceFactory.createProperty("urn:jena:lucene:index#joinPath");
        Map<String, String> byJoinPath = new java.util.TreeMap<>();
        StmtIterator it = model.listStatements(null, nested, (RDFNode) null);
        while (it.hasNext()) {
            var stmt = it.nextStatement();
            Resource block = stmt.getObject().asResource();
            if (!block.hasProperty(joinPath)) {
                continue;
            }
            byJoinPath.put(block.getProperty(joinPath).getObject().toString(),
                stmt.getSubject().getLocalName());
        }
        return byJoinPath;
    }

    /**
     * A nested block has to hang off the same shape in both configurations.
     * <p>
     * {@link #deployedConfigIndexesTheSameFieldsAsTheWorkingOne()} compares field names
     * only, and a nested block moving from one shape to another changes no field name at
     * all. That is exactly what happened: the correlated identifier records moved from
     * BoreholeShape to MiningReportShape in the working configuration, the deployed copy
     * kept indexing them against boreholes while the data attached them to reports, and
     * six of the app's examples silently returned nothing. The field-name comparison
     * passed throughout, and only the image's example replay caught it.
     */
    @Test
    public void deployedConfigHangsNestedBlocksOffTheSameShapes() {
        Path demo = demoDir();
        assumeTrue(demo != null, "demo/ not found from the working directory");

        Map<String, String> working = nestedJoinPathsByShape(demo.resolve("test/config.ttl"));
        Map<String, String> deployed = nestedJoinPathsByShape(demo.resolve("deploy/config.ttl"));

        assertFalse(working.isEmpty(), "no nested blocks found; update this test");
        for (Map.Entry<String, String> e : working.entrySet()) {
            String shape = deployed.get(e.getKey());
            assertEquals(e.getValue(), shape,
                "nested block " + e.getKey() + " hangs off a different shape in "
                    + "demo/deploy/config.ttl");
        }
        assertEquals(working, deployed, "demo/deploy/config.ttl nested blocks have drifted");
    }

    /**
     * The deployed configuration indexes the same fields as the working one, less the
     * four the assay CSV feeds.
     */
    @Test
    public void deployedConfigIndexesTheSameFieldsAsTheWorkingOne() {
        Path demo = demoDir();
        assumeTrue(demo != null, "demo/ not found from the working directory");

        Set<String> working = new TreeSet<>(fieldNames(demo.resolve("test/config.ttl")));
        Set<String> deployed = fieldNames(demo.resolve("deploy/config.ttl"));

        assertTrue(working.containsAll(EXTERNAL_ONLY_FIELDS),
            "the working config no longer defines the assay fields; update this test");
        working.removeAll(EXTERNAL_ONLY_FIELDS);
        assertEquals(working, deployed,
            "demo/deploy/config.ttl has drifted from demo/test/config.ttl");
    }
}
