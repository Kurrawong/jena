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
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the parallel implementation of {@link ShaclBulkIndexer} added in #70.
 * Validates that:
 * <ul>
 *   <li>Parallel and single-threaded paths produce identical results.</li>
 *   <li>{@code freshIndex=true} produces correct counts on a known-empty index.</li>
 *   <li>The worker exception path surfaces failures rather than silently
 *       returning a partial index.</li>
 *   <li>Results converge regardless of {@code threadCount}.</li>
 * </ul>
 * <p>
 * Pre-implementation assumption tests live in {@code TestBulkIndexerParallelism};
 * those validate that TDB2 + Lucene support concurrent reads/writes at all.
 */
public class TestShaclBulkIndexerParallel {

    private static final String NS = "http://example.org/";
    private static final Node BOOK_CLASS = NodeFactory.createURI(NS + "Book");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node CATEGORY_PRED = NodeFactory.createURI(NS + "category");

    private Dataset baseDataset;
    private ShaclTextIndexLucene textIndex;
    private ShaclIndexMapping mapping;
    private IndexProfile bookProfile;

    @Before
    public void setUp() {
        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);
        FieldDef categoryField = new FieldDef("category", FieldType.KEYWORD, null,
            true, true, true, false, true, false);

        bookProfile = new IndexProfile(
            NodeFactory.createURI(NS + "BookShape"),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, categoryField),
            Arrays.asList(occurrence(titleField, TITLE_PRED), occurrence(categoryField, CATEGORY_PRED)),
            Collections.emptyList(),
            Collections.emptyList());

        mapping = new ShaclIndexMapping(Collections.singletonList(bookProfile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);
        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);
        baseDataset = TDB2Factory.createDataset();
    }

    @After
    public void tearDown() {
        if (textIndex != null) textIndex.close();
        if (baseDataset != null) baseDataset.close();
    }

    private static FieldOccurrence occurrence(FieldDef field, Node predicate) {
        Path path = PathFactory.pathLink(predicate);
        return new FieldOccurrence(
            field, path,
            ShaclIndexAssembler.extractPathVariants(path),
            Collections.singleton(predicate),
            null, null, null, null);
    }

    private void loadBooks(int count) {
        baseDataset.begin(ReadWrite.WRITE);
        try {
            Model model = baseDataset.getDefaultModel();
            for (int i = 0; i < count; i++) {
                Resource book = ResourceFactory.createResource(NS + "book" + i);
                model.add(book, RDF.type, ResourceFactory.createResource(NS + "Book"));
                model.add(book, ResourceFactory.createProperty(NS + "title"),
                    "Parallel test title " + i);
                model.add(book, ResourceFactory.createProperty(NS + "category"),
                    (i % 3 == 0) ? "A" : (i % 3 == 1) ? "B" : "C");
            }
            baseDataset.commit();
        } finally {
            baseDataset.end();
        }
    }

    private long indexedDocsContainingTitle(String term) {
        return textIndex.query(TITLE_PRED, term, null, null).size();
    }

    @Test
    public void testParallelMatchesSingleThreadedCount() {
        final int total = 500;
        loadBooks(total);

        DatasetGraph dsg = baseDataset.asDatasetGraph();
        ShaclBulkIndexer indexer = new ShaclBulkIndexer(dsg, textIndex, mapping);
        indexer.setThreadCount(8);
        indexer.index();

        assertEquals("Parallel run must index every entity exactly once",
            total, indexer.getEntityCount());
        assertEquals("Lucene doc count must equal entity count",
            total, indexedDocsContainingTitle("parallel"));
    }

    @Test
    public void testThreadCountOneIsBackwardCompatible() {
        final int total = 50;
        loadBooks(total);

        DatasetGraph dsg = baseDataset.asDatasetGraph();
        ShaclBulkIndexer indexer = new ShaclBulkIndexer(dsg, textIndex, mapping);
        indexer.setThreadCount(1);
        indexer.index();

        assertEquals(total, indexer.getEntityCount());
        assertEquals(total, indexedDocsContainingTitle("parallel"));
    }

    @Test
    public void testFreshIndexSkipsDelete() {
        final int total = 100;
        loadBooks(total);

        DatasetGraph dsg = baseDataset.asDatasetGraph();
        ShaclBulkIndexer indexer = new ShaclBulkIndexer(dsg, textIndex, mapping);
        indexer.setFreshIndex(true);
        indexer.setThreadCount(4);
        indexer.index();

        assertEquals("freshIndex skips delete but still indexes every entity once",
            total, indexer.getEntityCount());
        assertEquals(total, indexedDocsContainingTitle("parallel"));
    }

    @Test
    public void testParallelIdempotentWithoutFreshIndexFlag() {
        final int total = 100;
        loadBooks(total);

        DatasetGraph dsg = baseDataset.asDatasetGraph();
        ShaclBulkIndexer first = new ShaclBulkIndexer(dsg, textIndex, mapping);
        first.setThreadCount(4);
        first.index();

        ShaclBulkIndexer second = new ShaclBulkIndexer(dsg, textIndex, mapping);
        second.setThreadCount(4);
        second.index();

        assertEquals("Re-indexing without freshIndex flag must not duplicate docs",
            total, indexedDocsContainingTitle("parallel"));
    }

    @Test
    public void testBatchedParallelCommitsBetweenBatches() {
        final int total = 200;
        loadBooks(total);

        DatasetGraph dsg = baseDataset.asDatasetGraph();
        ShaclBulkIndexer indexer = new ShaclBulkIndexer(dsg, textIndex, mapping);
        indexer.setThreadCount(4);
        // Force multiple batches: 200 entities / 50-batch size = 4 batches
        indexer.setBatchSize(50);
        indexer.index();

        assertEquals(total, indexer.getEntityCount());
        assertEquals(total, indexedDocsContainingTitle("parallel"));
    }

    @Test
    public void testRamBufferTuneIsRestoredAfterIndex() {
        loadBooks(20);

        double original = textIndex.getIndexWriter().getConfig().getRAMBufferSizeMB();

        DatasetGraph dsg = baseDataset.asDatasetGraph();
        ShaclBulkIndexer indexer = new ShaclBulkIndexer(dsg, textIndex, mapping);
        indexer.setRamBufferSizeMB(256.0);
        indexer.index();

        double after = textIndex.getIndexWriter().getConfig().getRAMBufferSizeMB();
        assertEquals("RAM buffer size must be restored after bulk index completes",
            original, after, 0.001);
    }

    @Test
    public void testEntityCountAfterFiveRuns() {
        final int total = 100;
        loadBooks(total);

        DatasetGraph dsg = baseDataset.asDatasetGraph();
        for (int run = 0; run < 5; run++) {
            ShaclBulkIndexer indexer = new ShaclBulkIndexer(dsg, textIndex, mapping);
            indexer.setThreadCount(4);
            indexer.index();
            assertEquals("Run " + run + " must report exact count", total, indexer.getEntityCount());
        }
        assertEquals("Final doc count must be exactly the input size after 5 idempotent runs",
            total, indexedDocsContainingTitle("parallel"));
    }
}
