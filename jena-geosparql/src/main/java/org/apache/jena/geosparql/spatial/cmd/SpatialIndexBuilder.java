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

package org.apache.jena.geosparql.spatial.cmd;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.jena.atlas.logging.LogCtlJUL;
import org.apache.jena.cmd.ArgDecl;
import org.apache.jena.cmd.CmdException;
import org.apache.jena.geosparql.InitGeoSPARQL;
import org.apache.jena.geosparql.configuration.GeoSPARQLOperations;
import org.apache.jena.geosparql.spatial.SpatialIndexException;
import org.apache.jena.geosparql.spatial.index.v2.SpatialIndexIoKryo;
import org.apache.jena.geosparql.spatial.index.v2.SpatialIndexLib;
import org.apache.jena.geosparql.spatial.index.v2.SpatialIndexPerGraph;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.TDB2Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import arq.cmdline.CmdARQ;

/**
 * CLI tool that builds a spatial index for a TDB2 dataset.
 * <p>
 * Use this after loading data with {@code tdb2.tdbloader}, which bypasses
 * the normal spatial indexing.
 * <p>
 * Usage: {@code spatialindexbuilder --loc=DATASET_PATH [--srs=SRS_URI] [--verbose]}
 */
public class SpatialIndexBuilder extends CmdARQ {

    private static Logger log = LoggerFactory.getLogger(SpatialIndexBuilder.class);

    private static final ArgDecl argLoc = new ArgDecl(ArgDecl.HasValue, "loc");
    private static final ArgDecl argSrs = new ArgDecl(ArgDecl.HasValue, "srs");
    private static final ArgDecl argOutput = new ArgDecl(ArgDecl.HasValue, "output");

    private String location = null;
    private String srsURI = null;
    private Path outputPath = null;

    static public void main(String... argv) {
        LogCtlJUL.routeJULtoSLF4J();
        InitGeoSPARQL.init();  // Ensure GeoSPARQL is initialized
        new SpatialIndexBuilder(argv).mainRun();
    }

    static public void testMain(String... argv) {
        new SpatialIndexBuilder(argv).mainMethod();
    }

    protected SpatialIndexBuilder(String[] argv) {
        super(argv);
        super.add(argLoc, "--loc=", "Location of TDB2 dataset");
        super.add(argSrs, "--srs=", "Spatial Reference System URI (optional)");
        super.add(argOutput, "--output=", "Output file path for spatial index (optional)");
        // Note: --verbose is provided by the base CmdARQ class, no need to add it here
    }

    @Override
    protected void processModulesAndArgs() {
        super.processModulesAndArgs();

        if (!super.contains(argLoc)) {
            throw new CmdException("No dataset location specified. Use --loc=PATH");
        }
        location = getValue(argLoc);

        if (super.contains(argSrs)) {
            srsURI = getValue(argSrs);
        }
        
        if (super.contains(argOutput)) {
            outputPath = Paths.get(getValue(argOutput));
        }
        
        // Verbose logging is handled by the base class and logging configuration
        // The --verbose flag is automatically available from CmdARQ
    }

    @Override
    protected String getSummary() {
        return getCommandName() + " --loc=DATASET_PATH [--srs=SRS_URI] [--output=OUTPUT_PATH] [--verbose]";
    }

    @Override
    protected void exec() {
        try {
            // Load the TDB2 dataset
            Dataset dataset = TDB2Factory.connectDataset(location);
            
            try {
                // Auto-detect SRS if not provided
                if (srsURI == null) {
                    srsURI = GeoSPARQLOperations.findModeSRS(dataset);
                }

                log.info("Starting spatial index build...");
                log.info("  Dataset: {}", location);
                log.info("  SRS: {}", srsURI);
                if (outputPath != null) {
                    log.info("  Output: {}", outputPath.toAbsolutePath());
                }

                long startTime = System.currentTimeMillis();

                // Build the spatial index
                // Note: SpatialIndexLib.buildSpatialIndex handles its own transactions via AutoTxn
                SpatialIndexPerGraph spatialIndex = SpatialIndexLib.buildSpatialIndex(dataset.asDatasetGraph(), srsURI);

                long elapsed = System.currentTimeMillis() - startTime;
                long seconds = Math.max(elapsed / 1000, 1);
                log.info("Spatial index build complete in {} seconds", seconds);

                // Save the spatial index to disk if output path is specified
                if (outputPath != null) {
                    log.info("Writing spatial index to disk...");
                    spatialIndex.setLocation(outputPath);
                    SpatialIndexIoKryo.save(outputPath, spatialIndex);
                    log.info("Spatial index written to: {}", outputPath.toAbsolutePath());
                }

                // No transaction management needed here - SpatialIndexLib handles it internally
            } finally {
                dataset.close();
            }
        } catch (SpatialIndexException e) {
            throw new CmdException("Failed to build spatial index: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new CmdException("Error building spatial index: " + e.getMessage(), e);
        }
    }
}