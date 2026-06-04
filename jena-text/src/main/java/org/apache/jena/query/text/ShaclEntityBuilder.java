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
