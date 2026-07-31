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

import java.util.*;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.*;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for luc:query with CQL filter support.
 */
public class TestTextQueryPFFilters {

    private static final String NS = "http://example.org/";
    private static final String FIELD_IRI_PREFIX = "urn:jena:lucene:field#";
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
            true, true, false, false, false, true);

        FieldDef categoryField = new FieldDef("category", FieldType.KEYWORD, null,
            true, true, true, false, true, false);

        FieldDef authorField = new FieldDef("author", FieldType.KEYWORD, null,
            true, true, true, false, false, false);

        FieldDef yearField = new FieldDef("year", FieldType.INT, null,
            true, true, false, true, false, false);

        List<FieldOccurrence> rootOccurrences = Arrays.asList(
            occurrence(titleField, PathFactory.pathLink(TITLE_PRED), Collections.singleton(TITLE_PRED)),
            occurrence(categoryField, PathFactory.pathLink(CATEGORY_PRED), Collections.singleton(CATEGORY_PRED)),
            occurrence(authorField, PathFactory.pathLink(AUTHOR_PRED), Collections.singleton(AUTHOR_PRED)),
            occurrence(yearField, PathFactory.pathLink(YEAR_PRED), Collections.singleton(YEAR_PRED)));

        IndexProfile bookProfile = new IndexProfile(
            NodeFactory.createURI(NS + "BookShape"),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, categoryField, authorField, yearField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

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
            addBook(model, "doc1", "Introduction to Machine Learning", NS + "category/technology", NS + "author/Smith", 2018);
            addBook(model, "doc2", "Deep Learning Neural Networks", NS + "category/technology", NS + "author/Jones", 2021);
            addBook(model, "doc3", "Machine Learning for Beginners", NS + "category/technology", NS + "author/Smith", 2022);
            addBook(model, "doc4", "Learning About Quantum Physics", NS + "category/science", NS + "author/Wilson", 2019);
            addBook(model, "doc5", "Machine Learning in Biology", NS + "category/science", NS + "author/Smith", 2024);
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private static FieldOccurrence occurrence(FieldDef field, Path path, Set<Node> predicates) {
        return new FieldOccurrence(
            field,
            path,
            ShaclIndexAssembler.extractPathVariants(path),
            predicates,
            null, null, null, null);
    }

    private void addBook(Model model, String id, String title, String categoryUri, String authorUri, int year) {
        Resource book = ResourceFactory.createResource(NS + id);
        model.add(book, RDF.type, ResourceFactory.createResource(NS + "Book"));
        model.add(book, ResourceFactory.createProperty(NS + "title"), title);
        model.add(book, ResourceFactory.createProperty(NS + "category"), ResourceFactory.createResource(categoryUri));
        model.add(book, ResourceFactory.createProperty(NS + "author"), ResourceFactory.createResource(authorUri));
        model.add(book, ResourceFactory.createProperty(NS + "year"), ResourceFactory.createTypedLiteral(year));
    }

    @After
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    @Test
    public void testLucQueryWithoutFilters() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s ?score WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" \"\" \"\" 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    assertNotNull(sol.get("s"));
                    assertNotNull(sol.get("score"));
                    count++;
                }
                assertTrue("Should find results for 'learning'", count > 0);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQueryAcceptsEmptyPlaceholdersAndStringLimit() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s ?score WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" \"\" \"\" \"10\" 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                assertTrue("Empty placeholders and a string limit should still yield results", rs.hasNext());
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQueryWithCqlEqualFilter() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s ?score WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" " +
            "    '{\"op\":\"=\",\"args\":[{\"property\":\"urn:jena:lucene:field#category\"},\"http://example.org/category/technology\"]}' \"\" 20 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                Set<String> subjects = new HashSet<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    subjects.add(sol.getResource("s").getURI());
                }
                assertTrue("Should find technology docs", subjects.size() > 0);
                for (String s : subjects) {
                    assertFalse("Should not find doc4 (science)", s.equals(NS + "doc4"));
                }
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQueryWithCqlAndFilter() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" " +
            "    '{\"op\":\"and\",\"args\":[" +
            "      {\"op\":\"=\",\"args\":[{\"property\":\"urn:jena:lucene:field#category\"},\"http://example.org/category/technology\"]}," +
            "      {\"op\":\"=\",\"args\":[{\"property\":\"urn:jena:lucene:field#author\"},\"http://example.org/author/Smith\"]}" +
            "    ]}' \"\" 20 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                Set<String> subjects = new HashSet<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    subjects.add(sol.getResource("s").getURI());
                }
                for (String s : subjects) {
                    assertTrue("Should only find doc1 or doc3",
                        s.equals(NS + "doc1") || s.equals(NS + "doc3"));
                }
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQueryWithCqlNoMatches() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" " +
            "    '{\"op\":\"=\",\"args\":[{\"property\":\"urn:jena:lucene:field#category\"},\"http://example.org/category/nonexistent\"]}' \"\" 20 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                assertFalse("Should have no results for nonexistent filter", rs.hasNext());
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQueryByFieldIRI() {
        // Search only the "title" field via its IRI (JSON array)
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s ?score WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" '[\"" + FIELD_IRI_PREFIX + "title\"]' \"learning\" \"\" \"\" 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    assertNotNull(sol.get("s"));
                    count++;
                }
                assertTrue("Should find results when searching 'title' field", count > 0);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQueryByFieldIRIArray() {
        // Search multiple fields via JSON array of IRIs
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s ?score WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" '[\"" + FIELD_IRI_PREFIX + "title\"]' \"learning\" \"\" \"\" 10 0)\n" +
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
                assertTrue("Should find results when searching via field array", count > 0);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucMatchFieldBinding() {
        // Search a single field and check that luc:match returns ?field bound to the field IRI
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?hit ?s ?score ?field ?value WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" '[\"" + FIELD_IRI_PREFIX + "title\"]' \"learning\" \"\" \"\" 10 0) .\n" +
            "  (?hit ?field ?value) luc:match () .\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    assertNotNull("?field should be bound via luc:match", sol.get("field"));
                    assertTrue("?field should be a URI", sol.get("field").isURIResource());
                    assertEquals("Field IRI should match title",
                        "urn:jena:lucene:field#title", sol.getResource("field").getURI());
                    count++;
                }
                assertTrue("Should find results", count > 0);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQueryTotalHitsBinding() {
        // The fourth luc:query slot now binds ?totalHits rather than a raw match literal
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s ?score ?totalHits WHERE {\n" +
            "  (?hit ?s ?score ?totalHits) luc:query (\"default\" '[\"" + FIELD_IRI_PREFIX + "title\"]' \"machine\" \"\" \"\" 10 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    assertNotNull("?totalHits should be bound", sol.get("totalHits"));
                    assertTrue("?totalHits should be a literal", sol.get("totalHits").isLiteral());
                    assertTrue("?totalHits should be positive",
                        sol.getLiteral("totalHits").getInt() > 0);
                    count++;
                }
                assertTrue("Should find results", count > 0);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucMatchWithDefaultFieldSpec() {
        // With "default", luc:match should still return field matches
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?hit ?s ?field ?value WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" \"\" \"\" 10 0) .\n" +
            "  OPTIONAL { (?hit ?field ?value) luc:match () . }\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                int count = 0;
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    // "default" resolves to "title" (only defaultSearch field in this config),
                    // so luc:match should bind ?field
                    count++;
                }
                assertTrue("Should find results", count > 0);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQueryFieldWithCqlFilter() {
        // Search specific field (by IRI) with CQL filter
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s ?score WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" '[\"" + FIELD_IRI_PREFIX + "title\"]' \"learning\" " +
            "    '{\"op\":\"=\",\"args\":[{\"property\":\"urn:jena:lucene:field#category\"},\"http://example.org/category/technology\"]}' \"\" 20 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                Set<String> subjects = new HashSet<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    subjects.add(sol.getResource("s").getURI());
                }
                assertTrue("Should find technology docs", subjects.size() > 0);
                for (String s : subjects) {
                    assertFalse("Should not find doc4 (science)", s.equals(NS + "doc4"));
                }
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQuerySortDescendingByFieldIri() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "PREFIX ex: <http://example.org/>\n" +
            "SELECT ?s ?year WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" \"\" '{\"field\":\"urn:jena:lucene:field#year\",\"order\":\"desc\"}' 10 0) .\n" +
            "  ?s ex:year ?year .\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                List<Integer> years = new ArrayList<>();
                while (rs.hasNext()) {
                    years.add(rs.next().getLiteral("year").getInt());
                }
                assertEquals(Arrays.asList(2024, 2022, 2021, 2019, 2018), years);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQuerySortAscendingWithFilter() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "PREFIX ex: <http://example.org/>\n" +
            "SELECT ?s ?year WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" " +
            "    '{\"op\":\"=\",\"args\":[{\"property\":\"urn:jena:lucene:field#category\"},\"http://example.org/category/technology\"]}' " +
            "    '{\"field\":\"urn:jena:lucene:field#year\",\"order\":\"asc\"}' 10 0) .\n" +
            "  ?s ex:year ?year .\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                List<Integer> years = new ArrayList<>();
                while (rs.hasNext()) {
                    years.add(rs.next().getLiteral("year").getInt());
                }
                assertEquals(Arrays.asList(2018, 2021, 2022), years);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQueryPinnedToEntityIriEqualsSingleHit() {
        // Issue #73: reserved property urn:jena:lucene:index#entityIri pins luc:query
        // to a specific document by IRI. With CQL filter '=' on entityIri, exactly one
        // hit must come back regardless of how broad the text search is.
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" " +
            "    '{\"op\":\"=\",\"args\":[{\"property\":\"urn:jena:lucene:index#entityIri\"},\""
            + NS + "doc3\"]}' \"\" 20 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                Set<String> subjects = new HashSet<>();
                while (rs.hasNext()) {
                    subjects.add(rs.next().getResource("s").getURI());
                }
                assertEquals("Pinning to entityIri should return exactly one entity",
                    Collections.singleton(NS + "doc3"), subjects);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQueryPinnedToEntityIriInSet() {
        // Issue #73: 'in' on entityIri pins to a set of entities.
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" " +
            "    '{\"op\":\"in\",\"args\":[{\"property\":\"urn:jena:lucene:index#entityIri\"},"
            + "[\"" + NS + "doc1\",\"" + NS + "doc5\"]]}' \"\" 20 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                Set<String> subjects = new HashSet<>();
                while (rs.hasNext()) {
                    subjects.add(rs.next().getResource("s").getURI());
                }
                Set<String> expected = new HashSet<>(Arrays.asList(NS + "doc1", NS + "doc5"));
                assertEquals("'in' on entityIri should return only the listed entities",
                    expected, subjects);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQueryEntityIriCombinedWithFieldFilter() {
        // Issue #73: reserved entityIri compose with other filters; AND should
        // intersect to either zero or one hit (the pinned entity if its other
        // attributes match the field filter).
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" " +
            "    '{\"op\":\"and\",\"args\":[" +
            "      {\"op\":\"=\",\"args\":[{\"property\":\"urn:jena:lucene:index#entityIri\"},\""
            + NS + "doc3\"]}," +
            "      {\"op\":\"=\",\"args\":[{\"property\":\"urn:jena:lucene:field#category\"},\""
            + NS + "category/technology\"]}" +
            "    ]}' \"\" 20 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                Set<String> subjects = new HashSet<>();
                while (rs.hasNext()) {
                    subjects.add(rs.next().getResource("s").getURI());
                }
                // doc3 is in category/technology, so the AND matches.
                assertEquals(Collections.singleton(NS + "doc3"), subjects);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testLucQueryEntityIriUnknownReturnsEmpty() {
        // Pinning to a non-existent IRI returns no hits.
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" " +
            "    '{\"op\":\"=\",\"args\":[{\"property\":\"urn:jena:lucene:index#entityIri\"},"
            + "\"http://example.org/nonexistent\"]}' \"\" 20 0)\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                assertFalse("Pinning to unknown IRI should yield zero hits", rs.hasNext());
            }
        } finally {
            dataset.end();
        }
    }
}
