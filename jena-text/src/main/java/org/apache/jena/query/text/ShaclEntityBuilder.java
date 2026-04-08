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
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.sparql.path.P_Link;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.eval.PathEval;
import org.apache.jena.util.iterator.ExtendedIterator;

/**
 * Builds parent entities and nested child records from a graph and a parsed index profile.
 */
final class ShaclEntityBuilder {

    private ShaclEntityBuilder() {}

    static Entity buildEntity(Graph graph, Node subject, String entityUri, IndexProfile profile) {
        Entity entity = new Entity(entityUri, null);

        for (FieldDef fieldDef : profile.getFields()) {
            if (fieldDef.isNestedScoped()) {
                continue;
            }
            addFieldValues(entity, fieldDef.getFieldName(), extractFieldValues(graph, subject, fieldDef));
        }

        for (NestedDef nestedDef : profile.getNestedDefs()) {
            Iterator<Node> childIter = PathEval.eval(graph, subject, nestedDef.getJoinPath(), null);
            while (childIter.hasNext()) {
                Node child = childIter.next();
                Entity.NestedRecord record = new Entity.NestedRecord();
                boolean hasValues = false;
                for (FieldDef fieldDef : nestedDef.getFields()) {
                    List<Object> values = extractFieldValues(graph, child, fieldDef);
                    if (!values.isEmpty()) {
                        hasValues = true;
                        addFieldValues(entity, fieldDef.getFieldName(), values);
                        addFieldValues(record, fieldDef.getFieldName(), values);
                    }
                }
                if (hasValues) {
                    entity.addNestedRecord(nestedDef.getNestedName(), record);
                }
            }
        }

        return entity;
    }

    private static List<Object> extractFieldValues(Graph graph, Node subject, FieldDef fieldDef) {
        List<Object> values = new ArrayList<>();
        Path path = fieldDef.getPath();

        if (path != null && fieldDef.hasComplexPath()) {
            Iterator<Node> iter = PathEval.eval(graph, subject, path, null);
            while (iter.hasNext()) {
                Object value = nodeToValue(iter.next(), fieldDef.getFieldType());
                if (value != null) {
                    values.add(value);
                }
            }
            return values;
        }

        if (path instanceof P_Link pLink) {
            ExtendedIterator<Node> iter = graph.find(subject, pLink.getNode(), Node.ANY)
                .mapWith(t -> t.getObject());
            try {
                while (iter.hasNext()) {
                    Object value = nodeToValue(iter.next(), fieldDef.getFieldType());
                    if (value != null) {
                        values.add(value);
                    }
                }
            } finally {
                iter.close();
            }
            return values;
        }

        for (Node predicate : fieldDef.getPredicates()) {
            ExtendedIterator<Node> iter = graph.find(subject, predicate, Node.ANY)
                .mapWith(t -> t.getObject());
            try {
                while (iter.hasNext()) {
                    Object value = nodeToValue(iter.next(), fieldDef.getFieldType());
                    if (value != null) {
                        values.add(value);
                    }
                }
            } finally {
                iter.close();
            }
        }
        return values;
    }

    private static void addFieldValues(Entity entity, String fieldName, List<Object> values) {
        for (Object value : values) {
            entity.addValue(fieldName, value);
        }
    }

    private static void addFieldValues(Entity.NestedRecord record, String fieldName, List<Object> values) {
        for (Object value : values) {
            record.addValue(fieldName, value);
        }
    }

    static Object nodeToValue(Node obj, FieldType fieldType) {
        if (obj.isLiteral()) {
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
