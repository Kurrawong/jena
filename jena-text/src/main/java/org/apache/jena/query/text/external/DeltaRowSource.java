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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.jena.query.text.ShaclIndexMapping.ColumnBinding;
import org.apache.jena.query.text.ShaclIndexMapping.ExternalSourceDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.TextIndexException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A base extract with delta files applied over it, presented as one row stream.
 * <p>
 * The delta carries only what changed. This reader reconstructs each entity's
 * <em>complete</em> child set by merging base and delta rows subject by subject, which
 * is what the indexer needs: Lucene has no partial document update and a block join
 * must be written whole, so an entity's children have to be in hand together. Nothing
 * here edits an existing document — the result is still a full rebuild, just one that
 * does not require the base and its deltas to be physically merged first.
 * <p>
 * All inputs must be sorted by subject ({@code idx:sorted true}), which
 * {@link CsvRowSource} verifies as it reads. Only one subject's rows are held at a
 * time, so memory stays bounded by the largest entity rather than by the file.
 *
 * <h2>Operation semantics</h2>
 * <ul>
 *   <li><b>DELETE</b> matches on the bound columns it fills in; an empty cell is a
 *       wildcard for that column. {@code DELETE s Cu} removes every Cu child of
 *       {@code s}; {@code DELETE s Cu 0.7} removes only that measurement. Numeric
 *       columns match by value, so {@code 0.70} deletes {@code 0.7}.
 *   <li><b>ADD</b> appends. It is deliberately not an upsert: duplicate
 *       (subject, property) rows are legal, so there is no key to upsert on.
 *       Replacing a measurement is a DELETE followed by an ADD.
 *   <li>Within a subject, <b>deletes apply before adds</b>, so the order of rows in
 *       the file cannot change the outcome.
 * </ul>
 * A delete that matches nothing is not an error — deltas get replayed and overlap —
 * but the count is reported.
 */
public class DeltaRowSource implements ExternalRowSource {
    private static final Logger log = LoggerFactory.getLogger(DeltaRowSource.class);

    /** Internal field for the operation column; consumed here, never indexed. */
    private static final FieldDef OP_FIELD = new FieldDef(
        "_deltaOp", FieldType.KEYWORD, null, false, false, false, false, false, false);

    private static final String OP_ADD = "ADD";
    private static final String OP_DELETE = "DELETE";

    private final ExternalSourceDef def;
    private final int bindingCount;
    private final Cursor base;
    private final List<Cursor> deltas;

    /** Rows of the subject currently being emitted. */
    private List<String[]> current = Collections.emptyList();
    private Iterator<String[]> currentIter = Collections.emptyIterator();
    private String subject;
    private String[] values;

    private long rowsAdded;
    private long rowsDeleted;
    private long deletesMatchingNothing;

    public DeltaRowSource(ExternalSourceDef def) {
        this.def = def;
        this.bindingCount = def.getColumns().size();
        this.base = new Cursor(ExternalRowSources.create(def.withoutDeltas()), bindingCount);
        this.deltas = new ArrayList<>(def.getDeltaLocations().size());
        for (String location : def.getDeltaLocations()) {
            // One extra binding for the op column; asDeltaLayer puts it last.
            this.deltas.add(new Cursor(
                new CsvRowSource(def.asDeltaLayer(location, OP_FIELD)), bindingCount + 1));
        }
    }

    @Override
    public void open() {
        base.open();
        for (Cursor delta : deltas) {
            delta.open();
        }
    }

    @Override
    public boolean next() {
        while (!currentIter.hasNext()) {
            if (!advanceSubject()) {
                return false;
            }
        }
        values = currentIter.next();
        return true;
    }

    /**
     * Move to the next subject present on any input, build its effective row set, and
     * make it current. Returns false when every input is exhausted.
     */
    private boolean advanceSubject() {
        String next = base.subject();
        for (Cursor delta : deltas) {
            String candidate = delta.subject();
            if (candidate != null && (next == null || candidate.compareTo(next) < 0)) {
                next = candidate;
            }
        }
        if (next == null) {
            return false;
        }

        List<String[]> rows = base.take(next);
        for (Cursor delta : deltas) {
            for (String[] row : delta.take(next)) {
                apply(row, rows, next);
            }
        }

        subject = next;
        current = rows;
        currentIter = rows.iterator();
        return true;
    }

    /** Apply one delta row to the working set for a subject. */
    private void apply(String[] deltaRow, List<String[]> rows, String forSubject) {
        String op = deltaRow[bindingCount];   // asDeltaLayer appended the op column last
        if (op == null) {
            throw new TextIndexException(
                "Delta row for '" + forSubject + "' has no value in the '" + def.getOpColumn()
                + "' column. Every row must be " + OP_ADD + " or " + OP_DELETE + ".");
        }
        String normalised = op.strip().toUpperCase(java.util.Locale.ROOT);
        String[] payload = java.util.Arrays.copyOf(deltaRow, bindingCount);

        if (OP_ADD.equals(normalised)) {
            rows.add(payload);
            rowsAdded++;
            return;
        }
        if (OP_DELETE.equals(normalised)) {
            int before = rows.size();
            rows.removeIf(row -> matches(payload, row));
            int removed = before - rows.size();
            rowsDeleted += removed;
            if (removed == 0) {
                deletesMatchingNothing++;
            }
            return;
        }
        throw new TextIndexException(
            "Unknown operation '" + op + "' in the '" + def.getOpColumn() + "' column for '"
            + forSubject + "'. Expected " + OP_ADD + " or " + OP_DELETE + ".");
    }

    /**
     * Does {@code candidate} match the pattern of a DELETE row? A null in the pattern
     * is a wildcard, so a DELETE that fills in fewer columns removes more rows.
     */
    private boolean matches(String[] pattern, String[] candidate) {
        for (int i = 0; i < bindingCount; i++) {
            if (pattern[i] == null) {
                continue;
            }
            if (!valuesEqual(def.getColumns().get(i), pattern[i], candidate[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Numeric columns compare by value, not by lexical form: a producer that writes
     * {@code 0.70} where the base said {@code 0.7} should still be able to delete it,
     * and a delete that silently matches nothing is the worst outcome available here.
     */
    private static boolean valuesEqual(ColumnBinding binding, String pattern, String candidate) {
        if (candidate == null) {
            return false;
        }
        switch (binding.getField().getFieldType()) {
            case INT: case LONG: case DOUBLE:
                try {
                    return Double.compare(Double.parseDouble(pattern.strip()),
                        Double.parseDouble(candidate.strip())) == 0;
                } catch (NumberFormatException e) {
                    return pattern.equals(candidate);   // unparseable: fall back to text
                }
            default:
                return pattern.equals(candidate);
        }
    }

    @Override
    public String subject() {
        return subject;
    }

    @Override
    public String value(int bindingIndex) {
        return values[bindingIndex];
    }

    @Override
    public int bindingCount() {
        return bindingCount;
    }

    @Override
    public boolean isSorted() {
        return true;   // guaranteed: ExternalSourceDef rejects deltas on an unsorted source
    }

    @Override
    public long rowsRead() {
        long total = base.source.rowsRead();
        for (Cursor delta : deltas) {
            total += delta.source.rowsRead();
        }
        return total;
    }

    @Override
    public String describe() {
        return base.source.describe() + " + " + deltas.size() + " delta(s) "
            + def.getDeltaLocations();
    }

    @Override
    public void close() {
        log.info("Delta source {}: {} rows added, {} rows deleted, {} deletes matched nothing",
            describe(), rowsAdded, rowsDeleted, deletesMatchingNothing);
        if (deletesMatchingNothing > 0) {
            log.warn("{} DELETE row(s) in {} matched no measurement. Harmless if the delta is "
                + "being replayed; otherwise check the column values.",
                deletesMatchingNothing, def.getDeltaLocations());
        }
        base.close();
        for (Cursor delta : deltas) {
            delta.close();
        }
    }

    /** One input, positioned on its next unconsumed row. */
    private static final class Cursor {
        private final ExternalRowSource source;
        private final int width;
        private String pendingSubject;
        private String[] pendingValues;
        private boolean exhausted;

        Cursor(ExternalRowSource source, int width) {
            this.source = source;
            this.width = width;
        }

        void open() {
            source.open();
            advance();
        }

        private void advance() {
            if (source.next()) {
                pendingSubject = source.subject();
                pendingValues = new String[width];
                for (int i = 0; i < width; i++) {
                    pendingValues[i] = source.value(i);
                }
            } else {
                pendingSubject = null;
                pendingValues = null;
                exhausted = true;
            }
        }

        /** Subject of the next unconsumed row, or null at end of input. */
        String subject() {
            return exhausted ? null : pendingSubject;
        }

        /** Consume and return every consecutive row for {@code wanted}. */
        List<String[]> take(String wanted) {
            List<String[]> rows = new ArrayList<>();
            while (!exhausted && pendingSubject.equals(wanted)) {
                rows.add(pendingValues);
                advance();
            }
            return rows;
        }

        void close() {
            source.close();
        }
    }
}
