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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * {@link SortingRowSource} is what replaced the old {@code idx:sorted} assertion, so
 * the cases that matter are the ones that assertion used to reject: descending input,
 * ungrouped repeats, and anything too large to hold in memory.
 * <p>
 * Each ordering case is exercised twice — once wholly in memory and once with a
 * one-row buffer, which forces a spilled run per row and drives the k-way merge. The
 * two paths share no code beyond the comparator, so both need covering.
 */
public class TestSortingRowSource {

    /** Rows in file order: descending subjects, the case that used to fail the build. */
    private static List<String[]> descending() {
        return Arrays.asList(
            new String[] {"https://ex.org/s/A3", "Au", "3.0"},
            new String[] {"https://ex.org/s/A2", "Cu", "2.0"},
            new String[] {"https://ex.org/s/A1", "Pb", "1.0"});
    }

    @Test
    public void sortsDescendingInputInMemory() {
        assertEquals(Arrays.asList(
            "https://ex.org/s/A1|Pb|1.0",
            "https://ex.org/s/A2|Cu|2.0",
            "https://ex.org/s/A3|Au|3.0"), drainSorted(descending(), 1000));
    }

    @Test
    public void sortsDescendingInputViaSpilledRuns() {
        assertEquals(Arrays.asList(
            "https://ex.org/s/A1|Pb|1.0",
            "https://ex.org/s/A2|Cu|2.0",
            "https://ex.org/s/A3|Au|3.0"), drainSorted(descending(), 1));
    }

    /** Interleaved repeats must come back grouped: a block join needs an entity's
     *  children together, which was the real reason ordering was demanded. */
    private static List<String[]> ungrouped() {
        return Arrays.asList(
            new String[] {"https://ex.org/s/A1", "Au", "1.0"},
            new String[] {"https://ex.org/s/A2", "Au", "2.0"},
            new String[] {"https://ex.org/s/A1", "Cu", "3.0"});
    }

    @Test
    public void groupsUngroupedRepeatsInMemory() {
        assertEquals(Arrays.asList(
            "https://ex.org/s/A1|Au|1.0",
            "https://ex.org/s/A1|Cu|3.0",
            "https://ex.org/s/A2|Au|2.0"), drainSorted(ungrouped(), 1000));
    }

    @Test
    public void groupsUngroupedRepeatsViaSpilledRuns() {
        assertEquals(Arrays.asList(
            "https://ex.org/s/A1|Au|1.0",
            "https://ex.org/s/A1|Cu|3.0",
            "https://ex.org/s/A2|Au|2.0"), drainSorted(ungrouped(), 1));
    }

    /**
     * The sort is stable: rows sharing a subject keep their input order. Duplicate
     * (subject, property) rows are legal and a delta's rows are position-sensitive, so
     * reordering within a subject would change meaning.
     */
    @Test
    public void isStableWithinASubject() {
        List<String[]> rows = Arrays.asList(
            new String[] {"https://ex.org/s/A1", "Au", "first"},
            new String[] {"https://ex.org/s/A1", "Au", "second"},
            new String[] {"https://ex.org/s/A1", "Au", "third"});

        List<String> expected = Arrays.asList(
            "https://ex.org/s/A1|Au|first",
            "https://ex.org/s/A1|Au|second",
            "https://ex.org/s/A1|Au|third");

        assertEquals("in memory", expected, drainSorted(rows, 1000));
        assertEquals("stability must survive the run merge too", expected, drainSorted(rows, 1));
    }

    /** A null cell is distinct from an empty one, and the spill format has to keep
     *  them apart across a round trip through disk. */
    @Test
    public void preservesNullAndEmptyCellsThroughASpill() {
        List<String[]> rows = Arrays.asList(
            new String[] {"https://ex.org/s/A2", null, ""},
            new String[] {"https://ex.org/s/A1", "", null});

        for (int buffer : new int[] {1000, 1}) {
            StubRowSource stub = new StubRowSource(rows, 2);
            SortingRowSource source = new SortingRowSource(stub, buffer);
            source.open();

            assertTrue(source.next());
            assertEquals("https://ex.org/s/A1", source.subject());
            assertEquals("", source.value(0));
            assertEquals(null, source.value(1));

            assertTrue(source.next());
            assertEquals("https://ex.org/s/A2", source.subject());
            assertEquals(null, source.value(0));
            assertEquals("", source.value(1));

            assertFalse(source.next());
            source.close();
        }
    }

    /** Non-ASCII subjects sort by their UTF-8 round trip, not by whatever the run
     *  file happened to encode. */
    @Test
    public void roundTripsNonAsciiThroughASpill() {
        List<String[]> rows = Arrays.asList(
            new String[] {"https://ex.org/s/Ü", "Au", "1.0"},
            new String[] {"https://ex.org/s/A", "Cu", "2.0"});

        assertEquals(Arrays.asList(
            "https://ex.org/s/A|Cu|2.0",
            "https://ex.org/s/Ü|Au|1.0"), drainSorted(rows, 1));
    }

    @Test
    public void handlesEmptyInput() {
        assertTrue(drainSorted(new ArrayList<>(), 1000).isEmpty());
        assertTrue(drainSorted(new ArrayList<>(), 1).isEmpty());
    }

    /** Already-sorted input is the common case and must pass through unchanged. */
    @Test
    public void leavesSortedInputAlone() {
        List<String[]> rows = Arrays.asList(
            new String[] {"https://ex.org/s/A1", "Au", "1.0"},
            new String[] {"https://ex.org/s/A2", "Cu", "2.0"},
            new String[] {"https://ex.org/s/A3", "Pb", "3.0"});

        assertEquals(Arrays.asList(
            "https://ex.org/s/A1|Au|1.0",
            "https://ex.org/s/A2|Cu|2.0",
            "https://ex.org/s/A3|Pb|3.0"), drainSorted(rows, 1000));
    }

    /** Enough rows to spill many runs, shuffled deterministically. */
    @Test
    public void mergesManyRunsInOrder() {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            int scrambled = (i * 137) % 500;      // fixed permutation, no RNG
            rows.add(new String[] {String.format("https://ex.org/s/%04d", scrambled), "Au", "1.0"});
        }

        List<String> sorted = drainSorted(rows, 16);
        assertEquals(500, sorted.size());
        for (int i = 0; i < 500; i++) {
            assertEquals(String.format("https://ex.org/s/%04d|Au|1.0", i), sorted.get(i));
        }
    }

    /** rowsRead reports the underlying input, not the sorted output. */
    @Test
    public void reportsDelegateRowsRead() {
        StubRowSource stub = new StubRowSource(descending(), 2);
        SortingRowSource source = new SortingRowSource(stub, 1);
        source.open();
        assertEquals(3, source.rowsRead());
        source.close();
    }

    // ----- helpers -----

    private static List<String> drainSorted(List<String[]> rows, int bufferRows) {
        SortingRowSource source = new SortingRowSource(new StubRowSource(rows, 2), bufferRows);
        List<String> out = new ArrayList<>();
        source.open();
        while (source.next()) {
            StringBuilder sb = new StringBuilder(source.subject());
            for (int i = 0; i < source.bindingCount(); i++) {
                sb.append('|').append(source.value(i));
            }
            out.add(sb.toString());
        }
        source.close();
        return out;
    }

    /** In-memory {@link ExternalRowSource}; row[0] is the subject, the rest bindings. */
    private static final class StubRowSource implements ExternalRowSource {
        private final List<String[]> rows;
        private final int bindingCount;
        private int index = -1;

        StubRowSource(List<String[]> rows, int bindingCount) {
            this.rows = rows;
            this.bindingCount = bindingCount;
        }

        @Override
        public void open() {
            index = -1;
        }

        @Override
        public boolean next() {
            return ++index < rows.size();
        }

        @Override
        public String subject() {
            return rows.get(index)[0];
        }

        @Override
        public String value(int bindingIndex) {
            return rows.get(index)[bindingIndex + 1];
        }

        @Override
        public int bindingCount() {
            return bindingCount;
        }

        @Override
        public long rowsRead() {
            return Math.min(index + 1, rows.size());
        }

        @Override
        public String describe() {
            return "stub";
        }

        @Override
        public void close() {}
    }
}
