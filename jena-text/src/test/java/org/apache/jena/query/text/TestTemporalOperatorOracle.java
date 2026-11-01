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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every temporal operator, against generated operands, agrees with OGC 21-065r2's
 * definitions evaluated directly. The values sit on a six-hour grid, and whole days start
 * on it, so ends coincide often and every equality and strict comparison is exercised at
 * its boundary.
 */
public class TestTemporalOperatorOracle {

    private static final String NS = "http://example.org/";
    private static final String FIELD = "urn:jena:lucene:field#when";
    private static final Node WHEN = NodeFactory.createURI(NS + "when");

    private static final Instant BASE = Instant.parse("2021-06-30T00:00:00Z");
    private static final long STEP = ChronoUnit.HOURS.getDuration().toMillis() * 6;
    private static final long DAY = ChronoUnit.DAYS.getDuration().toMillis();
    private static final long OPEN_START = Long.MIN_VALUE;
    private static final long OPEN_END = Long.MAX_VALUE;

    private static final List<String> OPERATORS = List.of(
        "t_after", "t_before", "t_contains", "t_disjoint", "t_during", "t_equals", "t_finishedBy",
        "t_finishes", "t_intersects", "t_meets", "t_metBy", "t_overlappedBy", "t_overlaps",
        "t_startedBy", "t_starts");

    /** A span in epoch milliseconds, and how it is written: as a value, or in a filter. */
    private record Span(long start, long end, String json) {}

    private final Map<String, Span> values = new LinkedHashMap<>();
    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @BeforeEach
    public void setUp() {
        FieldDef when = new FieldDef("when", FieldType.TEMPORAL, null, null,
            false, true, false, false, false, false, true);
        Path path = PathFactory.pathLink(WHEN);
        FieldOccurrence occurrence = new FieldOccurrence(when, path, ShaclIndexAssembler.extractPathVariants(path),
            Collections.singleton(WHEN), null, null, null, null);
        IndexProfile profile = new IndexProfile(NodeFactory.createURI(NS + "Shape"),
            Collections.singleton(NodeFactory.createURI(NS + "Thing")), "uri", "docType",
            List.of(when), List.of(occurrence), Collections.emptyList(), Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);
        Dataset baseDs = DatasetFactory.create();
        dataset = TextDatasetFactory.create(baseDs, textIndex, true,
            new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping));

        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            for (int i = -8; i <= 8; i++) {
                long t = BASE.toEpochMilli() + i * STEP;
                add(model, "instant" + i, new Span(t, t, Instant.ofEpochMilli(t).toString()), XSDDatatype.XSDdateTime);
            }
            for (int d = -2; d <= 2; d++) {
                LocalDate day = LocalDate.ofInstant(BASE, ZoneOffset.UTC).plusDays(d);
                long start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                add(model, "day" + d, new Span(start, start + DAY, day.toString()), XSDDatatype.XSDdate);
            }
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
    public void everyOperatorAgreesWithItsDefinition() {
        Random random = new Random(20261026L);
        List<String> disagreements = new ArrayList<>();
        int checked = 0;
        for (int i = 0; i < 120; i++) {
            Span literal = randomLiteral(random);
            for (String op : OPERATORS) {
                checked += 2;
                check(op, literal, true, disagreements);
                check(op, literal, false, disagreements);
            }
        }
        assertTrue(disagreements.isEmpty(), disagreements.size() + " of " + checked
            + " disagree, for example:\n" + String.join("\n", disagreements.subList(0, Math.min(10, disagreements.size()))));
    }

    /** Compare Lucene's answer with the definition, with the property first or second. */
    private void check(String op, Span literal, boolean propertyFirst, List<String> disagreements) {
        String property = "{\"property\": \"" + FIELD + "\"}";
        String json = "{\"op\": \"" + op + "\", \"args\": ["
            + (propertyFirst ? property + ", " + literal.json() : literal.json() + ", " + property) + "]}";

        Set<String> expected = new TreeSet<>();
        values.forEach((name, value) -> {
            boolean holds = propertyFirst ? related(op, value, literal) : related(op, literal, value);
            if (holds) {
                expected.add(name);
            }
        });

        Set<String> actual;
        try {
            actual = matching(json);
        } catch (RuntimeException ex) {
            disagreements.add(json + " raised " + ex.getMessage());
            return;
        }
        if (!expected.equals(actual)) {
            disagreements.add(json + " expected " + expected + " but was " + actual);
        }
    }

    /** OGC 21-065r2 Tables 5 and 6, with Allen's definitions for the relations Table 6 cites. */
    private static boolean related(String op, Span a, Span b) {
        long as = a.start();
        long ae = a.end();
        long bs = b.start();
        long be = b.end();
        return switch (op) {
            case "t_after" -> as > be;
            case "t_before" -> ae < bs;
            case "t_contains" -> as < bs && ae > be;
            case "t_disjoint" -> ae < bs || as > be;
            case "t_during" -> as > bs && ae < be;
            case "t_equals" -> as == bs && ae == be;
            case "t_finishedBy" -> as < bs && ae == be;
            case "t_finishes" -> as > bs && ae == be;
            case "t_intersects" -> !(ae < bs || as > be);
            case "t_meets" -> ae == bs;
            case "t_metBy" -> as == be;
            case "t_overlappedBy" -> as > bs && as < be && ae > be;
            case "t_overlaps" -> as < bs && ae > bs && ae < be;
            case "t_startedBy" -> as == bs && ae > be;
            case "t_starts" -> as == bs && ae < be;
            default -> throw new IllegalArgumentException(op);
        };
    }

    /** A timestamp, a date, or an interval whose ends are each a timestamp, a date or open. */
    private static Span randomLiteral(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> {
                long t = gridTime(random);
                yield new Span(t, t, "{\"timestamp\": \"" + Instant.ofEpochMilli(t) + "\"}");
            }
            case 1 -> {
                LocalDate day = randomDay(random);
                long start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                yield new Span(start, start + DAY, "{\"date\": \"" + day + "\"}");
            }
            default -> {
                long[] start = randomIntervalEnd(random, true);
                long[] end = randomIntervalEnd(random, false);
                String json = "{\"interval\": [" + endJson(start, true) + ", " + endJson(end, false) + "]}";
                yield new Span(start[0], end[0], json);
            }
        };
    }

    /** {@code [millis, kind]}: kind 0 is a timestamp, 1 a date, 2 open. */
    private static long[] randomIntervalEnd(Random random, boolean isStart) {
        return switch (random.nextInt(5)) {
            case 0 -> new long[] { isStart ? OPEN_START : OPEN_END, 2 };
            case 1, 2 -> {
                long dayStart = randomDay(random).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                // A date's start as a start bound, its end as an end bound.
                yield new long[] { isStart ? dayStart : dayStart + DAY, 1 };
            }
            default -> new long[] { gridTime(random), 0 };
        };
    }

    private static String endJson(long[] end, boolean isStart) {
        return switch ((int) end[1]) {
            case 2 -> "\"..\"";
            case 1 -> "\"" + LocalDate.ofInstant(Instant.ofEpochMilli(isStart ? end[0] : end[0] - DAY), ZoneOffset.UTC) + "\"";
            default -> "\"" + Instant.ofEpochMilli(end[0]) + "\"";
        };
    }

    private static long gridTime(Random random) {
        return BASE.toEpochMilli() + (random.nextInt(21) - 10) * STEP;
    }

    private static LocalDate randomDay(Random random) {
        return LocalDate.ofInstant(BASE, ZoneOffset.UTC).plusDays(random.nextInt(7) - 3);
    }

    private void add(Model model, String name, Span span, XSDDatatype datatype) {
        values.put(name, span);
        Resource thing = ResourceFactory.createResource(NS + name);
        model.add(thing, RDF.type, ResourceFactory.createResource(NS + "Thing"));
        model.add(thing, ResourceFactory.createProperty(WHEN.getURI()),
            ResourceFactory.createTypedLiteral(span.json(), datatype));
    }

    private Set<String> matching(String cqlJson) {
        Set<String> names = new TreeSet<>();
        for (TextHit hit : textIndex.queryWithCql(List.of(FIELD), null, CqlParser.parse(cqlJson),
                null, null, null, 100, null)) {
            names.add(hit.getNode().getURI().substring(NS.length()));
        }
        return names;
    }
}
