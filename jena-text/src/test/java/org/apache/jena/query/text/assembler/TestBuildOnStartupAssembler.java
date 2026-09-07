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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.DatasetGraphText;
import org.apache.jena.query.text.TextHit;
import org.apache.jena.query.text.TextIndex;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code text:buildOnStartup} — bulk-index the base dataset as the dataset is assembled.
 * <p>
 * A SHACL index is populated either by the change listener, which only sees triples that
 * arrive after the dataset is wrapped, or by the {@code shacltextindexer} command, which
 * is a separate process. Data loaded declaratively — {@code ja:data} on the base dataset,
 * or a TDB2 store filled by {@code tdb2.tdbloader} — is visible to neither: it is already
 * present when the wrapping happens, and the separate process cannot hand a
 * {@code text:directory "mem"} index back to the server that needs it.
 * <p>
 * That combination — an in-memory dataset with an in-memory index — is the whole of a
 * self-contained demo or test server, and before this flag there was no way to make it
 * hold any content at all. {@code text:buildOnStartup true} closes the gap by running
 * {@link org.apache.jena.query.text.ShaclBulkIndexer} at the one point where the index
 * and its populated dataset are both in hand.
 */
public class TestBuildOnStartupAssembler {

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    private static final String NS = "http://example.org/";
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");

    private Path dir;
    private Dataset dataset;

    @BeforeEach
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("build-on-startup-test");
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (dataset != null) {
            dataset.close();
            dataset = null;
        }
        if (dir != null) {
            try (var paths = Files.walk(dir)) {
                paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> p.toFile().delete());
            }
        }
    }

    private static final String SHAPE_AND_FIELDS =
        "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ;\n"
        + "    idx:stored true ; idx:defaultSearch true .\n"
        + "field:kind  idx:fieldName \"kind\"  ; idx:fieldType idx:KeywordField ;\n"
        + "    idx:facetable true .\n"
        + "ex:ThingShape\n"
        + "    sh:targetClass ex:Thing ;\n"
        + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
        + "    sh:property [ idx:field field:kind  ; sh:path ex:kind ] .\n";

    /** Two entities on disk before the dataset is ever assembled. */
    private String writeDataFile() throws IOException {
        String data =
            "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
            + "@prefix ex:  <" + NS + "> .\n"
            + "ex:thing1 rdf:type ex:Thing ; ex:title \"alpha widget\" ; ex:kind \"red\" .\n"
            + "ex:thing2 rdf:type ex:Thing ; ex:title \"beta widget\"  ; ex:kind \"blue\" .\n";
        Path file = dir.resolve("data.ttl");
        Files.writeString(file, data);
        return file.toUri().toString();
    }

    /**
     * A complete {@code text:TextDataset} over an in-memory base loaded from
     * {@code ja:data} and an in-memory index.
     *
     * @param datasetProperties extra properties on the TextDataset node
     * @param indexClause       {@code text:index} or {@code text:indexes ( ... )}
     */
    private Dataset assemble(String datasetProperties, String indexClause) throws IOException {
        String turtle =
            "@prefix idx:   <urn:jena:lucene:index#> .\n"
            + "@prefix field: <urn:jena:lucene:field#> .\n"
            + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix text:  <http://jena.apache.org/text#> .\n"
            + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
            + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
            + "@prefix ex:    <" + NS + "> .\n"
            + SHAPE_AND_FIELDS
            + "ex:dataset rdf:type text:TextDataset ;\n"
            + "    text:dataset ex:base ;\n"
            + indexClause
            + datasetProperties
            + "    .\n"
            + "ex:base rdf:type ja:MemoryDataset ;\n"
            + "    ja:data <" + writeDataFile() + "> .\n"
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:directory \"mem\" ;\n"
            + "    text:shapes ( ex:ThingShape ) .\n";

        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(turtle), null, "TTL");
        Resource spec = model.getResource(NS + "dataset");
        return (Dataset) Assembler.general().open(spec);
    }

    private static TextIndex indexOf(Dataset ds) {
        return ((DatasetGraphText) ds.asDatasetGraph()).getTextIndex();
    }

    private static Set<String> titleSearch(Dataset ds, String queryString) {
        List<TextHit> hits = indexOf(ds).query(TITLE_PRED, queryString, null, null);
        Set<String> uris = new HashSet<>();
        for (TextHit hit : hits) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }

    @Test
    public void preloadedDataIsIndexedOnStartup() throws IOException {
        dataset = assemble("    ; text:buildOnStartup true\n", "    text:index ex:index ;\n");
        assertEquals(Set.of(NS + "thing1", NS + "thing2"), titleSearch(dataset, "widget"),
            "ja:data content should be in the index without any separate indexing step");
    }

    /**
     * Without the flag nothing indexes the pre-existing triples. Asserted so the default
     * stays deliberate: turning it on by default would silently re-index every TDB2
     * store on every server start.
     */
    @Test
    public void withoutTheFlagPreloadedDataIsNotIndexed() throws IOException {
        dataset = assemble("", "    text:index ex:index ;\n");
        assertTrue(titleSearch(dataset, "widget").isEmpty(),
            "no index build was requested, so the index should be empty");
    }

    /** The change listener is still attached: a startup build is not a one-shot mode. */
    @Test
    public void liveUpdatesAreStillIndexedAfterAStartupBuild() throws IOException {
        dataset = assemble("    ; text:buildOnStartup true\n", "    text:index ex:index ;\n");

        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource thing3 = ResourceFactory.createResource(NS + "thing3");
            model.add(thing3, RDF.type, ResourceFactory.createResource(NS + "Thing"));
            model.add(thing3, ResourceFactory.createProperty(NS + "title"), "gamma widget");
            model.add(thing3, ResourceFactory.createProperty(NS + "kind"), "green");
            dataset.commit();
        } finally {
            dataset.end();
        }

        assertEquals(Set.of(NS + "thing1", NS + "thing2", NS + "thing3"),
            titleSearch(dataset, "widget"),
            "startup build and live indexing should both apply");
    }

    /** Multi-index configurations take the same flag. */
    @Test
    public void multiIndexAlsoBuildsOnStartup() throws IOException {
        dataset = assemble("    ; text:buildOnStartup true\n", "    text:indexes ( ex:index ) ;\n");
        assertEquals(Set.of(NS + "thing1", NS + "thing2"), titleSearch(dataset, "widget"),
            "text:indexes should honour text:buildOnStartup too");
    }
}
