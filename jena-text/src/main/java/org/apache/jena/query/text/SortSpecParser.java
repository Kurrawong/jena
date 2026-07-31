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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

/**
 * Parses JSON sort specifications into {@link SortSpec} lists.
 * <p>
 * Single: {@code {"field": "year", "order": "desc"}}
 * Multi:  {@code [{"field": "year", "order": "desc"}, {"field": "title"}]}
 * Default order is ascending.
 * <p>
 * A spec may also carry a nested sort selector — sort by a child field, choosing the
 * child by a co-located discriminator:
 * <pre>{@code
 * {"field": "...#identifierValue",
 *  "selector": {"field": "...#identifierType", "eq": "companyID"},
 *  "order": "asc", "missing": "last"}
 * }</pre>
 * The selector key is deliberately not called {@code filter}: {@code luc:query} already
 * takes a {@code filter} that restricts the result set, whereas this one only chooses
 * which child supplies the sort key.
 * <p>
 * Parsing here is purely syntactic; the nested scope is inferred and validated against
 * the SHACL mapping in {@code ShaclTextIndexLucene.buildLuceneSort}.
 */
public class SortSpecParser {

    public static List<SortSpec> parse(String json) {
        JsonValue val = JSON.parseAny(json);
        if (val.isArray()) {
            return parseArray(val.getAsArray());
        } else if (val.isObject()) {
            return Collections.singletonList(parseOne(val.getAsObject()));
        }
        throw new TextIndexException("Sort spec must be a JSON object or array: " + json);
    }

    private static List<SortSpec> parseArray(JsonArray arr) {
        List<SortSpec> specs = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            specs.add(parseOne(arr.get(i).getAsObject()));
        }
        return specs;
    }

    private static SortSpec parseOne(JsonObject obj) {
        if (!obj.hasKey("field")) {
            throw new TextIndexException("Sort spec must have a 'field' key: " + obj);
        }
        String field = obj.get("field").getAsString().value();
        boolean descending = false;
        if (obj.hasKey("order")) {
            String order = obj.get("order").getAsString().value();
            descending = "desc".equalsIgnoreCase(order);
        }

        String selectorField = null;
        String selectorValue = null;
        if (obj.hasKey("selector")) {
            JsonValue selectorVal = obj.get("selector");
            if (!selectorVal.isObject()) {
                throw new TextIndexException(
                    "Sort spec 'selector' must be an object {\"field\":..., \"eq\":...}: " + obj);
            }
            JsonObject selector = selectorVal.getAsObject();
            if (!selector.hasKey("field") || !selector.hasKey("eq")) {
                throw new TextIndexException(
                    "Sort spec 'selector' must have both 'field' and 'eq' keys: " + obj);
            }
            selectorField = selector.get("field").getAsString().value();
            selectorValue = asLexicalForm(selector.get("eq"), obj);
        }

        SortSpec.MissingPlacement missing = null;
        if (obj.hasKey("missing")) {
            String placement = obj.get("missing").getAsString().value();
            if ("first".equalsIgnoreCase(placement)) {
                missing = SortSpec.MissingPlacement.FIRST;
            } else if ("last".equalsIgnoreCase(placement)) {
                missing = SortSpec.MissingPlacement.LAST;
            } else {
                throw new TextIndexException(
                    "Sort spec 'missing' must be 'first' or 'last', got '" + placement + "'");
            }
        }

        return new SortSpec(field, descending, selectorField, selectorValue, missing);
    }

    /** Lexical form of a selector's {@code eq} value — strings, numbers and booleans all
     *  become the term text the discriminator field was indexed with. */
    private static String asLexicalForm(JsonValue value, JsonObject context) {
        if (value.isString()) {
            return value.getAsString().value();
        }
        if (value.isNumber()) {
            return value.getAsNumber().value().toString();
        }
        if (value.isBoolean()) {
            return Boolean.toString(value.getAsBoolean().value());
        }
        throw new TextIndexException(
            "Sort spec selector 'eq' must be a string, number or boolean: " + context);
    }

    /**
     * Check if a JSON string looks like a sort spec (has a "field" key).
     */
    public static boolean isSortSpec(String json) {
        return json.contains("\"field\"");
    }
}
