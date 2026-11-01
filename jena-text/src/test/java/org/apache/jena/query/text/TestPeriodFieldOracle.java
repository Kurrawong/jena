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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.assembler.TextAssembler;
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sys.JenaSystem;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every temporal operator on a PeriodField, against generated periods and operands,
 * agrees with OGC 21-065r2's definitions evaluated directly. Periods may be open at either
 * end; an open end of a period and an open end of an operand compare as equal infinities.
 */
public class TestPeriodFieldOracle {

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    private static final String NS = "http://example.org/";
    private static final String FIELD = "urn:jena:lucene:field#active";

    private static final Instant BASE = Instant.parse("2021-06-30T00:00:00Z");
    private static final long STEP = ChronoUnit.HOURS.getDuration().toMillis() * 6;
    private static final long DAY = ChronoUnit.DAYS.getDuration().toMillis();
    private static final long OPEN_START = Long.MIN_VALUE;
    private static final long OPEN_END = Long.MAX_VALUE;

    private static final List<String> OPERATORS = List.of(
        "t_after", "t_before", "t_contains", "t_disjoint", "t_during", "t_equals", "t_finishedBy",
        "t_finishes", "t_intersects", "t_meets", "t_metBy", "t_overlappedBy", "t_overlaps",
        "t_startedBy", "t_starts");

    private static final String CONFIG = """
        @prefix idx:    <urn:jena:lucene:index#> .
        @prefix field:  <urn:jena:lucene:field#> .
        @prefix sh:     <http://www.w3.org/ns/shacl#> .
        @prefix schema: <https://schema.org/> .
        @prefix ex:     <http://example.org/> .
        field:active idx:fieldName "active" ; idx:fieldType idx:PeriodField .
        ex:Shape sh:targetClass ex:Thing ;
            sh:property [ idx:field field:active ; idx:self true ;
                          idx:periodStart schema:startDate ; idx:periodEnd schema:endDate ] .
        """;

    private record Span(long start, long end, String json) {}

    private final Map<String, Span> periods = new LinkedHashMap<>();
    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @BeforeEach
    public void setUp() {
        Model config = ModelFactory.createDefaultModel();
        config.read(new StringReader(CONFIG), null, "TTL");
        RDFList shapes = config.createList(new Resource[] { config.getResource(NS + "Shape") });
        ShaclIndexMapping mapping = ShaclIndexAssembler.parseShapes(Assembler.general(), shapes);
        TextIndexConfig indexConfig = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        indexConfig.setShaclMapping(mapping);
        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), new ByteBuffersDirectory(), indexConfig);
        Dataset baseDs = DatasetFactory.create();
        dataset = TextDatasetFactory.create(baseDs, textIndex, true,
            new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping));

        Random random = new Random(20261027L);
        StringBuilder data = new StringBuilder("""
            @prefix schema: <https://schema.org/> .
            @prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex:     <http://example.org/> .
            """);
        for (int i = 0; i < 60; i++) {
            long[] start = randomEnd(random, true);
            long[] end = randomEnd(random, false);
            if (start[0] == OPEN_START && end[0] == OPEN_END || start[0] > end[0]) {
                continue;
            }
            String name = "period" + i;
            periods.put(name, new Span(start[0], end[0], null));
            data.append("ex:").append(name).append(" a ex:Thing");
            appendBound(data, "schema:startDate", start, true);
            appendBound(data, "schema:endDate", end, false);
            data.append(" .\n");
        }

        dataset.begin(ReadWrite.WRITE);
        try {
            dataset.getDefaultModel().read(new StringReader(data.toString()), null, "TTL");
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
        assertTrue(periods.size() >= 30, "too few periods to test: " + periods.size());
        assertTrue(periods.values().stream().anyMatch(p -> p.start() == OPEN_START), "no period open at its start");
        assertTrue(periods.values().stream().anyMatch(p -> p.end() == OPEN_END), "no period open at its end");

        Random random = new Random(20261028L);
        List<String> disagreements = new ArrayList<>();
        int checked = 0;
        int nonEmpty = 0;
        for (int i = 0; i < 120; i++) {
            Span literal = randomLiteral(random);
            for (String op : OPERATORS) {
                checked += 2;
                nonEmpty += check(op, literal, true, disagreements) ? 1 : 0;
                nonEmpty += check(op, literal, false, disagreements) ? 1 : 0;
            }
        }
        assertTrue(disagreements.isEmpty(), disagreements.size() + " of " + checked
            + " disagree, for example:\n" + String.join("\n", disagreements.subList(0, Math.min(10, disagreements.size()))));
        assertTrue(nonEmpty > checked / 4, "only " + nonEmpty + " of " + checked + " checks expected any match");
    }

    /** True when the definition expects at least one period to match. */
    private boolean check(String op, Span literal, boolean propertyFirst, List<String> disagreements) {
        String property = "{\"property\": \"" + FIELD + "\"}";
        String json = "{\"op\": \"" + op + "\", \"args\": ["
            + (propertyFirst ? property + ", " + literal.json() : literal.json() + ", " + property) + "]}";

        Set<String> expected = new TreeSet<>();
        periods.forEach((name, period) -> {
            if (propertyFirst ? related(op, period, literal) : related(op, literal, period)) {
                expected.add(name);
            }
        });
        Set<String> actual = new TreeSet<>();
        for (TextHit hit : textIndex.queryWithCql(List.of(FIELD), null, CqlParser.parse(json),
                null, null, null, 100, null)) {
            actual.add(hit.getNode().getURI().substring(NS.length()));
        }
        if (!expected.equals(actual)) {
            disagreements.add(json + " expected " + expected + " but was " + actual);
        }
        return !expected.isEmpty();
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
                long[] start = randomEnd(random, true);
                long[] end = randomEnd(random, false);
                yield new Span(start[0], end[0],
                    "{\"interval\": [" + endJson(start, true) + ", " + endJson(end, false) + "]}");
            }
        };
    }

    /** {@code [millis, kind]}: kind 0 is a timestamp, 1 a date, 2 open. */
    private static long[] randomEnd(Random random, boolean isStart) {
        return switch (random.nextInt(5)) {
            case 0 -> new long[] { isStart ? OPEN_START : OPEN_END, 2 };
            case 1, 2 -> {
                long dayStart = randomDay(random).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                yield new long[] { isStart ? dayStart : dayStart + DAY, 1 };
            }
            default -> new long[] { gridTime(random), 0 };
        };
    }

    private static String endJson(long[] end, boolean isStart) {
        return switch ((int) end[1]) {
            case 2 -> "\"..\"";
            case 1 -> "\"" + day(end, isStart) + "\"";
            default -> "\"" + Instant.ofEpochMilli(end[0]) + "\"";
        };
    }

    /** A bound as data: a date or a dateTime, or nothing for an open end. */
    private static void appendBound(StringBuilder data, String property, long[] end, boolean isStart) {
        switch ((int) end[1]) {
            case 2 -> { }
            case 1 -> data.append(" ; ").append(property).append(" \"").append(day(end, isStart)).append("\"^^xsd:date");
            default -> data.append(" ; ").append(property).append(" \"").append(Instant.ofEpochMilli(end[0]))
                .append("\"^^xsd:dateTime");
        }
    }

    /** The day a date bound names: its start as a start, the day before its end as an end. */
    private static LocalDate day(long[] end, boolean isStart) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(isStart ? end[0] : end[0] - DAY), ZoneOffset.UTC);
    }

    private static long gridTime(Random random) {
        return BASE.toEpochMilli() + (random.nextInt(21) - 10) * STEP;
    }

    private static LocalDate randomDay(Random random) {
        return LocalDate.ofInstant(BASE, ZoneOffset.UTC).plusDays(random.nextInt(7) - 3);
    }
}
