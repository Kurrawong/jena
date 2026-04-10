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
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.HierarchyDef;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.analyzer.EdgeNGramAnalyzer;
import org.apache.jena.query.text.analyzer.LowerCaseKeywordAnalyzer;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the nested child-record model for hierarchical identifier facets.
 */
public class TestNestedHierarchicalFacets {

    private static final String NS = "http://example.org/";
    private static final String SCHEMA = "https://schema.org/";
    private static final String FIELD_NS = "urn:jena:lucene:field#";
    private static final String IDENTIFIER_DIM = "identifierType_identifierValueExact";
    private static final String STATE_COMMODITY_DIM = "state_commodity";

    private static final Node BOREHOLE_CLASS = NodeFactory.createURI(NS + "Borehole");
    private static final Node LABEL_PRED = RDFS.label.asNode();
    private static final Node IDENTIFIER_PRED = NodeFactory.createURI(SCHEMA + "identifier");
    private static final Node IDENTIFIER_TYPE_PRED = NodeFactory.createURI(SCHEMA + "propertyID");
    private static final Node IDENTIFIER_VALUE_PRED = NodeFactory.createURI(SCHEMA + "value");
    private static final Node STATE_PRED = NodeFactory.createURI(NS + "state");
    private static final Node COMMODITY_PRED = NodeFactory.createURI(NS + "commodity");
    private static final String IDENTIFIER_SCOPE = PathFactory.pathLink(IDENTIFIER_PRED).toString();

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @Before
    public void setUp() {
        TextQuery.init();

        Node titleIRI = NodeFactory.createURI(FIELD_NS + "title");
        Node stateIRI = NodeFactory.createURI(FIELD_NS + "state");
        Node commodityIRI = NodeFactory.createURI(FIELD_NS + "commodity");
        Node typeIRI = NodeFactory.createURI(FIELD_NS + "identifierType");
        Node valueExactIRI = NodeFactory.createURI(FIELD_NS + "identifierValueExact");
        Node valueTextIRI = NodeFactory.createURI(FIELD_NS + "identifierValueText");

        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null, null,
            true, true, false, false, false, true, false, titleIRI);

        FieldDef stateField = new FieldDef("state", FieldType.KEYWORD, null, null,
            true, true, true, false, false, false, false, stateIRI);

        FieldDef commodityField = new FieldDef("commodity", FieldType.KEYWORD, null, null,
            true, true, true, false, true, false, false, commodityIRI);

        FieldDef typeField = new FieldDef("identifierType", FieldType.KEYWORD, null, null,
            true, true, true, false, true, false, false, typeIRI);

        FieldDef valueExactField = new FieldDef("identifierValueExact", FieldType.KEYWORD, null, null,
            true, true, true, false, true, false, false, valueExactIRI);

        FieldDef valueTextField = new FieldDef("identifierValueText", FieldType.TEXT,
            new EdgeNGramAnalyzer(1, 20), new LowerCaseKeywordAnalyzer(),
            true, true, false, false, true, false, false, valueTextIRI);

        HierarchyDef stateCommodityHierarchy = new HierarchyDef(STATE_COMMODITY_DIM,
            Arrays.asList(stateField, commodityField));

        HierarchyDef identifierHierarchy = new HierarchyDef(IDENTIFIER_DIM,
            Arrays.asList(typeField, valueExactField));

        List<FieldOccurrence> rootOccurrences = List.of(
            rootOccurrence(titleField, LABEL_PRED),
            rootOccurrence(stateField, STATE_PRED),
            rootOccurrence(commodityField, COMMODITY_PRED));

        List<FieldOccurrence> identifierOccurrences = List.of(
            nestedOccurrence(typeField, IDENTIFIER_TYPE_PRED, IDENTIFIER_SCOPE),
            nestedOccurrence(valueExactField, IDENTIFIER_VALUE_PRED, IDENTIFIER_SCOPE),
            nestedOccurrence(valueTextField, IDENTIFIER_VALUE_PRED, IDENTIFIER_SCOPE));

        NestedDef identifiers = new NestedDef(
            IDENTIFIER_SCOPE,
            PathFactory.pathLink(IDENTIFIER_PRED),
            List.of(new JoinStep(IDENTIFIER_PRED, false)),
            Collections.singleton(IDENTIFIER_PRED),
            identifierOccurrences,
            Collections.singletonList(identifierHierarchy));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "BoreholeShape"),
            Collections.singleton(BOREHOLE_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, stateField, commodityField, typeField, valueExactField, valueTextField),
            rootOccurrences,
            Collections.singletonList(stateCommodityHierarchy),
            Collections.singletonList(identifiers));

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
        loadData();
    }

    private void loadData() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();

            addBorehole(model, "bh1", "Alpha Bore",
                "WA", new String[] {"Gold"},
                new String[][] {
                    {"Company", "Acme"},
                    {"HoleNumber", "8412"}
                });
            addBorehole(model, "bh2", "Beta Bore",
                "WA", new String[] {"Iron"},
                new String[][] {
                    {"Company", "8412"},
                    {"HoleNumber", "B-17"}
                });
            addBorehole(model, "bh3", "Gamma Bore",
                "QLD", new String[] {"Copper"},
                new String[][] {
                    {"Company", "Rio"},
                    {"HoleNumber", "9999"}
                });

            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void addBorehole(Model model, String id, String label, String state,
            String[] commodities, String[][] identifiers) {
        Resource borehole = ResourceFactory.createResource(NS + id);
        model.add(borehole, RDF.type, ResourceFactory.createResource(NS + "Borehole"));
        model.add(borehole, RDFS.label, label);
        model.add(borehole, ResourceFactory.createProperty(NS + "state"), state);
        for (String commodity : commodities) {
            model.add(borehole, ResourceFactory.createProperty(NS + "commodity"), commodity);
        }

        for (int i = 0; i < identifiers.length; i++) {
            Resource identifier = ResourceFactory.createResource(NS + id + "-identifier-" + i);
            model.add(borehole, ResourceFactory.createProperty(SCHEMA + "identifier"), identifier);
            model.add(identifier, ResourceFactory.createProperty(SCHEMA + "propertyID"), identifiers[i][0]);
            model.add(identifier, ResourceFactory.createProperty(SCHEMA + "value"), identifiers[i][1]);
        }
    }

    private static FieldOccurrence rootOccurrence(FieldDef field, Node predicate) {
        return occurrence(field, predicate, null);
    }

    private static FieldOccurrence nestedOccurrence(FieldDef field, Node predicate, String nestedName) {
        return occurrence(field, predicate, nestedName);
    }

    private static FieldOccurrence occurrence(FieldDef field, Node predicate, String nestedName) {
        return new FieldOccurrence(
            field,
            PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            Collections.singleton(predicate),
            null,
            null,
            null,
            nestedName);
    }

    @After
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    @Test
    public void testNestedHierarchyTopLevelCounts() {
        Map<String, List<FacetValue>> counts = textIndex.getFacetCounts(
            null, null, Collections.singletonList(IDENTIFIER_DIM), 10, 0);

        Map<String, Long> values = toFacetMap(counts.get(IDENTIFIER_DIM));
        assertEquals(Long.valueOf(3), values.get("Company"));
        assertEquals(Long.valueOf(3), values.get("HoleNumber"));
    }

    @Test
    public void testNestedHierarchyDrillDownCountsStayCorrelated() {
        Map<String, String[]> drillDown = new HashMap<>();
        drillDown.put(IDENTIFIER_DIM, new String[] {"Company"});

        Map<String, List<FacetValue>> counts = textIndex.getFacetCounts(
            null, null, Collections.singletonList(IDENTIFIER_DIM), 10, 0, drillDown);

        Map<String, Long> values = toFacetMap(counts.get(IDENTIFIER_DIM));
        assertEquals(Long.valueOf(1), values.get("Acme"));
        assertEquals(Long.valueOf(1), values.get("8412"));
        assertEquals(Long.valueOf(1), values.get("Rio"));
        assertFalse(values.containsKey("B-17"));
        assertFalse(values.containsKey("9999"));
    }

    @Test
    public void testExactHierarchyFiltersUseCorrelatedPathQuery() {
        CqlExpression cql = CqlParser.parse("""
            {"op":"and","args":[
              {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierType"},"Company"]},
              {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierValueExact"},"8412"]}
            ]}
            """);

        List<TextHit> results = textIndex.queryWithCql(null, null, cql, null, null, null, 10, null);
        Set<String> uris = toSubjectSet(results);

        assertEquals(Set.of(NS + "bh2"), uris);
    }

    @Test
    public void testNestedTextFieldStillSupportsPrefixSearch() {
        List<TextHit> results = textIndex.queryWithCql(
            Collections.singletonList(FIELD_NS + "identifierValueText"),
            "84", null, null, null, null, 10, null);

        Set<String> uris = toSubjectSet(results);
        assertEquals(Set.of(NS + "bh1", NS + "bh2"), uris);
    }

    @Test
    public void testPhase1NestedTextAndSiblingFilterStillCrossCorrelate() {
        CqlExpression cql = CqlParser.parse("""
            {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierType"},"HoleNumber"]}
            """);

        List<TextHit> results = textIndex.queryWithCql(
            Collections.singletonList(FIELD_NS + "identifierValueText"),
            "84", cql, null, null, null, 10, null);

        // Phase 1 limitation: nested text fields are still flattened onto the parent document.
        // The level-0 identifierType filter can narrow by hierarchy path, but it does not constrain
        // which child record contributed the text match. When block join lands, invert this test.
        assertEquals(Set.of(NS + "bh1", NS + "bh2"), toSubjectSet(results));
    }

    @Test
    public void testSingleLevelTopFilterStillUsesFlattenedParentField() {
        CqlExpression cql = CqlParser.parse("""
            {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierType"},"Company"]}
            """);

        List<TextHit> results = textIndex.queryWithCql(null, null, cql, null, null, null, 10, null);
        assertEquals(Set.of(NS + "bh1", NS + "bh2", NS + "bh3"), toSubjectSet(results));
    }

    @Test
    public void testSingleLevelLeafFilterRemainsParentFlattenedUntilBlockJoin() {
        CqlExpression cql = CqlParser.parse("""
            {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierValueExact"},"8412"]}
            """);

        List<TextHit> results = textIndex.queryWithCql(null, null, cql, null, null, null, 10, null);
        // Phase 1 behavior: nested keyword fields are still flattened on the parent document,
        // so a lone leaf-level filter matches any entity carrying that identifier value.
        assertEquals(Set.of(NS + "bh1", NS + "bh2"), toSubjectSet(results));
    }

    @Test
    public void testChildValueUpdateReindexesParentDocument() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource identifier = ResourceFactory.createResource(NS + "bh1-identifier-0");
            model.removeAll(identifier, ResourceFactory.createProperty(SCHEMA + "value"), null);
            model.add(identifier, ResourceFactory.createProperty(SCHEMA + "value"), "8412");
            dataset.commit();
        } finally {
            dataset.end();
        }

        CqlExpression cql = CqlParser.parse("""
            {"op":"and","args":[
              {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierType"},"Company"]},
              {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierValueExact"},"8412"]}
            ]}
            """);

        List<TextHit> results = textIndex.queryWithCql(null, null, cql, null, null, null, 10, null);
        Set<String> uris = toSubjectSet(results);
        assertEquals(Set.of(NS + "bh1", NS + "bh2"), uris);
    }

    @Test
    public void testMultiValuedNestedRecordProducesMultipleCorrelatedPaths() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource borehole = ResourceFactory.createResource(NS + "bh4");
            Resource identifier = ResourceFactory.createResource(NS + "bh4-identifier-0");
            model.add(borehole, RDF.type, ResourceFactory.createResource(NS + "Borehole"));
            model.add(borehole, RDFS.label, "Delta Bore");
            model.add(borehole, ResourceFactory.createProperty(SCHEMA + "identifier"), identifier);
            model.add(identifier, ResourceFactory.createProperty(SCHEMA + "propertyID"), "Company");
            model.add(identifier, ResourceFactory.createProperty(SCHEMA + "propertyID"), "OldCompany");
            model.add(identifier, ResourceFactory.createProperty(SCHEMA + "value"), "ArchiveCo");
            dataset.commit();
        } finally {
            dataset.end();
        }

        Map<String, String[]> drillDown = new HashMap<>();
        drillDown.put(IDENTIFIER_DIM, new String[] {"Company"});
        Map<String, List<FacetValue>> companyCounts = textIndex.getFacetCounts(
            null, null, Collections.singletonList(IDENTIFIER_DIM), 20, 0, drillDown);
        assertEquals(Long.valueOf(1), toFacetMap(companyCounts.get(IDENTIFIER_DIM)).get("ArchiveCo"));

        drillDown.put(IDENTIFIER_DIM, new String[] {"OldCompany"});
        Map<String, List<FacetValue>> oldCompanyCounts = textIndex.getFacetCounts(
            null, null, Collections.singletonList(IDENTIFIER_DIM), 20, 0, drillDown);
        assertEquals(Long.valueOf(1), toFacetMap(oldCompanyCounts.get(IDENTIFIER_DIM)).get("ArchiveCo"));
    }

    @Test
    public void testDirectAndNestedHierarchiesCoexistOnSameShape() {
        Map<String, String[]> drillDown = new HashMap<>();
        drillDown.put(STATE_COMMODITY_DIM, new String[] {"WA"});

        Map<String, List<FacetValue>> counts = textIndex.getFacetCounts(
            null, null, Arrays.asList(STATE_COMMODITY_DIM, IDENTIFIER_DIM), 20, 0, drillDown);

        Map<String, Long> commodityCounts = toFacetMap(counts.get(STATE_COMMODITY_DIM));
        assertEquals(Long.valueOf(1), commodityCounts.get("Gold"));
        assertEquals(Long.valueOf(1), commodityCounts.get("Iron"));
        assertFalse(commodityCounts.containsKey("Copper"));

        Map<String, Long> identifierCounts = toFacetMap(counts.get(IDENTIFIER_DIM));
        assertEquals(Long.valueOf(3), identifierCounts.get("Company"));
        assertEquals(Long.valueOf(3), identifierCounts.get("HoleNumber"));
    }

    private Map<String, Long> toFacetMap(List<FacetValue> facets) {
        Map<String, Long> map = new HashMap<>();
        assertNotNull(facets);
        for (FacetValue facet : facets) {
            map.put(facet.getValue(), facet.getCount());
        }
        return map;
    }

    private Set<String> toSubjectSet(List<TextHit> hits) {
        Set<String> uris = new LinkedHashSet<>();
        for (TextHit hit : hits) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }
}
