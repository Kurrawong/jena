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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
import org.apache.jena.query.text.ShaclIndexMapping.HierarchyDef;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
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
 * A hierarchy over two <em>root</em> fields whose SHACL paths are prefix-chained —
 * {@code dataType} at {@code gswa:hasDisplayTable} and {@code dataTypeGrouping} at
 * {@code (gswa:hasDisplayTable gswa:hasGrouping)}.
 * <p>
 * Both fields are multi-valued, and their values are pairwise related through the
 * display-table node. Taking the cartesian product of the two levels invents paths that
 * are not in the data; the hierarchy must stay correlated per display table.
 * <p>
 * Design note: {@code docs/2026-08-03_correlated_hierarchy_over_root_fields.md}.
 */
public class TestCorrelatedRootHierarchy {

    private static final String NS = "http://example.org/";
    private static final String FIELD_NS = "urn:jena:lucene:field#";
    private static final String DIM = "dataTypeGrouping_dataType";

    private static final Node DOCUMENT_CLASS = NodeFactory.createURI(NS + "Document");
    private static final Node HAS_DISPLAY_TABLE = NodeFactory.createURI(NS + "hasDisplayTable");
    private static final Node HAS_GROUPING = NodeFactory.createURI(NS + "hasGrouping");

    private static final String BOREHOLE = NS + "display/borehole";
    private static final String DOWNHOLE_ASSAYS = NS + "display/downhole-assays";
    private static final String GEOCHEM_RESULTS = NS + "display/geochem-results";
    private static final String HOLES = NS + "datatype/Holes";
    private static final String GEOCHEMISTRY = NS + "datatype/Geochemistry";

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @Before
    public void setUp() {
        TextQuery.init();

        Node dataTypeIRI = NodeFactory.createURI(FIELD_NS + "dataType");
        Node groupingIRI = NodeFactory.createURI(FIELD_NS + "dataTypeGrouping");

        FieldDef dataType = new FieldDef("dataType", FieldType.KEYWORD, null, null,
            true, true, true, false, true, false, false, dataTypeIRI);
        FieldDef dataTypeGrouping = new FieldDef("dataTypeGrouping", FieldType.KEYWORD, null, null,
            true, true, true, false, true, false, false, groupingIRI);

        FieldOccurrence dataTypeOccurrence = occurrence(dataType, HAS_DISPLAY_TABLE);
        FieldOccurrence groupingOccurrence = occurrence(dataTypeGrouping, HAS_DISPLAY_TABLE, HAS_GROUPING);

        HierarchyDef hierarchy = new HierarchyDef(DIM, Arrays.asList(dataTypeGrouping, dataType));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "DocumentShape"),
            Collections.singleton(DOCUMENT_CLASS),
            "uri", "docType",
            Arrays.asList(dataType, dataTypeGrouping),
            Arrays.asList(dataTypeOccurrence, groupingOccurrence),
            Collections.singletonList(hierarchy),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        // Hierarchy ordinals live in the taxonomy directory — without it the
        // hierarchical counts vanish entirely (docs/03-configuration.md:64).
        textIndex = new ShaclTextIndexLucene(
            new ByteBuffersDirectory(), new ByteBuffersDirectory(), config);

        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(
            baseDs.asDatasetGraph(), textIndex, mapping);

        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);
        loadData();
    }

    /**
     * doc1 sits wholly inside Holes; doc2 straddles both groupings (the case the
     * cartesian product gets wrong); doc3 sits wholly inside Geochemistry.
     */
    private void loadData() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();

            addGrouping(model, BOREHOLE, HOLES);
            addGrouping(model, DOWNHOLE_ASSAYS, HOLES);
            addGrouping(model, GEOCHEM_RESULTS, GEOCHEMISTRY);

            addDocument(model, "doc1", BOREHOLE, DOWNHOLE_ASSAYS);
            addDocument(model, "doc2", BOREHOLE, GEOCHEM_RESULTS);
            addDocument(model, "doc3", GEOCHEM_RESULTS);

            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void addGrouping(Model model, String displayTable, String grouping) {
        model.add(ResourceFactory.createResource(displayTable),
            ResourceFactory.createProperty(HAS_GROUPING.getURI()),
            ResourceFactory.createResource(grouping));
    }

    private void addDocument(Model model, String id, String... displayTables) {
        Resource doc = ResourceFactory.createResource(NS + id);
        model.add(doc, RDF.type, ResourceFactory.createResource(DOCUMENT_CLASS.getURI()));
        for (String displayTable : displayTables) {
            model.add(doc, ResourceFactory.createProperty(HAS_DISPLAY_TABLE.getURI()),
                ResourceFactory.createResource(displayTable));
        }
    }

    private static FieldOccurrence occurrence(FieldDef field, Node... predicates) {
        List<JoinStep> steps = new ArrayList<>();
        Set<Node> predicateSet = new LinkedHashSet<>();
        Path path = null;
        for (Node predicate : predicates) {
            steps.add(new JoinStep(predicate, false));
            predicateSet.add(predicate);
            Path link = PathFactory.pathLink(predicate);
            path = (path == null) ? link : PathFactory.pathSeq(path, link);
        }
        return new FieldOccurrence(field, path, List.of(List.copyOf(steps)),
            predicateSet, null, null, null, null);
    }

    @After
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    private Map<String, Long> topLevel() {
        return facets(null);
    }

    private Map<String, Long> drillInto(String grouping) {
        return facets(new String[] {grouping});
    }

    private Map<String, Long> facets(String[] drillPath) {
        Map<String, String[]> drillDown = null;
        if (drillPath != null) {
            drillDown = new HashMap<>();
            drillDown.put(DIM, drillPath);
        }
        Map<String, List<FacetValue>> counts = textIndex.getFacetCounts(
            null, null, Collections.singletonList(DIM), 20, 0, drillDown);
        return toFacetMap(counts.get(DIM));
    }

    private Map<String, Long> flatFacet(String field) {
        Map<String, List<FacetValue>> counts = textIndex.getFacetCounts(
            null, null, Collections.singletonList(field), 20, 0);
        return toFacetMap(counts.get(field));
    }

    private static Map<String, Long> toFacetMap(List<FacetValue> values) {
        Map<String, Long> map = new HashMap<>();
        if (values != null) {
            for (FacetValue value : values) {
                map.put(value.getValue(), value.getCount());
            }
        }
        return map;
    }

    @Test
    public void testTopLevelGroupingCounts() {
        Map<String, Long> values = topLevel();
        assertEquals(Long.valueOf(2), values.get(HOLES));            // doc1, doc2
        assertEquals(Long.valueOf(2), values.get(GEOCHEMISTRY));     // doc2, doc3
    }

    /**
     * The defect: doc2 carries borehole (Holes) and geochem-results (Geochemistry).
     * A cartesian product over the two multi-valued levels invents
     * {@code Holes / geochem-results} and {@code Geochemistry / borehole}.
     */
    @Test
    public void testDrillDownDoesNotInventPaths() {
        Map<String, Long> holes = drillInto(HOLES);
        assertEquals(Long.valueOf(2), holes.get(BOREHOLE));           // doc1, doc2
        assertEquals(Long.valueOf(1), holes.get(DOWNHOLE_ASSAYS));    // doc1
        assertFalse("geochem-results is not in the Holes grouping",
            holes.containsKey(GEOCHEM_RESULTS));

        Map<String, Long> geochemistry = drillInto(GEOCHEMISTRY);
        assertEquals(Long.valueOf(2), geochemistry.get(GEOCHEM_RESULTS)); // doc2, doc3
        assertFalse("borehole is not in the Geochemistry grouping",
            geochemistry.containsKey(BOREHOLE));
    }

    /**
     * Children of a grouping must not sum to more than the parent count unless
     * documents genuinely carry several datatypes within that grouping. Holes has
     * doc1 (two datatypes) and doc2 (one), so 2 + 1 over a parent count of 2 is
     * correct; the invented-path failure shows up as Geochemistry's children
     * exceeding its own count.
     */
    @Test
    public void testGeochemistryChildrenDoNotExceedParent() {
        long parent = topLevel().get(GEOCHEMISTRY);
        long children = drillInto(GEOCHEMISTRY).values().stream().mapToLong(Long::longValue).sum();
        assertTrue("Geochemistry children (" + children + ") exceed parent count (" + parent + ")",
            children <= parent);
    }

    /** The flat facets on the same fields keep their own dimensions and counts. */
    @Test
    public void testFlatFacetsAreUnaffected() {
        Map<String, Long> dataTypes = flatFacet("dataType");
        assertEquals(Long.valueOf(2), dataTypes.get(BOREHOLE));
        assertEquals(Long.valueOf(1), dataTypes.get(DOWNHOLE_ASSAYS));
        assertEquals(Long.valueOf(2), dataTypes.get(GEOCHEM_RESULTS));

        Map<String, Long> groupings = flatFacet("dataTypeGrouping");
        assertEquals(Long.valueOf(2), groupings.get(HOLES));
        assertEquals(Long.valueOf(2), groupings.get(GEOCHEMISTRY));
    }

    /** A vocabulary edit — not a change on the entity — must reindex affected entities. */
    @Test
    public void testGroupingVocabularyChangeReindexesEntities() {
        dataset.begin(ReadWrite.WRITE);
        try {
            addGrouping(dataset.getDefaultModel(), GEOCHEM_RESULTS, HOLES);
            dataset.commit();
        } finally {
            dataset.end();
        }

        Map<String, Long> holes = drillInto(HOLES);
        assertEquals("geochem-results now also sits under Holes",
            Long.valueOf(2), holes.get(GEOCHEM_RESULTS));
        assertEquals(Long.valueOf(2), holes.get(BOREHOLE));

        Map<String, Long> geochemistry = drillInto(GEOCHEMISTRY);
        assertEquals("and is still under Geochemistry",
            Long.valueOf(2), geochemistry.get(GEOCHEM_RESULTS));
    }

    /**
     * Three prefix-chained levels: theme above grouping above datatype. A document
     * straddling two branches of the taxonomy must produce one path per branch, not the
     * eight combinations a cartesian product over three multi-valued levels would give.
     */
    @Test
    public void testThreeLevelChainStaysCorrelated() {
        Node hasTheme = NodeFactory.createURI(NS + "hasTheme");
        String geology = NS + "theme/Geology";
        String chemistry = NS + "theme/Chemistry";
        String dim = "dataTypeTheme_dataTypeGrouping_dataType";

        Node dataTypeIRI = NodeFactory.createURI(FIELD_NS + "dataType");
        Node groupingIRI = NodeFactory.createURI(FIELD_NS + "dataTypeGrouping");
        Node themeIRI = NodeFactory.createURI(FIELD_NS + "dataTypeTheme");

        FieldDef dataType = new FieldDef("dataType", FieldType.KEYWORD, null, null,
            true, true, true, false, true, false, false, dataTypeIRI);
        FieldDef grouping = new FieldDef("dataTypeGrouping", FieldType.KEYWORD, null, null,
            true, true, true, false, true, false, false, groupingIRI);
        FieldDef theme = new FieldDef("dataTypeTheme", FieldType.KEYWORD, null, null,
            true, true, true, false, true, false, false, themeIRI);

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "DocumentShape"),
            Collections.singleton(DOCUMENT_CLASS),
            "uri", "docType",
            Arrays.asList(dataType, grouping, theme),
            Arrays.asList(
                occurrence(dataType, HAS_DISPLAY_TABLE),
                occurrence(grouping, HAS_DISPLAY_TABLE, HAS_GROUPING),
                occurrence(theme, HAS_DISPLAY_TABLE, HAS_GROUPING, hasTheme)),
            Collections.singletonList(
                new HierarchyDef(dim, Arrays.asList(theme, grouping, dataType))),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        ShaclTextIndexLucene index = new ShaclTextIndexLucene(
            new ByteBuffersDirectory(), new ByteBuffersDirectory(), config);
        Dataset baseDs = DatasetFactory.create();
        Dataset ds = TextDatasetFactory.create(baseDs, index, true,
            new ShaclTextDocProducer(baseDs.asDatasetGraph(), index, mapping));
        try {
            ds.begin(ReadWrite.WRITE);
            try {
                Model model = ds.getDefaultModel();
                addGrouping(model, BOREHOLE, HOLES);
                addGrouping(model, GEOCHEM_RESULTS, GEOCHEMISTRY);
                model.add(ResourceFactory.createResource(HOLES),
                    ResourceFactory.createProperty(hasTheme.getURI()),
                    ResourceFactory.createResource(geology));
                model.add(ResourceFactory.createResource(GEOCHEMISTRY),
                    ResourceFactory.createProperty(hasTheme.getURI()),
                    ResourceFactory.createResource(chemistry));
                addDocument(model, "doc1", BOREHOLE, GEOCHEM_RESULTS);
                ds.commit();
            } finally {
                ds.end();
            }

            Map<String, String[]> geologyGrouping = new HashMap<>();
            geologyGrouping.put(dim, new String[] {geology});
            Map<String, Long> underGeology = toFacetMap(index.getFacetCounts(
                null, null, Collections.singletonList(dim), 20, 0, geologyGrouping).get(dim));
            assertEquals(Long.valueOf(1), underGeology.get(HOLES));
            assertFalse("Geochemistry does not sit under the Geology theme",
                underGeology.containsKey(GEOCHEMISTRY));

            Map<String, String[]> geologyHoles = new HashMap<>();
            geologyHoles.put(dim, new String[] {geology, HOLES});
            Map<String, Long> underHoles = toFacetMap(index.getFacetCounts(
                null, null, Collections.singletonList(dim), 20, 0, geologyHoles).get(dim));
            assertEquals(Long.valueOf(1), underHoles.get(BOREHOLE));
            assertFalse("geochem-results is not reachable through Geology / Holes",
                underHoles.containsKey(GEOCHEM_RESULTS));
        } finally {
            ds.close();
        }
    }

    /** A display table with no grouping contributes nothing to the hierarchy dimension. */
    @Test
    public void testDisplayTableWithoutGroupingIsAbsentFromHierarchy() {
        String orphan = NS + "display/orphan";
        dataset.begin(ReadWrite.WRITE);
        try {
            addDocument(dataset.getDefaultModel(), "doc4", orphan);
            dataset.commit();
        } finally {
            dataset.end();
        }

        assertEquals("orphan display table is still a flat dataType facet value",
            Long.valueOf(1), flatFacet("dataType").get(orphan));

        Map<String, Long> values = topLevel();
        assertEquals(Long.valueOf(2), values.get(HOLES));
        assertEquals(Long.valueOf(2), values.get(GEOCHEMISTRY));
        assertFalse(values.containsKey(orphan));
    }
}
