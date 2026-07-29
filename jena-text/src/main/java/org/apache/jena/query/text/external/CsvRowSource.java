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

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.jena.query.text.ShaclIndexMapping.ColumnBinding;
import org.apache.jena.query.text.ShaclIndexMapping.ExternalSourceDef;
import org.apache.jena.query.text.TextIndexException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delimited-text {@link ExternalRowSource}, backed by Apache Commons CSV.
 * <p>
 * Reads CSV or TSV, with a header row (columns bound by {@code idx:columnName}) or
 * without ({@code idx:headerless}, columns bound by {@code idx:columnIndex}). The
 * location may be a single path or a glob such as {@code /data/meas-*.csv}; globbed
 * files are read in filename order and concatenated.
 * <p>
 * <b>Values are returned verbatim.</b> A cell is either {@code null} — absent, empty
 * or whitespace-only — or exactly the text in the file. No trimming, no coercion; the
 * caller parses it as the bound field's declared type.
 * <p>
 * <b>Rows are emitted in file order.</b> Establishing the subject ordering the indexer
 * needs is {@link SortingRowSource}'s job, so the input does not have to be sorted and
 * nothing here checks that it is.
 */
public class CsvRowSource implements ExternalRowSource {
    private static final Logger log = LoggerFactory.getLogger(CsvRowSource.class);

    /** UTF-8 BOM, which would otherwise corrupt the first header name. */
    private static final char BOM = '\uFEFF';

    private final ExternalSourceDef def;
    private final CSVFormat format;
    private final String[] values;

    private List<Path> files = Collections.emptyList();
    private int nextFileIndex;
    private Path currentFile;
    private Reader reader;
    private CSVParser parser;
    private Iterator<CSVRecord> records;

    private String subject;
    private long rowsRead;
    private boolean opened;

    public CsvRowSource(ExternalSourceDef def) {
        this.def = def;
        this.values = new String[def.getColumns().size()];
        this.format = buildFormat(def);
    }

    private static CSVFormat buildFormat(ExternalSourceDef def) {
        CSVFormat base = switch (def.getFormat()) {
            case TSV -> CSVFormat.TDF;
            case CSV -> CSVFormat.DEFAULT;
        };
        CSVFormat.Builder builder = base.builder().setIgnoreEmptyLines(true);
        if (def.getDelimiter() != null) {
            builder.setDelimiter(def.getDelimiter());
        }
        if (!def.isHeaderless()) {
            builder.setHeader().setSkipHeaderRecord(true);
        }
        return builder.get();
    }

    @Override
    public void open() {
        if (opened) {
            throw new TextIndexException("CsvRowSource already open: " + describe());
        }
        opened = true;
        files = resolveFiles();
        nextFileIndex = 0;
        log.debug("Opening external source {} ({} file(s))", describe(), files.size());
        advanceFile();
    }

    private List<Path> resolveFiles() {
        Path path = Paths.get(def.getLocation());
        Path fileName = path.getFileName();
        String pattern = fileName != null ? fileName.toString() : "";

        if (!pattern.contains("*") && !pattern.contains("?")) {
            if (!Files.isReadable(path)) {
                throw new TextIndexException(
                    "idx:externalSource location is not a readable file: " + def.getLocation());
            }
            return List.of(path);
        }

        Path parent = path.getParent() != null ? path.getParent() : Paths.get(".");
        List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, pattern)) {
            for (Path match : stream) {
                if (Files.isReadable(match)) {
                    matches.add(match);
                }
            }
        } catch (IOException e) {
            throw new TextIndexException(
                "Could not expand idx:externalSource glob " + def.getLocation() + ": " + e.getMessage(), e);
        }
        if (matches.isEmpty()) {
            throw new TextIndexException(
                "idx:externalSource glob matched no readable files: " + def.getLocation());
        }
        Collections.sort(matches);
        return Collections.unmodifiableList(matches);
    }

    /** Close the current file and open the next, if any. */
    private boolean advanceFile() {
        closeCurrentFile();
        if (nextFileIndex >= files.size()) {
            records = null;
            return false;
        }
        currentFile = files.get(nextFileIndex++);
        try {
            reader = skipByteOrderMark(Files.newBufferedReader(currentFile, StandardCharsets.UTF_8));
            parser = CSVParser.parse(reader, format);
        } catch (IOException e) {
            throw new TextIndexException("Could not read " + currentFile + ": " + e.getMessage(), e);
        }
        if (!def.isHeaderless()) {
            validateHeader();
        }
        records = parser.iterator();
        return true;
    }

    private static Reader skipByteOrderMark(Reader in) throws IOException {
        PushbackReader pushback = new PushbackReader(in, 1);
        int first = pushback.read();
        if (first != -1 && first != BOM) {
            pushback.unread(first);
        }
        return pushback;
    }

    private void validateHeader() {
        List<String> headers = parser.getHeaderNames();
        if (!headers.contains(def.getSubjectColumn())) {
            throw new TextIndexException(
                "idx:subjectColumn '" + def.getSubjectColumn() + "' is not a column of "
                + currentFile + ". Columns present: " + headers);
        }
        for (ColumnBinding binding : def.getColumns()) {
            if (!headers.contains(binding.getColumnName())) {
                throw new TextIndexException(
                    "idx:columnName '" + binding.getColumnName() + "' (bound to field "
                    + binding.getField().getFieldName() + ") is not a column of " + currentFile
                    + ". Columns present: " + headers);
            }
        }
    }

    @Override
    public boolean next() {
        if (!opened) {
            throw new TextIndexException("CsvRowSource.next() before open(): " + describe());
        }
        while (true) {
            if (records == null) {
                return false;
            }
            if (!hasNextRecord()) {
                if (!advanceFile()) {
                    return false;
                }
                continue;
            }
            CSVRecord record = records.next();
            rowsRead++;

            String key = subjectCell(record);
            if (key == null) {
                // No join key: this row cannot be attached to any entity. Counted, dropped.
                continue;
            }
            subject = def.getSubjectPrefix() != null ? def.getSubjectPrefix() + key : key;

            List<ColumnBinding> bindings = def.getColumns();
            for (int i = 0; i < bindings.size(); i++) {
                values[i] = cell(record, bindings.get(i));
            }
            return true;
        }
    }

    private boolean hasNextRecord() {
        try {
            return records.hasNext();
        } catch (UncheckedIOException e) {
            throw new TextIndexException("Could not read " + currentFile + ": " + e.getMessage(), e);
        }
    }


    private String subjectCell(CSVRecord record) {
        if (def.isHeaderless()) {
            return positionalCell(record, def.getSubjectColumnIndex());
        }
        return namedCell(record, def.getSubjectColumn());
    }

    private String cell(CSVRecord record, ColumnBinding binding) {
        return binding.isPositional()
            ? positionalCell(record, binding.getColumnIndex())
            : namedCell(record, binding.getColumnName());
    }

    private static String namedCell(CSVRecord record, String column) {
        return record.isSet(column) ? emptyToNull(record.get(column)) : null;
    }

    private static String positionalCell(CSVRecord record, int index) {
        return index < record.size() ? emptyToNull(record.get(index)) : null;
    }

    /** An empty or whitespace-only cell means "no value" — never a zero, never "". */
    private static String emptyToNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : raw;
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
        return values.length;
    }


    @Override
    public long rowsRead() {
        return rowsRead;
    }

    @Override
    public String describe() {
        return def.getFormat() + " " + def.getLocation();
    }

    @Override
    public void close() {
        closeCurrentFile();
        records = null;
    }

    private void closeCurrentFile() {
        try {
            if (parser != null) {
                parser.close();
            }
        } catch (IOException e) {
            log.warn("Could not close {}: {}", currentFile, e.getMessage());
        } finally {
            parser = null;
        }
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException e) {
            log.warn("Could not close {}: {}", currentFile, e.getMessage());
        } finally {
            reader = null;
        }
    }

    @Override
    public String toString() {
        return "CsvRowSource(" + describe() + ")";
    }
}
