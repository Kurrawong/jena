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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.cql.CqlExpression;
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
 * A TEMPORAL field inside an {@code idx:nested} block.
 * <p>
 * Every other temporal test puts the date at root scope. That leaves the interesting part
 * untested, because a temporal field is really two Lucene fields: the stored lexical form
 * and an {@code __epoch} companion carrying the sortable, range-queryable value. Nesting
 * is where those could disagree, since the scope lookup resolves the field by name while
 * the range query targets the companion.
 * <p>
 * The shape is the one suggested in the issue and in
 * {@code docs/10-suggested-configuration.md}: observations hanging off a station, each
 * with its own {@code resultTime}. A per-record timestamp is the obvious next field a
 * reader following that page would add.
 * <p>
 * The assertion that matters is the correlated one. A station whose observations include
 * a copper reading and a March date, but never on the same observation, must not match a
 * filter asking for both. That is the whole point of nesting, and a date participating in
 * it is what was unverified.
 */
public class TestNestedTemporalField {

    private static final String EX = "http://example.org/";
    private static final String FIELD_NS = "urn:jena:lucene:field#";
    private static final String OBSERVATION_SCOPE = "observation";

    private static final Node STATION_CLASS = NodeFactory.createURI(EX + "Station");
    private static final Node HAS_OBSERVATION = NodeFactory.createURI(EX + "hasObservation");
    private static final Node ANALYTE = NodeFactory.createURI(EX + "analyte");
    private static final Node RESULT_TIME = NodeFactory.createURI(EX + "resultTime");
    private static final Node LABEL = NodeFactory.createURI(EX + "label");

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @Before
    public void setUp() {
        FieldDef entityType = new FieldDef("entityType", FieldType.KEYWORD, null,
            true, true, true, false, true, false, false,
            NodeFactory.createURI(FIELD_NS + "entityType"));
        FieldDef title = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true, false,
            NodeFactory.createURI(FIELD_NS + "title"));

        // The child fields. resultTime is stored so nestedMatch can project it back, and
        // sortable so a nested sort selector can order on it.
        FieldDef analyte = new FieldDef("analyte", FieldType.KEYWORD, null,
            true, true, true, false, true, false, false,
            NodeFactory.createURI(FIELD_NS + "analyte"));
        FieldDef resultTime = new FieldDef("resultTime", FieldType.TEMPORAL, null,
            true, true, false, true, true, false, true,
            NodeFactory.createURI(FIELD_NS + "resultTime"));

        List<FieldOccurrence> rootOccurrences = List.of(
            rootOccurrence(entityType, RDF.type.asNode()),
            rootOccurrence(title, LABEL));

        List<FieldOccurrence> childOccurrences = List.of(
            nestedOccurrence(analyte, ANALYTE),
            nestedOccurrence(resultTime, RESULT_TIME));

        NestedDef observations = new NestedDef(
            OBSERVATION_SCOPE,
            PathFactory.pathLink(HAS_OBSERVATION),
            List.of(new JoinStep(HAS_OBSERVATION, false)),
            Collections.singleton(HAS_OBSERVATION),
            childOccurrences,
            Collections.emptyList());

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(EX + "StationShape"),
            Collections.singleton(STATION_CLASS),
            "uri", "docType",
            Arrays.asList(entityType, title, analyte, resultTime),
            rootOccurrences,
            Collections.emptyList(),
            Collections.singletonList(observations));

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        textIndex = new ShaclTextIndexLucene(
            new ByteBuffersDirectory(), new ByteBuffersDirectory(), config);

        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(
            baseDs.asDatasetGraph(), textIndex, mapping);
        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);

        loadData();
    }

    private void loadData() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model m = dataset.getDefaultModel();

            // Copper in March. The one station a correlated Cu + March filter should return.
            addStation(m, "station-a", "Alpha Station", new String[][] {
                { "Cu", "2024-03-15" },
                { "Au", "2024-07-02" },
            });

            // Has copper, and has a March reading, but never on the same observation.
            // A cross-child match would wrongly surface this one.
            addStation(m, "station-b", "Beta Station", new String[][] {
                { "Cu", "2024-09-20" },
                { "Au", "2024-03-11" },
            });

            // Neither.
            addStation(m, "station-c", "Gamma Station", new String[][] {
                { "Fe", "2023-01-05" },
            });

            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void addStation(Model m, String id, String label, String[][] observations) {
        Resource station = ResourceFactory.createResource(EX + id);
        m.add(station, RDF.type, ResourceFactory.createResource(EX + "Station"));
        m.add(station, ResourceFactory.createProperty(EX, "label"), label);
        for (int i = 0; i < observations.length; i++) {
            Resource obs = ResourceFactory.createResource(EX + id + "-obs-" + i);
            m.add(station, ResourceFactory.createProperty(EX, "hasObservation"), obs);
            m.add(obs, ResourceFactory.createProperty(EX, "analyte"), observations[i][0]);
            m.add(obs, ResourceFactory.createProperty(EX, "resultTime"),
                ResourceFactory.createTypedLiteral(observations[i][1], XSDDatatype.XSDdate));
        }
    }

    private static FieldOccurrence rootOccurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(field, PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            Collections.singleton(predicate),
            null, null, null, null);
    }

    /** Evaluated relative to the observation child node. */
    private static FieldOccurrence nestedOccurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(field, PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            new LinkedHashSet<>(Collections.singletonList(predicate)),
            null, null, null, OBSERVATION_SCOPE);
    }

    @After
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    private Set<String> urisFor(CqlExpression filter) {
        List<TextHit> hits = textIndex.queryWithCql(
            null, "*", filter, null, null, null, 100, null);
        Set<String> uris = new HashSet<>();
        for (TextHit hit : hits) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }

    private static CqlExpression between(String from, String to) {
        return new CqlExpression.CqlBetween(FIELD_NS + "resultTime", from, to);
    }

    private static CqlExpression analyteIs(String value) {
        return new CqlExpression.CqlComparison("=", FIELD_NS + "analyte", value);
    }

    // ------------------------------------------------------------------

    @Test
    public void testChildScopedDateRangeFiltersStations() {
        // March 2024 falls on station-a's copper reading and station-b's gold one.
        Set<String> uris = urisFor(between("2024-03-01", "2024-04-01"));

        assertTrue("Alpha has a March observation", uris.contains(EX + "station-a"));
        assertTrue("Beta has a March observation", uris.contains(EX + "station-b"));
        assertFalse("Gamma's only reading is 2023", uris.contains(EX + "station-c"));
    }

    @Test
    public void testChildScopedDateRangeExcludesEverythingOutsideIt() {
        Set<String> uris = urisFor(between("2025-01-01", "2025-12-31"));
        assertTrue("No station has a 2025 observation: " + uris, uris.isEmpty());
    }

    @Test
    public void testDateAndKeywordMustHoldOnTheSameChild() {
        // The assertion this test exists for. Both stations have a copper reading and both
        // have a March reading; only Alpha has them on one observation.
        // Each clause on its own matches Beta, so the trap is real: only same-child
        // evaluation keeps it out of the conjunction. Without this the assertion below
        // could pass for the wrong reason.
        assertTrue("Beta matches copper alone",
            urisFor(analyteIs("Cu")).contains(EX + "station-b"));
        assertTrue("Beta matches March alone",
            urisFor(between("2024-03-01", "2024-04-01")).contains(EX + "station-b"));

        CqlExpression correlated = new CqlExpression.CqlAnd(List.of(
            analyteIs("Cu"),
            between("2024-03-01", "2024-04-01")));

        Set<String> uris = urisFor(correlated);

        assertTrue("Alpha's copper reading is the March one", uris.contains(EX + "station-a"));
        assertFalse("Beta's copper is September and its March reading is gold, "
            + "so no single observation satisfies both", uris.contains(EX + "station-b"));
        assertFalse(uris.contains(EX + "station-c"));
    }

    @Test
    public void testDateAndKeywordOnTheSameChildTheOtherWayRound() {
        // The mirror of the above: Beta's gold reading is the March one, Alpha's is July.
        CqlExpression correlated = new CqlExpression.CqlAnd(List.of(
            analyteIs("Au"),
            between("2024-03-01", "2024-04-01")));

        Set<String> uris = urisFor(correlated);

        assertTrue("Beta's gold reading is in March", uris.contains(EX + "station-b"));
        assertFalse("Alpha's gold reading is July", uris.contains(EX + "station-a"));
    }

    @Test
    public void testChildScopedDateIndexesItsEpochCompanion() {
        // A TEMPORAL field is two Lucene fields: the lexical form and an __epoch companion
        // that carries the range-queryable value. Nesting is where the two could diverge,
        // because the scope lookup resolves by field name and the range query targets the
        // companion. An open-ended range exercises the companion directly.
        Set<String> uris = urisFor(new CqlExpression.CqlComparison(
            ">=", FIELD_NS + "resultTime", "2024-01-01"));

        assertTrue(uris.contains(EX + "station-a"));
        assertTrue(uris.contains(EX + "station-b"));
        assertFalse("Gamma's only reading is 2023", uris.contains(EX + "station-c"));
    }

    @Test
    public void testExactChildDateEquality() {
        Set<String> uris = urisFor(new CqlExpression.CqlComparison(
            "=", FIELD_NS + "resultTime", "2024-03-15"));

        assertEquals(Collections.singleton(EX + "station-a"), uris);
    }
}
