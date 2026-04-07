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
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.query.text.cql.CqlToLuceneCompiler;
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
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
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

    private final ShaclIndexMapping shaclMapping;
    private final List<String> facetFields;
    private final FacetsConfig facetsConfig;
    private final int maxFacetHits;

    // Taxonomy directory for hierarchical facets (null if no hierarchies configured)
    private final Directory taxoDirectory;
    private final DirectoryTaxonomyWriter taxoWriter;
    private final Set<String> hierarchyDimensions;

    public ShaclTextIndexLucene(Directory directory, TextIndexConfig config) {
        this(directory, null, config);
    }

    public ShaclTextIndexLucene(Directory directory, Directory taxoDirectory, TextIndexConfig config) {
        super(directory, config);
        this.shaclMapping = config.getShaclMapping();
        this.maxFacetHits = config.getMaxFacetHits();

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
    }

    @Override
    public void close() {
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
     * Unknown identifiers are passed through unchanged.
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
        for (String iri : fieldIRIs) {
            // Check if this field IRI belongs to a hierarchy — if so, resolve to the dimension name
            ShaclIndexMapping.HierarchyDef hier = shaclMapping.findHierarchyForField(iri);
            if (hier != null) {
                if (!resolved.contains(hier.getDimensionName())) {
                    resolved.add(hier.getDimensionName());
                }
            } else {
                ShaclIndexMapping.FieldDef fd = shaclMapping.findField(iri);
                resolved.add(fd != null ? fd.getFieldName() : iri);
            }
        }
        return resolved;
    }

    private static boolean isNumericField(ShaclIndexMapping.FieldDef fieldDef) {
        if (fieldDef == null) return false;
        return switch (fieldDef.getFieldType()) {
            case INT, LONG, DOUBLE -> true;
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
        if ("*".equals(queryString)) {
            return new MatchAllDocsQuery();
        }
        if (fields.size() == 1) {
            QueryParser qp = new QueryParser(fields.get(0), getQueryAnalyzer());
            qp.setAllowLeadingWildcard(true);
            return qp.parse(queryString);
        }
        String[] fieldArray = fields.toArray(new String[0]);
        MultiFieldQueryParser mqp = new MultiFieldQueryParser(fieldArray, getQueryAnalyzer());
        mqp.setAllowLeadingWildcard(true);
        return mqp.parse(queryString);
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
        if (resolvedFields.size() == 1) {
            String fieldName = resolvedFields.get(0);
            QueryParser qp = new QueryParser(fieldName, getQueryAnalyzer());
            qp.setAllowLeadingWildcard(true);
            Query fieldQuery = qp.parse(queryString);
            // Wrap with field IRI as the name
            ShaclIndexMapping.FieldDef fd = shaclMapping.findFieldByName(fieldName);
            String name = fd != null ? fd.getFieldIRI().getURI() : fieldName;
            return NamedMatches.wrapQuery(name, fieldQuery);
        }
        BooleanQuery.Builder bq = new BooleanQuery.Builder();
        for (String fieldName : resolvedFields) {
            QueryParser qp = new QueryParser(fieldName, getQueryAnalyzer());
            qp.setAllowLeadingWildcard(true);
            Query fieldQuery = qp.parse(queryString);
            ShaclIndexMapping.FieldDef fd = shaclMapping.findFieldByName(fieldName);
            String name = fd != null ? fd.getFieldIRI().getURI() : fieldName;
            Query named = NamedMatches.wrapQuery(name, fieldQuery);
            bq.add(named, BooleanClause.Occur.SHOULD);
        }
        return bq.build();
    }

    /**
     * Execute a search and return SearchHit objects with stable hit IDs.
     * The returned hits carry Lucene doc IDs for later field match extraction.
     */
    public List<SearchHit> searchWithHitIds(List<String> resolvedFields, String qs,
            CqlExpression cqlFilter, List<SortSpec> sortSpecs,
            String graphURI, String lang, int limit) {
        try (IndexReader indexReader = DirectoryReader.open(getDirectory())) {
            IndexSearcher searcher = new IndexSearcher(indexReader);

            Query textQuery;
            if (qs == null || qs.isEmpty()) {
                textQuery = new MatchAllDocsQuery();
            } else {
                textQuery = buildNamedQuery(qs, resolvedFields);
            }

            Query finalQuery;
            if (cqlFilter != null) {
                BooleanQuery.Builder combined = new BooleanQuery.Builder();
                combined.add(textQuery, BooleanClause.Occur.MUST);
                CqlToLuceneCompiler compiler = new CqlToLuceneCompiler(shaclMapping);
                CqlToLuceneCompiler.CompileResult result = compiler.compile(cqlFilter);
                if (result.pushed() != null) {
                    combined.add(result.pushed(), BooleanClause.Occur.MUST);
                }
                if (result.residual() != null) {
                    log.warn("CQL filter has residual expressions ignored: {}",
                        result.residual().toCanonical());
                }
                finalQuery = combined.build();
            } else {
                finalQuery = textQuery;
            }

            int maxHits = limit > 0 ? limit : MAX_N;
            Sort luceneSort = buildLuceneSort(sortSpecs);

            TopDocs topDocs;
            if (luceneSort != null) {
                topDocs = searcher.search(finalQuery, maxHits, luceneSort);
            } else {
                topDocs = searcher.search(finalQuery, maxHits);
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
                    results.add(new SearchHit(idx++, entityNode, sd.score, null, sd.doc));
                }
            }

            // Compute field matches using NamedMatches
            if (!results.isEmpty() && !(textQuery instanceof MatchAllDocsQuery)) {
                computeFieldMatches(searcher, textQuery, resolvedFields, results);
            }

            return results;
        } catch (IOException ex) {
            throw new TextIndexException("searchWithHitIds", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(qs, ex.getMessage());
        }
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
                        String selected = selectStoredValue(fd.getFieldName(), storedValues, textQuery);
                        valueNode = fieldValueToNode(fd, selected);
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
                                String selected = selectStoredValue(fieldName, storedValues, textQuery);
                                valueNode = fieldValueToNode(fd, selected);
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
     * Convert a stored field value to an appropriate RDF node based on field type.
     */
    private Node fieldValueToNode(ShaclIndexMapping.FieldDef fd, String value) {
        if (value == null || fd == null) return null;
        return switch (fd.getFieldType()) {
            case KEYWORD -> looksLikeUri(value)
                ? NodeFactory.createURI(value)
                : NodeFactory.createLiteralString(value);
            case TEXT    -> NodeFactory.createLiteralString(value);
            case INT     -> NodeFactory.createLiteralDT(value, XSDDatatype.XSDinteger);
            case LONG    -> NodeFactory.createLiteralDT(value, XSDDatatype.XSDlong);
            case DOUBLE  -> NodeFactory.createLiteralDT(value, XSDDatatype.XSDdouble);
            case LATLON  -> null;
        };
    }

    // ---- Document building ----

    protected Document docFromMapping(Entity entity, ShaclIndexMapping.IndexProfile profile) {
        Document doc = new Document();

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
                for (Object v : values) {
                    addFieldToDoc(doc, fieldDef, v);
                }
            } else {
                addFieldToDoc(doc, fieldDef, value);
            }
        }

        // Add hierarchical facet fields
        addHierarchyFacetFields(doc, entity, profile);

        return doc;
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
            // Collect values for each level
            List<List<String>> levelValues = new ArrayList<>();
            for (ShaclIndexMapping.FieldDef levelField : hierarchy.getLevels()) {
                Object value = entity.get(levelField.getFieldName());
                List<String> values = new ArrayList<>();
                if (value instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) value;
                    for (Object v : list) {
                        if (v != null) values.add(v.toString());
                    }
                } else if (value != null) {
                    values.add(value.toString());
                }
                levelValues.add(values);
            }

            // Build FacetField paths from the cartesian product of level values
            // For most cases this is a single path, but multi-valued levels create multiple paths
            addFacetPaths(doc, hierarchy.getDimensionName(), levelValues, 0, new ArrayList<>());
        }
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
        for (String val : values) {
            currentPath.add(val);
            addFacetPaths(doc, dim, levelValues, level + 1, currentPath);
            currentPath.remove(currentPath.size() - 1);
        }
    }

    private void addFieldToDoc(Document doc, ShaclIndexMapping.FieldDef fieldDef, Object value) {
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
                if (fieldDef.isIndexed()) {
                    doc.add(new StringField(fieldName, strVal, store));
                } else if (fieldDef.isStored()) {
                    doc.add(new StoredField(fieldName, strVal));
                }
                if (fieldDef.isFacetable() && strVal != null && !strVal.isEmpty()) {
                    doc.add(new SortedSetDocValuesFacetField(fieldName, strVal));
                }
                if (fieldDef.isSortable()) {
                    doc.add(new SortedDocValuesField(fieldName, new BytesRef(strVal)));
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
        try {
            Document doc = docFromMapping(entity, profile);
            Document indexDoc;
            if (taxoWriter != null) {
                indexDoc = facetsConfig.build(taxoWriter, doc);
            } else if (!facetFields.isEmpty()) {
                indexDoc = facetsConfig.build(doc);
            } else {
                indexDoc = doc;
            }

            String docIdField = profile.getDocIdField();
            String discriminatorField = profile.getDiscriminatorField();
            Node firstClass = profile.getTargetClasses().iterator().next();
            String localName = firstClass.getLocalName();

            BooleanQuery deleteQuery = new BooleanQuery.Builder()
                .add(new TermQuery(new Term(docIdField, entity.getId())), BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term(discriminatorField, localName)), BooleanClause.Occur.MUST)
                .build();

            getIndexWriter().deleteDocuments(deleteQuery);
            getIndexWriter().addDocument(indexDoc);
            log.trace("updateEntityForProfile: {} profile={}", entity.getId(), profile.getShapeNode());
        } catch (IOException e) {
            throw new TextIndexException("updateEntityForProfile", e);
        }
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
        Map<String, List<FacetValue>> result = new HashMap<>();

        if ((facetFieldsToQuery == null || facetFieldsToQuery.isEmpty()) && facetRequest.getRangeFields().isEmpty()) {
            return result;
        }

        try (IndexReader indexReader = DirectoryReader.open(getDirectory())) {
            IndexSearcher searcher = new IndexSearcher(indexReader);

            List<String> resolved = resolveSearchFields(searchFields);

            FacetsCollector fc = null;
            if (queryString != null && !queryString.isEmpty()) {
                Query query = parseQueryForFields(queryString, resolved);
                fc = new FacetsCollector();
                searcher.search(query, fc);
            }

            Facets facets = createCombinedFacets(indexReader, fc);

            collectFacetResults(facets, facetFieldsToQuery, maxValues, minCount, drillDown, result);
            collectRangeFacetResults(indexReader, fc, facetRequest.getRangeFields(), maxValues, minCount, result);
        } catch (IOException ex) {
            throw new TextIndexException("getFacetCounts", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(queryString, ex.getMessage());
        }

        return result;
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
            searcher.search(new MatchAllDocsQuery(), collector);
        }

        for (FacetRequest.RangeFacetSpec spec : rangeSpecs) {
            ShaclIndexMapping.FieldDef fieldDef = shaclMapping.findField(spec.fieldIri());
            if (fieldDef == null) {
                throw new TextIndexException("Unknown range facet field: " + spec.fieldIri());
            }
            String fieldName = fieldDef.getFieldName();
            List<FacetValue> buckets = switch (fieldDef.getFieldType()) {
                case INT -> collectIntRangeFacetResults(fieldName, spec.boundaries(), collector, maxValues, minCount);
                case LONG -> collectLongRangeFacetResults(fieldName, spec.boundaries(), collector, maxValues, minCount);
                case DOUBLE -> collectDoubleRangeFacetResults(fieldName, spec.boundaries(), collector, maxValues, minCount);
                default -> throw new TextIndexException("Range facet field '" + spec.fieldIri() + "' is not numeric");
            };
            result.put(fieldName, buckets);
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

        try (IndexReader indexReader = DirectoryReader.open(getDirectory())) {
            IndexSearcher searcher = new IndexSearcher(indexReader);

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
                searcher.search(bq, fc);
            }

            Facets facets = createCombinedFacets(indexReader, fc);

            collectFacetResults(facets, facetFieldsToQuery, maxValues, minCount, null, result);
        } catch (IOException ex) {
            throw new TextIndexException("getFacetCountsWithFilters", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(queryString, ex.getMessage());
        }

        return result;
    }

    /**
     * Create a combined Facets object that handles both flat (SSDV) and hierarchical (taxonomy)
     * facet dimensions. Uses {@link MultiFacets} when hierarchies are configured.
     */
    private Facets createCombinedFacets(IndexReader indexReader, FacetsCollector fc) throws IOException {
        Facets flatFacets = null;
        if (hasSortedSetFacetFields()) {
            SortedSetDocValuesReaderState state =
                new DefaultSortedSetDocValuesReaderState(indexReader, facetsConfig);
            flatFacets = (fc != null)
                ? new SortedSetDocValuesFacetCounts(state, fc)
                : new SortedSetDocValuesFacetCounts(state);
        }

        if (taxoDirectory == null || hierarchyDimensions.isEmpty()) {
            return flatFacets;
        }

        // FastTaxonomyFacetCounts requires a non-null FacetsCollector.
        // When fc is null (no query), we need to collect all docs.
        if (fc == null) {
            IndexSearcher searcher = new IndexSearcher(indexReader);
            fc = new FacetsCollector();
            searcher.search(new MatchAllDocsQuery(), fc);
        }

        TaxonomyReader taxoReader = new DirectoryTaxonomyReader(taxoDirectory);
        Facets taxoFacets = new FastTaxonomyFacetCounts(TAXO_INDEX_FIELD, taxoReader, facetsConfig, fc);

        Map<String, Facets> dimToFacets = new HashMap<>();
        for (String dim : hierarchyDimensions) {
            dimToFacets.put(dim, taxoFacets);
        }
        return flatFacets != null ? new MultiFacets(dimToFacets, flatFacets) : new MultiFacets(dimToFacets);
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
            String storedValue = selectStoredValue(fieldName, storedValues, valueQuery);
            ShaclIndexMapping.FieldDef fd = shaclMapping.findFieldByName(fieldName);
            if (fd == null) continue;
            return switch (fd.getFieldType()) {
                case KEYWORD -> looksLikeUri(storedValue)
                    ? NodeFactory.createURI(storedValue)
                    : NodeFactory.createLiteralString(storedValue);
                case TEXT    -> NodeFactory.createLiteralString(storedValue);
                case INT     -> NodeFactory.createLiteralDT(storedValue, XSDDatatype.XSDinteger);
                case LONG    -> NodeFactory.createLiteralDT(storedValue, XSDDatatype.XSDlong);
                case DOUBLE  -> NodeFactory.createLiteralDT(storedValue, XSDDatatype.XSDdouble);
                case LATLON  -> null;
            };
        }
        return null;
    }

    private String selectStoredValue(String fieldName, String[] storedValues, Query valueQuery) {
        if (storedValues.length == 1 || valueQuery == null) {
            return storedValues[0];
        }
        for (String storedValue : storedValues) {
            if (storedValue != null && matchesStoredValue(fieldName, storedValue, valueQuery)) {
                return storedValue;
            }
        }
        return storedValues[0];
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

    private static boolean looksLikeUri(String value) {
        return value.contains("://") || value.startsWith("urn:");
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
        try (IndexReader indexReader = DirectoryReader.open(getDirectory())) {
            IndexSearcher searcher = new IndexSearcher(indexReader);

            Query query;
            if (qs == null || qs.isEmpty()) {
                query = new MatchAllDocsQuery();
            } else {
                query = parseQueryForFields(qs, resolvedFields);
            }

            int maxHits = limit > 0 ? limit : MAX_N;
            TopDocs topDocs = searcher.search(query, maxHits);

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

        try (IndexReader indexReader = DirectoryReader.open(getDirectory())) {
            IndexSearcher searcher = new IndexSearcher(indexReader);

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
            TopDocs topDocs = searcher.search(combined.build(), maxHits);

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
        }
    }

    public long countQuery(String queryString, List<String> searchFields, Map<String, List<String>> filters) {
        try (IndexReader indexReader = DirectoryReader.open(getDirectory())) {
            IndexSearcher searcher = new IndexSearcher(indexReader);
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
                return indexReader.numDocs();
            }
            return searcher.count(query);
        } catch (IOException ex) {
            throw new TextIndexException("countQuery", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(queryString, ex.getMessage());
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

        try (IndexReader indexReader = DirectoryReader.open(getDirectory())) {
            IndexSearcher searcher = new IndexSearcher(indexReader);

            BooleanQuery.Builder combined = new BooleanQuery.Builder();

            Query valueQuery = null;
            if (qs != null && !qs.isEmpty()) {
                valueQuery = parseQueryForFields(qs, resolved);
                combined.add(valueQuery, BooleanClause.Occur.MUST);
            }

            if (cqlFilter != null) {
                CqlToLuceneCompiler compiler = new CqlToLuceneCompiler(shaclMapping);
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
                topDocs = searcher.search(combined.build(), maxHits, luceneSort);
            } else {
                topDocs = searcher.search(combined.build(), maxHits);
            }

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
            throw new TextIndexException("queryWithCql", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(qs, ex.getMessage());
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
        Map<String, List<FacetValue>> result = new HashMap<>();

        if ((facetFieldsToQuery == null || facetFieldsToQuery.isEmpty()) && facetRequest.getRangeFields().isEmpty()) {
            return result;
        }

        try (IndexReader indexReader = DirectoryReader.open(getDirectory())) {
            IndexSearcher searcher = new IndexSearcher(indexReader);

            List<String> resolved = resolveSearchFields(searchFields);

            BooleanQuery.Builder combined = new BooleanQuery.Builder();

            if (queryString != null && !queryString.isEmpty()) {
                combined.add(parseQueryForFields(queryString, resolved), BooleanClause.Occur.MUST);
            }

            if (cqlFilter != null) {
                CqlToLuceneCompiler compiler = new CqlToLuceneCompiler(shaclMapping);
                CqlToLuceneCompiler.CompileResult cr = compiler.compile(cqlFilter);
                if (cr.pushed() != null) {
                    combined.add(cr.pushed(), BooleanClause.Occur.MUST);
                }
                if (cr.residual() != null) {
                    log.warn("CQL filter has residual expressions that cannot be pushed to Lucene and will be ignored: {}",
                        cr.residual().toCanonical());
                }
            }

            FacetsCollector fc = null;
            BooleanQuery bq = combined.build();
            if (!bq.clauses().isEmpty()) {
                fc = new FacetsCollector();
                searcher.search(bq, fc);
            }

            Facets facets = createCombinedFacets(indexReader, fc);

            // Extract hierarchy drill-down paths from CQL = filters
            Map<String, String[]> drillDown = extractHierarchyDrillDown(cqlFilter, facetFieldsToQuery);

            collectFacetResults(facets, facetFieldsToQuery, maxValues, minCount, drillDown, result);
            collectRangeFacetResults(indexReader, fc, facetRequest.getRangeFields(), maxValues, minCount, result);
        } catch (IOException ex) {
            throw new TextIndexException("getFacetCountsWithCql", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(queryString, ex.getMessage());
        }

        return result;
    }

    public long countQueryWithCql(String queryString, List<String> searchFields, CqlExpression cqlFilter) {
        try (IndexReader indexReader = DirectoryReader.open(getDirectory())) {
            IndexSearcher searcher = new IndexSearcher(indexReader);
            List<String> resolved = resolveSearchFields(searchFields);
            BooleanQuery.Builder bq = new BooleanQuery.Builder();
            if (queryString != null && !queryString.isEmpty()) {
                bq.add(parseQueryForFields(queryString, resolved), BooleanClause.Occur.MUST);
            }
            if (cqlFilter != null) {
                CqlToLuceneCompiler compiler = new CqlToLuceneCompiler(shaclMapping);
                CqlToLuceneCompiler.CompileResult cr = compiler.compile(cqlFilter);
                if (cr.pushed() != null) {
                    bq.add(cr.pushed(), BooleanClause.Occur.MUST);
                }
                if (cr.residual() != null) {
                    log.warn("CQL filter has residual expressions that cannot be pushed to Lucene and will be ignored: {}",
                        cr.residual().toCanonical());
                }
            }
            BooleanQuery query = bq.build();
            if (query.clauses().isEmpty()) {
                return indexReader.numDocs();
            }
            return searcher.count(query);
        } catch (IOException ex) {
            throw new TextIndexException("countQueryWithCql", ex);
        } catch (ParseException ex) {
            throw new TextIndexParseException(queryString, ex.getMessage());
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
            SortField.Type sortType = SortField.Type.STRING; // default

            // Resolve field identifier (IRI) to Lucene field name
            ShaclIndexMapping.FieldDef fd = shaclMapping.findField(spec.field());
            String luceneFieldName = fd != null ? fd.getFieldName() : spec.field();
            if (fd != null) {
                sortType = switch (fd.getFieldType()) {
                    case KEYWORD -> SortField.Type.STRING;
                    case INT -> SortField.Type.INT;
                    case LONG -> SortField.Type.LONG;
                    case DOUBLE -> SortField.Type.DOUBLE;
                    case TEXT -> throw new TextIndexException(
                        "Cannot sort on TEXT field '" + spec.field() + "'. Use KEYWORD for sortable fields.");
                    case LATLON -> throw new TextIndexException(
                        "Cannot sort on LATLON field '" + spec.field() + "'.");
                };
            }

            if (fd != null && isNumericField(fd)) {
                SortedNumericSelector.Type selector = spec.descending()
                    ? SortedNumericSelector.Type.MAX
                    : SortedNumericSelector.Type.MIN;
                fields[i] = new SortedNumericSortField(luceneFieldName, sortType, spec.descending(), selector);
            } else {
                fields[i] = new SortField(luceneFieldName, sortType, spec.descending());
            }
        }
        return new Sort(fields);
    }
}
