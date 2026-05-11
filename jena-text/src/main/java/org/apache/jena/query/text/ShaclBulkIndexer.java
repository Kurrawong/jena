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

import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.ShaclIndexMapping.*;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bulk indexer for SHACL entity-per-document mode.
 * <p>
 * Iterates over all entities in the dataset that match SHACL index profiles,
 * builds Lucene documents, and writes them to the index. Designed for use
 * after bulk-loading data with {@code tdb2.tdbloader}, which bypasses the
 * normal {@link ShaclTextDocProducer} change listener.
 * <p>
 * <b>Execution model:</b> Single-threaded throughout. TDB2's node table cache
 * uses a single shared lock, so concurrent readers serialize on it and
 * multi-threading produces no throughput gain over a single thread.
 * Commits fire periodically (every {@link #setBatchSize} entities) to bound
 * peak memory use and provide progress checkpoints.
 * <p>
 * Usage:
 * <pre>
 *   ShaclBulkIndexer indexer = new ShaclBulkIndexer(datasetGraph, textIndex, mapping);
 *   indexer.setFreshIndex(true);              // when loading into an empty index
 *   indexer.setRamBufferSizeMB(256);          // optional Lucene tune
 *   indexer.index();
 * </pre>
 */
public class ShaclBulkIndexer {
    private static final Logger log = LoggerFactory.getLogger(ShaclBulkIndexer.class);
    private static final NumberFormat COUNT_FORMAT = NumberFormat.getIntegerInstance(Locale.US);

    private static final Node RDF_TYPE = RDF.type.asNode();

    private final DatasetGraph baseDataset;
    private final ShaclTextIndexLucene textIndex;
    private final ShaclIndexMapping mapping;

    private final AtomicLong entityCount = new AtomicLong();
    private long progressInterval = 10000;
    private long maxEntitiesPerProfile = 0;
    private boolean freshIndex = false;
    private double ramBufferSizeMB = -1.0;
    private long batchSize = 100_000;

    public ShaclBulkIndexer(DatasetGraph baseDataset, TextIndex textIndex, ShaclIndexMapping mapping) {
        this.baseDataset = baseDataset;
        if (!(textIndex instanceof ShaclTextIndexLucene)) {
            throw new TextIndexException("ShaclBulkIndexer requires a ShaclTextIndexLucene instance");
        }
        this.textIndex = (ShaclTextIndexLucene) textIndex;
        this.mapping = mapping;
    }

    public void setProgressInterval(long progressInterval) {
        this.progressInterval = progressInterval;
    }

    public void setMaxEntitiesPerProfile(long maxEntitiesPerProfile) {
        this.maxEntitiesPerProfile = maxEntitiesPerProfile;
    }

    /**
     * Skip the pre-add {@code deleteDocuments()} call when writing each entity.
     * Set to {@code true} ONLY when loading into a known-empty index (e.g.
     * immediately after {@code tdb2.tdbloader}). Setting on a non-empty index
     * produces duplicates.
     */
    public void setFreshIndex(boolean freshIndex) {
        this.freshIndex = freshIndex;
    }

    /** Optionally raise the IndexWriter RAM buffer for the duration of bulk indexing. */
    public void setRamBufferSizeMB(double ramBufferSizeMB) {
        this.ramBufferSizeMB = ramBufferSizeMB;
    }

    /**
     * Number of entities between intermediate commits. After each batch a
     * {@code commit()} fires to flush buffered docs and reclaim RAM. Default 100k.
     * Set to {@code Long.MAX_VALUE} to issue a single commit at the very end.
     */
    public void setBatchSize(long batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        this.batchSize = batchSize;
    }

    public long getEntityCount() {
        return entityCount.get();
    }

    /**
     * Index all entities matching SHACL profiles.
     * <p>
     * Phase 1 (discovery): walks type triples in default + named graphs,
     * dedupes, and collects work items.
     * <br>
     * Phase 2 (indexing): processes each work item sequentially, committing
     * every {@link #setBatchSize} entities.
     */
    public void index() {
        entityCount.set(0);
        Double previousRamBuffer = null;
        if (ramBufferSizeMB > 0) {
            try {
                previousRamBuffer = textIndex.getIndexWriter().getConfig().getRAMBufferSizeMB();
                textIndex.getIndexWriter().getConfig().setRAMBufferSizeMB(ramBufferSizeMB);
                log.info("IndexWriter RAM buffer raised to {} MB for bulk indexing", ramBufferSizeMB);
            } catch (Exception e) {
                log.warn("Could not adjust RAM buffer size: {}", e.getMessage());
            }
        }

        boolean ownTxn = false;
        if (baseDataset.supportsTransactions() && !baseDataset.isInTransaction()) {
            baseDataset.begin(ReadWrite.READ);
            ownTxn = true;
        }

        try {
            List<WorkItem> items = discoverWorkItems();
            log.info("Discovered {} entities across {} profile(s)",
                formatCount(items.size()), mapping.getProfiles().size());
            processItems(items);
            log.info("Flushing and committing index...");
            textIndex.commit();
            log.info("Bulk indexing complete: {} entities indexed", formatCount(entityCount.get()));
        } finally {
            if (ownTxn) {
                baseDataset.end();
            }
            if (previousRamBuffer != null) {
                try {
                    textIndex.getIndexWriter().getConfig().setRAMBufferSizeMB(previousRamBuffer);
                } catch (Exception e) {
                    log.warn("Could not restore RAM buffer size: {}", e.getMessage());
                }
            }
        }
    }

    // ----- Phase 1: discovery -----

    /** Single-pass discovery — walks default + named graphs and emits dedup'd work items. */
    private List<WorkItem> discoverWorkItems() {
        List<WorkItem> items = new ArrayList<>();
        Set<String> indexed = new HashSet<>();
        Graph defaultGraph = baseDataset.getDefaultGraph();

        for (IndexProfile profile : mapping.getProfiles()) {
            for (Node targetClass : profile.getTargetClasses()) {
                long perProfile = 0;

                // Default graph discovery via type triples
                ExtendedIterator<Triple> typeTriples = defaultGraph.find(Node.ANY, RDF_TYPE, targetClass);
                try {
                    while (typeTriples.hasNext()) {
                        if (maxEntitiesPerProfile > 0 && perProfile >= maxEntitiesPerProfile) break;
                        Node subject = typeTriples.next().getSubject();
                        if (subject.isBlank()) {
                            log.warn("Skipping blank-node entity (cannot be indexed): {} for shape {}",
                                subject, profile.getShapeNode());
                            continue;
                        }
                        String entityUri = TextQueryFuncs.subjectToString(subject);
                        if (!indexed.add(entityUri + "|" + profile.getShapeNode())) continue;
                        items.add(new WorkItem(subject, entityUri, profile, GraphScope.DEFAULT));
                        perProfile++;
                    }
                } finally {
                    typeTriples.close();
                }

                // Named-graph discovery via quad iteration
                if (maxEntitiesPerProfile <= 0 || perProfile < maxEntitiesPerProfile) {
                    Iterator<Quad> quadIter = baseDataset.find(Node.ANY, Node.ANY, RDF_TYPE, targetClass);
                    while (quadIter.hasNext()) {
                        if (maxEntitiesPerProfile > 0 && perProfile >= maxEntitiesPerProfile) break;
                        Node subject = quadIter.next().getSubject();
                        if (subject.isBlank()) {
                            log.warn("Skipping blank-node entity (cannot be indexed): {} for shape {}",
                                subject, profile.getShapeNode());
                            continue;
                        }
                        String entityUri = TextQueryFuncs.subjectToString(subject);
                        if (!indexed.add(entityUri + "|" + profile.getShapeNode())) continue;
                        items.add(new WorkItem(subject, entityUri, profile, GraphScope.UNION));
                        perProfile++;
                    }
                }

                log.debug("Discovered {} entities for profile {} class {}",
                    formatCount(perProfile), profile.getShapeNode(), targetClass);
            }
        }
        return items;
    }

    // ----- Phase 2: indexing -----

    private void processItems(List<WorkItem> items) {
        Graph unionGraph = baseDataset.getUnionGraph();
        Graph defaultGraph = baseDataset.getDefaultGraph();
        for (WorkItem item : items) {
            Graph graph = (item.scope == GraphScope.UNION) ? unionGraph : defaultGraph;
            Entity entity = ShaclEntityBuilder.buildEntity(graph, item.subject, item.entityUri, item.profile);
            textIndex.updateEntityForProfile(entity, item.profile, freshIndex);
            long count = entityCount.incrementAndGet();
            maybeLogProgress(count);
            if (count % batchSize == 0) {
                log.info("  Committing at {} entities...", formatCount(count));
                textIndex.commit();
            }
        }
    }

    // ----- Helpers -----

    private void maybeLogProgress(long count) {
        if (progressInterval > 0 && count % progressInterval == 0) {
            log.info("  Indexed {} entities so far", formatCount(count));
        }
    }

    private static String formatCount(long count) {
        return COUNT_FORMAT.format(count);
    }

    private enum GraphScope { DEFAULT, UNION }

    private record WorkItem(Node subject, String entityUri, IndexProfile profile, GraphScope scope) {}
}
