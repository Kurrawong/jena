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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * An {@code idx:self} occurrence binds the focus node itself — the child node inside an
 * {@code idx:nested} block, the entity at root scope.
 * <p>
 * Without it, a correlated hierarchy cannot be expressed when one of its levels <em>is</em>
 * the correlating node. Here the child is a display table, the hierarchy is
 * (grouping, display table), and the display table is reached only by being the child.
 * Every value on a child record is correlated with its siblings by construction, so the
 * hierarchy stays pairwise correct where a cartesian product over entity-level values
 * would invent combinations.
 * <p>
 * Design note: {@code docs/2026-08-03_correlated_hierarchy_over_root_fields.md}.
 */
public class TestSelfBoundOccurrences {

    private static final String NS = "http://example.org/";
    private static final String FIELD_NS = "urn:jena:lucene:field#";
    private static final String DIM = "grouping_displayTable";

    private static final Node DOCUMENT_CLASS = NodeFactory.createURI(NS + "Document");
    private static final Node HAS_DISPLAY_TABLE = NodeFactory.createURI(NS + "hasDisplayTable");
    private static final Node HAS_GROUPING = NodeFactory.createURI(NS + "hasGrouping");
    private static final String SCOPE = PathFactory.pathLink(HAS_DISPLAY_TABLE).toString();

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

        FieldDef displayTable = new FieldDef("displayTable", FieldType.KEYWORD, null, null,
            true, true, true, false, true, false, false,
            NodeFactory.createURI(FIELD_NS + "displayTable"));
        FieldDef grouping = new FieldDef("grouping", FieldType.KEYWORD, null, null,
            true, true, true, false, true, false, false,
            NodeFactory.createURI(FIELD_NS + "grouping"));

        // The child node IS the display table, so its occurrence binds the focus node.
        NestedDef displayTables = new NestedDef(
            SCOPE,
            PathFactory.pathLink(HAS_DISPLAY_TABLE),
            List.of(new JoinStep(HAS_DISPLAY_TABLE, false)),
            Collections.singleton(HAS_DISPLAY_TABLE),
            List.of(
                FieldOccurrence.self(displayTable, null, null, null, SCOPE),
                pathOccurrence(grouping, HAS_GROUPING)),
            Collections.singletonList(
                new HierarchyDef(DIM, Arrays.asList(grouping, displayTable))));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "DocumentShape"),
            Collections.singleton(DOCUMENT_CLASS),
            "uri", "docType",
            Arrays.asList(displayTable, grouping),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.singletonList(displayTables));

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        // Hierarchy ordinals live in the taxonomy directory — without it the counts
        // vanish entirely (docs/03-configuration.md).
        textIndex = new ShaclTextIndexLucene(
            new ByteBuffersDirectory(), new ByteBuffersDirectory(), config);

        Dataset baseDs = DatasetFactory.create();
        dataset = TextDatasetFactory.create(baseDs, textIndex, true,
            new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping));
        loadData();
    }

    private static FieldOccurrence pathOccurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(field, PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            Collections.singleton(predicate), null, null, null, SCOPE);
    }

    /**
     * doc1 sits wholly inside Holes; doc2 straddles both groupings — the case a cartesian
     * product over entity-level values gets wrong; doc3 sits wholly inside Geochemistry.
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
        return toFacetMap(textIndex.getFacetCounts(
            null, null, Collections.singletonList(DIM), 20, 0, drillDown).get(DIM));
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
    public void testSelfBoundLevelGivesTopLevelCounts() {
        Map<String, Long> values = topLevel();
        assertEquals(Long.valueOf(2), values.get(HOLES));           // doc1, doc2
        assertEquals(Long.valueOf(2), values.get(GEOCHEMISTRY));    // doc2, doc3
    }

    /**
     * doc2 carries borehole (Holes) and geochem-results (Geochemistry). Reading the two
     * levels independently from the entity would emit {@code Holes / geochem-results} and
     * {@code Geochemistry / borehole}; per child record, neither can arise.
     */
    @Test
    public void testDrillDownStaysCorrelatedPerChild() {
        Map<String, Long> holes = drillInto(HOLES);
        assertEquals(Long.valueOf(2), holes.get(BOREHOLE));         // doc1, doc2
        assertEquals(Long.valueOf(1), holes.get(DOWNHOLE_ASSAYS));  // doc1
        assertFalse("geochem-results is not in the Holes grouping",
            holes.containsKey(GEOCHEM_RESULTS));

        Map<String, Long> geochemistry = drillInto(GEOCHEMISTRY);
        assertEquals(Long.valueOf(2), geochemistry.get(GEOCHEM_RESULTS));
        assertFalse("borehole is not in the Geochemistry grouping",
            geochemistry.containsKey(BOREHOLE));
    }

    /** Geochemistry holds one datatype, so its children cannot out-count it. */
    @Test
    public void testChildrenDoNotExceedParent() {
        long parent = topLevel().get(GEOCHEMISTRY);
        long children = drillInto(GEOCHEMISTRY).values().stream().mapToLong(Long::longValue).sum();
        assertTrue("Geochemistry children (" + children + ") exceed parent count (" + parent + ")",
            children <= parent);
    }

    /**
     * A self-bound field is an ordinary child-scope field, not facet machinery: it takes
     * part in same-child correlation like any sibling value.
     */
    @Test
    public void testSelfBoundFieldCorrelatesWithSiblingInSameChild() {
        assertEquals("both documents carrying borehole have it inside Holes",
            Set.of(NS + "doc1", NS + "doc2"), matchingEntities(BOREHOLE, HOLES));
        assertTrue("no document has borehole inside Geochemistry on one child",
            matchingEntities(BOREHOLE, GEOCHEMISTRY).isEmpty());
    }

    private Set<String> matchingEntities(String displayTable, String grouping) {
        CqlExpression cql = CqlParser.parse("""
            {"op":"and","args":[
              {"op":"=","args":[{"property":"%sdisplayTable"},"%s"]},
              {"op":"=","args":[{"property":"%sgrouping"},"%s"]}
            ]}""".formatted(FIELD_NS, displayTable, FIELD_NS, grouping));

        Set<String> uris = new HashSet<>();
        for (TextHit hit : textIndex.queryWithCql(null, null, cql, null, null, null, 20, null)) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }

    /** A vocabulary edit — not a change on the entity — reindexes the affected entities. */
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
        assertEquals("and is still under Geochemistry",
            Long.valueOf(2), drillInto(GEOCHEMISTRY).get(GEOCHEM_RESULTS));
    }

    /** Adding a display table to an entity reindexes it through the join predicate. */
    @Test
    public void testAddingChildReindexesEntity() {
        dataset.begin(ReadWrite.WRITE);
        try {
            dataset.getDefaultModel().add(
                ResourceFactory.createResource(NS + "doc3"),
                ResourceFactory.createProperty(HAS_DISPLAY_TABLE.getURI()),
                ResourceFactory.createResource(DOWNHOLE_ASSAYS));
            dataset.commit();
        } finally {
            dataset.end();
        }

        assertEquals("doc3 joins doc1 and doc2 under Holes",
            Long.valueOf(3), topLevel().get(HOLES));
        assertEquals("downhole-assays is now on doc1 and doc3",
            Long.valueOf(2), drillInto(HOLES).get(DOWNHOLE_ASSAYS));
    }
}
