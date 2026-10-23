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

import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.Sort;

/** A position in a total order of {@code luc:query} hits. Not implemented yet. */
final class SearchCursor {

    record Order(String definition, Sort sort) {}

    static SearchCursor parse(String token) {
        throw new UnsupportedOperationException("SearchCursor.parse");
    }

    static String encode(Order order, int rank, Object[] sortValues) {
        throw new UnsupportedOperationException("SearchCursor.encode");
    }

    boolean isStart() {
        throw new UnsupportedOperationException("SearchCursor.isStart");
    }

    int firstRank() {
        throw new UnsupportedOperationException("SearchCursor.firstRank");
    }

    FieldDoc after(Order order, int maxDoc) {
        throw new UnsupportedOperationException("SearchCursor.after");
    }
}
