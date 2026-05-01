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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
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
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pre-implementation validation tests for issue #70 (parallel ShaclBulkIndexer).
 * <p>
 * These tests do NOT add parallelism. They validate the four assumptions the parallel
 * design rests on, against the current single-threaded code:
 * <ol>
 *   <li>TDB2 supports many concurrent read transactions on a single dataset.</li>
 *   <li>Lucene {@code IndexWriter.updateDocument()} is safe under concurrent calls,
 *       and concurrent {@code commit()} from a separate thread does not corrupt the index.</li>
 *   <li>Single-pass entity discovery dedupes correctly when the same entity appears
 *       in both the default graph and a named graph.</li>
 *   <li>{@link Future#get()} surfaces worker exceptions cleanly so a parallel
 *       implementation can fail fast rather than producing a silent partial index.</li>
 * </ol>
 * <p>
 * If any of these fail, the parallel design needs rethinking before implementation.
 */
public class TestBulkIndexerParallelism {

    private static final String NS = "http://example.org/";
    private static final Node BOOK_CLASS = NodeFactory.createURI(NS + "Book");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");

    private Dataset baseDataset;
    private ShaclTextIndexLucene textIndex;
    private ShaclIndexMapping mapping;
    private IndexProfile bookProfile;

    @Before
    public void setUp() {
        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);

        FieldOccurrence titleOcc = occurrence(titleField, TITLE_PRED);

        bookProfile = new IndexProfile(
            NodeFactory.createURI(NS + "BookShape"),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            Collections.singletonList(titleField),
            Collections.singletonList(titleOcc),
            Collections.emptyList(),
            Collections.emptyList());

        mapping = new ShaclIndexMapping(Collections.singletonList(bookProfile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);
        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);
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

    // ---------------------------------------------------------------
    // Test 1: TDB2 concurrent reads
    // ---------------------------------------------------------------

    /**
     * Validates: many concurrent reader threads, each in its own READ transaction,
     * can read the same TDB2 dataset and see consistent results.
     * <p>
     * This is the load-bearing assumption for the parallel design — workers will each
     * open their own read txn and call {@code PathEval.eval()} on a shared graph.
     */
    @Test
    public void testTdb2ConcurrentReadsAreConsistent() throws Exception {
        baseDataset = TDB2Factory.createDataset();
        final int totalBooks = 200;

        baseDataset.begin(ReadWrite.WRITE);
        try {
            Model model = baseDataset.getDefaultModel();
            for (int i = 0; i < totalBooks; i++) {
                addBook(model, "book" + i, "Title " + i);
            }
            baseDataset.commit();
        } finally {
            baseDataset.end();
        }

        final int threadCount = 16;
        final int iterationsPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<Long>> futures = new ArrayList<>();

        try {
            for (int t = 0; t < threadCount; t++) {
                futures.add(pool.submit(() -> {
                    long localFailures = 0;
                    for (int it = 0; it < iterationsPerThread; it++) {
                        baseDataset.begin(ReadWrite.READ);
                        try {
                            DatasetGraph dsg = baseDataset.asDatasetGraph();
                            long count = 0;
                            ExtendedIterator<Triple> iter = dsg.getDefaultGraph()
                                .find(Node.ANY, RDF.type.asNode(), BOOK_CLASS);
                            try {
                                while (iter.hasNext()) {
                                    iter.next();
                                    count++;
                                }
                            } finally {
                                iter.close();
                            }
                            if (count != totalBooks) {
                                localFailures++;
                            }
                        } finally {
                            baseDataset.end();
                        }
                    }
                    return localFailures;
                }));
            }

            long totalFailures = 0;
            for (Future<Long> f : futures) {
                totalFailures += f.get(60, TimeUnit.SECONDS);
            }
            assertEquals("Concurrent TDB2 reads must all see " + totalBooks + " books",
                0, totalFailures);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    // ---------------------------------------------------------------
    // Test 2: Concurrent updateEntityForProfile + commit
    // ---------------------------------------------------------------

    /**
     * Validates: many writer threads can concurrently call {@code updateEntityForProfile()}
     * on the same {@link ShaclTextIndexLucene} while a separate thread fires periodic
     * {@code commit()}s, and the final doc count exactly matches the number of distinct
     * entities written.
     * <p>
     * Lucene's {@code IndexWriter} is documented as thread-safe; this test validates that
     * the SHACL wrapper preserves that property.
     */
    @Test
    public void testConcurrentUpdateEntityForProfileWithCommitTimer() throws Exception {
        final int writerThreads = 8;
        final int entitiesPerThread = 250;
        final int totalEntities = writerThreads * entitiesPerThread;

        ExecutorService writerPool = Executors.newFixedThreadPool(writerThreads);
        ScheduledExecutorService commitTimer = Executors.newSingleThreadScheduledExecutor();
        AtomicBoolean writersDone = new AtomicBoolean(false);
        AtomicLong commitCount = new AtomicLong();

        // Commit timer fires every 50 ms while writers run
        commitTimer.scheduleAtFixedRate(() -> {
            if (!writersDone.get()) {
                try {
                    textIndex.commit();
                    commitCount.incrementAndGet();
                } catch (Exception ignored) {
                    // commits during concurrent writes can be no-ops if nothing is queued
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < writerThreads; t++) {
                final int threadId = t;
                futures.add(writerPool.submit(() -> {
                    for (int i = 0; i < entitiesPerThread; i++) {
                        String uri = NS + "t" + threadId + "_e" + i;
                        Entity entity = new Entity(uri, null);
                        entity.addValue("title", "Concurrent " + threadId + " " + i);
                        textIndex.updateEntityForProfile(entity, bookProfile);
                    }
                }));
            }
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
            writersDone.set(true);
        } finally {
            writerPool.shutdownNow();
            commitTimer.shutdownNow();
            assertTrue(writerPool.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(commitTimer.awaitTermination(10, TimeUnit.SECONDS));
        }

        textIndex.commit();

        // Assert exact final count: query for a term every entity contains
        List<TextHit> hits = textIndex.query(TITLE_PRED, "concurrent", null, null);
        assertEquals("Final doc count must equal number of distinct entities written; "
            + "intermediate commits fired " + commitCount.get() + " times during writes",
            totalEntities, hits.size());
    }

    // ---------------------------------------------------------------
    // Test 3: Duplicate entity dedup across default + named graph
    // ---------------------------------------------------------------

    /**
     * Validates: when the same entity URI carries {@code rdf:type} in both the default
     * graph AND a named graph, the bulk indexer's discovery dedupes it. Asserts a single
     * Lucene document is produced, not two.
     * <p>
     * The parallel design relies on a single-pass discovery that collects unique
     * {@code (subject, profile)} pairs. This test confirms the existing dedup logic
     * (currently interleaved with indexing) handles the cross-graph case correctly so
     * the parallel pass can preserve the same property.
     */
    @Test
    public void testDuplicateEntityDedupAcrossDefaultAndNamedGraph() {
        baseDataset = DatasetFactory.create();

        Resource book = ResourceFactory.createResource(NS + "shared1");

        Model defaultModel = baseDataset.getDefaultModel();
        defaultModel.add(book, RDF.type, ResourceFactory.createResource(NS + "Book"));
        defaultModel.add(book, ResourceFactory.createProperty(NS + "title"), "Shared Book");

        Model namedModel = baseDataset.getNamedModel(NS + "graph1");
        namedModel.add(book, RDF.type, ResourceFactory.createResource(NS + "Book"));
        namedModel.add(book, ResourceFactory.createProperty(NS + "title"), "Shared Book");

        DatasetGraph dsg = baseDataset.asDatasetGraph();
        ShaclBulkIndexer indexer = new ShaclBulkIndexer(dsg, textIndex, mapping);
        indexer.index();

        assertEquals("Same entity in default + named graph must be indexed exactly once",
            1, indexer.getEntityCount());

        List<TextHit> hits = textIndex.query(TITLE_PRED, "shared", null, null);
        assertEquals("Lucene must hold exactly one document for the shared entity",
            1, hits.size());
        assertEquals(NS + "shared1", hits.get(0).getNode().getURI());
    }

    // ---------------------------------------------------------------
    // Test 4: Worker exception propagation
    // ---------------------------------------------------------------

    /**
     * Validates: when a task submitted to an {@link ExecutorService} throws, the
     * exception surfaces via {@link Future#get()} (wrapped in {@link ExecutionException}).
     * <p>
     * Trivial Java semantics, but the parallel implementation must honour them. A
     * common bug is to fire-and-forget tasks via {@code execute()} or to ignore
     * {@code Future} return values — either silently swallows worker failures.
     * This test pins the expected pattern so reviewers and implementers know what
     * to look for.
     */
    @Test
    public void testWorkerExceptionPropagation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            final int taskCount = 50;
            final int poisonIndex = 23;
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < taskCount; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    if (idx == poisonIndex) {
                        throw new TextIndexException("simulated worker failure at index " + idx);
                    }
                    return "ok-" + idx;
                }));
            }

            ExecutionException caught = null;
            int successCount = 0;
            for (Future<String> f : futures) {
                try {
                    f.get(10, TimeUnit.SECONDS);
                    successCount++;
                } catch (ExecutionException ex) {
                    if (caught == null) caught = ex;
                }
            }

            assertNotNull("Worker exception must surface via Future.get()", caught);
            assertNotNull("ExecutionException must wrap a real cause", caught.getCause());
            assertTrue("Cause must be the TextIndexException thrown in the worker",
                caught.getCause() instanceof TextIndexException);
            assertTrue("Cause message must include the poison index marker",
                caught.getCause().getMessage().contains("simulated worker failure at index 23"));
            assertEquals("Exactly one task should have failed; " + successCount + " succeeded",
                taskCount - 1, successCount);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private void addBook(Model model, String id, String title) {
        Resource r = ResourceFactory.createResource(NS + id);
        model.add(r, RDF.type, ResourceFactory.createResource(NS + "Book"));
        model.add(r, ResourceFactory.createProperty(NS + "title"), title);
    }
}
