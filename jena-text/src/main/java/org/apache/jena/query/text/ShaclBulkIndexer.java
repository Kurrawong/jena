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
import java.util.concurrent.*;
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
 * <b>Execution model:</b> Single-threaded discovery walks the dataset and
 * collects {@code (subject, profile)} pairs (TDB2 iterators do not
 * thread-share). The collected pairs are then processed by a fixed thread
 * pool, each worker opening its own read transaction and calling
 * {@code buildEntity()} + {@code updateEntityForProfile()} per item.
 * A single {@code commit()} fires after all workers complete.
 * <p>
 * Set {@link #setThreadCount(int)} to {@code 1} to fall back to the
 * pre-parallel single-threaded path (useful for debugging).
 * <p>
 * Usage:
 * <pre>
 *   ShaclBulkIndexer indexer = new ShaclBulkIndexer(datasetGraph, textIndex, mapping);
 *   indexer.setFreshIndex(true);              // when loading into an empty index
 *   indexer.setThreadCount(8);                // optional, defaults to availableProcessors()
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
    private int threadCount = Math.max(1, Runtime.getRuntime().availableProcessors());
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

    /** Number of worker threads (default: {@code availableProcessors()}). Setting 1 disables parallelism. */
    public void setThreadCount(int threadCount) {
        if (threadCount < 1) {
            throw new IllegalArgumentException("threadCount must be >= 1");
        }
        this.threadCount = threadCount;
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
     * Number of work items processed per parallel batch. After each batch the
     * pool drains and a single {@code commit()} fires before the next batch
     * starts. Default 100k. Set to {@code Long.MAX_VALUE} to issue a single
     * commit at the very end.
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
     * Phase 1 (single-threaded): walk type triples in default + named graphs,
     * dedupe, collect work items.
     * <br>
     * Phase 2 (parallel): each worker opens its own read transaction and
     * calls {@code buildEntity()} + {@code updateEntityForProfile()} for every
     * item assigned to it. Exceptions surface via {@code Future.get()}.
     * <br>
     * Phase 3: single {@code commit()} at the end (or one per batch when
     * batches are bounded by {@link #setBatchSize}).
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

        // Discovery uses TDB2 iterators on this thread, which require a read transaction.
        // Honour any caller-managed transaction; self-manage otherwise.
        boolean ownTxn = false;
        if (baseDataset.supportsTransactions() && !baseDataset.isInTransaction()) {
            baseDataset.begin(ReadWrite.READ);
            ownTxn = true;
        }

        try {
            List<WorkItem> items = discoverWorkItems();
            log.info("Discovered {} entities across {} profile(s); processing with {} thread(s)",
                formatCount(items.size()), mapping.getProfiles().size(), threadCount);

            if (threadCount == 1) {
                processSingleThreaded(items);
            } else {
                processParallel(items);
            }

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

    // ----- Phase 2a: single-threaded fallback -----

    private void processSingleThreaded(List<WorkItem> items) {
        Graph unionGraph = baseDataset.getUnionGraph();
        Graph defaultGraph = baseDataset.getDefaultGraph();
        for (WorkItem item : items) {
            Graph graph = (item.scope == GraphScope.UNION) ? unionGraph : defaultGraph;
            Entity entity = ShaclEntityBuilder.buildEntity(graph, item.subject, item.entityUri, item.profile);
            textIndex.updateEntityForProfile(entity, item.profile, freshIndex);
            long count = entityCount.incrementAndGet();
            maybeLogProgress(count);
        }
    }

    // ----- Phase 2b: parallel workers -----

    private void processParallel(List<WorkItem> items) {
        if (items.isEmpty()) return;

        // Process in bounded batches so we can commit and reclaim RAM periodically.
        int processed = 0;
        while (processed < items.size()) {
            int end = (int) Math.min((long) processed + batchSize, items.size());
            List<WorkItem> batch = items.subList(processed, end);
            runBatch(batch);
            // Single commit between batches (and once more at the end of index()).
            textIndex.commit();
            processed = end;
            log.info("  Committed at {} entities", formatCount(entityCount.get()));
        }
    }

    private void runBatch(List<WorkItem> batch) {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "shacl-bulk-worker");
            t.setDaemon(true);
            return t;
        });

        // Round-robin partition keeps batches balanced when items vary in cost.
        @SuppressWarnings("unchecked")
        List<WorkItem>[] partitions = new List[threadCount];
        for (int i = 0; i < threadCount; i++) partitions[i] = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) partitions[i % threadCount].add(batch.get(i));

        List<Future<?>> futures = new ArrayList<>(threadCount);
        try {
            for (List<WorkItem> partition : partitions) {
                if (partition.isEmpty()) continue;
                futures.add(pool.submit(() -> processPartition(partition)));
            }
            // Surface the FIRST worker exception, after all in-flight workers settle.
            ExecutionException firstFailure = null;
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    if (firstFailure == null) firstFailure = e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TextIndexException("Bulk indexing interrupted", e);
                }
            }
            if (firstFailure != null) {
                Throwable cause = firstFailure.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new TextIndexException("Bulk indexing worker failed", cause);
            }
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void processPartition(List<WorkItem> partition) {
        boolean txStarted = false;
        if (baseDataset.supportsTransactions()) {
            try {
                baseDataset.begin(ReadWrite.READ);
                txStarted = true;
            } catch (Exception e) {
                // If begin() fails (e.g. already in a transaction owned by this thread),
                // proceed without our own transaction.
                log.debug("Worker could not begin its own read transaction: {}", e.getMessage());
            }
        }
        try {
            Graph unionGraph = baseDataset.getUnionGraph();
            Graph defaultGraph = baseDataset.getDefaultGraph();
            for (WorkItem item : partition) {
                Graph graph = (item.scope == GraphScope.UNION) ? unionGraph : defaultGraph;
                Entity entity = ShaclEntityBuilder.buildEntity(graph, item.subject, item.entityUri, item.profile);
                textIndex.updateEntityForProfile(entity, item.profile, freshIndex);
                long count = entityCount.incrementAndGet();
                maybeLogProgress(count);
            }
        } finally {
            if (txStarted) {
                baseDataset.end();
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
