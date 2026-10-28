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

package org.apache.jena.query.text.assembler;

import java.util.*;

import org.apache.jena.graph.Node;
import org.apache.jena.query.text.ShaclIndexMapping.*;
import org.apache.jena.query.text.ShaclShapeCondition;
import org.apache.jena.query.text.TextIndexException;
import org.apache.jena.sparql.path.P_Alt;
import org.apache.jena.sparql.path.P_Inverse;
import org.apache.jena.sparql.path.P_Seq;
import org.apache.jena.sparql.path.Path;

/**
 * Checks each {@code idx:copyOf}: that the source field it names holds every value the copy
 * field declaring it indexes, so that a match on the copy field can be reported as a match on
 * its source field.
 * <p>
 * Both are text fields. In every shape that feeds the copy field, the source field is fed by
 * equal occurrences: the same paths, the same steps with the same filters, the same value
 * constraints, the same {@code idx:self} and the same {@code idx:nested} block, or the same
 * column of an external source. The source field may also be fed by shapes that do not feed
 * the copy field.
 * <p>
 * A field that is not {@code idx:multiValued} keeps only its first value, in the order of
 * the field's occurrences and then of what each path reaches. A source field that keeps only
 * one therefore keeps the same one only when the copy field is single-valued too and, in
 * each scope, each is fed by one occurrence whose alternatives come in the same order. The
 * order of two occurrences is the order the configuration's triples are read in, which
 * nothing fixes.
 */
final class CopyFields {

    private CopyFields() {}

    /**
     * How a field is fed in one shape.
     *
     * @param scope       the {@code idx:nested} block, or "" at the root
     * @param unordered   a form equal for occurrences that reach the same values
     * @param ordered     a form equal for occurrences that reach them in the same order
     * @param description the occurrence, as an error message names it
     */
    private record Fed(String scope, String unordered, String ordered, String description) {

        static Fed of(FieldOccurrence occurrence) {
            String scope = occurrence.getNestedName() == null ? "" : occurrence.getNestedName();
            StringBuilder unordered = new StringBuilder(scope).append(" |");
            StringBuilder ordered = new StringBuilder(scope).append(" |");
            StringBuilder description = new StringBuilder();
            for (StepDef step : occurrence.getSteps()) {
                String filter = condition(step.getFilter());
                unordered.append(' ').append(pathKey(step.getPath(), false)).append(" filter ").append(filter);
                ordered.append(' ').append(pathKey(step.getPath(), true)).append(" filter ").append(filter);
                description.append("sh:path ").append(step.getPath());
                if (step.getFilter() != null) {
                    description.append(" filtered by ").append(filter);
                }
                description.append(" then ");
            }
            String constraints = condition(occurrence.getValueCondition());
            if (occurrence.isSelf()) {
                unordered.append(" | self");
                ordered.append(" | self");
                description.append("idx:self");
            } else {
                unordered.append(" | ").append(pathKey(occurrence.getLocalPath(), false));
                ordered.append(" | ").append(pathKey(occurrence.getLocalPath(), true));
                description.append("sh:path ").append(occurrence.getLocalPath());
            }
            unordered.append(" | ").append(constraints);
            ordered.append(" | ").append(constraints);
            if (occurrence.getValueCondition() != null) {
                description.append(" with the value constraints ").append(constraints);
            }
            description.append(inScope(scope));
            return new Fed(scope, unordered.toString(), ordered.toString(), description.toString());
        }

        static Fed of(NestedDef nested, ColumnBinding column) {
            String name = column.getColumnName() != null
                ? "column '" + column.getColumnName() + "'"
                : "column " + column.getColumnIndex();
            String key = nested.getNestedName() + " | " + name;
            return new Fed(nested.getNestedName(), key, key,
                name + " of the external source" + inScope(nested.getNestedName()));
        }
    }

    static void check(List<IndexProfile> profiles, Map<String, FieldDef> fields, Map<FieldDef, Node> copies) {
        for (Map.Entry<FieldDef, Node> declaration : copies.entrySet()) {
            FieldDef field = declaration.getKey();
            String iri = declaration.getValue().getURI();
            FieldDef source = fields.get(iri);
            if (source == null) {
                throw new TextIndexException("idx:copyOf on field '" + field.getFieldName() + "' names <" + iri
                    + ">, which no shape of this index feeds.");
            }
            String declared = "Field '" + field.getFieldName() + "' is idx:copyOf '" + source.getFieldName() + "', but ";
            if (source == field) {
                throw new TextIndexException(declared + "a field cannot be a copy of itself.");
            }
            for (FieldDef f : List.of(field, source)) {
                if (f.getFieldType() != FieldType.TEXT) {
                    throw new TextIndexException(declared + "'" + f.getFieldName() + "' has type " + f.getFieldType()
                        + ". A copy field and its source field are both TextFields.");
                }
            }
            Node sourcesSource = copies.get(source);
            if (sourcesSource != null) {
                FieldDef named = fields.get(sourcesSource.getURI());
                throw new TextIndexException(declared + "'" + source.getFieldName() + "' is itself idx:copyOf "
                    + (named == null ? "<" + sourcesSource.getURI() + ">" : "'" + named.getFieldName() + "'")
                    + ". Name that field instead: a match is reported under one source field, not a chain of them.");
            }
            for (IndexProfile profile : profiles) {
                checkFedAlike(declared, profile, field, source);
            }
            if (!source.isMultiValued()) {
                checkSameFirstValue(declared, profiles, field, source);
            }
        }
    }

    /** In a shape that feeds {@code field}, its source field is fed by equal occurrences. */
    private static void checkFedAlike(String declared, IndexProfile profile, FieldDef field, FieldDef source) {
        List<Fed> fed = fed(profile, field);
        if (fed.isEmpty()) {
            return;
        }
        List<Fed> sourceFed = fed(profile, source);
        Fed unmatched = firstUnmatched(fed, sourceFed);
        Fed sourceUnmatched = firstUnmatched(sourceFed, fed);
        if (unmatched == null && sourceUnmatched == null) {
            return;
        }
        StringBuilder message = new StringBuilder(declared).append("in shape ").append(shape(profile))
            .append(" the occurrence ");
        if (unmatched != null) {
            message.append(unmatched.description()).append(" feeds '").append(field.getFieldName())
                .append("' and no occurrence of '").append(source.getFieldName()).append("' matches it.");
            if (sourceUnmatched != null) {
                message.append(" '").append(source.getFieldName()).append("' is fed there by the occurrence ")
                    .append(sourceUnmatched.description()).append(" instead.");
            }
        } else {
            message.append(sourceUnmatched.description()).append(" feeds '").append(source.getFieldName())
                .append("' and no occurrence of '").append(field.getFieldName()).append("' matches it.");
        }
        message.append(" A copy field and its source field are fed alike, to index the same values: by the same")
            .append(" paths, steps, step filters, value constraints, idx:self and idx:nested block.");
        throw new TextIndexException(message.toString());
    }

    /** {@code source} keeps only its first value, so it must be the first value of {@code field}. */
    private static void checkSameFirstValue(String declared, List<IndexProfile> profiles,
                                            FieldDef field, FieldDef source) {
        if (field.isMultiValued()) {
            throw new TextIndexException(declared + "'" + field.getFieldName() + "' is idx:multiValued and '"
                + source.getFieldName() + "' is not, so '" + source.getFieldName() + "' keeps only the first of the values '"
                + field.getFieldName() + "' indexes. Make '" + source.getFieldName() + "' idx:multiValued.");
        }
        String singleValued = declared + "neither is idx:multiValued, so each keeps only its first value, and ";
        for (IndexProfile profile : profiles) {
            Map<String, List<Fed>> byScope = byScope(fed(profile, field));
            Map<String, List<Fed>> sourceByScope = byScope(fed(profile, source));
            for (Map.Entry<String, List<Fed>> scope : byScope.entrySet()) {
                List<Fed> fed = scope.getValue();
                List<Fed> sourceFed = sourceByScope.getOrDefault(scope.getKey(), List.of());
                for (Map.Entry<FieldDef, List<Fed>> each : List.of(Map.entry(field, fed), Map.entry(source, sourceFed))) {
                    if (each.getValue().size() != 1) {
                        throw new TextIndexException(singleValued + "in shape " + shape(profile) + " '"
                            + each.getKey().getFieldName() + "' is fed by " + each.getValue().size() + " occurrences"
                            + inScope(scope.getKey()) + ", whose values come in an order nothing fixes. Make '"
                            + source.getFieldName() + "' idx:multiValued, or feed each field by one occurrence.");
                    }
                }
                if (!fed.get(0).ordered().equals(sourceFed.get(0).ordered())) {
                    throw new TextIndexException(singleValued + "in shape " + shape(profile) + " the occurrences "
                        + fed.get(0).description() + " and " + sourceFed.get(0).description()
                        + " reach their values in different orders. Make '" + source.getFieldName()
                        + "' idx:multiValued, or list the alternatives in the same order.");
                }
            }
        }
    }

    /** How {@code profile} feeds {@code field}, in the profile's order. */
    private static List<Fed> fed(IndexProfile profile, FieldDef field) {
        List<Fed> fed = new ArrayList<>();
        for (FieldOccurrence occurrence : profile.getRootOccurrences()) {
            if (feeds(occurrence.getField(), field)) {
                fed.add(Fed.of(occurrence));
            }
        }
        for (NestedDef nested : profile.getNestedDefs()) {
            for (FieldOccurrence occurrence : nested.getOccurrences()) {
                if (feeds(occurrence.getField(), field)) {
                    fed.add(Fed.of(occurrence));
                }
            }
            if (nested.isExternal()) {
                for (ColumnBinding column : nested.getExternalSource().getColumns()) {
                    if (feeds(column.getField(), field)) {
                        fed.add(Fed.of(nested, column));
                    }
                }
            }
        }
        return fed;
    }

    private static boolean feeds(FieldDef fed, FieldDef field) {
        return fed.getFieldIRI().equals(field.getFieldIRI());
    }

    private static Fed firstUnmatched(List<Fed> fed, List<Fed> others) {
        Set<String> keys = new HashSet<>();
        for (Fed other : others) {
            keys.add(other.unordered());
        }
        for (Fed each : fed) {
            if (!keys.contains(each.unordered())) {
                return each;
            }
        }
        return null;
    }

    private static Map<String, List<Fed>> byScope(List<Fed> fed) {
        Map<String, List<Fed>> byScope = new LinkedHashMap<>();
        for (Fed each : fed) {
            byScope.computeIfAbsent(each.scope(), k -> new ArrayList<>()).add(each);
        }
        return byScope;
    }

    /**
     * A path's form. Unordered, an alternative path's members are a set: they reach the same
     * values in whatever order they are listed. Ordered, they are the sequence they reach
     * them in. The index accepts links, inverses, sequences and alternatives.
     */
    private static String pathKey(Path path, boolean ordered) {
        if (ordered) {
            return String.valueOf(path);
        }
        if (path instanceof P_Alt) {
            SortedSet<String> members = new TreeSet<>();
            collectAlternatives(path, members);
            return "alternatives" + members;
        }
        if (path instanceof P_Seq seq) {
            return "(" + pathKey(seq.getLeft(), false) + "/" + pathKey(seq.getRight(), false) + ")";
        }
        if (path instanceof P_Inverse inverse) {
            return "^(" + pathKey(inverse.getSubPath(), false) + ")";
        }
        return String.valueOf(path);
    }

    private static void collectAlternatives(Path path, Set<String> members) {
        if (path instanceof P_Alt alt) {
            collectAlternatives(alt.getLeft(), members);
            collectAlternatives(alt.getRight(), members);
        } else {
            members.add(pathKey(path, false));
        }
    }

    private static String condition(ShaclShapeCondition condition) {
        return condition == null ? "none" : condition.getCanonical();
    }

    private static String inScope(String scope) {
        return scope.isEmpty() ? "" : " in idx:nested " + scope;
    }

    private static String shape(IndexProfile profile) {
        Node node = profile.getShapeNode();
        return node.isURI() ? "<" + node.getURI() + ">" : node.toString();
    }
}
