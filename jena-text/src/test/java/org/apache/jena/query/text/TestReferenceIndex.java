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

import java.net.URL;
import java.util.*;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.*;
import org.apache.jena.query.text.assembler.TextAssembler;
import org.apache.jena.query.text.assembler.TextVocab;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.assembler.AssemblerUtils;
import org.apache.jena.sparql.util.graph.GraphUtils;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Two indexes over one dataset: {@code reference} for the classes, properties and
 * controlled terms in {@code reference-index/data.ttl}, and {@code instance} for the
 * sites that use them.
 * <p>
 * Assembled from {@code reference-index/config.ttl} on the test classpath. Each test is
 * a question a search panel asks of one index or the other.
 */
public class TestReferenceIndex {

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    private static final String EX = "http://example.org/ref/";
    private static final String FIELD = "urn:jena:lucene:field#";
    private static final String PREFIXES =
        "PREFIX luc: <urn:jena:lucene:index#>\n"
        + "PREFIX skos: <http://www.w3.org/2004/02/skos/core#>\n"
        + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
        + "PREFIX ex: <" + EX + ">\n";

    private Dataset dataset;

    @AfterEach
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
            dataset = null;
        }
    }

    // ---- set-up

    /** A file under {@code reference-index/} on the test classpath. */
    private static String resource(String name) {
        URL url = TestReferenceIndex.class.getClassLoader().getResource("reference-index/" + name);
        assertNotNull(url, "reference-index/" + name + " not on the test classpath");
        return url.toString();
    }

    private static Dataset assemble() {
        Model spec = AssemblerUtils.readAssemblerFile(resource("config.ttl"));
        Resource root = GraphUtils.findRootByType(spec, TextVocab.textDataset);
        return (Dataset) Assembler.general().open(root);
    }

    // ---- query helpers

    /** Entities from luc:query, in rank order. */
    private List<String> search(String index, String fieldSpec, String query, String filter, String sort) {
        String q = PREFIXES
            + "SELECT ?entity WHERE {\n"
            + "  (?hit ?entity ?score ?totalHits ?rank) luc:query ("
            + quote(index) + " " + quote(fieldSpec) + " " + quote(query) + " "
            + quote(filter) + " " + quote(sort) + " 1000 0)\n"
            + "} ORDER BY ?rank";
        List<String> out = new ArrayList<>();
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
            qe.execSelect().forEachRemaining(r -> out.add(r.getResource("entity").getURI()));
        } finally {
            dataset.end();
        }
        return out;
    }

    private List<String> search(String index, String query) {
        return search(index, "default", query, "", "");
    }

    private static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static Model loadData() {
        return RDFDataMgr.loadModel(resource("data.ttl"));
    }

    // ---- the reference index

    /** Typeahead: the first letters of a term's label find it. */
    @Test
    public void typeaheadFindsATermFromItsFirstLetters() {
        dataset = assemble();
        List<String> hits = search("reference", "[\"" + FIELD + "termLabelPrefix\"]", "Cop", "", "");
        assertTrue(hits.contains(EX + "commodity/Copper"), "Cop should suggest Copper: " + hits);
    }

    /** Dropdown: every term in one scheme, in label order. */
    @Test
    public void aSchemeListsItsTermsInLabelOrder() {
        dataset = assemble();
        String filter = "{\"op\":\"=\",\"args\":[{\"property\":\"" + FIELD + "scheme\"},\"" + EX + "states\"]}";
        String sort = "{\"field\":\"" + FIELD + "termLabelExact\",\"order\":\"asc\"}";
        List<String> hits = search("reference", "default", "*", filter, sort);

        Model reference = loadData();
        Resource states = reference.createResource(EX + "states");
        List<Resource> expected = new ArrayList<>(
            reference.listSubjectsWithProperty(SKOS.inScheme, states).toList());
        expected.sort(Comparator.comparing(r -> r.getProperty(RDFS.label).getString()));
        List<String> expectedIris = expected.stream().map(Resource::getURI).toList();

        assertFalse(expectedIris.isEmpty(), "data.ttl defines no states");
        assertEquals(expectedIris, hits);
    }

    /** A property is found by the words that describe it, not only its name. */
    @Test
    public void aPropertyIsFoundByItsDescription() {
        dataset = assemble();
        assertTrue(search("reference", "metres").contains(EX + "depth"));
    }

    @Test
    public void aPropertyIsFoundByItsName() {
        dataset = assemble();
        assertTrue(search("reference", "commodity").contains(EX + "commodity"));
    }

    /** What kind of term each hit is: a concept, a class or a property. */
    @Test
    public void termsFacetByKind() {
        dataset = assemble();
        String q = PREFIXES
            + "SELECT ?value ?count WHERE {\n"
            + "  (?field ?value ?low ?high ?count) luc:facet ('reference' 'default' '*' "
            + quote("[\"" + FIELD + "termType\"]") + " '' 10 0)\n"
            + "}";
        Map<String, Integer> counts = new HashMap<>();
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
            qe.execSelect().forEachRemaining(r ->
                counts.put(r.get("value").toString(), r.getLiteral("count").getInt()));
        } finally {
            dataset.end();
        }

        Model reference = loadData();
        for (Resource kind : List.of(SKOS.Concept, RDFS.Class, RDF.Property)) {
            int defined = reference.listSubjectsWithProperty(RDF.type, kind).toList().size();
            assertEquals(defined, counts.getOrDefault(kind.getURI(), 0), "count for " + kind);
        }
    }

    // ---- keeping the two apart

    @Test
    public void theInstanceIndexHoldsNoReferenceData() {
        dataset = assemble();
        List<String> hits = search("instance", "Copper");
        assertFalse(hits.isEmpty(), "instance data mentions copper");
        assertFalse(hits.contains(EX + "commodity/Copper"));
        assertTrue(hits.stream().noneMatch(h -> h.startsWith(EX + "commodity/")));
    }

    @Test
    public void theReferenceIndexHoldsNoInstanceData() {
        dataset = assemble();
        assertFalse(search("reference", "Brolga").contains(EX + "site-brolga-ridge"));
    }

    /** One dataset, two change listeners: a new term goes to the reference index only. */
    @Test
    public void aTermAddedLiveIsIndexedAsReference() {
        dataset = assemble();
        dataset.begin(ReadWrite.WRITE);
        try {
            Model m = dataset.getDefaultModel();
            Resource nickel = m.createResource(EX + "commodity/Nickel");
            m.add(nickel, RDF.type, SKOS.Concept);
            m.add(nickel, SKOS.inScheme, m.createResource(EX + "commodities"));
            m.add(nickel, RDFS.label, "Nickel");
            dataset.commit();
        } finally {
            dataset.end();
        }

        assertTrue(search("reference", "[\"" + FIELD + "termLabelPrefix\"]", "Nic", "", "")
            .contains(EX + "commodity/Nickel"));
        assertFalse(search("instance", "Nickel").contains(EX + "commodity/Nickel"));
    }

    // ---- configuration

    @Test
    public void bothIndexesExposeTheirConfiguration() {
        dataset = assemble();
        for (String index : List.of("instance", "reference")) {
            String q = "PREFIX luc: <urn:jena:lucene:index#>\n"
                + "CONSTRUCT { ?s ?p ?o } WHERE { (?s ?p ?o) luc:config (" + quote(index) + ") }";
            dataset.begin(ReadWrite.READ);
            try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
                Model config = qe.execConstruct();
                assertTrue(config.contains(null, RDF.type, TextVocab.textIndexShacl), index);
            } finally {
                dataset.end();
            }
        }
    }
}
