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

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.*;

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
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Temporal operators on a TemporalField inside an {@code idx:nested} block: a clause on
 * the child field surfaces the parent, and clauses on one scope hold of one child.
 */
public class TestNestedTemporalOperators {

    private static final String EX = "http://example.org/";
    private static final String FP = "urn:jena:lucene:field#";
    private static final String SCOPE = "observation";
    private static final Node HAS_OBSERVATION = NodeFactory.createURI(EX + "hasObservation");

    /**
     * Station B has a copper reading and a March reading, but never on the same
     * observation, so a correlated filter must not return it.
     */
    private static final String FIXTURE = """
        @prefix ex:  <http://example.org/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

        ex:station-a a ex:Station ;
            ex:hasObservation [ ex:analyte "Cu" ; ex:resultTime "2024-03-15"^^xsd:date ] ,
                              [ ex:analyte "Au" ; ex:resultTime "2024-07-02"^^xsd:date ] .
        ex:station-b a ex:Station ;
            ex:hasObservation [ ex:analyte "Cu" ; ex:resultTime "2024-09-20"^^xsd:date ] ,
                              [ ex:analyte "Au" ; ex:resultTime "2024-03-11"^^xsd:date ] .
        ex:station-c a ex:Station ;
            ex:hasObservation [ ex:analyte "Fe" ; ex:resultTime "2023-01-05"^^xsd:date ] .
        """;

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @BeforeEach
    public void setUp() {
        FieldDef analyte = new FieldDef("analyte", FieldType.KEYWORD, null, null,
            true, true, false, false, true, false, false);
        FieldDef resultTime = new FieldDef("resultTime", FieldType.TEMPORAL, null, null,
            false, true, false, false, true, false, true);
        NestedDef observations = new NestedDef(SCOPE, PathFactory.pathLink(HAS_OBSERVATION),
            List.of(new JoinStep(HAS_OBSERVATION, false)), Collections.singleton(HAS_OBSERVATION),
            List.of(childOccurrence(analyte, EX + "analyte"), childOccurrence(resultTime, EX + "resultTime")),
            Collections.emptyList());
        IndexProfile profile = new IndexProfile(NodeFactory.createURI(EX + "StationShape"),
            Collections.singleton(NodeFactory.createURI(EX + "Station")), "uri", "docType",
            List.of(analyte, resultTime), Collections.emptyList(),
            Collections.emptyList(), List.of(observations));

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), new ByteBuffersDirectory(), config);
        Dataset baseDs = DatasetFactory.create();
        dataset = TextDatasetFactory.create(baseDs, textIndex, true,
            new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping));

        dataset.begin(ReadWrite.WRITE);
        try {
            dataset.getDefaultModel().read(new StringReader(FIXTURE), null, "TTL");
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    @AfterEach
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    @Test
    public void aClauseOnAChildFieldSurfacesTheParent() {
        assertEquals(Set.of("station-a", "station-b"), matching("""
            {"op": "t_during", "args": [{"property": "%sresultTime"}, {"interval": ["2024-03-01", "2024-03-31"]}]}
            """.formatted(FP)));
    }

    @Test
    public void clausesOnOneScopeHoldOfOneChild() {
        assertEquals(Set.of("station-a"), matching("""
            {"op": "and", "args": [
                {"op": "=", "args": [{"property": "%sanalyte"}, "Cu"]},
                {"op": "t_during", "args": [{"property": "%sresultTime"}, {"interval": ["2024-03-01", "2024-03-31"]}]}]}
            """.formatted(FP, FP)));
    }

    private Set<String> matching(String cqlJson) {
        Set<String> names = new TreeSet<>();
        for (TextHit hit : textIndex.queryWithCql(List.of(FP + "analyte"), null, CqlParser.parse(cqlJson),
                null, null, null, 100, null)) {
            names.add(hit.getNode().getURI().substring(EX.length()));
        }
        return names;
    }

    private static FieldOccurrence childOccurrence(FieldDef field, String predicate) {
        Node p = NodeFactory.createURI(predicate);
        return new FieldOccurrence(field, PathFactory.pathLink(p), List.of(List.of(new JoinStep(p, false))),
            new LinkedHashSet<>(Collections.singletonList(p)), null, null, null, SCOPE);
    }
}
