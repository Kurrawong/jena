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

import java.util.List;
import java.util.Map;

/**
 * A CQL2 temporal relation, as comparisons between the ends of its two operands' spans:
 * all of them hold, or with {@code anyOf} at least one. The definitions are OGC
 * 21-065r2's Tables 5 and 6 and the OWL-Time relations Table 6 cites. Every comparison is
 * strict or an equality, so spans are closed at both ends.
 */
record TemporalRelation(List<Condition> conditions, boolean anyOf) {

    enum End { START, END }

    enum Comparison {
        LT, LE, GT, GE, EQ;

        boolean holds(long left, long right) {
            return switch (this) {
                case LT -> left < right;
                case LE -> left <= right;
                case GT -> left > right;
                case GE -> left >= right;
                case EQ -> left == right;
            };
        }

        /** The same comparison with its operands swapped: {@code a < b} is {@code b > a}. */
        Comparison flipped() {
            return switch (this) {
                case LT -> GT;
                case LE -> GE;
                case GT -> LT;
                case GE -> LE;
                case EQ -> EQ;
            };
        }
    }

    /** {@code first}'s end compared with {@code second}'s end. */
    record Condition(End first, Comparison comparison, End second) {}

    private static final Map<String, TemporalRelation> RELATIONS = Map.ofEntries(
        Map.entry("t_after", allOf(new Condition(End.START, Comparison.GT, End.END))),
        Map.entry("t_before", allOf(new Condition(End.END, Comparison.LT, End.START))),
        Map.entry("t_during", allOf(
            new Condition(End.START, Comparison.GT, End.START),
            new Condition(End.END, Comparison.LT, End.END))),
        Map.entry("t_contains", allOf(
            new Condition(End.START, Comparison.LT, End.START),
            new Condition(End.END, Comparison.GT, End.END))),
        Map.entry("t_overlaps", allOf(
            new Condition(End.START, Comparison.LT, End.START),
            new Condition(End.END, Comparison.GT, End.START),
            new Condition(End.END, Comparison.LT, End.END))),
        Map.entry("t_overlappedBy", allOf(
            new Condition(End.START, Comparison.GT, End.START),
            new Condition(End.START, Comparison.LT, End.END),
            new Condition(End.END, Comparison.GT, End.END))),
        Map.entry("t_meets", allOf(new Condition(End.END, Comparison.EQ, End.START))),
        Map.entry("t_metBy", allOf(new Condition(End.START, Comparison.EQ, End.END))),
        // Table 6 links T_STARTS and T_STARTEDBY to each other's OWL-Time relation; its
        // Figure 6 and test data follow Allen, as these do.
        Map.entry("t_starts", allOf(
            new Condition(End.START, Comparison.EQ, End.START),
            new Condition(End.END, Comparison.LT, End.END))),
        Map.entry("t_startedBy", allOf(
            new Condition(End.START, Comparison.EQ, End.START),
            new Condition(End.END, Comparison.GT, End.END))),
        Map.entry("t_finishes", allOf(
            new Condition(End.START, Comparison.GT, End.START),
            new Condition(End.END, Comparison.EQ, End.END))),
        Map.entry("t_finishedBy", allOf(
            new Condition(End.START, Comparison.LT, End.START),
            new Condition(End.END, Comparison.EQ, End.END))),
        Map.entry("t_equals", allOf(
            new Condition(End.START, Comparison.EQ, End.START),
            new Condition(End.END, Comparison.EQ, End.END))),
        // Before or after: a positive predicate, so a value that is either matches, and an
        // entity with no value does not.
        Map.entry("t_disjoint", anyOf(
            new Condition(End.END, Comparison.LT, End.START),
            new Condition(End.START, Comparison.GT, End.END))),
        // NOT t_disjoint, applied to each value: neither before nor after.
        Map.entry("t_intersects", allOf(
            new Condition(End.START, Comparison.LE, End.END),
            new Condition(End.END, Comparison.GE, End.START))));

    /** The relation an operator names, or null if it names none. */
    static TemporalRelation forOperator(String op) {
        return RELATIONS.get(op);
    }

    private static TemporalRelation allOf(Condition... conditions) {
        return new TemporalRelation(List.of(conditions), false);
    }

    private static TemporalRelation anyOf(Condition... conditions) {
        return new TemporalRelation(List.of(conditions), true);
    }
}
