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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;

import org.apache.jena.query.QueryExecException;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.SortedSetSortField;
import org.apache.lucene.util.BytesRef;

/**
 * A position in a total order of {@code luc:query} hits: the sort values of one hit and
 * its rank. {@code luc:cursor} gives one per hit; {@code luc:query} takes one back as its
 * eighth argument and continues with the hits that sort after it.
 * <p>
 * A cursor names sort values, not a Lucene document, so it survives commits, merges and
 * re-inserted entities: the next page is every hit that sorts after those values, whichever
 * documents now hold them. The order is total because its last key is the entity IRI. The
 * rank is the hit's position when the cursor was read; if the index has changed since, the
 * ranks of later pages count from that position rather than from the current one.
 *
 * <h2>Token</h2>
 * Unpadded base64url, so it can sit in a URL query parameter unescaped, of: a version byte,
 * a hash of the order's definition, the rank, the number of values, then each value as a
 * type tag and its bytes. Floating-point values are kept as their bits, so a relevance
 * score continues exactly. The hash lets a cursor used with a different sort be refused
 * rather than misread.
 * <p>
 * A refused cursor's error message starts with {@value #MALFORMED} or
 * {@value #OTHER_SORT}, which clients match to report a bad request. They are part of
 * the API: change them only with the clients.
 */
final class SearchCursor {

    /** The eighth {@code luc:query} argument that starts cursor paging at the first hit. */
    static final String START_TOKEN = "*";

    private static final byte VERSION = 1;

    private static final byte NULL = 0;
    private static final byte BYTES = 1;
    private static final byte INT = 2;
    private static final byte LONG = 3;
    private static final byte FLOAT = 4;
    private static final byte DOUBLE = 5;

    /** Marker starting the error for a string that is not a cursor {@code luc:cursor} returned. */
    static final String MALFORMED = "[LUC_CURSOR_MALFORMED]";
    /** Marker starting the error for a cursor read from a search with a different sort. */
    static final String OTHER_SORT = "[LUC_CURSOR_OTHER_SORT]";

    private static final String NOT_A_CURSOR = MALFORMED + " luc:query: '%s' is not a cursor luc:cursor returned";

    /**
     * A total order hits are paged through: its definition, which a cursor is checked
     * against, and the Lucene sort that applies it.
     */
    record Order(String definition, Sort sort) {
        int hash() {
            CRC32 crc = new CRC32();
            crc.update(definition.getBytes(StandardCharsets.UTF_8));
            return (int) crc.getValue();
        }
    }

    private static final SearchCursor START = new SearchCursor(START_TOKEN, 0, -1, null);

    private final String token;
    private final int orderHash;
    /** Rank of the hit the cursor was read from; -1 for the start. */
    private final int rank;
    private final Object[] values;

    private SearchCursor(String token, int orderHash, int rank, Object[] values) {
        this.token = token;
        this.orderHash = orderHash;
        this.rank = rank;
        this.values = values;
    }

    /** The cursor {@code token} names: the start for {@value #START_TOKEN}, otherwise a decoded token. */
    static SearchCursor parse(String token) {
        if (START_TOKEN.equals(token)) {
            return START;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(token)));
            if (in.readByte() != VERSION) {
                throw new QueryExecException(String.format(NOT_A_CURSOR, token));
            }
            int orderHash = in.readInt();
            int rank = in.readInt();
            int count = in.readInt();
            if (rank < 0 || count < 0 || count > in.available()) {
                throw new QueryExecException(String.format(NOT_A_CURSOR, token));
            }
            Object[] values = new Object[count];
            for (int i = 0; i < count; i++) {
                values[i] = readValue(in, token);
            }
            if (in.available() != 0) {
                throw new QueryExecException(String.format(NOT_A_CURSOR, token));
            }
            return new SearchCursor(token, orderHash, rank, values);
        } catch (IOException | IllegalArgumentException ex) {
            throw new QueryExecException(String.format(NOT_A_CURSOR, token));
        }
    }

    /** The token for the hit at {@code rank} whose sort values under {@code order} are {@code sortValues}. */
    static String encode(Order order, int rank, Object[] sortValues) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            out.writeInt(order.hash());
            out.writeInt(rank);
            out.writeInt(sortValues.length);
            for (Object value : sortValues) {
                writeValue(out, value);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException ex) {
            throw new TextIndexException("Encoding a cursor", ex);
        }
    }

    /** The token this cursor was parsed from. */
    String token() {
        return token;
    }

    /** True for {@value #START_TOKEN}, which names no hit. */
    boolean isStart() {
        return values == null;
    }

    /** The rank of the first hit after this cursor. */
    int firstRank() {
        return rank + 1;
    }

    /**
     * This cursor as Lucene's {@code searchAfter} position under {@code order}. Raises if the
     * cursor was read under a different order.
     * <p>
     * The doc ID is the index's last, so a document tying with the cursor on every sort key
     * counts as already returned. The IRI makes such a tie the cursor's own entity, which a
     * re-inserted document may now hold under a different doc ID.
     *
     * @param maxDoc the reader's {@code maxDoc}, which bounds the doc ID Lucene accepts
     */
    FieldDoc after(Order order, int maxDoc) {
        if (isStart()) {
            throw new IllegalStateException("The start cursor names no hit");
        }
        SortField[] fields = order.sort().getSort();
        if (orderHash != order.hash() || values.length != fields.length) {
            throw new QueryExecException(OTHER_SORT + " luc:query: the cursor was read from a search with "
                + "a different sort; continue with that sort, or start again with \"" + START_TOKEN + "\"");
        }
        for (int i = 0; i < fields.length; i++) {
            if (tag(values[i]) != expectedTag(fields[i]) && !(values[i] == null && expectedTag(fields[i]) == BYTES)) {
                throw new QueryExecException(String.format(NOT_A_CURSOR, token)
                    + ": it does not hold the values of this sort");
            }
        }
        return new FieldDoc(Math.max(0, maxDoc - 1), Float.NaN, values.clone());
    }

    private static void writeValue(DataOutputStream out, Object value) throws IOException {
        out.writeByte(tag(value));
        switch (value) {
            case null -> {}
            case BytesRef ref -> {
                out.writeInt(ref.length);
                out.write(ref.bytes, ref.offset, ref.length);
            }
            case Integer v -> out.writeInt(v);
            case Long v -> out.writeLong(v);
            case Float v -> out.writeInt(Float.floatToRawIntBits(v));
            case Double v -> out.writeLong(Double.doubleToRawLongBits(v));
            default -> throw new IllegalArgumentException("No cursor encoding for " + value.getClass());
        }
    }

    private static Object readValue(DataInputStream in, String token) throws IOException {
        byte tag = in.readByte();
        return switch (tag) {
            case NULL -> null;
            case BYTES -> {
                int length = in.readInt();
                if (length < 0 || length > in.available()) {
                    throw new QueryExecException(String.format(NOT_A_CURSOR, token));
                }
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                yield new BytesRef(bytes);
            }
            case INT -> in.readInt();
            case LONG -> in.readLong();
            case FLOAT -> Float.intBitsToFloat(in.readInt());
            case DOUBLE -> Double.longBitsToDouble(in.readLong());
            default -> throw new QueryExecException(String.format(NOT_A_CURSOR, token));
        };
    }

    private static byte tag(Object value) {
        return switch (value) {
            case null -> NULL;
            case BytesRef ref -> BYTES;
            case Integer v -> INT;
            case Long v -> LONG;
            case Float v -> FLOAT;
            case Double v -> DOUBLE;
            default -> throw new IllegalArgumentException("No cursor encoding for " + value.getClass());
        };
    }

    /** The tag of the value Lucene's comparator for {@code field} produces. */
    private static byte expectedTag(SortField field) {
        if (field instanceof SortedNumericSortField numeric) {
            return expectedTag(numeric.getNumericType());
        }
        if (field instanceof SortedSetSortField) {
            return BYTES;
        }
        return expectedTag(field.getType());
    }

    private static byte expectedTag(SortField.Type type) {
        return switch (type) {
            case SCORE, FLOAT -> FLOAT;
            case STRING, STRING_VAL -> BYTES;
            case INT -> INT;
            case LONG -> LONG;
            case DOUBLE -> DOUBLE;
            default -> throw new IllegalArgumentException("No cursor encoding for sort type " + type);
        };
    }
}
