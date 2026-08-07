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

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.FacetValue;
import org.apache.jena.query.text.ShaclTextDocProducer;
import org.apache.jena.query.text.ShaclTextIndexLucene;
import org.apache.jena.query.text.TextDatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.vocabulary.RDF;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@code text:taxonomyDirectory} — where hierarchical facet ordinals are written.
 * <p>
 * The default is derived from {@code text:directory} rather than fixed: a persistent
 * index gets a persistent taxonomy at a sibling {@code <path>_taxonomy}, and only an
 * in-memory index gets an in-memory taxonomy. {@code text:taxonomyDirectory} remains an
 * explicit override.
 * <p>
 * Before that, the default was in-memory regardless. Pairing a persistent index with an
 * ephemeral taxonomy is invisible while indexing and querying share a JVM (the live
 * change-listener path) and fatal when they do not: a bulk build writes its facet
 * ordinals into a taxonomy discarded when the process exits, and the server then starts
 * on an empty one and reports no hierarchical facet counts at all — a silent, total loss
 * with no error anywhere. That is the ordinary loader/server split, not an edge case, and
 * an {@code idx:externalSource} profile is rebuild-only, so bulk indexing is the
 * <em>only</em> way to build it.
 */
public class TestTaxonomyDirectoryAssembler {

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    private Path dir;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("taxonomy-directory-test");
    }

    @After
    public void tearDown() throws IOException {
        if (dir != null) {
            try (var paths = Files.walk(dir)) {
                paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> p.toFile().delete());
            }
        }
    }

    private static final String SHAPE_AND_FIELDS =
        "field:kind idx:fieldName \"kind\" ; idx:fieldType idx:KeywordField ; idx:facetable true .\n"
        + "field:sub  idx:fieldName \"sub\"  ; idx:fieldType idx:KeywordField ; idx:facetable true .\n"
        + "ex:ThingShape\n"
        + "    sh:targetClass ex:Thing ;\n"
        + "    sh:property [ idx:field field:kind ; sh:path ex:kind ] ;\n"
        + "    sh:property [ idx:field field:sub  ; sh:path ex:sub ] ;\n"
        + "    idx:facetHierarchy ( field:kind field:sub ) .\n";

    /** The same two fields with no {@code idx:facetHierarchy}, so the mapping carries no
     *  hierarchy and the index needs no taxonomy at all. */
    private static final String SHAPE_WITHOUT_HIERARCHY =
        "field:kind idx:fieldName \"kind\" ; idx:fieldType idx:KeywordField ; idx:facetable true .\n"
        + "field:sub  idx:fieldName \"sub\"  ; idx:fieldType idx:KeywordField ; idx:facetable true .\n"
        + "ex:ThingShape\n"
        + "    sh:targetClass ex:Thing ;\n"
        + "    sh:property [ idx:field field:kind ; sh:path ex:kind ] ;\n"
        + "    sh:property [ idx:field field:sub  ; sh:path ex:sub ] .\n";

    private ShaclTextIndexLucene openIndex(String indexProperties) {
        return openIndex(indexProperties, SHAPE_AND_FIELDS);
    }

    private ShaclTextIndexLucene openIndex(String indexProperties, String shapeBlock) {
        String turtle =
            "@prefix idx:   <urn:jena:lucene:index#> .\n"
            + "@prefix field: <urn:jena:lucene:field#> .\n"
            + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix text:  <http://jena.apache.org/text#> .\n"
            + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
            + "@prefix ex:    <http://example.org/> .\n"
            + shapeBlock
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:shapes ( ex:ThingShape ) ;\n"
            + indexProperties
            + "    .\n";

        Model model = ModelFactory.createDefaultModel();
        model.read(new java.io.StringReader(turtle), null, "TTL");
        Resource indexSpec = model.getResource("http://example.org/index");
        return (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
    }

    /** True when {@code path} holds a Lucene index, not merely an empty directory. */
    private static boolean holdsLuceneIndex(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (var paths = Files.list(path)) {
            return paths.anyMatch(p -> p.getFileName().toString().startsWith("segments"));
        }
    }

    /** Write three entities through the change-listener path, as a bulk build would. */
    private void indexThings(ShaclTextIndexLucene index) {
        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(
            baseDs.asDatasetGraph(), index, index.getShaclMapping());
        Dataset ds = TextDatasetFactory.create(baseDs, index, true, producer);
        ds.begin(ReadWrite.WRITE);
        try {
            Model model = ds.getDefaultModel();
            addThing(model, "t1", "Water", "Shallow");
            addThing(model, "t2", "Water", "Deep");
            addThing(model, "t3", "Mineral", "Gold");
            ds.commit();
        } finally {
            ds.end();
        }
        // closeIndexOnDSGClose is set, so this closes the index and commits the taxonomy.
        ds.close();
    }

    private static void addThing(Model model, String id, String kind, String sub) {
        Resource thing = ResourceFactory.createResource("http://example.org/" + id);
        model.add(thing, RDF.type, ResourceFactory.createResource("http://example.org/Thing"));
        model.add(thing, ResourceFactory.createProperty("http://example.org/kind"),
            ResourceFactory.createPlainLiteral(kind));
        model.add(thing, ResourceFactory.createProperty("http://example.org/sub"),
            ResourceFactory.createPlainLiteral(sub));
    }

    /** The taxonomy is written where the config says, so it survives the process that
     *  built it. */
    @Test
    public void taxonomyDirectoryIsPersistedToDisk() {
        Path lucene = dir.resolve("Lucene");
        Path taxonomy = dir.resolve("Taxonomy");

        ShaclTextIndexLucene index = openIndex(
            "    text:directory <file://" + lucene + "> ;\n"
            + "    text:taxonomyDirectory <file://" + taxonomy + "> ;\n");
        try {
            assertTrue("hierarchical faceting must be active", index.hasHierarchies());
            index.commit();
            assertTrue("taxonomy directory should exist on disk", Files.isDirectory(taxonomy));
            assertTrue("and hold a Lucene index",
                Files.list(taxonomy).anyMatch(p -> p.getFileName().toString().startsWith("segments")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            index.close();
        }
    }

    /** A "mem" taxonomy is explicit about being throwaway. */
    @Test
    public void memTaxonomyDirectoryIsAccepted() {
        Path lucene = dir.resolve("Lucene2");
        ShaclTextIndexLucene index = openIndex(
            "    text:directory <file://" + lucene + "> ;\n"
            + "    text:taxonomyDirectory \"mem\" ;\n");
        try {
            assertTrue(index.hasHierarchies());
        } finally {
            index.close();
        }
    }

    /** Omitted against a persistent index, the taxonomy defaults to a sibling of
     *  {@code text:directory} rather than to memory. */
    @Test
    public void absentTaxonomyDirectoryDerivesFromIndexDirectory() throws IOException {
        Path lucene = dir.resolve("Lucene3");
        Path derived = dir.resolve("Lucene3_taxonomy");

        ShaclTextIndexLucene index = openIndex(
            "    text:directory <file://" + lucene + "> ;\n");
        try {
            assertTrue("hierarchies still enable faceting", index.hasHierarchies());
            index.commit();
            assertTrue("taxonomy should default to " + derived + ", not to memory",
                holdsLuceneIndex(derived));
        } finally {
            index.close();
        }
    }

    /** An in-memory index keeps an in-memory taxonomy: persisting ordinals for an index
     *  that does not itself persist would make no sense, and nothing should reach disk. */
    @Test
    public void memIndexDirectoryKeepsTaxonomyInMemory() throws IOException {
        ShaclTextIndexLucene index = openIndex("    text:directory \"mem\" ;\n");
        try {
            assertTrue("hierarchies still enable faceting", index.hasHierarchies());
            index.commit();
            try (var paths = Files.list(dir)) {
                assertEquals("a \"mem\" index must not write a taxonomy to disk",
                    Collections.emptyList(), paths.toList());
            }
        } finally {
            index.close();
        }
    }

    /** No hierarchy in the mapping means no taxonomy is needed, so no directory should be
     *  created beside the index. */
    @Test
    public void noTaxonomyDirectoryCreatedWithoutHierarchies() throws IOException {
        Path lucene = dir.resolve("Lucene4");
        Path derived = dir.resolve("Lucene4_taxonomy");

        ShaclTextIndexLucene index = openIndex(
            "    text:directory <file://" + lucene + "> ;\n", SHAPE_WITHOUT_HIERARCHY);
        try {
            assertFalse("no hierarchy declared", index.hasHierarchies());
            index.commit();
            assertFalse("nothing should be created at " + derived,
                Files.exists(derived));
        } finally {
            index.close();
        }
    }

    /** The regression that matters: the loader image builds the index in one process and
     *  the server reads it in another. Hierarchical counts must survive that boundary
     *  without {@code text:taxonomyDirectory} being set. */
    @Test
    public void hierarchyFacetsSurviveReopen() {
        Path lucene = dir.resolve("Reopen");
        String indexProperties = "    text:directory <file://" + lucene + "> ;\n";

        String dimension;
        ShaclTextIndexLucene builder = openIndex(indexProperties);
        try {
            dimension = builder.getHierarchyDimensions().iterator().next();
        } catch (RuntimeException e) {
            builder.close();
            throw e;
        }
        indexThings(builder);

        ShaclTextIndexLucene reopened = openIndex(indexProperties);
        try {
            Map<String, List<FacetValue>> counts = reopened.getFacetCounts(
                null, null, Collections.singletonList(dimension), 10, 0);
            List<FacetValue> values = counts.get(dimension);
            assertNotNull("hierarchy dimension absent after reopen", values);

            Map<String, Long> byLabel = new java.util.HashMap<>();
            for (FacetValue value : values) {
                byLabel.put(value.getValue(), value.getCount());
            }
            assertEquals("Water count lost across reopen", Long.valueOf(2), byLabel.get("Water"));
            assertEquals("Mineral count lost across reopen", Long.valueOf(1), byLabel.get("Mineral"));
        } finally {
            reopened.close();
        }
    }
}
