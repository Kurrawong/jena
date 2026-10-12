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
import java.util.List;

import org.apache.jena.assembler.Assembler;
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
 * {@code luc:stats} — figures about a SHACL index's Lucene contents, as triples, through
 * the query endpoint. Subjects are the index resource and the field IRIs, so the result
 * joins with {@code luc:config}.
 */
public class TestLucStats {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final String NS = "http://example.org/";
    private static final String LUC = "urn:jena:lucene:index#";
    private static final String FIELD = "urn:jena:lucene:field#";

    private static final String PREFIXES =
        "PREFIX luc:   <urn:jena:lucene:index#>\n"
        + "PREFIX idx:   <urn:jena:lucene:index#>\n"
        + "PREFIX field: <urn:jena:lucene:field#>\n"
        + "PREFIX ex:    <" + NS + ">\n";

    private static final String CONFIG_PREFIXES =
        "@prefix idx:   <urn:jena:lucene:index#> .\n"
        + "@prefix field: <urn:jena:lucene:field#> .\n"
        + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
        + "@prefix text:  <http://jena.apache.org/text#> .\n"
        + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
        + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
        + "@prefix ex:    <" + NS + "> .\n";

    private static final String THING_SHAPE =
        "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ;\n"
        + "    idx:defaultSearch true .\n"
        + "field:kind  idx:fieldName \"kind\"  ; idx:fieldType idx:KeywordField .\n"
        + "field:year  idx:fieldName \"year\"  ; idx:fieldType idx:IntField .\n"
        + "ex:ThingShape\n"
        + "    sh:targetClass ex:Thing ;\n"
        + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
        + "    sh:property [ idx:field field:kind  ; sh:path ex:kind ] ;\n"
        + "    sh:property [ idx:field field:year  ; sh:path ex:year ] .\n";

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
    private static Dataset single(String indexProperties) {
        return assemble(CONFIG_PREFIXES + THING_SHAPE
            + "ex:dataset rdf:type text:TextDataset ;\n"
            + "    text:dataset ex:base ;\n"
            + "    text:index ex:index .\n"
            + "ex:base rdf:type ja:MemoryDataset .\n"
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:directory \"mem\" ;\n"
            + "    text:shapes ( ex:ThingShape )"
            + indexProperties + " .\n");
    }

    private static Dataset multi() {
        return assemble(CONFIG_PREFIXES + THING_SHAPE + OTHER_SHAPE
            + "ex:dataset rdf:type text:TextDataset ;\n"
            + "    text:dataset ex:base ;\n"
            + "    text:indexes ( ex:index ex:index2 ) .\n"
            + "ex:base rdf:type ja:MemoryDataset .\n"
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:directory \"mem\" ;\n"
            + "    text:shapes ( ex:ThingShape ) .\n"
            + "ex:index2 rdf:type text:TextIndexShacl ;\n"
            + "    text:directory \"mem\" ;\n"
            + "    text:shapes ( ex:OtherShape ) .\n");
    }

    private static Dataset assemble(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(turtle), null, "TTL");
        Resource spec = model.getResource(NS + "dataset");
        return (Dataset) Assembler.general().open(spec);
    }

    private void load(String turtle) {
        dataset.begin(ReadWrite.WRITE);
        try {
            dataset.getDefaultModel().read(new StringReader(
                "@prefix ex: <" + NS + "> .\n" + turtle), null, "TTL");
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    /** Three things: six title tokens over four distinct terms, two kinds, three years. */
    private static final String THREE_THINGS =
        "ex:a a ex:Thing ; ex:title \"red apple\" ;   ex:kind \"fruit\" ;   ex:year 2001 .\n"
        + "ex:b a ex:Thing ; ex:title \"green apple\" ; ex:kind \"fruit\" ;   ex:year 2002 .\n"
        + "ex:c a ex:Thing ; ex:title \"red car\" ;     ex:kind \"vehicle\" ; ex:year 2003 .\n";

    // ---- query helpers

    private Graph stats(String selector) {
        String q = PREFIXES
            + "CONSTRUCT { ?s ?p ?o } WHERE { (?s ?p ?o) luc:stats (\"" + selector + "\") }";
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

    /** The single integer value of {@code s p}, failing if there is not exactly one. */
    private static long value(Graph g, Node s, String localName) {
        List<Triple> found = g.find(s, NodeFactory.createURI(LUC + localName), Node.ANY).toList();
        assertEquals(1, found.size(), "expected one " + localName + " for " + s + ", got " + found);
        Object v = found.get(0).getObject().getLiteralValue();
        return ((Number) v).longValue();
    }

    private static Node ex(String local) {
        return NodeFactory.createURI(NS + local);
    }

    private static Node field(String local) {
        return NodeFactory.createURI(FIELD + local);
    }

    // ---- index-level figures

    @Test
    public void entitiesAndDocumentsAreCounted() {
        dataset = single("");
        load(THREE_THINGS);
        Graph g = stats("default");
        Node index = ex("index");

        assertEquals(3, value(g, index, "entityCount"));
        assertEquals(3, value(g, index, "documentCount"));
        assertTrue(value(g, index, "segmentCount") >= 1);
        assertTrue(value(g, index, "sizeInBytes") > 0);
        value(g, index, "deletedDocumentCount");
    }

    @Test
    public void anEmptyIndexReportsZero() {
        dataset = single("");
        Graph g = stats("default");

        assertEquals(0, value(g, ex("index"), "entityCount"));
        assertEquals(0, value(g, ex("index"), "documentCount"));
        assertEquals(0, value(g, field("title"), "documentCount"));
        assertEquals(0, value(g, field("title"), "termCount"));
        assertEquals(0, value(g, field("year"), "pointCount"));
    }

    // ---- field-level figures

    @Test
    public void textFieldTermsAndTokensAreCounted() {
        dataset = single("");
        load(THREE_THINGS);
        Graph g = stats("default");

        assertEquals(3, value(g, field("title"), "documentCount"));
        assertEquals(4, value(g, field("title"), "termCount"), "red, apple, green, car");
        assertEquals(6, value(g, field("title"), "tokenCount"));
    }

    @Test
    public void keywordFieldDistinctValuesAreCounted() {
        dataset = single("");
        load(THREE_THINGS);
        Graph g = stats("default");

        assertEquals(3, value(g, field("kind"), "documentCount"));
        assertEquals(2, value(g, field("kind"), "termCount"), "fruit, vehicle");
    }

    @Test
    public void numericFieldPointsAreCounted() {
        dataset = single("");
        load(THREE_THINGS);
        Graph g = stats("default");

        assertEquals(3, value(g, field("year"), "documentCount"));
        assertEquals(3, value(g, field("year"), "pointCount"));
        assertFalse(g.contains(field("year"), NodeFactory.createURI(LUC + "termCount"), Node.ANY),
            "a numeric field has points, not terms");
    }

    // ---- freshness

    @Test
    public void figuresFollowCommits() {
        dataset = single("");
        load(THREE_THINGS);
        assertEquals(3, value(stats("default"), ex("index"), "entityCount"));

        load("ex:d a ex:Thing ; ex:title \"blue boat\" .\n");
        Graph g = stats("default");
        assertEquals(4, value(g, ex("index"), "entityCount"));
        assertEquals(6, value(g, field("title"), "termCount"));
    }

    // ---- access and selection

    @Test
    public void availableWithoutExposeConfig() {
        dataset = single(" ;\n    text:exposeConfig false");
        load(THREE_THINGS);
        assertEquals(3, value(stats("default"), ex("index"), "entityCount"));
    }

    @Test
    public void joinsWithLucConfigOnFieldIri() {
        dataset = single(" ;\n    text:exposeConfig true");
        load(THREE_THINGS);
        List<QuerySolution> rows = select(
            "(?field idx:fieldType idx:KeywordField) luc:config (\"default\") .\n"
            + "(?field luc:termCount ?terms) luc:stats (\"default\") .");

        assertEquals(1, rows.size());
        assertEquals(FIELD + "kind", rows.get(0).getResource("field").getURI());
        assertEquals(2, rows.get(0).getLiteral("terms").getLong());
    }

    @Test
    public void eachIndexReportsItsOwnFields() {
        dataset = multi();
        Graph g = stats(NS + "index2");

        assertTrue(g.contains(field("label"), Node.ANY, Node.ANY));
        assertFalse(g.contains(field("title"), Node.ANY, Node.ANY));
        assertTrue(g.contains(ex("index2"), Node.ANY, Node.ANY));
    }

    @Test
    public void constantSlotsRestrictTheResult() {
        dataset = single("");
        load(THREE_THINGS);
        List<QuerySolution> rows = select("(field:title ?p ?o) luc:stats (\"default\") .");

        assertEquals(3, rows.size(), "documentCount, termCount, tokenCount: " + rows);
    }

    @Test
    public void unknownSelectorIsAnError() {
        dataset = multi();
        assertThrows(QueryExecException.class, () -> stats("nope"));
    }
}
