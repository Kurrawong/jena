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

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.text.ShaclShapeCondition;
import org.apache.jena.query.text.TextIndexException;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.shacl.vocabulary.SHACL;
import org.apache.jena.shacl.vocabulary.SHACLM;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.vocabulary.RDF;

/**
 * Builds the jena-shacl conditions of a SHACL index configuration: the filter of an
 * {@code sh:node} step, and the value constraints of a field occurrence. Each is copied
 * out of the configuration graph into a small graph of its own and parsed there, so that
 * the {@code idx:} members of the configuration never reach the SHACL parser.
 */
final class ShaclConstraints {

    /**
     * Constraints SHACL defines per value node. On a field occurrence they are applied to
     * each value the occurrence reaches. {@code sh:flags} accompanies {@code sh:pattern}.
     */
    static final List<Property> VALUE_CONSTRAINTS = List.of(
        SHACLM.class_, SHACLM.datatype, SHACLM.nodeKind, SHACLM.in,
        SHACLM.pattern, SHACLM.flags, SHACLM.minLength, SHACLM.maxLength, SHACLM.languageIn,
        SHACLM.minInclusive, SHACLM.maxInclusive, SHACLM.minExclusive, SHACLM.maxExclusive,
        SHACLM.node, SHACLM.and, SHACLM.or, SHACLM.not, SHACLM.xone);

    /**
     * Constraints that describe the set of values a path reaches, or compare it with
     * another property's values. They have no per-value meaning, so they are rejected on a
     * field occurrence. Inside a filter they keep their SHACL meaning. Listed in the order
     * an error reports them.
     */
    static final List<Property> SET_CONSTRAINTS = List.of(
        SHACLM.hasValue, SHACLM.minCount, SHACLM.maxCount, SHACLM.uniqueLang,
        SHACLM.qualifiedValueShape, SHACLM.qualifiedMinCount, SHACLM.qualifiedMaxCount,
        SHACLM.qualifiedValueShapesDisjoint,
        SHACLM.equals, SHACLM.disjoint, SHACLM.lessThan, SHACLM.lessThanOrEquals);

    /** Every constraint predicate the index recognises, for rejecting them on a step. */
    static final List<Property> ALL;

    static {
        List<Property> all = new ArrayList<>(VALUE_CONSTRAINTS);
        all.addAll(SET_CONSTRAINTS);
        all.add(SHACLM.sparql);
        all.add(SHACLM.closed);
        ALL = List.copyOf(all);
    }

    /** Predicates whose object is a shape, so a named object's triples belong to the condition. */
    private static final Set<Node> SHAPE_REFERENCES = Set.of(
        SHACL.node, SHACL.property, SHACL.not, SHACL.qualifiedValueShape);

    /** Predicates whose object is a list of shapes. */
    private static final Set<Node> SHAPE_LISTS = Set.of(SHACL.and, SHACL.or, SHACL.xone);

    private static final Node RDF_FIRST = RDF.first.asNode();
    private static final Node RDF_REST = RDF.rest.asNode();

    private ShaclConstraints() {}

    /** {@code sh:localName}, as written in a configuration. */
    static String name(Property property) {
        return "sh:" + property.getLocalName();
    }

    /**
     * The filter of a step: the {@code sh:node} shape's triples, minus its field
     * occurrences and further steps, or null when nothing else is left.
     */
    static ShaclShapeCondition filterCondition(Resource shapeRes) {
        Graph source = shapeRes.getModel().getGraph();
        Graph target = GraphFactory.createDefaultGraph();
        Node shapeNode = shapeRes.asNode();
        Set<Node> visited = new HashSet<>();
        visited.add(shapeNode);
        for (Statement stmt : shapeRes.listProperties().toList()) {
            RDFNode object = stmt.getObject();
            if (stmt.getPredicate().equals(SHACLM.property) && object.isResource()
                    && (object.asResource().hasProperty(IndexVocab.pField)
                        || ShaclIndexAssembler.isStep(object.asResource()))) {
                continue;
            }
            if (stmt.getPredicate().equals(RDF.type)) {
                continue;
            }
            copyTriple(source, target, stmt.asTriple(), false, visited);
        }
        if (target.isEmpty()) {
            return null;
        }
        String owner = "the sh:node shape " + shapeRes;
        check(source, target, owner);
        return ShaclShapeCondition.parse(target, shapeNode, owner);
    }

    /**
     * The value constraints of a field occurrence, copied onto a fresh node shape, or null
     * when it has none. A set constraint or {@code sh:sparql} is a configuration error.
     */
    static ShaclShapeCondition valueCondition(Resource occurrenceRes) {
        String owner = "field occurrence " + occurrenceRes;
        if (occurrenceRes.hasProperty(SHACLM.sparql)) {
            throw sparqlRejected(owner);
        }
        for (Property constraint : SET_CONSTRAINTS) {
            if (occurrenceRes.hasProperty(constraint)) {
                Property reported = constraint.getLocalName().startsWith("qualified")
                    ? SHACLM.qualifiedValueShape
                    : constraint;
                throw new TextIndexException("Field occurrence " + occurrenceRes + " uses " + name(reported)
                    + ", which describes the set of values the path reaches rather than each value, "
                    + "so it has no meaning on a field occurrence. It is accepted inside a sh:node "
                    + "filter; to index only some values, use a per-value constraint such as sh:in.");
            }
        }
        if (occurrenceRes.hasProperty(SHACLM.closed)) {
            throw new TextIndexException("Field occurrence " + occurrenceRes + " uses sh:closed, "
                + "which is not supported in text:shapes.");
        }
        if (occurrenceRes.hasProperty(SHACLM.property)) {
            // Not a value constraint the index applies, so it would otherwise be ignored
            // and every value indexed.
            throw new TextIndexException("Field occurrence " + occurrenceRes + " has sh:property. "
                + "To index only values whose own properties conform, use "
                + "sh:node [ sh:property [ ... ] ] on the occurrence.");
        }

        Graph source = occurrenceRes.getModel().getGraph();
        Graph target = GraphFactory.createDefaultGraph();
        Node shapeNode = NodeFactory.createBlankNode();
        Set<Node> visited = new HashSet<>();
        for (Property constraint : VALUE_CONSTRAINTS) {
            for (Statement stmt : occurrenceRes.listProperties(constraint).toList()) {
                Triple triple = Triple.create(shapeNode, stmt.getPredicate().asNode(), stmt.getObject().asNode());
                copyTriple(source, target, triple, false, visited);
            }
        }
        if (target.isEmpty()) {
            return null;
        }
        check(source, target, owner);
        return ShaclShapeCondition.parse(target, shapeNode, owner);
    }

    /**
     * Add {@code triple} to {@code target} and copy what its object brings: the triples of
     * a blank node, of a named shape it references, and of the shapes in a shape list.
     */
    private static void copyTriple(Graph source, Graph target, Triple triple, boolean inShapeList,
                                   Set<Node> visited) {
        target.add(triple);
        Node predicate = triple.getPredicate();
        Node object = triple.getObject();
        boolean shapeList = SHAPE_LISTS.contains(predicate) || (inShapeList && RDF_REST.equals(predicate));
        boolean namedShape = object.isURI()
            && (SHAPE_REFERENCES.contains(predicate) || (inShapeList && RDF_FIRST.equals(predicate)));
        if ((object.isBlank() || namedShape) && visited.add(object)) {
            source.find(object, Node.ANY, Node.ANY).forEachRemaining(t ->
                copyTriple(source, target, t, shapeList, visited));
        }
    }

    /** Reject what a copied condition must not contain. */
    private static void check(Graph source, Graph target, String owner) {
        if (target.contains(Node.ANY, SHACL.sparql, Node.ANY)) {
            throw sparqlRejected(owner);
        }
        target.find().forEachRemaining(t -> {
            Node predicate = t.getPredicate();
            if (predicate.isURI() && predicate.getURI().startsWith(IndexVocab.NS)) {
                throw new TextIndexException(owner + " uses " + predicate.getLocalName() + " from the idx: "
                    + "namespace inside a SHACL constraint. idx:field is read only on a sh:property "
                    + "member of a scope or of a step's sh:node shape.");
            }
        });
        // A SPARQL-based constraint component is declared elsewhere in the configuration
        // and used through its parameter predicates. The condition's own graph does not
        // carry the declaration, so jena-shacl would ignore the parameter; reject it instead.
        source.find(Node.ANY, SHACL.parameter, Node.ANY).forEachRemaining(t -> {
            source.find(t.getObject(), SHACL.path, Node.ANY).forEachRemaining(p -> {
                if (target.contains(Node.ANY, p.getObject(), Node.ANY)) {
                    throw new TextIndexException(owner + " uses " + p.getObject()
                        + ", a parameter of a SPARQL-based constraint component. These run arbitrary "
                        + "queries during indexing and are not supported in text:shapes.");
                }
            });
        });
    }

    private static TextIndexException sparqlRejected(String owner) {
        return new TextIndexException(owner + " uses sh:sparql. SPARQL-based constraints run arbitrary "
            + "queries during indexing and are not supported in text:shapes.");
    }
}
