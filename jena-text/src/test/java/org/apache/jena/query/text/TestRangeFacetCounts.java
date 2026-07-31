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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestRangeFacetCounts {
    private static final String NS = "http://example.org/";
    private static final String FP = "urn:jena:lucene:field#";

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @Before
    public void setUp() {
        TextQuery.init();

        Node titlePred = NodeFactory.createURI(NS + "title");
        Node yearPred = NodeFactory.createURI(NS + "year");
        Node eventTimePred = NodeFactory.createURI(NS + "eventTime");
        Node scorePred = NodeFactory.createURI(NS + "score");
        Node publishedOnPred = NodeFactory.createURI(NS + "publishedOn");

        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);
        FieldDef yearField = new FieldDef("year", FieldType.INT, null,
            true, true, true, true, true, false);
        FieldDef eventTimeField = new FieldDef("eventTime", FieldType.LONG, null,
            true, true, true, true, false, false);
        FieldDef scoreField = new FieldDef("score", FieldType.DOUBLE, null,
            true, true, true, true, false, false);
        // TEMPORAL buckets by ISO boundary — the epoch twin field carries the docvalues, so
        // the field does NOT need to be stored to be bucketed (stored=false here, which is
        // the recommended shape for a date nothing projects).
        // storeLiteralMetadata is not optional though: validateLiteralMetadataRequirements
        // rejects any TEMPORAL field without it.
        FieldDef publishedOnField = new FieldDef("publishedOn", FieldType.TEMPORAL, null,
            false, true, true, true, false, false, true);

        List<FieldOccurrence> rootOccurrences = Arrays.asList(
            occurrence(titleField, PathFactory.pathLink(titlePred), Collections.singleton(titlePred)),
            occurrence(yearField, PathFactory.pathLink(yearPred), Collections.singleton(yearPred)),
            occurrence(eventTimeField, PathFactory.pathLink(eventTimePred), Collections.singleton(eventTimePred)),
            occurrence(scoreField, PathFactory.pathLink(scorePred), Collections.singleton(scorePred)),
            occurrence(publishedOnField, PathFactory.pathLink(publishedOnPred), Collections.singleton(publishedOnPred)));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "ThingShape"),
            Collections.singleton(NodeFactory.createURI(NS + "Thing")),
            "uri", "docType",
            Arrays.asList(titleField, yearField, eventTimeField, scoreField, publishedOnField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);

        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping);
        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);

        loadData();
    }

    private void loadData() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            addThing(model, "d1", "alpha learning", new int[]{2018, 2020}, 100L, 1.5, "2018-05-01");
            addThing(model, "d2", "beta learning", new int[]{2021}, 200L, 2.5, "2021-03-15");
            addThing(model, "d3", "gamma learning", new int[]{2024}, 300L, 3.75, "2024-01-20");
            addThing(model, "d4", "delta learning", new int[]{2019}, 400L, 4.5, "2019-11-30");
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void addThing(Model model, String id, String title, int[] years, long eventTime, double score,
                          String publishedOn) {
        Resource thing = ResourceFactory.createResource(NS + id);
        model.add(thing, RDF.type, ResourceFactory.createResource(NS + "Thing"));
        model.add(thing, ResourceFactory.createProperty(NS + "title"), title);
        for (int year : years) {
            model.add(thing, ResourceFactory.createProperty(NS + "year"), ResourceFactory.createTypedLiteral(year));
        }
        model.add(thing, ResourceFactory.createProperty(NS + "eventTime"), ResourceFactory.createTypedLiteral(eventTime));
        model.add(thing, ResourceFactory.createProperty(NS + "score"), ResourceFactory.createTypedLiteral(score));
        model.addLiteral(thing, ResourceFactory.createProperty(NS + "publishedOn"),
            ResourceFactory.createTypedLiteral(publishedOn, XSDDatatype.XSDdate));
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
    public void testIntLongAndDoubleRangeFacetCounts() {
        FacetRequest request = new FacetRequest(
            List.of(),
            List.of(
                new FacetRequest.RangeFacetSpec(FP + "year", Arrays.asList(null, "2020", "2023", null)),
                new FacetRequest.RangeFacetSpec(FP + "eventTime", Arrays.asList(null, "200", "350", null)),
                new FacetRequest.RangeFacetSpec(FP + "score", Arrays.asList(null, "2.0", "4.0", null))
            ));

        Map<String, List<FacetValue>> facets = textIndex.getFacetCounts("learning", List.of("default"), request, 10, 0);

        assertRangeBucket(facets.get("year").get(0), null, "2020", 2);
        assertRangeBucket(facets.get("year").get(1), "2020", "2023", 2);
        assertRangeBucket(facets.get("year").get(2), "2023", null, 1);

        assertRangeBucket(facets.get("eventTime").get(0), null, "200", 1);
        assertRangeBucket(facets.get("eventTime").get(1), "200", "350", 2);
        assertRangeBucket(facets.get("eventTime").get(2), "350", null, 1);

        assertRangeBucket(facets.get("score").get(0), null, "2.0", 1);
        assertRangeBucket(facets.get("score").get(1), "2.0", "4.0", 2);
        assertRangeBucket(facets.get("score").get(2), "4.0", null, 1);
    }

    /**
     * Date buckets are declared with ISO boundaries, not epoch millis, and are counted from
     * the {@code publishedOn__epoch} docvalues the TEMPORAL field writes alongside the
     * stored lexical form. Lower bound inclusive, upper exclusive except on the open end.
     */
    @Test
    public void testTemporalRangeFacetCounts() {
        FacetRequest request = new FacetRequest(
            List.of(),
            List.of(new FacetRequest.RangeFacetSpec(FP + "publishedOn",
                Arrays.asList(null, "2019-01-01", "2022-01-01", null))));

        Map<String, List<FacetValue>> facets = textIndex.getFacetCounts("learning", List.of("default"), request, 10, 0);

        assertRangeBucket(facets.get("publishedOn").get(0), null, "2019-01-01", 1);          // d1
        assertRangeBucket(facets.get("publishedOn").get(1), "2019-01-01", "2022-01-01", 2);  // d4, d2
        assertRangeBucket(facets.get("publishedOn").get(2), "2022-01-01", null, 1);          // d3
    }

    private void assertRangeBucket(FacetValue bucket, String low, String high, long count) {
        assertEquals(FacetValue.Kind.RANGE, bucket.getKind());
        assertEquals(low, bucket.getLow());
        assertEquals(high, bucket.getHigh());
        assertEquals(count, bucket.getCount());
    }
}
