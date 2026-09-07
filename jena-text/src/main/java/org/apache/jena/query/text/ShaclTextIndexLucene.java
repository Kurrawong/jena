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

import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.implementation.datatype.WKTDatatype;
import org.apache.jena.geosparql.implementation.parsers.wkt.WKTReader;
import org.apache.jena.geosparql.implementation.vocabulary.SRS_URI;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.text.assembler.IndexVocab;
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.query.text.cql.CqlToLuceneCompiler;
import org.apache.jena.query.text.embedding.EmbeddingProvider;
import org.apache.lucene.document.*;
import org.apache.lucene.facet.FacetField;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.MultiDoubleValuesSource;
import org.apache.lucene.facet.MultiFacets;
import org.apache.lucene.facet.MultiLongValuesSource;
import org.apache.lucene.facet.range.DoubleRange;
import org.apache.lucene.facet.range.DoubleRangeFacetCounts;
import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.facet.range.LongRangeFacetCounts;
import org.apache.lucene.facet.sortedset.DefaultSortedSetDocValuesReaderState;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetCounts;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesReaderState;
import org.apache.lucene.facet.taxonomy.FastTaxonomyFacetCounts;
import org.apache.lucene.facet.taxonomy.TaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.ParentChildrenBlockJoinQuery;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.apache.lucene.search.join.ToParentBlockJoinQuery;
import org.apache.lucene.search.join.ToParentBlockJoinSortField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.NamedMatches;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.locationtech.jts.geom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SHACL entity-per-document Lucene index.
 * <p>
 * Extends {@link TextIndexLucene} with faceting, CQL filtering, sort pushdown,
 * and SHACL-driven document building. This class holds all SHACL-specific
 * state and methods so that the parent remains a clean classic triple-per-document
 * implementation.
 */
public class ShaclTextIndexLucene extends TextIndexLucene {
    private static final Logger log = LoggerFactory.getLogger(ShaclTextIndexLucene.class);

    /** Index field name for taxonomy facet ordinals (kept separate from SSDV's $facets). */
    private static final String TAXO_INDEX_FIELD = "$taxo_facets";
    private static final String MULTI_VALUED_CONFIG_IRI = IndexVocab.NS + "multiValued";

    /**
     * Discriminator field marking parent vs child docs in a block-join layout.
     * Used by the parents-filter {@link BitSetProducer} at query time and by
     * read-side filters that need to exclude child docs from result iteration.
     */
    static final String BLOCK_KIND_FIELD = "_blockKind";
    static final String BLOCK_KIND_PARENT = "parent";
    static final String BLOCK_KIND_CHILD = "child";

    /**
     * Field on every child doc identifying which {@code idx:nested} scope (by name)
     * it belongs to. Single-valued. Empty on parent docs.
     */
    static final String NESTED_SCOPE_FIELD = "_nestedScope";

    private final ShaclIndexMapping shaclMapping;
    /** Embeds document and query text; null unless a vector field is configured. */
    private final EmbeddingProvider embeddingProvider;
    /** Neighbours retrieved by a KNN search — see {@link TextIndexConfig#getKnnTopK()}. */
    private final int knnTopK;
    private final List<String> facetFields;
    private final FacetsConfig facetsConfig;
    private final int maxFacetHits;

    /** Fingerprint of the configuration this instance was built with. */
    private final String configFingerprint;

    /** The stamp found on disk when this index was opened, or null if there was none. */
    private final ShaclIndexStamp.StampData openedStamp;

    /**
     * Whether this open created the stamp because the index was empty. Such an index may
     * still adopt the identity of the dataset it is attached to; an existing one may not.
     */
    private boolean stampedAsNew = false;

    // Taxonomy directory for hierarchical facets (null if no hierarchies configured)
    private final Directory taxoDirectory;
    private final DirectoryTaxonomyWriter taxoWriter;
    private final Set<String> hierarchyDimensions;

    /**
     * Filter that matches parent docs only (children docs share the entity URI in the
     * docIdField, so reads must restrict to parent docs to preserve the entity-per-hit
     * iteration contract). Used by {@link #filterToParents(Query)} on every read site.
     */
    private static final Query PARENT_DOC_FILTER =
        new TermQuery(new Term(BLOCK_KIND_FIELD, BLOCK_KIND_PARENT));

    /** {@link BitSetProducer} over the parent filter — required by {@code ToParentBlockJoinQuery}. */
    public static final BitSetProducer PARENTS_FILTER = new QueryBitSetProducer(PARENT_DOC_FILTER);

    /**
     * Wrap a child-scope query in a {@link ToParentBlockJoinQuery} so that matching
     * child docs lift their owning parent into the result set. Used by the CQL compiler
     * and by field-scoped read paths when the target field lives on child docs.
     */
    public static Query wrapAsParent(Query childQuery) {
        return new ToParentBlockJoinQuery(childQuery, PARENTS_FILTER,
            org.apache.lucene.search.join.ScoreMode.Avg);
    }

    /**
     * Wrap an arbitrary query with a parent-doc filter so search/iteration sees only
     * parent docs in the result set. Always safe to call — for indexes that have no
     * child docs (no {@code idx:nested}) the parent filter is a near-no-op cost-wise.
     */
    static Query filterToParents(Query inner) {
        if (inner == null) {
            return PARENT_DOC_FILTER;
        }
        return new BooleanQuery.Builder()
            .add(inner, BooleanClause.Occur.MUST)
            .add(PARENT_DOC_FILTER, BooleanClause.Occur.FILTER)
            .build();
    }

    /**
     * Manages a single live {@link IndexSearcher} per index generation, refreshed via
     * {@link #commit()}. Replaces the previous per-call {@code DirectoryReader.open(...)}
     * pattern that paid file-handle, segment-info, and doc-values setup costs on every
     * read. Initialised eagerly in the constructor (the parent constructor opens the
     * {@link IndexWriter} before we get here) and rebuilt on rollback (the parent
     * recreates the writer, invalidating any reader rooted at the old one).
     * <p>
     * Volatile because rollback may replace it concurrently with reads in flight; an
     * already-acquired {@link IndexSearcher} stays valid against its acquired snapshot.
     */
    private volatile SearcherManager searcherManager;

    /**
     * Per-live-reader cache of {@link DefaultSortedSetDocValuesReaderState}. Building
     * this state walks ordinal mappings for every facet field across every segment —
     * at scale (10M+ docs) it dominates per-call facet latency. The state is
     * deterministic per reader generation, so we cache it keyed by the reader's core
     * cache key and rely on Lucene's
     * {@link IndexReader.ClosedListener} to evict the entry when the reader is retired
     * by {@link SearcherManager#maybeRefresh()}.
     */
    private final java.util.concurrent.ConcurrentMap<IndexReader.CacheKey, SortedSetDocValuesReaderState>
        ssdvStateCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Result-level cache for "open" facet requests — those with no query string and
     * no CQL filter, which produce identical output between commits. At 10M+ docs the
     * full-index aggregation costs several seconds; subsequent identical requests
     * answer in microseconds from this map.
     * <p>
     * The map reference is replaced (not cleared) on commit to avoid races with
     * concurrent readers holding a reference to the old map; the old map and its
     * entries become GC-eligible once those readers complete.
     */
    private volatile java.util.concurrent.ConcurrentMap<OpenFacetCacheKey, Map<String, List<FacetValue>>>
        openFacetCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Key for {@link #openFacetCache}. {@code searchFields} are intentionally omitted —
     *  with no query and no filter they have no effect on the result. */
    private record OpenFacetCacheKey(FacetRequest facetRequest, int maxValues, int minCount) {}

    /**
     * Child-filter {@link BitSetProducer}s for nested sort selectors, keyed by
     * {@code scope + field + value}. See {@link #childSortFilter}.
     */
    private final java.util.concurrent.ConcurrentMap<String, BitSetProducer> childSortFilterCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Upper bound on {@link #childSortFilterCache} entries. */
    private static final int MAX_CHILD_SORT_FILTERS = 256;

    /**
     * Upper bound on child records projected per hit by {@link #computeNestedMatches}.
     * A hit whose filter selects more children than this is logged, not silently trimmed.
     */
    private static final int MAX_NESTED_MATCHES_PER_HIT = 100;

    public ShaclTextIndexLucene(Directory directory, TextIndexConfig config) {
        this(directory, null, config);
    }

    public ShaclTextIndexLucene(Directory directory, Directory taxoDirectory, TextIndexConfig config) {
        super(directory, config);
        this.shaclMapping = config.getShaclMapping();
        this.maxFacetHits = config.getMaxFacetHits();
        this.embeddingProvider = config.getEmbeddingProvider();
        this.knnTopK = config.getKnnTopK();

        this.facetFields = new ArrayList<>(config.getFacetFields());
        this.facetsConfig = new FacetsConfig();
        for (String facetField : this.facetFields) {
            facetsConfig.setMultiValued(facetField, true);
        }
        for (ShaclIndexMapping.IndexProfile profile : this.shaclMapping.getProfiles()) {
            for (ShaclIndexMapping.FieldDef field : profile.getFields()) {
                if (field.isFacetable() && field.isMultiValued()) {
                    facetsConfig.setMultiValued(field.getFieldName(), true);
                }
            }
        }
        if (!this.facetFields.isEmpty()) {
            log.info("Faceting enabled for fields: {}", this.facetFields);
        }

        // Initialize hierarchical facet support
        // Taxonomy dimensions use a separate index field to avoid conflict with SSDV's $facets field
        this.hierarchyDimensions = new LinkedHashSet<>();
        for (ShaclIndexMapping.HierarchyDef h : shaclMapping.getAllHierarchies()) {
            String dim = h.getDimensionName();
            hierarchyDimensions.add(dim);
            facetsConfig.setHierarchical(dim, true);
            facetsConfig.setMultiValued(dim, true);
            facetsConfig.setIndexFieldName(dim, TAXO_INDEX_FIELD);
        }

        if (!hierarchyDimensions.isEmpty()) {
            if (taxoDirectory == null) {
                taxoDirectory = new ByteBuffersDirectory();
            }
            this.taxoDirectory = taxoDirectory;
            try {
                this.taxoWriter = new DirectoryTaxonomyWriter(this.taxoDirectory);
            } catch (IOException e) {
                throw new TextIndexException("Failed to create taxonomy writer", e);
            }
            log.info("Hierarchical faceting enabled for dimensions: {}", hierarchyDimensions);
        } else {
            this.taxoDirectory = null;
            this.taxoWriter = null;
        }

        this.configFingerprint =
            ShaclConfigFingerprint.fingerprint(shaclMapping, config.isValueStored(), this.facetFields);
        this.openedStamp = ShaclIndexStamp.read(directory);
        stampIfNewIndex();
        logConfigStatus();

        // The parent constructor opened (and committed) the IndexWriter before this point,
        // so an NRT SearcherManager rooted on it is safe to construct now.
        initSearcherManager();
    }

    /**
     * Stamp an index that has no content yet, and only such an index.
     * <p>
     * Anything else would destroy the evidence. If a server opened a populated index with
     * a different configuration and re-stamped it, the mismatch it is supposed to report
     * would be overwritten by the very act of reporting it — and an index built before
     * fingerprinting existed would acquire a stamp asserting a match that nothing has
     * checked. A full rebuild re-stamps explicitly through {@link #stampConfig}.
     */
    private void stampIfNewIndex() {
        if ( openedStamp != null )
            return;
        try {
            if ( getIndexWriter().getDocStats().numDocs > 0 )
                return;
        } catch (Exception e) {
            // Not being able to count documents is not a reason to fail opening an index.
            return;
        }
        stampConfig(null);
        stampedAsNew = true;
    }

    /**
     * Tie this index to the dataset it is being attached to, or report that it is tied to
     * a different one.
     * <p>
     * Called by the assembler, which is the first point at which the index and the dataset
     * are both in hand — the constructor runs before they meet.
     * <p>
     * A freshly created index adopts the dataset's identity, minting one if the dataset
     * has none. An index that already carries content does not: its pairing records the
     * dataset its documents actually came from, and rewriting that would erase the only
     * evidence of a crossed mount.
     * <p>
     * An identity is minted for the attached dataset only when the index carries a
     * pairing to compare it against. Otherwise there is no verdict to reach and no reason
     * to write into someone's database directory. The case this covers: an index built by
     * the indexer from dataset A, mounted against a dataset B that was loaded with plain
     * {@code tdbloader} and so has no identity of its own. Without minting here, B stays
     * anonymous and the crossed mount is reported as unknown rather than as the error it
     * is. A read-only mount simply yields null and falls back to unknown.
     */
    public void checkOrCompletePairing(DatasetGraph dsg) {
        if ( stampedAsNew ) {
            String datasetId = DatasetLocations.datasetInstanceId(dsg, true);
            if ( datasetId != null ) {
                stampConfig(datasetId);
                log.info("New index paired with dataset {}", datasetId);
            }
            return;
        }

        boolean indexIsPaired = openedStamp != null && openedStamp.pairedDatasetId() != null;
        String currentDatasetId = DatasetLocations.datasetInstanceId(dsg, indexIsPaired);
        switch ( ShaclIndexStamp.comparePairing(openedStamp, currentDatasetId) ) {
            case MATCH ->
                log.info("Index is paired with the dataset it was built from ({})", currentDatasetId);
            case MISMATCH -> {
                log.warn("Index/dataset PAIRING MISMATCH - this index was not built from this dataset.");
                log.warn("    index was built from dataset: {}", openedStamp.pairedDatasetId());
                log.warn("    dataset attached here:        {}", currentDatasetId);
                log.warn("    The configuration matches, so results will look plausible and be wrong.");
                log.warn("    Check which index and database directories are mounted.");
            }
            case UNKNOWN -> {
                // Silent. An unpaired index or an in-memory dataset is an ordinary
                // configuration, and the config-status log above has already spoken.
            }
        }
    }

    /**
     * Record this configuration as the one the index content was built from.
     * <p>
     * Called automatically for a new index, and explicitly by the rebuild path after the
     * index has been repopulated. Lucene carries live commit data forward, so the stamp
     * lands on the next commit and persists after that.
     *
     * @param pairedDatasetId identity of the dataset the content came from, or null when
     *                        it is unknown; see {@link DatasetInstanceId}
     */
    public void stampConfig(String pairedDatasetId) {
        ShaclIndexStamp.StampData stamp = new ShaclIndexStamp.StampData(
            configFingerprint,
            ShaclConfigFingerprint.FINGERPRINT_VERSION,
            java.time.Instant.now().toString(),
            org.apache.jena.atlas.lib.Version.versionForClass(ShaclTextIndexLucene.class).orElse("unknown"),
            java.util.UUID.randomUUID().toString(),
            pairedDatasetId);
        ShaclIndexStamp.write(getIndexWriter(), stamp);
    }

    /**
     * Report, once per index open, whether the index on disk was built from this
     * configuration.
     * <p>
     * A match is logged as well as a mismatch. A check that says nothing when healthy is
     * a check nobody trusts when it finally speaks, and "no warning" is indistinguishable
     * from "the check never ran".
     * <p>
     * A mismatch warns rather than failing. An index built from an older configuration
     * still answers correctly for every field that existed when it was built, so refusing
     * to start would turn a degraded service into an outage.
     */
    private void logConfigStatus() {
        switch ( getConfigStatus() ) {
            case MATCH ->
                log.info("Index configuration matches the index on disk ({})", configFingerprint);
            case MISMATCH -> {
                log.warn("Index configuration MISMATCH - the Lucene index was built from a different configuration.");
                log.warn("    index was built from: {}  (at {})", openedStamp.fingerprint(), openedStamp.builtAt());
                log.warn("    running config is:    {}", configFingerprint);
                log.warn("    Fields, facets or hierarchies added since the index was built will return no results.");
                log.warn("    Rebuild with: shacltextindexer --desc=<config>");
            }
            case UNKNOWN -> {
                if ( openedStamp == null )
                    log.info("Index configuration not verified: the index carries no configuration stamp "
                             + "(built before stamping, or built elsewhere). Running config is {}", configFingerprint);
                else
                    log.info("Index configuration not verified: stamp uses fingerprint version {}, this build understands {}",
                             openedStamp.version(), ShaclConfigFingerprint.FINGERPRINT_VERSION);
            }
        }
    }

    /** Fingerprint of the configuration this instance is running with. */
    public String getConfigFingerprint() {
        return configFingerprint;
    }

    /** The stamp present on disk when this index was opened, or null if there was none. */
    public ShaclIndexStamp.StampData getOpenedStamp() {
        return openedStamp;
    }

    /** Whether the index on disk was built from a shape-equivalent configuration. */
    public ShaclIndexStamp.Status getConfigStatus() {
        return ShaclIndexStamp.compare(openedStamp, configFingerprint);
    }

    /** (Re)initialise the {@link SearcherManager} against the current writer. */
    private void initSearcherManager() {
        try {
            this.searcherManager = new SearcherManager(getIndexWriter(), null);
        } catch (IOException e) {
            throw new TextIndexException("Failed to initialise SearcherManager", e);
        }
    }

    /**
     * Acquire an {@link IndexSearcher} from the cached {@link SearcherManager}.
     * The caller MUST pair this with {@link #releaseSearcher(IndexSearcher)} in a
     * try/finally — a missed release leaks reader references.
     */
    private IndexSearcher acquireSearcher() {
        try {
            return searcherManager.acquire();
        } catch (IOException e) {
            throw new TextIndexException("acquireSearcher", e);
        }
    }

    /** Release a searcher previously obtained from {@link #acquireSearcher()}. */
    private void releaseSearcher(IndexSearcher searcher) {
        if (searcher == null) {
            return;
        }
        try {
            searcherManager.release(searcher);
        } catch (IOException e) {
            log.warn("Failed to release searcher: {}", e.getMessage());
        }
    }

    public ShaclIndexMapping getShaclMapping() {
        return shaclMapping;
    }

    public boolean isShaclMode() {
        return true;
    }

    public boolean hasHierarchies() {
        return taxoWriter != null;
    }

    public DirectoryTaxonomyWriter getTaxoWriter() {
        return taxoWriter;
    }

    public Set<String> getHierarchyDimensions() {
        return Collections.unmodifiableSet(hierarchyDimensions);
    }

    @Override
    public void commit() {
        super.commit();
        if (taxoWriter != null) {
            try {
                taxoWriter.commit();
            } catch (IOException e) {
                throw new TextIndexException("Failed to commit taxonomy writer", e);
            }
        }
        // Only refresh after a fully successful commit chain — an exception above
        // skips this. Reads in flight against the previous reader stay valid against
        // their acquired snapshot; subsequent acquires see the refreshed reader.
        if (searcherManager != null) {
            try {
                searcherManager.maybeRefresh();
            } catch (IOException e) {
                throw new TextIndexException("Failed to refresh searcher", e);
            }
        }
        // Invalidate the open-facet cache by replacing the map (not clear()) so
        // concurrent readers holding a reference to the old map see consistent
        // entries from the previous snapshot until they release their searcher.
        openFacetCache = new java.util.concurrent.ConcurrentHashMap<>();
    }

    @Override
    public void rollback() {
        // The parent rollback closes and re-opens the IndexWriter, so any
        // SearcherManager rooted at the old writer is invalidated.
        SearcherManager old = searcherManager;
        searcherManager = null;
        if (old != null) {
            try { old.close(); }
            catch (IOException e) { log.warn("Failed to close searcher manager on rollback: {}", e.getMessage()); }
        }
        super.rollback();
        initSearcherManager();
    }

    @Override
    public void close() {
        if (searcherManager != null) {
            try { searcherManager.close(); }
            catch (IOException e) { log.warn("Failed to close searcher manager: {}", e.getMessage()); }
            searcherManager = null;
        }
        // ClosedListeners typically clear entries during searcherManager.close(),
        // but clear defensively in case any reader bypassed the cache helper path.
        ssdvStateCache.clear();
        try {
            if (taxoWriter != null) {
                taxoWriter.close();
            }
            if (taxoDirectory != null) {
                taxoDirectory.close();
            }
        } catch (IOException e) {
            log.warn("Error closing taxonomy resources: {}", e.getMessage());
        }
        super.close();
    }

    // ---- Field-scoped query support ----

    /**
     * Resolve field spec strings to validated Lucene field names.
     * "default" resolves to all defaultSearch fields; explicit names are validated.
     */
    /**
     * Resolve facet field IRIs to Lucene field names.
     * <p>
     * A field IRI resolves to that field's own facet dimension, including when the field is
     * also a level of a {@code facetHierarchy} — every facetable field is indexed as a flat
     * dimension in its own right, so hierarchy membership must not redirect the request to
     * the hierarchy's dimension. Faceting a dimension with no drill-down path returns its
     * top level, so that redirect answered with counts for a different field.
     * <p>
     * To facet a hierarchy, name its dimension: those are passed through unchanged, as are
     * identifiers that match no field at all. Naming a field that is not facetable is an
     * error — there is no dimension to answer from.
     */
    public List<String> resolveFacetFieldNames(List<String> fieldIRIs) {
        if (fieldIRIs == null) return null;
        // Wildcard expands only to flat/hierarchical facet targets, not numeric range fields.
        if (fieldIRIs.size() == 1 && "*".equals(fieldIRIs.get(0))) {
            List<String> all = new ArrayList<>();
            for (String fieldName : facetFields) {
                ShaclIndexMapping.FieldDef fd = shaclMapping.findFieldByName(fieldName);
                if (fd != null && fd.getFieldType() == ShaclIndexMapping.FieldType.KEYWORD) {
                    all.add(fieldName);
                }
            }
            all.addAll(hierarchyDimensions);
            return all;
        }
        List<String> resolved = new ArrayList<>(fieldIRIs.size());
        for (String spec : fieldIRIs) {
            if (hierarchyDimensions.contains(spec)) {
                if (!resolved.contains(spec)) {
                    resolved.add(spec);
                }
                continue;
            }
            ShaclIndexMapping.FieldDef fd = shaclMapping.findField(spec);
            if (fd == null) {
                fd = shaclMapping.findFieldByName(spec);
            }
            if (fd == null) {
                resolved.add(spec);
                continue;
            }
            if (!fd.isFacetable()) {
                throw new TextIndexException("Facet field is not facetable: " + spec);
            }
            if (!resolved.contains(fd.getFieldName())) {
                resolved.add(fd.getFieldName());
            }
        }
        return resolved;
    }

    private static boolean isNumericField(ShaclIndexMapping.FieldDef fieldDef) {
        if (fieldDef == null) return false;
        return switch (fieldDef.getFieldType()) {
            case INT, LONG, DOUBLE, TEMPORAL -> true;
            default -> false;
        };
    }

    private boolean hasSortedSetFacetFields() {
        for (String fieldName : facetFields) {
            ShaclIndexMapping.FieldDef fd = shaclMapping.findFieldByName(fieldName);
            if (fd != null && fd.getFieldType() == ShaclIndexMapping.FieldType.KEYWORD) {
                return true;
            }
        }
        return false;
    }

    public List<String> resolveSearchFields(List<String> fieldIRIs) {
        if (fieldIRIs == null || fieldIRIs.isEmpty()
                || (fieldIRIs.size() == 1 && "default".equals(fieldIRIs.get(0)))) {
            List<String> defaults = shaclMapping.getDefaultSearchFieldNames();
            if (defaults.isEmpty()) {
                log.warn("No defaultSearch fields configured; falling back to primary field");
                return List.of(getDocDef().getPrimaryField());
            }
            return defaults;
        }
        List<String> resolved = new ArrayList<>(fieldIRIs.size());
        for (String iri : fieldIRIs) {
            ShaclIndexMapping.FieldDef fd = shaclMapping.findField(iri);
            if (fd == null) {
                throw new TextIndexException("Unknown search field: '" + iri + "'. "
                    + "Available fields: " + shaclMapping.getAllFieldNames());
            }
            resolved.add(fd.getFieldName());
        }
        return resolved;
    }

    /**
     * Parse a query string targeting specific fields.
     * Uses MultiFieldQueryParser for multiple fields, standard QueryParser for single.
     */
    protected Query parseQueryForFields(String queryString, List<String> fields) throws ParseException {
        // A vector field short-circuits the query parser entirely: the query string is text
        // to embed, not a Lucene expression. Call sites that hold a compiled CQL filter call
        // buildKnnQuery directly so the filter can be pushed into the graph traversal; this
        // path serves the ones that have no filter to push.
        ShaclIndexMapping.FieldDef vectorField = vectorFieldIn(fields);
        if (vectorField != null) {
            return buildKnnQuery(vectorField, queryString, null);
        }
        if ("*".equals(queryString)) {
            return new MatchAllDocsQuery();
        }
        // Partition fields by scope: root-scoped fields stay flat on the parent doc;
        // child-scoped fields live on child docs and need a ToParentBlockJoinQuery lift.
        List<String> rootFields = new ArrayList<>();
        Map<String, List<String>> fieldsByNestedScope = new LinkedHashMap<>();
        for (String fieldName : fields) {
            ShaclIndexMapping.NestedDef scope = shaclMapping.findNestedDefForFieldName(fieldName);
            if (scope == null) {
                rootFields.add(fieldName);
            } else {
                fieldsByNestedScope
                    .computeIfAbsent(scope.getNestedName(), k -> new ArrayList<>())
                    .add(fieldName);
            }
        }

        // Fast path: all root-scoped — preserve previous single/multi-field parser behaviour.
        if (fieldsByNestedScope.isEmpty()) {
            if (rootFields.size() == 1) {
                QueryParser qp = new QueryParser(rootFields.get(0), getQueryAnalyzer());
                qp.setAllowLeadingWildcard(true);
                return qp.parse(queryString);
            }
            String[] fieldArray = rootFields.toArray(new String[0]);
            MultiFieldQueryParser mqp = new MultiFieldQueryParser(fieldArray, getQueryAnalyzer());
            mqp.setAllowLeadingWildcard(true);
            return mqp.parse(queryString);
        }

        // At least one child-scoped field present. Build per-scope queries, wrapping
        // each nested scope in a ToParentBlockJoinQuery, then OR them together (a hit
        // anywhere across the listed fields should surface the parent entity).
        List<Query> clauses = new ArrayList<>();
        if (!rootFields.isEmpty()) {
            Query rootQ;
            if (rootFields.size() == 1) {
                QueryParser qp = new QueryParser(rootFields.get(0), getQueryAnalyzer());
                qp.setAllowLeadingWildcard(true);
                rootQ = qp.parse(queryString);
            } else {
                MultiFieldQueryParser mqp = new MultiFieldQueryParser(
                    rootFields.toArray(new String[0]), getQueryAnalyzer());
                mqp.setAllowLeadingWildcard(true);
                rootQ = mqp.parse(queryString);
            }
            clauses.add(rootQ);
        }
        for (List<String> scopeFields : fieldsByNestedScope.values()) {
            Query inner;
            if (scopeFields.size() == 1) {
                QueryParser qp = new QueryParser(scopeFields.get(0), getQueryAnalyzer());
                qp.setAllowLeadingWildcard(true);
                inner = qp.parse(queryString);
            } else {
                MultiFieldQueryParser mqp = new MultiFieldQueryParser(
                    scopeFields.toArray(new String[0]), getQueryAnalyzer());
                mqp.setAllowLeadingWildcard(true);
                inner = mqp.parse(queryString);
            }
            clauses.add(wrapAsParent(inner));
        }
        if (clauses.size() == 1) {
            return clauses.get(0);
        }
        BooleanQuery.Builder bq = new BooleanQuery.Builder();
        for (Query q : clauses) {
            bq.add(q, BooleanClause.Occur.SHOULD);
        }
        bq.setMinimumNumberShouldMatch(1);
        return bq.build();
    }

    /**
     * Compile a CQL filter to the Lucene query that can be pushed into the index, or null
     * when there is no filter. Residual (non-pushable) expressions are logged and dropped,
     * matching the behaviour every call site already had.
     */
    private Query compileCqlFilter(CqlExpression cqlFilter) {
        if (cqlFilter == null) {
            return null;
        }
        CqlToLuceneCompiler compiler = new CqlToLuceneCompiler(shaclMapping, facetsConfig, getQueryAnalyzer());
        CqlToLuceneCompiler.CompileResult result = compiler.compile(cqlFilter);
        if (result.residual() != null) {
            log.warn("CQL filter has residual expressions ignored: {}", result.residual().toCanonical());
        }
        return result.pushed();
    }

    // ---- Vector (KNN) search ----

    /**
     * The single VECTOR field among {@code resolvedFields}, or null if there is none.
     * <p>
     * Two combinations are refused rather than guessed at:
     * <ul>
     *   <li><b>Two vector fields.</b> There is no defined way to combine two independent
     *       similarity rankings.</li>
     *   <li><b>A vector field mixed with text fields.</b> This is hybrid retrieval, and
     *       doing it as a boolean SHOULD would add a BM25 score to a cosine similarity —
     *       two quantities on unrelated scales, where whichever happens to be numerically
     *       larger dominates. Reciprocal rank fusion is the correct treatment and is not
     *       implemented yet, so the query is refused instead of silently answered badly.</li>
     * </ul>
     */
    private ShaclIndexMapping.FieldDef vectorFieldIn(List<String> resolvedFields) {
        if (resolvedFields == null) {
            return null;
        }
        ShaclIndexMapping.FieldDef vectorField = null;
        List<String> textFields = new ArrayList<>();
        for (String fieldName : resolvedFields) {
            ShaclIndexMapping.FieldDef fd = shaclMapping.findFieldByName(fieldName);
            if (fd != null && fd.isVector()) {
                if (vectorField != null) {
                    throw new TextIndexException("Query names more than one vector field ('"
                        + vectorField.getFieldName() + "', '" + fd.getFieldName()
                        + "'). A KNN search ranks by one similarity measure.");
                }
                vectorField = fd;
            } else {
                textFields.add(fieldName);
            }
        }
        if (vectorField != null && !textFields.isEmpty()) {
            throw new TextIndexException("Query mixes vector field '" + vectorField.getFieldName()
                + "' with text field(s) " + textFields + ". Hybrid dense/lexical retrieval needs "
                + "rank fusion to combine incomparable scores and is not implemented; search them "
                + "as separate luc:query patterns.");
        }
        return vectorField;
    }

    /**
     * Build a filtered KNN query: embed {@code qs} and search {@code vectorField} for its
     * nearest neighbours, restricted to documents matching {@code pushedFilter}.
     * <p>
     * The filter goes into the {@link KnnFloatVectorQuery} constructor rather than being
     * ANDed with the result. That is the whole point of doing this in Lucene: the filter is
     * applied <em>during</em> the HNSW graph traversal, so k documents that satisfy it are
     * returned. Post-filtering a KNN result instead — which is what a boolean AND would do,
     * and what pgvector is known for — can return far fewer than k, or none at all, when
     * the filter is selective.
     */
    private Query buildKnnQuery(ShaclIndexMapping.FieldDef vectorField, String qs, Query pushedFilter) {
        if (embeddingProvider == null) {
            throw new TextIndexException("Vector field '" + vectorField.getFieldName()
                + "' was queried but no embedding provider is configured");
        }
        if (qs == null || qs.isEmpty() || "*".equals(qs)) {
            throw new TextIndexException("Vector field '" + vectorField.getFieldName()
                + "' needs query text to embed; got " + (qs == null || qs.isEmpty() ? "an empty string" : "\"*\"")
                + ". A similarity search has no match-all form — there is no point to be near.");
        }
        float[] target = embeddingProvider.embedQuery(qs);
        int dimension = vectorField.getVectorDef().dimension();
        if (target.length != dimension) {
            throw new TextIndexException("Embedding provider returned a " + target.length
                + "-dimensional query vector for field '" + vectorField.getFieldName()
                + "', which was indexed with dimension " + dimension
                + ". Index-time and query-time models must match.");
        }
        // The parent-doc restriction belongs inside the KNN filter, not around the result:
        // vectors live only on parent docs, and filtering afterwards would waste graph
        // traversal on children that can never match.
        return new KnnFloatVectorQuery(vectorField.getFieldName(), target, knnTopK,
            filterToParents(pushedFilter));
    }

    /**
     * Build a query with NamedMatches wrapping for field attribution.
     * For multi-field queries, each field gets a named sub-query so
     * we can later determine which field(s) matched each hit.
     */
    protected Query buildNamedQuery(String queryString, List<String> resolvedFields) throws ParseException {
        if ("*".equals(queryString)) {
            return new MatchAllDocsQuery();
        }
        // Per-field named clause, with child-scoped fields wrapped in ToParentBlockJoinQuery
        // so a matching child-doc clause surfaces its parent in the result iteration.
        BooleanQuery.Builder bq = new BooleanQuery.Builder();
        for (String fieldName : resolvedFields) {
            QueryParser qp = new QueryParser(fieldName, getQueryAnalyzer());
            qp.setAllowLeadingWildcard(true);
            Query fieldQuery = qp.parse(queryString);
            ShaclIndexMapping.FieldDef fd = shaclMapping.findFieldByName(fieldName);
            String name = fd != null ? fd.getFieldIRI().getURI() : fieldName;
            Query named = NamedMatches.wrapQuery(name, fieldQuery);
            ShaclIndexMapping.NestedDef scope = shaclMapping.findNestedDefForFieldName(fieldName);
            if (scope != null) {
                named = wrapAsParent(named);
            }
            bq.add(named, BooleanClause.Occur.SHOULD);
        }
        BooleanQuery built = bq.build();
        if (built.clauses().size() == 1) {
            return built.clauses().get(0).query();
        }
        return built;
    }

    /**
     * Execute a search and return SearchHit objects with stable hit IDs.
     * The returned hits carry Lucene doc IDs for later field match extraction.
     */
    public List<SearchHit> searchWithHitIds(List<String> resolvedFields, String qs,
            CqlExpression cqlFilter, List<SortSpec> sortSpecs,
            String graphURI, String lang, int limit) {
        IndexSearcher searcher = acquireSearcher();
        try {
            Query pushedFilter = compileCqlFilter(cqlFilter);
            ShaclIndexMapping.FieldDef vectorField = vectorFieldIn(resolvedFields);

            Query textQuery;
            Query finalQuery;
            if (vectorField != null) {
                // The filter is inside the KNN query, so there is nothing left to AND on.
                textQuery = buildKnnQuery(vectorField, qs, pushedFilter);
                finalQuery = textQuery;
            } else {
                if (qs == null || qs.isEmpty()) {
                    textQuery = new MatchAllDocsQuery();
                } else {
                    textQuery = buildNamedQuery(qs, resolvedFields);
                }
                if (pushedFilter != null) {
                    finalQuery = new BooleanQuery.Builder()
                        .add(textQuery, BooleanClause.Occur.MUST)
                        .add(pushedFilter, BooleanClause.Occur.MUST)
                        .build();
                } else {
                    finalQuery = textQuery;
                }
            }

            int maxHits = limit > 0 ? limit : MAX_N;
            Sort luceneSort = buildLuceneSort(sortSpecs);

            Query searchQuery = filterToParents(finalQuery);
            TopDocs topDocs;
            if (luceneSort != null) {
                topDocs = searcher.search(searchQuery, maxHits, luceneSort);
            } else {
                topDocs = searcher.search(searchQuery, maxHits);
            }

            String entityField = getDocDef().getEntityField();
            StoredFields storedFields = searcher.storedFields();
            List<SearchHit> results = new ArrayList<>();
            int idx = 0;
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = storedFields.document(sd.doc);
                String uri = doc.get(entityField);
                if (uri != null) {
                    Node entityNode = TextQueryFuncs.stringToNode(uri);
                    float score = luceneSort == null ? sd.score : rankScore(idx);
                    results.add(new SearchHit(idx++, entityNode, score, sd.doc));
                }
            }

            // Compute field matches using NamedMatches. A KNN query carries none — a vector
            // hit has no matching term to attribute, only a distance — so skip the rewrite
            // and weight pass entirely rather than run it for an empty result.
            if (!results.isEmpty() && !(textQuery instanceof MatchAllDocsQuery) && vectorField == null) {
                computeFieldMatches(searcher, textQuery, resolvedFields, results);
            }

            // Project the child documents the filter selected. Done here, inside the same
            // try block, because this is the one place where the compiled query, a live
            // searcher and valid parent doc ids all coexist — resolving children later
            // would read doc ids against a reader that may have been refreshed since.
            if (!results.isEmpty()) {
                computeNestedMatches(searcher, finalQuery, results);
            }

            return results;
        } catch (IOException ex) {
            throw new TextIndexException("searchWithHitIds", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(qs, ex.getMessage());
        } finally {
            releaseSearcher(searcher);
        }
    }

    /**
     * The score for a hit from a sorted search.
     * <p>
     * Lucene does not score documents when a {@link Sort} is supplied — {@code ScoreDoc.score}
     * is {@code NaN} — so rank stands in for relevance: a value in {@code (0,1]} that strictly
     * decreases with position, keeping "higher score first" true for sorted and unsorted
     * searches alike. It depends only on the rank, so a hit keeps the same score when a later
     * page re-runs the search with a larger window.
     */
    private static float rankScore(int rank) {
        return 1.0f / (1 + rank);
    }

    /**
     * Compute field-level matches for each hit using Lucene's Matches/NamedMatches API.
     */
    private void computeFieldMatches(IndexSearcher searcher, Query textQuery,
            List<String> resolvedFields, List<SearchHit> hits) throws IOException {
        Query rewritten = searcher.rewrite(textQuery);
        Weight weight = searcher.createWeight(rewritten, ScoreMode.COMPLETE, 1.0f);
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();

        for (SearchHit hit : hits) {
            int docId = hit.getLuceneDocId();
            // Find the leaf context for this doc
            int leafIdx = ReaderUtil.subIndex(docId, leaves);
            LeafReaderContext leaf = leaves.get(leafIdx);
            int segmentDocId = docId - leaf.docBase;

            Matches matches = weight.matches(leaf, segmentDocId);
            if (matches == null) continue;

            // Extract named matches → field IRIs
            Collection<NamedMatches> namedMatchList = NamedMatches.findNamedMatches(matches);
            List<FieldMatch> fieldMatches = new ArrayList<>();

            StoredFields storedFields = searcher.storedFields();
            Document doc = storedFields.document(docId);

            for (NamedMatches nm : namedMatchList) {
                String name = nm.getName(); // This is the field IRI string
                Node fieldIRI = NodeFactory.createURI(name);

                ShaclIndexMapping.FieldDef fd = shaclMapping.findField(name);
                Node valueNode = null;
                if (fd != null) {
                    String[] storedValues = doc.getValues(fd.getFieldName());
                    if (storedValues != null && storedValues.length > 0) {
                        SelectedStoredValue selected = selectStoredValue(fd.getFieldName(), storedValues, textQuery);
                        valueNode = fieldValueToNode(doc, fd, selected);
                    }
                }

                fieldMatches.add(new FieldMatch(fieldIRI, valueNode, null));
            }

            // If no named matches found, fall back to field-level match check
            if (fieldMatches.isEmpty()) {
                for (String fieldName : resolvedFields) {
                    MatchesIterator mi = matches.getMatches(fieldName);
                    if (mi != null && mi.next()) {
                        ShaclIndexMapping.FieldDef fd = shaclMapping.findFieldByName(fieldName);
                        Node fieldIRI = fd != null ? fd.getFieldIRI()
                            : NodeFactory.createLiteralString(fieldName);
                        Node valueNode = null;
                        if (fd != null) {
                            String[] storedValues = doc.getValues(fieldName);
                            if (storedValues != null && storedValues.length > 0) {
                                SelectedStoredValue selected = selectStoredValue(fieldName, storedValues, textQuery);
                                valueNode = fieldValueToNode(doc, fd, selected);
                            }
                        }
                        fieldMatches.add(new FieldMatch(fieldIRI, valueNode, null));
                    }
                }
            }

            hit.setFieldMatches(fieldMatches);
        }
    }

    /**
     * Attach to each hit the block-join child documents that satisfied the filter.
     * <p>
     * The child queries are recovered from the compiled query rather than from the
     * {@link CqlExpression} it came from: the compiler has already wrapped every nested
     * clause in a {@link ToParentBlockJoinQuery}, so its {@code getChildQuery()} is
     * exactly the "which children" predicate, whether the clause was folded as a
     * same-child group or lifted on its own. Nothing here re-interprets CQL.
     * <p>
     * Each surviving child is then re-queried with {@link ParentChildrenBlockJoinQuery},
     * which restricts the child query to the block belonging to one parent — the same
     * primitive an "inner hits" feature is built on, and the reason no doc id arithmetic
     * appears in this method.
     */
    private void computeNestedMatches(IndexSearcher searcher, Query finalQuery, List<SearchHit> hits)
            throws IOException {
        List<Query> childQueries = new ArrayList<>();
        collectChildQueries(finalQuery, childQueries);
        if (childQueries.isEmpty()) {
            // No nested clause: nothing selected a child, so there is no matching child
            // to report. Returning every child of every scope would be a different
            // feature wearing the same name.
            return;
        }

        StoredFields storedFields = searcher.storedFields();
        for (SearchHit hit : hits) {
            // Sorted and de-duplicated: one child can satisfy more than one nested clause,
            // and index order keeps the projection stable across pages.
            Set<Integer> childDocIds = new TreeSet<>();
            for (Query childQuery : childQueries) {
                Query scoped = new ParentChildrenBlockJoinQuery(
                    PARENTS_FILTER, childQuery, hit.getLuceneDocId());
                for (ScoreDoc sd : searcher.search(scoped, MAX_NESTED_MATCHES_PER_HIT + 1).scoreDocs) {
                    childDocIds.add(sd.doc);
                }
            }
            if (childDocIds.isEmpty()) {
                continue;
            }
            if (childDocIds.size() > MAX_NESTED_MATCHES_PER_HIT) {
                log.warn("Hit {} matched {} child records; projecting the first {}",
                    hit.getEntityNode(), childDocIds.size(), MAX_NESTED_MATCHES_PER_HIT);
            }

            List<NestedMatch> matches = new ArrayList<>();
            for (int childDocId : childDocIds) {
                if (matches.size() >= MAX_NESTED_MATCHES_PER_HIT) {
                    break;
                }
                NestedMatch match = nestedMatchFromChild(
                    hit.getRank(), matches.size(), storedFields.document(childDocId));
                if (match != null) {
                    matches.add(match);
                }
            }
            hit.setNestedMatches(matches);
        }
    }

    /**
     * Collect the child-doc query of every {@link ToParentBlockJoinQuery} reachable in
     * {@code query} without passing through a negation.
     * <p>
     * A {@code MUST_NOT} nested clause describes children that must <em>not</em> match;
     * those children are not why the parent surfaced, so projecting them would report the
     * opposite of what was asked.
     */
    private static void collectChildQueries(Query query, List<Query> out) {
        switch (query) {
            case ToParentBlockJoinQuery blockJoin -> out.add(blockJoin.getChildQuery());
            case BooleanQuery booleanQuery -> {
                for (BooleanClause clause : booleanQuery) {
                    if (clause.occur() != BooleanClause.Occur.MUST_NOT) {
                        collectChildQueries(clause.query(), out);
                    }
                }
            }
            case BoostQuery boost -> collectChildQueries(boost.getQuery(), out);
            case ConstantScoreQuery constantScore -> collectChildQueries(constantScore.getQuery(), out);
            default -> { }
        }
    }

    /** Project one child document's stored fields, or null if it has nothing to project. */
    private NestedMatch nestedMatchFromChild(int hitRank, int ordinal, Document childDoc) {
        String scope = childDoc.get(NESTED_SCOPE_FIELD);
        ShaclIndexMapping.NestedDef nestedDef = shaclMapping.findNestedDefByName(scope);
        if (nestedDef == null) {
            log.warn("Child document carries nested scope '{}', which is not in the current "
                + "mapping — reindex after changing idx:joinPath", scope);
            return null;
        }

        List<FieldMatch> fieldMatches = new ArrayList<>();
        for (ShaclIndexMapping.FieldDef fd : nestedDef.getFields()) {
            if (!fd.isStored()) {
                continue;
            }
            String[] storedValues = childDoc.getValues(fd.getFieldName());
            if (storedValues == null) {
                continue;
            }
            Node fieldIRI = fd.getFieldIRI() != null
                ? fd.getFieldIRI()
                : NodeFactory.createLiteralString(fd.getFieldName());
            for (int i = 0; i < storedValues.length; i++) {
                // Every stored value is projected, positionally aligned with its datatype
                // and language twins. selectStoredValue's "which one did the query mean"
                // heuristic is not needed and would be wrong here: a child document holds
                // exactly the one record being reported.
                Node value = fieldValueToNode(childDoc, fd, new SelectedStoredValue(i, storedValues[i]));
                if (value != null) {
                    fieldMatches.add(new FieldMatch(fieldIRI, value, null));
                }
            }
        }
        return fieldMatches.isEmpty() ? null : new NestedMatch(hitRank, ordinal, scope, fieldMatches);
    }

    /**
     * Convert a stored field value to an appropriate RDF node based on field type.
     */
    private Node fieldValueToNode(Document doc, ShaclIndexMapping.FieldDef fd, SelectedStoredValue selectedValue) {
        if (selectedValue == null || selectedValue.value() == null || fd == null) return null;
        return LiteralFieldSupport.reconstructNode(fd, selectedValue.value(),
            alignedStoredValue(doc, LiteralFieldSupport.datatypeField(fd.getFieldName()), selectedValue.index()),
            alignedStoredValue(doc, LiteralFieldSupport.langField(fd.getFieldName()), selectedValue.index()));
    }

    // ---- Document building ----

    protected Document docFromMapping(Entity entity, ShaclIndexMapping.IndexProfile profile) {
        Document doc = new Document();

        // Tag as parent doc for block-join discrimination.
        doc.add(new StringField(BLOCK_KIND_FIELD, BLOCK_KIND_PARENT, Field.Store.NO));

        String docIdField = profile.getDocIdField();
        doc.add(new Field(docIdField, entity.getId(), ftIRI));

        String discriminatorField = profile.getDiscriminatorField();
        if (discriminatorField != null && !profile.getTargetClasses().isEmpty()) {
            Node firstClass = profile.getTargetClasses().iterator().next();
            String localName = firstClass.getLocalName();
            if (localName != null && !localName.isEmpty()) {
                doc.add(new StringField(discriminatorField, localName, Field.Store.YES));
            }
        }

        for (ShaclIndexMapping.FieldDef fieldDef : profile.getFields()) {
            Object value = entity.get(fieldDef.getFieldName());
            if (value == null) continue;

            if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> values = (List<Object>) value;
                List<Object> valuesToIndex = values;
                if (!fieldDef.isMultiValued() && values.size() > 1) {
                    log.warn("Multiple values found for non-multi-valued field '{}' on entity '{}'; only the first value will be indexed. To index all values, set <{}> true in the index configuration.",
                        fieldDef.getFieldName(), entity.getId(), MULTI_VALUED_CONFIG_IRI);
                    valuesToIndex = Collections.singletonList(values.get(0));
                }
                for (Object v : valuesToIndex) {
                    addFieldToDoc(doc, fieldDef, v);
                }
            } else {
                addFieldToDoc(doc, fieldDef, value);
            }
        }

        // Add hierarchical facet fields
        addHierarchyFacetFields(doc, entity, profile);

        // Vectors last: they read the values the loop above just consumed, and they are the
        // one field kind derived from other fields rather than extracted from the entity.
        addVectorFields(doc, entity, profile);

        return doc;
    }

    /**
     * Verbalise each vector field's source values and write the embedding.
     * <p>
     * An entity with no text in any source field is skipped rather than embedded: Lucene
     * treats a missing KNN field as "not a candidate", which is the right answer for an
     * entity with nothing to be semantically similar to. Embedding the empty string
     * instead would put every such entity at one arbitrary point in the space, where they
     * would surface as neighbours of each other and of whatever happens to be nearby.
     */
    private void addVectorFields(Document doc, Entity entity, ShaclIndexMapping.IndexProfile profile) {
        List<ShaclIndexMapping.FieldDef> vectorFields = profile.getVectorFields();
        if (vectorFields.isEmpty()) {
            return;
        }
        if (embeddingProvider == null) {
            // The assembler rejects this combination, so reaching here means the index was
            // built through some other path (a hand-constructed TextIndexConfig in a test).
            throw new TextIndexException("Profile " + profile.getShapeNode()
                + " declares vector field(s) but no embedding provider is configured");
        }

        for (ShaclIndexMapping.FieldDef fieldDef : vectorFields) {
            String text = verbaliseForEmbedding(entity, fieldDef.getVectorDef());
            if (text.isEmpty()) {
                log.debug("Entity '{}' has no text in the source fields of vector field '{}'; "
                    + "no embedding written", entity.getId(), fieldDef.getFieldName());
                continue;
            }
            float[] vector = embeddingProvider.embedDocument(text);
            if (vector.length != fieldDef.getVectorDef().dimension()) {
                throw new TextIndexException("Embedding provider returned a " + vector.length
                    + "-dimensional vector for field '" + fieldDef.getFieldName()
                    + "', which declares dimension " + fieldDef.getVectorDef().dimension());
            }
            doc.add(new KnnFloatVectorField(fieldDef.getFieldName(), vector,
                luceneSimilarity(fieldDef.getVectorDef().similarity())));
        }
    }

    /**
     * Flatten the values of a vector field's source fields into the string that gets
     * embedded.
     * <p>
     * Source fields are joined in their declared order, values within a field in index
     * order, separated by ". " so the model sees sentence-ish boundaries rather than one
     * run-on token stream. Declaration order is honoured because it is the one thing the
     * config author controls about the verbalisation — and because changing it changes
     * every vector, so it must not depend on map iteration order.
     */
    private String verbaliseForEmbedding(Entity entity, ShaclIndexMapping.VectorDef vectorDef) {
        StringBuilder sb = new StringBuilder();
        for (String sourceIRI : vectorDef.sourceFieldIRIs()) {
            ShaclIndexMapping.FieldDef sourceField = shaclMapping.findField(sourceIRI);
            if (sourceField == null) continue;
            Object value = entity.get(sourceField.getFieldName());
            if (value == null) continue;

            if (value instanceof List<?> values) {
                for (Object v : values) {
                    appendVerbalisedValue(sb, v);
                }
            } else {
                appendVerbalisedValue(sb, value);
            }
        }
        return sb.toString();
    }

    private static void appendVerbalisedValue(StringBuilder sb, Object value) {
        if (value == null) return;
        String text = value instanceof Node node
            ? (node.isLiteral() ? node.getLiteralLexicalForm() : node.toString())
            : value.toString();
        if (text.isBlank()) return;
        if (sb.length() > 0) {
            sb.append(". ");
        }
        sb.append(text);
    }

    private static VectorSimilarityFunction luceneSimilarity(ShaclIndexMapping.VectorSimilarity similarity) {
        return switch (similarity) {
            case COSINE -> VectorSimilarityFunction.COSINE;
            case DOT_PRODUCT -> VectorSimilarityFunction.DOT_PRODUCT;
            case EUCLIDEAN -> VectorSimilarityFunction.EUCLIDEAN;
            case MAXIMUM_INNER_PRODUCT -> VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT;
        };
    }

    /**
     * Build child documents for each nested record carried by {@code entity}, one
     * Lucene doc per {@link Entity.NestedRecord}. Returned in the order they should
     * appear in the Lucene block — i.e. before the parent doc. Each child carries:
     * <ul>
     *   <li>{@code _blockKind = "child"}</li>
     *   <li>the entity's URI in the profile's docIdField (so block-delete by parent
     *       term hits the whole block)</li>
     *   <li>the profile's discriminatorField (so multi-profile delete remains scoped)</li>
     *   <li>{@code _nestedScope = <nestedName>}</li>
     *   <li>the child-scoped field values</li>
     * </ul>
     * Children do NOT carry parent-flattened fields, hierarchy facet paths, or other
     * parent-only data. The parent doc continues to carry the denormalised flattened
     * representation for backward compatibility (Phase 1 reads still hit the parent).
     */
    protected List<Document> childDocsFromMapping(Entity entity, ShaclIndexMapping.IndexProfile profile) {
        if (profile.getNestedDefs().isEmpty()) {
            return Collections.emptyList();
        }

        String docIdField = profile.getDocIdField();
        String discriminatorField = profile.getDiscriminatorField();
        String discriminatorValue = null;
        if (discriminatorField != null && !profile.getTargetClasses().isEmpty()) {
            Node firstClass = profile.getTargetClasses().iterator().next();
            String localName = firstClass.getLocalName();
            if (localName != null && !localName.isEmpty()) {
                discriminatorValue = localName;
            }
        }

        List<Document> children = new ArrayList<>();
        for (ShaclIndexMapping.NestedDef nestedDef : profile.getNestedDefs()) {
            List<Entity.NestedRecord> records = entity.getNestedRecords(nestedDef.getNestedName());
            if (records == null || records.isEmpty()) {
                continue;
            }
            // Fields, not occurrences: an external nested block has no occurrences, its
            // fields come from the idx:column bindings instead.
            Map<String, ShaclIndexMapping.FieldDef> childFieldDefs = new LinkedHashMap<>();
            for (ShaclIndexMapping.FieldDef field : nestedDef.getFields()) {
                childFieldDefs.put(field.getFieldName(), field);
            }

            for (Entity.NestedRecord record : records) {
                Document child = new Document();
                child.add(new StringField(BLOCK_KIND_FIELD, BLOCK_KIND_CHILD, Field.Store.NO));
                child.add(new Field(docIdField, entity.getId(), ftIRI));
                if (discriminatorValue != null) {
                    child.add(new StringField(discriminatorField, discriminatorValue, Field.Store.YES));
                }
                child.add(new StringField(NESTED_SCOPE_FIELD, nestedDef.getNestedName(), Field.Store.YES));

                for (Map.Entry<String, ShaclIndexMapping.FieldDef> entry : childFieldDefs.entrySet()) {
                    Object value = record.get(entry.getKey());
                    if (value == null) continue;
                    ShaclIndexMapping.FieldDef fieldDef = entry.getValue();
                    if (value instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> values = (List<Object>) value;
                        for (Object v : values) {
                            addFieldToDoc(child, fieldDef, v);
                        }
                    } else {
                        addFieldToDoc(child, fieldDef, value);
                    }
                }
                children.add(child);
            }
        }
        return children;
    }

    /**
     * Add hierarchical FacetField entries to a document for all configured hierarchies.
     * <p>
     * For each hierarchy, extracts the value at each level from the entity and builds
     * a Lucene {@link FacetField} with path components. If any level has no value,
     * the path is truncated at that point (partial paths are valid for counting).
     */
    private void addHierarchyFacetFields(Document doc, Entity entity,
            ShaclIndexMapping.IndexProfile profile) {
        for (ShaclIndexMapping.HierarchyDef hierarchy : profile.getHierarchies()) {
            addDirectHierarchyFacetFields(doc, entity, profile, hierarchy);
        }
        for (ShaclIndexMapping.NestedDef nestedDef : profile.getNestedDefs()) {
            for (ShaclIndexMapping.HierarchyDef hierarchy : nestedDef.getHierarchies()) {
                addNestedHierarchyFacetFields(doc, entity, profile, nestedDef, hierarchy);
            }
        }
    }

    private void addDirectHierarchyFacetFields(Document doc, Entity entity,
            ShaclIndexMapping.IndexProfile profile, ShaclIndexMapping.HierarchyDef hierarchy) {
        List<List<String>> levelValues = new ArrayList<>();
        for (ShaclIndexMapping.FieldDef levelField : hierarchy.getLevels()) {
            levelValues.add(asHierarchyFacetValues(entity.get(levelField.getFieldName()),
                profile, entity, hierarchy, levelField));
        }
        addFacetPaths(doc, hierarchy.getDimensionName(), levelValues, 0, new ArrayList<>());
    }

    private void addNestedHierarchyFacetFields(Document doc, Entity entity,
            ShaclIndexMapping.IndexProfile profile, ShaclIndexMapping.NestedDef nestedDef,
            ShaclIndexMapping.HierarchyDef hierarchy) {
        for (Entity.NestedRecord record : entity.getNestedRecords(nestedDef.getNestedName())) {
            List<List<String>> levelValues = new ArrayList<>();
            boolean hasAnyValue = false;
            for (ShaclIndexMapping.FieldDef levelField : hierarchy.getLevels()) {
                List<String> values = asHierarchyFacetValues(record.get(levelField.getFieldName()),
                    profile, entity, hierarchy, levelField);
                if (!values.isEmpty()) {
                    hasAnyValue = true;
                }
                levelValues.add(values);
            }
            if (hasAnyValue) {
                addFacetPaths(doc, hierarchy.getDimensionName(), levelValues, 0, new ArrayList<>());
            }
        }
    }

    private List<String> asStringValues(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            for (Object v : list) {
                if (v != null) {
                    values.add(v.toString());
                }
            }
        } else if (value != null) {
            values.add(value.toString());
        }
        return values;
    }

    private List<String> asHierarchyFacetValues(Object value, ShaclIndexMapping.IndexProfile profile,
            Entity entity, ShaclIndexMapping.HierarchyDef hierarchy, ShaclIndexMapping.FieldDef levelField) {
        List<String> values = new ArrayList<>();
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            for (Object rawValue : list) {
                addHierarchyFacetValue(values, rawValue, profile, entity, hierarchy, levelField);
            }
        } else {
            addHierarchyFacetValue(values, value, profile, entity, hierarchy, levelField);
        }
        return values;
    }

    private void addHierarchyFacetValue(List<String> values, Object rawValue,
            ShaclIndexMapping.IndexProfile profile, Entity entity,
            ShaclIndexMapping.HierarchyDef hierarchy, ShaclIndexMapping.FieldDef levelField) {
        if (rawValue == null) {
            return;
        }
        String stringValue = rawValue.toString();
        if (stringValue.isBlank()) {
            log.warn("Skipping blank hierarchy facet component: profile='{}', entity='{}', dimension='{}', field='{}', rawValue='{}'",
                profile.getShapeNode(), entity.getId(), hierarchy.getDimensionName(),
                levelField.getFieldName(), rawValue);
            return;
        }
        values.add(stringValue);
    }

    private void addFacetPaths(Document doc, String dim, List<List<String>> levelValues,
            int level, List<String> currentPath) {
        if (level >= levelValues.size()) {
            if (!currentPath.isEmpty()) {
                doc.add(new FacetField(dim, currentPath.toArray(new String[0])));
            }
            return;
        }
        List<String> values = levelValues.get(level);
        if (values.isEmpty()) {
            // No value at this level — emit partial path if we have any components
            if (!currentPath.isEmpty()) {
                doc.add(new FacetField(dim, currentPath.toArray(new String[0])));
            }
            return;
        }
        List<String> usableValues = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                usableValues.add(value);
            }
        }
        if (usableValues.isEmpty()) {
            if (!currentPath.isEmpty()) {
                doc.add(new FacetField(dim, currentPath.toArray(new String[0])));
            }
            return;
        }
        for (String val : usableValues) {
            currentPath.add(val);
            addFacetPaths(doc, dim, levelValues, level + 1, currentPath);
            currentPath.remove(currentPath.size() - 1);
        }
    }

    private void addFieldToDoc(Document doc, ShaclIndexMapping.FieldDef fieldDef, Object value) {
        if (value instanceof Node node) {
            addNodeFieldToDoc(doc, fieldDef, node);
            return;
        }

        String fieldName = fieldDef.getFieldName();
        Field.Store store =
            fieldDef.isStored() ? Field.Store.YES : Field.Store.NO;

        switch (fieldDef.getFieldType()) {
            case TEXT:
                if (fieldDef.isIndexed()) {
                    FieldType ft = fieldDef.isStored() ? TextField.TYPE_STORED : TextField.TYPE_NOT_STORED;
                    doc.add(new Field(fieldName, value.toString(), ft));
                } else if (fieldDef.isStored()) {
                    doc.add(new StoredField(fieldName, value.toString()));
                }
                break;

            case KEYWORD:
                String strVal = value.toString();
                // If a normalizer is declared, the indexed term + sort key use the
                // normalized bytes; the stored value and facet label stay raw (human-readable).
                Analyzer kwNorm = fieldDef.getNormalizer();
                BytesRef kwKey = kwNorm != null ? kwNorm.normalize(fieldName, strVal) : null;
                if (fieldDef.isIndexed()) {
                    if (kwKey != null) {
                        doc.add(new StringField(fieldName, kwKey.utf8ToString(), Field.Store.NO));
                        if (fieldDef.isStored()) {
                            doc.add(new StoredField(fieldName, strVal));
                        }
                    } else {
                        doc.add(new StringField(fieldName, strVal, store));
                    }
                } else if (fieldDef.isStored()) {
                    doc.add(new StoredField(fieldName, strVal));
                }
                if (fieldDef.isFacetable() && strVal != null && !strVal.isEmpty()) {
                    doc.add(new SortedSetDocValuesFacetField(fieldName, strVal));
                }
                if (fieldDef.isSortable()) {
                    // Normalized key (if a normalizer is declared) drives both the single- and
                    // multi-valued sort DocValues, so multi-valued sorting is case-normalized too.
                    BytesRef sortValue = kwKey != null ? kwKey : new BytesRef(strVal);
                    if (fieldDef.isMultiValued()) {
                        doc.add(new SortedSetDocValuesField(fieldName, sortValue));
                    } else {
                        doc.add(new SortedDocValuesField(fieldName, sortValue));
                    }
                }
                break;

            case INT: {
                int intVal = (value instanceof Number) ? ((Number) value).intValue() : Integer.parseInt(value.toString());
                if (fieldDef.isIndexed()) {
                    doc.add(new IntPoint(fieldName, intVal));
                }
                if (fieldDef.isStored()) {
                    doc.add(new StoredField(fieldName, intVal));
                }
                if (fieldDef.isFacetable() || fieldDef.isSortable()) {
                    doc.add(new SortedNumericDocValuesField(fieldName, intVal));
                }
                break;
            }

            case LONG: {
                long longVal = (value instanceof Number) ? ((Number) value).longValue() : Long.parseLong(value.toString());
                if (fieldDef.isIndexed()) {
                    doc.add(new LongPoint(fieldName, longVal));
                }
                if (fieldDef.isStored()) {
                    doc.add(new StoredField(fieldName, longVal));
                }
                if (fieldDef.isFacetable() || fieldDef.isSortable()) {
                    doc.add(new SortedNumericDocValuesField(fieldName, longVal));
                }
                break;
            }

            case DOUBLE: {
                double dblVal = (value instanceof Number) ? ((Number) value).doubleValue() : Double.parseDouble(value.toString());
                if (fieldDef.isIndexed()) {
                    doc.add(new DoublePoint(fieldName, dblVal));
                }
                if (fieldDef.isStored()) {
                    doc.add(new StoredField(fieldName, dblVal));
                }
                if (fieldDef.isFacetable() || fieldDef.isSortable()) {
                    doc.add(new SortedNumericDocValuesField(fieldName, NumericUtils.doubleToSortableLong(dblVal)));
                }
                break;
            }

            case TEMPORAL:
                if (fieldDef.isStored()) {
                    doc.add(new StoredField(fieldName, value.toString()));
                }
                break;

            case LATLON: {
                String wktValue = value.toString();
                List<IndexableField> spatialFields = parseWktToLuceneFields(fieldName, wktValue, fieldDef.isStored());
                for (IndexableField f : spatialFields) {
                    doc.add(f);
                }
                break;
            }
        }
    }

    private void addNodeFieldToDoc(Document doc, ShaclIndexMapping.FieldDef fieldDef, Node node) {
        if (!node.isLiteral()) {
            addFieldToDoc(doc, fieldDef, node.isURI() ? node.getURI() : node.toString());
            return;
        }

        String fieldName = fieldDef.getFieldName();
        String lexical = node.getLiteralLexicalForm();
        if (fieldDef.preservesLiteralMetadata()) {
            doc.add(new StoredField(LiteralFieldSupport.datatypeField(fieldName),
                Optional.ofNullable(node.getLiteralDatatypeURI()).orElse("")));
            doc.add(new StoredField(LiteralFieldSupport.langField(fieldName), node.getLiteralLanguage()));
        }

        switch (fieldDef.getFieldType()) {
            case TEXT -> {
                if (fieldDef.isIndexed()) {
                    FieldType ft = fieldDef.isStored() ? TextField.TYPE_STORED : TextField.TYPE_NOT_STORED;
                    doc.add(new Field(fieldName, lexical, ft));
                } else if (fieldDef.isStored()) {
                    doc.add(new StoredField(fieldName, lexical));
                }
            }
            case KEYWORD -> {
                Field.Store store = fieldDef.isStored() ? Field.Store.YES : Field.Store.NO;
                // Normalizer (if any) drives the indexed term + sort key; stored/facet stay raw.
                Analyzer kwNorm = fieldDef.getNormalizer();
                BytesRef kwKey = kwNorm != null ? kwNorm.normalize(fieldName, lexical) : null;
                if (fieldDef.isIndexed()) {
                    if (kwKey != null) {
                        doc.add(new StringField(fieldName, kwKey.utf8ToString(), Field.Store.NO));
                        if (fieldDef.isStored()) {
                            doc.add(new StoredField(fieldName, lexical));
                        }
                    } else {
                        doc.add(new StringField(fieldName, lexical, store));
                    }
                } else if (fieldDef.isStored()) {
                    doc.add(new StoredField(fieldName, lexical));
                }
                if (fieldDef.isFacetable() && !lexical.isEmpty()) {
                    doc.add(new SortedSetDocValuesFacetField(fieldName, lexical));
                }
                if (fieldDef.isSortable()) {
                    // Normalized key (if any) drives both single- and multi-valued sort DocValues.
                    BytesRef sortValue = kwKey != null ? kwKey : new BytesRef(lexical);
                    if (fieldDef.isMultiValued()) {
                        doc.add(new SortedSetDocValuesField(fieldName, sortValue));
                    } else {
                        doc.add(new SortedDocValuesField(fieldName, sortValue));
                    }
                }
            }
            case INT -> addIntegerLiteralField(doc, fieldDef, lexical);
            case LONG -> addLongLiteralField(doc, fieldDef, lexical);
            case DOUBLE -> addDoubleLiteralField(doc, fieldDef, lexical);
            case TEMPORAL -> addTemporalLiteralField(doc, fieldDef, lexical);
            case LATLON -> {
                List<IndexableField> spatialFields = parseWktToLuceneFields(fieldName, lexical, fieldDef.isStored());
                for (IndexableField f : spatialFields) {
                    doc.add(f);
                }
            }
        }
    }

    private void addIntegerLiteralField(Document doc, ShaclIndexMapping.FieldDef fieldDef, String lexical) {
        Integer intVal = parseInteger(lexical);
        if (intVal == null) {
            log.warn("Failed to parse INT literal for field '{}': {}", fieldDef.getFieldName(), lexical);
            if (fieldDef.isStored()) {
                doc.add(new StoredField(fieldDef.getFieldName(), lexical));
            }
            return;
        }
        if (fieldDef.isIndexed()) {
            doc.add(new IntPoint(fieldDef.getFieldName(), intVal));
        }
        if (fieldDef.isStored()) {
            doc.add(new StoredField(fieldDef.getFieldName(), lexical));
        }
        if (fieldDef.isFacetable() || fieldDef.isSortable()) {
            doc.add(new SortedNumericDocValuesField(fieldDef.getFieldName(), intVal));
        }
    }

    private void addLongLiteralField(Document doc, ShaclIndexMapping.FieldDef fieldDef, String lexical) {
        Long longVal = parseLong(lexical);
        if (longVal == null) {
            log.warn("Failed to parse LONG literal for field '{}': {}", fieldDef.getFieldName(), lexical);
            if (fieldDef.isStored()) {
                doc.add(new StoredField(fieldDef.getFieldName(), lexical));
            }
            return;
        }
        if (fieldDef.isIndexed()) {
            doc.add(new LongPoint(fieldDef.getFieldName(), longVal));
        }
        if (fieldDef.isStored()) {
            doc.add(new StoredField(fieldDef.getFieldName(), lexical));
        }
        if (fieldDef.isFacetable() || fieldDef.isSortable()) {
            doc.add(new SortedNumericDocValuesField(fieldDef.getFieldName(), longVal));
        }
    }

    private void addDoubleLiteralField(Document doc, ShaclIndexMapping.FieldDef fieldDef, String lexical) {
        Double dblVal = parseDouble(lexical);
        if (dblVal == null) {
            log.warn("Failed to parse DOUBLE literal for field '{}': {}", fieldDef.getFieldName(), lexical);
            if (fieldDef.isStored()) {
                doc.add(new StoredField(fieldDef.getFieldName(), lexical));
            }
            return;
        }
        if (fieldDef.isIndexed()) {
            doc.add(new DoublePoint(fieldDef.getFieldName(), dblVal));
        }
        if (fieldDef.isStored()) {
            doc.add(new StoredField(fieldDef.getFieldName(), lexical));
        }
        if (fieldDef.isFacetable() || fieldDef.isSortable()) {
            doc.add(new SortedNumericDocValuesField(fieldDef.getFieldName(), NumericUtils.doubleToSortableLong(dblVal)));
        }
    }

    private void addTemporalLiteralField(Document doc, ShaclIndexMapping.FieldDef fieldDef, String lexical) {
        if (fieldDef.isStored()) {
            doc.add(new StoredField(fieldDef.getFieldName(), lexical));
        }
        Long epoch = LiteralFieldSupport.toEpochMillis(fieldDef.getFieldType(), lexical);
        if (epoch == null) {
            log.warn("Failed to parse {} literal for field '{}': {}", fieldDef.getFieldType(), fieldDef.getFieldName(), lexical);
            return;
        }
        String epochFieldName = LiteralFieldSupport.epochField(fieldDef.getFieldName());
        if (fieldDef.isIndexed()) {
            doc.add(new LongPoint(epochFieldName, epoch));
        }
        if (fieldDef.isFacetable() || fieldDef.isSortable()) {
            doc.add(new SortedNumericDocValuesField(epochFieldName, epoch));
        }
    }

    /**
     * Parse a WKT literal into Lucene indexable fields for spatial queries.
     * <p>
     * Handles CRS detection and normalisation via {@link GeometryWrapper}:
     * <ul>
     *   <li>CRS84 (bare WKT, no prefix) and EPSG:4326 — axis order handled by GeometryWrapper</li>
     *   <li>GDA94/GDA2020 (EPSG:4283/7844) — treated as WGS84-equivalent</li>
     *   <li>Other CRS — transformed to WGS84 via {@link GeometryWrapper#convertSRS}</li>
     * </ul>
     * Supports Point, Polygon, and MultiPolygon geometries.
     */
    static List<IndexableField> parseWktToLuceneFields(String fieldName, String wktValue, boolean stored) {
        List<IndexableField> fields = new ArrayList<>();

        try {
            WKTReader reader = WKTReader.extract(wktValue);
            String srsUri = reader.getSrsURI();

            // Build a GeometryWrapper for CRS-aware coordinate handling
            GeometryWrapper wrapper = new GeometryWrapper(
                reader.getGeometry(), srsUri, WKTDatatype.URI,
                reader.getDimensionInfo());

            // Convert to WGS84 if not already in WGS84/CRS84
            if (!isWgs84OrCrs84(srsUri)) {
                wrapper = wrapper.convertSRS(SRS_URI.WGS84_CRS);
            }

            // getXYGeometry() normalises all CRSes to x=lon, y=lat (standard JTS convention)
            Geometry geom = wrapper.getXYGeometry();

            if (geom instanceof Point point) {
                double lat = point.getY();
                double lon = point.getX();
                Collections.addAll(fields, LatLonShape.createIndexableFields(fieldName, lat, lon));
            } else if (geom instanceof org.locationtech.jts.geom.Polygon jtsPoly) {
                org.apache.lucene.geo.Polygon lucenePoly = jtsPolygonToLucene(jtsPoly);
                Collections.addAll(fields, LatLonShape.createIndexableFields(fieldName, lucenePoly));
            } else if (geom instanceof MultiPolygon multiPolygon) {
                for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
                    org.locationtech.jts.geom.Polygon jtsPoly =
                        (org.locationtech.jts.geom.Polygon) multiPolygon.getGeometryN(i);
                    org.apache.lucene.geo.Polygon lucenePoly = jtsPolygonToLucene(jtsPoly);
                    Collections.addAll(fields, LatLonShape.createIndexableFields(fieldName, lucenePoly));
                }
            } else {
                log.warn("Unsupported geometry type for LATLON field '{}': {}", fieldName, geom.getGeometryType());
                return fields;
            }

            if (stored) {
                fields.add(new StoredField(fieldName, wktValue));
            }
        } catch (Exception e) {
            log.warn("Failed to parse WKT for field '{}': {} — {}", fieldName, wktValue, e.getMessage());
        }

        return fields;
    }

    private static boolean isWgs84OrCrs84(String srsUri) {
        return SRS_URI.DEFAULT_WKT_CRS84.equals(srsUri)
            || SRS_URI.WGS84_CRS.equals(srsUri)
            || "http://www.opengis.net/def/crs/EPSG/0/4283".equals(srsUri)
            || "http://www.opengis.net/def/crs/EPSG/0/7844".equals(srsUri);
    }

    /** Convert a JTS Polygon (x=lon, y=lat from getXYGeometry) to a Lucene Polygon. */
    private static org.apache.lucene.geo.Polygon jtsPolygonToLucene(org.locationtech.jts.geom.Polygon jtsPoly) {
        Coordinate[] shellCoords = jtsPoly.getExteriorRing().getCoordinates();
        double[] lats = new double[shellCoords.length];
        double[] lons = new double[shellCoords.length];
        for (int i = 0; i < shellCoords.length; i++) {
            lats[i] = shellCoords[i].y;  // y = lat
            lons[i] = shellCoords[i].x;  // x = lon
        }

        org.apache.lucene.geo.Polygon[] holes = new org.apache.lucene.geo.Polygon[jtsPoly.getNumInteriorRing()];
        for (int h = 0; h < jtsPoly.getNumInteriorRing(); h++) {
            Coordinate[] holeCoords = jtsPoly.getInteriorRingN(h).getCoordinates();
            double[] holeLats = new double[holeCoords.length];
            double[] holeLons = new double[holeCoords.length];
            for (int i = 0; i < holeCoords.length; i++) {
                holeLats[i] = holeCoords[i].y;
                holeLons[i] = holeCoords[i].x;
            }
            holes[h] = new org.apache.lucene.geo.Polygon(holeLats, holeLons);
        }

        return new org.apache.lucene.geo.Polygon(lats, lons, holes);
    }

    // ---- Entity update/delete ----

    public void updateEntityForProfile(Entity entity, ShaclIndexMapping.IndexProfile profile) {
        updateEntityForProfile(entity, profile, false);
    }

    /**
     * Update or insert a document for {@code entity} under {@code profile}.
     * <p>
     * When {@code skipDelete} is {@code true}, the pre-add {@code deleteDocuments()}
     * call is skipped — the caller is asserting that no document exists for this
     * entity (e.g. a fresh bulk load into an empty index after {@code tdb2.tdbloader}).
     * Setting this flag on a non-empty index will produce duplicate documents.
     */
    public void updateEntityForProfile(Entity entity, ShaclIndexMapping.IndexProfile profile,
                                       boolean skipDelete) {
        try {
            Document parentDoc = docFromMapping(entity, profile);
            List<Document> childDocs = childDocsFromMapping(entity, profile);

            // Lucene block convention: children first, parent last. Build facets per doc.
            List<Document> block = new ArrayList<>(childDocs.size() + 1);
            for (Document child : childDocs) {
                block.add(buildFacetsDoc(child));
            }
            block.add(buildFacetsDoc(parentDoc));

            if (!skipDelete) {
                String docIdField = profile.getDocIdField();
                String discriminatorField = profile.getDiscriminatorField();
                Node firstClass = profile.getTargetClasses().iterator().next();
                String localName = firstClass.getLocalName();

                // Every doc in the block carries (docIdField, entity.getId()) and the
                // discriminator, so this term-pair query hits the whole block as a unit.
                BooleanQuery deleteQuery = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term(docIdField, entity.getId())), BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term(discriminatorField, localName)), BooleanClause.Occur.MUST)
                    .build();

                getIndexWriter().deleteDocuments(deleteQuery);
            }
            // addDocuments preserves block ordering — required by Lucene block join.
            getIndexWriter().addDocuments(block);
            log.trace("updateEntityForProfile: {} profile={} skipDelete={} block={} (1 parent + {} children)",
                entity.getId(), profile.getShapeNode(), skipDelete, block.size(), childDocs.size());
        } catch (IOException e) {
            throw new TextIndexException("updateEntityForProfile", e);
        }
    }

    /** Apply the facet config to a doc (no-op when no facets are configured). */
    private Document buildFacetsDoc(Document doc) throws IOException {
        if (taxoWriter != null) {
            return facetsConfig.build(taxoWriter, doc);
        }
        if (!facetFields.isEmpty()) {
            return facetsConfig.build(doc);
        }
        return doc;
    }

    public void deleteEntityByUri(String entityUri) {
        try {
            Set<String> docIdFields = new HashSet<>();
            for (ShaclIndexMapping.IndexProfile profile : shaclMapping.getProfiles()) {
                docIdFields.add(profile.getDocIdField());
            }
            if (docIdFields.isEmpty()) {
                docIdFields.add(getDocDef().getEntityField());
            }
            for (String field : docIdFields) {
                getIndexWriter().deleteDocuments(new Term(field, entityUri));
            }
            log.trace("deleteEntityByUri: {}", entityUri);
        } catch (IOException e) {
            throw new TextIndexException("deleteEntityByUri", e);
        }
    }

    // ---- Faceting ----

    public boolean isFacetingEnabled() {
        return !facetFields.isEmpty();
    }

    public List<String> getFacetFields() {
        return Collections.unmodifiableList(facetFields);
    }

    private int facetSearchLimit() {
        return maxFacetHits > 0 ? maxFacetHits : Integer.MAX_VALUE;
    }

    public Map<String, List<FacetValue>> getFacetCounts(List<String> facetFieldsToQuery, int maxValues) {
        return getFacetCounts(null, null, FacetRequest.flatOnly(facetFieldsToQuery), maxValues, 0);
    }

    public Map<String, List<FacetValue>> getFacetCounts(String queryString, List<String> facetFieldsToQuery, int maxValues) {
        return getFacetCounts(queryString, null, FacetRequest.flatOnly(facetFieldsToQuery), maxValues, 0);
    }

    public Map<String, List<FacetValue>> getFacetCounts(String queryString, List<String> facetFieldsToQuery, int maxValues, int minCount) {
        return getFacetCounts(queryString, null, FacetRequest.flatOnly(facetFieldsToQuery), maxValues, minCount);
    }

    public Map<String, List<FacetValue>> getFacetCounts(String queryString, List<String> searchFields,
            List<String> facetFieldsToQuery, int maxValues) {
        return getFacetCounts(queryString, searchFields, FacetRequest.flatOnly(facetFieldsToQuery), maxValues, 0);
    }

    public Map<String, List<FacetValue>> getFacetCounts(String queryString, List<String> searchFields,
            List<String> facetFieldsToQuery, int maxValues, int minCount) {
        return getFacetCounts(queryString, searchFields, FacetRequest.flatOnly(facetFieldsToQuery), maxValues, minCount, null);
    }

    /**
     * Get facet counts, optionally with drill-down paths for hierarchical dimensions.
     * @param drillDown optional map of dimension name → path prefix for hierarchical drill-down
     */
    public Map<String, List<FacetValue>> getFacetCounts(String queryString, List<String> searchFields,
            List<String> facetFieldsToQuery, int maxValues, int minCount,
            Map<String, String[]> drillDown) {
        return getFacetCounts(queryString, searchFields, FacetRequest.flatOnly(facetFieldsToQuery), maxValues, minCount, drillDown);
    }

    public Map<String, List<FacetValue>> getFacetCounts(String queryString, List<String> searchFields,
            FacetRequest facetRequest, int maxValues, int minCount) {
        return getFacetCounts(queryString, searchFields, facetRequest, maxValues, minCount, null);
    }

    /**
     * Get flat, hierarchical, and range facet counts from a single FacetsCollector pass.
     */
    public Map<String, List<FacetValue>> getFacetCounts(String queryString, List<String> searchFields,
            FacetRequest facetRequest, int maxValues, int minCount,
            Map<String, String[]> drillDown) {
        List<String> facetFieldsToQuery = resolveFacetFieldNames(facetRequest.getFlatFields());

        if ((facetFieldsToQuery == null || facetFieldsToQuery.isEmpty()) && facetRequest.getRangeFields().isEmpty()) {
            return new HashMap<>();
        }

        // Fast path: no query, no drilldown — eligible for the open-facet cache.
        boolean openRequest = (queryString == null || queryString.isEmpty())
            && (drillDown == null || drillDown.isEmpty());
        OpenFacetCacheKey cacheKey = openRequest
            ? new OpenFacetCacheKey(facetRequest, maxValues, minCount) : null;
        if (cacheKey != null) {
            Map<String, List<FacetValue>> cached = openFacetCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        Map<String, List<FacetValue>> result = new HashMap<>();
        IndexSearcher searcher = acquireSearcher();
        try {
            IndexReader indexReader = searcher.getIndexReader();
            List<String> resolved = resolveSearchFields(searchFields);

            ShaclIndexMapping.FieldDef vectorField = vectorFieldIn(resolved);
            FacetsCollector fc = null;
            if (vectorField != null) {
                // Top-k-scoped counts, as in getFacetCountsWithCql.
                fc = new FacetsCollector();
                searcher.search(buildKnnQuery(vectorField, queryString, null), fc);
            } else if (queryString != null && !queryString.isEmpty()) {
                Query query = parseQueryForFields(queryString, resolved);
                fc = new FacetsCollector();
                searcher.search(filterToParents(query), fc);
            }

            Facets facets = createCombinedFacets(indexReader, fc, facetFieldsToQuery);

            collectFacetResults(facets, facetFieldsToQuery, maxValues, minCount, drillDown, result);
            collectRangeFacetResults(indexReader, fc, facetRequest.getRangeFields(), maxValues, minCount, result);
        } catch (IOException ex) {
            throw new TextIndexException("getFacetCounts", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(queryString, ex.getMessage());
        } finally {
            releaseSearcher(searcher);
        }

        if (cacheKey != null) {
            openFacetCache.putIfAbsent(cacheKey, freezeFacetResult(result));
        }
        return result;
    }

    /**
     * Wrap a facet result map in unmodifiable views so cache entries cannot be
     * mutated by callers iterating over them. Cheap — just two layers of wrapping,
     * no copying.
     */
    private static Map<String, List<FacetValue>> freezeFacetResult(Map<String, List<FacetValue>> result) {
        Map<String, List<FacetValue>> snapshot = new HashMap<>(result.size());
        for (Map.Entry<String, List<FacetValue>> e : result.entrySet()) {
            snapshot.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Collect facet results from a Facets object into the result map.
     * Handles both flat fields and hierarchical dimensions with optional drill-down.
     * <p>
     * Drill-down paths are provided via the {@code drillDown} map, keyed by dimension name.
     * When a CQL {@code =} filter references a hierarchy level field, the filter value
     * is extracted as a drill-down path component by {@link #extractHierarchyDrillDown}.
     */
    private void collectFacetResults(Facets facets, List<String> facetFieldsToQuery,
            int maxValues, int minCount, Map<String, String[]> drillDown,
            Map<String, List<FacetValue>> result) throws IOException {
        if (facets == null || facetFieldsToQuery == null || facetFieldsToQuery.isEmpty()) {
            return;
        }
        for (String fieldSpec : facetFieldsToQuery) {
            List<FacetValue> fieldFacets = new ArrayList<>();
            try {
                String dim = fieldSpec;
                String[] drillPath = (drillDown != null) ? drillDown.get(fieldSpec) : null;

                FacetResult facetResult;
                if (drillPath != null && drillPath.length > 0) {
                    facetResult = (maxValues <= 0)
                        ? facets.getAllChildren(dim, drillPath)
                        : facets.getTopChildren(maxValues, dim, drillPath);
                } else {
                    facetResult = (maxValues <= 0)
                        ? facets.getAllChildren(dim)
                        : facets.getTopChildren(maxValues, dim);
                }
                if (facetResult != null && facetResult.labelValues != null) {
                    for (LabelAndValue lv : facetResult.labelValues) {
                        if (minCount <= 0 || lv.value.longValue() >= minCount) {
                            fieldFacets.add(FacetValue.ofValue(lv.label, lv.value.longValue()));
                        }
                    }
                }
            } catch (IllegalArgumentException e) {
                log.debug("No facet data for field '{}': {}", fieldSpec, e.getMessage());
            }

            result.put(fieldSpec, fieldFacets);
        }
    }

    private void collectRangeFacetResults(IndexReader indexReader, FacetsCollector fc,
            List<FacetRequest.RangeFacetSpec> rangeSpecs, int maxValues, int minCount,
            Map<String, List<FacetValue>> result) throws IOException {
        if (rangeSpecs == null || rangeSpecs.isEmpty()) {
            return;
        }

        FacetsCollector collector = fc;
        if (collector == null) {
            IndexSearcher searcher = new IndexSearcher(indexReader);
            collector = new FacetsCollector();
            searcher.search(filterToParents(new MatchAllDocsQuery()), collector);
        }

        for (FacetRequest.RangeFacetSpec spec : rangeSpecs) {
            ShaclIndexMapping.FieldDef fieldDef = shaclMapping.findField(spec.fieldIri());
            if (fieldDef == null) {
                throw new TextIndexException("Unknown range facet field: " + spec.fieldIri());
            }
            String fieldName = fieldDef.isDateLike()
                ? LiteralFieldSupport.epochField(fieldDef.getFieldName())
                : fieldDef.getFieldName();
            List<FacetValue> buckets = switch (fieldDef.getFieldType()) {
                case INT -> collectIntRangeFacetResults(fieldName, spec.boundaries(), collector, maxValues, minCount);
                case LONG -> collectLongRangeFacetResults(fieldName, spec.boundaries(), collector, maxValues, minCount);
                case DOUBLE -> collectDoubleRangeFacetResults(fieldName, spec.boundaries(), collector, maxValues, minCount);
                case TEMPORAL -> collectDateRangeFacetResults(fieldDef, collector, spec.boundaries(), maxValues, minCount);
                default -> throw new TextIndexException("Range facet field '" + spec.fieldIri() + "' is not numeric");
            };
            result.put(fieldDef.getFieldName(), buckets);
        }
    }

    private List<FacetValue> collectIntRangeFacetResults(String fieldName, List<String> boundaries,
            FacetsCollector fc, int maxValues, int minCount) throws IOException {
        List<LongRange> ranges = buildLongRanges(boundaries, true);
        LongRangeFacetCounts counts = new LongRangeFacetCounts(
            fieldName, MultiLongValuesSource.fromIntField(fieldName), fc, ranges.toArray(LongRange[]::new));
        return extractRangeBuckets(counts.getAllChildren(fieldName), boundaries, maxValues, minCount);
    }

    private List<FacetValue> collectLongRangeFacetResults(String fieldName, List<String> boundaries,
            FacetsCollector fc, int maxValues, int minCount) throws IOException {
        List<LongRange> ranges = buildLongRanges(boundaries, false);
        LongRangeFacetCounts counts = new LongRangeFacetCounts(
            fieldName, MultiLongValuesSource.fromLongField(fieldName), fc, ranges.toArray(LongRange[]::new));
        return extractRangeBuckets(counts.getAllChildren(fieldName), boundaries, maxValues, minCount);
    }

    private List<FacetValue> collectDoubleRangeFacetResults(String fieldName, List<String> boundaries,
            FacetsCollector fc, int maxValues, int minCount) throws IOException {
        List<DoubleRange> ranges = buildDoubleRanges(boundaries);
        DoubleRangeFacetCounts counts = new DoubleRangeFacetCounts(
            fieldName,
            MultiDoubleValuesSource.fromField(fieldName, NumericUtils::sortableLongToDouble),
            fc,
            ranges.toArray(DoubleRange[]::new));
        return extractRangeBuckets(counts.getAllChildren(fieldName), boundaries, maxValues, minCount);
    }

    private List<FacetValue> collectDateRangeFacetResults(ShaclIndexMapping.FieldDef fieldDef,
            FacetsCollector fc, List<String> boundaries, int maxValues, int minCount) throws IOException {
        String fieldName = LiteralFieldSupport.epochField(fieldDef.getFieldName());
        List<LongRange> ranges = buildTemporalRanges(boundaries, fieldDef.getFieldType());
        LongRangeFacetCounts counts = new LongRangeFacetCounts(
            fieldName, MultiLongValuesSource.fromLongField(fieldName), fc, ranges.toArray(LongRange[]::new));
        return extractRangeBuckets(counts.getAllChildren(fieldName), boundaries, maxValues, minCount);
    }

    private static List<LongRange> buildLongRanges(List<String> boundaries, boolean intField) {
        List<LongRange> ranges = new ArrayList<>();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            String low = boundaries.get(i);
            String high = boundaries.get(i + 1);
            long min = low != null ? Long.parseLong(low) : (intField ? Integer.MIN_VALUE : Long.MIN_VALUE);
            long max = high != null ? Long.parseLong(high) : (intField ? Integer.MAX_VALUE : Long.MAX_VALUE);
            boolean minInclusive = true;
            boolean maxInclusive = high == null;
            ranges.add(new LongRange(Integer.toString(i), min, minInclusive, max, maxInclusive));
        }
        return ranges;
    }

    private static List<DoubleRange> buildDoubleRanges(List<String> boundaries) {
        List<DoubleRange> ranges = new ArrayList<>();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            String low = boundaries.get(i);
            String high = boundaries.get(i + 1);
            double min = low != null ? Double.parseDouble(low) : Double.NEGATIVE_INFINITY;
            double max = high != null ? Double.parseDouble(high) : Double.POSITIVE_INFINITY;
            boolean minInclusive = true;
            boolean maxInclusive = high == null;
            ranges.add(new DoubleRange(Integer.toString(i), min, minInclusive, max, maxInclusive));
        }
        return ranges;
    }

    private static List<LongRange> buildTemporalRanges(List<String> boundaries, ShaclIndexMapping.FieldType fieldType) {
        List<LongRange> ranges = new ArrayList<>();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            String low = boundaries.get(i);
            String high = boundaries.get(i + 1);
            long min = low != null ? requireEpoch(fieldType, low) : Long.MIN_VALUE;
            long max = high != null ? requireEpoch(fieldType, high) : Long.MAX_VALUE;
            boolean minInclusive = true;
            boolean maxInclusive = high == null;
            ranges.add(new LongRange(Integer.toString(i), min, minInclusive, max, maxInclusive));
        }
        return ranges;
    }

    private static List<FacetValue> extractRangeBuckets(FacetResult facetResult, List<String> boundaries,
            int maxValues, int minCount) {
        List<FacetValue> buckets = new ArrayList<>();
        if (facetResult == null || facetResult.labelValues == null) {
            return buckets;
        }

        int emitted = 0;
        for (LabelAndValue lv : facetResult.labelValues) {
            long count = lv.value.longValue();
            if (minCount > 0 && count < minCount) {
                continue;
            }
            int rangeIndex = Integer.parseInt(lv.label);
            buckets.add(FacetValue.ofRange(boundaries.get(rangeIndex), boundaries.get(rangeIndex + 1), count));
            emitted++;
            if (maxValues > 0 && emitted >= maxValues) {
                break;
            }
        }
        return buckets;
    }

    /**
     * Extract hierarchy drill-down paths from a CQL filter expression.
     * <p>
     * Scans for {@code =} comparisons on fields that belong to a hierarchy. For each such
     * comparison, the filter value becomes a drill-down path component on the hierarchy dimension.
     * This allows regular CQL filters to transparently trigger hierarchical facet drill-down.
     *
     * @param cqlFilter the CQL filter expression (may be null)
     * @param facetFieldsToQuery resolved facet field names (to identify which dimensions are requested)
     * @return a map of dimension name → drill-down path components, or null if no hierarchy filters found
     */
    Map<String, String[]> extractHierarchyDrillDown(CqlExpression cqlFilter, List<String> facetFieldsToQuery) {
        if (cqlFilter == null || shaclMapping == null || !shaclMapping.hasHierarchies()) {
            return null;
        }

        // Collect all = comparisons from the CQL expression
        List<CqlExpression.CqlComparison> comparisons = new ArrayList<>();
        collectEqualComparisons(cqlFilter, comparisons);
        if (comparisons.isEmpty()) return null;

        // For each requested facet dimension, check if any CQL = filters reference parent levels
        Map<String, String[]> drillDown = null;
        Set<String> requestedDims = new HashSet<>(facetFieldsToQuery);

        for (CqlExpression.CqlComparison cmp : comparisons) {
            if (!"=".equals(cmp.op())) continue;

            ShaclIndexMapping.HierarchyDef hier = shaclMapping.findHierarchyForField(cmp.property());
            if (hier == null) continue;

            String dimName = hier.getDimensionName();
            if (!requestedDims.contains(dimName)) continue;

            // This = filter is on a hierarchy level field, and the hierarchy dimension is being faceted.
            // Build the drill-down path: values for all levels up to and including this one.
            ShaclIndexMapping.FieldDef filterField = shaclMapping.findField(cmp.property());
            int levelIdx = hier.getLevelIndex(filterField);
            if (levelIdx < 0) continue;

            // Build path from level 0 to levelIdx using values from CQL = filters
            String[] path = new String[levelIdx + 1];
            boolean complete = true;
            for (int i = 0; i <= levelIdx; i++) {
                ShaclIndexMapping.FieldDef levelField = hier.getLevel(i);
                String levelValue = findEqualValue(comparisons, levelField);
                if (levelValue == null) {
                    complete = false;
                    break;
                }
                path[i] = levelValue;
            }

            if (complete) {
                if (drillDown == null) drillDown = new HashMap<>();
                drillDown.put(dimName, path);
            }
        }

        return drillDown;
    }

    /** Recursively collect all CqlComparison nodes with op "=" from a CQL expression tree. */
    private void collectEqualComparisons(CqlExpression expr, List<CqlExpression.CqlComparison> result) {
        switch (expr) {
            case CqlExpression.CqlComparison cmp -> {
                if ("=".equals(cmp.op())) result.add(cmp);
            }
            case CqlExpression.CqlAnd and -> {
                for (CqlExpression child : and.args()) collectEqualComparisons(child, result);
            }
            case CqlExpression.CqlOr or -> {
                for (CqlExpression child : or.args()) collectEqualComparisons(child, result);
            }
            case CqlExpression.CqlNot not -> collectEqualComparisons(not.arg(), result);
            default -> {} // Other expression types don't contain = comparisons
        }
    }

    /** Find the value of a CQL = comparison for a specific field. */
    private String findEqualValue(List<CqlExpression.CqlComparison> comparisons,
                                  ShaclIndexMapping.FieldDef field) {
        for (CqlExpression.CqlComparison cmp : comparisons) {
            ShaclIndexMapping.FieldDef fd = shaclMapping.findField(cmp.property());
            if (fd != null && fd.equals(field)) {
                return String.valueOf(cmp.value());
            }
        }
        return null;
    }

    public Map<String, List<FacetValue>> getFacetCountsWithFilters(
            String queryString, List<String> facetFieldsToQuery,
            Map<String, List<String>> filters, int maxValues) {
        return getFacetCountsWithFilters(queryString, null, facetFieldsToQuery, filters, maxValues, 0);
    }

    public Map<String, List<FacetValue>> getFacetCountsWithFilters(
            String queryString, List<String> searchFields, List<String> facetFieldsToQuery,
            Map<String, List<String>> filters, int maxValues, int minCount) {

        facetFieldsToQuery = resolveFacetFieldNames(facetFieldsToQuery);
        log.debug("getFacetCountsWithFilters: query='{}' filters={}", queryString, filters);
        Map<String, List<FacetValue>> result = new HashMap<>();

        if (facetFieldsToQuery == null || facetFieldsToQuery.isEmpty()) {
            return result;
        }

        IndexSearcher searcher = acquireSearcher();
        try {
            IndexReader indexReader = searcher.getIndexReader();
            List<String> resolved = resolveSearchFields(searchFields);

            BooleanQuery.Builder combined = new BooleanQuery.Builder();

            if (queryString != null && !queryString.isEmpty()) {
                combined.add(parseQueryForFields(queryString, resolved), BooleanClause.Occur.MUST);
            }

            if (filters != null) {
                for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
                    String field = entry.getKey();
                    List<String> values = entry.getValue();
                    if (values.size() == 1) {
                        combined.add(new TermQuery(new Term(field, values.get(0))),
                            BooleanClause.Occur.MUST);
                    } else {
                        List<BytesRef> valRefs = new ArrayList<>(values.size());
                        for (String v : values) {
                            valRefs.add(new BytesRef(v));
                        }
                        combined.add(new TermInSetQuery(field, valRefs),
                            BooleanClause.Occur.MUST);
                    }
                }
            }

            FacetsCollector fc = null;
            BooleanQuery bq = combined.build();
            if (!bq.clauses().isEmpty()) {
                fc = new FacetsCollector();
                searcher.search(filterToParents(bq), fc);
            }

            Facets facets = createCombinedFacets(indexReader, fc, facetFieldsToQuery);

            collectFacetResults(facets, facetFieldsToQuery, maxValues, minCount, null, result);
        } catch (IOException ex) {
            throw new TextIndexException("getFacetCountsWithFilters", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(queryString, ex.getMessage());
        } finally {
            releaseSearcher(searcher);
        }

        return result;
    }

    /**
     * Create a combined Facets object that handles both flat (SSDV) and hierarchical (taxonomy)
     * facet dimensions. Uses {@link MultiFacets} when hierarchies are configured.
     */
    private Facets createCombinedFacets(IndexReader indexReader, FacetsCollector fc,
                                        Collection<String> requestedFields) throws IOException {
        Facets flatFacets = null;
        if (hasSortedSetFacetFields()) {
            SortedSetDocValuesReaderState state = getOrBuildSsdvReaderState(indexReader);
            flatFacets = (fc != null)
                ? new SortedSetDocValuesFacetCounts(state, fc)
                : new SortedSetDocValuesFacetCounts(state);
        }

        if (taxoDirectory == null || hierarchyDimensions.isEmpty()) {
            return flatFacets;
        }

        // Skip taxonomy reader + FastTaxonomyFacetCounts construction unless the caller
        // actually asked for a hierarchical dimension. At scale this is the largest
        // per-call overhead, often unnecessary for plain SSDV facet requests.
        if (requestedFields == null || Collections.disjoint(requestedFields, hierarchyDimensions)) {
            return flatFacets;
        }

        // FastTaxonomyFacetCounts requires a non-null FacetsCollector.
        // When fc is null (no query), we need to collect all docs.
        if (fc == null) {
            IndexSearcher searcher = new IndexSearcher(indexReader);
            fc = new FacetsCollector();
            searcher.search(filterToParents(new MatchAllDocsQuery()), fc);
        }

        TaxonomyReader taxoReader = new DirectoryTaxonomyReader(taxoWriter);
        Facets taxoFacets = new FastTaxonomyFacetCounts(TAXO_INDEX_FIELD, taxoReader, facetsConfig, fc);

        Map<String, Facets> dimToFacets = new HashMap<>();
        for (String dim : hierarchyDimensions) {
            dimToFacets.put(dim, taxoFacets);
        }
        return flatFacets != null ? new MultiFacets(dimToFacets, flatFacets) : new MultiFacets(dimToFacets);
    }

    /**
     * Return the {@link SortedSetDocValuesReaderState} for the supplied reader,
     * building and caching one on first use. The cache entry is evicted automatically
     * when the reader is closed (via {@link IndexReader.ClosedListener}), which
     * happens when {@link SearcherManager#maybeRefresh()} retires the reader after
     * a commit.
     */
    private SortedSetDocValuesReaderState getOrBuildSsdvReaderState(IndexReader indexReader) throws IOException {
        IndexReader.CacheHelper helper = indexReader.getReaderCacheHelper();
        if (helper == null) {
            // Reader does not support caching (rare — e.g. some test wrappers).
            // Fall back to building each call to preserve correctness.
            return new DefaultSortedSetDocValuesReaderState(indexReader, facetsConfig);
        }
        IndexReader.CacheKey key = helper.getKey();
        SortedSetDocValuesReaderState cached = ssdvStateCache.get(key);
        if (cached != null) {
            return cached;
        }
        // Build outside the map to avoid holding the bin lock during a slow construction.
        SortedSetDocValuesReaderState fresh = new DefaultSortedSetDocValuesReaderState(indexReader, facetsConfig);
        SortedSetDocValuesReaderState raced = ssdvStateCache.putIfAbsent(key, fresh);
        if (raced != null) {
            // Another thread won the race; drop our build and use theirs.
            return raced;
        }
        // We populated the cache. Register a listener that evicts on reader close.
        helper.addClosedListener(ssdvStateCache::remove);
        return fresh;
    }

    /**
     * Check if a field name is a hierarchical dimension or maps to one.
     * Returns the dimension name if hierarchical, null otherwise.
     */
    String resolveHierarchyDimension(String fieldNameOrIRI) {
        if (hierarchyDimensions.contains(fieldNameOrIRI)) {
            return fieldNameOrIRI;
        }
        // Check if it's a field IRI that belongs to a hierarchy
        ShaclIndexMapping.HierarchyDef h = shaclMapping.findHierarchyForField(fieldNameOrIRI);
        if (h != null) {
            return h.getDimensionName();
        }
        return null;
    }

    // ---- Value and field node helpers ----

    private Node extractValueNode(Document doc, List<String> resolvedFields, Query valueQuery) {
        for (String fieldName : resolvedFields) {
            String[] storedValues = doc.getValues(fieldName);
            if (storedValues == null || storedValues.length == 0) continue;
            SelectedStoredValue storedValue = selectStoredValue(fieldName, storedValues, valueQuery);
            ShaclIndexMapping.FieldDef fd = shaclMapping.findFieldByName(fieldName);
            if (fd == null) continue;
            return LiteralFieldSupport.reconstructNode(fd, storedValue.value(),
                alignedStoredValue(doc, LiteralFieldSupport.datatypeField(fieldName), storedValue.index()),
                alignedStoredValue(doc, LiteralFieldSupport.langField(fieldName), storedValue.index()));
        }
        return null;
    }

    private SelectedStoredValue selectStoredValue(String fieldName, String[] storedValues, Query valueQuery) {
        if (storedValues.length == 1 || valueQuery == null) {
            return new SelectedStoredValue(0, storedValues[0]);
        }
        for (int i = 0; i < storedValues.length; i++) {
            String storedValue = storedValues[i];
            if (storedValue != null && matchesStoredValue(fieldName, storedValue, valueQuery)) {
                return new SelectedStoredValue(i, storedValue);
            }
        }
        return new SelectedStoredValue(0, storedValues[0]);
    }

    private boolean matchesStoredValue(String fieldName, String storedValue, Query valueQuery) {
        try (Directory dir = new ByteBuffersDirectory()) {
            IndexWriterConfig config = new IndexWriterConfig(getAnalyzer());
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                Document valueDoc = new Document();
                valueDoc.add(new Field(fieldName, storedValue, TextField.TYPE_STORED));
                writer.addDocument(valueDoc);
                writer.commit();
            }
            try (IndexReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                return searcher.count(valueQuery) > 0;
            }
        } catch (IOException ex) {
            throw new TextIndexException("matchesStoredValue", ex);
        }
    }

    static boolean looksLikeUri(String value) {
        return value.contains("://") || value.startsWith("urn:");
    }

    private record SelectedStoredValue(int index, String value) {}

    private static String alignedStoredValue(Document doc, String fieldName, int index) {
        String[] values = doc.getValues(fieldName);
        if (values == null || index < 0 || index >= values.length) {
            return null;
        }
        return values[index];
    }

    private static Integer parseInteger(String lexical) {
        try {
            return Integer.parseInt(lexical);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long parseLong(String lexical) {
        try {
            return Long.parseLong(lexical);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double parseDouble(String lexical) {
        try {
            return Double.parseDouble(lexical);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static long requireEpoch(ShaclIndexMapping.FieldType fieldType, String lexical) {
        Long epoch = LiteralFieldSupport.toEpochMillis(fieldType, lexical);
        if (epoch == null) {
            throw new TextIndexException("Failed to parse " + fieldType + " value: " + lexical);
        }
        return epoch;
    }

    private Node resolveFieldNode(List<String> resolvedFields) {
        if (resolvedFields.size() != 1) return null;
        ShaclIndexMapping.FieldDef fd = shaclMapping.findFieldByName(resolvedFields.get(0));
        return fd != null ? fd.getFieldIRI() : null;
    }

    // ---- Field-scoped query ----

    /**
     * Query using resolved field names (SHACL mode).
     * Replaces the inherited query(List&lt;Resource&gt;...) path for SHACL mode.
     */
    public List<TextHit> queryByFields(List<String> resolvedFields, String qs,
            String graphURI, String lang, int limit, String highlight) {
        IndexSearcher searcher = acquireSearcher();
        try {
            Query query;
            if (qs == null || qs.isEmpty()) {
                query = new MatchAllDocsQuery();
            } else {
                query = parseQueryForFields(qs, resolvedFields);
            }

            int maxHits = limit > 0 ? limit : MAX_N;
            TopDocs topDocs = searcher.search(filterToParents(query), maxHits);

            Node fieldNode = resolveFieldNode(resolvedFields);
            List<TextHit> results = new ArrayList<>();
            String entityField = getDocDef().getEntityField();
            StoredFields storedFields = searcher.storedFields();
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = storedFields.document(sd.doc);
                String uri = doc.get(entityField);
                if (uri != null) {
                    Node entityNode = TextQueryFuncs.stringToNode(uri);
                    Node valueNode = extractValueNode(doc, resolvedFields, query);
                    results.add(new TextHit(entityNode, sd.score, valueNode, null, fieldNode));
                }
            }
            return results;
        } catch (IOException ex) {
            throw new TextIndexException("queryByFields", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(qs, ex.getMessage());
        } finally {
            releaseSearcher(searcher);
        }
    }

    // ---- Filtered queries ----

    public List<TextHit> queryWithFilters(List<String> searchFields, String qs,
            Map<String, List<String>> filters, String graphURI, String lang,
            int limit, String highlight) {

        List<String> resolved = resolveSearchFields(searchFields);

        if (filters == null || filters.isEmpty()) {
            return queryByFields(resolved, qs, graphURI, lang, limit, highlight);
        }

        IndexSearcher searcher = acquireSearcher();
        try {
            BooleanQuery.Builder combined = new BooleanQuery.Builder();

            Query valueQuery = null;
            if (qs != null && !qs.isEmpty()) {
                valueQuery = parseQueryForFields(qs, resolved);
                combined.add(valueQuery, BooleanClause.Occur.MUST);
            }

            for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
                String field = entry.getKey();
                List<String> values = entry.getValue();
                if (values.size() == 1) {
                    combined.add(new TermQuery(new Term(field, values.get(0))),
                        BooleanClause.Occur.MUST);
                } else {
                    List<BytesRef> valRefs = new ArrayList<>(values.size());
                    for (String v : values) {
                        valRefs.add(new BytesRef(v));
                    }
                    combined.add(new TermInSetQuery(field, valRefs),
                        BooleanClause.Occur.MUST);
                }
            }

            int maxHits = limit > 0 ? limit : MAX_N;
            TopDocs topDocs = searcher.search(filterToParents(combined.build()), maxHits);

            Node fieldNode = resolveFieldNode(resolved);
            List<TextHit> results = new ArrayList<>();
            String entityField = getDocDef().getEntityField();
            StoredFields storedFields = searcher.storedFields();
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = storedFields.document(sd.doc);
                String uri = doc.get(entityField);
                if (uri != null) {
                    Node entityNode = TextQueryFuncs.stringToNode(uri);
                    Node valueNode = extractValueNode(doc, resolved, valueQuery);
                    results.add(new TextHit(entityNode, sd.score, valueNode, null, fieldNode));
                }
            }
            return results;
        } catch (IOException ex) {
            throw new TextIndexException("queryWithFilters", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(qs, ex.getMessage());
        } finally {
            releaseSearcher(searcher);
        }
    }

    public long countQuery(String queryString, List<String> searchFields, Map<String, List<String>> filters) {
        IndexSearcher searcher = acquireSearcher();
        try {
            IndexReader indexReader = searcher.getIndexReader();
            List<String> resolved = resolveSearchFields(searchFields);
            BooleanQuery.Builder bq = new BooleanQuery.Builder();
            if (queryString != null && !queryString.isEmpty()) {
                bq.add(parseQueryForFields(queryString, resolved), BooleanClause.Occur.MUST);
            }
            if (filters != null) {
                for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
                    String field = entry.getKey();
                    List<String> values = entry.getValue();
                    if (values.size() == 1) {
                        bq.add(new TermQuery(new Term(field, values.get(0))),
                            BooleanClause.Occur.MUST);
                    } else {
                        List<BytesRef> valRefs = new ArrayList<>(values.size());
                        for (String v : values) {
                            valRefs.add(new BytesRef(v));
                        }
                        bq.add(new TermInSetQuery(field, valRefs),
                            BooleanClause.Occur.MUST);
                    }
                }
            }
            BooleanQuery query = bq.build();
            if (query.clauses().isEmpty()) {
                // numDocs() counts parent + child docs; we want parents only.
                return searcher.count(PARENT_DOC_FILTER);
            }
            return searcher.count(filterToParents(query));
        } catch (IOException ex) {
            throw new TextIndexException("countQuery", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(queryString, ex.getMessage());
        } finally {
            releaseSearcher(searcher);
        }
    }

    // ---- CQL-based query methods ----

    public List<TextHit> queryWithCql(List<String> searchFields, String qs,
            CqlExpression cqlFilter, List<SortSpec> sortSpecs,
            String graphURI, String lang, int limit, String highlight) {

        List<String> resolved = resolveSearchFields(searchFields);

        if (cqlFilter == null && (sortSpecs == null || sortSpecs.isEmpty())) {
            return queryByFields(resolved, qs, graphURI, lang, limit, highlight);
        }

        IndexSearcher searcher = acquireSearcher();
        try {
            BooleanQuery.Builder combined = new BooleanQuery.Builder();

            Query valueQuery = null;
            if (qs != null && !qs.isEmpty()) {
                valueQuery = parseQueryForFields(qs, resolved);
                combined.add(valueQuery, BooleanClause.Occur.MUST);
            }

            if (cqlFilter != null) {
                CqlToLuceneCompiler compiler = new CqlToLuceneCompiler(shaclMapping, facetsConfig, getQueryAnalyzer());
                CqlToLuceneCompiler.CompileResult result = compiler.compile(cqlFilter);
                if (result.pushed() != null) {
                    combined.add(result.pushed(), BooleanClause.Occur.MUST);
                }
                if (result.residual() != null) {
                    log.warn("CQL filter has residual expressions that cannot be pushed to Lucene and will be ignored: {}",
                        result.residual().toCanonical());
                }
            }

            int maxHits = limit > 0 ? limit : MAX_N;
            Sort luceneSort = buildLuceneSort(sortSpecs);

            TopDocs topDocs;
            if (luceneSort != null) {
                topDocs = searcher.search(filterToParents(combined.build()), maxHits, luceneSort);
            } else {
                topDocs = searcher.search(filterToParents(combined.build()), maxHits);
            }

            Node fieldNode = resolveFieldNode(resolved);
            List<TextHit> results = new ArrayList<>();
            String entityField = getDocDef().getEntityField();
            StoredFields storedFields = searcher.storedFields();
            int idx = 0;
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = storedFields.document(sd.doc);
                String uri = doc.get(entityField);
                if (uri != null) {
                    Node entityNode = TextQueryFuncs.stringToNode(uri);
                    Node valueNode = extractValueNode(doc, resolved, valueQuery);
                    float score = luceneSort == null ? sd.score : rankScore(idx++);
                    results.add(new TextHit(entityNode, score, valueNode, null, fieldNode));
                }
            }
            return results;
        } catch (IOException ex) {
            throw new TextIndexException("queryWithCql", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(qs, ex.getMessage());
        } finally {
            releaseSearcher(searcher);
        }
    }

    public Map<String, List<FacetValue>> getFacetCountsWithCql(
            String queryString, List<String> searchFields, List<String> facetFieldsToQuery,
            CqlExpression cqlFilter, int maxValues, int minCount) {
        return getFacetCountsWithCql(queryString, searchFields, FacetRequest.flatOnly(facetFieldsToQuery),
            cqlFilter, maxValues, minCount);
    }

    public Map<String, List<FacetValue>> getFacetCountsWithCql(
            String queryString, List<String> searchFields, FacetRequest facetRequest,
            CqlExpression cqlFilter, int maxValues, int minCount) {

        List<String> facetFieldsToQuery = resolveFacetFieldNames(facetRequest.getFlatFields());

        if ((facetFieldsToQuery == null || facetFieldsToQuery.isEmpty()) && facetRequest.getRangeFields().isEmpty()) {
            return new HashMap<>();
        }

        // Fast path: no query, no filter — eligible for the open-facet cache.
        boolean openRequest = (queryString == null || queryString.isEmpty()) && cqlFilter == null;
        OpenFacetCacheKey cacheKey = openRequest
            ? new OpenFacetCacheKey(facetRequest, maxValues, minCount) : null;
        if (cacheKey != null) {
            Map<String, List<FacetValue>> cached = openFacetCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        Map<String, List<FacetValue>> result = new HashMap<>();
        IndexSearcher searcher = acquireSearcher();
        try {
            IndexReader indexReader = searcher.getIndexReader();
            List<String> resolved = resolveSearchFields(searchFields);

            Query pushedFilter = compileCqlFilter(cqlFilter);
            ShaclIndexMapping.FieldDef vectorField = vectorFieldIn(resolved);

            FacetsCollector fc = null;
            if (vectorField != null) {
                // Facet counts over a KNN query are counts within the top-k, not over the
                // whole matching set — a similarity search has no "all matching documents".
                // Using the same knnTopK as the hit path keeps the counts consistent with
                // the page the user is looking at. See docs/02-sparql-api.md.
                fc = new FacetsCollector();
                searcher.search(buildKnnQuery(vectorField, queryString, pushedFilter), fc);
            } else {
                BooleanQuery.Builder combined = new BooleanQuery.Builder();
                if (queryString != null && !queryString.isEmpty()) {
                    combined.add(parseQueryForFields(queryString, resolved), BooleanClause.Occur.MUST);
                }
                if (pushedFilter != null) {
                    combined.add(pushedFilter, BooleanClause.Occur.MUST);
                }
                BooleanQuery bq = combined.build();
                if (!bq.clauses().isEmpty()) {
                    fc = new FacetsCollector();
                    searcher.search(filterToParents(bq), fc);
                }
            }

            Facets facets = createCombinedFacets(indexReader, fc, facetFieldsToQuery);

            // Extract hierarchy drill-down paths from CQL = filters
            Map<String, String[]> drillDown = extractHierarchyDrillDown(cqlFilter, facetFieldsToQuery);

            collectFacetResults(facets, facetFieldsToQuery, maxValues, minCount, drillDown, result);
            collectRangeFacetResults(indexReader, fc, facetRequest.getRangeFields(), maxValues, minCount, result);
        } catch (IOException ex) {
            throw new TextIndexException("getFacetCountsWithCql", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(queryString, ex.getMessage());
        } finally {
            releaseSearcher(searcher);
        }

        if (cacheKey != null) {
            openFacetCache.putIfAbsent(cacheKey, freezeFacetResult(result));
        }
        return result;
    }

    public long countQueryWithCql(String queryString, List<String> searchFields, CqlExpression cqlFilter) {
        IndexSearcher searcher = acquireSearcher();
        try {
            IndexReader indexReader = searcher.getIndexReader();
            List<String> resolved = resolveSearchFields(searchFields);
            Query pushedFilter = compileCqlFilter(cqlFilter);
            ShaclIndexMapping.FieldDef vectorField = vectorFieldIn(resolved);
            if (vectorField != null) {
                // Bounded by knnTopK by construction, so this is "how many neighbours came
                // back", not "how many documents exist". ?totalHits therefore agrees with
                // the facet counts and the hit page, all three describing the same top-k.
                return searcher.count(buildKnnQuery(vectorField, queryString, pushedFilter));
            }

            BooleanQuery.Builder bq = new BooleanQuery.Builder();
            if (queryString != null && !queryString.isEmpty()) {
                bq.add(parseQueryForFields(queryString, resolved), BooleanClause.Occur.MUST);
            }
            if (pushedFilter != null) {
                bq.add(pushedFilter, BooleanClause.Occur.MUST);
            }
            BooleanQuery query = bq.build();
            if (query.clauses().isEmpty()) {
                // numDocs() counts parent + child docs; we want parents only.
                return searcher.count(PARENT_DOC_FILTER);
            }
            return searcher.count(filterToParents(query));
        } catch (IOException ex) {
            throw new TextIndexException("countQueryWithCql", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(queryString, ex.getMessage());
        } finally {
            releaseSearcher(searcher);
        }
    }

    // ---- Sort ----

    public Sort buildLuceneSort(List<SortSpec> sortSpecs) {
        if (sortSpecs == null || sortSpecs.isEmpty()) {
            return null;
        }

        SortField[] fields = new SortField[sortSpecs.size()];
        for (int i = 0; i < sortSpecs.size(); i++) {
            SortSpec spec = sortSpecs.get(i);

            // Resolve field identifier (IRI) to Lucene field name
            ShaclIndexMapping.FieldDef fd = shaclMapping.findField(spec.field());
            String luceneFieldName = fd != null ? LiteralFieldSupport.queryFieldName(fd) : spec.field();
            SortField.Type sortType = fd != null ? sortTypeFor(fd, spec.field()) : SortField.Type.STRING;

            if (spec.hasSelector()) {
                fields[i] = buildSelectorSortField(spec, fd, luceneFieldName, sortType);
            } else if (fd != null && isNumericField(fd)) {
                SortedNumericSelector.Type selector = spec.descending()
                    ? SortedNumericSelector.Type.MAX
                    : SortedNumericSelector.Type.MIN;
                fields[i] = new SortedNumericSortField(luceneFieldName, sortType, spec.descending(), selector);
            } else if (fd != null && fd.getFieldType() == ShaclIndexMapping.FieldType.KEYWORD
                    && fd.isMultiValued()) {
                SortedSetSelector.Type selector = spec.descending()
                    ? SortedSetSelector.Type.MAX
                    : SortedSetSelector.Type.MIN;
                fields[i] = new SortedSetSortField(luceneFieldName, spec.descending(), selector);
            } else {
                fields[i] = new SortField(luceneFieldName, sortType, spec.descending());
            }

            // A selector spec has already applied its (defaulted) missing placement.
            if (!spec.hasSelector()) {
                applyMissingPlacement(fields[i], spec.missing(), sortType, spec.descending());
            }
        }
        return new Sort(fields);
    }

    /** The Lucene sort value type for a mapped field, rejecting the types that have no order. */
    private static SortField.Type sortTypeFor(ShaclIndexMapping.FieldDef fd, String fieldIRI) {
        return switch (fd.getFieldType()) {
            case KEYWORD -> SortField.Type.STRING;
            case INT -> SortField.Type.INT;
            case LONG -> SortField.Type.LONG;
            case DOUBLE -> SortField.Type.DOUBLE;
            case TEMPORAL -> SortField.Type.LONG;
            case TEXT -> throw new TextIndexException(
                "Cannot sort on TEXT field '" + fieldIRI + "'. Use KEYWORD for sortable fields.");
            case LATLON -> throw new TextIndexException(
                "Cannot sort on LATLON field '" + fieldIRI + "'.");
            case VECTOR -> throw new TextIndexException(
                "Cannot sort on VECTOR field '" + fieldIRI + "'. A vector has no total order; "
                + "rank by similarity instead, by naming it in the luc:query fieldSpec.");
        };
    }

    /**
     * Build the {@link ToParentBlockJoinSortField} for a nested sort selector: order parent
     * docs by {@code spec.field()} taken from the child docs where the co-located
     * discriminator {@code spec.selectorField()} equals {@code spec.selectorValue()}, collapsing
     * MIN (ascending) / MAX (descending) when an entity has several matching children.
     * <p>
     * The selector chooses the sort key only — it never removes entities. Parents with no
     * matching child have no key and are placed by {@code spec.missing()} (default last).
     */
    private SortField buildSelectorSortField(SortSpec spec, ShaclIndexMapping.FieldDef fd,
            String luceneFieldName, SortField.Type sortType) {
        if (fd == null) {
            throw new TextIndexException("Unknown sort field: '" + spec.field() + "'. "
                + "Available fields: " + shaclMapping.getAllFieldNames());
        }
        ShaclIndexMapping.NestedDef scope = shaclMapping.findNestedDefForFieldName(fd.getFieldName());
        if (scope == null) {
            throw new TextIndexException("Sort selector requires a nested field: '" + spec.field()
                + "' is not part of an idx:nested block. A flat multivalued field keeps no "
                + "per-value discriminator to select on.");
        }
        if (!fd.isSortable()) {
            throw new TextIndexException("Sort field '" + spec.field()
                + "' is not idx:sortable, so it has no sort doc-values to select from.");
        }

        ShaclIndexMapping.FieldDef selectorFd = shaclMapping.findField(spec.selectorField());
        if (selectorFd == null) {
            throw new TextIndexException("Unknown sort selector field: '" + spec.selectorField()
                + "'. Available fields: " + shaclMapping.getAllFieldNames());
        }
        ShaclIndexMapping.NestedDef selectorScope =
            shaclMapping.findNestedDefForFieldName(selectorFd.getFieldName());
        if (selectorScope == null || !selectorScope.getNestedName().equals(scope.getNestedName())) {
            throw new TextIndexException("Sort selector field '" + spec.selectorField()
                + "' must belong to the same idx:nested block as sort field '" + spec.field()
                + "' (block '" + scope.getNestedName() + "'); the type/value correlation only "
                + "survives on a single child document.");
        }

        BitSetProducer childFilter = childSortFilter(scope, selectorFd, spec.selectorValue());
        SortField sortField = new ToParentBlockJoinSortField(
            luceneFieldName, sortType, spec.descending(), PARENTS_FILTER, childFilter);
        SortSpec.MissingPlacement missing =
            spec.missing() != null ? spec.missing() : SortSpec.MissingPlacement.LAST;
        applyMissingPlacement(sortField, missing, sortType, spec.descending());
        return sortField;
    }

    /**
     * {@link BitSetProducer} matching the child docs a sort selector may draw its key from:
     * children of the given nested scope whose discriminator field equals {@code value}.
     * <p>
     * Cached per selector — a {@link QueryBitSetProducer} memoises its per-segment bitset for
     * the life of a reader generation (Lucene evicts on reader close), so paging through a
     * large result set re-runs the child filter once rather than once per page. The key space
     * is caller-supplied (the selector's discriminator value), so the cache is bounded and
     * stops admitting entries once full rather than growing with query traffic.
     */
    private BitSetProducer childSortFilter(ShaclIndexMapping.NestedDef scope,
            ShaclIndexMapping.FieldDef selectorFd, String value) {
        String key = scope.getNestedName() + '\0' + selectorFd.getFieldName() + '\0' + value;
        BitSetProducer cached = childSortFilterCache.get(key);
        if (cached != null) {
            return cached;
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new TermQuery(new Term(NESTED_SCOPE_FIELD, scope.getNestedName())),
            BooleanClause.Occur.FILTER);
        builder.add(childDiscriminatorQuery(selectorFd, value), BooleanClause.Occur.MUST);
        BitSetProducer producer = new QueryBitSetProducer(builder.build());
        if (childSortFilterCache.size() >= MAX_CHILD_SORT_FILTERS) {
            return producer;
        }
        BitSetProducer existing = childSortFilterCache.putIfAbsent(key, producer);
        return existing != null ? existing : producer;
    }

    /** Exact-match query for a sort selector's discriminator value on a child doc. */
    private static Query childDiscriminatorQuery(ShaclIndexMapping.FieldDef selectorFd, String value) {
        String fieldName = selectorFd.getFieldName();
        if (!selectorFd.isIndexed()) {
            throw new TextIndexException("Sort selector field '" + fieldName
                + "' is not idx:indexed, so it cannot be matched.");
        }
        try {
            return switch (selectorFd.getFieldType()) {
                // KEYWORD with a normalizer indexes the normalized term, so normalize the
                // comparison value the same way (mirrors the CQL compiler's = handling).
                case KEYWORD, TEXT -> {
                    Analyzer norm = selectorFd.getNormalizer();
                    BytesRef term = norm != null ? norm.normalize(fieldName, value) : new BytesRef(value);
                    yield new TermQuery(new Term(fieldName, term));
                }
                case INT -> IntPoint.newExactQuery(fieldName, Integer.parseInt(value));
                case LONG -> LongPoint.newExactQuery(fieldName, Long.parseLong(value));
                case DOUBLE -> DoublePoint.newExactQuery(fieldName, Double.parseDouble(value));
                case TEMPORAL, LATLON, VECTOR -> throw new TextIndexException(
                    "Sort selector field '" + fieldName + "' has unsupported type "
                        + selectorFd.getFieldType() + "; use a KEYWORD discriminator.");
            };
        } catch (NumberFormatException ex) {
            throw new TextIndexException("Sort selector value '" + value + "' is not a valid "
                + selectorFd.getFieldType() + " for field '" + fieldName + "'");
        }
    }

    /**
     * Set the sort field's missing value so that {@code missing} means absolute placement in
     * the final result order.
     * <p>
     * Lucene applies the sort's reverse multiplier <em>outside</em> the comparator, so a
     * "missing sorts high" sentinel would surface first under a descending sort. Flipping the
     * sentinel by {@code reverse} keeps {@code first}/{@code last} meaning what the caller
     * sees. A no-op when {@code missing} is null, which leaves Lucene's own default.
     */
    private static void applyMissingPlacement(SortField sortField, SortSpec.MissingPlacement missing,
            SortField.Type sortType, boolean reverse) {
        if (missing == null) {
            return;
        }
        boolean sentinelHigh = (missing == SortSpec.MissingPlacement.LAST) != reverse;
        switch (sortType) {
            case STRING -> sortField.setMissingValue(
                sentinelHigh ? SortField.STRING_LAST : SortField.STRING_FIRST);
            case INT -> sortField.setMissingValue(
                sentinelHigh ? Integer.MAX_VALUE : Integer.MIN_VALUE);
            case LONG -> sortField.setMissingValue(
                sentinelHigh ? Long.MAX_VALUE : Long.MIN_VALUE);
            case DOUBLE -> sortField.setMissingValue(
                sentinelHigh ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
            default -> throw new TextIndexException(
                "Cannot apply 'missing' placement to sort type " + sortType);
        }
    }
}
