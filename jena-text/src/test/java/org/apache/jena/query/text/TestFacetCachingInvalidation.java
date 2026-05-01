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
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the facet caching layer added in #71. Focuses on cache correctness
 * across the commit boundary — the implementation tests of cache hits live in
 * the existing facet test classes; these tests prove that:
 * <ol>
 *   <li>Open-facet cache returns identical results within a commit window.</li>
 *   <li>Open-facet cache invalidates when {@link ShaclTextIndexLucene#commit()} fires
 *       (a write-then-read sequence sees the new doc).</li>
 *   <li>SearcherManager rotates the live reader on commit, so a query after a write
 *       reflects the write.</li>
 *   <li>Concurrent acquire+commit doesn't drop documents or crash.</li>
 * </ol>
 */
public class TestFacetCachingInvalidation {

    private static final String NS = "http://example.org/";
    private static final Node BOOK_CLASS = NodeFactory.createURI(NS + "Book");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node CATEGORY_PRED = NodeFactory.createURI(NS + "category");

    private ShaclTextIndexLucene textIndex;
    private IndexProfile bookProfile;

    @Before
    public void setUp() {
        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);
        FieldDef categoryField = new FieldDef("category", FieldType.KEYWORD, null,
            true, true, true, false, true, false);

        List<FieldOccurrence> occurrences = Arrays.asList(
            occurrence(titleField, TITLE_PRED),
            occurrence(categoryField, CATEGORY_PRED));

        bookProfile = new IndexProfile(
            NodeFactory.createURI(NS + "BookShape"),
            Collections.singleton(BOOK_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, categoryField),
            occurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(bookProfile));
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
    }

    private static FieldOccurrence occurrence(FieldDef field, Node predicate) {
        Path path = PathFactory.pathLink(predicate);
        return new FieldOccurrence(
            field, path,
            ShaclIndexAssembler.extractPathVariants(path),
            Collections.singleton(predicate),
            null, null, null, null);
    }

    private void addBook(String id, String title, String category) {
        Entity entity = new Entity(NS + id, null);
        entity.addValue("title", title);
        entity.addValue("category", category);
        textIndex.updateEntityForProfile(entity, bookProfile);
    }

    // ---------------------------------------------------------------
    // Test 1: open-facet cache returns identical results within a commit
    // ---------------------------------------------------------------

    @Test
    public void testOpenFacetCacheReturnsSameResultBetweenCommits() {
        addBook("b1", "First book", "Fiction");
        addBook("b2", "Second book", "Fiction");
        addBook("b3", "Third book", "Science");
        textIndex.commit();

        Map<String, List<FacetValue>> first = textIndex.getFacetCounts(
            null, null, FacetRequest.flatOnly(Collections.singletonList("category")), 10, 0, null);
        Map<String, List<FacetValue>> second = textIndex.getFacetCounts(
            null, null, FacetRequest.flatOnly(Collections.singletonList("category")), 10, 0, null);

        assertEquals("Open-facet cache must return equal results for the same request",
            first, second);
        assertEquals("Fiction count", Long.valueOf(2),
            facetCount(first.get("category"), "Fiction"));
        assertEquals("Science count", Long.valueOf(1),
            facetCount(first.get("category"), "Science"));
    }

    // ---------------------------------------------------------------
    // Test 2: open-facet cache invalidates on commit
    // ---------------------------------------------------------------

    @Test
    public void testOpenFacetCacheInvalidatesOnCommit() {
        addBook("b1", "First book", "Fiction");
        textIndex.commit();

        Map<String, List<FacetValue>> before = textIndex.getFacetCounts(
            null, null, FacetRequest.flatOnly(Collections.singletonList("category")), 10, 0, null);
        assertEquals("Initial Fiction count", Long.valueOf(1),
            facetCount(before.get("category"), "Fiction"));

        addBook("b2", "Second book", "Fiction");
        textIndex.commit();

        Map<String, List<FacetValue>> after = textIndex.getFacetCounts(
            null, null, FacetRequest.flatOnly(Collections.singletonList("category")), 10, 0, null);
        assertEquals("After commit, Fiction count must reflect the new doc — cache must invalidate",
            Long.valueOf(2), facetCount(after.get("category"), "Fiction"));
    }

    // ---------------------------------------------------------------
    // Test 3: queries see new docs after commit (SearcherManager refreshes)
    // ---------------------------------------------------------------

    @Test
    public void testSearcherRefreshAfterCommit() {
        addBook("b1", "Alpha title", "Fiction");
        textIndex.commit();

        List<TextHit> first = textIndex.query(TITLE_PRED, "alpha", null, null);
        assertEquals(1, first.size());

        addBook("b2", "Alpha second", "Fiction");
        textIndex.commit();

        // Without maybeRefresh in commit(), the searcher would still see only b1.
        List<TextHit> second = textIndex.query(TITLE_PRED, "alpha", null, null);
        assertEquals("After commit, query must see both documents", 2, second.size());
    }

    // ---------------------------------------------------------------
    // Test 4: filtered facet requests bypass the cache
    // ---------------------------------------------------------------

    @Test
    public void testFilteredFacetRequestsBypassCache() {
        addBook("b1", "Alpha", "Fiction");
        addBook("b2", "Bravo", "Science");
        textIndex.commit();

        // Open request: cached
        Map<String, List<FacetValue>> open = textIndex.getFacetCounts(
            null, null, FacetRequest.flatOnly(Collections.singletonList("category")), 10, 0, null);
        assertEquals(Long.valueOf(1), facetCount(open.get("category"), "Fiction"));
        assertEquals(Long.valueOf(1), facetCount(open.get("category"), "Science"));

        // Filtered (with query) request: must NOT be served from open-facet cache.
        // It should reflect only matching docs, not the full open-facet set.
        Map<String, List<FacetValue>> filtered = textIndex.getFacetCounts(
            "alpha", null, FacetRequest.flatOnly(Collections.singletonList("category")), 10, 0, null);
        assertEquals("Filtered request should see only Fiction", Long.valueOf(1),
            facetCount(filtered.get("category"), "Fiction"));
        assertNull("Filtered request must NOT see Science",
            facetCount(filtered.get("category"), "Science"));
    }

    // ---------------------------------------------------------------
    // Test 5: concurrent acquire + commit stress
    // ---------------------------------------------------------------

    /**
     * Spins reader threads against the index while a writer interleaves writes
     * and commits. Asserts no exceptions are thrown and the final query result
     * reflects all writes. The acquire/release pattern under concurrent refresh
     * is the most fragile part of {@link SearcherManager} usage — a missed
     * release leaks readers; an acquire-after-close throws AlreadyClosedException.
     */
    @Test
    public void testConcurrentAcquireAndRefreshDoesNotCrash() throws Exception {
        final int writerOps = 100;
        final int readerThreads = 6;
        final int readsPerThread = 200;

        ExecutorService readers = Executors.newFixedThreadPool(readerThreads);
        AtomicInteger queryFailures = new AtomicInteger();

        try {
            // Writer: interleaved writes and commits
            Future<?> writerFuture = readers.submit(() -> {
                try {
                    for (int i = 0; i < writerOps; i++) {
                        addBook("c" + i, "Concurrent title " + i, (i % 2 == 0) ? "Even" : "Odd");
                        if (i % 5 == 0) {
                            textIndex.commit();
                        }
                    }
                    textIndex.commit();
                } catch (Exception e) {
                    queryFailures.incrementAndGet();
                }
            });

            // Readers: hammer text queries while writes happen
            List<Future<?>> readerFutures = new ArrayList<>();
            for (int t = 0; t < readerThreads - 1; t++) {
                readerFutures.add(readers.submit(() -> {
                    try {
                        for (int i = 0; i < readsPerThread; i++) {
                            textIndex.query(TITLE_PRED, "concurrent", null, null);
                        }
                    } catch (Exception e) {
                        queryFailures.incrementAndGet();
                    }
                }));
            }

            writerFuture.get(60, TimeUnit.SECONDS);
            for (Future<?> f : readerFutures) f.get(60, TimeUnit.SECONDS);
        } finally {
            readers.shutdownNow();
            assertTrue(readers.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals("Concurrent acquire+commit must produce no exceptions",
            0, queryFailures.get());

        // Final state: all writerOps documents must be present.
        textIndex.commit();
        List<TextHit> finalHits = textIndex.query(TITLE_PRED, "concurrent", null, null);
        assertEquals("All writes must land", writerOps, finalHits.size());
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static Long facetCount(List<FacetValue> values, String label) {
        if (values == null) return null;
        for (FacetValue fv : values) {
            if (fv.getValue().equals(label)) return fv.getCount();
        }
        return null;
    }
}
