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
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.StoredFieldVisitor;

/**
 * Reads per-hit data for a set of {@link SearchHit}s in doc-ID order.
 * <p>
 * Hits arrive in rank order, which jumps between segments and between stored-field
 * blocks. Reading in doc-ID order visits each segment once and each compressed
 * stored-field block once per run of hits in it, and lets doc-values iterators move
 * forward only. Results are written back onto the hits, so the caller keeps rank order.
 */
final class HitReader {

    private HitReader() {}

    /** Per-segment reads, called by {@link #inDocOrder}. */
    interface LeafReads {

        /** A new segment starts; every hit until the next call is in {@code leaf}. */
        void startLeaf(LeafReaderContext leaf) throws IOException;

        /** Read one hit; {@code segmentDocId} is relative to the current segment. */
        void read(SearchHit hit, int segmentDocId) throws IOException;
    }

    /**
     * Call {@code reads} for every hit in {@code hits}, ordered by Lucene doc ID, with
     * one {@link LeafReads#startLeaf} per segment. The doc IDs must come from
     * {@code reader}.
     */
    static void inDocOrder(IndexReader reader, Collection<SearchHit> hits, LeafReads reads)
            throws IOException {
        if (hits.isEmpty()) {
            return;
        }
        SearchHit[] sorted = hits.toArray(new SearchHit[0]);
        Arrays.sort(sorted, Comparator.comparingInt(SearchHit::getLuceneDocId));
        List<LeafReaderContext> leaves = reader.leaves();
        LeafReaderContext leaf = null;
        int leafEnd = 0;
        for (SearchHit hit : sorted) {
            int docId = hit.getLuceneDocId();
            if (leaf == null || docId >= leafEnd) {
                leaf = leaves.get(ReaderUtil.subIndex(docId, leaves));
                leafEnd = leaf.docBase + leaf.reader().maxDoc();
                reads.startLeaf(leaf);
            }
            reads.read(hit, docId - leaf.docBase);
        }
    }

    /**
     * Decodes the first value of one single-valued string field and stops. The stored
     * field block is still decompressed, but no other field is decoded.
     */
    static final class OneStringField extends StoredFieldVisitor {
        private final String fieldName;
        private String value;

        OneStringField(String fieldName) {
            this.fieldName = fieldName;
        }

        /** Clear the value before visiting the next document. */
        void reset() {
            value = null;
        }

        /** The value, or null if the document has no such field. */
        String value() {
            return value;
        }

        @Override
        public Status needsField(FieldInfo fieldInfo) {
            if (value != null) {
                return Status.STOP;
            }
            return fieldName.equals(fieldInfo.name) ? Status.YES : Status.NO;
        }

        @Override
        public void stringField(FieldInfo fieldInfo, String fieldValue) {
            value = fieldValue;
        }
    }
}
