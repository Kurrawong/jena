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

import java.util.Collection;

/**
 * Test hook on the {@code luc:query} hit path. A skipped read has no other observable
 * result, so tests count reads through this. Unset in production.
 */
interface HitReadObserver {

    /** Stored fields were decoded for one document; {@code fieldNames} are those decoded. */
    default void storedFieldsRead(int docId, Collection<String> fieldNames) {}

    /** Match details were computed for one hit. */
    default void matchesComputed(int docId) {}
}
