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

/**
 * A forward-only cursor over an external tabular source supplying nested child
 * records for graph entities. One implementation per input format; see
 * {@code docs/2026-07-27_external_content_indexing_design.md}.
 * <p>
 * The contract is deliberately minimal — a cursor, a join key, and positional cell
 * access — so that adding a format (JDBC, Parquet) is one class and no config change.
 * Implementations never transform a value: {@link #value(int)} returns the raw cell
 * text and the caller parses it as the bound field's declared type.
 * <p>
 * Not thread-safe. One source instance is driven by one indexing thread.
 */
public interface ExternalRowSource extends Closeable {

    /** Open the underlying input(s). Must be called before {@link #next()}. */
    void open();

    /**
     * Advance to the next row, skipping rows the source itself rejects (blank lines,
     * rows with no join key).
     *
     * @return {@code false} when the input is exhausted
     */
    boolean next();

    /**
     * Join key of the current row — the entity IRI, with {@code idx:subjectPrefix}
     * already applied. Never {@code null} while {@link #next()} returned {@code true}.
     */
    String subject();

    /**
     * Raw cell text for the column binding at {@code bindingIndex}, or {@code null}
     * when the cell is absent or empty. Bindings are indexed in declaration order.
     */
    String value(int bindingIndex);

    /** Number of column bindings — the valid range for {@link #value(int)}. */
    int bindingCount();

    /**
     * Whether rows arrive grouped and ascending by {@link #subject()}. When true the
     * indexer can stream a merge join; when false it must buffer the source.
     * <p>
     * This reflects the {@code idx:sorted} assertion in config. Implementations are
     * expected to verify it while reading and fail on violation rather than silently
     * producing a mostly-unmatched build.
     */
    boolean isSorted();

    /** Rows read so far, including rows skipped for having no usable join key. */
    long rowsRead();

    /** Human-readable identification of the input, for logs and error messages. */
    String describe();

    @Override
    void close();
}
