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

package org.apache.jena.query.text.external;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.jena.query.text.ShaclIndexMapping.ColumnBinding;
import org.apache.jena.query.text.ShaclIndexMapping.ErrorPolicy;
import org.apache.jena.query.text.ShaclIndexMapping.ExternalFormat;
import org.apache.jena.query.text.ShaclIndexMapping.ExternalSourceDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.TextIndexException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Delta files applied over a base extract at build time — {@code idx:delta}.
 * <p>
 * The delta carries only what changed; the indexer reconstructs each entity's
 * <em>complete</em> child set by merging base and deltas per subject, because a
 * Lucene block has to be written whole. Nothing here updates an existing document
 * in place; that is not something Lucene offers.
 * <p>
 * <b>Semantics under test.</b> The design note said {@code DELETE} needs no value,
 * on the reasoning that a row <em>is</em> a measurement keyed by (subject,
 * property). But the same note makes duplicate (subject, property) rows legal, so
 * that pair is not a key and a valueless DELETE cannot say which child it means.
 * Resolved here as:
 * <ul>
 *   <li>a DELETE matches on the bound columns it actually fills in; an empty cell
 *       is a wildcard for that column, so {@code DELETE s Cu} removes every Cu
 *       child and {@code DELETE s Cu 0.7} removes only that one;</li>
 *   <li>numeric columns match by value, not by lexical form, so {@code 0.70}
 *       deletes {@code 0.7};</li>
 *   <li>deletes apply before adds within a subject, and ADD appends rather than
 *       upserting — with duplicates legal there is no key to upsert on. Replacing
 *       a measurement is DELETE then ADD.</li>
 * </ul>
 */
public class TestExternalDeltaSource {

    private static final FieldDef ANALYTE =
        new FieldDef("analyte", FieldType.KEYWORD, null, true, true, true, false, false, false);
    private static final FieldDef VALUE =
        new FieldDef("analyteValue", FieldType.DOUBLE, null, false, true, true, true, false, false);

    private Path dir;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("external-delta-test");
    }

    @After
    public void tearDown() throws IOException {
        if (dir != null) {
            try (var paths = Files.walk(dir)) {
                paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> p.toFile().delete());
            }
        }
    }

    private Path write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    /** Base extract: two subjects, and s1 carries two Au measurements — duplicates
     *  are legal, which is exactly what makes a valueless DELETE ambiguous. */
    private static final String BASE =
        "iri,property,value\n"
        + "http://ex.org/s1,Au,1.0\n"
        + "http://ex.org/s1,Au,2.0\n"
        + "http://ex.org/s1,Cu,50.0\n"
        + "http://ex.org/s2,Au,3.0\n";

    private ExternalSourceDef def(Path base, List<String> deltas) {
        return new ExternalSourceDef(ExternalFormat.CSV, base.toString(), "iri", -1, null,
            true, null, false, ErrorPolicy.SKIP, 0.0,
            Arrays.asList(new ColumnBinding("property", -1, ANALYTE),
                new ColumnBinding("value", -1, VALUE)),
            deltas, "op");
    }

    /** All rows as "subject|analyte|value", in emission order. */
    private static List<String> drain(ExternalRowSource source) {
        List<String> rows = new ArrayList<>();
        source.open();
        try {
            while (source.next()) {
                rows.add(source.subject() + "|" + source.value(0) + "|" + source.value(1));
            }
        } finally {
            source.close();
        }
        return rows;
    }

    private List<String> applyDelta(String deltaContent) throws IOException {
        Path base = write("base.csv", BASE);
        Path delta = write("delta.csv", deltaContent);
        return drain(ExternalRowSources.create(def(base, List.of(delta.toString()))));
    }

    // ---- tests ----

    /** With no delta rows the base passes through unchanged. */
    @Test
    public void emptyDeltaLeavesTheBaseAlone() throws IOException {
        assertEquals(Arrays.asList(
            "http://ex.org/s1|Au|1.0",
            "http://ex.org/s1|Au|2.0",
            "http://ex.org/s1|Cu|50.0",
            "http://ex.org/s2|Au|3.0"),
            applyDelta("op,iri,property,value\n"));
    }

    @Test
    public void addAppendsANewMeasurement() throws IOException {
        assertEquals(Arrays.asList(
            "http://ex.org/s1|Au|1.0",
            "http://ex.org/s1|Au|2.0",
            "http://ex.org/s1|Cu|50.0",
            "http://ex.org/s1|Ni|7.5",
            "http://ex.org/s2|Au|3.0"),
            applyDelta("op,iri,property,value\nADD,http://ex.org/s1,Ni,7.5\n"));
    }

    /** The case the design note could not express: one of two Au values, by value. */
    @Test
    public void deleteWithAValueRemovesOnlyThatMeasurement() throws IOException {
        assertEquals(Arrays.asList(
            "http://ex.org/s1|Au|1.0",
            "http://ex.org/s1|Cu|50.0",
            "http://ex.org/s2|Au|3.0"),
            applyDelta("op,iri,property,value\nDELETE,http://ex.org/s1,Au,2.0\n"));
    }

    /** An empty cell in a DELETE is a wildcard — every Au child of s1 goes. */
    @Test
    public void deleteWithoutAValueRemovesEveryMatchingMeasurement() throws IOException {
        assertEquals(Arrays.asList(
            "http://ex.org/s1|Cu|50.0",
            "http://ex.org/s2|Au|3.0"),
            applyDelta("op,iri,property,value\nDELETE,http://ex.org/s1,Au,\n"));
    }

    /** Numeric columns match by value. Requiring the lexical form to agree would make
     *  deletes fail silently whenever the producer reformatted a number. */
    @Test
    public void deleteMatchesNumericallyNotLexically() throws IOException {
        assertEquals(Arrays.asList(
            "http://ex.org/s1|Au|1.0",
            "http://ex.org/s1|Cu|50.0",
            "http://ex.org/s2|Au|3.0"),
            applyDelta("op,iri,property,value\nDELETE,http://ex.org/s1,Au,2.00\n"));

        assertEquals("and 5.0e1 is 50.0",
            Arrays.asList(
                "http://ex.org/s1|Au|1.0",
                "http://ex.org/s1|Au|2.0",
                "http://ex.org/s2|Au|3.0"),
            applyDelta("op,iri,property,value\nDELETE,http://ex.org/s1,Cu,5.0e1\n"));
    }

    /** Replacing a measurement is DELETE then ADD; deletes are applied first, so the
     *  order of the two rows in the file does not matter. */
    @Test
    public void deletesApplyBeforeAddsWithinASubject() throws IOException {
        List<String> addFirst = applyDelta(
            "op,iri,property,value\n"
            + "ADD,http://ex.org/s1,Cu,99.0\n"
            + "DELETE,http://ex.org/s1,Cu,50.0\n");
        List<String> deleteFirst = applyDelta(
            "op,iri,property,value\n"
            + "DELETE,http://ex.org/s1,Cu,50.0\n"
            + "ADD,http://ex.org/s1,Cu,99.0\n");

        assertEquals(Arrays.asList(
            "http://ex.org/s1|Au|1.0",
            "http://ex.org/s1|Au|2.0",
            "http://ex.org/s1|Cu|99.0",
            "http://ex.org/s2|Au|3.0"), deleteFirst);
        assertEquals("file order must not change the outcome", deleteFirst, addFirst);
    }

    /** A subject that exists only in the delta gets children from nothing. External
     *  rows still never create the entity itself — that is the graph's job. */
    @Test
    public void addForASubjectAbsentFromTheBase() throws IOException {
        assertEquals(Arrays.asList(
            "http://ex.org/s1|Au|1.0",
            "http://ex.org/s1|Au|2.0",
            "http://ex.org/s1|Cu|50.0",
            "http://ex.org/s2|Au|3.0",
            "http://ex.org/s3|Zn|4.0"),
            applyDelta("op,iri,property,value\nADD,http://ex.org/s3,Zn,4.0\n"));
    }

    /** Deleting everything leaves the subject with no children at all — which is a
     *  parent document with graph fields only, not a deleted document. */
    @Test
    public void deletingEverySubjectRowLeavesNoChildren() throws IOException {
        assertEquals(List.of("http://ex.org/s1|Au|1.0",
                "http://ex.org/s1|Au|2.0",
                "http://ex.org/s1|Cu|50.0"),
            applyDelta("op,iri,property,value\nDELETE,http://ex.org/s2,,\n"));
    }

    /** Several deltas apply in the order configured, so a later one sees the effect
     *  of an earlier one. */
    @Test
    public void multipleDeltasApplyInOrder() throws IOException {
        Path base = write("base.csv", BASE);
        Path first = write("d1.csv", "op,iri,property,value\nADD,http://ex.org/s2,Pb,9.0\n");
        Path second = write("d2.csv", "op,iri,property,value\nDELETE,http://ex.org/s2,Pb,9.0\n");

        assertEquals("the second delta removes what the first added",
            Arrays.asList(
                "http://ex.org/s1|Au|1.0",
                "http://ex.org/s1|Au|2.0",
                "http://ex.org/s1|Cu|50.0",
                "http://ex.org/s2|Au|3.0"),
            drain(ExternalRowSources.create(
                def(base, List.of(first.toString(), second.toString())))));
    }

    /** A delete that matches nothing is not an error — deltas are routinely replayed
     *  or overlap — but it must not silently corrupt anything either. */
    @Test
    public void deleteMatchingNothingIsHarmless() throws IOException {
        assertEquals(Arrays.asList(
            "http://ex.org/s1|Au|1.0",
            "http://ex.org/s1|Au|2.0",
            "http://ex.org/s1|Cu|50.0",
            "http://ex.org/s2|Au|3.0"),
            applyDelta("op,iri,property,value\nDELETE,http://ex.org/s1,Sn,1.0\n"));
    }

    @Test
    public void unknownOpIsAConfigError() throws IOException {
        TextIndexException e = assertThrows(TextIndexException.class,
            () -> applyDelta("op,iri,property,value\nUPSERT,http://ex.org/s1,Au,1.0\n"));
        assertTrue(e.getMessage(), e.getMessage().contains("UPSERT"));
    }

    /** The per-subject merge needs both sides on one ordering; there is no meaningful
     *  way to apply a delta to an unordered base while streaming. */
    @Test
    public void deltasRequireSortedInput() throws IOException {
        Path base = write("base.csv", BASE);
        TextIndexException e = assertThrows(TextIndexException.class,
            () -> new ExternalSourceDef(ExternalFormat.CSV, base.toString(), "iri", -1, null,
                false, null, false, ErrorPolicy.SKIP, 0.0,
                Arrays.asList(new ColumnBinding("property", -1, ANALYTE),
                    new ColumnBinding("value", -1, VALUE)),
                List.of("delta.csv"), "op"));
        assertTrue(e.getMessage(), e.getMessage().contains("idx:sorted"));
    }

    /** Sortedness is verified on the delta too, not just the base. */
    @Test
    public void unsortedDeltaIsRejected() throws IOException {
        assertThrows(TextIndexException.class, () -> applyDelta(
            "op,iri,property,value\n"
            + "ADD,http://ex.org/s2,Pb,9.0\n"
            + "ADD,http://ex.org/s1,Pb,8.0\n"));
    }

    /** Without deltas configured the plain CSV source is used — no wrapper, no cost. */
    @Test
    public void noDeltaMeansNoWrapper() throws IOException {
        Path base = write("base.csv", BASE);
        ExternalRowSource source = ExternalRowSources.create(def(base, Collections.emptyList()));
        assertTrue(source instanceof CsvRowSource);
        source.close();
    }
}
