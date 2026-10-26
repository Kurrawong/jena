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
import java.util.stream.Stream;

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
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * CQL2's temporal operators on a TemporalField. Each operand is the interval it spans in
 * UTC, and each relation compares the ends of the two, strictly or for equality.
 * <p>
 * The fixture and the cases mirror Prez's ({@code api/tests/integration/jena-lucene-cql/
 * data/temporal.ttl} and {@code api/tests/temporal_cases.py}), whose SPARQL answers
 * pushdown must match.
 */
public class TestTemporalOperators {

    private static final String NS = "http://example.org/temporal/";
    private static final String FP = "urn:jena:lucene:field#";
    private static final String SCHEMA = "https://schema.org/";

    private static final Set<String> DATETIMES = Set.of("datetime-utc", "datetime-offset", "datetime-no-timezone");
    private static final Set<String> PERIODS = Set.of("date", "date-offset", "year-month", "year");

    private static final String FIXTURE = """
        @prefix ex:     <http://example.org/temporal/> .
        @prefix schema: <https://schema.org/> .
        @prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .

        ex:datetime-utc a ex:TemporalFixture ;
            schema:temporal "2021-06-30T12:00:00Z"^^xsd:dateTime .
        ex:datetime-offset a ex:TemporalFixture ;
            schema:temporal "2021-06-30T22:00:00+10:00"^^xsd:dateTime .
        ex:datetime-no-timezone a ex:TemporalFixture ;
            schema:temporal "2021-06-30T12:00:00"^^xsd:dateTime .
        ex:date a ex:TemporalFixture ;
            schema:temporal "2021-06-30"^^xsd:date .
        ex:date-offset a ex:TemporalFixture ;
            schema:temporal "2021-06-30+10:00"^^xsd:date .
        ex:year-month a ex:TemporalFixture ;
            schema:temporal "2021-06"^^xsd:gYearMonth .
        ex:year a ex:TemporalFixture ;
            schema:temporal "2021"^^xsd:gYear .
        ex:month-day a ex:TemporalFixture ;
            schema:temporal "--06-30"^^xsd:gMonthDay .
        ex:time a ex:TemporalFixture ;
            schema:temporal "12:00:00Z"^^xsd:time .

        ex:built-and-opened a ex:TemporalFixture ;
            ex:built "2021-03-01"^^xsd:date ;
            ex:opened "2019-05-01"^^xsd:date .

        ex:founded-0900 a ex:TemporalFixture ;
            ex:founded "0900"^^xsd:gYear .
        ex:founded-0900-06 a ex:TemporalFixture ;
            ex:founded "0900-06"^^xsd:gYearMonth .
        ex:founded-0999 a ex:TemporalFixture ;
            ex:founded "0999"^^xsd:gYear .
        ex:founded-2021-12 a ex:TemporalFixture ;
            ex:founded "2021-12"^^xsd:gYearMonth .
        """;

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @BeforeEach
    public void setUp() {
        List<FieldDef> fields = new ArrayList<>();
        List<FieldOccurrence> occurrences = new ArrayList<>();
        addTemporalField(fields, occurrences, "temporal", SCHEMA + "temporal");
        addTemporalField(fields, occurrences, "built", NS + "built");
        addTemporalField(fields, occurrences, "opened", NS + "opened");
        addTemporalField(fields, occurrences, "founded", NS + "founded");

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "FixtureShape"),
            Collections.singleton(NodeFactory.createURI(NS + "TemporalFixture")),
            "uri", "docType", fields, occurrences,
            Collections.emptyList(), Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        config.setValueStored(true);

        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);
        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping);
        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);

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

    /** Prez's {@code CQL_TEMPORAL_CASES}, in its order. */
    static Stream<Arguments> prezCases() {
        return Stream.of(
            Arguments.of("""
                {"op": "t_after", "args": [{"property": "%stemporal"}, {"timestamp": "2021-06-30T11:00:00Z"}]}
                """, DATETIMES),
            Arguments.of("""
                {"op": "t_before", "args": [{"property": "%stemporal"}, {"timestamp": "2021-07-01T00:00:00Z"}]}
                """, union(DATETIMES, "date-offset")),
            Arguments.of("""
                {"op": "t_during", "args": [{"property": "%stemporal"}, {"interval": ["2021-06-01", "2021-06-30"]}]}
                """, union(DATETIMES, "date-offset")),
            Arguments.of("""
                {"op": "t_intersects", "args": [{"property": "%stemporal"}, {"interval": ["2021-06-30T13:00:00Z", ".."]}]}
                """, PERIODS),
            Arguments.of("""
                {"op": "t_equals", "args": [{"property": "%stemporal"}, {"date": "2021-06-30"}]}
                """, Set.of("date")),
            Arguments.of("""
                {"op": "t_meets", "args": [{"property": "%stemporal"}, {"interval": ["2021-07-01", ".."]}]}
                """, Set.of("date", "year-month")),
            Arguments.of("""
                {"op": "t_contains", "args": [{"interval": ["2021-01-01", "2021-12-31"]}, {"property": "%stemporal"}]}
                """, union(DATETIMES, "date", "date-offset", "year-month"))
        );
    }

    @ParameterizedTest
    @MethodSource("prezCases")
    public void matchesPrez(String filterTemplate, Set<String> expected) {
        assertEquals(expected, matching(filterTemplate.formatted(FP, FP, FP, FP)));
    }

    private static Set<String> union(Set<String> names, String... more) {
        Set<String> all = new TreeSet<>(names);
        all.addAll(List.of(more));
        return all;
    }

    private Set<String> matching(String cqlJson) {
        Set<String> names = new TreeSet<>();
        for (TextHit hit : textIndex.queryWithCql(List.of(FP + "temporal"), null, CqlParser.parse(cqlJson),
                null, null, null, 100, null)) {
            names.add(hit.getNode().getURI().substring(NS.length()));
        }
        return names;
    }

    private static void addTemporalField(List<FieldDef> fields, List<FieldOccurrence> occurrences,
                                         String name, String predicate) {
        FieldDef field = new FieldDef(name, FieldType.TEMPORAL, null, null,
            false, true, false, false, false, false, true);
        Node p = NodeFactory.createURI(predicate);
        Path path = PathFactory.pathLink(p);
        fields.add(field);
        occurrences.add(new FieldOccurrence(field, path, ShaclIndexAssembler.extractPathVariants(path),
            Collections.singleton(p), null, null, null, null));
    }
}
