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
 * Tests for the luc:match property function.
 */
public class TestTextMatchPF {

    private static final String NS = "http://example.org/";
    private static final String FIELD_IRI_PREFIX = "urn:jena:lucene:field#";
    private static final Node BOOK_CLASS = NodeFactory.createURI(NS + "Book");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node CATEGORY_PRED = NodeFactory.createURI(NS + "category");

    private Dataset dataset;

    @Before
    public void setUp() {
        TextQuery.init();

        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);

        FieldDef categoryField = new FieldDef("category", FieldType.KEYWORD, null,
            true, true, true, false, true, false);

        List<FieldOccurrence> rootOccurrences = Arrays.asList(
            occurrence(titleField, PathFactory.pathLink(TITLE_PRED), Collections.singleton(TITLE_PRED)),
            occurrence(categoryField, PathFactory.pathLink(CATEGORY_PRED), Collections.singleton(CATEGORY_PRED)));

        IndexProfile bookProfile = new IndexProfile(
            NodeFactory.createURI(NS + "BookShape"),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, categoryField),
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
            addBook(model, "doc1", "Introduction to Machine Learning", NS + "category/technology");
            addBook(model, "doc2", "Deep Learning Neural Networks", NS + "category/technology");
            addBook(model, "doc3", "Quantum Physics Explained", NS + "category/science");
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void addBook(Model model, String id, String title, String categoryUri) {
        Resource book = ResourceFactory.createResource(NS + id);
        model.add(book, RDF.type, ResourceFactory.createResource(NS + "Book"));
        model.add(book, ResourceFactory.createProperty(NS + "title"), title);
        model.add(book, ResourceFactory.createProperty(NS + "category"), ResourceFactory.createResource(categoryUri));
    }

    private static FieldOccurrence occurrence(FieldDef field, Path path, Set<Node> predicates) {
        return new FieldOccurrence(
            field,
            path,
            ShaclIndexAssembler.extractPathVariants(path),
            predicates,
            null, null, null, null);
    }

    @After
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    @Test
    public void testMatchReturnsSingleFieldForSingleFieldQuery() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?hit ?s ?field ?value WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" '[\"" + FIELD_IRI_PREFIX + "title\"]' \"learning\" \"\" \"\" 10) .\n" +
            "  (?hit ?field ?value) luc:match () .\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            int count = 0;
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                assertNotNull("?hit should be bound", sol.get("hit"));
                assertNotNull("?field should be bound", sol.get("field"));
                assertEquals("Field should be title IRI",
                    FIELD_IRI_PREFIX + "title", sol.getResource("field").getURI());
                assertNotNull("?value should be bound", sol.get("value"));
                count++;
            }
            assertTrue("Should have match results", count > 0);
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testMatchJoinsWithQueryByHitId() {
        // Verify that ?hit from luc:query joins correctly with luc:match
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s ?field WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"machine\" \"\" \"\" 10) .\n" +
            "  (?hit ?field ?value) luc:match () .\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            Set<String> entities = new HashSet<>();
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                entities.add(sol.getResource("s").getURI());
                assertNotNull("?field should be bound", sol.get("field"));
            }
            assertTrue("Should find doc1 (Machine Learning)", entities.contains(NS + "doc1"));
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testMatchWithOptional() {
        // Use OPTIONAL so hits without field matches still appear
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?hit ?s ?field WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" \"\" \"\" 10) .\n" +
            "  OPTIONAL { (?hit ?field ?value) luc:match () . }\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            int count = 0;
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                assertNotNull("?hit should be bound", sol.get("hit"));
                assertNotNull("?s should be bound", sol.get("s"));
                count++;
            }
            assertTrue("Should have results", count > 0);
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testMatchFieldValueIsCorrectType() {
        // For TEXT fields, ?value should be a literal
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?value WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" '[\"" + FIELD_IRI_PREFIX + "title\"]' \"machine\" \"\" \"\" 10) .\n" +
            "  (?hit ?field ?value) luc:match () .\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            assertTrue("Should have results", rs.hasNext());
            QuerySolution sol = rs.next();
            assertTrue("Value for TEXT field should be a literal", sol.get("value").isLiteral());
            assertTrue("Value should contain 'Machine'",
                sol.getLiteral("value").getString().contains("Machine"));
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testMatchNoResultsWithoutQuery() {
        // luc:match without a preceding luc:query should return no results
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?hit ?field WHERE {\n" +
            "  (?hit ?field ?value) luc:match () .\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            assertFalse("luc:match without luc:query should return no results", rs.hasNext());
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testHitIdIsBlankNode() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?hit WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"learning\" \"\" \"\" 10) .\n" +
            "}";

        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            assertTrue("Should have results", rs.hasNext());
            QuerySolution sol = rs.next();
            assertTrue("?hit should be a blank node (anonymous resource)",
                sol.get("hit").isAnon());
        } finally {
            dataset.end();
        }
    }
}
