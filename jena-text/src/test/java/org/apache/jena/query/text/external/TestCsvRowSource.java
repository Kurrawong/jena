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
import java.util.List;

import org.apache.jena.query.text.TextIndexException;
import org.apache.jena.query.text.ShaclIndexMapping.ColumnBinding;
import org.apache.jena.query.text.ShaclIndexMapping.ErrorPolicy;
import org.apache.jena.query.text.ShaclIndexMapping.ExternalFormat;
import org.apache.jena.query.text.ShaclIndexMapping.ExternalSourceDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit coverage for {@link CsvRowSource} — the delimited-text implementation of the
 * {@link ExternalRowSource} SPI. Exercises header and headerless binding, the
 * subject prefix, glob expansion across files, TSV, and the {@code idx:sorted}
 * order check that turns a silently-mostly-unmatched build into a loud failure.
 */
public class TestCsvRowSource {

    private static final FieldDef PROPERTY_FIELD =
        new FieldDef("measuredProperty", FieldType.KEYWORD, null, true, true, true, false, false, false);
    private static final FieldDef VALUE_FIELD =
        new FieldDef("measuredValue", FieldType.DOUBLE, null, false, true, true, true, false, false);

    private Path dir;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("csv-row-source-test");
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

    private static ExternalSourceDef sourceDef(ExternalFormat format, String location,
                                               String subjectColumn, String subjectPrefix,
                                               boolean sorted) {
        return new ExternalSourceDef(format, location, subjectColumn, -1, subjectPrefix,
            sorted, null, false, ErrorPolicy.SKIP, 0.0,
            Arrays.asList(new ColumnBinding("property", -1, PROPERTY_FIELD),
                new ColumnBinding("value", -1, VALUE_FIELD)));
    }

    /** All rows as "subject|binding0|binding1", in read order. */
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

    @Test
    public void readsNarrowCsvByHeaderName() throws IOException {
        Path file = write("m.csv",
            "sample_iri,property,value\n"
            + "https://ex.org/s/A1,Au,12.4\n"
            + "https://ex.org/s/A1,Cu,0.7\n"
            + "https://ex.org/s/A2,Au,0.3\n");

        List<String> rows = drain(new CsvRowSource(
            sourceDef(ExternalFormat.CSV, file.toString(), "sample_iri", null, true)));

        assertEquals(Arrays.asList(
            "https://ex.org/s/A1|Au|12.4",
            "https://ex.org/s/A1|Cu|0.7",
            "https://ex.org/s/A2|Au|0.3"), rows);
    }

    /** Column order in the file is irrelevant — bindings resolve by header name. */
    @Test
    public void bindsByHeaderNameNotPosition() throws IOException {
        Path file = write("m.csv",
            "value,sample_iri,property\n"
            + "12.4,https://ex.org/s/A1,Au\n");

        List<String> rows = drain(new CsvRowSource(
            sourceDef(ExternalFormat.CSV, file.toString(), "sample_iri", null, true)));

        assertEquals(List.of("https://ex.org/s/A1|Au|12.4"), rows);
    }

    @Test
    public void appliesSubjectPrefix() throws IOException {
        Path file = write("m.csv",
            "sample_id,property,value\n"
            + "A1,Au,12.4\n");

        List<String> rows = drain(new CsvRowSource(
            sourceDef(ExternalFormat.CSV, file.toString(), "sample_id", "https://ex.org/id/sample/", true)));

        assertEquals(List.of("https://ex.org/id/sample/A1|Au|12.4"), rows);
    }

    @Test
    public void readsTsv() throws IOException {
        Path file = write("m.tsv",
            "sample_iri\tproperty\tvalue\n"
            + "https://ex.org/s/A1\tAu\t12.4\n");

        List<String> rows = drain(new CsvRowSource(
            sourceDef(ExternalFormat.TSV, file.toString(), "sample_iri", null, true)));

        assertEquals(List.of("https://ex.org/s/A1|Au|12.4"), rows);
    }

    @Test
    public void readsHeaderlessByColumnIndex() throws IOException {
        Path file = write("m.csv",
            "https://ex.org/s/A1,Au,12.4\n"
            + "https://ex.org/s/A2,Cu,0.7\n");

        ExternalSourceDef def = new ExternalSourceDef(ExternalFormat.CSV, file.toString(),
            null, 0, null, true, null, true, ErrorPolicy.SKIP, 0.0,
            Arrays.asList(new ColumnBinding(null, 1, PROPERTY_FIELD),
                new ColumnBinding(null, 2, VALUE_FIELD)));

        assertEquals(Arrays.asList(
            "https://ex.org/s/A1|Au|12.4",
            "https://ex.org/s/A2|Cu|0.7"), drain(new CsvRowSource(def)));
    }

    /** A glob reads the matching files in sorted filename order, so a set of shards
     *  that is individually sorted and range-disjoint stays globally sorted. */
    @Test
    public void expandsGlobAcrossFilesInNameOrder() throws IOException {
        write("meas-2.csv", "sample_iri,property,value\nhttps://ex.org/s/B1,Cu,2.0\n");
        write("meas-1.csv", "sample_iri,property,value\nhttps://ex.org/s/A1,Au,1.0\n");

        List<String> rows = drain(new CsvRowSource(sourceDef(
            ExternalFormat.CSV, dir.resolve("meas-*.csv").toString(), "sample_iri", null, true)));

        assertEquals(Arrays.asList(
            "https://ex.org/s/A1|Au|1.0",
            "https://ex.org/s/B1|Cu|2.0"), rows);
    }

    /** Empty cells are null, not "" — the merge layer skips a row with no value
     *  rather than coercing it to zero. */
    @Test
    public void emptyCellReadsAsNull() throws IOException {
        Path file = write("m.csv",
            "sample_iri,property,value\n"
            + "https://ex.org/s/A1,Au,\n");

        CsvRowSource source = new CsvRowSource(
            sourceDef(ExternalFormat.CSV, file.toString(), "sample_iri", null, true));
        source.open();
        try {
            assertTrue(source.next());
            assertEquals("Au", source.value(0));
            assertNull(source.value(1));
        } finally {
            source.close();
        }
    }

    /** A row with a blank join key cannot be attached to any entity. It is skipped,
     *  but still counted as read. */
    @Test
    public void skipsRowsWithBlankSubject() throws IOException {
        Path file = write("m.csv",
            "sample_iri,property,value\n"
            + ",Au,12.4\n"
            + "https://ex.org/s/A1,Cu,0.7\n");

        CsvRowSource source = new CsvRowSource(
            sourceDef(ExternalFormat.CSV, file.toString(), "sample_iri", null, true));
        List<String> rows = drain(source);

        assertEquals(List.of("https://ex.org/s/A1|Cu|0.7"), rows);
        assertEquals("blank-subject row is read then discarded", 2, source.rowsRead());
    }

    /** idx:sorted true is verified, not trusted: an out-of-order file fails loudly
     *  instead of merging to mostly-unmatched. */
    @Test
    public void verifiesAssertedSortOrder() throws IOException {
        Path file = write("m.csv",
            "sample_iri,property,value\n"
            + "https://ex.org/s/A2,Au,12.4\n"
            + "https://ex.org/s/A1,Cu,0.7\n");

        CsvRowSource source = new CsvRowSource(
            sourceDef(ExternalFormat.CSV, file.toString(), "sample_iri", null, true));
        try {
            TextIndexException e = assertThrows(TextIndexException.class, () -> drain(source));
            assertTrue("message should name both subjects: " + e.getMessage(),
                e.getMessage().contains("A2") && e.getMessage().contains("A1"));
        } finally {
            source.close();
        }
    }

    /** Interleaved (ungrouped) subjects break the merge just as badly as descending
     *  order, so the same check catches them. */
    @Test
    public void verifiesGroupingOfRepeatedSubjects() throws IOException {
        Path file = write("m.csv",
            "sample_iri,property,value\n"
            + "https://ex.org/s/A1,Au,1.0\n"
            + "https://ex.org/s/A2,Au,2.0\n"
            + "https://ex.org/s/A1,Cu,3.0\n");

        CsvRowSource source = new CsvRowSource(
            sourceDef(ExternalFormat.CSV, file.toString(), "sample_iri", null, true));
        assertThrows(TextIndexException.class, () -> drain(source));
        source.close();
    }

    /** Without the idx:sorted assertion no order check runs — the indexer buffers instead. */
    @Test
    public void unsortedSourceDoesNotCheckOrder() throws IOException {
        Path file = write("m.csv",
            "sample_iri,property,value\n"
            + "https://ex.org/s/A2,Au,12.4\n"
            + "https://ex.org/s/A1,Cu,0.7\n");

        CsvRowSource source = new CsvRowSource(
            sourceDef(ExternalFormat.CSV, file.toString(), "sample_iri", null, false));

        assertFalse(source.isSorted());
        assertEquals(Arrays.asList(
            "https://ex.org/s/A2|Au|12.4",
            "https://ex.org/s/A1|Cu|0.7"), drain(source));
    }

    @Test
    public void missingSubjectColumnIsAConfigError() throws IOException {
        Path file = write("m.csv", "wrong_name,property,value\nA1,Au,12.4\n");

        CsvRowSource source = new CsvRowSource(
            sourceDef(ExternalFormat.CSV, file.toString(), "sample_iri", null, true));
        TextIndexException e = assertThrows(TextIndexException.class, source::open);
        assertTrue(e.getMessage().contains("sample_iri"));
        source.close();
    }

    @Test
    public void missingBoundColumnIsAConfigError() throws IOException {
        Path file = write("m.csv", "sample_iri,property\nA1,Au\n");

        CsvRowSource source = new CsvRowSource(
            sourceDef(ExternalFormat.CSV, file.toString(), "sample_iri", null, true));
        TextIndexException e = assertThrows(TextIndexException.class, source::open);
        assertTrue(e.getMessage().contains("value"));
        source.close();
    }

    @Test
    public void missingFileIsAConfigError() {
        CsvRowSource source = new CsvRowSource(sourceDef(
            ExternalFormat.CSV, dir.resolve("absent.csv").toString(), "sample_iri", null, true));
        assertThrows(TextIndexException.class, source::open);
        source.close();
    }

    @Test
    public void globMatchingNothingIsAConfigError() {
        CsvRowSource source = new CsvRowSource(sourceDef(
            ExternalFormat.CSV, dir.resolve("none-*.csv").toString(), "sample_iri", null, true));
        assertThrows(TextIndexException.class, source::open);
        source.close();
    }
}
