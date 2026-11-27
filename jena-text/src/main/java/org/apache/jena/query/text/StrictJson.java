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
import java.io.StringReader;
import java.math.BigDecimal;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import org.apache.jena.atlas.json.*;

/**
 * Parses the JSON arguments of {@code luc:query} and {@code luc:facet} as strict
 * RFC 8259 JSON: one value and nothing after it, no repeated key in an object, no
 * comments, and every string and key in double quotes.
 * <p>
 * {@link JSON#parse} is lenient. It keeps the last value of a repeated key and
 * allows comments, so a comment can hide text after the value. A caller that builds
 * an argument from a template, for example a stored query that wraps a client's
 * filter as {@code {"op":"and","args":[<fixed clause>, <client filter>]}}, depends
 * on the client's text being unable to close the template early or replace its
 * {@code "op"}. Rejecting these inputs makes that hold.
 */
public final class StrictJson {

    private StrictJson() {}

    /** Parse one JSON value of any kind. */
    public static JsonValue parseAny(String json) {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setStrictness(Strictness.STRICT);
        try {
            if ( reader.peek() == JsonToken.END_DOCUMENT )
                throw new TextIndexException("Empty JSON input");
            JsonValue value = read(reader);
            if ( reader.peek() != JsonToken.END_DOCUMENT )
                throw new TextIndexException("Content after the JSON value at " + reader.getPath() + ": " + json);
            return value;
        } catch (IOException | IllegalStateException | NumberFormatException ex) {
            // gson reports malformed JSON as MalformedJsonException (an IOException),
            // or as IllegalStateException for a token out of place.
            throw new TextIndexException("Invalid JSON: " + ex.getMessage(), ex);
        }
    }

    /** Parse one JSON object. */
    public static JsonObject parseObject(String json) {
        JsonValue value = parseAny(json);
        if ( !value.isObject() )
            throw new TextIndexException("JSON object expected: " + json);
        return value.getAsObject();
    }

    private static JsonValue read(JsonReader in) throws IOException {
        JsonToken token = in.peek();
        switch (token) {
            case BEGIN_OBJECT : {
                JsonObject object = new JsonObject();
                in.beginObject();
                while (in.hasNext()) {
                    String key = in.nextName();
                    if ( object.hasKey(key) )
                        throw new TextIndexException("Repeated key '" + key + "' in a JSON object at " + in.getPath());
                    object.put(key, read(in));
                }
                in.endObject();
                return object;
            }
            case BEGIN_ARRAY : {
                JsonArray array = new JsonArray();
                in.beginArray();
                while (in.hasNext())
                    array.add(read(in));
                in.endArray();
                return array;
            }
            case STRING :
                return new JsonString(in.nextString());
            case NUMBER :
                // As JSON.parse does: the number as written, as a BigDecimal.
                return JsonNumber.value(new BigDecimal(in.nextString()));
            case BOOLEAN :
                return new JsonBoolean(in.nextBoolean());
            case NULL :
                in.nextNull();
                return JsonNull.instance;
            default :
                throw new TextIndexException("Not the start of a JSON value: " + token + " at " + in.getPath());
        }
    }
}
