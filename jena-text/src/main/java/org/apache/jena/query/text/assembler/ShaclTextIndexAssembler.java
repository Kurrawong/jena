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

import static org.apache.jena.query.text.assembler.TextVocab.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.Mode;
import org.apache.jena.assembler.assemblers.AssemblerBase;
import org.apache.jena.atlas.io.IO;
import org.apache.jena.atlas.lib.IRILib;
import org.apache.jena.query.text.*;
import org.apache.jena.query.text.embedding.EmbeddingConfig;
import org.apache.jena.query.text.embedding.EmbeddingProvider;
import org.apache.jena.query.text.embedding.EmbeddingProviders;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.sparql.util.graph.GraphUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembler for SHACL-mode text indexes ({@code text:TextIndexShacl}).
 * <p>
 * Config format:
 * <pre>
 * :index rdf:type text:TextIndexShacl ;
 *     text:directory &lt;file:Lucene&gt; ;
 *     text:taxonomyDirectory &lt;file:Taxonomy&gt; ;   # optional; defaults to a sibling
 *                                                 # &lt;text:directory&gt;_taxonomy, or to
 *                                                 # memory for a "mem" index
 *     text:shapes ( :Shape1 :Shape2 ) ;
 *     text:storeValues true ;
 *     text:maxFacetHits 50000 .
 * </pre>
 * <p>
 * Requires {@code text:shapes} — use {@code text:TextIndexLucene} with
 * {@code text:entityMap} for classic triple-per-document mode.
 */
public class ShaclTextIndexAssembler extends AssemblerBase {
    private static final Logger log = LoggerFactory.getLogger(ShaclTextIndexAssembler.class);

    @Override
    public TextIndex open(Assembler a, Resource root, Mode mode) {
        try {
            // Directory (required)
            if (!GraphUtils.exactlyOneProperty(root, pDirectory))
                throw new TextIndexException("No 'text:directory' property on " + root);

            Directory directory = asDirectory(root.getProperty(pDirectory).getObject());

            // Shapes (required)
            Statement shapesStmt = root.getProperty(pShapes);
            if (shapesStmt == null)
                throw new TextIndexException("text:TextIndexShacl requires text:shapes on " + root);

            ShaclIndexMapping shaclMapping = ShaclIndexAssembler.parseShapes(a, shapesStmt.getObject().asResource());
            EntityDefinition docDef = ShaclIndexAssembler.deriveEntityDefinition(shaclMapping);

            // Optional taxonomy directory — hierarchical facet ordinals. Resolved after
            // the shapes because the default depends on whether any hierarchy exists.
            Directory taxonomyDirectory = null;
            Statement taxonomyStatement = root.getProperty(pTaxonomyDirectory);
            if (taxonomyStatement != null) {
                taxonomyDirectory = asDirectory(taxonomyStatement.getObject());
            } else if (shaclMapping.hasHierarchies()) {
                taxonomyDirectory = defaultTaxonomyDirectory(directory);
            }

            // Optional analyzers
            Analyzer analyzer = null;
            Statement analyzerStatement = root.getProperty(pAnalyzer);
            if (analyzerStatement != null) {
                RDFNode aNode = analyzerStatement.getObject();
                if (!aNode.isResource())
                    throw new TextIndexException("Text analyzer property is not a resource : " + aNode);
                analyzer = (Analyzer) a.open(aNode.asResource());
            }

            Analyzer queryAnalyzer = null;
            Statement queryAnalyzerStatement = root.getProperty(pQueryAnalyzer);
            if (queryAnalyzerStatement != null) {
                RDFNode qaNode = queryAnalyzerStatement.getObject();
                if (!qaNode.isResource())
                    throw new TextIndexException("Text query analyzer property is not a resource : " + qaNode);
                queryAnalyzer = (Analyzer) a.open(qaNode.asResource());
            }

            // Optional storeValues
            boolean storeValues = false;
            Statement storeValuesStatement = root.getProperty(pStoreValues);
            if (storeValuesStatement != null) {
                RDFNode svNode = storeValuesStatement.getObject();
                if (!svNode.isLiteral())
                    throw new TextIndexException("text:storeValues property must be a boolean : " + svNode);
                storeValues = svNode.asLiteral().getBoolean();
            }

            // Build config
            TextIndexConfig config = new TextIndexConfig(docDef);
            config.setAnalyzer(analyzer);
            config.setQueryAnalyzer(queryAnalyzer);
            config.setValueStored(storeValues);
            config.setShaclMapping(shaclMapping);
            config.setFacetFields(shaclMapping.getFacetFieldNames());
            config.setEmbeddingProvider(resolveEmbeddingProvider(root, shaclMapping));

            Statement knnTopKStatement = root.getProperty(IndexVocab.pKnnTopK);
            if (knnTopKStatement != null) {
                RDFNode kNode = knnTopKStatement.getObject();
                if (!kNode.isLiteral())
                    throw new TextIndexException("idx:knnTopK property must be an int : " + kNode);
                int k = kNode.asLiteral().getInt();
                if (k <= 0)
                    throw new TextIndexException("idx:knnTopK must be positive, got : " + k);
                config.setKnnTopK(k);
            }

            // Optional maxFacetHits
            Statement maxFacetHitsStatement = root.getProperty(pMaxFacetHits);
            if (maxFacetHitsStatement != null) {
                RDFNode mfhNode = maxFacetHitsStatement.getObject();
                if (!mfhNode.isLiteral())
                    throw new TextIndexException("text:maxFacetHits property must be an int : " + mfhNode);
                config.setMaxFacetHits(mfhNode.asLiteral().getInt());
            }

            return new ShaclTextIndexLucene(directory, taxonomyDirectory, config);
        } catch (IOException e) {
            IO.exception(e);
            return null;
        }
    }

    /**
     * Build the embedding provider for this index, and check it against the vector fields
     * the shapes declare.
     * <p>
     * Two mismatches are caught here rather than left to fail later, or worse, not fail at
     * all: a vector field with no provider (the field would silently never be populated),
     * and a provider whose dimension differs from the field's (Lucene fixes a KNN field's
     * dimension at index time, so this is unrecoverable once documents exist).
     *
     * @return the provider, or null when nothing in the config needs one
     */
    private static EmbeddingProvider resolveEmbeddingProvider(Resource root, ShaclIndexMapping mapping) {
        EmbeddingConfig embeddingConfig = ShaclIndexAssembler.parseEmbeddingConfig(root);

        List<ShaclIndexMapping.FieldDef> vectorFields = new ArrayList<>();
        for (ShaclIndexMapping.IndexProfile profile : mapping.getProfiles()) {
            vectorFields.addAll(profile.getVectorFields());
        }

        if (vectorFields.isEmpty()) {
            if (embeddingConfig != null) {
                log.warn("idx:embedding is configured on {} but no shape declares an idx:vectorField;"
                    + " no embeddings will be produced.", root);
            }
            return null;
        }
        if (embeddingConfig == null) {
            throw new TextIndexException(
                "Shapes declare vector field(s) " + fieldNames(vectorFields)
                + " but " + root + " has no idx:embedding block naming a provider.");
        }

        EmbeddingProvider provider = EmbeddingProviders.create(embeddingConfig);
        for (ShaclIndexMapping.FieldDef field : vectorFields) {
            int declared = field.getVectorDef().dimension();
            if (declared != provider.dimension()) {
                throw new TextIndexException(
                    "Vector field '" + field.getFieldName() + "' declares idx:dimension " + declared
                    + " but embedding provider '" + embeddingConfig.provider() + "' (model "
                    + provider.modelId() + ") produces " + provider.dimension() + "-dimensional vectors. "
                    + "Lucene fixes a KNN field's dimension at index time, so these must match.");
            }
        }
        return provider;
    }

    private static String fieldNames(List<ShaclIndexMapping.FieldDef> fields) {
        StringJoiner joiner = new StringJoiner(", ");
        for (ShaclIndexMapping.FieldDef field : fields) {
            joiner.add("'" + field.getFieldName() + "'");
        }
        return joiner.toString();
    }

    /**
     * Where hierarchical facet ordinals go when {@code text:taxonomyDirectory} is absent.
     * <p>
     * Tied to {@code text:directory} rather than fixed, because the two have to agree
     * about persistence. A persistent index paired with an in-memory taxonomy is the
     * loader/server split: the bulk build writes ordinals into a directory that dies with
     * the process, and the server then reads an index whose facet ordinals nothing can
     * resolve. An in-memory index paired with a persistent taxonomy is the mirror image,
     * and equally pointless.
     * <p>
     * So an {@link FSDirectory} index gets an {@code FSDirectory} taxonomy at a sibling
     * {@code <path>_taxonomy}, and anything else keeps the in-memory default.
     */
    private static Directory defaultTaxonomyDirectory(Directory directory) throws IOException {
        if (directory instanceof FSDirectory fsDirectory) {
            Path indexPath = fsDirectory.getDirectory();
            return FSDirectory.open(indexPath.resolveSibling(indexPath.getFileName() + "_taxonomy"));
        }
        return new ByteBuffersDirectory();
    }

    /** Resolve a directory-valued config node: the literal {@code "mem"} for an
     *  in-memory directory, any other literal as a path, a resource as a file IRI. */
    private static Directory asDirectory(RDFNode node) throws IOException {
        if (node.isLiteral()) {
            String literalValue = node.asLiteral().getLexicalForm();
            if (literalValue.equals("mem")) {
                return new ByteBuffersDirectory();
            }
            return FSDirectory.open(new File(literalValue).toPath());
        }
        String path = IRILib.IRIToFilename(node.asResource().getURI());
        return FSDirectory.open(new File(path).toPath());
    }
}
