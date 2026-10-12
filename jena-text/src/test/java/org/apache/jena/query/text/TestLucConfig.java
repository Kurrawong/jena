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

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.AssemblerHelp;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code luc:config} — the configuration of a SHACL index, as triples, through the query
 * endpoint.
 * <p>
 * What is returned is the configuration as written: every triple reachable from the
 * index resource, without following {@code rdf:type}. That is the whole index
 * configuration (directories, analyzers, shapes, fields) and nothing that only the
 * dataset or the server declares. The feature is off unless the index sets
 * {@code text:exposeConfig true}.
 */
public class TestLucConfig {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final String NS = "http://example.org/";

    private static final String PREFIXES =
        "PREFIX luc:   <urn:jena:lucene:index#>\n"
        + "PREFIX idx:   <urn:jena:lucene:index#>\n"
        + "PREFIX field: <urn:jena:lucene:field#>\n"
        + "PREFIX sh:    <http://www.w3.org/ns/shacl#>\n"
        + "PREFIX text:  <http://jena.apache.org/text#>\n"
        + "PREFIX rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
        + "PREFIX ex:    <" + NS + ">\n";

    private static final String CONFIG_PREFIXES =
        "@prefix idx:   <urn:jena:lucene:index#> .\n"
        + "@prefix field: <urn:jena:lucene:field#> .\n"
        + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
        + "@prefix text:  <http://jena.apache.org/text#> .\n"
        + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
        + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
        + "@prefix rdfs:  <http://www.w3.org/2000/01/rdf-schema#> .\n"
        + "@prefix ex:    <" + NS + "> .\n";

    private static final String THING_SHAPE =
        "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ;\n"
        + "    idx:defaultSearch true .\n"
        + "field:kind  idx:fieldName \"kind\"  ; idx:fieldType idx:KeywordField ;\n"
        + "    idx:facetable true .\n"
        + "field:maker idx:fieldName \"maker\" ; idx:fieldType idx:KeywordField .\n"
        + "ex:ThingShape\n"
        + "    sh:targetClass ex:Thing ;\n"
        + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
        + "    sh:property [ idx:field field:kind  ; sh:path ex:kind ] ;\n"
        + "    sh:property [ idx:field field:maker ;\n"
        + "                  sh:path ( ex:madeBy [ sh:inversePath ex:owns ] ) ] .\n"
        // A description of the index's class. Reached from the index only through
        // rdf:type, so it is not part of this index's configuration.
        + "text:TextIndexShacl rdfs:comment \"class description\" .\n";

    private static final String OTHER_SHAPE =
        "field:label idx:fieldName \"label\" ; idx:fieldType idx:TextField .\n"
        + "ex:OtherShape\n"
        + "    sh:targetClass ex:Other ;\n"
        + "    sh:property [ idx:field field:label ; sh:path ex:label ] .\n";

    private Dataset dataset;

    @AfterEach
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
            dataset = null;
        }
    }

    // ---- set-up

    /** A single-index dataset. {@code indexProperties} are added to the index resource. */
    private Dataset single(String indexProperties) {
        return assemble(singleConfig(indexProperties));
    }

    private static String singleConfig(String indexProperties) {
        return CONFIG_PREFIXES + THING_SHAPE
            + "ex:dataset rdf:type text:TextDataset ;\n"
            + "    text:dataset ex:base ;\n"
            + "    text:index ex:index .\n"
            + "ex:base rdf:type ja:MemoryDataset .\n"
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:directory \"mem\" ;\n"
            + "    text:storeValues true ;\n"
            + "    text:maxFacetHits 500 ;\n"
            + "    text:analyzer [ rdf:type text:StandardAnalyzer ; text:stopWords ( \"the\" \"a\" ) ] ;\n"
            + "    text:shapes ( ex:ThingShape )"
            + indexProperties + " .\n";
    }

    /** {@code ex:index} exposed, {@code ex:index2} not. */
    private Dataset multi() {
        String turtle = CONFIG_PREFIXES + THING_SHAPE + OTHER_SHAPE
            + "ex:dataset rdf:type text:TextDataset ;\n"
            + "    text:dataset ex:base ;\n"
            + "    text:indexes ( ex:index ex:index2 ) .\n"
            + "ex:base rdf:type ja:MemoryDataset .\n"
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:directory \"mem\" ;\n"
            + "    text:shapes ( ex:ThingShape ) ;\n"
            + "    text:exposeConfig true .\n"
            + "ex:index2 rdf:type text:TextIndexShacl ;\n"
            + "    text:directory \"mem\" ;\n"
            + "    text:shapes ( ex:OtherShape ) .\n";
        return assemble(turtle);
    }

    private static Dataset assemble(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(turtle), null, "TTL");
        Resource spec = model.getResource(NS + "dataset");
        return (Dataset) Assembler.general().open(spec);
    }

    /**
     * As Fuseki assembles: through {@link AssemblerHelp#fullModel}, which adds the
     * {@code rdf:type} triples the assembler schema implies.
     */
    private static Dataset assembleExpanded(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(turtle), null, "TTL");
        Model expanded = AssemblerHelp.fullModel(model);
        Resource spec = expanded.getResource(NS + "dataset");
        return (Dataset) Assembler.general().open(spec);
    }

    // ---- query helpers

    private Graph construct(String selector) {
        String q = PREFIXES
            + "CONSTRUCT { ?s ?p ?o } WHERE { (?s ?p ?o) luc:config (\"" + selector + "\") }";
        try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
            return qe.execConstruct().getGraph();
        }
    }

    private List<QuerySolution> select(String where) {
        String q = PREFIXES + "SELECT * WHERE { " + where + " }";
        try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
            List<QuerySolution> rows = new ArrayList<>();
            qe.execSelect().forEachRemaining(rows::add);
            return rows;
        }
    }

    private static Node uri(String s) {
        return NodeFactory.createURI(s);
    }

    private static Node ex(String local) {
        return uri(NS + local);
    }

    private static boolean has(Graph g, Node s, String p, Node o) {
        return g.contains(s, uri(p), o);
    }

    private static final String TEXT = "http://jena.apache.org/text#";
    private static final String IDX = "urn:jena:lucene:index#";
    private static final String SH = "http://www.w3.org/ns/shacl#";

    // ---- off unless asked for

    @Test
    public void disabledByDefault() {
        dataset = single("");
        QueryExecException e = assertThrows(QueryExecException.class, () -> construct("default"));
        assertTrue(e.getMessage().contains("text:exposeConfig"),
            "the error should name the setting that enables it: " + e.getMessage());
    }

    @Test
    public void disabledWhenSetFalse() {
        dataset = single(" ;\n    text:exposeConfig false");
        assertThrows(QueryExecException.class, () -> construct("default"));
    }

    @Test
    public void nonBooleanSettingIsRejected() {
        Exception e = assertThrows(Exception.class, () -> single(" ;\n    text:exposeConfig \"yes\""));
        boolean named = false;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("text:exposeConfig")) {
                named = true;
            }
        }
        assertTrue(named, "the assembly error should name text:exposeConfig: " + e);
    }

    // ---- what is returned

    @Test
    public void indexSettingsAreReturnedAsWritten() {
        dataset = single(" ;\n    text:exposeConfig true");
        Graph g = construct("default");
        Node index = ex("index");

        assertTrue(has(g, index, TEXT + "directory", NodeFactory.createLiteralString("mem")));
        assertTrue(g.contains(index, uri(TEXT + "storeValues"), Node.ANY));
        assertTrue(g.contains(index, uri(TEXT + "maxFacetHits"), Node.ANY));
        assertTrue(g.contains(index, uri(TEXT + "exposeConfig"), Node.ANY));
        assertTrue(has(g, index, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
            uri(TEXT + "TextIndexShacl")));
    }

    @Test
    public void analyzerConfigurationIsReturned() {
        dataset = single(" ;\n    text:exposeConfig true");
        Graph g = construct("default");
        assertTrue(g.contains(Node.ANY, uri(TEXT + "stopWords"), Node.ANY),
            "analyzer settings are part of the index configuration and are returned");
    }

    @Test
    public void shapesAndFieldsAreReturnedAsWritten() {
        dataset = single(" ;\n    text:exposeConfig true");
        Graph g = construct("default");

        assertTrue(has(g, ex("ThingShape"), SH + "targetClass", ex("Thing")));
        Node kind = uri("urn:jena:lucene:field#kind");
        assertTrue(has(g, kind, IDX + "facetable", NodeFactory.createLiteralByValue(true)));
        assertTrue(has(g, kind, IDX + "fieldType", uri(IDX + "KeywordField")));
        assertTrue(has(g, kind, IDX + "fieldName", NodeFactory.createLiteralString("kind")));
    }

    /**
     * Only what the configuration says: a flag left at its default is absent, not
     * written out. The defaults are documented in 03-configuration.md.
     */
    @Test
    public void defaultsAreNotAdded() {
        dataset = single(" ;\n    text:exposeConfig true");
        Graph g = construct("default");
        assertFalse(g.contains(uri("urn:jena:lucene:field#title"), uri(IDX + "facetable"), Node.ANY));
    }

    @Test
    public void datasetAndUnrelatedTriplesAreNotReturned() {
        dataset = single(" ;\n    text:exposeConfig true");
        Graph g = construct("default");

        assertFalse(g.contains(ex("dataset"), Node.ANY, Node.ANY),
            "the dataset declares the index; it is not part of the index's configuration");
        assertFalse(g.contains(ex("base"), Node.ANY, Node.ANY));
        assertFalse(g.contains(uri(TEXT + "TextIndexShacl"), Node.ANY, Node.ANY),
            "rdf:type is not followed, so a description of the class is not returned");
    }

    /**
     * Fuseki expands the configuration model before assembling, which types every list
     * cell {@code rdf:List} and {@code rdfs:Resource}. Those triples were not written and
     * must not be returned.
     */
    @Test
    public void typesInferredByTheAssemblerAreNotReturned() {
        dataset = assembleExpanded(singleConfig(" ;\n    text:exposeConfig true"));
        Graph g = construct("default");
        Node type = uri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        assertFalse(g.contains(Node.ANY, type, uri("http://www.w3.org/1999/02/22-rdf-syntax-ns#List")));
        assertFalse(g.contains(Node.ANY, type, uri("http://www.w3.org/2000/01/rdf-schema#Resource")));
        assertTrue(g.contains(ex("index"), type, uri(TEXT + "TextIndexShacl")),
            "a type that was written is still returned");
    }

    // ---- querying it

    @Test
    public void aConstantInASlotFilters() {
        dataset = single(" ;\n    text:exposeConfig true");
        List<QuerySolution> rows = select("(?f idx:facetable true) luc:config (\"default\")");
        Set<String> fields = new HashSet<>();
        rows.forEach(r -> fields.add(r.getResource("f").getURI()));
        assertEquals(Set.of("urn:jena:lucene:field#kind"), fields);
    }

    @Test
    public void callsJoinOnBlankNodes() {
        dataset = single(" ;\n    text:exposeConfig true");
        List<QuerySolution> rows = select(
            "(ex:ThingShape sh:property ?occ) luc:config (\"default\") .\n"
            + "(?occ idx:field field:kind) luc:config (\"default\") .\n"
            + "(?occ sh:path ?path) luc:config (\"default\") .");
        assertEquals(1, rows.size());
        assertEquals(NS + "kind", rows.get(0).getResource("path").getURI());
    }

    /** A sequence path comes back as the flat list that was written, not re-nested. */
    @Test
    public void aSequencePathIsReturnedAsWritten() {
        dataset = single(" ;\n    text:exposeConfig true");
        List<QuerySolution> rows = select(
            "(?occ idx:field field:maker) luc:config (\"default\") .\n"
            + "(?occ sh:path ?list) luc:config (\"default\") .\n"
            + "(?list rdf:first ex:madeBy) luc:config (\"default\") .\n"
            + "(?list rdf:rest ?rest) luc:config (\"default\") .\n"
            + "(?rest rdf:first ?inverse) luc:config (\"default\") .\n"
            + "(?inverse sh:inversePath ex:owns) luc:config (\"default\") .\n"
            + "(?rest rdf:rest rdf:nil) luc:config (\"default\") .");
        assertEquals(1, rows.size());
    }

    @Test
    public void aRepeatedVariableMustMatchItself() {
        dataset = single(" ;\n    text:exposeConfig true");
        assertTrue(select("(?x ?p ?x) luc:config (\"default\")").isEmpty());
    }

    @Test
    public void triplesMatchTheConfigurationExactly() {
        dataset = single(" ;\n    text:exposeConfig true");
        Graph g = construct("default");
        // Every triple returned is in the configuration as written. Blank nodes are
        // relabelled by each parse, so only triples without them can be compared.
        Model written = ModelFactory.createDefaultModel();
        written.read(new StringReader(CONFIG_PREFIXES + THING_SHAPE), null, "TTL");
        for (Triple t : g.find().toList()) {
            if (t.getSubject().isURI() && !t.getObject().isBlank()
                    && !t.getSubject().equals(ex("index"))) {
                assertTrue(written.getGraph().contains(t), "not in the configuration: " + t);
            }
        }
    }

    // ---- several indexes

    @Test
    public void eachIndexReturnsItsOwnConfiguration() {
        dataset = multi();
        Graph g = construct("index");
        assertTrue(g.contains(ex("ThingShape"), Node.ANY, Node.ANY));
        assertFalse(g.contains(ex("OtherShape"), Node.ANY, Node.ANY));
        assertFalse(g.contains(ex("index2"), Node.ANY, Node.ANY));
    }

    @Test
    public void exposureIsPerIndex() {
        dataset = multi();
        assertThrows(QueryExecException.class, () -> construct("index2"));
    }

    @Test
    public void anUnknownSelectorIsAQueryError() {
        dataset = multi();
        assertThrows(QueryExecException.class, () -> construct("nope"));
    }

    // ---- call shape

    @Test
    public void subjectMustBeThreeSlots() {
        dataset = single(" ;\n    text:exposeConfig true");
        assertThrows(QueryBuildException.class,
            () -> select("(?s ?p) luc:config (\"default\")"));
    }

    @Test
    public void objectMustBeOneSelector() {
        dataset = single(" ;\n    text:exposeConfig true");
        assertThrows(QueryBuildException.class,
            () -> select("(?s ?p ?o) luc:config (\"default\" \"extra\")"));
    }

    // ---- no effect on the index itself

    /** Turning exposure on or off must not make the startup check ask for a rebuild. */
    @Test
    public void exposureDoesNotChangeTheFingerprint() {
        dataset = single("");
        String hidden = shaclIndex(dataset).getConfigFingerprint();
        dataset.close();
        dataset = single(" ;\n    text:exposeConfig true");
        assertEquals(hidden, shaclIndex(dataset).getConfigFingerprint());
    }

    private static ShaclTextIndexLucene shaclIndex(Dataset ds) {
        return (ShaclTextIndexLucene) ((DatasetGraphText) ds.asDatasetGraph()).getTextIndex();
    }
}
