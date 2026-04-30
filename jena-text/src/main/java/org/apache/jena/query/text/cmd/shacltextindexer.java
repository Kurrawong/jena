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

package org.apache.jena.query.text.cmd;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.jena.atlas.logging.LogCtlJUL;
import org.apache.jena.cmd.ArgDecl;
import org.apache.jena.cmd.CmdException;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import arq.cmdline.CmdARQ;

/**
 * CLI tool that bulk-indexes a TDB2 dataset into a Lucene text index
 * using SHACL entity-per-document profiles.
 * <p>
 * Use this after loading data with {@code tdb2.tdbloader}, which bypasses
 * the normal change listener.
 * <p>
 * Usage: {@code shacltextindexer --desc=assembler.ttl}
 */
public class shacltextindexer extends CmdARQ {

    private static Logger log = LoggerFactory.getLogger(shacltextindexer.class);
    private static final String ENV_INDEX_FIRST_N = "SHACL_INDEX_FIRST_N";

    public static final ArgDecl assemblerDescDecl = new ArgDecl(ArgDecl.HasValue, "desc", "dataset");

    protected DatasetGraphText dataset = null;
    /** Ordered map of (registry id -> SHACL index). Always contains at least one entry. */
    protected Map<String, ShaclTextIndexLucene> shaclIndexes = new LinkedHashMap<>();

    static public void main(String... argv) {
        LogCtlJUL.routeJULtoSLF4J();
        new shacltextindexer(argv).mainRun();
    }

    static public void testMain(String... argv) {
        new shacltextindexer(argv).mainMethod();
    }

    protected shacltextindexer(String[] argv) {
        super(argv);
        super.add(assemblerDescDecl, "--desc=", "Assembler description file");
    }

    @Override
    protected void processModulesAndArgs() {
        super.processModulesAndArgs();
        String file;

        if (!super.contains(assemblerDescDecl) && getNumPositional() == 0)
            throw new CmdException("No assembler description given");

        if (super.contains(assemblerDescDecl)) {
            if (getValues(assemblerDescDecl).size() != 1)
                throw new CmdException("Multiple assembler descriptions given via --desc");
            if (getPositional().size() != 0)
                throw new CmdException("Additional assembler descriptions given");
            file = getValue(assemblerDescDecl);
        } else {
            if (getNumPositional() != 1)
                throw new CmdException("Multiple assembler descriptions given as positional arguments");
            file = getPositionalArg(0);
        }

        if (file == null)
            throw new CmdException("No dataset specified");

        Dataset ds = TextDatasetFactory.create(file);
        if (ds == null)
            throw new CmdException("No dataset description found");

        dataset = (DatasetGraphText)(ds.asDatasetGraph());

        // Prefer the TextIndexRegistry stored in the dataset context (multi-index aware).
        // Fall back to the single-index path for backwards compatibility.
        Object regObj = dataset.getContext().get(TextQuery.textIndexRegistry);
        if (regObj instanceof TextIndexRegistry registry) {
            for (Map.Entry<String, TextIndexLucene> entry : registry.allWithIds().entrySet()) {
                if (entry.getValue() instanceof ShaclTextIndexLucene shaclIdx) {
                    shaclIndexes.put(entry.getKey(), shaclIdx);
                }
            }
            if (shaclIndexes.isEmpty()) {
                throw new CmdException("No SHACL Lucene indexes registered. " +
                    "Use 'textindexer' for classic triple-per-document indexes.");
            }
        } else {
            TextIndex idx = dataset.getTextIndex();
            if (idx == null)
                throw new CmdException("Dataset has no text index");
            if (!(idx instanceof ShaclTextIndexLucene shaclIdx))
                throw new CmdException("Text index is not a SHACL Lucene index. " +
                    "Use 'textindexer' for classic triple-per-document indexes.");
            shaclIndexes.put(TextIndexRegistry.DEFAULT_ID, shaclIdx);
        }
    }

    @Override
    protected String getSummary() {
        return getCommandName() + " --desc=assemblerFile";
    }

    @Override
    protected void exec() {
        try {
            if (dataset.supportsTransactions()) {
                dataset.begin(ReadWrite.READ);
            }

            log.info("Starting SHACL bulk indexing across {} index(es)", shaclIndexes.size());
            String firstN = System.getenv(ENV_INDEX_FIRST_N);
            long maxEntitiesPerProfile = -1;
            if (firstN != null && !firstN.isBlank()) {
                long parsed = Long.parseLong(firstN.trim());
                if (parsed > 0) {
                    maxEntitiesPerProfile = parsed;
                    log.info("Dev mode: indexing first {} entities per SHACL profile from ${}",
                        maxEntitiesPerProfile, ENV_INDEX_FIRST_N);
                }
            }

            long startTime = System.currentTimeMillis();
            long totalEntities = 0;

            for (Map.Entry<String, ShaclTextIndexLucene> entry : shaclIndexes.entrySet()) {
                String id = entry.getKey();
                ShaclTextIndexLucene shaclIdx = entry.getValue();
                ShaclIndexMapping mapping = shaclIdx.getShaclMapping();

                log.info("Indexing index '{}' ({} profile(s))", id, mapping.getProfiles().size());
                for (ShaclIndexMapping.IndexProfile profile : mapping.getProfiles()) {
                    log.info("    {} -> {}", profile.getShapeNode(), profile.getTargetClasses());
                }

                ShaclBulkIndexer indexer = new ShaclBulkIndexer(dataset, shaclIdx, mapping);
                if (maxEntitiesPerProfile > 0) {
                    indexer.setMaxEntitiesPerProfile(maxEntitiesPerProfile);
                }
                indexer.index();
                totalEntities += indexer.getEntityCount();

                shaclIdx.close();
                log.info("  Index '{}' complete: {} entities", id, indexer.getEntityCount());
            }

            long elapsed = System.currentTimeMillis() - startTime;
            long seconds = Math.max(elapsed / 1000, 1);
            log.info("All indexes complete: {} entities total in {} seconds ({} per second)",
                totalEntities, seconds, totalEntities / seconds);

            if (dataset.supportsTransactions()) {
                dataset.commit();
            }
            dataset.close();
        } finally {
            if (dataset.supportsTransactions()) {
                dataset.end();
            }
        }
    }
}
