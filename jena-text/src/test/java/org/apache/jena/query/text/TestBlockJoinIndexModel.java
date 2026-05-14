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
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the index-time block-join layout (block-join PR-A).
 * <p>
 * Validates that:
 * <ol>
 *   <li>Entities with {@code idx:nested} fields produce one parent + N child Lucene docs.</li>
 *   <li>Entities without nested defs produce exactly one (parent) doc.</li>
 *   <li>Reads remain parent-scoped — child docs don't double-count or leak into result sets.</li>
 *   <li>Block ordering convention is preserved (children precede parent in the segment).</li>
 *   <li>Update + delete operations affect the whole block atomically.</li>
 * </ol>
 * <p>
 * Query-time same-child correlation lives in PR-B; this PR only changes the on-disk layout
 * while preserving Phase 1 read behaviour (denormalised parent fields).
 */
public class TestBlockJoinIndexModel {

    private static final String NS = "http://example.org/";
    private static final String SCHEMA = "https://schema.org/";
    private static final Node BOREHOLE_CLASS = NodeFactory.createURI(NS + "Borehole");
    private static final Node LABEL_PRED = NodeFactory.createURI(NS + "label");
    private static final Node IDENTIFIER_PRED = NodeFactory.createURI(SCHEMA + "identifier");
    private static final Node ID_TYPE_PRED = NodeFactory.createURI(SCHEMA + "propertyID");
    private static final Node ID_VALUE_PRED = NodeFactory.createURI(SCHEMA + "value");

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;
    private ByteBuffersDirectory directory;

    @Before
    public void setUp() {
        FieldDef labelField = new FieldDef("label", FieldType.TEXT, null,
            true, true, false, false, false, true);
        FieldDef idType = new FieldDef("identifierType", FieldType.KEYWORD, null,
            true, true, true, false, true, false);
        FieldDef idValue = new FieldDef("identifierValueExact", FieldType.KEYWORD, null,
            true, true, true, false, true, false);

        NestedDef identifierNest = new NestedDef(
            "identifier",
            PathFactory.pathLink(IDENTIFIER_PRED),
            Collections.singletonList(new JoinStep(IDENTIFIER_PRED, false)),
            Collections.singleton(IDENTIFIER_PRED),
            Arrays.asList(occurrence(idType, ID_TYPE_PRED), occurrence(idValue, ID_VALUE_PRED)),
            Collections.emptyList());

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "BoreholeShape"),
            Collections.singleton(BOREHOLE_CLASS),
            "uri", "docType",
            Arrays.asList(labelField, idType, idValue),
            Collections.singletonList(occurrence(labelField, LABEL_PRED)),
            Collections.emptyList(),
            Collections.singletonList(identifierNest));

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);
        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        directory = new ByteBuffersDirectory();
        textIndex = new ShaclTextIndexLucene(directory, config);
        Dataset base = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(
            base.asDatasetGraph(), textIndex, mapping);
        dataset = TextDatasetFactory.create(base, textIndex, true, producer);
    }

    @After
    public void tearDown() {
        if (dataset != null) dataset.close();
    }

    private static FieldOccurrence occurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(
            field, PathFactory.pathLink(predicate),
            ShaclIndexAssembler.extractPathVariants(PathFactory.pathLink(predicate)),
            Collections.singleton(predicate),
            null, null, null, null);
    }

    private void addBorehole(String id, String label, String[][] identifiers) {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource bh = ResourceFactory.createResource(NS + id);
            model.add(bh, RDF.type, ResourceFactory.createResource(NS + "Borehole"));
            model.add(bh, ResourceFactory.createProperty(NS + "label"), label);
            for (int i = 0; i < identifiers.length; i++) {
                Resource idNode = ResourceFactory.createResource(NS + id + "-id-" + i);
                model.add(bh, ResourceFactory.createProperty(SCHEMA + "identifier"), idNode);
                model.add(idNode, ResourceFactory.createProperty(SCHEMA + "propertyID"), identifiers[i][0]);
                model.add(idNode, ResourceFactory.createProperty(SCHEMA + "value"), identifiers[i][1]);
            }
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private int countDocsWithBlockKind(String kind) throws Exception {
        textIndex.commit();
        try (IndexReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            return searcher.count(new TermQuery(
                new Term(ShaclTextIndexLucene.BLOCK_KIND_FIELD, kind)));
        }
    }

    // ---------------------------------------------------------------
    // 1. Block layout: parent + N children
    // ---------------------------------------------------------------

    @Test
    public void testEntityWithNestedRecordsEmitsParentPlusChildBlock() throws Exception {
        addBorehole("bh1", "Site Alpha", new String[][] {
            {"Company", "BHP"},
            {"HoleNumber", "8412"}
        });

        // 1 parent + 2 children
        assertEquals("Should have one parent doc",
            1, countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_PARENT));
        assertEquals("Should have one child doc per nested record",
            2, countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_CHILD));
    }

    @Test
    public void testEntityWithoutNestedRecordsEmitsParentOnly() throws Exception {
        addBorehole("bh-plain", "No identifiers here", new String[0][]);
        assertEquals(1, countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_PARENT));
        assertEquals(0, countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_CHILD));
    }

    @Test
    public void testMultipleEntitiesEachProduceTheirOwnBlock() throws Exception {
        addBorehole("bh1", "A", new String[][] {{"Company", "X"}, {"Company", "Y"}});
        addBorehole("bh2", "B", new String[][] {{"HoleNumber", "1"}});
        addBorehole("bh3", "C", new String[0][]);

        assertEquals("3 entities → 3 parent docs", 3,
            countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_PARENT));
        assertEquals("3 nested records total → 3 child docs", 3,
            countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_CHILD));
    }

    // ---------------------------------------------------------------
    // 2. Reads remain parent-scoped
    // ---------------------------------------------------------------

    @Test
    public void testQueryReturnsOneHitPerEntityEvenWhenChildDocsMatch() {
        // The label field is on the parent only. The query should return one hit
        // regardless of child doc presence.
        addBorehole("bh1", "Site Alpha", new String[][] {{"Company", "X"}, {"Company", "Y"}});

        List<TextHit> hits = textIndex.query(LABEL_PRED, "alpha", null, null);
        assertEquals("Parent-only field — one hit", 1, hits.size());
        assertEquals(NS + "bh1", hits.get(0).getNode().getURI());
    }

    @Test
    public void testCountQueryWithEmptyFilterCountsParentsOnly() {
        // Add 1 entity with 3 child records. Index then has 1 parent + 3 child docs = 4 total.
        // countQuery with no filter must return 1 (parent doc count), not 4 (total doc count).
        addBorehole("bh1", "Site Alpha", new String[][] {
            {"Company", "BHP"},
            {"Company", "Rio"},
            {"HoleNumber", "8412"}
        });

        long count = textIndex.countQuery(null, null, null);
        assertEquals("countQuery must count parent docs only, not the full block",
            1L, count);
    }

    // ---------------------------------------------------------------
    // 3. Updates affect the whole block atomically
    // ---------------------------------------------------------------

    @Test
    public void testUpdatingAnEntityReplacesItsEntireBlock() throws Exception {
        addBorehole("bh1", "Site Alpha", new String[][] {
            {"Company", "BHP"},
            {"HoleNumber", "8412"}
        });

        assertEquals(1, countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_PARENT));
        assertEquals(2, countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_CHILD));

        // Re-add (same URI, different identifier count)
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource bh = ResourceFactory.createResource(NS + "bh1");
            // Remove all existing identifier triples
            model.removeAll(bh, ResourceFactory.createProperty(SCHEMA + "identifier"), null);
            for (int i = 0; i < 2; i++) {
                Resource old = ResourceFactory.createResource(NS + "bh1-id-" + i);
                model.removeAll(old, null, null);
            }
            // Add a single new identifier
            Resource newId = ResourceFactory.createResource(NS + "bh1-new");
            model.add(bh, ResourceFactory.createProperty(SCHEMA + "identifier"), newId);
            model.add(newId, ResourceFactory.createProperty(SCHEMA + "propertyID"), "Other");
            model.add(newId, ResourceFactory.createProperty(SCHEMA + "value"), "Z");
            dataset.commit();
        } finally {
            dataset.end();
        }

        assertEquals("Parent count unchanged after rebuild", 1,
            countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_PARENT));
        assertEquals("Child count reflects new identifier count (1, not stale 2)", 1,
            countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_CHILD));
    }

    @Test
    public void testDeletingTypeRemovesEntireBlock() throws Exception {
        addBorehole("bh1", "Site Alpha", new String[][] {
            {"Company", "BHP"},
            {"HoleNumber", "8412"}
        });

        assertEquals(1, countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_PARENT));
        assertEquals(2, countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_CHILD));

        // Removing rdf:type triggers deleteEntityByUri on the listener path
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource bh = ResourceFactory.createResource(NS + "bh1");
            model.remove(bh, RDF.type, ResourceFactory.createResource(NS + "Borehole"));
            dataset.commit();
        } finally {
            dataset.end();
        }

        assertEquals("Deleting type should remove the parent doc", 0,
            countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_PARENT));
        assertEquals("Deleting type should remove the child docs too (block-aware delete)", 0,
            countDocsWithBlockKind(ShaclTextIndexLucene.BLOCK_KIND_CHILD));
    }

    // ---------------------------------------------------------------
    // 4. Block ordering convention (parent last)
    // ---------------------------------------------------------------

    @Test
    public void testBlockOrderingChildrenFirstParentLast() throws Exception {
        // Add a single entity; verify within the segment its children appear before
        // the parent doc. This is the Lucene block convention required for any
        // future ToParentBlockJoinQuery to work.
        addBorehole("bh1", "Alpha", new String[][] {
            {"Company", "BHP"},
            {"HoleNumber", "8412"}
        });
        textIndex.commit();

        try (IndexReader reader = DirectoryReader.open(directory)) {
            // Find the parent doc id
            IndexSearcher searcher = new IndexSearcher(reader);
            org.apache.lucene.search.TopDocs parentHits = searcher.search(
                new TermQuery(new Term(ShaclTextIndexLucene.BLOCK_KIND_FIELD,
                    ShaclTextIndexLucene.BLOCK_KIND_PARENT)),
                10);
            assertEquals(1, parentHits.totalHits.value());
            int parentDocId = parentHits.scoreDocs[0].doc;

            // All child doc ids must be < parentDocId (children come first in the block)
            org.apache.lucene.search.TopDocs childHits = searcher.search(
                new TermQuery(new Term(ShaclTextIndexLucene.BLOCK_KIND_FIELD,
                    ShaclTextIndexLucene.BLOCK_KIND_CHILD)),
                10);
            assertEquals(2, childHits.totalHits.value());
            for (org.apache.lucene.search.ScoreDoc sd : childHits.scoreDocs) {
                assertTrue("Child doc " + sd.doc + " must precede parent doc " + parentDocId
                    + " (block ordering convention)",
                    sd.doc < parentDocId);
            }
        }
    }
}
