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

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.*;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the luc:facet property function.
 */
public class TestTextFacetPF {

    private static final String NS = "http://example.org/";
    private static final String FP = "urn:jena:lucene:field#";
    private static final Node BOOK_CLASS = NodeFactory.createURI(NS + "Book");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node CATEGORY_PRED = NodeFactory.createURI(NS + "category");
    private static final Node AUTHOR_PRED = NodeFactory.createURI(NS + "author");
    private static final Node YEAR_PRED = NodeFactory.createURI(NS + "year");

    private Dataset dataset;

    @Before
    public void setUp() {
        TextQuery.init();

        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true,
            Collections.singleton(TITLE_PRED));

        FieldDef categoryField = new FieldDef("category", FieldType.KEYWORD, null,
            true, true, true, false, true, false,
            Collections.singleton(CATEGORY_PRED));

        FieldDef authorField = new FieldDef("author", FieldType.KEYWORD, null,
            true, true, true, false, false, false,
            Collections.singleton(AUTHOR_PRED));

        FieldDef yearField = new FieldDef("year", FieldType.INT, null,
            true, true, true, true, true, false,
            Collections.singleton(YEAR_PRED));

        IndexProfile bookProfile = new IndexProfile(
            NodeFactory.createURI(NS + "BookShape"),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, categoryField, authorField, yearField));

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(bookProfile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        ShaclTextIndexLucene textIndex = new ShaclTextIndexLucene(dir, config);

        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(
            baseDs.asDatasetGraph(), textIndex, mapping);

        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);

        loadTestData();
    }

    private void loadTestData() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            addBook(model, "doc1", "Introduction to Machine Learning", NS + "category/technology", NS + "author/Smith", 2018, 2020);
            addBook(model, "doc2", "Deep Learning Neural Networks", NS + "category/technology", NS + "author/Jones", 2021);
            addBook(model, "doc3", "Machine Learning for Beginners", NS + "category/technology", NS + "author/Smith", 2022);
            addBook(model, "doc4", "Learning About Quantum Physics", NS + "category/science", NS + "author/Wilson", 2019);
            addBook(model, "doc5", "Machine Learning in Biology", NS + "category/science", NS + "author/Smith", 2024);
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void addBook(Model model, String id, String title, String categoryUri, String authorUri, int... years) {
        Resource book = ResourceFactory.createResource(NS + id);
        model.add(book, RDF.type, ResourceFactory.createResource(NS + "Book"));
        model.add(book, ResourceFactory.createProperty(NS + "title"), title);
        model.add(book, ResourceFactory.createProperty(NS + "category"), ResourceFactory.createResource(categoryUri));
        model.add(book, ResourceFactory.createProperty(NS + "author"), ResourceFactory.createResource(authorUri));
        for (int year : years) {
            model.add(book, ResourceFactory.createProperty(NS + "year"), ResourceFactory.createTypedLiteral(year));
        }
    }

    @After
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    @Test
    public void testBasicFacetCounts() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "category\"]' \"\" 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    assertNotNull(sol.get("f"));
                    assertNotNull(sol.get("v"));
                    assertNotNull(sol.get("c"));
                    count++;
                }
                assertTrue("Should have at least one facet result", count > 0);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFlatFacetRowsLeaveLowAndHighUnbound() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "category\"]' \"\" \"10\" \"0\")\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    assertTrue("Flat facet rows should bind ?value", sol.contains("v"));
                    assertFalse("Flat facet rows should leave ?low unbound", sol.contains("low"));
                    assertFalse("Flat facet rows should leave ?high unbound", sol.contains("high"));
                    count++;
                }
                assertTrue("Should have flat facet rows", count > 0);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFacetFieldIsURI() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "category\"]' \"\" 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    assertTrue("?f should be a URI", sol.get("f").isURIResource());
                    assertEquals("urn:jena:lucene:field#category",
                        sol.getResource("f").getURI());
                }
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFacetCountsWithMultipleFields() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "category\", \"" + FP + "author\"]' \"\" 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                boolean foundCategory = false;
                boolean foundAuthor = false;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    String fieldUri = sol.getResource("f").getURI();
                    if (fieldUri.endsWith("#category")) foundCategory = true;
                    if (fieldUri.endsWith("#author")) foundAuthor = true;
                }
                assertTrue("Should have category facets", foundCategory);
                assertTrue("Should have author facets", foundAuthor);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFacetWildcard() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[\"*\"]' \"\" 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                boolean foundCategory = false;
                boolean foundAuthor = false;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    String fieldUri = sol.getResource("f").getURI();
                    if (fieldUri.endsWith("#category")) foundCategory = true;
                    if (fieldUri.endsWith("#author")) foundAuthor = true;
                }
                assertTrue("Wildcard should return category facets", foundCategory);
                assertTrue("Wildcard should return author facets", foundAuthor);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFacetCountsWithFilters() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "author\"]' '{\"op\":\"=\",\"args\":[{\"property\":\"" + FP + "category\"},\"" + NS + "category/technology\"]}' 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    assertTrue(sol.getResource("f").getURI().endsWith("#author"));
                    count++;
                }
                assertTrue("Should have filtered author facets", count > 0);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFacetCountsWithMaxValues() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "author\"]' \"\" 1 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    rs.next();
                    count++;
                }
                assertEquals("Should have at most 1 author facet value", 1, count);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFacetCountsWithMinCount() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "author\"]' \"\" 10 2)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    long cnt = sol.getLiteral("c").getLong();
                    assertTrue("Count should be >= 2", cnt >= 2);
                    count++;
                }
                assertEquals("Only Smith should pass minCount=2", 1, count);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFacetCountsWithMaxValuesZero() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "author\"]' \"\" 0 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    rs.next();
                    count++;
                }
                assertEquals("maxValues=0 should return all authors", 3, count);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFacetCountsWithMinCountAndMaxValues() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "author\"]' \"\" 0 2)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    assertTrue("?v should be a URI for KEYWORD field",
                        sol.get("v").isURIResource());
                    assertEquals(NS + "author/Smith", sol.getResource("v").getURI());
                    count++;
                }
                assertEquals("Only Smith should pass combined filter", 1, count);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testRangeFacetFiveSlotBindings() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[{\"field\":\"" + FP + "year\",\"ranges\":[null,2020,2023,null]}]' \"\" 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                int rangeRows = 0;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    assertEquals(FP + "year", sol.getResource("f").getURI());
                    assertFalse(sol.contains("v"));
                    assertTrue(sol.getLiteral("c").getLong() > 0);
                    rangeRows++;
                }
                assertEquals(3, rangeRows);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testMixedFlatAndRangeFacets() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "category\", {\"field\":\"" + FP + "year\",\"ranges\":[null,2020,2023,null]}]' \"\" 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                boolean foundCategory = false;
                boolean foundRange = false;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    String fieldUri = sol.getResource("f").getURI();
                    if (fieldUri.endsWith("#category")) {
                        assertTrue(sol.contains("v"));
                        assertFalse(sol.contains("low"));
                        assertFalse(sol.contains("high"));
                        foundCategory = true;
                    }
                    if (fieldUri.endsWith("#year")) {
                        assertFalse(sol.contains("v"));
                        foundRange = true;
                    }
                }
                assertTrue(foundCategory);
                assertTrue(foundRange);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testNumericFacetFieldBareStringRejected() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "year\"]' \"\" 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                qe.execSelect().hasNext();
                fail("Expected numeric facet request to be rejected");
            } catch (QueryExecException ex) {
                assertTrue(ex.getMessage().contains("requires a range object"));
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testThreeSlotSubjectRejected() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?c WHERE {\n" +
            "  (?f ?v ?c) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "author\"]' \"\" 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try {
                try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                    qe.execSelect().hasNext();
                    fail("Expected 3-slot facet request to be rejected");
                }
            } catch (QueryBuildException | QueryExecException ex) {
                assertTrue(ex.getMessage().contains("exactly 5"));
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testRangeObjectOnNonNumericFieldRejected() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?f ?v ?low ?high ?c WHERE {\n" +
            "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"learning\" '[{\"field\":\"" + FP + "category\",\"ranges\":[1,2]}]' \"\" 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                qe.execSelect().hasNext();
                fail("Expected non-numeric range request to be rejected");
            } catch (QueryExecException ex) {
                assertTrue(ex.getMessage().contains("is not numeric"));
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testDifferentFacetRequestsDoNotReuseStaleCache() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT DISTINCT ?f1 ?f2 WHERE {\n" +
            "  (?f1 ?v1 ?low1 ?high1 ?c1) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "category\"]' \"\" 10 0) .\n" +
            "  (?f2 ?v2 ?low2 ?high2 ?c2) luc:facet (\"default\" \"default\" \"learning\" '[\"" + FP + "author\"]' \"\" 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    assertEquals(FP + "category", sol.getResource("f1").getURI());
                    assertEquals(FP + "author", sol.getResource("f2").getURI());
                }
            }
        } finally {
            dataset.end();
        }
    }
}
