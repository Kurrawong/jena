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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** CQL2-JSON temporal predicates, parsed. */
public class TestCqlTemporalParsing {

    /** The schema writes the "By" operators in camelCase; any other casing is not CQL2. */
    @Test
    public void operatorNamesAreCasedAsTheSchemaWritesThem() {
        assertInstanceOf(CqlExpression.CqlTemporal.class, CqlParser.parse("""
            {"op": "t_finishedBy", "args": [{"property": "p"}, {"date": "2021-06-30"}]}
            """));
        CqlFilterException ex = assertThrows(CqlFilterException.class, () -> CqlParser.parse("""
            {"op": "t_finishedby", "args": [{"property": "p"}, {"date": "2021-06-30"}]}
            """));
        assertTrue(ex.getMessage().contains("Unknown CQL2 operator"), ex.getMessage());
    }

    @Test
    public void everyOperandFormParses() {
        CqlExpression.CqlTemporal parsed = (CqlExpression.CqlTemporal) CqlParser.parse("""
            {"op": "t_during", "args": [
                {"interval": [{"property": "p"}, ".."]},
                {"interval": ["2021-06-01", "2021-06-30T12:00:00Z"]}]}
            """);
        assertEquals(new CqlExpression.TemporalInterval(
            new CqlExpression.TemporalProperty("p"), CqlExpression.OpenEnd.OPEN), parsed.first());
        assertEquals(new CqlExpression.TemporalInterval(
            new CqlExpression.LiteralEnd("2021-06-01"), new CqlExpression.LiteralEnd("2021-06-30T12:00:00Z")),
            parsed.second());

        CqlExpression.CqlTemporal instants = (CqlExpression.CqlTemporal) CqlParser.parse("""
            {"op": "t_before", "args": [{"timestamp": "2021-06-30T12:00:00Z"}, {"date": "2021-07-01"}]}
            """);
        assertEquals(new CqlExpression.TemporalInstant("2021-06-30T12:00:00Z"), instants.first());
        assertEquals(new CqlExpression.TemporalInstant("2021-07-01"), instants.second());
    }

    /**
     * {@code luc:query} and {@code luc:facet} in one SPARQL query share a search when their
     * filters' canonical forms agree, so swapping the operands, which changes the meaning,
     * must change the form.
     */
    @Test
    public void theCanonicalFormKeepsOperandOrder() {
        String propertyFirst = CqlParser.parse("""
            {"op": "t_contains", "args": [{"property": "p"}, {"date": "2021-06-30"}]}
            """).toCanonical();
        String propertySecond = CqlParser.parse("""
            {"op": "t_contains", "args": [{"date": "2021-06-30"}, {"property": "p"}]}
            """).toCanonical();
        assertNotEquals(propertyFirst, propertySecond);
    }
}
