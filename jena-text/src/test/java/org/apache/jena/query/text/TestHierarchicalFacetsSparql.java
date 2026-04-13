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
import org.apache.jena.query.*;
import org.apache.jena.query.text.ShaclIndexMapping.*;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for hierarchical facets via SPARQL luc:facet property function.
 */
public class TestHierarchicalFacetsSparql {

    private static final String NS = "http://example.org/";
    private static final String FIELD_NS = "urn:jena:lucene:field#";
    private static final Node BOREHOLE_CLASS = NodeFactory.createURI(NS + "Borehole");
    private static final Node NAME_PRED = NodeFactory.createURI(NS + "name");
    private static final Node TYPE_PRED = NodeFactory.createURI(NS + "type");
    private static final Node SUBTYPE_PRED = NodeFactory.createURI(NS + "subtype");

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @Before
    public void setUp() {
        Node nameIRI = NodeFactory.createURI(FIELD_NS + "name");
        Node typeIRI = NodeFactory.createURI(FIELD_NS + "type");
        Node subtypeIRI = NodeFactory.createURI(FIELD_NS + "subtype");

        FieldDef nameField = new FieldDef("name", FieldType.TEXT, null, null,
            true, true, false, false, false, true, nameIRI);

        FieldDef typeField = new FieldDef("type", FieldType.KEYWORD, null, null,
            true, true, true, false, false, false, typeIRI);

        FieldDef subtypeField = new FieldDef("subtype", FieldType.KEYWORD, null, null,
            true, true, true, false, false, false, subtypeIRI);

        HierarchyDef typeHierarchy = new HierarchyDef("type_subtype",
            Arrays.asList(typeField, subtypeField));

        List<FieldOccurrence> rootOccurrences = Arrays.asList(
            occurrence(nameField, PathFactory.pathLink(NAME_PRED), Collections.singleton(NAME_PRED)),
            occurrence(typeField, PathFactory.pathLink(TYPE_PRED), Collections.singleton(TYPE_PRED)),
            occurrence(subtypeField, PathFactory.pathLink(SUBTYPE_PRED), Collections.singleton(SUBTYPE_PRED)));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "BoreholeShape"),
            Collections.singleton(BOREHOLE_CLASS),
            "uri", "docType",
            Arrays.asList(nameField, typeField, subtypeField),
            rootOccurrences,
            Collections.singletonList(typeHierarchy),
            Collections.emptyList());

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

        // Register in context for property functions
        Context ctx = dataset.getContext();
        TextIndexRegistry registry = new TextIndexRegistry();
        registry.register("default", textIndex);
        ctx.put(TextQuery.textIndexRegistry, registry);

        loadTestData();
    }

    private void loadTestData() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            addBorehole(model, "bh1", "Alpha Well", "Water", "Shallow");
            addBorehole(model, "bh2", "Beta Well", "Water", "Deep");
            addBorehole(model, "bh3", "Gamma Bore", "Mineral", "Gold");
            addBorehole(model, "bh4", "Delta Bore", "Mineral", "Gold");
            addBorehole(model, "bh5", "Epsilon Bore", "Mineral", "Iron");
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void addBorehole(Model model, String id, String name, String type, String subtype) {
        Resource bh = ResourceFactory.createResource(NS + id);
        model.add(bh, RDF.type, ResourceFactory.createResource(NS + "Borehole"));
        model.add(bh, ResourceFactory.createProperty(NS + "name"),
            ResourceFactory.createPlainLiteral(name));
        model.add(bh, ResourceFactory.createProperty(NS + "type"),
            ResourceFactory.createPlainLiteral(type));
        model.add(bh, ResourceFactory.createProperty(NS + "subtype"),
            ResourceFactory.createPlainLiteral(subtype));
    }

    private static FieldOccurrence occurrence(FieldDef field, Path path, Set<Node> predicates) {
        return new FieldOccurrence(
            field,
            path,
            ShaclIndexAssembler.extractPathVariants(path),
            predicates,
            null, null, null, null);
    }

    @After
    public void tearDown() {
        if (dataset != null) dataset.close();
    }

    @Test
    public void testHierarchyTopLevelViaSparql() {
        // Request facets on a hierarchy level field IRI — auto-resolves to the dimension.
        // Requesting the type field (level 0) returns top-level hierarchy values.
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?field ?value ?low ?high ?count WHERE {\n"
            + "  (?field ?value ?low ?high ?count) luc:facet (\"default\" \"default\" \"*\" '[\"urn:jena:lucene:field#type\"]' \"\" 10 0)\n"
            + "}";

        dataset.begin(ReadWrite.READ);
        try {
            ResultSet rs = QueryExecutionFactory.create(sparql, dataset).execSelect();
            Map<String, Long> facets = new HashMap<>();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                String value = qs.getLiteral("value").getString();
                long count = qs.getLiteral("count").getLong();
                facets.put(value, count);
            }
            assertEquals("Water count", Long.valueOf(2), facets.get("Water"));
            assertEquals("Mineral count", Long.valueOf(3), facets.get("Mineral"));
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testDrillDownViaCqlFilter() {
        // Drill into "Water" children via CQL = filter on type field.
        // Requesting facets on subtype (child level) with type=Water filter
        // auto-detects hierarchy membership and returns subtype children under Water.
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?field ?value ?low ?high ?count WHERE {\n"
            + "  (?field ?value ?low ?high ?count) luc:facet (\"default\" \"default\" \"*\""
            + " '[\"urn:jena:lucene:field#subtype\"]'"
            + " '{\"op\":\"=\",\"args\":[{\"property\":\"urn:jena:lucene:field#type\"},\"Water\"]}'"
            + " 10 0)\n"
            + "}";

        dataset.begin(ReadWrite.READ);
        try {
            ResultSet rs = QueryExecutionFactory.create(sparql, dataset).execSelect();
            Map<String, Long> facets = new HashMap<>();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                String value = qs.getLiteral("value").getString();
                long count = qs.getLiteral("count").getLong();
                facets.put(value, count);
            }
            assertEquals("Shallow count under Water", Long.valueOf(1), facets.get("Shallow"));
            assertEquals("Deep count under Water", Long.valueOf(1), facets.get("Deep"));
            assertFalse("Should not have Gold under Water", facets.containsKey("Gold"));
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFlatFacetsStillWorkWithHierarchy() {
        // Flat facets (type, subtype) should still work alongside hierarchy
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?field ?value ?low ?high ?count WHERE {\n"
            + "  (?field ?value ?low ?high ?count) luc:facet (\"default\" \"default\" \"*\" '[\"urn:jena:lucene:field#type\"]' \"\" 10 0)\n"
            + "}";

        dataset.begin(ReadWrite.READ);
        try {
            ResultSet rs = QueryExecutionFactory.create(sparql, dataset).execSelect();
            Map<String, Long> facets = new HashMap<>();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                String value = qs.getLiteral("value").getString();
                long count = qs.getLiteral("count").getLong();
                facets.put(value, count);
            }
            assertEquals("Water count via flat facet", Long.valueOf(2), facets.get("Water"));
            assertEquals("Mineral count via flat facet", Long.valueOf(3), facets.get("Mineral"));
        } finally {
            dataset.end();
        }
    }
}
