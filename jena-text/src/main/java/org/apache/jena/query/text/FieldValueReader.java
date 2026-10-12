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

import java.io.IOException;
import java.util.*;

import org.apache.jena.graph.Node;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DocumentStoredFieldVisitor;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.StoredFields;

/**
 * Reads the values of SHACL fields for documents of one index, in doc-ID order. Used by
 * {@code luc:match}, {@code luc:nestedMatch}, {@code luc:fields} and the index's
 * {@link TextHit} query methods.
 * <p>
 * A field whose doc value holds its original value
 * ({@link ShaclIndexMapping.FieldDef#isValueInDocValues()}) is read from that doc value
 * with a forward-only {@code advanceExact}; every other field is read from stored fields,
 * decoding only the fields asked for. Which source a field uses is decided per segment
 * from its {@link FieldInfo}: a segment with no doc value for the field, such as one
 * written before the field was made sortable, falls back to its stored copy. A segment
 * written while the field had an {@code idx:normalizer} has a doc value holding the
 * normalized key, and that key is what is read: the index needs rebuilding after such a
 * configuration change, as {@link ShaclIndexStamp} warns.
 * <p>
 * Both sources give the same term for the same value: a doc value goes through
 * {@link LiteralFieldSupport#reconstructNode} with no datatype or language, which is what
 * the stored path passes for such a field. A multi-valued doc value returns each distinct
 * value once, in byte order; stored fields return values in indexing order.
 * <p>
 * Call {@link #startLeaf} for each segment, then read documents of that segment in
 * increasing doc-ID order. A field with {@code idx:stored false} has no values here.
 */
final class FieldValueReader {

    /** The values of one field on one document, with the datatype and language twins where stored. */
    static final class Values {
        static final Values NONE = new Values(null, new String[0], null, null);

        private final ShaclIndexMapping.FieldDef field;
        private final String[] lexicals;
        private final String[] datatypes;
        private final String[] langs;

        Values(ShaclIndexMapping.FieldDef field, String[] lexicals, String[] datatypes, String[] langs) {
            this.field = field;
            this.lexicals = lexicals;
            this.datatypes = datatypes;
            this.langs = langs;
        }

        int size() {
            return lexicals.length;
        }

        boolean isEmpty() {
            return lexicals.length == 0;
        }

        /** The lexical forms, in the order the source returns them. */
        String[] lexicals() {
            return lexicals;
        }

        /** The term for value {@code i}, or null if the field type has no term (LATLON). */
        Node node(int i) {
            return LiteralFieldSupport.reconstructNode(field, lexicals[i], aligned(datatypes, i), aligned(langs, i));
        }

        /** Every value as a term, skipping values with no term. */
        List<Node> nodes() {
            if (lexicals.length == 1) {
                Node node = node(0);
                return node == null ? List.of() : List.of(node);
            }
            List<Node> nodes = new ArrayList<>(lexicals.length);
            for (int i = 0; i < lexicals.length; i++) {
                Node node = node(i);
                if (node != null) {
                    nodes.add(node);
                }
            }
            return nodes;
        }

        private static String aligned(String[] values, int i) {
            return values == null || i >= values.length ? null : values[i];
        }
    }

    /** How one field is read in the current segment. */
    private static final class Source {
        /** Neither a doc value nor a stored copy: no document in the segment has the field. */
        static final Source ABSENT = new Source(null, null, false);
        static final Source STORED = new Source(null, null, true);

        final SortedDocValues sorted;
        final SortedSetDocValues sortedSet;
        final boolean stored;
        int lastDoc = -1;
        Values lastValues;

        Source(SortedDocValues sorted, SortedSetDocValues sortedSet, boolean stored) {
            this.sorted = sorted;
            this.sortedSet = sortedSet;
            this.stored = stored;
        }
    }

    private static final Document EMPTY = new Document();

    private final HitReadObserver observer;
    private LeafReaderContext leaf;
    private StoredFields storedFields;
    private final Map<String, Source> sources = new HashMap<>();

    FieldValueReader(HitReadObserver observer) {
        this.observer = observer;
    }

    /** A new segment starts; every document until the next call is in {@code leafContext}. */
    void startLeaf(LeafReaderContext leafContext) {
        leaf = leafContext;
        storedFields = null;
        sources.clear();
    }

    private Source source(ShaclIndexMapping.FieldDef field) throws IOException {
        String name = field.getFieldName();
        Source source = sources.get(name);
        if (source != null) {
            return source;
        }
        LeafReader reader = leaf.reader();
        FieldInfo info = reader.getFieldInfos().fieldInfo(name);
        if (info == null) {
            source = Source.ABSENT;
        } else if (field.isValueInDocValues() && info.getDocValuesType() == DocValuesType.SORTED) {
            source = new Source(reader.getSortedDocValues(name), null, false);
        } else if (field.isValueInDocValues() && info.getDocValuesType() == DocValuesType.SORTED_SET) {
            source = new Source(null, reader.getSortedSetDocValues(name), false);
        } else {
            source = Source.STORED;
        }
        sources.put(name, source);
        return source;
    }

    /**
     * The stored fields to decode so that {@link #values} can answer for {@code fields} in
     * the current segment: each returnable field not read from a doc value, with its
     * datatype and language twins where the field keeps them.
     */
    Set<String> storedNames(Collection<ShaclIndexMapping.FieldDef> fields) throws IOException {
        Set<String> names = new HashSet<>();
        for (ShaclIndexMapping.FieldDef field : fields) {
            if (field == null || !field.isStored() || !source(field).stored) {
                continue;
            }
            String name = field.getFieldName();
            names.add(name);
            if (field.preservesLiteralMetadata()) {
                names.add(LiteralFieldSupport.datatypeField(name));
                names.add(LiteralFieldSupport.langField(name));
            }
        }
        return names;
    }

    /** Decode {@code names} from the stored fields of one document; nothing when empty. */
    Document stored(int segmentDocId, Set<String> names) throws IOException {
        if (names.isEmpty()) {
            return EMPTY;
        }
        if (storedFields == null) {
            storedFields = leaf.reader().storedFields();
        }
        DocumentStoredFieldVisitor visitor = new DocumentStoredFieldVisitor(names);
        storedFields.document(segmentDocId, visitor);
        Document doc = visitor.getDocument();
        if (observer != null) {
            Set<String> decoded = new LinkedHashSet<>();
            doc.getFields().forEach(f -> decoded.add(f.name()));
            observer.storedFieldsRead(leaf.docBase + segmentDocId, decoded);
        }
        return doc;
    }

    /**
     * The values of {@code field} on one document. {@code stored} is what {@link #stored}
     * decoded for that document with names including {@link #storedNames} of this field.
     */
    Values values(ShaclIndexMapping.FieldDef field, int segmentDocId, Document stored) throws IOException {
        if (field == null || !field.isStored()) {
            return Values.NONE;
        }
        Source source = source(field);
        if (source == Source.ABSENT) {
            return Values.NONE;
        }
        if (source.stored) {
            String name = field.getFieldName();
            String[] lexicals = stored.getValues(name);
            if (lexicals == null || lexicals.length == 0) {
                return Values.NONE;
            }
            return new Values(field, lexicals,
                stored.getValues(LiteralFieldSupport.datatypeField(name)),
                stored.getValues(LiteralFieldSupport.langField(name)));
        }
        // Forward-only: a second request for the same document reuses the first answer.
        if (source.lastDoc == segmentDocId) {
            return source.lastValues;
        }
        Values values = Values.NONE;
        if (source.sorted != null && source.sorted.advanceExact(segmentDocId)) {
            String value = source.sorted.lookupOrd(source.sorted.ordValue()).utf8ToString();
            values = new Values(field, new String[] {value}, null, null);
        } else if (source.sortedSet != null && source.sortedSet.advanceExact(segmentDocId)) {
            int count = source.sortedSet.docValueCount();
            String[] lexicals = new String[count];
            for (int i = 0; i < count; i++) {
                lexicals[i] = source.sortedSet.lookupOrd(source.sortedSet.nextOrd()).utf8ToString();
            }
            values = new Values(field, lexicals, null, null);
        }
        if (observer != null) {
            observer.docValuesRead(leaf.docBase + segmentDocId, field.getFieldName());
        }
        source.lastDoc = segmentDocId;
        source.lastValues = values;
        return values;
    }
}
