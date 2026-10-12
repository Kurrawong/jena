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

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.system.Txn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Live writes to a {@code text:TextDataset} with {@code text:indexes ( :a :b )} must be
 * committed to, and rolled back from, every index in the list.
 * <p>
 * Every index has its own Lucene {@code IndexWriter}, and documents a writer has not
 * committed are invisible to searches. When only the first index joined the dataset's
 * transaction, the second built its documents on every write and never committed them:
 * its directory held an empty {@code segments_1}, so {@code luc:query} and
 * {@code luc:facet} on its selector returned nothing until an offline
 * {@code shacltextindexer} rebuild.
 * <p>
 * Each test runs over the three ways {@code DatasetGraphText} joins a transaction: TDB2
 * and TDB1 register external components with their coordinator, and any other dataset
 * (here an in-memory one) is committed by {@code DatasetGraphText} itself.
 */
public class TestMultiIndexLiveWrites {

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    private static final String NS = "http://example.org/";
    private static final String FIELD = "urn:jena:lucene:field#";

    private Path dir;
    private Dataset dataset;

    @BeforeEach
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("multi-index-live-writes");
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

    private String baseDataset(String kind) {
        String location = dir.resolve("db").toString();
        return switch (kind) {
            case "mem" -> "ex:base rdf:type ja:MemoryDataset .\n";
            case "tdb2" -> "ex:base rdf:type tdb2:DatasetTDB2 ; tdb2:location \"" + location + "\" .\n";
            case "tdb1" -> "ex:base rdf:type tdb:DatasetTDB ; tdb:location \"" + location + "\" .\n";
            default -> throw new IllegalArgumentException(kind);
        };
    }

    /** Index {@code ex:things} first, so {@code ex:concepts} is the one that was dropped. */
    private Dataset assemble(String kind) {
        String turtle =
            "@prefix idx:   <urn:jena:lucene:index#> .\n"
            + "@prefix field: <" + FIELD + "> .\n"
            + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix text:  <http://jena.apache.org/text#> .\n"
            + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
            + "@prefix tdb:   <http://jena.hpl.hp.com/2008/tdb#> .\n"
            + "@prefix tdb2:  <http://jena.apache.org/2016/tdb#> .\n"
            + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
            + "@prefix ex:    <" + NS + "> .\n"
            + "field:title  idx:fieldName \"title\" ; idx:fieldType idx:TextField ;\n"
            + "    idx:stored true ; idx:defaultSearch true .\n"
            + "field:kind   idx:fieldName \"kind\" ; idx:fieldType idx:KeywordField ;\n"
            + "    idx:facetable true .\n"
            + "field:scheme idx:fieldName \"scheme\" ; idx:fieldType idx:KeywordField ;\n"
            + "    idx:facetable true .\n"
            + "ex:ThingShape sh:targetClass ex:Thing ;\n"
            + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
            + "    sh:property [ idx:field field:kind ; sh:path ex:kind ] .\n"
            + "ex:ConceptShape sh:targetClass ex:Concept ;\n"
            + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
            + "    sh:property [ idx:field field:scheme ; sh:path ex:scheme ] .\n"
            + "ex:dataset rdf:type text:TextDataset ;\n"
            + "    text:dataset ex:base ;\n"
            + "    text:indexes ( ex:things ex:concepts ) .\n"
            + baseDataset(kind)
            + "ex:things rdf:type text:TextIndexShacl ;\n"
            + "    text:indexId \"things\" ;\n"
            + "    text:directory \"" + dir.resolve("things") + "\" ;\n"
            + "    text:shapes ( ex:ThingShape ) .\n"
            + "ex:concepts rdf:type text:TextIndexShacl ;\n"
            + "    text:indexId \"concepts\" ;\n"
            + "    text:directory \"" + dir.resolve("concepts") + "\" ;\n"
            + "    text:shapes ( ex:ConceptShape ) .\n";

        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(turtle), null, "TTL");
        Resource spec = model.getResource(NS + "dataset");
        return (Dataset) Assembler.general().open(spec);
    }

    private static final String DATA =
        "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
        + "@prefix ex:  <" + NS + "> .\n"
        + "ex:thing1 rdf:type ex:Thing ; ex:title \"alpha widget\" ; ex:kind \"red\" .\n"
        + "ex:thing2 rdf:type ex:Thing ; ex:title \"beta widget\" ; ex:kind \"blue\" .\n"
        + "ex:c1 rdf:type ex:Concept ; ex:title \"basalt rock\" ; ex:scheme \"rocks\" .\n"
        + "ex:c2 rdf:type ex:Concept ; ex:title \"granite rock\" ; ex:scheme \"rocks\" .\n"
        + "ex:c3 rdf:type ex:Concept ; ex:title \"forest cover\" ; ex:scheme \"cover\" .\n";

    /** Parse Turtle into the default graph inside one write transaction, as a GSP POST does. */
    private void post(String turtle) {
        Txn.executeWrite(dataset, () ->
            RDFParser.fromString(turtle, Lang.TURTLE).parse(dataset.asDatasetGraph()));
    }

    private Set<String> query(String selector, String queryString) {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?s WHERE {\n"
            + "  (?hit ?s ?score) luc:query (\"" + selector + "\" \"default\" \""
            + queryString + "\" \"\" \"\" 100 0)\n"
            + "}";
        Set<String> uris = new HashSet<>();
        Txn.executeRead(dataset, () -> {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    uris.add(rs.next().getResource("s").getURI());
                }
            }
        });
        return uris;
    }

    private Map<String, Integer> facet(String selector, String field) {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?v ?c WHERE {\n"
            + "  (?f ?v ?low ?high ?c) luc:facet (\"" + selector + "\" \"default\" \"\" '[\""
            + FIELD + field + "\"]' \"\" 0 0)\n"
            + "}";
        Map<String, Integer> counts = new HashMap<>();
        Txn.executeRead(dataset, () -> {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    counts.put(sol.getLiteral("v").getLexicalForm(), sol.getLiteral("c").getInt());
                }
            }
        });
        return counts;
    }

    @ParameterizedTest
    @ValueSource(strings = {"mem", "tdb2", "tdb1"})
    public void liveWriteIsCommittedToEveryIndex(String kind) {
        dataset = assemble(kind);
        post(DATA);

        assertEquals(Set.of(NS + "thing1", NS + "thing2"), query("things", "widget"),
            "first index in text:indexes");
        assertEquals(Set.of(NS + "c1", NS + "c2"), query("concepts", "rock"),
            "second index in text:indexes");
        assertEquals(Map.of("rocks", 2, "cover", 1), facet("concepts", "scheme"),
            "facet counts on the second index");
    }

    /**
     * An aborted write must be discarded from every index. Without a rollback the
     * aborted documents stay pending in the second index's writer, and the next
     * committed write publishes them alongside its own.
     */
    @ParameterizedTest
    @ValueSource(strings = {"mem", "tdb2", "tdb1"})
    public void abortedWriteIsRolledBackFromEveryIndex(String kind) {
        dataset = assemble(kind);
        post(DATA);

        dataset.begin(org.apache.jena.query.ReadWrite.WRITE);
        try {
            RDFParser.fromString(
                "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
                + "@prefix ex:  <" + NS + "> .\n"
                + "ex:thing9 rdf:type ex:Thing ; ex:title \"aborted widget\" ; ex:kind \"red\" .\n"
                + "ex:c9 rdf:type ex:Concept ; ex:title \"aborted rock\" ; ex:scheme \"rocks\" .\n",
                Lang.TURTLE).parse(dataset.asDatasetGraph());
            dataset.abort();
        } finally {
            dataset.end();
        }

        post("@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
            + "@prefix ex:  <" + NS + "> .\n"
            + "ex:c4 rdf:type ex:Concept ; ex:title \"pumice rock\" ; ex:scheme \"rocks\" .\n");

        assertEquals(Set.of(NS + "thing1", NS + "thing2"), query("things", "widget"),
            "first index after an aborted write");
        assertEquals(Set.of(NS + "c1", NS + "c2", NS + "c4"), query("concepts", "rock"),
            "second index after an aborted write");
    }
}
