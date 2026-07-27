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

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.text.ShaclTextIndexLucene;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sys.JenaSystem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@code text:taxonomyDirectory} — where hierarchical facet ordinals are written.
 * <p>
 * Without it the taxonomy lives in memory, which is invisible while indexing and
 * querying happen in one JVM (the live change-listener path) and fatal when they
 * do not. A bulk build writes its facet ordinals into a taxonomy that is discarded
 * when the process exits, and the server then starts on an empty one and reports no
 * hierarchical facet counts at all — a silent, total loss with no error anywhere.
 * <p>
 * That combination is not exotic: an {@code idx:externalSource} profile is
 * rebuild-only, so bulk indexing is the <em>only</em> way to build it.
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

    private ShaclTextIndexLucene openIndex(String indexProperties) {
        String turtle =
            "@prefix idx:   <urn:jena:lucene:index#> .\n"
            + "@prefix field: <urn:jena:lucene:field#> .\n"
            + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix text:  <http://jena.apache.org/text#> .\n"
            + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
            + "@prefix ex:    <http://example.org/> .\n"
            + SHAPE_AND_FIELDS
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:shapes ( ex:ThingShape ) ;\n"
            + indexProperties
            + "    .\n";

        Model model = ModelFactory.createDefaultModel();
        model.read(new java.io.StringReader(turtle), null, "TTL");
        Resource indexSpec = model.getResource("http://example.org/index");
        return (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
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

    /** Omitting it keeps the previous behaviour — an in-memory taxonomy — so existing
     *  configs are unaffected. */
    @Test
    public void absentTaxonomyDirectoryStillWorksInMemory() {
        Path lucene = dir.resolve("Lucene3");
        ShaclTextIndexLucene index = openIndex(
            "    text:directory <file://" + lucene + "> ;\n");
        try {
            assertTrue("hierarchies still enable faceting", index.hasHierarchies());
        } finally {
            index.close();
        }
    }
}
