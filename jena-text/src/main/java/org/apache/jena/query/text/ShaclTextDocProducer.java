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
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.ShaclIndexMapping.ProfileOccurrence;
import org.apache.jena.query.text.changes.TextQuadAction;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphUnionRead;
import org.apache.jena.sparql.path.eval.PathEval;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entity-per-document change listener for SHACL-driven index profiles.
 */
public class ShaclTextDocProducer implements TextDocProducer {
    private static final Logger log = LoggerFactory.getLogger(ShaclTextDocProducer.class);

    private static final Node RDF_TYPE = RDF.type.asNode();

    private final DatasetGraph baseDataset;
    private final ShaclTextIndexLucene indexer;
    private final ShaclIndexMapping mapping;

    private final ThreadLocal<Boolean> inTransaction = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public ShaclTextDocProducer(DatasetGraph baseDataset, TextIndex textIndex, ShaclIndexMapping mapping) {
        this.baseDataset = baseDataset;
        if (!(textIndex instanceof ShaclTextIndexLucene)) {
            throw new TextIndexException("ShaclTextDocProducer requires a ShaclTextIndexLucene instance");
        }
        this.indexer = (ShaclTextIndexLucene) textIndex;
        this.mapping = mapping;
    }

    @Override
    public void start() {
        inTransaction.set(true);
    }

    @Override
    public void finish() {
        inTransaction.set(false);
    }

    @Override
    public void reset() {}

    @Override
    public void change(TextQuadAction qaction, Node g, Node s, Node p, Node o) {
        if (qaction != TextQuadAction.ADD && qaction != TextQuadAction.DELETE) {
            return;
        }

        if (RDF_TYPE.equals(p)) {
            handleTypeChange(s, o);
        } else if (mapping.isRelevantPredicate(p)) {
            for (Node entity : findEntitiesForPredicateChange(s, p, o)) {
                rebuildEntityDocuments(entity);
            }
        }

        if (!inTransaction.get()) {
            indexer.commit();
        }
    }

    private void handleTypeChange(Node subject, Node typeNode) {
        Set<Node> entitiesToRebuild = new LinkedHashSet<>();
        if (!mapping.getProfilesForClass(typeNode).isEmpty()) {
            entitiesToRebuild.add(subject);
        }

        Graph graph = allGraphsView();
        for (ProfileOccurrence profileOccurrence : mapping.getOccurrencesRequiringClass(typeNode)) {
            Set<Node> scopeRoots = findScopeRootsForConstraintChange(graph, subject,
                profileOccurrence.getOccurrence());
            entitiesToRebuild.addAll(resolveEntityRoots(graph, profileOccurrence, scopeRoots));
        }

        for (Node entity : entitiesToRebuild) {
            rebuildEntityDocuments(entity);
        }
    }

    private Set<Node> findEntitiesForPredicateChange(Node changedSubject, Node changedPredicate, Node changedObject) {
        Graph graph = allGraphsView();
        Set<Node> entities = new LinkedHashSet<>();

        for (ProfileOccurrence profileOccurrence : mapping.getOccurrencesForPredicate(changedPredicate)) {
            Set<Node> scopeRoots = findScopeRootsForPredicateChange(
                profileOccurrence.getOccurrence(), changedPredicate, changedSubject, changedObject, graph);
            entities.addAll(resolveEntityRoots(graph, profileOccurrence, scopeRoots));
        }

        if (mapping.isNestedJoinPredicate(changedPredicate)) {
            for (IndexProfile profile : mapping.getProfiles()) {
                for (NestedDef nestedDef : profile.getNestedDefs()) {
                    if (nestedDef.getJoinPredicates().contains(changedPredicate)) {
                        entities.addAll(findParentsForJoinStepChange(
                            graph, nestedDef, changedPredicate, changedSubject, changedObject));
                    }
                }
            }
        }

        return entities;
    }

    private Set<Node> resolveEntityRoots(Graph graph, ProfileOccurrence profileOccurrence, Set<Node> scopeRoots) {
        if (scopeRoots.isEmpty()) {
            return Collections.emptySet();
        }
        if (!profileOccurrence.isNestedScoped()) {
            return scopeRoots;
        }
        return reverseTraverseToParents(graph, scopeRoots, profileOccurrence.getNestedDef().getJoinSteps());
    }

    private Set<Node> findScopeRootsForConstraintChange(Graph graph, Node endpoint, FieldOccurrence occurrence) {
        Set<Node> roots = new LinkedHashSet<>();
        Iterator<Node> iter = PathEval.evalReverse(graph, endpoint, occurrence.getPath(), null);
        while (iter.hasNext()) {
            roots.add(iter.next());
        }
        return roots;
    }

    private Set<Node> findScopeRootsForPredicateChange(FieldOccurrence occurrence, Node changedPredicate,
                                                       Node changedSubject, Node changedObject, Graph graph) {
        Set<Node> roots = new LinkedHashSet<>();
        for (List<JoinStep> variant : occurrence.getPathVariants()) {
            for (int i = 0; i < variant.size(); i++) {
                JoinStep step = variant.get(i);
                if (!step.getPredicate().equals(changedPredicate)) {
                    continue;
                }
                Node stepStart = step.isInverse() ? changedObject : changedSubject;
                if (stepStart == null) {
                    continue;
                }
                roots.addAll(reverseTraverseToParents(graph, Collections.singleton(stepStart), variant.subList(0, i)));
            }
        }
        return roots;
    }

    private Set<Node> findParentsForJoinStepChange(Graph graph, NestedDef nestedDef,
                                                   Node changedPredicate, Node changedSubject, Node changedObject) {
        Set<Node> parents = new LinkedHashSet<>();
        List<JoinStep> joinSteps = nestedDef.getJoinSteps();

        for (int i = 0; i < joinSteps.size(); i++) {
            JoinStep joinStep = joinSteps.get(i);
            if (!joinStep.getPredicate().equals(changedPredicate)) {
                continue;
            }

            Node stepStart = joinStep.isInverse() ? changedObject : changedSubject;
            if (stepStart == null) {
                continue;
            }

            parents.addAll(reverseTraverseToParents(graph, Collections.singleton(stepStart), joinSteps.subList(0, i)));
        }

        return parents;
    }

    private Set<Node> reverseTraverseToParents(Graph graph, Collection<Node> startNodes, List<JoinStep> steps) {
        Set<Node> current = new LinkedHashSet<>(startNodes);
        for (int i = steps.size() - 1; i >= 0 && !current.isEmpty(); i--) {
            JoinStep step = steps.get(i);
            Set<Node> next = new LinkedHashSet<>();
            for (Node node : current) {
                if (step.isInverse()) {
                    graph.find(node, step.getPredicate(), Node.ANY)
                        .forEachRemaining(t -> next.add(t.getObject()));
                } else {
                    graph.find(Node.ANY, step.getPredicate(), node)
                        .forEachRemaining(t -> next.add(t.getSubject()));
                }
            }
            current = next;
        }
        return current;
    }

    private void rebuildEntityDocuments(Node subject) {
        String entityUri = TextQueryFuncs.subjectToString(subject);
        log.trace("rebuildEntityDocuments: {}", entityUri);

        Set<Node> types = new HashSet<>();
        Iterator<Quad> typeIter = baseDataset.find(Node.ANY, subject, RDF_TYPE, Node.ANY);
        while (typeIter.hasNext()) {
            types.add(typeIter.next().getObject());
        }

        Set<IndexProfile> matchedProfiles = new LinkedHashSet<>();
        for (Node type : types) {
            matchedProfiles.addAll(mapping.getProfilesForClass(type));
        }

        if (matchedProfiles.isEmpty()) {
            indexer.deleteEntityByUri(entityUri);
            return;
        }

        if (subject.isBlank()) {
            List<String> shapes = new ArrayList<>();
            for (IndexProfile profile : matchedProfiles) {
                shapes.add(profile.getShapeNode().toString());
            }
            log.warn("Skipping blank-node entity (cannot be indexed): {} matched shapes {}", entityUri, shapes);
            return;
        }

        for (IndexProfile profile : matchedProfiles) {
            Entity entity = buildEntity(subject, entityUri, profile);
            indexer.updateEntityForProfile(entity, profile);
        }
    }

    private Entity buildEntity(Node subject, String entityUri, IndexProfile profile) {
        return ShaclEntityBuilder.buildEntity(allGraphsView(), subject, entityUri, profile);
    }

    private Graph allGraphsView() {
        List<Node> graphNames = new ArrayList<>();
        graphNames.add(Quad.defaultGraphIRI);
        baseDataset.listGraphNodes().forEachRemaining(graphNames::add);
        return new GraphUnionRead(baseDataset, graphNames);
    }
}
