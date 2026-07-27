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

import java.io.Closeable;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.jena.graph.Node;
import org.apache.jena.query.text.Entity;
import org.apache.jena.query.text.ShaclIndexMapping;
import org.apache.jena.query.text.ShaclIndexMapping.ColumnBinding;
import org.apache.jena.query.text.ShaclIndexMapping.ErrorPolicy;
import org.apache.jena.query.text.ShaclIndexMapping.ExternalSourceDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.TextIndexException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Joins external source rows onto graph-derived entities during a bulk build,
 * emitting one nested child record per row.
 * <p>
 * Callers must present entities in ascending {@code entityUri} order — the same
 * order the sources assert with {@code idx:sorted} — so the join is a single
 * sort-merge pass: O(N + M), constant memory, sequential I/O. This is not merely an
 * optimisation. Lucene has no partial document update and a block join must be
 * written atomically, so <em>all</em> of an entity's children have to be in hand
 * before anything is written; grouping by subject is therefore mandatory.
 * <p>
 * A source that does not assert {@code idx:sorted} is buffered into memory instead.
 * That is fine for a small sidecar and untenable at scale — sort the source, or push
 * an {@code ORDER BY} into whatever produced it.
 * <p>
 * External rows <b>augment</b> entities; they never create them. A row whose subject
 * matches no entity is counted and dropped, because extracts are routinely broader
 * than the graph. The count is always reported, and {@code idx:minMatchRate} turns a
 * catastrophically low match — the signature of a wrong {@code idx:subjectPrefix} —
 * into a hard failure.
 */
public class ExternalChildMerger implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(ExternalChildMerger.class);
    private static final NumberFormat COUNT_FORMAT = NumberFormat.getIntegerInstance(Locale.US);

    private final Map<Node, List<SourceCursor>> cursorsByShape = new LinkedHashMap<>();
    private final List<SourceCursor> allCursors = new ArrayList<>();
    private boolean opened;

    public ExternalChildMerger(ShaclIndexMapping mapping) {
        for (IndexProfile profile : mapping.getProfiles()) {
            for (NestedDef nestedDef : profile.getExternalNestedDefs()) {
                SourceCursor cursor = new SourceCursor(profile, nestedDef);
                cursorsByShape.computeIfAbsent(profile.getShapeNode(), k -> new ArrayList<>()).add(cursor);
                allCursors.add(cursor);
            }
        }
    }

    /** True when there is nothing to merge — the caller can skip the whole path. */
    public boolean isEmpty() {
        return allCursors.isEmpty();
    }

    public void open() {
        if (opened) {
            throw new TextIndexException("ExternalChildMerger already open");
        }
        opened = true;
        for (SourceCursor cursor : allCursors) {
            cursor.open();
        }
    }

    /**
     * Attach this entity's external children to {@code entity}. Entities must arrive
     * in ascending {@code entityUri} order.
     */
    public void attach(IndexProfile profile, String entityUri, Entity entity) {
        List<SourceCursor> cursors = cursorsByShape.get(profile.getShapeNode());
        if (cursors == null) {
            return;
        }
        for (SourceCursor cursor : cursors) {
            cursor.attach(entityUri, entity);
        }
    }

    /**
     * Drain what is left of every source so unmatched rows are counted rather than
     * silently ignored, report the counters, and enforce {@code idx:minMatchRate}.
     */
    public void finish() {
        for (SourceCursor cursor : allCursors) {
            cursor.drain();
            cursor.report();
        }
        for (SourceCursor cursor : allCursors) {
            cursor.enforceMinMatchRate();
        }
    }

    /** Counters per configured source, in declaration order. Chiefly for diagnostics and tests. */
    public List<SourceStats> getStats() {
        List<SourceStats> stats = new ArrayList<>(allCursors.size());
        for (SourceCursor cursor : allCursors) {
            stats.add(cursor.stats());
        }
        return Collections.unmodifiableList(stats);
    }

    @Override
    public void close() {
        for (SourceCursor cursor : allCursors) {
            cursor.close();
        }
    }

    /** Outcome counters for one {@code idx:externalSource}. */
    public record SourceStats(String nestedName, String location,
                              long rowsRead, long rowsMatched, long rowsUnmatched, long rowsSkipped,
                              long entitiesSeen, long entitiesMatched) {

        /** Fraction of the profile's entities that received at least one child. */
        public double matchRate() {
            return entitiesSeen == 0 ? 0.0 : (double) entitiesMatched / entitiesSeen;
        }
    }

    // ----- one configured source -----

    private static final class SourceCursor {
        private final IndexProfile profile;
        private final NestedDef nestedDef;
        private final ExternalSourceDef def;
        private final ExternalRowSource source;

        /** Buffered rows by subject; null while streaming a sorted source. */
        private Map<String, List<String[]>> buffer;
        private String pendingSubject;
        private String[] pendingValues;
        private boolean exhausted;
        private String lastAttachedSubject;

        private long rowsMatched;
        private long rowsUnmatched;
        private long rowsSkipped;
        private long entitiesSeen;
        private long entitiesMatched;

        SourceCursor(IndexProfile profile, NestedDef nestedDef) {
            this.profile = profile;
            this.nestedDef = nestedDef;
            this.def = nestedDef.getExternalSource();
            this.source = ExternalRowSources.create(def);
        }

        void open() {
            source.open();
            if (source.isSorted()) {
                advance();
            } else {
                bufferAll();
            }
        }

        /**
         * Unsorted source: read it whole and group by subject. The streaming merge is
         * impossible without an ordering, and a block join cannot be assembled from
         * rows arriving out of order.
         */
        private void bufferAll() {
            buffer = new LinkedHashMap<>();
            while (source.next()) {
                buffer.computeIfAbsent(source.subject(), k -> new ArrayList<>()).add(snapshot());
            }
            log.warn("External source {} is not declared idx:sorted — buffered {} subjects in memory. "
                    + "Sort the source by {} and set idx:sorted true before using it at scale.",
                describe(), formatCount(buffer.size()),
                def.isHeaderless() ? "column " + def.getSubjectColumnIndex() : def.getSubjectColumn());
            source.close();
        }

        private String[] snapshot() {
            String[] row = new String[source.bindingCount()];
            for (int i = 0; i < row.length; i++) {
                row[i] = source.value(i);
            }
            return row;
        }

        private void advance() {
            if (source.next()) {
                pendingSubject = source.subject();
                pendingValues = snapshot();
            } else {
                pendingSubject = null;
                pendingValues = null;
                exhausted = true;
            }
        }

        void attach(String entityUri, Entity entity) {
            checkAscending(entityUri);
            entitiesSeen++;
            boolean attached = false;
            for (String[] row : rowsFor(entityUri)) {
                Entity.NestedRecord record = toRecord(row, entityUri);
                if (record == null) {
                    rowsSkipped++;
                    continue;
                }
                entity.addNestedRecord(nestedDef.getNestedName(), record);
                rowsMatched++;
                attached = true;
            }
            if (attached) {
                entitiesMatched++;
            }
        }

        /** A streaming merge is only correct if the caller's entities ascend too.
         *  A buffered source is indexed by subject and does not care. */
        private void checkAscending(String entityUri) {
            if (buffer != null) {
                return;
            }
            if (lastAttachedSubject != null && entityUri.compareTo(lastAttachedSubject) < 0) {
                throw new TextIndexException(
                    "External merge requires entities in ascending IRI order, but '" + entityUri
                    + "' followed '" + lastAttachedSubject + "' for source " + describe());
            }
            lastAttachedSubject = entityUri;
        }

        private List<String[]> rowsFor(String entityUri) {
            if (buffer != null) {
                List<String[]> rows = buffer.remove(entityUri);
                return rows != null ? rows : Collections.emptyList();
            }
            // Rows before this entity belong to a subject the graph does not have.
            while (!exhausted && pendingSubject.compareTo(entityUri) < 0) {
                rowsUnmatched++;
                advance();
            }
            if (exhausted || !pendingSubject.equals(entityUri)) {
                return Collections.emptyList();
            }
            List<String[]> rows = new ArrayList<>();
            while (!exhausted && pendingSubject.equals(entityUri)) {
                rows.add(pendingValues);
                advance();
            }
            return rows;
        }

        /**
         * Build one child record from a row. Returns null — the row is dropped — when a
         * bound cell is empty or unparseable, since a half-populated child would match a
         * same-child filter on the field it does have while carrying nothing for the
         * field it does not.
         */
        private Entity.NestedRecord toRecord(String[] row, String entityUri) {
            List<ColumnBinding> bindings = def.getColumns();
            Object[] parsed = new Object[bindings.size()];
            for (int i = 0; i < bindings.size(); i++) {
                ColumnBinding binding = bindings.get(i);
                String raw = row[i];
                if (raw == null) {
                    onBadCell(binding, entityUri, null, "empty");
                    return null;
                }
                try {
                    parsed[i] = parseValue(binding.getField(), raw);
                } catch (NumberFormatException e) {
                    onBadCell(binding, entityUri, raw, "not a valid " + binding.getField().getFieldType());
                    return null;
                }
            }
            Entity.NestedRecord record = new Entity.NestedRecord();
            for (int i = 0; i < bindings.size(); i++) {
                record.addValue(bindings.get(i).getField().getFieldName(), parsed[i]);
            }
            return record;
        }

        private void onBadCell(ColumnBinding binding, String entityUri, String raw, String reason) {
            String message = "Row for '" + entityUri + "' in " + describe() + ": column "
                + binding + " is " + reason + (raw != null ? " ('" + raw + "')" : "");
            if (def.getOnError() == ErrorPolicy.FAIL) {
                throw new TextIndexException(message + ". Set idx:onError \"skip\" to drop such rows instead.");
            }
            log.debug("{} — row skipped", message);
        }

        /**
         * Parse a cell as the bound field's declared type. Nothing else happens to it:
         * no unit handling, no null sentinels, no detection-limit markers. A cell either
         * parses or it is an error.
         */
        private static Object parseValue(FieldDef field, String raw) {
            return switch (field.getFieldType()) {
                case TEXT, KEYWORD -> raw;
                case INT -> Integer.valueOf(raw.strip());
                case LONG -> Long.valueOf(raw.strip());
                case DOUBLE -> Double.valueOf(raw.strip());
                default -> throw new TextIndexException(
                    "Field " + field.getFieldName() + " has type " + field.getFieldType()
                    + ", which cannot be read from an external column");
            };
        }

        /** Read the tail so rows past the last entity are counted, not silently ignored. */
        void drain() {
            if (buffer != null) {
                for (List<String[]> rows : buffer.values()) {
                    rowsUnmatched += rows.size();
                }
                buffer.clear();
                return;
            }
            while (!exhausted) {
                rowsUnmatched++;
                advance();
            }
        }

        void report() {
            SourceStats stats = stats();
            log.info("External source {} [{}]: {} rows read, {} matched, {} unmatched, {} skipped; "
                    + "{} of {} entities matched ({}%)",
                describe(), nestedDef.getNestedName(),
                formatCount(stats.rowsRead()), formatCount(stats.rowsMatched()),
                formatCount(stats.rowsUnmatched()), formatCount(stats.rowsSkipped()),
                formatCount(stats.entitiesMatched()), formatCount(stats.entitiesSeen()),
                String.format(Locale.US, "%.1f", stats.matchRate() * 100.0));
            if (stats.rowsMatched() == 0 && stats.rowsRead() > 0) {
                log.warn("External source {} matched no entity at all. Check idx:subjectColumn and "
                    + "idx:subjectPrefix — the join key must be the full entity IRI.", describe());
            }
        }

        void enforceMinMatchRate() {
            double required = def.getMinMatchRate();
            if (required <= 0.0) {
                return;
            }
            double actual = stats().matchRate();
            if (actual < required) {
                throw new TextIndexException(String.format(Locale.US,
                    "External source %s matched only %.1f%% of the %d entities of shape %s, "
                    + "below the required idx:minMatchRate of %.1f%%. This usually means the "
                    + "join key is wrong (idx:subjectColumn / idx:subjectPrefix).",
                    describe(), actual * 100.0, entitiesSeen, profile.getShapeNode(), required * 100.0));
            }
        }

        SourceStats stats() {
            return new SourceStats(nestedDef.getNestedName(), def.getLocation(),
                source.rowsRead(), rowsMatched, rowsUnmatched, rowsSkipped,
                entitiesSeen, entitiesMatched);
        }

        String describe() {
            return source.describe();
        }

        void close() {
            source.close();
        }
    }

    private static String formatCount(long count) {
        return COUNT_FORMAT.format(count);
    }
}
