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
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.query.text.ShaclIndexMapping.NodeKindConstraint;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.engine.ValidationContext;
import org.apache.jena.shacl.engine.constraint.*;
import org.apache.jena.shacl.parser.Constraint;
import org.apache.jena.shacl.parser.PropertyShape;
import org.apache.jena.shacl.parser.ShaclParseException;
import org.apache.jena.shacl.parser.Shape;
import org.apache.jena.shacl.validation.VLib;
import org.apache.jena.shacl.vocabulary.SHACL;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.sparql.path.*;
import org.apache.jena.vocabulary.RDF;

/**
 * A SHACL node shape that the index applies to one node at a time, evaluated by
 * jena-shacl: the filter of an {@code sh:node} step, or the value constraints of a field
 * occurrence.
 * <p>
 * The index asks only whether a node conforms. It never produces a validation report.
 * Conformance is tested with {@link VLib#validateShape} on a fresh
 * {@link ValidationContext}: the node conforms when the context records no report entry.
 * Those two classes are public but not jena-shacl's documented API, so an upstream change
 * to them arrives with an upstream sync; {@code TestShaclOccurrenceValueConstraints} and
 * {@code TestShaclNodeOccurrenceFilters} catch a break.
 * <p>
 * The condition also lists the paths, relative to the checked node, whose triples decide
 * the outcome, so that the change listener can rebuild an entity when one of them changes.
 */
public final class ShaclShapeCondition {

    private static final Node RDF_TYPE = RDF.type.asNode();

    private final Shapes shapes;
    private final Shape shape;
    private final String canonical;
    private final List<List<JoinStep>> trackedPaths;

    private ShaclShapeCondition(Shapes shapes, Shape shape, String canonical,
                                List<List<JoinStep>> trackedPaths) {
        this.shapes = shapes;
        this.shape = shape;
        this.canonical = canonical;
        this.trackedPaths = trackedPaths;
    }

    /**
     * Parse the node shape at {@code shapeNode} in {@code shapesGraph}. The graph holds
     * only the triples of this condition; it is retained, and must not be changed after
     * this call.
     *
     * @param owner names the configuration resource in error messages
     */
    public static ShaclShapeCondition parse(Graph shapesGraph, Node shapeNode, String owner) {
        // Declared, so that Shapes.parse includes a shape that has no target.
        shapesGraph.add(Triple.create(shapeNode, RDF_TYPE, SHACL.NodeShape));
        String canonical = render(shapesGraph, shapeNode);
        Shapes shapes;
        try {
            shapes = Shapes.parse(shapesGraph);
        } catch (ShaclParseException ex) {
            throw new TextIndexException("Invalid SHACL on " + owner + ": " + ex.getMessage(), ex);
        }
        Shape shape = shapes.getShape(shapeNode);
        if (shape == null) {
            throw new TextIndexException("Invalid SHACL on " + owner + ": no shape parsed from " + shapeNode);
        }
        List<List<JoinStep>> tracked = new ArrayList<>();
        collectShape(shape, List.of(List.of()), tracked, owner, Collections.newSetFromMap(new IdentityHashMap<>()));
        return new ShaclShapeCondition(shapes, shape, canonical, deduplicate(tracked));
    }

    /**
     * The condition for an occurrence built in code with the three constraints the
     * {@link ShaclIndexMapping.FieldOccurrence} constructors take, or null when all three
     * are null.
     */
    public static ShaclShapeCondition ofConstraints(Node requiredClass, NodeKindConstraint nodeKind,
                                                    Node datatype) {
        if (requiredClass == null && nodeKind == null && datatype == null) {
            return null;
        }
        Graph graph = GraphFactory.createDefaultGraph();
        Node shapeNode = NodeFactory.createBlankNode();
        if (requiredClass != null) {
            graph.add(Triple.create(shapeNode, SHACL.class_, requiredClass));
        }
        if (nodeKind != null) {
            graph.add(Triple.create(shapeNode, SHACL.nodeKind, nodeKindNode(nodeKind)));
        }
        if (datatype != null) {
            graph.add(Triple.create(shapeNode, SHACL.datatype, datatype));
        }
        return parse(graph, shapeNode, "field occurrence");
    }

    private static Node nodeKindNode(NodeKindConstraint nodeKind) {
        return switch (nodeKind) {
            case IRI -> SHACL.IRI;
            case BLANK_NODE -> SHACL.BlankNode;
            case LITERAL -> SHACL.Literal;
            case BLANK_NODE_OR_IRI -> SHACL.BlankNodeOrIRI;
            case BLANK_NODE_OR_LITERAL -> SHACL.BlankNodeOrLiteral;
            case IRI_OR_LITERAL -> SHACL.IRIOrLiteral;
        };
    }

    /** True when {@code node} conforms to this shape in {@code data}. */
    public boolean conforms(Graph data, Node node) {
        ValidationContext vCxt = ValidationContext.create(shapes, data);
        VLib.validateShape(vCxt, data, shape, node);
        return !vCxt.hasViolation();
    }

    /**
     * Paths from the checked node whose triples can change the outcome, as predicate
     * steps. A path ending in {@code rdf:type} comes from {@code sh:class}.
     */
    public List<List<JoinStep>> getTrackedPaths() {
        return trackedPaths;
    }

    /** A rendering of the shape's triples that does not depend on blank node labels. */
    public String getCanonical() {
        return canonical;
    }

    @Override
    public String toString() {
        return canonical;
    }

    // ---- Change tracking: the paths a shape reads.

    private static void collectShape(Shape shape, List<List<JoinStep>> focus, List<List<JoinStep>> tracked,
                                     String owner, Set<Shape> active) {
        if (!active.add(shape)) {
            return;
        }
        List<List<JoinStep>> value = focus;
        if (shape instanceof PropertyShape propertyShape) {
            value = ShaclIndexMapping.concatVariants(focus, pathVariants(propertyShape.getPath(), owner));
            tracked.addAll(value);
        }
        for (Constraint constraint : shape.getConstraints()) {
            collectConstraint(constraint, focus, value, tracked, owner, active);
        }
        for (PropertyShape propertyShape : shape.getPropertyShapes()) {
            collectShape(propertyShape, value, tracked, owner, active);
        }
        active.remove(shape);
    }

    private static void collectConstraint(Constraint constraint, List<List<JoinStep>> focus,
                                          List<List<JoinStep>> value, List<List<JoinStep>> tracked,
                                          String owner, Set<Shape> active) {
        if (constraint instanceof SparqlConstraint || constraint instanceof ConstraintComponentSPARQL) {
            throw new TextIndexException(owner + " uses sh:sparql or a SPARQL-based constraint component. "
                + "These run arbitrary queries during indexing and are not supported in text:shapes.");
        }
        if (constraint instanceof ClosedConstraint) {
            throw new TextIndexException(owner + " uses sh:closed. A closed shape depends on every "
                + "predicate of a node, which the index cannot track for changes.");
        }
        if (constraint instanceof ClassConstraint) {
            tracked.addAll(ShaclIndexMapping.concatVariants(value, List.of(List.of(new JoinStep(RDF_TYPE, false)))));
        } else if (constraint instanceof ConstraintOp1 op1) {
            collectShape(op1.getOther(), value, tracked, owner, active);
        } else if (constraint instanceof ConstraintOpN opN) {
            for (Shape other : opN.getOthers()) {
                collectShape(other, value, tracked, owner, active);
            }
        } else if (constraint instanceof QualifiedValueShape qualified) {
            collectShape(qualified.getSub(), value, tracked, owner, active);
        } else if (constraint instanceof ConstraintPairwise pairwise) {
            tracked.addAll(ShaclIndexMapping.concatVariants(focus,
                List.of(List.of(new JoinStep(pairwise.getValue(), false)))));
        }
    }

    /** Predicate-step variants of a SHACL path, rejecting the forms the index cannot track. */
    static List<List<JoinStep>> pathVariants(Path path, String owner) {
        if (path instanceof P_Link link) {
            return List.of(List.of(new JoinStep(link.getNode(), false)));
        }
        if (path instanceof P_Inverse inverse) {
            List<List<JoinStep>> inverted = new ArrayList<>();
            for (List<JoinStep> variant : pathVariants(inverse.getSubPath(), owner)) {
                List<JoinStep> copy = new ArrayList<>(variant.size());
                for (int i = variant.size() - 1; i >= 0; i--) {
                    JoinStep step = variant.get(i);
                    copy.add(new JoinStep(step.getPredicate(), !step.isInverse()));
                }
                inverted.add(List.copyOf(copy));
            }
            return inverted;
        }
        if (path instanceof P_Seq seq) {
            return ShaclIndexMapping.concatVariants(pathVariants(seq.getLeft(), owner),
                pathVariants(seq.getRight(), owner));
        }
        if (path instanceof P_Alt alt) {
            List<List<JoinStep>> variants = new ArrayList<>(pathVariants(alt.getLeft(), owner));
            variants.addAll(pathVariants(alt.getRight(), owner));
            return variants;
        }
        throw new TextIndexException("Unsupported SHACL path " + path + " in " + owner
            + ". Only predicate, alternative, inverse, and sequence paths are supported.");
    }

    private static List<List<JoinStep>> deduplicate(List<List<JoinStep>> tracked) {
        Map<String, List<JoinStep>> unique = new LinkedHashMap<>();
        for (List<JoinStep> variant : tracked) {
            if (!variant.isEmpty()) {
                unique.putIfAbsent(variant.toString(), List.copyOf(variant));
            }
        }
        return List.copyOf(unique.values());
    }

    // ---- Canonical rendering, for the configuration fingerprint.

    private static String render(Graph graph, Node node) {
        return render(graph, node, new HashSet<>());
    }

    private static String render(Graph graph, Node node, Set<Node> active) {
        if (!node.isBlank()) {
            String term = term(node);
            if (!node.isURI() || !graph.contains(node, Node.ANY, Node.ANY) || active.contains(node)) {
                return term;
            }
            // A named shape referenced from this one: its triples are part of the condition.
            active.add(node);
            String body = renderBody(graph, node, active);
            active.remove(node);
            return term + body;
        }
        if (!active.add(node)) {
            return "_:cycle";
        }
        String body = renderBody(graph, node, active);
        active.remove(node);
        return body;
    }

    private static String renderBody(Graph graph, Node node, Set<Node> active) {
        List<String> parts = new ArrayList<>();
        graph.find(node, Node.ANY, Node.ANY).forEachRemaining(t ->
            parts.add(term(t.getPredicate()) + " " + render(graph, t.getObject(), active)));
        Collections.sort(parts);
        return "[" + String.join(" ; ", parts) + "]";
    }

    private static String term(Node n) {
        if (n.isBlank()) {
            return "_:";
        }
        if (n.isURI()) {
            return "<" + n.getURI() + ">";
        }
        if (n.isLiteral()) {
            String lex = "\"" + n.getLiteralLexicalForm() + "\"";
            String lang = n.getLiteralLanguage();
            if (lang != null && !lang.isEmpty()) {
                return lex + "@" + lang;
            }
            String dt = n.getLiteralDatatypeURI();
            return dt == null ? lex : lex + "^^<" + dt + ">";
        }
        return n.toString();
    }
}
