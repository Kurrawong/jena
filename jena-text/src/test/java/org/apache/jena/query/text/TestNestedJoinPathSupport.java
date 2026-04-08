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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
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
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.Test;

public class TestNestedJoinPathSupport {

    private static final String NS = "http://example.org/";
    private static final String SCHEMA = "https://schema.org/";
    private static final String FIELD_NS = "urn:jena:lucene:field#";
    private static final String IDENTIFIER_DIM = "identifierType_identifierValueExact";

    private static final Node BOREHOLE_CLASS = NodeFactory.createURI(NS + "Borehole");
    private static final Node LABEL_PRED = RDFS.label.asNode();
    private static final Node IDENTIFIER_TYPE_PRED = NodeFactory.createURI(SCHEMA + "propertyID");
    private static final Node IDENTIFIER_VALUE_PRED = NodeFactory.createURI(SCHEMA + "value");
    private static final Node IDENTIFIER_ABOUT_PRED = NodeFactory.createURI(NS + "identifierAbout");
    private static final Node IDENTIFIER_LINK_PRED = NodeFactory.createURI(NS + "hasIdentifierLink");
    private static final Node IDENTIFIER_NODE_PRED = NodeFactory.createURI(NS + "identifierNode");

    @Test
    public void testInverseJoinPathChildUpdateReindexesParent() {
        Path joinPath = PathFactory.pathInverse(PathFactory.pathLink(IDENTIFIER_ABOUT_PRED));
        List<JoinStep> joinSteps = Collections.singletonList(new JoinStep(IDENTIFIER_ABOUT_PRED, true));

        try (Harness harness = createHarness(joinPath, joinSteps)) {
            harness.dataset.begin(ReadWrite.WRITE);
            try {
                Model model = harness.dataset.getDefaultModel();
                Resource borehole = ResourceFactory.createResource(NS + "bh1");
                Resource identifier = ResourceFactory.createResource(NS + "bh1-id");

                model.add(borehole, RDF.type, ResourceFactory.createResource(NS + "Borehole"));
                model.add(borehole, RDFS.label, "Alpha Bore");
                model.add(identifier, ResourceFactory.createProperty(NS + "identifierAbout"), borehole);
                model.add(identifier, ResourceFactory.createProperty(SCHEMA + "propertyID"), "Company");
                model.add(identifier, ResourceFactory.createProperty(SCHEMA + "value"), "Acme");
                harness.dataset.commit();
            } finally {
                harness.dataset.end();
            }

            assertEquals(Set.of(NS + "bh1"), queryCompanyIdentifier(harness.textIndex, "Acme"));

            harness.dataset.begin(ReadWrite.WRITE);
            try {
                Model model = harness.dataset.getDefaultModel();
                Resource identifier = ResourceFactory.createResource(NS + "bh1-id");
                model.removeAll(identifier, ResourceFactory.createProperty(SCHEMA + "value"), null);
                model.add(identifier, ResourceFactory.createProperty(SCHEMA + "value"), "Rio");
                harness.dataset.commit();
            } finally {
                harness.dataset.end();
            }

            assertEquals(Set.of(NS + "bh1"), queryCompanyIdentifier(harness.textIndex, "Rio"));
            assertEquals(Collections.emptySet(), queryCompanyIdentifier(harness.textIndex, "Acme"));
        }
    }

    @Test
    public void testSequenceJoinPathChildUpdateReindexesParent() {
        Path joinPath = PathFactory.pathSeq(
            PathFactory.pathLink(IDENTIFIER_LINK_PRED),
            PathFactory.pathLink(IDENTIFIER_NODE_PRED));
        List<JoinStep> joinSteps = Arrays.asList(
            new JoinStep(IDENTIFIER_LINK_PRED, false),
            new JoinStep(IDENTIFIER_NODE_PRED, false));

        try (Harness harness = createHarness(joinPath, joinSteps)) {
            harness.dataset.begin(ReadWrite.WRITE);
            try {
                Model model = harness.dataset.getDefaultModel();
                Resource borehole = ResourceFactory.createResource(NS + "bh1");
                Resource link = ResourceFactory.createResource(NS + "bh1-link");
                Resource identifier = ResourceFactory.createResource(NS + "bh1-id");

                model.add(borehole, RDF.type, ResourceFactory.createResource(NS + "Borehole"));
                model.add(borehole, RDFS.label, "Alpha Bore");
                model.add(borehole, ResourceFactory.createProperty(NS + "hasIdentifierLink"), link);
                model.add(link, ResourceFactory.createProperty(NS + "identifierNode"), identifier);
                model.add(identifier, ResourceFactory.createProperty(SCHEMA + "propertyID"), "Company");
                model.add(identifier, ResourceFactory.createProperty(SCHEMA + "value"), "Acme");
                harness.dataset.commit();
            } finally {
                harness.dataset.end();
            }

            assertEquals(Set.of(NS + "bh1"), queryCompanyIdentifier(harness.textIndex, "Acme"));

            harness.dataset.begin(ReadWrite.WRITE);
            try {
                Model model = harness.dataset.getDefaultModel();
                Resource identifier = ResourceFactory.createResource(NS + "bh1-id");
                model.removeAll(identifier, ResourceFactory.createProperty(SCHEMA + "value"), null);
                model.add(identifier, ResourceFactory.createProperty(SCHEMA + "value"), "Rio");
                harness.dataset.commit();
            } finally {
                harness.dataset.end();
            }

            assertEquals(Set.of(NS + "bh1"), queryCompanyIdentifier(harness.textIndex, "Rio"));
            assertEquals(Collections.emptySet(), queryCompanyIdentifier(harness.textIndex, "Acme"));
        }
    }

    @Test
    public void testSequenceJoinPredicateUpdateReindexesParent() {
        Path joinPath = PathFactory.pathSeq(
            PathFactory.pathLink(IDENTIFIER_LINK_PRED),
            PathFactory.pathLink(IDENTIFIER_NODE_PRED));
        List<JoinStep> joinSteps = Arrays.asList(
            new JoinStep(IDENTIFIER_LINK_PRED, false),
            new JoinStep(IDENTIFIER_NODE_PRED, false));

        try (Harness harness = createHarness(joinPath, joinSteps)) {
            harness.dataset.begin(ReadWrite.WRITE);
            try {
                Model model = harness.dataset.getDefaultModel();
                Resource borehole = ResourceFactory.createResource(NS + "bh1");
                Resource link = ResourceFactory.createResource(NS + "bh1-link");
                Resource identifier = ResourceFactory.createResource(NS + "bh1-id");

                model.add(borehole, RDF.type, ResourceFactory.createResource(NS + "Borehole"));
                model.add(borehole, RDFS.label, "Alpha Bore");
                model.add(borehole, ResourceFactory.createProperty(NS + "hasIdentifierLink"), link);
                model.add(identifier, ResourceFactory.createProperty(SCHEMA + "propertyID"), "Company");
                model.add(identifier, ResourceFactory.createProperty(SCHEMA + "value"), "Acme");
                harness.dataset.commit();
            } finally {
                harness.dataset.end();
            }

            assertEquals(Collections.emptySet(), queryCompanyIdentifier(harness.textIndex, "Acme"));

            harness.dataset.begin(ReadWrite.WRITE);
            try {
                Model model = harness.dataset.getDefaultModel();
                Resource link = ResourceFactory.createResource(NS + "bh1-link");
                Resource identifier = ResourceFactory.createResource(NS + "bh1-id");
                model.add(link, ResourceFactory.createProperty(NS + "identifierNode"), identifier);
                harness.dataset.commit();
            } finally {
                harness.dataset.end();
            }

            assertEquals(Set.of(NS + "bh1"), queryCompanyIdentifier(harness.textIndex, "Acme"));

            harness.dataset.begin(ReadWrite.WRITE);
            try {
                Model model = harness.dataset.getDefaultModel();
                Resource link = ResourceFactory.createResource(NS + "bh1-link");
                Resource identifier = ResourceFactory.createResource(NS + "bh1-id");
                model.remove(link, ResourceFactory.createProperty(NS + "identifierNode"), identifier);
                harness.dataset.commit();
            } finally {
                harness.dataset.end();
            }

            assertEquals(Collections.emptySet(), queryCompanyIdentifier(harness.textIndex, "Acme"));
        }
    }

    private Harness createHarness(Path joinPath, List<JoinStep> joinSteps) {
        TextQuery.init();

        Node titleIRI = NodeFactory.createURI(FIELD_NS + "title");
        Node typeIRI = NodeFactory.createURI(FIELD_NS + "identifierType");
        Node valueExactIRI = NodeFactory.createURI(FIELD_NS + "identifierValueExact");

        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null, null,
            true, true, false, false, false, true,
            Collections.singleton(LABEL_PRED), PathFactory.pathLink(LABEL_PRED), titleIRI);

        FieldDef typeField = new FieldDef("identifierType", FieldType.KEYWORD, null, null,
            true, true, true, false, true, false,
            Collections.singleton(IDENTIFIER_TYPE_PRED),
            PathFactory.pathLink(IDENTIFIER_TYPE_PRED), typeIRI)
            .withNestedName(joinPath.toString());

        FieldDef valueExactField = new FieldDef("identifierValueExact", FieldType.KEYWORD, null, null,
            true, true, true, false, true, false,
            Collections.singleton(IDENTIFIER_VALUE_PRED),
            PathFactory.pathLink(IDENTIFIER_VALUE_PRED), valueExactIRI)
            .withNestedName(joinPath.toString());

        HierarchyDef identifierHierarchy = new HierarchyDef(IDENTIFIER_DIM, Arrays.asList(typeField, valueExactField));
        NestedDef nestedDef = new NestedDef(
            joinPath.toString(),
            joinPath,
            joinSteps,
            collectJoinPredicates(joinSteps),
            Arrays.asList(typeField, valueExactField),
            Collections.singletonList(identifierHierarchy));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "BoreholeShape"),
            Collections.singleton(BOREHOLE_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, typeField, valueExactField),
            Collections.emptyList(),
            Collections.singletonList(nestedDef));

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        ShaclTextIndexLucene textIndex = new ShaclTextIndexLucene(
            new ByteBuffersDirectory(), new ByteBuffersDirectory(), config);
        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping);
        Dataset dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);
        return new Harness(dataset, textIndex);
    }

    private Set<String> queryCompanyIdentifier(ShaclTextIndexLucene textIndex, String value) {
        CqlExpression cql = CqlParser.parse("""
            {"op":"and","args":[
              {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierType"},"Company"]},
              {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierValueExact"},"%s"]}
            ]}
            """.formatted(value));

        Set<String> uris = new LinkedHashSet<>();
        for (TextHit hit : textIndex.queryWithCql(null, null, cql, null, null, null, 10, null)) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }

    private Set<Node> collectJoinPredicates(List<JoinStep> joinSteps) {
        Set<Node> predicates = new LinkedHashSet<>();
        for (JoinStep joinStep : joinSteps) {
            predicates.add(joinStep.getPredicate());
        }
        return predicates;
    }

    private static final class Harness implements AutoCloseable {
        private final Dataset dataset;
        private final ShaclTextIndexLucene textIndex;

        private Harness(Dataset dataset, ShaclTextIndexLucene textIndex) {
            this.dataset = dataset;
            this.textIndex = textIndex;
        }

        @Override
        public void close() {
            dataset.close();
        }
    }
}
