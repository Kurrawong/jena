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
import org.apache.jena.query.text.ShaclIndexMapping.*;
import org.apache.jena.query.text.changes.TextQuadAction;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphUnionRead;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entity-per-document change listener for SHACL-driven index profiles.
 * <p>
 * When a relevant triple changes, this producer rebuilds the entire Lucene document
 * for the affected entity by reading all triples from the base dataset.
 * <p>
 * The base dataset is already updated when {@code change()} fires because
 * {@code DatasetGraphTextMonitor.add()} calls {@code super.add()} before {@code record()}.
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
        if (qaction != TextQuadAction.ADD && qaction != TextQuadAction.DELETE)
            return;

        if (RDF_TYPE.equals(p)) {
            // rdf:type change → may add/remove entity from a profile
            handleTypeChange(qaction, s, o);
        } else if (mapping.isRelevantPredicate(p)) {
            Set<Node> entitiesToRebuild = new LinkedHashSet<>();
            if (mapping.isTopLevelPredicate(p)) {
                entitiesToRebuild.add(s);
            }
            if (mapping.isNestedChildPredicate(p) || mapping.isNestedJoinPredicate(p)) {
                entitiesToRebuild.addAll(findParentEntitiesForNestedChange(s, p, o));
            }
            for (Node entity : entitiesToRebuild) {
                rebuildEntityDocuments(entity);
            }
        }
        // Else: irrelevant predicate, ignore

        if (!inTransaction.get()) {
            indexer.commit();
        }
    }

    private void handleTypeChange(TextQuadAction qaction, Node subject, Node typeNode) {
        List<IndexProfile> profiles = mapping.getProfilesForClass(typeNode);
        if (profiles.isEmpty()) {
            return; // Not a type we care about
        }

        if (qaction == TextQuadAction.ADD) {
            // New type assertion → rebuild docs for matching profiles
            rebuildEntityDocuments(subject);
        } else if (qaction == TextQuadAction.DELETE) {
            // Type removed — check if entity still has this type in the dataset
            // If not, delete the corresponding profile document(s)
            rebuildEntityDocuments(subject);
        }
    }

    /**
     * Rebuild all Lucene documents for the given entity by reading its current state
     * from the base dataset.
     */
    private void rebuildEntityDocuments(Node subject) {
        String entityUri = TextQueryFuncs.subjectToString(subject);
        log.trace("rebuildEntityDocuments: {}", entityUri);

        // Get rdf:type values for the entity across all graphs (default + named)
        Set<Node> types = new HashSet<>();
        Iterator<Quad> typeIter = baseDataset.find(Node.ANY, subject, RDF_TYPE, Node.ANY);
        while (typeIter.hasNext()) {
            types.add(typeIter.next().getObject());
        }

        // Find matching profiles
        Set<IndexProfile> matchedProfiles = new LinkedHashSet<>();
        for (Node type : types) {
            matchedProfiles.addAll(mapping.getProfilesForClass(type));
        }

        if (matchedProfiles.isEmpty()) {
            // No matching profiles — delete any existing docs for this entity
            indexer.deleteEntityByUri(entityUri);
            return;
        }

        // For each matching profile, build an Entity and update the index
        for (IndexProfile profile : matchedProfiles) {
            Entity entity = buildEntity(subject, entityUri, profile);
            indexer.updateEntityForProfile(entity, profile);
        }
    }

    /**
     * Build an Entity by reading all relevant triples for the subject from the base dataset.
     * Uses PathEval for complex paths (sequence, inverse); direct triple match for simple predicates.
     */
    private Entity buildEntity(Node subject, String entityUri, IndexProfile profile) {
        return ShaclEntityBuilder.buildEntity(allGraphsView(), subject, entityUri, profile);
    }

    /**
     * Return a read-only graph view that spans the default graph and all named graphs.
     * This ensures the change listener finds data regardless of which graph it was added to.
     */
    private Graph allGraphsView() {
        List<Node> graphNames = new ArrayList<>();
        graphNames.add(Quad.defaultGraphIRI);
        baseDataset.listGraphNodes().forEachRemaining(graphNames::add);
        return new GraphUnionRead(baseDataset, graphNames);
    }

    private Set<Node> findParentEntitiesForNestedChange(Node changedSubject, Node changedPredicate, Node changedObject) {
        Graph graph = allGraphsView();
        Set<Node> parents = new LinkedHashSet<>();
        for (IndexProfile profile : mapping.getProfiles()) {
            for (NestedDef nestedDef : profile.getNestedDefs()) {
                boolean nestedChildPredicate = nestedDef.getFields().stream()
                    .anyMatch(f -> f.getPredicates().contains(changedPredicate));
                boolean nestedJoinPredicate = nestedDef.getJoinPredicates().contains(changedPredicate);

                if (nestedChildPredicate) {
                    parents.addAll(reverseTraverseToParents(graph, Collections.singleton(changedSubject),
                        nestedDef.getJoinSteps()));
                }
                if (nestedJoinPredicate) {
                    parents.addAll(findParentsForJoinStepChange(graph, nestedDef, changedPredicate, changedSubject, changedObject));
                }
            }
        }

        return parents;
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

            parents.addAll(reverseTraverseToParents(graph, Collections.singleton(stepStart),
                joinSteps.subList(0, i)));
        }

        return parents;
    }

    private Set<Node> reverseTraverseToParents(Graph graph, Collection<Node> startNodes, List<JoinStep> joinSteps) {
        Set<Node> current = new LinkedHashSet<>(startNodes);
        for (int i = joinSteps.size() - 1; i >= 0 && !current.isEmpty(); i--) {
            JoinStep joinStep = joinSteps.get(i);
            Set<Node> next = new LinkedHashSet<>();
            for (Node node : current) {
                if (joinStep.isInverse()) {
                    graph.find(node, joinStep.getPredicate(), Node.ANY)
                        .forEachRemaining(t -> next.add(t.getObject()));
                } else {
                    graph.find(Node.ANY, joinStep.getPredicate(), node)
                        .forEachRemaining(t -> next.add(t.getSubject()));
                }
            }
            current = next;
        }
        return current;
    }
}
