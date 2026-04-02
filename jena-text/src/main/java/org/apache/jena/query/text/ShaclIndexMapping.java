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
import org.apache.jena.sparql.path.P_Link;
import org.apache.jena.sparql.path.Path;
import org.apache.lucene.analysis.Analyzer;

/**
 * Parsed representation of SHACL-like index shapes for entity-per-document indexing.
 * Pure data model — no RDF parsing or Lucene indexing logic.
 */
public class ShaclIndexMapping {

    public enum FieldType {
        TEXT, KEYWORD, INT, LONG, DOUBLE, LATLON
    }

    private static final String FIELD_IRI_PREFIX = "urn:jena:lucene:field#";

    public static class FieldDef {
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
        private final Set<Node> predicates;
        private final Path path;
        private final Node fieldIRI;

        public FieldDef(String fieldName, FieldType fieldType, Analyzer analyzer,
                        boolean stored, boolean indexed, boolean facetable,
                        boolean sortable, boolean multiValued, boolean defaultSearch,
                        Set<Node> predicates) {
            this(fieldName, fieldType, analyzer, null, stored, indexed, facetable,
                 sortable, multiValued, defaultSearch, predicates, null, null);
        }

        public FieldDef(String fieldName, FieldType fieldType, Analyzer analyzer,
                        boolean stored, boolean indexed, boolean facetable,
                        boolean sortable, boolean multiValued, boolean defaultSearch,
                        Set<Node> predicates, Path path) {
            this(fieldName, fieldType, analyzer, null, stored, indexed, facetable,
                 sortable, multiValued, defaultSearch, predicates, path, null);
        }

        public FieldDef(String fieldName, FieldType fieldType, Analyzer analyzer,
                        boolean stored, boolean indexed, boolean facetable,
                        boolean sortable, boolean multiValued, boolean defaultSearch,
                        Set<Node> predicates, Path path, Node fieldIRI) {
            this(fieldName, fieldType, analyzer, null, stored, indexed, facetable,
                 sortable, multiValued, defaultSearch, predicates, path, fieldIRI);
        }

        public FieldDef(String fieldName, FieldType fieldType, Analyzer analyzer,
                        Analyzer queryAnalyzer,
                        boolean stored, boolean indexed, boolean facetable,
                        boolean sortable, boolean multiValued, boolean defaultSearch,
                        Set<Node> predicates, Path path, Node fieldIRI) {
            this.fieldName = Objects.requireNonNull(fieldName);
            this.fieldType = fieldType != null ? fieldType : FieldType.TEXT;
            this.analyzer = analyzer;
            this.queryAnalyzer = queryAnalyzer;
            this.stored = stored;
            this.indexed = indexed;
            this.facetable = facetable;
            this.sortable = sortable;
            this.multiValued = multiValued;
            this.defaultSearch = defaultSearch;
            this.predicates = predicates != null ? Collections.unmodifiableSet(new LinkedHashSet<>(predicates)) : Collections.emptySet();
            this.path = path;
            this.fieldIRI = fieldIRI != null ? fieldIRI
                : NodeFactory.createURI(FIELD_IRI_PREFIX + fieldName);
        }

        public String getFieldName()       { return fieldName; }
        public FieldType getFieldType()     { return fieldType; }
        public Analyzer getAnalyzer()       { return analyzer; }
        public Analyzer getQueryAnalyzer()  { return queryAnalyzer; }
        public boolean isStored()           { return stored; }
        public boolean isIndexed()          { return indexed; }
        public boolean isFacetable()        { return facetable; }
        public boolean isSortable()         { return sortable; }
        public boolean isMultiValued()      { return multiValued; }
        public boolean isDefaultSearch()    { return defaultSearch; }
        public Set<Node> getPredicates()    { return predicates; }
        public Node getFieldIRI()            { return fieldIRI; }

        /** The structured path for this field. Null for simple predicate fields (backward compat). */
        public Path getPath()              { return path; }

        /** True if this field uses a complex path (sequence, inverse, or nested). */
        public boolean hasComplexPath() {
            return path != null && !(path instanceof P_Link);
        }

        @Override
        public String toString() {
            return fieldName + "(" + fieldType + ")";
        }
    }

    /**
     * Defines a hierarchical facet dimension composed of ordered field levels.
     * Each level is a {@link FieldDef} whose values form path components in a Lucene taxonomy.
     * <p>
     * Example: a hierarchy of (type, subtype) where type="Water Bore" and subtype="BH123456"
     * produces a Lucene {@code FacetField("type_hierarchy", "Water Bore", "BH123456")}.
     */
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

        public String getDimensionName()   { return dimensionName; }
        public List<FieldDef> getLevels()   { return levels; }
        public int getDepth()              { return levels.size(); }

        /** Get the FieldDef for a specific level (0-based). */
        public FieldDef getLevel(int index) { return levels.get(index); }

        /** Check if a FieldDef is part of this hierarchy. */
        public boolean containsField(FieldDef field) {
            return levels.contains(field);
        }

        /** Get the level index of a field, or -1 if not in this hierarchy. */
        public int getLevelIndex(FieldDef field) {
            return levels.indexOf(field);
        }

        @Override
        public String toString() {
            return "HierarchyDef(" + dimensionName + ", levels=" + levels + ")";
        }
    }

    public static class IndexProfile {
        private final Node shapeNode;
        private final Set<Node> targetClasses;
        private final String docIdField;
        private final String discriminatorField;
        private final List<FieldDef> fields;
        private final List<HierarchyDef> hierarchies;

        public IndexProfile(Node shapeNode, Set<Node> targetClasses,
                            String docIdField, String discriminatorField,
                            List<FieldDef> fields) {
            this(shapeNode, targetClasses, docIdField, discriminatorField, fields, Collections.emptyList());
        }

        public IndexProfile(Node shapeNode, Set<Node> targetClasses,
                            String docIdField, String discriminatorField,
                            List<FieldDef> fields, List<HierarchyDef> hierarchies) {
            this.shapeNode = shapeNode;
            this.targetClasses = targetClasses != null ? Collections.unmodifiableSet(new LinkedHashSet<>(targetClasses)) : Collections.emptySet();
            this.docIdField = docIdField != null ? docIdField : "uri";
            this.discriminatorField = discriminatorField != null ? discriminatorField : "docType";
            this.fields = fields != null ? Collections.unmodifiableList(new ArrayList<>(fields)) : Collections.emptyList();
            this.hierarchies = hierarchies != null ? Collections.unmodifiableList(new ArrayList<>(hierarchies)) : Collections.emptyList();
        }

        public Node getShapeNode()          { return shapeNode; }
        public Set<Node> getTargetClasses() { return targetClasses; }
        public String getDocIdField()       { return docIdField; }
        public String getDiscriminatorField() { return discriminatorField; }
        public List<FieldDef> getFields()   { return fields; }
        public List<HierarchyDef> getHierarchies() { return hierarchies; }

        @Override
        public String toString() {
            return "IndexProfile(" + shapeNode + " -> " + targetClasses + ", fields=" + fields + ")";
        }
    }

    /** A (profile, field) pair returned from predicate lookups. */
    public static class ProfileField {
        private final IndexProfile profile;
        private final FieldDef field;

        public ProfileField(IndexProfile profile, FieldDef field) {
            this.profile = profile;
            this.field = field;
        }

        public IndexProfile getProfile()    { return profile; }
        public FieldDef getField()          { return field; }
    }

    private final List<IndexProfile> profiles;
    private final Map<Node, List<ProfileField>> predicateLookup;
    private final Map<Node, List<IndexProfile>> classLookup;

    public ShaclIndexMapping(List<IndexProfile> profiles) {
        this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
        validateFieldNameUniqueness();

        // Build predicate → (profile, field) lookup
        Map<Node, List<ProfileField>> predMap = new HashMap<>();
        for (IndexProfile profile : profiles) {
            for (FieldDef field : profile.getFields()) {
                for (Node pred : field.getPredicates()) {
                    predMap.computeIfAbsent(pred, k -> new ArrayList<>())
                           .add(new ProfileField(profile, field));
                }
            }
        }
        this.predicateLookup = Collections.unmodifiableMap(predMap);

        // Build targetClass → profiles lookup
        Map<Node, List<IndexProfile>> clsMap = new HashMap<>();
        for (IndexProfile profile : profiles) {
            for (Node cls : profile.getTargetClasses()) {
                clsMap.computeIfAbsent(cls, k -> new ArrayList<>())
                      .add(profile);
            }
        }
        this.classLookup = Collections.unmodifiableMap(clsMap);
    }

    public List<IndexProfile> getProfiles() {
        return profiles;
    }

    public boolean isRelevantPredicate(Node p) {
        return predicateLookup.containsKey(p);
    }

    public List<ProfileField> getProfilesForPredicate(Node p) {
        return predicateLookup.getOrDefault(p, Collections.emptyList());
    }

    public List<IndexProfile> getProfilesForClass(Node cls) {
        return classLookup.getOrDefault(cls, Collections.emptyList());
    }

    /**
     * Find a FieldDef by field IRI across all profiles.
     * Matches the exact IRI string against each field's IRI.
     * Returns null if not found.
     */
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

    /**
     * Find a FieldDef by Lucene field name across all profiles.
     * This is for internal use where the Lucene field name is known.
     * Returns null if not found.
     */
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

    /** Return all field names marked as defaultSearch across all profiles. */
    public List<String> getDefaultSearchFieldNames() {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IndexProfile profile : profiles) {
            for (FieldDef field : profile.getFields()) {
                if (field.isDefaultSearch() && seen.add(field.getFieldName())) {
                    result.add(field.getFieldName());
                }
            }
        }
        return result;
    }

    /** Return all field names across all profiles. */
    public Set<String> getAllFieldNames() {
        Set<String> result = new LinkedHashSet<>();
        for (IndexProfile profile : profiles) {
            for (FieldDef field : profile.getFields()) {
                result.add(field.getFieldName());
            }
        }
        return result;
    }

    /**
     * Validate that field names are consistent across profiles.
     * The same field name may appear in multiple profiles (e.g. "title" in both
     * BookShape and ArticleShape), but must have the same FieldType when shared.
     */
    private void validateFieldNameUniqueness() {
        Map<String, FieldType> seen = new HashMap<>();
        for (IndexProfile profile : profiles) {
            for (FieldDef field : profile.getFields()) {
                FieldType prev = seen.put(field.getFieldName(), field.getFieldType());
                if (prev != null && prev != field.getFieldType()) {
                    throw new TextIndexException(
                        "Field name '" + field.getFieldName() +
                        "' has conflicting types: " + prev + " vs " + field.getFieldType());
                }
            }
        }
    }

    /** Return all field names marked as facetable across all profiles. */
    public List<String> getFacetFieldNames() {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IndexProfile profile : profiles) {
            for (FieldDef field : profile.getFields()) {
                if (field.isFacetable() && seen.add(field.getFieldName())) {
                    result.add(field.getFieldName());
                }
            }
        }
        return result;
    }

    /** Return all hierarchy definitions across all profiles. */
    public List<HierarchyDef> getAllHierarchies() {
        List<HierarchyDef> result = new ArrayList<>();
        for (IndexProfile profile : profiles) {
            result.addAll(profile.getHierarchies());
        }
        return result;
    }

    /** Find a HierarchyDef by dimension name. */
    public HierarchyDef findHierarchy(String dimensionName) {
        for (IndexProfile profile : profiles) {
            for (HierarchyDef h : profile.getHierarchies()) {
                if (h.getDimensionName().equals(dimensionName)) {
                    return h;
                }
            }
        }
        return null;
    }

    /**
     * Find the HierarchyDef that contains a given field (by field IRI).
     * Returns null if the field is not part of any hierarchy.
     */
    public HierarchyDef findHierarchyForField(String fieldIRI) {
        FieldDef fd = findField(fieldIRI);
        if (fd == null) return null;
        for (IndexProfile profile : profiles) {
            for (HierarchyDef h : profile.getHierarchies()) {
                if (h.containsField(fd)) {
                    return h;
                }
            }
        }
        return null;
    }

    /** Return all hierarchy dimension names across all profiles. */
    public List<String> getHierarchyDimensionNames() {
        List<String> result = new ArrayList<>();
        for (IndexProfile profile : profiles) {
            for (HierarchyDef h : profile.getHierarchies()) {
                result.add(h.getDimensionName());
            }
        }
        return result;
    }

    /** Check if any hierarchies are defined. */
    public boolean hasHierarchies() {
        for (IndexProfile profile : profiles) {
            if (!profile.getHierarchies().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
