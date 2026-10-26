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

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.junit.jupiter.api.Test;

/**
 * The interval a temporal literal spans, in UTC: an instant for a dateTime, the whole
 * period for a date, year-month or year. The values are those of Prez's temporal
 * fixture, whose SPARQL answers pushdown must match.
 */
public class TestTemporalSpan {

    @Test
    public void aDateTimeIsAnInstantInUtc() {
        TemporalSpan noon = instant("2021-06-30T12:00:00Z");

        assertEquals(noon, TemporalSpan.of(literal("2021-06-30T12:00:00Z", XSDDatatype.XSDdateTime)));
        assertEquals(noon, TemporalSpan.of(literal("2021-06-30T22:00:00+10:00", XSDDatatype.XSDdateTime)),
            "an offset is converted to UTC");
        assertEquals(noon, TemporalSpan.of(literal("2021-06-30T12:00:00", XSDDatatype.XSDdateTime)),
            "no timezone is taken as UTC");
    }

    /** OWL-Time's {@code time:inXSDDateTimeStamp} is typed this way. */
    @Test
    public void aDateTimeStampIsAnInstantInUtc() {
        assertEquals(instant("2021-06-30T12:00:00Z"),
            TemporalSpan.of(literal("2021-06-30T22:00:00+10:00", XSDDatatype.XSDdateTimeStamp)));
    }

    private static Node literal(String lexical, XSDDatatype datatype) {
        return NodeFactory.createLiteralDT(lexical, datatype);
    }

    private static TemporalSpan instant(String utc) {
        long t = Instant.parse(utc).toEpochMilli();
        return new TemporalSpan(t, t);
    }
}
