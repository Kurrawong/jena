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

import java.util.*;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.text.analyzer.EdgeNGramAnalyzer;
import org.apache.jena.query.text.analyzer.LowerCaseKeywordAnalyzer;
import org.apache.jena.sparql.path.Path;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

/**
 * Parsed representation of SHACL-driven index shapes.
 * <p>
 * Canonical fields carry Lucene/index metadata only. Field extraction is modeled
 * separately as root or nested field occurrences.
 */
public class ShaclIndexMapping {

    public enum FieldType {
        TEXT, KEYWORD, INT, LONG, DOUBLE, TEMPORAL, LATLON
    }

    public enum NodeKindConstraint {
        IRI,
        BLANK_NODE,
        LITERAL,
        BLANK_NODE_OR_IRI,
        BLANK_NODE_OR_LITERAL,
        IRI_OR_LITERAL;

        public boolean matches(Node node) {
            return switch (this) {
                case IRI -> node != null && node.isURI();
                case BLANK_NODE -> node != null && node.isBlank();
                case LITERAL -> node != null && node.isLiteral();
                case BLANK_NODE_OR_IRI -> node != null && (node.isBlank() || node.isURI());
                case BLANK_NODE_OR_LITERAL -> node != null && (node.isBlank() || node.isLiteral());
                case IRI_OR_LITERAL -> node != null && (node.isURI() || node.isLiteral());
            };
        }
    }

    private static final String FIELD_IRI_PREFIX = "urn:jena:lucene:field#";

    public static class FieldDef {
        private final Node fieldIRI;
        private final String fieldName;
        private final FieldType fieldType;
        private final Analyzer analyzer;
        private final Analyzer queryAnalyzer;
        private final boolean stored;
        private final boolean indexed;
        private final boolean facetable;
        private final boolean sortable;
        private final boolean multiValued;
        private final boolean defaultSearch;
        private final boolean storeLiteralMetadata;
        /** Optional analyzer applied (via {@link Analyzer#normalize}) to a KEYWORD field's
         *  indexed term and sort key. Null = raw value (unchanged behaviour). */
        private final Analyzer normalizer;

        public FieldDef(String fieldName, FieldType fieldType, Analyzer analyzer,
                        boolean stored, boolean indexed, boolean facetable,
                        boolean sortable, boolean multiValued, boolean defaultSearch) {
            this(fieldName, fieldType, analyzer, null, stored, indexed, facetable,
                sortable, multiValued, defaultSearch, false, null);
        }

        public FieldDef(String fieldName, FieldType fieldType, Analyzer analyzer,
                        boolean stored, boolean indexed, boolean facetable,
                        boolean sortable, boolean multiValued, boolean defaultSearch,
                        Node fieldIRI) {
            this(fieldName, fieldType, analyzer, null, stored, indexed, facetable,
                sortable, multiValued, defaultSearch, false, fieldIRI);
        }

        public FieldDef(String fieldName, FieldType fieldType, Analyzer analyzer,
                        Analyzer queryAnalyzer,
                        boolean stored, boolean indexed, boolean facetable,
                        boolean sortable, boolean multiValued, boolean defaultSearch) {
            this(fieldName, fieldType, analyzer, queryAnalyzer, stored, indexed, facetable,
                sortable, multiValued, defaultSearch, false, null);
        }

        public FieldDef(String fieldName, FieldType fieldType, Analyzer analyzer,
                        Analyzer queryAnalyzer,
                        boolean stored, boolean indexed, boolean facetable,
                        boolean sortable, boolean multiValued, boolean defaultSearch,
                        Node fieldIRI) {
            this(fieldName, fieldType, analyzer, queryAnalyzer, stored, indexed, facetable,
                sortable, multiValued, defaultSearch, false, fieldIRI);
        }

        public FieldDef(String fieldName, FieldType fieldType, Analyzer analyzer,
                        boolean stored, boolean indexed, boolean facetable,
                        boolean sortable, boolean multiValued, boolean defaultSearch,
                        boolean storeLiteralMetadata) {
            this(fieldName, fieldType, analyzer, null, stored, indexed, facetable,
                sortable, multiValued, defaultSearch, storeLiteralMetadata, null);
        }

        public FieldDef(String fieldName, FieldType fieldType, Analyzer analyzer,
                        Analyzer queryAnalyzer,
                        boolean stored, boolean indexed, boolean facetable,
                        boolean sortable, boolean multiValued, boolean defaultSearch,
                        boolean storeLiteralMetadata) {
            this(fieldName, fieldType, analyzer, queryAnalyzer, stored, indexed, facetable,
                sortable, multiValued, defaultSearch, storeLiteralMetadata, null);
        }

        public FieldDef(String fieldName, FieldType fieldType, Analyzer analyzer,
                        boolean stored, boolean indexed, boolean facetable,
                        boolean sortable, boolean multiValued, boolean defaultSearch,
                        boolean storeLiteralMetadata, Node fieldIRI) {
            this(fieldName, fieldType, analyzer, null, stored, indexed, facetable,
                sortable, multiValued, defaultSearch, storeLiteralMetadata, fieldIRI);
        }

        public FieldDef(String fieldName, FieldType fieldType, Analyzer analyzer,
                        Analyzer queryAnalyzer,
                        boolean stored, boolean indexed, boolean facetable,
                        boolean sortable, boolean multiValued, boolean defaultSearch,
                        boolean storeLiteralMetadata, Node fieldIRI) {
            this(fieldName, fieldType, analyzer, queryAnalyzer, stored, indexed, facetable,
                sortable, multiValued, defaultSearch, storeLiteralMetadata, fieldIRI, null);
        }

        /** Canonical (widest) constructor. {@code normalizer} is the KEYWORD normalizer
         *  (null for all other cases and for the many delegating constructors above). */
        public FieldDef(String fieldName, FieldType fieldType, Analyzer analyzer,
                        Analyzer queryAnalyzer,
                        boolean stored, boolean indexed, boolean facetable,
                        boolean sortable, boolean multiValued, boolean defaultSearch,
                        boolean storeLiteralMetadata, Node fieldIRI, Analyzer normalizer) {
            this.fieldName = Objects.requireNonNull(fieldName);
            this.fieldType = fieldType != null ? fieldType : FieldType.TEXT;
            this.analyzer = analyzer;
            this.queryAnalyzer = queryAnalyzer != null ? queryAnalyzer : pairedQueryAnalyzer(analyzer);
            this.stored = stored;
            this.indexed = indexed;
            this.facetable = facetable;
            this.sortable = sortable;
            this.multiValued = multiValued;
            this.defaultSearch = defaultSearch;
            this.storeLiteralMetadata = storeLiteralMetadata;
            this.normalizer = normalizer;
            this.fieldIRI = fieldIRI != null ? fieldIRI : NodeFactory.createURI(FIELD_IRI_PREFIX + fieldName);
        }

        /**
         * The query-side analyzer implied by an index analyzer when the field declares no
         * {@code idx:queryAnalyzer}. Only edge-n-gram fields need this: re-running the
         * n-gram analyzer over the query turns every input into a pile of prefixes and
         * matches far too much, so the sensible partner is supplied instead — a
         * whole-value lowercaser for whole-value n-grams, and a word tokenizer for
         * per-word n-grams. Every other analyzer is its own correct query-side partner.
         */
        private static Analyzer pairedQueryAnalyzer(Analyzer indexAnalyzer) {
            if (indexAnalyzer instanceof EdgeNGramAnalyzer ngram) {
                return ngram.isTokenized()
                    ? new StandardAnalyzer()
                    : new LowerCaseKeywordAnalyzer();
            }
            return null;
        }

        public Node getFieldIRI()            { return fieldIRI; }
        public String getFieldName()         { return fieldName; }
        public FieldType getFieldType()      { return fieldType; }
        public Analyzer getAnalyzer()        { return analyzer; }
        public Analyzer getQueryAnalyzer()   { return queryAnalyzer; }
        public Analyzer getNormalizer()      { return normalizer; }
        public boolean isStored()            { return stored; }
        public boolean isIndexed()           { return indexed; }
        public boolean isFacetable()         { return facetable; }
        public boolean isSortable()          { return sortable; }
        public boolean isMultiValued()       { return multiValued; }
        public boolean isDefaultSearch()     { return defaultSearch; }
        public boolean isStoreLiteralMetadata() { return storeLiteralMetadata; }
        public boolean isTemporal()          { return fieldType == FieldType.TEMPORAL; }
        /** @deprecated use {@link #isTemporal()}. Kept for backwards compat with PR-merge windows. */
        @Deprecated public boolean isDateLike() { return isTemporal(); }
        public boolean preservesLiteralMetadata() { return storeLiteralMetadata || isTemporal(); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FieldDef fieldDef)) return false;
            return fieldIRI.equals(fieldDef.fieldIRI);
        }

        @Override
        public int hashCode() {
            return fieldIRI.hashCode();
        }

        @Override
        public String toString() {
            return fieldName + "(" + fieldType + ")";
        }
    }

    public static class HierarchyDef {
        private final String dimensionName;
        private final List<FieldDef> levels;

        public HierarchyDef(String dimensionName, List<FieldDef> levels) {
            this.dimensionName = Objects.requireNonNull(dimensionName);
            if (levels == null || levels.size() < 2) {
                throw new IllegalArgumentException("Hierarchy must have at least 2 levels");
            }
            this.levels = Collections.unmodifiableList(new ArrayList<>(levels));
        }

        public String getDimensionName()     { return dimensionName; }
        public List<FieldDef> getLevels()    { return levels; }
        public int getDepth()                { return levels.size(); }
        public FieldDef getLevel(int index)  { return levels.get(index); }
        public boolean containsField(FieldDef field) { return levels.contains(field); }
        public int getLevelIndex(FieldDef field)     { return levels.indexOf(field); }

        @Override
        public String toString() {
            return "HierarchyDef(" + dimensionName + ", levels=" + levels + ")";
        }
    }

    /**
     * One simple predicate step.
     * A forward step follows {@code subject --predicate--> object}.
     * An inverse step follows {@code object --predicate--> subject}.
     */
    public static class JoinStep {
        private final Node predicate;
        private final boolean inverse;

        public JoinStep(Node predicate, boolean inverse) {
            this.predicate = Objects.requireNonNull(predicate);
            this.inverse = inverse;
        }

        public Node getPredicate()          { return predicate; }
        public boolean isInverse()          { return inverse; }

        @Override
        public String toString() {
            return inverse ? "^" + predicate : predicate.toString();
        }
    }

    public static class FieldOccurrence {
        private final FieldDef field;
        private final Path path;
        private final List<List<JoinStep>> pathVariants;
        private final Set<Node> predicates;
        private final Node requiredClass;
        private final NodeKindConstraint nodeKindConstraint;
        private final Node datatype;
        private final String nestedName;

        public FieldOccurrence(FieldDef field, Path path, List<List<JoinStep>> pathVariants,
                               Set<Node> predicates, Node requiredClass,
                               NodeKindConstraint nodeKindConstraint, Node datatype,
                               String nestedName) {
            this.field = Objects.requireNonNull(field);
            this.path = Objects.requireNonNull(path);
            this.pathVariants = pathVariants != null
                ? deepUnmodifiable(pathVariants)
                : Collections.emptyList();
            this.predicates = predicates != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(predicates))
                : Collections.emptySet();
            this.requiredClass = requiredClass;
            this.nodeKindConstraint = nodeKindConstraint;
            this.datatype = datatype;
            this.nestedName = nestedName;
            if (this.pathVariants.isEmpty()) {
                throw new IllegalArgumentException("FieldOccurrence must contain at least one predicate path variant");
            }
        }

        private FieldOccurrence(FieldDef field, Node requiredClass,
                                NodeKindConstraint nodeKindConstraint, Node datatype,
                                String nestedName) {
            this.field = Objects.requireNonNull(field);
            this.path = null;
            // One variant of zero steps: the identity walk. Not the empty list, which
            // would mean "no way to reach this field at all".
            this.pathVariants = List.of(List.of());
            this.predicates = Collections.emptySet();
            this.requiredClass = requiredClass;
            this.nodeKindConstraint = nodeKindConstraint;
            this.datatype = datatype;
            this.nestedName = nestedName;
        }

        /**
         * An occurrence that binds the focus node itself rather than a path from it —
         * the entity at root scope, the child node inside an {@code idx:nested} block.
         * Written {@code idx:self true} in place of {@code sh:path}.
         */
        public static FieldOccurrence self(FieldDef field, Node requiredClass,
                                           NodeKindConstraint nodeKindConstraint, Node datatype,
                                           String nestedName) {
            return new FieldOccurrence(field, requiredClass, nodeKindConstraint, datatype, nestedName);
        }

        public FieldDef getField()                { return field; }
        /** The path from the focus node, or null when this occurrence is self-bound. */
        public Path getPath()                     { return path; }
        /** True when this occurrence binds the focus node itself ({@code idx:self}). */
        public boolean isSelf()                   { return path == null; }
        public List<List<JoinStep>> getPathVariants() { return pathVariants; }
        public Set<Node> getPredicates()          { return predicates; }
        public Node getRequiredClass()            { return requiredClass; }
        public NodeKindConstraint getNodeKindConstraint() { return nodeKindConstraint; }
        public Node getDatatype()                 { return datatype; }
        public String getNestedName()             { return nestedName; }
        public boolean isNestedScoped()           { return nestedName != null; }
        public boolean isRootScoped()             { return nestedName == null; }
        public boolean requiresTypeConstraint()   { return requiredClass != null; }

        @Override
        public String toString() {
            String source = isSelf() ? "self" : String.valueOf(path);
            return nestedName == null
                ? field.getFieldName() + " <- " + source
                : field.getFieldName() + "@" + nestedName + " <- " + source;
        }

        private static List<List<JoinStep>> deepUnmodifiable(List<List<JoinStep>> variants) {
            List<List<JoinStep>> copy = new ArrayList<>(variants.size());
            for (List<JoinStep> variant : variants) {
                copy.add(Collections.unmodifiableList(new ArrayList<>(variant)));
            }
            return Collections.unmodifiableList(copy);
        }
    }

    /**
     * Binding of one external source column to a canonical field. The column is
     * identified by name (header row present) or by zero-based position
     * ({@code idx:headerless}); exactly one of the two is set.
     */
    public static class ColumnBinding {
        private final String columnName;
        private final int columnIndex;
        private final FieldDef field;

        public ColumnBinding(String columnName, int columnIndex, FieldDef field) {
            this.field = Objects.requireNonNull(field, "field");
            this.columnName = columnName;
            this.columnIndex = columnIndex;
            if ((columnName == null) == (columnIndex < 0)) {
                throw new IllegalArgumentException(
                    "Column binding for field " + field.getFieldName()
                    + " must set exactly one of idx:columnName or idx:columnIndex");
            }
        }

        public String getColumnName()   { return columnName; }
        public int getColumnIndex()     { return columnIndex; }
        public FieldDef getField()      { return field; }
        public boolean isPositional()   { return columnName == null; }

        @Override
        public String toString() {
            return (isPositional() ? "[" + columnIndex + "]" : columnName) + " -> " + field.getFieldName();
        }
    }

    /** How an unparseable cell is handled. */
    public enum ErrorPolicy { SKIP, FAIL }

    /** Input formats an {@code idx:externalSource} can read. */
    public enum ExternalFormat { CSV, TSV }

    /**
     * A tabular source supplying nested child records for entities that already exist
     * in the graph, joined on the entity IRI. See
     * {@code docs/2026-07-27_external_content_indexing_design.md}.
     * <p>
     * Values are parsed as their declared {@link FieldType} and never transformed —
     * the indexer parses, it never computes. The single concession to derivation is
     * {@link #getSubjectPrefix()}, a plain string concatenation onto the join key.
     */
    public static class ExternalSourceDef {
        /** Field types a source column may bind to. TEMPORAL and LATLON need literal
         *  metadata / WKT parsing that a bare cell cannot carry unambiguously. */
        private static final Set<FieldType> SUPPORTED_COLUMN_TYPES =
            Collections.unmodifiableSet(EnumSet.of(FieldType.TEXT, FieldType.KEYWORD,
                FieldType.INT, FieldType.LONG, FieldType.DOUBLE));

        private final ExternalFormat format;
        private final String location;
        private final String subjectColumn;
        private final int subjectColumnIndex;
        private final String subjectPrefix;
        private final Character delimiter;
        private final boolean headerless;
        private final ErrorPolicy onError;
        private final List<ColumnBinding> columns;
        private final List<FieldDef> fields;
        private final List<String> deltaLocations;
        private final String opColumn;

        /** Default name of the operation column in a delta file. */
        public static final String DEFAULT_OP_COLUMN = "op";

        public ExternalSourceDef(ExternalFormat format, String location,
                                 String subjectColumn, int subjectColumnIndex, String subjectPrefix,
                                 Character delimiter, boolean headerless,
                                 ErrorPolicy onError,
                                 List<ColumnBinding> columns) {
            this(format, location, subjectColumn, subjectColumnIndex, subjectPrefix,
                delimiter, headerless, onError, columns,
                Collections.emptyList(), DEFAULT_OP_COLUMN);
        }

        /** Widest form: {@code deltaLocations} are applied over the base in order. */
        public ExternalSourceDef(ExternalFormat format, String location,
                                 String subjectColumn, int subjectColumnIndex, String subjectPrefix,
                                 Character delimiter, boolean headerless,
                                 ErrorPolicy onError,
                                 List<ColumnBinding> columns,
                                 List<String> deltaLocations, String opColumn) {
            this.format = Objects.requireNonNull(format, "format");
            this.location = Objects.requireNonNull(location, "location");
            this.subjectColumn = subjectColumn;
            this.subjectColumnIndex = subjectColumnIndex;
            this.subjectPrefix = subjectPrefix;
            this.delimiter = delimiter;
            this.headerless = headerless;
            this.onError = onError != null ? onError : ErrorPolicy.SKIP;
            this.columns = columns != null
                ? Collections.unmodifiableList(new ArrayList<>(columns))
                : Collections.emptyList();
            this.fields = Collections.unmodifiableList(distinctBoundFields(this.columns));
            this.deltaLocations = deltaLocations != null
                ? Collections.unmodifiableList(new ArrayList<>(deltaLocations))
                : Collections.emptyList();
            this.opColumn = opColumn != null && !opColumn.isBlank() ? opColumn : DEFAULT_OP_COLUMN;
            validate();
        }

        public ExternalFormat getFormat()        { return format; }
        public String getLocation()              { return location; }
        public String getSubjectColumn()         { return subjectColumn; }
        public int getSubjectColumnIndex()       { return subjectColumnIndex; }
        public String getSubjectPrefix()         { return subjectPrefix; }
        public Character getDelimiter()          { return delimiter; }
        public boolean isHeaderless()            { return headerless; }
        public ErrorPolicy getOnError()          { return onError; }
        public List<ColumnBinding> getColumns()  { return columns; }
        public List<FieldDef> getFields()        { return fields; }
        /** Delta files applied over the base, in order. Empty for a plain source. */
        public List<String> getDeltaLocations()  { return deltaLocations; }
        public String getOpColumn()              { return opColumn; }
        public boolean hasDeltas()               { return !deltaLocations.isEmpty(); }

        /** Same source with the deltas stripped — the base layer a delta reader wraps. */
        public ExternalSourceDef withoutDeltas() {
            return new ExternalSourceDef(format, location, subjectColumn, subjectColumnIndex,
                subjectPrefix, delimiter, headerless, onError, columns);
        }

        /**
         * Same source pointed at {@code deltaLocation}, with one extra column binding for
         * the operation. The extra field is internal — a delta reader consumes the op and
         * emits only the declared columns, so it never reaches Lucene.
         */
        public ExternalSourceDef asDeltaLayer(String deltaLocation, FieldDef opField) {
            List<ColumnBinding> withOp = new ArrayList<>(columns);
            withOp.add(new ColumnBinding(opColumn, -1, opField));
            return new ExternalSourceDef(format, deltaLocation, subjectColumn, subjectColumnIndex,
                subjectPrefix, delimiter, headerless, onError, withOp);
        }

        private void validate() {
            if (columns.isEmpty()) {
                throw new TextIndexException(
                    "idx:externalSource " + location + " declares no idx:column bindings");
            }
            if (headerless) {
                if (subjectColumnIndex < 0) {
                    throw new TextIndexException(
                        "Headerless idx:externalSource " + location
                        + " requires idx:subjectColumnIndex");
                }
            } else if (subjectColumn == null) {
                throw new TextIndexException(
                    "idx:externalSource " + location + " requires idx:subjectColumn");
            }
            Set<String> boundIris = new LinkedHashSet<>();
            for (ColumnBinding binding : columns) {
                if (headerless != binding.isPositional()) {
                    throw new TextIndexException(
                        "idx:externalSource " + location + " binds column " + binding
                        + " by " + (binding.isPositional() ? "index" : "name")
                        + " but idx:headerless is " + headerless);
                }
                FieldDef field = binding.getField();
                if (!SUPPORTED_COLUMN_TYPES.contains(field.getFieldType())) {
                    throw new TextIndexException(
                        "Field " + field.getFieldIRI().getURI() + " has type " + field.getFieldType()
                        + ", which cannot be bound to an idx:column. Supported types: "
                        + SUPPORTED_COLUMN_TYPES);
                }
                if (!boundIris.add(field.getFieldIRI().getURI())) {
                    throw new TextIndexException(
                        "Field " + field.getFieldIRI().getURI()
                        + " is bound to more than one column of idx:externalSource " + location);
                }
            }
            if (!deltaLocations.isEmpty()) {
                if (headerless) {
                    throw new TextIndexException(
                        "idx:delta on " + location + " requires a header row: the operation "
                        + "column is bound by name (idx:opColumn), not by position.");
                }
            }
        }

        private static List<FieldDef> distinctBoundFields(List<ColumnBinding> columns) {
            Map<String, FieldDef> byIri = new LinkedHashMap<>();
            for (ColumnBinding binding : columns) {
                byIri.putIfAbsent(binding.getField().getFieldIRI().getURI(), binding.getField());
            }
            return new ArrayList<>(byIri.values());
        }

        @Override
        public String toString() {
            return "ExternalSourceDef(" + format + " " + location + ", columns=" + columns + ")";
        }
    }

    /**
     * Defines a repeated correlated child collection. Children come either from the
     * graph — reached from the parent entity by {@code idx:joinPath}, with field values
     * evaluated relative to each child node — or from an {@code idx:externalSource},
     * one child per source row. Never both.
     */
    public static class NestedDef {
        private final String nestedName;
        private final Path joinPath;
        private final List<JoinStep> joinSteps;
        private final Set<Node> joinPredicates;
        private final List<FieldOccurrence> occurrences;
        private final List<FieldDef> fields;
        private final List<HierarchyDef> hierarchies;
        private final ExternalSourceDef externalSource;

        public NestedDef(String nestedName, Path joinPath, List<JoinStep> joinSteps,
                         Set<Node> joinPredicates, List<FieldOccurrence> occurrences,
                         List<HierarchyDef> hierarchies) {
            this.nestedName = Objects.requireNonNull(nestedName);
            this.joinPath = Objects.requireNonNull(joinPath);
            this.joinSteps = joinSteps != null
                ? Collections.unmodifiableList(new ArrayList<>(joinSteps))
                : Collections.emptyList();
            this.joinPredicates = joinPredicates != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(joinPredicates))
                : Collections.emptySet();
            this.occurrences = occurrences != null
                ? Collections.unmodifiableList(new ArrayList<>(occurrences))
                : Collections.emptyList();
            this.fields = Collections.unmodifiableList(distinctFields(this.occurrences));
            this.hierarchies = hierarchies != null
                ? Collections.unmodifiableList(new ArrayList<>(hierarchies))
                : Collections.emptyList();
            this.externalSource = null;
            if (this.occurrences.isEmpty()) {
                throw new IllegalArgumentException("NestedDef must contain at least one occurrence");
            }
            if (this.joinSteps.isEmpty()) {
                throw new IllegalArgumentException("NestedDef must contain at least one join step");
            }
        }

        /**
         * External variant: children come from {@code externalSource} rows rather than
         * from the graph, so there is no join path and no field occurrences. The scope
         * name must be given explicitly ({@code idx:nestedName}) since there is no join
         * path to derive one from.
         */
        public NestedDef(String nestedName, ExternalSourceDef externalSource,
                         List<HierarchyDef> hierarchies) {
            this.nestedName = Objects.requireNonNull(nestedName);
            this.externalSource = Objects.requireNonNull(externalSource);
            this.joinPath = null;
            this.joinSteps = Collections.emptyList();
            this.joinPredicates = Collections.emptySet();
            this.occurrences = Collections.emptyList();
            this.fields = externalSource.getFields();
            this.hierarchies = hierarchies != null
                ? Collections.unmodifiableList(new ArrayList<>(hierarchies))
                : Collections.emptyList();
        }

        public String getNestedName()             { return nestedName; }
        public Path getJoinPath()                 { return joinPath; }
        public List<JoinStep> getJoinSteps()      { return joinSteps; }
        public Set<Node> getJoinPredicates()      { return joinPredicates; }
        public List<FieldOccurrence> getOccurrences() { return occurrences; }
        public List<FieldDef> getFields()         { return fields; }
        public List<HierarchyDef> getHierarchies() { return hierarchies; }
        public ExternalSourceDef getExternalSource() { return externalSource; }
        /** True when children are supplied by an external source rather than the graph. */
        public boolean isExternal()               { return externalSource != null; }

        @Override
        public String toString() {
            return isExternal()
                ? "NestedDef(" + nestedName + ", external=" + externalSource + ")"
                : "NestedDef(" + nestedName + ", joinPath=" + joinPath + ", occurrences=" + occurrences + ")";
        }
    }

    public static class IndexProfile {
        private final Node shapeNode;
        private final Set<Node> targetClasses;
        private final String docIdField;
        private final String discriminatorField;
        private final List<FieldDef> fields;
        private final List<FieldOccurrence> rootOccurrences;
        private final List<HierarchyDef> hierarchies;
        private final List<NestedDef> nestedDefs;

        public IndexProfile(Node shapeNode, Set<Node> targetClasses,
                            String docIdField, String discriminatorField,
                            List<FieldDef> fields, List<FieldOccurrence> rootOccurrences) {
            this(shapeNode, targetClasses, docIdField, discriminatorField, fields,
                rootOccurrences, Collections.emptyList(), Collections.emptyList());
        }

        public IndexProfile(Node shapeNode, Set<Node> targetClasses,
                            String docIdField, String discriminatorField,
                            List<FieldDef> fields, List<FieldOccurrence> rootOccurrences,
                            List<HierarchyDef> hierarchies) {
            this(shapeNode, targetClasses, docIdField, discriminatorField, fields,
                rootOccurrences, hierarchies, Collections.emptyList());
        }

        public IndexProfile(Node shapeNode, Set<Node> targetClasses,
                            String docIdField, String discriminatorField,
                            List<FieldDef> fields, List<FieldOccurrence> rootOccurrences,
                            List<HierarchyDef> hierarchies, List<NestedDef> nestedDefs) {
            this.shapeNode = Objects.requireNonNull(shapeNode);
            this.targetClasses = targetClasses != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(targetClasses))
                : Collections.emptySet();
            this.docIdField = docIdField != null ? docIdField : "uri";
            this.discriminatorField = discriminatorField != null ? discriminatorField : "docType";
            this.fields = fields != null
                ? Collections.unmodifiableList(new ArrayList<>(fields))
                : Collections.emptyList();
            this.rootOccurrences = rootOccurrences != null
                ? Collections.unmodifiableList(new ArrayList<>(rootOccurrences))
                : Collections.emptyList();
            this.hierarchies = hierarchies != null
                ? Collections.unmodifiableList(new ArrayList<>(hierarchies))
                : Collections.emptyList();
            this.nestedDefs = nestedDefs != null
                ? Collections.unmodifiableList(new ArrayList<>(nestedDefs))
                : Collections.emptyList();
        }

        public Node getShapeNode()               { return shapeNode; }
        public Set<Node> getTargetClasses()      { return targetClasses; }
        public String getDocIdField()            { return docIdField; }
        public String getDiscriminatorField()    { return discriminatorField; }
        public List<FieldDef> getFields()        { return fields; }
        public List<FieldOccurrence> getRootOccurrences() { return rootOccurrences; }
        public List<HierarchyDef> getHierarchies() { return hierarchies; }
        public List<NestedDef> getNestedDefs()   { return nestedDefs; }

        /** True when any nested block of this profile draws its children from an
         *  external source. Such a profile can only be built by {@link ShaclBulkIndexer};
         *  a live graph-driven rebuild would drop the external children. */
        public boolean hasExternalSource() {
            for (NestedDef nestedDef : nestedDefs) {
                if (nestedDef.isExternal()) {
                    return true;
                }
            }
            return false;
        }

        /** Nested blocks fed by an external source, in declaration order. */
        public List<NestedDef> getExternalNestedDefs() {
            List<NestedDef> result = new ArrayList<>();
            for (NestedDef nestedDef : nestedDefs) {
                if (nestedDef.isExternal()) {
                    result.add(nestedDef);
                }
            }
            return result;
        }

        @Override
        public String toString() {
            return "IndexProfile(" + shapeNode + " -> " + targetClasses + ", fields=" + fields + ")";
        }
    }

    /** A (profile, occurrence, nested scope) tuple used by change tracking lookups. */
    public static class ProfileOccurrence {
        private final IndexProfile profile;
        private final FieldOccurrence occurrence;
        private final NestedDef nestedDef;

        public ProfileOccurrence(IndexProfile profile, FieldOccurrence occurrence, NestedDef nestedDef) {
            this.profile = Objects.requireNonNull(profile);
            this.occurrence = Objects.requireNonNull(occurrence);
            this.nestedDef = nestedDef;
        }

        public IndexProfile getProfile()         { return profile; }
        public FieldOccurrence getOccurrence()   { return occurrence; }
        public NestedDef getNestedDef()          { return nestedDef; }
        public boolean isNestedScoped()          { return nestedDef != null; }
    }

    /**
     * Reserved CQL filter property URI that resolves to the doc-id field of the
     * active index. See {@link #findDocIdFieldForEntityIriProperty(String)} and
     * issue #73.
     */
    public static final String ENTITY_IRI_PROPERTY = "urn:jena:lucene:index#entityIri";

    private final List<IndexProfile> profiles;
    private final Map<Node, List<ProfileOccurrence>> predicateLookup;
    private final Map<Node, List<ProfileOccurrence>> classConstraintLookup;
    private final Map<Node, List<IndexProfile>> classLookup;
    private final Set<Node> topLevelPredicates;
    private final Set<Node> nestedChildPredicates;
    private final Set<Node> nestedJoinPredicates;
    private final Set<Node> relevantPredicates;

    public ShaclIndexMapping(List<IndexProfile> profiles) {
        this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
        validateProfilesHaveOccurrences();
        validateLiteralMetadataRequirements();
        validateFieldNameUniqueness();
        validateHierarchyScopeConsistency();
        validateDocIdFieldUniformity();

        this.predicateLookup = Collections.unmodifiableMap(buildPredicateLookup(this.profiles));
        this.classConstraintLookup = Collections.unmodifiableMap(buildClassConstraintLookup(this.profiles));
        this.topLevelPredicates = buildTopLevelPredicates(this.profiles);
        this.nestedChildPredicates = buildNestedChildPredicates(this.profiles);
        this.nestedJoinPredicates = buildNestedJoinPredicates(this.profiles);
        this.relevantPredicates = buildRelevantPredicates(
            topLevelPredicates, nestedChildPredicates, nestedJoinPredicates);
        this.classLookup = Collections.unmodifiableMap(buildClassLookup(this.profiles));
    }

    public List<IndexProfile> getProfiles() {
        return profiles;
    }

    public boolean isRelevantPredicate(Node predicate) {
        return relevantPredicates.contains(predicate);
    }

    public boolean isTopLevelPredicate(Node predicate) {
        return topLevelPredicates.contains(predicate);
    }

    public boolean isNestedChildPredicate(Node predicate) {
        return nestedChildPredicates.contains(predicate);
    }

    public boolean isNestedJoinPredicate(Node predicate) {
        return nestedJoinPredicates.contains(predicate);
    }

    public List<ProfileOccurrence> getOccurrencesForPredicate(Node predicate) {
        return predicateLookup.getOrDefault(predicate, Collections.emptyList());
    }

    public List<ProfileOccurrence> getOccurrencesRequiringClass(Node cls) {
        return classConstraintLookup.getOrDefault(cls, Collections.emptyList());
    }

    public List<IndexProfile> getProfilesForClass(Node cls) {
        return classLookup.getOrDefault(cls, Collections.emptyList());
    }

    /**
     * If {@code property} is the reserved {@link #ENTITY_IRI_PROPERTY}, returns the
     * Lucene field name where the entity IRI is stored (the {@code idx:docIdField}
     * value, defaulting to {@code "uri"}). Otherwise returns {@code null}.
     * <p>
     * Multi-profile configs are guaranteed by {@code validateDocIdFieldUniformity}
     * to share a single doc-id field, so the choice is unambiguous.
     */
    public String findDocIdFieldForEntityIriProperty(String property) {
        if (!ENTITY_IRI_PROPERTY.equals(property)) {
            return null;
        }
        if (profiles.isEmpty()) {
            return null;
        }
        return profiles.get(0).getDocIdField();
    }

    public FieldDef findField(String fieldIRI) {
        for (IndexProfile profile : profiles) {
            for (FieldDef field : profile.getFields()) {
                if (field.getFieldIRI().getURI().equals(fieldIRI)) {
                    return field;
                }
            }
        }
        return null;
    }

    public FieldDef findFieldByName(String fieldName) {
        for (IndexProfile profile : profiles) {
            for (FieldDef field : profile.getFields()) {
                if (field.getFieldName().equals(fieldName)) {
                    return field;
                }
            }
        }
        return null;
    }

    /**
     * Return the {@link NestedDef} that owns {@code fieldName}, or {@code null} if the
     * field is root-scoped (appears in a profile's root occurrences, not under any
     * {@code idx:nested} block).
     * <p>
     * Used by the read path to detect when a field-scoped query must be wrapped in
     * {@code ToParentBlockJoinQuery} (block-join lift) rather than searched flat.
     */
    public NestedDef findNestedDefForFieldName(String fieldName) {
        if (fieldName == null) return null;
        for (IndexProfile profile : profiles) {
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                for (FieldDef field : nestedDef.getFields()) {
                    if (fieldName.equals(field.getFieldName())) {
                        return nestedDef;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The {@code idx:nested} block with the given scope name, or null if none.
     * <p>
     * The scope name is the one stored on each child document, so this is how the read
     * path recovers a child's field definitions from the document it just loaded.
     */
    public NestedDef findNestedDefByName(String nestedName) {
        if (nestedName == null) return null;
        for (IndexProfile profile : profiles) {
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                if (nestedName.equals(nestedDef.getNestedName())) {
                    return nestedDef;
                }
            }
        }
        return null;
    }

    /** True when any profile carries an {@code idx:externalSource} — such profiles are
     *  rebuild-only (see {@link ShaclTextDocProducer}). */
    public boolean hasExternalSources() {
        for (IndexProfile profile : profiles) {
            if (profile.hasExternalSource()) {
                return true;
            }
        }
        return false;
    }

    public List<String> getDefaultSearchFieldNames() {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (IndexProfile profile : profiles) {
            for (FieldDef field : profile.getFields()) {
                if (field.isDefaultSearch() && seen.add(field.getFieldName())) {
                    result.add(field.getFieldName());
                }
            }
        }
        return result;
    }

    public Set<String> getAllFieldNames() {
        Set<String> result = new LinkedHashSet<>();
        for (IndexProfile profile : profiles) {
            for (FieldDef field : profile.getFields()) {
                result.add(field.getFieldName());
            }
        }
        return result;
    }

    public List<String> getFacetFieldNames() {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (IndexProfile profile : profiles) {
            for (FieldDef field : profile.getFields()) {
                if (field.isFacetable() && seen.add(field.getFieldName())) {
                    result.add(field.getFieldName());
                }
            }
        }
        return result;
    }

    public List<HierarchyDef> getAllHierarchies() {
        List<HierarchyDef> result = new ArrayList<>();
        for (IndexProfile profile : profiles) {
            result.addAll(profile.getHierarchies());
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                result.addAll(nestedDef.getHierarchies());
            }
        }
        return result;
    }

    public HierarchyDef findHierarchy(String dimensionName) {
        for (IndexProfile profile : profiles) {
            for (HierarchyDef hierarchy : profile.getHierarchies()) {
                if (hierarchy.getDimensionName().equals(dimensionName)) {
                    return hierarchy;
                }
            }
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                for (HierarchyDef hierarchy : nestedDef.getHierarchies()) {
                    if (hierarchy.getDimensionName().equals(dimensionName)) {
                        return hierarchy;
                    }
                }
            }
        }
        return null;
    }

    public HierarchyDef findHierarchyForField(String fieldIRI) {
        FieldDef field = findField(fieldIRI);
        if (field == null) {
            return null;
        }
        for (IndexProfile profile : profiles) {
            for (HierarchyDef hierarchy : profile.getHierarchies()) {
                if (hierarchy.containsField(field)) {
                    return hierarchy;
                }
            }
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                for (HierarchyDef hierarchy : nestedDef.getHierarchies()) {
                    if (hierarchy.containsField(field)) {
                        return hierarchy;
                    }
                }
            }
        }
        return null;
    }

    public List<String> getHierarchyDimensionNames() {
        List<String> result = new ArrayList<>();
        for (IndexProfile profile : profiles) {
            for (HierarchyDef hierarchy : profile.getHierarchies()) {
                result.add(hierarchy.getDimensionName());
            }
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                for (HierarchyDef hierarchy : nestedDef.getHierarchies()) {
                    result.add(hierarchy.getDimensionName());
                }
            }
        }
        return result;
    }

    public boolean hasHierarchies() {
        for (IndexProfile profile : profiles) {
            if (!profile.getHierarchies().isEmpty()) {
                return true;
            }
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                if (!nestedDef.getHierarchies().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasNestedDefs() {
        for (IndexProfile profile : profiles) {
            if (!profile.getNestedDefs().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void validateLiteralMetadataRequirements() {
        for (IndexProfile profile : profiles) {
            for (FieldDef field : profile.getFields()) {
                if (field.isTemporal() && !field.isStoreLiteralMetadata()) {
                    throw new TextIndexException(
                        "Field " + field.getFieldIRI().getURI() + " uses " + field.getFieldType()
                        + " and requires idx:storeLiteralMetadata true");
                }
            }
        }
    }

    private void validateProfilesHaveOccurrences() {
        for (IndexProfile profile : profiles) {
            if (profile.getRootOccurrences().isEmpty() && profile.getNestedDefs().isEmpty()) {
                throw new TextIndexException(
                    "Profile " + profile.getShapeNode() + " has no root or nested field occurrences");
            }
        }
    }

    /**
     * One {@code idx:fieldName} may be bound in many shapes, but only by one field.
     * <p>
     * The name is the Lucene field, so two definitions sharing it write to the same place,
     * and every lookup that goes name to definition returns whichever shape happened to
     * parse first. The second definition is then silently ignored: its analyzer, its
     * facetability, its whole configuration. Checking only for conflicting types let the
     * commonest case through, two same-typed fields differing in everything else.
     */
    private void validateFieldNameUniqueness() {
        Map<String, FieldDef> seen = new HashMap<>();
        for (IndexProfile profile : profiles) {
            for (FieldDef field : profile.getFields()) {
                FieldDef prev = seen.putIfAbsent(field.getFieldName(), field);
                if (prev == null) {
                    continue;
                }
                if (prev.getFieldType() != field.getFieldType()) {
                    throw new TextIndexException(
                        "Field name '" + field.getFieldName()
                        + "' has conflicting types: " + prev.getFieldType()
                        + " vs " + field.getFieldType());
                }
                String previousIRI = prev.getFieldIRI().getURI();
                String currentIRI = field.getFieldIRI().getURI();
                if (!previousIRI.equals(currentIRI)) {
                    throw new TextIndexException(
                        "idx:fieldName '" + field.getFieldName() + "' is claimed by two field IRIs: "
                        + previousIRI + " and " + currentIRI
                        + ". Both write to one Lucene field, and a lookup by name returns whichever "
                        + "shape parsed first, so one definition would be silently ignored. Give them "
                        + "distinct idx:fieldName values, or bind the one field in both shapes.");
                }
            }
        }
    }

    private void validateDocIdFieldUniformity() {
        if (profiles.isEmpty()) {
            return;
        }
        String first = profiles.get(0).getDocIdField();
        for (int i = 1; i < profiles.size(); i++) {
            String other = profiles.get(i).getDocIdField();
            if (!Objects.equals(first, other)) {
                throw new TextIndexException(
                    "All profiles must share the same idx:docIdField (profiles in one Lucene index "
                    + "share doc storage). Profile " + profiles.get(0).getShapeNode() + " uses '"
                    + first + "' but profile " + profiles.get(i).getShapeNode() + " uses '"
                    + other + "'.");
            }
        }
    }

    private void validateHierarchyScopeConsistency() {
        for (IndexProfile profile : profiles) {
            Set<FieldDef> rootFields = new LinkedHashSet<>(distinctFields(profile.getRootOccurrences()));
            validateHierarchyScope(profile.getShapeNode(), profile.getHierarchies(), rootFields);
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                validateHierarchyScope(profile.getShapeNode(), nestedDef.getHierarchies(),
                    new LinkedHashSet<>(nestedDef.getFields()));
            }
        }
    }

    private void validateHierarchyScope(Node owner, List<HierarchyDef> hierarchies, Set<FieldDef> scopeFields) {
        for (HierarchyDef hierarchy : hierarchies) {
            for (FieldDef field : hierarchy.getLevels()) {
                if (!scopeFields.contains(field)) {
                    throw new TextIndexException(
                        "Hierarchy '" + hierarchy.getDimensionName() + "' on " + owner
                        + " references field " + field.getFieldIRI().getURI()
                        + " outside its populated scope");
                }
            }
        }
    }

    private static Map<Node, List<ProfileOccurrence>> buildPredicateLookup(List<IndexProfile> profiles) {
        Map<Node, List<ProfileOccurrence>> lookup = new LinkedHashMap<>();
        for (IndexProfile profile : profiles) {
            for (FieldOccurrence occurrence : profile.getRootOccurrences()) {
                addOccurrenceLookup(lookup, profile, occurrence, null);
            }
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                for (FieldOccurrence occurrence : nestedDef.getOccurrences()) {
                    addOccurrenceLookup(lookup, profile, occurrence, nestedDef);
                }
            }
        }
        return lookup;
    }

    private static void addOccurrenceLookup(Map<Node, List<ProfileOccurrence>> lookup,
                                            IndexProfile profile, FieldOccurrence occurrence,
                                            NestedDef nestedDef) {
        ProfileOccurrence profileOccurrence = new ProfileOccurrence(profile, occurrence, nestedDef);
        for (Node predicate : occurrence.getPredicates()) {
            lookup.computeIfAbsent(predicate, k -> new ArrayList<>()).add(profileOccurrence);
        }
    }

    private static Map<Node, List<ProfileOccurrence>> buildClassConstraintLookup(List<IndexProfile> profiles) {
        Map<Node, List<ProfileOccurrence>> lookup = new LinkedHashMap<>();
        for (IndexProfile profile : profiles) {
            for (FieldOccurrence occurrence : profile.getRootOccurrences()) {
                addClassConstraintLookup(lookup, profile, occurrence, null);
            }
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                for (FieldOccurrence occurrence : nestedDef.getOccurrences()) {
                    addClassConstraintLookup(lookup, profile, occurrence, nestedDef);
                }
            }
        }
        return lookup;
    }

    private static void addClassConstraintLookup(Map<Node, List<ProfileOccurrence>> lookup,
                                                 IndexProfile profile, FieldOccurrence occurrence,
                                                 NestedDef nestedDef) {
        if (!occurrence.requiresTypeConstraint()) {
            return;
        }
        lookup.computeIfAbsent(occurrence.getRequiredClass(), k -> new ArrayList<>())
            .add(new ProfileOccurrence(profile, occurrence, nestedDef));
    }

    private static Map<Node, List<IndexProfile>> buildClassLookup(List<IndexProfile> profiles) {
        Map<Node, List<IndexProfile>> lookup = new LinkedHashMap<>();
        for (IndexProfile profile : profiles) {
            for (Node cls : profile.getTargetClasses()) {
                lookup.computeIfAbsent(cls, k -> new ArrayList<>()).add(profile);
            }
        }
        return lookup;
    }

    private static Set<Node> buildTopLevelPredicates(List<IndexProfile> profiles) {
        Set<Node> predicates = new LinkedHashSet<>();
        for (IndexProfile profile : profiles) {
            for (FieldOccurrence occurrence : profile.getRootOccurrences()) {
                predicates.addAll(occurrence.getPredicates());
            }
        }
        return Collections.unmodifiableSet(predicates);
    }

    private static Set<Node> buildNestedChildPredicates(List<IndexProfile> profiles) {
        Set<Node> predicates = new LinkedHashSet<>();
        for (IndexProfile profile : profiles) {
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                for (FieldOccurrence occurrence : nestedDef.getOccurrences()) {
                    predicates.addAll(occurrence.getPredicates());
                }
            }
        }
        return Collections.unmodifiableSet(predicates);
    }

    private static Set<Node> buildNestedJoinPredicates(List<IndexProfile> profiles) {
        Set<Node> predicates = new LinkedHashSet<>();
        for (IndexProfile profile : profiles) {
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                predicates.addAll(nestedDef.getJoinPredicates());
            }
        }
        return Collections.unmodifiableSet(predicates);
    }

    private static Set<Node> buildRelevantPredicates(Set<Node> topLevelPredicates,
                                                     Set<Node> nestedChildPredicates,
                                                     Set<Node> nestedJoinPredicates) {
        Set<Node> predicates = new LinkedHashSet<>();
        predicates.addAll(topLevelPredicates);
        predicates.addAll(nestedChildPredicates);
        predicates.addAll(nestedJoinPredicates);
        return Collections.unmodifiableSet(predicates);
    }

    private static List<FieldDef> distinctFields(List<FieldOccurrence> occurrences) {
        Map<String, FieldDef> fieldsByIri = new LinkedHashMap<>();
        for (FieldOccurrence occurrence : occurrences) {
            FieldDef field = occurrence.getField();
            fieldsByIri.putIfAbsent(field.getFieldIRI().getURI(), field);
        }
        return new ArrayList<>(fieldsByIri.values());
    }
}
