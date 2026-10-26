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

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;

/**
 * The interval a temporal value spans, as UTC epoch milliseconds, closed at both ends.
 */
public record TemporalSpan(long start, long end) {

    /** The span of a temporal literal, or null if it names no point in time. */
    public static TemporalSpan of(Node literal) {
        if (literal == null || !literal.isLiteral()) {
            return null;
        }
        String datatype = literal.getLiteralDatatypeURI();
        String lexical = literal.getLiteralLexicalForm();
        try {
            if (XSDDatatype.XSDdateTime.getURI().equals(datatype)
                    || XSDDatatype.XSDdateTimeStamp.getURI().equals(datatype)) {
                long t = dateTimeMillis(lexical);
                return new TemporalSpan(t, t);
            }
            if (XSDDatatype.XSDdate.getURI().equals(datatype)) {
                return dateSpan(lexical);
            }
        } catch (DateTimeException ex) {
            return null;
        }
        return null;
    }

    /**
     * An xsd:date as its whole day, up to when the next day starts: from midnight at the
     * date's own offset, or in UTC if it has none.
     */
    private static TemporalSpan dateSpan(String lexical) {
        TemporalAccessor parsed = DateTimeFormatter.ISO_DATE.parse(lexical);
        LocalDate date = LocalDate.from(parsed);
        ZoneOffset offset = parsed.isSupported(ChronoField.OFFSET_SECONDS)
            ? ZoneOffset.from(parsed)
            : ZoneOffset.UTC;
        return new TemporalSpan(
            date.atStartOfDay(offset).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(offset).toInstant().toEpochMilli());
    }

    /** An xsd:dateTime as an instant, taking one without a timezone as UTC. */
    private static long dateTimeMillis(String lexical) {
        TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(lexical,
            OffsetDateTime::from, LocalDateTime::from);
        if (parsed instanceof OffsetDateTime offset) {
            return offset.toInstant().toEpochMilli();
        }
        return ((LocalDateTime) parsed).toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
