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
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.apache.jena.sparql.path.PathFactory;

/**
 * Tests for offset paging support in luc:query.
 */
public class TestOffsetPaging {

    private static final String NS = "http://example.org/";
    private static final Node ITEM_CLASS = NodeFactory.createURI(NS + "Item");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node CATEGORY_PRED = NodeFactory.createURI(NS + "category");

    private Dataset dataset;

    @Before
    public void setUp() {
        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);
        FieldDef categoryField = new FieldDef("category", FieldType.KEYWORD, null,
            true, true, true, false, true, false);

        List<FieldOccurrence> rootOccurrences = Arrays.asList(
            occurrence(titleField, TITLE_PRED),
            occurrence(categoryField, CATEGORY_PRED));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "ItemShape"),
            Collections.singleton(ITEM_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, categoryField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
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

        // Load 10 items all matching "item"
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            for (int i = 1; i <= 10; i++) {
                var res = ResourceFactory.createResource(NS + "item" + i);
                model.add(res, RDF.type, ResourceFactory.createResource(NS + "Item"));
                model.add(res, ResourceFactory.createProperty(NS + "title"),
                    "item number " + i);
                model.add(res, ResourceFactory.createProperty(NS + "category"), "cat");
            }
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private static FieldOccurrence occurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(
            field,
            PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            Collections.singleton(predicate),
            null, null, null, null);
    }

    @After
    public void tearDown() {
        if (dataset != null) dataset.close();
    }

    private List<String> runQuery(int limit, int offset) {
        String q =
            "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"item\" \"\" \"\" " + limit + " " + offset + ")\n" +
            "}";
        dataset.begin(ReadWrite.READ);
        try {
            List<String> results = new ArrayList<>();
            try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(q), dataset)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    results.add(rs.next().getResource("s").getURI());
                }
            }
            return results;
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testOffsetZeroSameAsNoOffset() {
        List<String> page = runQuery(10, 0);
        assertEquals("offset=0 with limit=10 should return all 10 items", 10, page.size());
    }

    @Test
    public void testFirstPage() {
        List<String> page = runQuery(3, 0);
        assertEquals("First page should have 3 results", 3, page.size());
    }

    @Test
    public void testSecondPage() {
        List<String> page1 = runQuery(3, 0);
        List<String> page2 = runQuery(3, 3);
        assertEquals(3, page2.size());
        // Pages should not overlap
        Set<String> overlap = new HashSet<>(page1);
        overlap.retainAll(page2);
        assertTrue("Pages should not overlap", overlap.isEmpty());
    }

    @Test
    public void testPagesExhaustResults() {
        Set<String> all = new HashSet<>();
        for (int offset = 0; offset < 10; offset += 3) {
            all.addAll(runQuery(3, offset));
        }
        assertEquals("Paging through all results should yield 10 unique items", 10, all.size());
    }

    @Test
    public void testOffsetBeyondResults() {
        List<String> page = runQuery(10, 100);
        assertTrue("Offset beyond total hits should return empty", page.isEmpty());
    }

    @Test
    public void testPartialLastPage() {
        // 10 items, offset=8, limit=5 → should get 2
        List<String> page = runQuery(5, 8);
        assertEquals("Partial last page should return remaining items", 2, page.size());
    }

    @Test
    public void testTotalHitsUnchangedByOffset() {
        String q =
            "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?totalHits WHERE {\n" +
            "  (?hit ?s ?score ?totalHits) luc:query (\"default\" \"default\" \"item\" \"\" \"\" 3 5)\n" +
            "} LIMIT 1";
        dataset.begin(ReadWrite.READ);
        try {
            try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(q), dataset)) {
                ResultSet rs = qe.execSelect();
                assertTrue(rs.hasNext());
                long total = rs.next().getLiteral("totalHits").getLong();
                assertEquals("totalHits should reflect full match count, not page size", 10, total);
            }
        } finally {
            dataset.end();
        }
    }

    /**
     * A relevance score cannot carry result order: a match-all query scores every document
     * identically, and real queries tie often enough that ordering by score is unreliable.
     * {@code ?rank} is the position itself, so a consumer holding an unordered result set
     * (a CONSTRUCT graph) can always reconstruct the order the engine returned.
     */
    @Test
    public void testRankIsBoundInResultOrder() {
        String q =
            "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s ?rank WHERE {\n" +
            "  (?hit ?s ?score ?totalHits ?rank) luc:query (\"default\" \"default\" \"item\" \"\" \"\" 4 0)\n" +
            "}";
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(q), dataset)) {
            List<Integer> ranks = new ArrayList<>();
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                ranks.add(rs.next().getLiteral("rank").getInt());
            }
            assertEquals("rank should count the page from 0", List.of(0, 1, 2, 3), ranks);
        } finally {
            dataset.end();
        }
    }

    /** Rank is the position in the whole result set, so it continues across pages. */
    @Test
    public void testRankIsGlobalNotPageRelative() {
        String q =
            "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s ?rank WHERE {\n" +
            "  (?hit ?s ?score ?totalHits ?rank) luc:query (\"default\" \"default\" \"item\" \"\" \"\" 3 5)\n" +
            "}";
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(q), dataset)) {
            List<Integer> ranks = new ArrayList<>();
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                ranks.add(rs.next().getLiteral("rank").getInt());
            }
            assertEquals("offset 5 should start at rank 5", List.of(5, 6, 7), ranks);
        } finally {
            dataset.end();
        }
    }

    /**
     * ?rank sits last, so ?totalHits keeps the fourth position it held before rank existed
     * and a subject list written against the older shape still binds it.
     */
    @Test
    public void testRankFollowsTotalHits() {
        String q =
            "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?rank ?totalHits WHERE {\n" +
            "  (?hit ?s ?score ?totalHits ?rank) luc:query (\"default\" \"default\" \"item\" \"\" \"\" 3 5)\n" +
            "} LIMIT 1";
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(q), dataset)) {
            ResultSet rs = qe.execSelect();
            assertTrue(rs.hasNext());
            QuerySolution qs = rs.next();
            assertEquals(5, qs.getLiteral("rank").getInt());
            assertEquals(10, qs.getLiteral("totalHits").getLong());
        } finally {
            dataset.end();
        }
    }

    @Test(expected = QueryExecException.class)
    public void testNegativeOffsetRejected() {
        runQuery(10, -1);
    }

    /**
     * Five is the whole subject list. There was a sixth slot, {@code ?graph}, which never
     * bound a value — a SHACL document is assembled from a union view over every graph, so
     * a hit has no single source graph. It was removed rather than left as a hole that
     * pushed ?rank out of position; graph provenance belongs in a doc-level field instead
     * (docs/2026-04-08-graph-filtering-target-model.md). Rejecting six keeps the removal
     * loud: a query still naming the old slot fails at build rather than silently binding
     * ?graph to a rank integer.
     */
    @Test
    public void testSixElementSubjectListRejected() {
        String q =
            "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT * WHERE {\n" +
            "  (?hit ?s ?score ?totalHits ?graph ?rank) luc:query (\"default\" \"default\" \"item\" \"\" \"\" 3 0)\n" +
            "}";
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(q), dataset)) {
            qe.execSelect().hasNext();
            fail("A six-element subject list should be rejected");
        } catch (QueryBuildException e) {
            assertTrue("Message should state the permitted arity: " + e.getMessage(),
                e.getMessage().contains("must be 1-5"));
        } finally {
            dataset.end();
        }
    }
}
