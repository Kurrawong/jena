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

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;

public final class LiteralFieldSupport {
    static final String DATATYPE_SUFFIX = "__datatype";
    static final String LANG_SUFFIX = "__lang";
    static final String EPOCH_SUFFIX = "__epoch";

    private LiteralFieldSupport() {}

    public static String datatypeField(String fieldName) {
        return fieldName + DATATYPE_SUFFIX;
    }

    public static String langField(String fieldName) {
        return fieldName + LANG_SUFFIX;
    }

    public static String epochField(String fieldName) {
        return fieldName + EPOCH_SUFFIX;
    }

    public static String queryFieldName(FieldDef fieldDef) {
        return fieldDef != null && fieldDef.isTemporal()
            ? epochField(fieldDef.getFieldName())
            : fieldDef != null ? fieldDef.getFieldName() : null;
    }

    public static Long toEpochMillis(FieldType fieldType, Object rawValue) {
        if (rawValue == null || fieldType != FieldType.TEMPORAL) {
            return null;
        }
        String lexical = rawValue instanceof Node node && node.isLiteral()
            ? node.getLiteralLexicalForm()
            : String.valueOf(rawValue);
        try {
            return parseTemporalEpochMillis(lexical);
        } catch (DateTimeException ex) {
            return null;
        }
    }

    public static Node reconstructNode(FieldDef fieldDef, String lexical, String datatypeUri, String language) {
        if (lexical == null || fieldDef == null) {
            return null;
        }
        String dt = blankToNull(datatypeUri);
        String lang = blankToNull(language);
        if (lang != null) {
            return NodeFactory.createLiteralLang(lexical, lang);
        }
        if (dt != null) {
            return NodeFactory.createLiteralDT(lexical, TypeMapper.getInstance().getSafeTypeByName(dt));
        }
        return switch (fieldDef.getFieldType()) {
            case KEYWORD -> ShaclTextIndexLucene.looksLikeUri(lexical)
                ? NodeFactory.createURI(lexical)
                : NodeFactory.createLiteralString(lexical);
            case TEXT -> NodeFactory.createLiteralString(lexical);
            case INT -> NodeFactory.createLiteralDT(lexical, org.apache.jena.datatypes.xsd.XSDDatatype.XSDinteger);
            case LONG -> NodeFactory.createLiteralDT(lexical, org.apache.jena.datatypes.xsd.XSDDatatype.XSDlong);
            case DOUBLE -> NodeFactory.createLiteralDT(lexical, org.apache.jena.datatypes.xsd.XSDDatatype.XSDdouble);
            case TEMPORAL -> NodeFactory.createLiteralDT(lexical,
                lexical.contains("T")
                    ? org.apache.jena.datatypes.xsd.XSDDatatype.XSDdateTime
                    : org.apache.jena.datatypes.xsd.XSDDatatype.XSDdate);
            // A vector is machine state, not a value a caller ever wants back as RDF, and
            // it is never stored — so there is nothing to reconstruct. Same as LATLON.
            case LATLON, VECTOR -> null;
        };
    }

    public static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Parse an xsd:date or xsd:dateTime lexical form into epoch milliseconds (UTC).
     * Date-only forms are treated as start-of-day UTC.
     */
    private static long parseTemporalEpochMillis(String lexical) {
        if (lexical.contains("T")) {
            try {
                return OffsetDateTime.parse(lexical, DateTimeFormatter.ISO_DATE_TIME)
                    .toInstant().toEpochMilli();
            } catch (DateTimeException ex) {
                LocalDateTime localDateTime = LocalDateTime.parse(lexical, DateTimeFormatter.ISO_DATE_TIME);
                return localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
            }
        }
        TemporalAccessor parsed = DateTimeFormatter.ISO_DATE.parse(lexical);
        LocalDate date = LocalDate.from(parsed);
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
