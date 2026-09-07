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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.VectorDef;
import org.apache.jena.query.text.ShaclIndexMapping.VectorSimilarity;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.query.text.embedding.HashingEmbeddingProvider;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end tests for the VECTOR field type: index-time embedding, KNN retrieval,
 * filtered KNN, and facet counts scoped to the top-k.
 * <p>
 * These run on {@link HashingEmbeddingProvider}, which measures lexical overlap rather
 * than meaning. That is a deliberate limit on what can be asserted here: these tests
 * verify the <em>plumbing</em> — that a vector is written, that KNN retrieves by
 * similarity, that a filter is pushed into the traversal, that facets count within the
 * top-k. They cannot and do not verify semantic quality, which depends entirely on the
 * model behind a real provider.
 */
public class TestVectorSearch {

    private static final String NS = "http://example.org/";
    private static final Node DOC_CLASS = NodeFactory.createURI(NS + "Document");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node BODY_PRED = NodeFactory.createURI(NS + "body");
    private static final Node CATEGORY_PRED = NodeFactory.createURI(NS + "category");

    private static final String EMBEDDING_IRI = "urn:jena:lucene:field#embedding";
    private static final String TITLE_IRI = "urn:jena:lucene:field#title";
    private static final String BODY_IRI = "urn:jena:lucene:field#body";
    private static final String CATEGORY_IRI = "urn:jena:lucene:field#category";

    private static final int DIMENSION = 64;

    /** CQL2-JSON: category = "agriculture". */
    private static final String CATEGORY_IS_AGRICULTURE =
        "{\"op\":\"=\",\"args\":[{\"property\":\"" + CATEGORY_IRI + "\"},\"agriculture\"]}";

    /** The index most tests run against; the first one {@link #buildIndex} creates. */
    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;
    private final List<Dataset> datasets = new ArrayList<>();

    @Before
    public void setUp() {
        textIndex = buildIndex(TextIndexConfig.DEFAULT_KNN_TOP_K);
    }

    /**
     * Build a self-contained index (its own directory, dataset and loaded documents) with
     * the given KNN top-k. Most tests use the default; the filter-pushdown test needs a
     * small k to make pushed and post-applied filtering give different answers.
     */
    private ShaclTextIndexLucene buildIndex(int knnTopK) {
        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);
        FieldDef bodyField = new FieldDef("body", FieldType.TEXT, null,
            true, true, false, false, false, true);
        FieldDef categoryField = new FieldDef("category", FieldType.KEYWORD, null,
            true, true, true, false, false, false);

        FieldDef embeddingField = vectorField("embedding",
            new VectorDef(DIMENSION, VectorSimilarity.COSINE, List.of(TITLE_IRI, BODY_IRI)));

        List<FieldOccurrence> rootOccurrences = Arrays.asList(
            occurrence(titleField, PathFactory.pathLink(TITLE_PRED), Collections.singleton(TITLE_PRED)),
            occurrence(bodyField, PathFactory.pathLink(BODY_PRED), Collections.singleton(BODY_PRED)),
            occurrence(categoryField, PathFactory.pathLink(CATEGORY_PRED), Collections.singleton(CATEGORY_PRED)));

        IndexProfile docProfile = new IndexProfile(
            NodeFactory.createURI(NS + "DocShape"),
            Collections.singleton(DOC_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, bodyField, categoryField, embeddingField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(docProfile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);
        config.setEmbeddingProvider(new HashingEmbeddingProvider(DIMENSION));
        config.setKnnTopK(knnTopK);

        ShaclTextIndexLucene index = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);

        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(
            baseDs.asDatasetGraph(), index, mapping);
        Dataset ds = TextDatasetFactory.create(baseDs, index, true, producer);
        datasets.add(ds);
        if (dataset == null) {
            dataset = ds;
        }

        loadTestData(ds, index);
        return index;
    }

    private void loadTestData(Dataset dataset, ShaclTextIndexLucene textIndex) {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            addDoc(model, "doc1", "Copper mining exploration",
                "Drilling for copper ore in the eastern goldfields", "geology");
            addDoc(model, "doc2", "Copper ore processing",
                "Smelting copper concentrate at the refinery", "processing");
            addDoc(model, "doc3", "Wheat harvest report",
                "Grain yields across the southern farming belt", "agriculture");
            addDoc(model, "doc4", "Barley planting season",
                "Grain sowing schedules for the southern belt", "agriculture");
            dataset.commit();
        } finally {
            dataset.end();
        }
        textIndex.commit();
    }

    private void addDoc(Model model, String id, String title, String body, String category) {
        Resource doc = ResourceFactory.createResource(NS + id);
        model.add(doc, RDF.type, ResourceFactory.createResource(NS + "Document"));
        model.add(doc, ResourceFactory.createProperty(NS + "title"), title);
        model.add(doc, ResourceFactory.createProperty(NS + "body"), body);
        model.add(doc, ResourceFactory.createProperty(NS + "category"), category);
    }

    @After
    public void tearDown() {
        for (Dataset ds : datasets) {
            ds.close();
        }
        datasets.clear();
        dataset = null;
    }

    // ---- Retrieval ----

    @Test
    public void testKnnRetrievesNearestFirst() {
        List<SearchHit> hits = search("copper ore drilling", null, 4);

        assertFalse("KNN search returned no hits", hits.isEmpty());
        // Both copper documents share vocabulary with the query; neither grain document
        // does. Under the hashing provider that is the whole of the similarity signal.
        List<String> top2 = uris(hits).subList(0, 2);
        assertTrue("Expected both copper docs in the top 2, got " + top2,
            top2.contains(NS + "doc1") && top2.contains(NS + "doc2"));
    }

    @Test
    public void testKnnRanksByDescendingScore() {
        List<SearchHit> hits = search("copper ore drilling", null, 4);

        assertTrue("Expected at least 2 hits to compare", hits.size() >= 2);
        for (int i = 1; i < hits.size(); i++) {
            assertTrue("Hits are not in descending score order at position " + i
                    + ": " + hits.get(i - 1).getScore() + " then " + hits.get(i).getScore(),
                hits.get(i - 1).getScore() >= hits.get(i).getScore());
        }
    }

    @Test
    public void testEntityWithNoSourceTextIsNotACandidate() {
        // A document with a category but neither title nor body has nothing to embed, so
        // it must not appear as a neighbour of anything.
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource doc = ResourceFactory.createResource(NS + "doc5");
            model.add(doc, RDF.type, ResourceFactory.createResource(NS + "Document"));
            model.add(doc, ResourceFactory.createProperty(NS + "category"), "geology");
            dataset.commit();
        } finally {
            dataset.end();
        }
        textIndex.commit();

        List<String> uris = uris(search("copper ore drilling", null, 10));
        assertFalse("An entity with no embeddable text was returned as a KNN neighbour",
            uris.contains(NS + "doc5"));
    }

    // ---- Filtered KNN ----

    @Test
    public void testFilterIsAppliedToKnnSearch() {
        List<String> uris = uris(search("copper ore drilling", CATEGORY_IS_AGRICULTURE, 4));

        assertFalse("Filtered KNN returned nothing", uris.isEmpty());
        for (String uri : uris) {
            assertTrue("Filtered KNN returned a document outside the filter: " + uri,
                uri.equals(NS + "doc3") || uri.equals(NS + "doc4"));
        }
    }

    @Test
    public void testFilterIsPushedIntoTraversalNotAppliedAfterwards() {
        // The distinction only shows up when k is smaller than the number of documents
        // that out-rank the filtered ones, so this uses its own index with knnTopK = 2.
        //
        // The two nearest neighbours of "copper ore drilling" are both copper documents,
        // neither of which is agriculture. Post-filtering a k=2 result would therefore
        // return NOTHING. Pushing the filter into the HNSW traversal asks for the two
        // nearest documents *that are already agriculture*, and finds both.
        //
        // This is the property the whole design rests on — it is what the design note
        // calls out as pgvector's known sore spot — so it is asserted head-on.
        ShaclTextIndexLucene narrowIndex = buildIndex(2);
        try {
            List<SearchHit> unfiltered = narrowIndex.searchWithHitIds(
                narrowIndex.resolveSearchFields(Collections.singletonList(EMBEDDING_IRI)),
                "copper ore drilling", null, null, null, null, 10);
            List<String> unfilteredUris = uris(unfiltered);
            assertEquals("Precondition: k=2 should retrieve exactly 2 neighbours", 2, unfilteredUris.size());
            assertFalse("Precondition: neither top-2 neighbour should be an agriculture doc",
                unfilteredUris.contains(NS + "doc3") || unfilteredUris.contains(NS + "doc4"));

            List<String> filtered = uris(narrowIndex.searchWithHitIds(
                narrowIndex.resolveSearchFields(Collections.singletonList(EMBEDDING_IRI)),
                "copper ore drilling", CqlParser.parse(CATEGORY_IS_AGRICULTURE), null, null, null, 10));

            assertEquals("Filter was applied after the KNN search rather than pushed into it: "
                + "with k=2 the unfiltered neighbours are all non-agriculture, so post-filtering "
                + "yields nothing. Got " + filtered, 2, filtered.size());
            assertTrue(filtered.contains(NS + "doc3"));
            assertTrue(filtered.contains(NS + "doc4"));
        }
        finally { /* the dataset owning this index is closed in tearDown */ }
    }

    // ---- Facets under KNN ----

    @Test
    public void testFacetCountsAreScopedToKnnResults() {
        Map<String, List<FacetValue>> facets = textIndex.getFacetCountsWithCql(
            "grain southern belt",
            Collections.singletonList(EMBEDDING_IRI),
            FacetRequest.flatOnly(Collections.singletonList(CATEGORY_IRI)),
            CqlParser.parse(CATEGORY_IS_AGRICULTURE),
            10, 0);

        List<FacetValue> categories = facets.get("category");
        assertNotNull("No facet counts returned for a KNN query", categories);
        long total = categories.stream().mapToLong(FacetValue::getCount).sum();
        assertEquals("Facet counts should cover only the filtered KNN result set", 2, total);
        assertEquals("agriculture", categories.get(0).getValue());
    }

    // ---- Rejected combinations ----

    @Test
    public void testMixingVectorAndTextFieldsIsRejected() {
        try {
            textIndex.searchWithHitIds(
                textIndex.resolveSearchFields(Arrays.asList(EMBEDDING_IRI, TITLE_IRI)),
                "copper", null, null, null, null, 10);
            fail("Expected a mixed vector/text fieldSpec to be rejected");
        } catch (TextIndexException ex) {
            assertTrue("Error should explain that hybrid retrieval is unimplemented: " + ex.getMessage(),
                ex.getMessage().contains("Hybrid"));
        }
    }

    @Test
    public void testEmptyQueryTextIsRejectedForVectorSearch() {
        try {
            search("", null, 10);
            fail("Expected an empty query string to be rejected for a vector search");
        } catch (TextIndexException ex) {
            assertTrue("Error should explain there is no match-all form: " + ex.getMessage(),
                ex.getMessage().contains("match-all"));
        }
    }

    @Test
    public void testMatchAllIsRejectedForVectorSearch() {
        try {
            search("*", null, 10);
            fail("Expected \"*\" to be rejected for a vector search");
        } catch (TextIndexException ex) {
            assertTrue("Error should explain there is no match-all form: " + ex.getMessage(),
                ex.getMessage().contains("match-all"));
        }
    }

    // ---- Helpers ----

    private List<SearchHit> search(String queryText, String cqlFilter, int limit) {
        return textIndex.searchWithHitIds(
            textIndex.resolveSearchFields(Collections.singletonList(EMBEDDING_IRI)),
            queryText,
            cqlFilter == null ? null : CqlParser.parse(cqlFilter),
            null, null, null, limit);
    }

    private static List<String> uris(List<SearchHit> hits) {
        List<String> uris = new ArrayList<>();
        for (SearchHit hit : hits) {
            uris.add(hit.getEntityNode().getURI());
        }
        return uris;
    }

    private static FieldDef vectorField(String name, VectorDef vectorDef) {
        return new FieldDef(name, FieldType.VECTOR, null, null,
            false, true, false, false, false, false, false,
            NodeFactory.createURI("urn:jena:lucene:field#" + name), null, vectorDef);
    }

    private static FieldOccurrence occurrence(FieldDef field, Path path, Set<Node> predicates) {
        return new FieldOccurrence(field, path,
            ShaclIndexAssembler.extractPathVariants(path), predicates,
            null, null, null, null);
    }
}
