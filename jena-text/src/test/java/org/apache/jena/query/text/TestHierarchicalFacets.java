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
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.ShaclIndexMapping.*;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for hierarchical facets using Lucene taxonomy.
 */
public class TestHierarchicalFacets {

    private static final String NS = "http://example.org/";
    private static final String FIELD_NS = "urn:jena:lucene:field#";
    private static final Node BOREHOLE_CLASS = NodeFactory.createURI(NS + "Borehole");
    private static final Node NAME_PRED = NodeFactory.createURI(NS + "name");
    private static final Node TYPE_PRED = NodeFactory.createURI(NS + "type");
    private static final Node SUBTYPE_PRED = NodeFactory.createURI(NS + "subtype");
    private static final Node STATE_PRED = NodeFactory.createURI(NS + "state");

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @Before
    public void setUp() {
        Node nameIRI = NodeFactory.createURI(FIELD_NS + "name");
        Node typeIRI = NodeFactory.createURI(FIELD_NS + "type");
        Node subtypeIRI = NodeFactory.createURI(FIELD_NS + "subtype");
        Node stateIRI = NodeFactory.createURI(FIELD_NS + "state");

        FieldDef nameField = new FieldDef("name", FieldType.TEXT, null, null,
            true, true, false, false, false, true,
            Collections.singleton(NAME_PRED), null, nameIRI);

        FieldDef typeField = new FieldDef("type", FieldType.KEYWORD, null, null,
            true, true, true, false, false, false,
            Collections.singleton(TYPE_PRED), null, typeIRI);

        FieldDef subtypeField = new FieldDef("subtype", FieldType.KEYWORD, null, null,
            true, true, true, false, false, false,
            Collections.singleton(SUBTYPE_PRED), null, subtypeIRI);

        FieldDef stateField = new FieldDef("state", FieldType.KEYWORD, null, null,
            true, true, true, false, false, false,
            Collections.singleton(STATE_PRED), null, stateIRI);

        // Hierarchy: type → subtype
        HierarchyDef typeHierarchy = new HierarchyDef("type_subtype",
            Arrays.asList(typeField, subtypeField));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "BoreholeShape"),
            Collections.singleton(BOREHOLE_CLASS),
            "uri", "docType",
            Arrays.asList(nameField, typeField, subtypeField, stateField),
            Collections.singletonList(typeHierarchy));

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        ByteBuffersDirectory taxoDir = new ByteBuffersDirectory();
        textIndex = new ShaclTextIndexLucene(dir, taxoDir, config);

        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(
            baseDs.asDatasetGraph(), textIndex, mapping);

        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);

        loadTestData();
    }

    private void loadTestData() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();

            addBorehole(model, "bh1", "Alpha Well", "Water", "Shallow", "WA");
            addBorehole(model, "bh2", "Beta Well", "Water", "Deep", "WA");
            addBorehole(model, "bh3", "Gamma Bore", "Water", "Shallow", "NSW");
            addBorehole(model, "bh4", "Delta Hole", "Mineral", "Gold", "WA");
            addBorehole(model, "bh5", "Epsilon Hole", "Mineral", "Gold", "NSW");
            addBorehole(model, "bh6", "Zeta Hole", "Mineral", "Iron", "WA");
            addBorehole(model, "bh7", "Eta Core", "Gas", "Shallow", "QLD");

            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void addBorehole(Model model, String id, String name, String type,
            String subtype, String state) {
        Resource bh = ResourceFactory.createResource(NS + id);
        model.add(bh, RDF.type, ResourceFactory.createResource(NS + "Borehole"));
        model.add(bh, ResourceFactory.createProperty(NS + "name"),
            ResourceFactory.createPlainLiteral(name));
        model.add(bh, ResourceFactory.createProperty(NS + "type"),
            ResourceFactory.createPlainLiteral(type));
        model.add(bh, ResourceFactory.createProperty(NS + "subtype"),
            ResourceFactory.createPlainLiteral(subtype));
        model.add(bh, ResourceFactory.createProperty(NS + "state"),
            ResourceFactory.createPlainLiteral(state));
    }

    @After
    public void tearDown() {
        if (dataset != null) dataset.close();
    }

    @Test
    public void testHierarchyDefined() {
        assertTrue("Index should have hierarchies", textIndex.hasHierarchies());
        assertTrue("type_subtype should be a hierarchy dimension",
            textIndex.getHierarchyDimensions().contains("type_subtype"));
    }

    @Test
    public void testTopLevelHierarchyFacets() {
        // Get top-level facets for the hierarchy dimension
        Map<String, List<FacetValue>> counts = textIndex.getFacetCounts(
            null, null, Collections.singletonList("type_subtype"), 10, 0);

        assertTrue("Should have type_subtype facets", counts.containsKey("type_subtype"));
        List<FacetValue> facets = counts.get("type_subtype");
        assertFalse("Should have facet values", facets.isEmpty());

        // Should have 3 top-level types: Water(3), Mineral(3), Gas(1)
        Map<String, Long> facetMap = toMap(facets);
        assertEquals("Water count", Long.valueOf(3), facetMap.get("Water"));
        assertEquals("Mineral count", Long.valueOf(3), facetMap.get("Mineral"));
        assertEquals("Gas count", Long.valueOf(1), facetMap.get("Gas"));
    }

    @Test
    public void testDrillDownHierarchyFacets() {
        // Drill down into "Water" to see subtypes
        Map<String, String[]> drillDown = new HashMap<>();
        drillDown.put("type_subtype", new String[]{"Water"});

        Map<String, List<FacetValue>> counts = textIndex.getFacetCounts(
            null, null, Collections.singletonList("type_subtype"), 10, 0, drillDown);

        assertTrue("Should have type_subtype facets", counts.containsKey("type_subtype"));
        List<FacetValue> facets = counts.get("type_subtype");
        assertFalse("Should have drill-down values", facets.isEmpty());

        // Under Water: Shallow(2), Deep(1)
        Map<String, Long> facetMap = toMap(facets);
        assertEquals("Shallow count under Water", Long.valueOf(2), facetMap.get("Shallow"));
        assertEquals("Deep count under Water", Long.valueOf(1), facetMap.get("Deep"));
    }

    @Test
    public void testDrillDownMineralGold() {
        // Drill down into "Mineral" to see subtypes
        Map<String, String[]> drillDown = new HashMap<>();
        drillDown.put("type_subtype", new String[]{"Mineral"});

        Map<String, List<FacetValue>> counts = textIndex.getFacetCounts(
            null, null, Collections.singletonList("type_subtype"), 10, 0, drillDown);

        List<FacetValue> facets = counts.get("type_subtype");
        Map<String, Long> facetMap = toMap(facets);
        assertEquals("Gold count under Mineral", Long.valueOf(2), facetMap.get("Gold"));
        assertEquals("Iron count under Mineral", Long.valueOf(1), facetMap.get("Iron"));
    }

    @Test
    public void testHierarchyWithTextSearch() {
        // Combine text search with hierarchy facets
        Map<String, List<FacetValue>> counts = textIndex.getFacetCounts(
            "well", null, Collections.singletonList("type_subtype"), 10, 0);

        List<FacetValue> facets = counts.get("type_subtype");
        assertNotNull("Should have facets for 'well' search", facets);
        // "Alpha Well" and "Beta Well" are both Water type
        Map<String, Long> facetMap = toMap(facets);
        assertEquals("Water count for 'well' search", Long.valueOf(2), facetMap.get("Water"));
        assertFalse("Should not have Mineral for 'well' search", facetMap.containsKey("Mineral"));
    }

    @Test
    public void testFlatAndHierarchyCombined() {
        // Request both flat (state) and hierarchical (type_subtype) facets
        Map<String, List<FacetValue>> counts = textIndex.getFacetCounts(
            null, null, Arrays.asList("state", "type_subtype"), 10, 0);

        // Flat facets should work
        List<FacetValue> stateFacets = counts.get("state");
        assertNotNull("Should have state facets", stateFacets);
        Map<String, Long> stateMap = toMap(stateFacets);
        assertEquals("WA count", Long.valueOf(4), stateMap.get("WA"));
        assertEquals("NSW count", Long.valueOf(2), stateMap.get("NSW"));
        assertEquals("QLD count", Long.valueOf(1), stateMap.get("QLD"));

        // Hierarchy facets should also work
        List<FacetValue> hierFacets = counts.get("type_subtype");
        assertNotNull("Should have type_subtype facets", hierFacets);
        Map<String, Long> hierMap = toMap(hierFacets);
        assertEquals("Water count", Long.valueOf(3), hierMap.get("Water"));
    }

    @Test
    public void testHierarchyDefDataModel() {
        ShaclIndexMapping mapping = textIndex.getShaclMapping();

        // findHierarchy by dimension name
        HierarchyDef h = mapping.findHierarchy("type_subtype");
        assertNotNull("Should find type_subtype hierarchy", h);
        assertEquals(2, h.getDepth());
        assertEquals("type", h.getLevel(0).getFieldName());
        assertEquals("subtype", h.getLevel(1).getFieldName());

        // findHierarchyForField
        HierarchyDef byField = mapping.findHierarchyForField(
            "urn:jena:lucene:field#type");
        assertNotNull("Should find hierarchy for type field", byField);
        assertEquals("type_subtype", byField.getDimensionName());
    }

    @Test
    public void testMinCountFilter() {
        // Top-level with minCount=2 should exclude Gas(1)
        Map<String, List<FacetValue>> counts = textIndex.getFacetCounts(
            null, null, Collections.singletonList("type_subtype"), 10, 2);

        List<FacetValue> facets = counts.get("type_subtype");
        Map<String, Long> facetMap = toMap(facets);
        assertTrue("Should have Water", facetMap.containsKey("Water"));
        assertTrue("Should have Mineral", facetMap.containsKey("Mineral"));
        assertFalse("Should not have Gas (count=1 < minCount=2)", facetMap.containsKey("Gas"));
    }

    @Test
    public void testDirectHierarchyExactFiltersCompileToHierarchyPath() {
        CqlExpression cql = CqlParser.parse("""
            {"op":"and","args":[
              {"op":"=","args":[{"property":"urn:jena:lucene:field#type"},"Mineral"]},
              {"op":"=","args":[{"property":"urn:jena:lucene:field#subtype"},"Gold"]}
            ]}
            """);

        List<TextHit> results = textIndex.queryWithCql(null, null, cql, null, null, null, 10, null);
        Set<String> uris = new LinkedHashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }
        assertEquals(Set.of(NS + "bh4", NS + "bh5"), uris);
    }

    private static Map<String, Long> toMap(List<FacetValue> facets) {
        Map<String, Long> map = new HashMap<>();
        if (facets != null) {
            for (FacetValue fv : facets) {
                map.put(fv.getValue(), fv.getCount());
            }
        }
        return map;
    }
}
