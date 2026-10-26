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

import java.util.*;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.ShaclIndexMapping.StepDef;
import org.apache.jena.query.ARQ;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.eval.PathEval;
import org.apache.jena.sparql.util.Context;

/**
 * Builds parent entities and nested child records from a graph and a parsed index profile.
 */
final class ShaclEntityBuilder {

    private ShaclEntityBuilder() {}

    /**
     * Context used for all PathEval calls during indexing. Property functions are
     * disabled so that predicates registered as SPARQL PFs (e.g. geo:sfWithin) are
     * treated as plain triple-store predicates rather than being invoked as functions.
     */
    static Context indexingContext() {
        Context ctx = ARQ.getContext().copy();
        ctx.set(ARQ.propertyFunctions, false);
        return ctx;
    }

    static Entity buildEntity(Graph graph, Node subject, String entityUri, IndexProfile profile) {
        Entity entity = new Entity(entityUri, null);
        Map<String, LinkedHashSet<Object>> docValues = new LinkedHashMap<>();

        Map<List<StepDef>, Set<Node>> rootKept = new HashMap<>();
        for (FieldOccurrence occurrence : profile.getRootOccurrences()) {
            addValues(docValues, occurrence.getField().getFieldName(),
                extractOccurrenceValues(graph, subject, occurrence, rootKept));
        }

        for (NestedDef nestedDef : profile.getNestedDefs()) {
            if (nestedDef.isExternal()) {
                // Children come from rows, not from the graph. ShaclBulkIndexer attaches
                // them after this call; there is nothing to traverse here.
                continue;
            }
            Iterator<Node> childIter = PathEval.eval(graph, subject, nestedDef.getJoinPath(), indexingContext());
            while (childIter.hasNext()) {
                Node child = childIter.next();
                Map<String, LinkedHashSet<Object>> recordValues = new LinkedHashMap<>();
                Map<List<StepDef>, Set<Node>> childKept = new HashMap<>();

                for (FieldOccurrence occurrence : nestedDef.getOccurrences()) {
                    List<Object> values = extractOccurrenceValues(graph, child, occurrence, childKept);
                    if (!values.isEmpty()) {
                        // Child-scoped values go ONLY to the per-record map; they are
                        // emitted on the child Lucene doc by the indexer, not flattened
                        // onto the parent. This is what enables same-child correlation
                        // at query time via ToParentBlockJoinQuery (block-join PR-B).
                        addValues(recordValues, occurrence.getField().getFieldName(), values);
                    }
                }

                if (!recordValues.isEmpty()) {
                    entity.addNestedRecord(nestedDef.getNestedName(), toNestedRecord(recordValues));
                }
            }
        }

        for (Map.Entry<String, LinkedHashSet<Object>> entry : docValues.entrySet()) {
            for (Object value : entry.getValue()) {
                entity.addValue(entry.getKey(), value);
            }
        }

        return entity;
    }

    private static Entity.NestedRecord toNestedRecord(Map<String, LinkedHashSet<Object>> recordValues) {
        Entity.NestedRecord record = new Entity.NestedRecord();
        for (Map.Entry<String, LinkedHashSet<Object>> entry : recordValues.entrySet()) {
            for (Object value : entry.getValue()) {
                record.addValue(entry.getKey(), value);
            }
        }
        return record;
    }

    private static void addValues(Map<String, LinkedHashSet<Object>> target, String fieldName, List<Object> values) {
        if (values.isEmpty()) {
            return;
        }
        target.computeIfAbsent(fieldName, key -> new LinkedHashSet<>()).addAll(values);
    }

    /**
     * The values of one occurrence, evaluated from the scope's focus node: through the
     * occurrence's steps to the kept nodes, then along its own path, keeping each value
     * that satisfies its value constraints.
     *
     * @param kept nodes kept by each chain of steps from {@code focus}, shared by the
     *             occurrences of one scope so that a step is evaluated once
     */
    private static List<Object> extractOccurrenceValues(Graph graph, Node focus, FieldOccurrence occurrence,
                                                        Map<List<StepDef>, Set<Node>> kept) {
        LinkedHashSet<Object> values = new LinkedHashSet<>();
        for (Node start : keptNodes(graph, focus, occurrence.getSteps(), kept)) {
            if (occurrence.isSelf()) {
                // The node the occurrence is evaluated from: the entity at root scope, the
                // child node inside a nested block, or a node kept by a step. Constraints
                // still apply; they filter that node.
                addValue(values, graph, start, occurrence);
                continue;
            }
            Iterator<Node> iter = PathEval.eval(graph, start, occurrence.getLocalPath(), indexingContext());
            while (iter.hasNext()) {
                addValue(values, graph, iter.next(), occurrence);
            }
        }
        return new ArrayList<>(values);
    }

    private static void addValue(Set<Object> values, Graph graph, Node endpoint, FieldOccurrence occurrence) {
        if (!occurrence.accepts(graph, endpoint)) {
            return;
        }
        Object value = occurrence.getPeriodPaths() != null
            ? periodSpan(graph, endpoint, occurrence.getPeriodPaths())
            : nodeToValue(endpoint, occurrence.getField().getFieldType(),
                occurrence.getField().preservesLiteralMetadata());
        if (value != null) {
            values.add(value);
        }
    }

    /**
     * The span of a period node: from its start value's start to its end value's end, so a
     * period ending on a date runs to the end of that day.
     */
    private static TemporalSpan periodSpan(Graph graph, Node period, FieldOccurrence.PeriodPaths paths) {
        List<Node> starts = reached(graph, period, paths.start());
        List<Node> ends = reached(graph, period, paths.end());
        if (starts.size() != 1 || ends.size() != 1) {
            return null;
        }
        TemporalSpan start = TemporalSpan.of(starts.get(0));
        TemporalSpan end = TemporalSpan.of(ends.get(0));
        if (start == null || end == null) {
            return null;
        }
        return new TemporalSpan(start.start(), end.end());
    }

    private static List<Node> reached(Graph graph, Node from, Path path) {
        List<Node> nodes = new ArrayList<>();
        PathEval.eval(graph, from, path, indexingContext()).forEachRemaining(nodes::add);
        return nodes;
    }

    /** The nodes reached from {@code focus} through {@code steps}, each kept by its step's filter. */
    private static Set<Node> keptNodes(Graph graph, Node focus, List<StepDef> steps,
                                       Map<List<StepDef>, Set<Node>> kept) {
        if (steps.isEmpty()) {
            return Collections.singleton(focus);
        }
        Set<Node> cached = kept.get(steps);
        if (cached != null) {
            return cached;
        }
        StepDef last = steps.get(steps.size() - 1);
        Set<Node> result = new LinkedHashSet<>();
        for (Node from : keptNodes(graph, focus, steps.subList(0, steps.size() - 1), kept)) {
            Iterator<Node> iter = PathEval.eval(graph, from, last.getPath(), indexingContext());
            while (iter.hasNext()) {
                Node reached = iter.next();
                if (!result.contains(reached) && last.keeps(graph, reached)) {
                    result.add(reached);
                }
            }
        }
        kept.put(List.copyOf(steps), result);
        return result;
    }

    static Object nodeToValue(Node obj, FieldType fieldType) {
        return nodeToValue(obj, fieldType, false);
    }

    static Object nodeToValue(Node obj, FieldType fieldType, boolean preserveLiteralMetadata) {
        if (obj.isLiteral()) {
            if (preserveLiteralMetadata || fieldType == FieldType.TEMPORAL) {
                return obj;
            }
            return switch (fieldType) {
                case INT -> {
                    try {
                        yield Integer.parseInt(obj.getLiteralLexicalForm());
                    } catch (NumberFormatException e) {
                        yield null;
                    }
                }
                case LONG -> {
                    try {
                        yield Long.parseLong(obj.getLiteralLexicalForm());
                    } catch (NumberFormatException e) {
                        yield null;
                    }
                }
                case DOUBLE -> {
                    try {
                        yield Double.parseDouble(obj.getLiteralLexicalForm());
                    } catch (NumberFormatException e) {
                        yield null;
                    }
                }
                case LATLON -> obj.getLiteralLexicalForm();
                default -> obj.getLiteralLexicalForm();
            };
        }
        if (obj.isURI()) {
            return obj.getURI();
        }
        return null;
    }
}
