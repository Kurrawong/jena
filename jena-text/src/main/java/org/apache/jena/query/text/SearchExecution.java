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

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.jena.graph.Node;
import org.apache.jena.query.QueryExecException;

import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.sparql.util.Symbol;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared execution state between the {@code luc:} property functions of one SPARQL query.
 * <p>
 * When {@code luc:query} and {@code luc:facet} appear in the same query with matching
 * parameters (same index, search fields, query string, CQL filter and sort), they share
 * one SearchExecution stored in the ExecutionContext, so the Lucene query runs once.
 * {@code luc:match} and {@code luc:nestedMatch} find it through the {@code ?hit} they are
 * given: each search labels its hits with its own prefix.
 *
 * <h2>Held searcher</h2>
 * A Lucene doc ID is valid only against the reader it came from. The search therefore
 * keeps the {@link IndexSearcher} it ran on, holding one {@code SearcherManager}
 * reference, while any {@code luc:query} iterator over it is open ({@link #retain()} /
 * {@link #release()}). Reads that depend on the hits — entity IRIs, match details,
 * child records — run against it later, and only when the query asks for them.
 * <p>
 * A read after the last iterator closed acquires the current searcher and compares its
 * reader with the one the search used. Without a commit in between they are the same
 * reader and the read proceeds; otherwise it raises {@link QueryExecException} rather
 * than attach values from a different index state.
 * <p>
 * All public methods are synchronized: an ARQ iterator can be closed from a different
 * thread from the one evaluating the query.
 */
public class SearchExecution {
    private static final Logger log = LoggerFactory.getLogger(SearchExecution.class);

    /** Context key of the per-query {@link Registry}. */
    private static final Symbol REGISTRY = Symbol.create(TextQuery.NS + "searchRegistry");

    private final String indexIdentity;
    private final List<String> searchFields;
    private final String queryString;
    private final CqlExpression filter;
    private final List<SortSpec> sortSpecs;
    private final ShaclTextIndexLucene textIndex;
    private final String graphURI;
    private final String lang;
    /** Hit label prefix, {@code s<id>h}; distinct per search within one query. */
    private final String hitPrefix;

    // Lazy results
    private List<TextHit> hits;
    private boolean hitsComputed = false;
    private List<SearchHit> searchHits;
    private int searchHitsFetchedWindow = 0;
    private ShaclTextIndexLucene.HitSearch search;
    private List<String> resolvedFields;
    /** Ranks returned to luc:query since the last search; dependent reads batch over these. */
    private final BitSet returned = new BitSet();
    private final Map<FacetRequestKey, Map<String, List<FacetValue>>> facetCountsCache = new HashMap<>();
    private long totalHits = -1;

    // Held searcher
    private int openIterators = 0;
    private IndexSearcher heldSearcher;
    /** The reader the cached hits came from; compared by identity for late reads. */
    private IndexReader searchReader;

    public SearchExecution(String indexIdentity, List<String> searchFields, String queryString,
                           CqlExpression filter, List<SortSpec> sortSpecs,
                           ShaclTextIndexLucene textIndex, String graphURI, String lang) {
        this(0, indexIdentity, searchFields, queryString, filter, sortSpecs, textIndex, graphURI, lang);
    }

    private SearchExecution(int id, String indexIdentity, List<String> searchFields, String queryString,
                            CqlExpression filter, List<SortSpec> sortSpecs,
                            ShaclTextIndexLucene textIndex, String graphURI, String lang) {
        this.indexIdentity = indexIdentity;
        this.searchFields = searchFields != null ? new ArrayList<>(searchFields) : new ArrayList<>();
        this.queryString = queryString;
        this.filter = filter;
        this.sortSpecs = sortSpecs != null ? List.copyOf(sortSpecs) : List.of();
        this.textIndex = textIndex;
        this.graphURI = graphURI;
        this.lang = lang;
        this.hitPrefix = "s" + id + "h";
    }

    /** The searches of one query, by id. Stored in the query's context. */
    private static final class Registry {
        private final Map<Integer, SearchExecution> byId = new LinkedHashMap<>();
        private int nextId = 1;
    }

    private static Registry registry(Context ctx, boolean create) {
        synchronized (ctx) {
            Object existing = ctx.get(REGISTRY);
            if (existing instanceof Registry registry) {
                return registry;
            }
            if (!create) {
                return null;
            }
            Registry registry = new Registry();
            ctx.put(REGISTRY, registry);
            return registry;
        }
    }

    /**
     * Get or create a SearchExecution in the given ExecutionContext.
     * If one already exists with the same key, it is reused.
     */
    public static SearchExecution getOrCreate(ExecutionContext execCxt,
                                              String indexIdentity,
                                              List<String> searchFields,
                                              String queryString, CqlExpression filter,
                                              List<SortSpec> sortSpecs,
                                              ShaclTextIndexLucene textIndex,
                                              String graphURI, String lang) {
        String key = buildKey(indexIdentity, searchFields, queryString, filter, sortSpecs);
        Symbol symbol = Symbol.create(TextQuery.NS + "searchExecution/" + key);
        Context ctx = execCxt.getContext();

        synchronized (ctx) {
            Object existing = ctx.get(symbol);
            if (existing instanceof SearchExecution se) {
                log.trace("Reusing SearchExecution for key: {}", key);
                return se;
            }

            Registry registry = registry(ctx, true);
            int id = registry.nextId++;
            SearchExecution se = new SearchExecution(id, indexIdentity, searchFields, queryString, filter,
                sortSpecs, textIndex, graphURI, lang);
            registry.byId.put(id, se);
            ctx.put(symbol, se);
            log.trace("Created SearchExecution {} for key: {}", id, key);
            return se;
        }
    }

    /**
     * The search that produced {@code hitId} in this query, or null if no search in the
     * query labels its hits that way.
     */
    public static SearchExecution forHit(ExecutionContext execCxt, Node hitId) {
        if (hitId == null || !hitId.isBlank()) {
            return null;
        }
        String label = hitId.getBlankNodeLabel();
        int h = label.indexOf('h');
        if (!label.startsWith("s") || h < 2) {
            return null;
        }
        int id;
        try {
            id = Integer.parseInt(label.substring(1, h));
        } catch (NumberFormatException ex) {
            return null;
        }
        Context ctx = execCxt.getContext();
        Registry registry = registry(ctx, false);
        if (registry == null) {
            return null;
        }
        synchronized (ctx) {
            return registry.byId.get(id);
        }
    }

    /** Every search created in this query so far, in creation order. */
    public static List<SearchExecution> all(ExecutionContext execCxt) {
        Context ctx = execCxt.getContext();
        Registry registry = registry(ctx, false);
        if (registry == null) {
            return List.of();
        }
        synchronized (ctx) {
            return new ArrayList<>(registry.byId.values());
        }
    }

    /**
     * Build a cache key from search fields, query string, CQL filter, and sort specs.
     */
    static String buildKey(String indexIdentity, List<String> searchFields, String queryString,
                           CqlExpression filter, List<SortSpec> sortSpecs) {
        StringBuilder sb = new StringBuilder();

        sb.append("index=").append(indexIdentity != null ? indexIdentity : TextIndexRegistry.DEFAULT_ID);

        if (searchFields != null && !searchFields.isEmpty()) {
            List<String> sorted = searchFields.stream()
                .sorted()
                .collect(Collectors.toList());
            sb.append("|fields=").append(String.join(",", sorted));
        }

        sb.append("|qs=").append(queryString != null ? queryString : "");

        if (filter != null) {
            sb.append("|cql=").append(filter.toCanonical());
        }

        if (sortSpecs != null && !sortSpecs.isEmpty()) {
            sb.append("|sort=");
            sb.append(sortSpecs.stream().map(SortSpec::toCanonical).collect(Collectors.joining(",")));
        }

        return sb.toString();
    }

    /**
     * Get search hits, computing them lazily on first access.
     */
    public synchronized List<TextHit> getHits(int limit, String highlight) {
        if (!hitsComputed) {
            try {
                if (filter == null && (sortSpecs == null || sortSpecs.isEmpty())) {
                    List<String> resolved = textIndex.resolveSearchFields(searchFields);
                    hits = textIndex.queryByFields(resolved, queryString, graphURI, lang, limit, highlight);
                } else {
                    hits = textIndex.queryWithCql(searchFields, queryString, filter, sortSpecs,
                        graphURI, lang, limit, highlight);
                }
            } catch (Exception e) {
                log.error("Error computing hits: {}", e.getMessage());
                hits = Collections.emptyList();
            }
            hitsComputed = true;
        }
        return hits;
    }

    // ---- Held searcher ------------------------------------------------------------

    /** A {@code luc:query} iterator over this search opened. */
    public synchronized void retain() {
        openIterators++;
    }

    /**
     * A {@code luc:query} iterator over this search closed. The last one releases the
     * held searcher; the cached hits stay, and later reads check the reader.
     */
    public synchronized void release() {
        if (openIterators == 0) {
            log.warn("SearchExecution released more often than retained");
            return;
        }
        openIterators--;
        if (openIterators == 0 && heldSearcher != null) {
            IndexSearcher searcher = heldSearcher;
            heldSearcher = null;
            textIndex.releaseSearcher(searcher);
        }
    }

    @FunctionalInterface
    private interface SearcherRead {
        void read(IndexSearcher searcher) throws IOException;
    }

    /**
     * Run {@code read} against the searcher the cached hits came from: the held one, or a
     * newly acquired one if its reader is the same. Raises if the index has been
     * refreshed since the search.
     */
    private void readAgainstSearch(SearcherRead read) {
        try {
            if (heldSearcher != null) {
                read.read(heldSearcher);
                return;
            }
            IndexSearcher searcher = textIndex.acquireSearcher();
            try {
                if (searcher.getIndexReader() != searchReader) {
                    throw new QueryExecException("The text index was refreshed after luc:query searched it; "
                        + "the hits no longer identify the same documents. Re-run the query.");
                }
                read.read(searcher);
            } finally {
                textIndex.releaseSearcher(searcher);
            }
        } catch (IOException ex) {
            throw new TextIndexException("Reading search hits", ex);
        }
    }

    // ---- Hits -----------------------------------------------------------------------

    /**
     * Get search hits with stable hit IDs. Caches the largest window seen so far and
     * returns the requested page slice. The hits carry rank, score and doc ID; their
     * entity IRIs, match details and child records are read on request.
     * <p>
     * A larger window re-runs the search, against the held searcher if there is one, and
     * discards everything read for the previous hits.
     *
     * @param offset number of leading hits to skip
     * @param limit  maximum number of hits to return
     * @return the page slice of search hits
     */
    public synchronized List<SearchHit> getSearchHits(int offset, int limit) {
        int requestedWindow = (int) Math.min(Integer.MAX_VALUE, (long) offset + limit);
        if (searchHits == null || requestedWindow > searchHitsFetchedWindow) {
            runSearch(requestedWindow);
        }
        if (offset >= searchHits.size()) {
            return Collections.emptyList();
        }
        int end = (int) Math.min((long) offset + limit, searchHits.size());
        returned.set(offset, end);
        return searchHits.subList(offset, end);
    }

    private void runSearch(int window) {
        IndexSearcher searcher = heldSearcher;
        boolean acquired = searcher == null;
        if (acquired) {
            searcher = textIndex.acquireSearcher();
        }
        try {
            List<String> resolved = textIndex.resolveSearchFields(searchFields);
            search = textIndex.searchHits(searcher, resolved, queryString, filter, sortSpecs, window, hitPrefix);
            resolvedFields = resolved;
            searchHits = search.hits();
            searchHitsFetchedWindow = window;
        } catch (Exception e) {
            log.error("Error computing search hits: {}", e.getMessage());
            search = null;
            searchHits = Collections.emptyList();
            searchHitsFetchedWindow = 0;
        } finally {
            returned.clear();
            searchReader = searcher.getIndexReader();
            if (acquired) {
                if (openIterators > 0) {
                    heldSearcher = searcher;
                } else {
                    textIndex.releaseSearcher(searcher);
                }
            }
        }
    }

    /**
     * The cached hit with this ID, or null. The label carries the rank, so this is an
     * index into the cached window.
     */
    public synchronized SearchHit findHit(Node hitId) {
        if (searchHits == null || hitId == null || !hitId.isBlank()) {
            return null;
        }
        String label = hitId.getBlankNodeLabel();
        if (!label.startsWith(hitPrefix)) {
            return null;
        }
        int rank;
        try {
            rank = Integer.parseInt(label.substring(hitPrefix.length()));
        } catch (NumberFormatException ex) {
            return null;
        }
        if (rank < 0 || rank >= searchHits.size()) {
            return null;
        }
        SearchHit hit = searchHits.get(rank);
        return hit.getHitId().equals(hitId) ? hit : null;
    }

    /** Every cached hit, in rank order. */
    public synchronized List<SearchHit> getCachedHits() {
        return searchHits != null ? Collections.unmodifiableList(searchHits) : Collections.emptyList();
    }

    /** Read the entity IRI of every hit in {@code hits} that does not have it yet. */
    public synchronized void readEntities(Collection<SearchHit> hits) {
        List<SearchHit> missing = new ArrayList<>();
        for (SearchHit hit : hits) {
            if (!hit.isEntityRead()) {
                missing.add(hit);
            }
        }
        if (!missing.isEmpty()) {
            readAgainstSearch(searcher -> textIndex.readEntityNodes(searcher, missing));
        }
    }

    /**
     * The match details of a cached hit. The first request computes them for every hit
     * returned to {@code luc:query} so far that lacks them, in one pass.
     */
    public synchronized List<FieldMatch> fieldMatches(SearchHit hit) {
        if (!hit.hasFieldMatches()) {
            computeFieldMatches(pending(hit, SearchHit::hasFieldMatches));
        }
        return hit.getFieldMatches();
    }

    /** The child records of a cached hit; computed as {@link #fieldMatches} is. */
    public synchronized List<NestedMatch> nestedMatches(SearchHit hit) {
        if (!hit.hasNestedMatches()) {
            computeNestedMatches(pending(hit, SearchHit::hasNestedMatches));
        }
        return hit.getNestedMatches();
    }

    /** Every cached hit, with its match details computed. */
    public synchronized List<SearchHit> hitsWithFieldMatches() {
        List<SearchHit> all = getCachedHits();
        computeFieldMatches(missing(all, SearchHit::hasFieldMatches));
        return all;
    }

    /** Every cached hit, with its child records computed. */
    public synchronized List<SearchHit> hitsWithNestedMatches() {
        List<SearchHit> all = getCachedHits();
        computeNestedMatches(missing(all, SearchHit::hasNestedMatches));
        return all;
    }

    /** {@code hit}, plus every hit returned to luc:query that lacks the value. */
    private List<SearchHit> pending(SearchHit hit, Predicate<SearchHit> done) {
        List<SearchHit> batch = new ArrayList<>();
        batch.add(hit);
        for (int rank = returned.nextSetBit(0); rank >= 0; rank = returned.nextSetBit(rank + 1)) {
            if (rank >= searchHits.size()) {
                break;
            }
            SearchHit other = searchHits.get(rank);
            if (other != hit && !done.test(other)) {
                batch.add(other);
            }
        }
        return batch;
    }

    private static List<SearchHit> missing(List<SearchHit> hits, Predicate<SearchHit> done) {
        List<SearchHit> batch = new ArrayList<>();
        for (SearchHit hit : hits) {
            if (!done.test(hit)) {
                batch.add(hit);
            }
        }
        return batch;
    }

    private void computeFieldMatches(List<SearchHit> batch) {
        if (batch.isEmpty()) {
            return;
        }
        if (search == null) {
            batch.forEach(h -> h.setFieldMatches(List.of()));
            return;
        }
        readAgainstSearch(searcher ->
            textIndex.computeFieldMatches(searcher, search.textQuery(), resolvedFields, batch));
    }

    private void computeNestedMatches(List<SearchHit> batch) {
        if (batch.isEmpty()) {
            return;
        }
        if (search == null) {
            batch.forEach(h -> h.setNestedMatches(List.of()));
            return;
        }
        readAgainstSearch(searcher -> textIndex.computeNestedMatches(searcher, search.finalQuery(), batch));
    }

    // ---- Facets and counts -------------------------------------------------------------

    /**
     * Get facet counts with minCount threshold, computing them lazily on first access.
     */
    public synchronized Map<String, List<FacetValue>> getFacetCounts(
            FacetRequest facetRequest, int maxValues, int minCount) {
        FacetRequestKey requestKey = FacetRequestKey.of(facetRequest, maxValues, minCount);
        if (!facetCountsCache.containsKey(requestKey)) {
            Map<String, List<FacetValue>> facetCounts;
            try {
                if (filter == null) {
                    facetCounts = textIndex.getFacetCounts(queryString, searchFields, facetRequest, maxValues, minCount);
                } else {
                    facetCounts = textIndex.getFacetCountsWithCql(
                        queryString, searchFields, facetRequest, filter, maxValues, minCount);
                }
            } catch (Exception e) {
                log.error("Error computing facet counts: {}", e.getMessage());
                facetCounts = Collections.emptyMap();
            }
            facetCountsCache.put(requestKey, facetCounts);
        }
        return facetCountsCache.get(requestKey);
    }

    /**
     * Get total hit count, computing lazily on first access.
     */
    public synchronized long getTotalHits() {
        if (totalHits < 0) {
            try {
                totalHits = textIndex.countQueryWithCql(queryString, searchFields, filter);
            } catch (Exception e) {
                log.error("Error computing total hits: {}", e.getMessage());
                totalHits = 0;
            }
        }
        return totalHits;
    }

    public CqlExpression getFilter() {
        return filter;
    }

    public String getIndexIdentity() {
        return indexIdentity;
    }

    public String getQueryString() {
        return queryString;
    }

    public List<String> getSearchFields() {
        return searchFields;
    }

    public List<SortSpec> getSortSpecs() {
        return sortSpecs;
    }
}
