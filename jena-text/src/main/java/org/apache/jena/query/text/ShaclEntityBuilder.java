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
import org.apache.jena.query.text.ShaclIndexMapping.CorrelatedHierarchy;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.ARQ;
import org.apache.jena.sparql.path.eval.PathEval;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.RDF;

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

        for (FieldOccurrence occurrence : profile.getRootOccurrences()) {
            addValues(docValues, occurrence.getField().getFieldName(),
                extractOccurrenceValues(graph, subject, occurrence));
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

                for (FieldOccurrence occurrence : nestedDef.getOccurrences()) {
                    List<Object> values = extractOccurrenceValues(graph, child, occurrence);
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

        for (CorrelatedHierarchy hierarchy : profile.getCorrelatedHierarchies()) {
            addCorrelatedHierarchyPaths(graph, subject, entity, hierarchy);
        }

        return entity;
    }

    /**
     * Walk the graph to build the facet paths of a correlated hierarchy.
     * <p>
     * Starts at the innermost (deepest, shortest-path) level and ascends one level at a
     * time, so each emitted path is a chain of edges that exists in the data. An entity
     * with two display tables in different groupings yields exactly its two real paths,
     * where cross-producting the two levels' values would yield four.
     */
    private static void addCorrelatedHierarchyPaths(Graph graph, Node subject, Entity entity,
            CorrelatedHierarchy hierarchy) {
        int innermost = hierarchy.getDepth() - 1;
        Iterator<Node> iter = PathEval.eval(graph, subject, hierarchy.getInnermostPath(), indexingContext());
        while (iter.hasNext()) {
            Node node = iter.next();
            ascendHierarchy(graph, entity, hierarchy, innermost, node, new ArrayDeque<>());
        }
    }

    /**
     * Prepend the value of {@code node} at {@code level} and recurse to the level above,
     * emitting a facet path once level 0 is reached. A level that contributes no usable
     * value, or whose ancestor step finds nothing, contributes no path at all — a
     * partial path would count the entity under a parent it does not have.
     */
    private static void ascendHierarchy(Graph graph, Entity entity, CorrelatedHierarchy hierarchy,
            int level, Node node, Deque<String> pathSoFar) {
        FieldOccurrence occurrence = hierarchy.getLevelOccurrence(level);
        if (!satisfiesConstraints(graph, node, occurrence)) {
            return;
        }
        Object value = nodeToValue(node, occurrence.getField().getFieldType(),
            occurrence.getField().preservesLiteralMetadata());
        if (value == null) {
            return;
        }
        String component = value.toString();
        if (component.isBlank()) {
            return;
        }

        pathSoFar.addFirst(component);
        if (level == 0) {
            entity.addHierarchyPath(hierarchy.getDimensionName(), new ArrayList<>(pathSoFar));
        } else {
            Iterator<Node> iter = PathEval.eval(graph, node, hierarchy.getAscentPath(level - 1),
                indexingContext());
            Set<Node> seen = new LinkedHashSet<>();
            while (iter.hasNext()) {
                Node ancestor = iter.next();
                if (seen.add(ancestor)) {
                    ascendHierarchy(graph, entity, hierarchy, level - 1, ancestor, pathSoFar);
                }
            }
        }
        pathSoFar.removeFirst();
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

    private static List<Object> extractOccurrenceValues(Graph graph, Node subject, FieldOccurrence occurrence) {
        LinkedHashSet<Object> values = new LinkedHashSet<>();
        Iterator<Node> iter = PathEval.eval(graph, subject, occurrence.getPath(), indexingContext());
        while (iter.hasNext()) {
            Node endpoint = iter.next();
            if (!satisfiesConstraints(graph, endpoint, occurrence)) {
                continue;
            }
            Object value = nodeToValue(endpoint, occurrence.getField().getFieldType(),
                occurrence.getField().preservesLiteralMetadata());
            if (value != null) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    private static boolean satisfiesConstraints(Graph graph, Node endpoint, FieldOccurrence occurrence) {
        if (occurrence.getRequiredClass() != null) {
            if (!endpoint.isBlank() && !endpoint.isURI()) {
                return false;
            }
            if (!graph.find(endpoint, RDF.type.asNode(), occurrence.getRequiredClass()).hasNext()) {
                return false;
            }
        }
        if (occurrence.getNodeKindConstraint() != null
                && !occurrence.getNodeKindConstraint().matches(endpoint)) {
            return false;
        }
        if (occurrence.getDatatype() != null) {
            if (!endpoint.isLiteral()) {
                return false;
            }
            String datatypeUri = endpoint.getLiteralDatatypeURI();
            if (!Objects.equals(datatypeUri, occurrence.getDatatype().getURI())) {
                return false;
            }
        }
        return true;
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
