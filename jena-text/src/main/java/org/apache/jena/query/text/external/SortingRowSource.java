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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

import org.apache.jena.query.text.TextIndexException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps an {@link ExternalRowSource} and yields its rows grouped and ascending by
 * {@link #subject()}, whatever order they arrived in.
 * <p>
 * The merge join that attaches external children to graph entities needs one ordering
 * on both sides, and a block join must be written whole, so an entity's children have
 * to arrive together. Rather than push that requirement onto config — the old
 * {@code idx:sorted} assertion, which the operator had to guarantee by pre-sorting the
 * file with {@code LC_ALL=C sort} — the ordering is established here.
 * <p>
 * Standard external merge sort: rows are accumulated until {@value #DEFAULT_BUFFER_ROWS}
 * are held, then sorted and spilled to a temp file; at the end the runs are merged
 * k-way. An input small enough to fit the buffer never touches disk. Memory is bounded
 * by the buffer, not by the input, so a source of any size is safe.
 * <p>
 * The sort is <em>stable</em>: rows sharing a subject keep their input order. That
 * matters because duplicate (subject, property) rows are legal and a delta's rows are
 * position-sensitive.
 * <p>
 * Not thread-safe. One instance is driven by one indexing thread.
 */
public class SortingRowSource implements ExternalRowSource {
    private static final Logger log = LoggerFactory.getLogger(SortingRowSource.class);

    /** Rows held in memory before spilling a run to disk. */
    static final int DEFAULT_BUFFER_ROWS = 200_000;

    /** Override for tests and for tuning on memory-constrained hosts. */
    public static final String BUFFER_ROWS_PROPERTY = "jena.text.external.sortBufferRows";

    private final ExternalRowSource delegate;
    private final int bufferRows;

    private final List<Path> runs = new ArrayList<>();
    private int bindingCount;

    /** Set when the whole input fitted in memory; then {@link #runs} is empty. */
    private Iterator<Row> inMemory;
    private PriorityQueue<RunReader> merge;

    private Row current;
    private boolean opened;

    public SortingRowSource(ExternalRowSource delegate) {
        this(delegate, bufferRowsFromProperty());
    }

    SortingRowSource(ExternalRowSource delegate, int bufferRows) {
        this.delegate = delegate;
        this.bufferRows = Math.max(1, bufferRows);
    }

    private static int bufferRowsFromProperty() {
        String raw = System.getProperty(BUFFER_ROWS_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BUFFER_ROWS;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new TextIndexException(
                BUFFER_ROWS_PROPERTY + " must be an integer, got '" + raw + "'");
        }
    }

    /**
     * Drains the delegate completely, sorting as it goes. The delegate is closed here:
     * once drained there is nothing more to read from it.
     */
    @Override
    public void open() {
        delegate.open();
        opened = true;
        List<Row> buffer = new ArrayList<>();
        long seq = 0;
        try {
            bindingCount = delegate.bindingCount();
            while (delegate.next()) {
                String[] values = new String[bindingCount];
                for (int i = 0; i < bindingCount; i++) {
                    values[i] = delegate.value(i);
                }
                buffer.add(new Row(seq++, delegate.subject(), values));
                if (buffer.size() >= bufferRows) {
                    spill(buffer);
                    buffer.clear();
                }
            }
        } finally {
            delegate.close();
        }

        sortInPlace(buffer);
        if (runs.isEmpty()) {
            // Fast path: everything fitted, no temp file was ever written.
            inMemory = buffer.iterator();
            return;
        }
        if (!buffer.isEmpty()) {
            writeRun(buffer);
        }
        log.info("External source {} sorted by subject via {} spilled run(s) of up to {} rows.",
            delegate.describe(), runs.size(), bufferRows);
        openMerge();
    }

    /** Stable by construction: equal subjects fall back to input sequence. */
    private static void sortInPlace(List<Row> rows) {
        rows.sort(Comparator.comparing((Row r) -> r.subject).thenComparingLong(r -> r.seq));
    }

    private void spill(List<Row> buffer) {
        sortInPlace(buffer);
        writeRun(buffer);
    }

    private void writeRun(List<Row> rows) {
        try {
            Path run = Files.createTempFile("jena-text-extsort-", ".run");
            runs.add(run);
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(run)))) {
                for (Row row : rows) {
                    row.write(out);
                }
            }
        } catch (IOException e) {
            throw new TextIndexException(
                "Could not write a sort run for " + delegate.describe() + ": " + e.getMessage(), e);
        }
    }

    private void openMerge() {
        merge = new PriorityQueue<>(Comparator
            .comparing((RunReader r) -> r.peek.subject)
            .thenComparingLong(r -> r.peek.seq));
        for (Path run : runs) {
            RunReader reader = new RunReader(run);
            if (reader.advance()) {
                merge.add(reader);
            } else {
                reader.close();
            }
        }
    }

    @Override
    public boolean next() {
        if (!opened) {
            throw new IllegalStateException("open() must be called before next()");
        }
        if (inMemory != null) {
            if (!inMemory.hasNext()) {
                return false;
            }
            current = inMemory.next();
            return true;
        }
        RunReader head = merge.poll();
        if (head == null) {
            return false;
        }
        current = head.peek;
        if (head.advance()) {
            merge.add(head);
        } else {
            head.close();
        }
        return true;
    }

    @Override
    public String subject() {
        return current.subject;
    }

    @Override
    public String value(int bindingIndex) {
        return current.values[bindingIndex];
    }

    @Override
    public int bindingCount() {
        return bindingCount;
    }

    @Override
    public long rowsRead() {
        return delegate.rowsRead();
    }

    @Override
    public String describe() {
        return delegate.describe();
    }

    @Override
    public void close() {
        if (merge != null) {
            for (RunReader reader : merge) {
                reader.close();
            }
            merge.clear();
        }
        for (Path run : runs) {
            try {
                Files.deleteIfExists(run);
            } catch (IOException e) {
                log.warn("Could not delete sort run {}: {}", run, e.getMessage());
            }
        }
        runs.clear();
        inMemory = null;
        delegate.close();
    }

    /** One buffered row. {@code seq} preserves input order among equal subjects. */
    private static final class Row {
        private final long seq;
        private final String subject;
        private final String[] values;

        Row(long seq, String subject, String[] values) {
            this.seq = seq;
            this.subject = subject;
            this.values = values;
        }

        void write(DataOutputStream out) throws IOException {
            out.writeLong(seq);
            writeString(out, subject);
            out.writeInt(values.length);
            for (String value : values) {
                writeString(out, value);
            }
        }

        static Row read(DataInputStream in) throws IOException {
            long seq = in.readLong();
            String subject = readString(in);
            int n = in.readInt();
            String[] values = new String[n];
            for (int i = 0; i < n; i++) {
                values[i] = readString(in);
            }
            return new Row(seq, subject, values);
        }

        /**
         * Length-prefixed UTF-8 rather than {@code writeUTF}, whose 64 KB ceiling a
         * long cell could exceed. A length of -1 encodes null, which is distinct from
         * an empty cell.
         */
        private static void writeString(DataOutputStream out, String s) throws IOException {
            if (s == null) {
                out.writeInt(-1);
                return;
            }
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        }

        private static String readString(DataInputStream in) throws IOException {
            int len = in.readInt();
            if (len < 0) {
                return null;
            }
            byte[] bytes = new byte[len];
            in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** A spilled run being merged; {@link #peek} is its next unconsumed row. */
    private final class RunReader {
        private final Path path;
        private final DataInputStream in;
        private Row peek;

        RunReader(Path path) {
            this.path = path;
            try {
                this.in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(path)));
            } catch (IOException e) {
                throw new TextIndexException(
                    "Could not read sort run " + path + ": " + e.getMessage(), e);
            }
        }

        boolean advance() {
            try {
                peek = Row.read(in);
                return true;
            } catch (EOFException e) {
                peek = null;
                return false;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void close() {
            try {
                in.close();
            } catch (IOException e) {
                log.warn("Could not close sort run {}: {}", path, e.getMessage());
            }
        }
    }
}
