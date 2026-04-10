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

import java.util.*;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.Test;

public class TestSharedFieldOccurrences {

    private static final String NS = "http://example.org/";
    private static final String FIELD_NS = "urn:jena:lucene:field#";

    private static final Node SURVEY_CLASS = NodeFactory.createURI(NS + "Survey");
    private static final Node WELL_CLASS = NodeFactory.createURI(NS + "Well");
    private static final Node SAMPLE_CLASS = NodeFactory.createURI(NS + "Sample");
    private static final Node BOREHOLE_CLASS = NodeFactory.createURI(NS + "Borehole");

    private static final Node ABOUT_PRED = NodeFactory.createURI(NS + "about");
    private static final Node PARENT_PRED = NodeFactory.createURI(NS + "parent");
    private static final Node VALUE_PRED = NodeFactory.createURI(NS + "value");

    @Test
    public void testSharedCanonicalFieldFansInAcrossProfiles() {
        FieldDef hasParent = new FieldDef("hasParent", FieldType.KEYWORD, null, null,
            true, true, false, false, true, false, false,
            NodeFactory.createURI(FIELD_NS + "hasParent"));

        IndexProfile surveyProfile = new IndexProfile(
            NodeFactory.createURI(NS + "SurveyShape"),
            Collections.singleton(SURVEY_CLASS),
            "uri", "docType",
            Collections.singletonList(hasParent),
            Collections.singletonList(occurrence(hasParent,
                PathFactory.pathInverse(PathFactory.pathLink(ABOUT_PRED)),
                Collections.singleton(ABOUT_PRED), null, null, null)),
            Collections.emptyList(),
            Collections.emptyList());

        IndexProfile wellProfile = new IndexProfile(
            NodeFactory.createURI(NS + "WellShape"),
            Collections.singleton(WELL_CLASS),
            "uri", "docType",
            Collections.singletonList(hasParent),
            Collections.singletonList(occurrence(hasParent,
                PathFactory.pathLink(PARENT_PRED),
                Collections.singleton(PARENT_PRED), null, null, null)),
            Collections.emptyList(),
            Collections.emptyList());

        try (Harness harness = createHarness(Arrays.asList(surveyProfile, wellProfile))) {
            harness.dataset.begin(ReadWrite.WRITE);
            try {
                Model model = harness.dataset.getDefaultModel();
                Resource survey = ResourceFactory.createResource(NS + "survey1");
                Resource well = ResourceFactory.createResource(NS + "well1");
                Resource borehole = ResourceFactory.createResource(NS + "bh1");

                model.add(survey, RDF.type, ResourceFactory.createResource(SURVEY_CLASS.getURI()));
                model.add(well, RDF.type, ResourceFactory.createResource(WELL_CLASS.getURI()));
                model.add(borehole, ResourceFactory.createProperty(ABOUT_PRED.getURI()), survey);
                model.add(well, ResourceFactory.createProperty(PARENT_PRED.getURI()), borehole);
                harness.dataset.commit();
            } finally {
                harness.dataset.end();
            }

            assertEquals(Set.of(NS + "survey1", NS + "well1"),
                queryByFieldValue(harness.textIndex, FIELD_NS + "hasParent", NS + "bh1"));
        }
    }

    @Test
    public void testClassConstraintFiltersReachedNodes() {
        FieldDef hasParent = new FieldDef("hasParent", FieldType.KEYWORD, null, null,
            true, true, false, false, true, false, false,
            NodeFactory.createURI(FIELD_NS + "hasParent"));

        IndexProfile surveyProfile = new IndexProfile(
            NodeFactory.createURI(NS + "SurveyShape"),
            Collections.singleton(SURVEY_CLASS),
            "uri", "docType",
            Collections.singletonList(hasParent),
            Collections.singletonList(occurrence(hasParent,
                PathFactory.pathInverse(PathFactory.pathLink(ABOUT_PRED)),
                Collections.singleton(ABOUT_PRED), BOREHOLE_CLASS, null, null)),
            Collections.emptyList(),
            Collections.emptyList());

        try (Harness harness = createHarness(Collections.singletonList(surveyProfile))) {
            harness.dataset.begin(ReadWrite.WRITE);
            try {
                Model model = harness.dataset.getDefaultModel();
                Resource survey1 = ResourceFactory.createResource(NS + "survey1");
                Resource survey2 = ResourceFactory.createResource(NS + "survey2");
                Resource borehole = ResourceFactory.createResource(NS + "bh1");
                Resource project = ResourceFactory.createResource(NS + "project1");

                model.add(survey1, RDF.type, ResourceFactory.createResource(SURVEY_CLASS.getURI()));
                model.add(survey2, RDF.type, ResourceFactory.createResource(SURVEY_CLASS.getURI()));
                model.add(borehole, RDF.type, ResourceFactory.createResource(BOREHOLE_CLASS.getURI()));
                model.add(borehole, ResourceFactory.createProperty(ABOUT_PRED.getURI()), survey1);
                model.add(project, ResourceFactory.createProperty(ABOUT_PRED.getURI()), survey2);
                harness.dataset.commit();
            } finally {
                harness.dataset.end();
            }

            assertEquals(Set.of(NS + "survey1"),
                queryByFieldValue(harness.textIndex, FIELD_NS + "hasParent", NS + "bh1"));
            assertEquals(Collections.emptySet(),
                queryByFieldValue(harness.textIndex, FIELD_NS + "hasParent", NS + "project1"));
        }
    }

    @Test
    public void testNodeKindAndDatatypeConstraintsFilterValues() {
        FieldDef valueField = new FieldDef("value", FieldType.KEYWORD, null, null,
            true, true, false, false, true, false, false,
            NodeFactory.createURI(FIELD_NS + "value"));

        IndexProfile sampleProfile = new IndexProfile(
            NodeFactory.createURI(NS + "SampleShape"),
            Collections.singleton(SAMPLE_CLASS),
            "uri", "docType",
            Collections.singletonList(valueField),
            Collections.singletonList(occurrence(valueField,
                PathFactory.pathLink(VALUE_PRED),
                Collections.singleton(VALUE_PRED), null,
                ShaclIndexMapping.NodeKindConstraint.LITERAL, XSDDatatype.XSDinteger.getURI())),
            Collections.emptyList(),
            Collections.emptyList());

        try (Harness harness = createHarness(Collections.singletonList(sampleProfile))) {
            harness.dataset.begin(ReadWrite.WRITE);
            try {
                Model model = harness.dataset.getDefaultModel();
                Resource sample1 = ResourceFactory.createResource(NS + "sample1");
                Resource sample2 = ResourceFactory.createResource(NS + "sample2");
                Resource sample3 = ResourceFactory.createResource(NS + "sample3");

                model.add(sample1, RDF.type, ResourceFactory.createResource(SAMPLE_CLASS.getURI()));
                model.add(sample2, RDF.type, ResourceFactory.createResource(SAMPLE_CLASS.getURI()));
                model.add(sample3, RDF.type, ResourceFactory.createResource(SAMPLE_CLASS.getURI()));
                model.add(sample1, ResourceFactory.createProperty(VALUE_PRED.getURI()),
                    model.createTypedLiteral("42", XSDDatatype.XSDinteger));
                model.add(sample2, ResourceFactory.createProperty(VALUE_PRED.getURI()),
                    model.createTypedLiteral("oops", XSDDatatype.XSDstring));
                model.add(sample3, ResourceFactory.createProperty(VALUE_PRED.getURI()),
                    ResourceFactory.createResource(NS + "objectValue"));
                harness.dataset.commit();
            } finally {
                harness.dataset.end();
            }

            assertEquals(Set.of(NS + "sample1"),
                queryByFieldValue(harness.textIndex, FIELD_NS + "value", "42"));
            assertEquals(Collections.emptySet(),
                queryByFieldValue(harness.textIndex, FIELD_NS + "value", "oops"));
        }
    }

    @Test
    public void testClassConstraintTypeChangesReindexParent() {
        FieldDef hasParent = new FieldDef("hasParent", FieldType.KEYWORD, null, null,
            true, true, false, false, true, false, false,
            NodeFactory.createURI(FIELD_NS + "hasParent"));

        IndexProfile surveyProfile = new IndexProfile(
            NodeFactory.createURI(NS + "SurveyShape"),
            Collections.singleton(SURVEY_CLASS),
            "uri", "docType",
            Collections.singletonList(hasParent),
            Collections.singletonList(occurrence(hasParent,
                PathFactory.pathInverse(PathFactory.pathLink(ABOUT_PRED)),
                Collections.singleton(ABOUT_PRED), BOREHOLE_CLASS, null, null)),
            Collections.emptyList(),
            Collections.emptyList());

        try (Harness harness = createHarness(Collections.singletonList(surveyProfile))) {
            harness.dataset.begin(ReadWrite.WRITE);
            try {
                Model model = harness.dataset.getDefaultModel();
                Resource survey = ResourceFactory.createResource(NS + "survey1");
                Resource candidateParent = ResourceFactory.createResource(NS + "bh1");

                model.add(survey, RDF.type, ResourceFactory.createResource(SURVEY_CLASS.getURI()));
                model.add(candidateParent, ResourceFactory.createProperty(ABOUT_PRED.getURI()), survey);
                harness.dataset.commit();
            } finally {
                harness.dataset.end();
            }

            assertEquals(Collections.emptySet(),
                queryByFieldValue(harness.textIndex, FIELD_NS + "hasParent", NS + "bh1"));

            harness.dataset.begin(ReadWrite.WRITE);
            try {
                Model model = harness.dataset.getDefaultModel();
                model.add(ResourceFactory.createResource(NS + "bh1"),
                    RDF.type, ResourceFactory.createResource(BOREHOLE_CLASS.getURI()));
                harness.dataset.commit();
            } finally {
                harness.dataset.end();
            }

            assertEquals(Set.of(NS + "survey1"),
                queryByFieldValue(harness.textIndex, FIELD_NS + "hasParent", NS + "bh1"));

            harness.dataset.begin(ReadWrite.WRITE);
            try {
                Model model = harness.dataset.getDefaultModel();
                model.remove(ResourceFactory.createResource(NS + "bh1"),
                    RDF.type, ResourceFactory.createResource(BOREHOLE_CLASS.getURI()));
                harness.dataset.commit();
            } finally {
                harness.dataset.end();
            }

            assertEquals(Collections.emptySet(),
                queryByFieldValue(harness.textIndex, FIELD_NS + "hasParent", NS + "bh1"));
        }
    }

    private static FieldOccurrence occurrence(FieldDef field, Path path, Set<Node> predicates,
                                              Node requiredClass,
                                              ShaclIndexMapping.NodeKindConstraint nodeKind,
                                              String datatypeUri) {
        return new FieldOccurrence(
            field,
            path,
            ShaclIndexAssembler.extractPathVariants(path),
            predicates,
            requiredClass,
            nodeKind,
            datatypeUri != null ? NodeFactory.createURI(datatypeUri) : null,
            null);
    }

    private static Set<String> queryByFieldValue(ShaclTextIndexLucene textIndex, String fieldIri, String value) {
        CqlExpression cql = CqlParser.parse("""
            {"op":"=","args":[{"property":"%s"},"%s"]}
            """.formatted(fieldIri, value));

        Set<String> uris = new LinkedHashSet<>();
        for (TextHit hit : textIndex.queryWithCql(null, null, cql, null, null, null, 10, null)) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }

    private static Harness createHarness(List<IndexProfile> profiles) {
        TextQuery.init();
        ShaclIndexMapping mapping = new ShaclIndexMapping(profiles);
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        ShaclTextIndexLucene textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);
        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping);
        Dataset dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);
        return new Harness(dataset, textIndex);
    }

    private record Harness(Dataset dataset, ShaclTextIndexLucene textIndex) implements AutoCloseable {
        @Override
        public void close() {
            dataset.close();
        }
    }
}
