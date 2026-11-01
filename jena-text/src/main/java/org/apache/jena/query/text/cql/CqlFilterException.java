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

package org.apache.jena.query.text.cql;

import org.apache.jena.query.QueryParseException;
import org.apache.jena.query.text.TextIndexException;

/**
 * A CQL filter that cannot be run as written: it does not parse, or it asks for something
 * the index cannot filter on. Unlike other index errors it is the request's fault, so
 * {@code luc:query} and {@code luc:facet} raise it as a {@link QueryParseException}, which
 * Fuseki answers with a 400.
 */
public class CqlFilterException extends TextIndexException {

    public CqlFilterException(String msg, Throwable cause) {
        super(msg, cause);
    }

    /** {@code ex} as a {@link CqlFilterException}, keeping one that already is. */
    static CqlFilterException of(RuntimeException ex) {
        return ex instanceof CqlFilterException filterError ? filterError : new CqlFilterException(ex.getMessage(), ex);
    }

    /** As the parse error a SPARQL endpoint reports as a bad request. */
    public QueryParseException asParseError() {
        return new QueryParseException(getMessage(), this, -1, -1);
    }
}
