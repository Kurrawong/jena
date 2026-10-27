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

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.*;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.Test;

/**
 * {@code idx:boost}: a field's weight when the query string is searched across several
 * fields, and the search-as-you-type twin it exists for.
 * <p>
 * The twin is an exact {@code TextField} and a per-word {@code EdgeNGramAnalyzer} copy of
 * it over the same path, both in {@code idx:defaultSearch}. The exact field keeps its BM25
 * ranking for whole words; the twin adds the prefix hits a half-typed word needs. Every
 * case here runs through {@code luc:query} on an assembled configuration, because the
 * ranking is a property of the configuration as a deployment writes it.
 */
public class TestFieldBoost {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final String NS = "http://example.org/";
    private static final String FIELD = "urn:jena:lucene:field#";

    private static final String PREFIXES =
        "PREFIX luc:   <urn:jena:lucene:index#>\n"
        + "PREFIX idx:   <urn:jena:lucene:index#>\n"
        + "PREFIX field: <urn:jena:lucene:field#>\n";

    /** Hold "bore" as a whole word. The last is a long title, whose BM25 score is lowest. */
    private static final Set<String> WHOLE_WORD = Set.of(NS + "bore", NS + "waterBore", NS + "longTitle");

    /** Hold "bore" only as the start of a longer word. */
    private static final Set<String> PREFIX_ONLY = Set.of(NS + "bores", NS + "borehole", NS + "boredom");

    private static final String DATA =
        "@prefix ex: <" + NS + "> .\n"
        + "ex:bore      a ex:Report ; ex:title \"Bore\" .\n"
        + "ex:waterBore a ex:Report ; ex:title \"Water bore\" .\n"
        + "ex:longTitle a ex:Report ; ex:title \"Hydrogeological assessment of the northern basin"
        + " aquifer including monitoring of a deep bore beside the eastern escarpment and its"
        + " associated springs\" .\n"
        + "ex:bores     a ex:Report ; ex:title \"Bores\" .\n"
        + "ex:borehole  a ex:Report ; ex:title \"Borehole BH-12\" .\n"
        + "ex:boredom   a ex:Report ; ex:title \"Boredom\" .\n"
        + "ex:survey    a ex:Report ; ex:title \"Groundwater survey\" .\n"
        // Reachable only through the nested alias field.
        + "ex:aliased   a ex:Report ; ex:title \"Monitoring network\" ; ex:alias ex:alias1 .\n"
        + "ex:alias1    ex:aliasText \"Kingfisher\" .\n";

    /**
     * The twin pattern over {@code ex:title}, plus a nested alias field.
     *
     * @param prefixField extra properties on the edge-n-gram twin, {@code field:titlePrefix}
     * @param aliasField  extra properties on the nested {@code field:alias}
     */
    private static String config(String prefixField, String aliasField) {
        return "@prefix idx:   <urn:jena:lucene:index#> .\n"
            + "@prefix field: <urn:jena:lucene:field#> .\n"
            + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix text:  <http://jena.apache.org/text#> .\n"
            + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
            + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
            + "@prefix ex:    <" + NS + "> .\n"
            + "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ;\n"
            + "    idx:defaultSearch true .\n"
            + "field:titlePrefix idx:fieldName \"titlePrefix\" ; idx:fieldType idx:TextField ;\n"
            + "    idx:defaultSearch true ;\n"
            + "    idx:analyzer [ a text:EdgeNGramAnalyzer ; text:minGram 2 ; text:maxGram 20 ;\n"
            + "                   text:tokenized true ]" + prefixField + " .\n"
            + "field:alias idx:fieldName \"alias\" ; idx:fieldType idx:TextField ;\n"
            + "    idx:defaultSearch true" + aliasField + " .\n"
            + "ex:ReportShape\n"
            + "    sh:targetClass ex:Report ;\n"
            + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
            + "    sh:property [ idx:field field:titlePrefix ; sh:path ex:title ] ;\n"
            + "    idx:nested [\n"
            + "        idx:joinPath ex:alias ;\n"
            + "        idx:property [ idx:field field:alias ; sh:path ex:aliasText ] ;\n"
            + "    ] .\n"
            + "ex:dataset rdf:type text:TextDataset ;\n"
            + "    text:dataset ex:base ;\n"
            + "    text:index ex:index .\n"
            + "ex:base rdf:type ja:MemoryDataset .\n"
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:directory \"mem\" ;\n"
            + "    text:storeValues true ;\n"
            + "    text:exposeConfig true ;\n"
            + "    text:shapes ( ex:ReportShape ) .\n";
    }

    private static Dataset open(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(turtle), null, "TTL");
        Dataset dataset = (Dataset) Assembler.general().open(model.getResource(NS + "dataset"));
        dataset.begin(ReadWrite.WRITE);
        try {
            dataset.getDefaultModel().read(new StringReader(DATA), null, "TTL");
            dataset.commit();
        } finally {
            dataset.end();
        }
        return dataset;
    }

    /** Each hit's score for {@code text} over the default search fields, by entity IRI. */
    private static Map<String, Float> scores(String turtle, String text) {
        Dataset dataset = open(turtle);
        try {
            String q = PREFIXES + "SELECT ?entity ?score WHERE {\n"
                + "  (?hit ?entity ?score) luc:query (\"default\" \"default\" \"" + text + "\" \"\" \"\" 50 0) .\n"
                + "}";
            Map<String, Float> scores = new HashMap<>();
            try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
                qe.execSelect().forEachRemaining(row ->
                    scores.put(row.getResource("entity").getURI(), row.getLiteral("score").getFloat()));
            }
            return scores;
        } finally {
            dataset.close();
        }
    }

    private static float weakest(Map<String, Float> scores, Set<String> entities) {
        return (float) entities.stream().mapToDouble(scores::get).min().orElseThrow();
    }

    private static float strongest(Map<String, Float> scores, Set<String> entities) {
        return (float) entities.stream().mapToDouble(scores::get).max().orElseThrow();
    }

    /** How far the weakest whole-word hit ranks above the strongest prefix-only hit. */
    private static float gap(Map<String, Float> scores) {
        return weakest(scores, WHOLE_WORD) / strongest(scores, PREFIX_ONLY);
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    // ---- The search-as-you-type twin, unboosted

    @Test
    public void twinFindsWholeWordAndPrefixHits() {
        Set<String> expected = new HashSet<>(WHOLE_WORD);
        expected.addAll(PREFIX_ONLY);
        assertEquals(expected, scores(config("", ""), "bore").keySet());
    }

    @Test
    public void wholeWordHitsRankAboveEveryPrefixOnlyHit() {
        Map<String, Float> scores = scores(config("", ""), "bore");
        assertTrue(gap(scores) > 1.0f,
            "even the long title, " + scores.get(NS + "longTitle") + ", ranks above every prefix-only hit: " + scores);
    }

    @Test
    public void partlyTypedLastWordRanksTheTitleItCompletesFirst() {
        Map<String, Float> scores = scores(config("", ""), "water bor");
        String top = Collections.max(scores.entrySet(), Map.Entry.comparingByValue()).getKey();
        assertEquals(NS + "waterBore", top, scores.toString());
    }

    @Test
    public void singleLetterMatchesNothingAtMinGramTwo() {
        assertEquals(Map.of(), scores(config("", ""), "b"));
    }

    /** A client showing which field matched has to fold the twin into its exact field. */
    @Test
    public void lucMatchReportsTheTwinBesideTheExactField() {
        Map<String, Map<String, String>> matches = new HashMap<>();
        Dataset dataset = open(config("", ""));
        try {
            String q = PREFIXES + "SELECT ?entity ?field ?value WHERE {\n"
                + "  (?hit ?entity ?score) luc:query (\"default\" \"default\" \"bore\" \"\" \"\" 50 0) .\n"
                + "  (?hit ?field ?value) luc:match () .\n"
                + "}";
            try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
                qe.execSelect().forEachRemaining(row -> matches
                    .computeIfAbsent(row.getResource("entity").getURI(), k -> new HashMap<>())
                    .put(row.getResource("field").getURI(), row.getLiteral("value").getString()));
            }
        } finally {
            dataset.close();
        }
        assertEquals(Map.of(FIELD + "title", "Bore", FIELD + "titlePrefix", "Bore"),
            matches.get(NS + "bore"), "a whole-word hit, once per field");
        assertEquals(Map.of(FIELD + "titlePrefix", "Borehole BH-12"),
            matches.get(NS + "borehole"), "a prefix-only hit, on the twin alone");
    }

    // ---- idx:boost

    @Test
    public void boostScalesAPrefixOnlyHitExactly() {
        Map<String, Float> plain = scores(config("", ""), "bore");
        Map<String, Float> lowered = scores(config(" ;\n    idx:boost 0.5", ""), "bore");
        for (String entity : PREFIX_ONLY) {
            assertEquals(plain.get(entity) * 0.5f, lowered.get(entity), 1e-6f, entity);
        }
    }

    @Test
    public void loweringTheTwinsBoostWidensTheGap() {
        float plain = gap(scores(config("", ""), "bore"));
        float lowered = gap(scores(config(" ;\n    idx:boost 0.5", ""), "bore"));
        assertTrue(lowered > plain, "gap " + lowered + " at boost 0.5, " + plain + " unboosted");
    }

    @Test
    public void integerBoostIsAccepted() {
        Map<String, Float> plain = scores(config("", ""), "bore");
        Map<String, Float> raised = scores(config(" ;\n    idx:boost 2", ""), "bore");
        assertEquals(plain.get(NS + "bores") * 2f, raised.get(NS + "bores"), 1e-6f);
    }

    @Test
    public void boostAppliesToANestedScopeField() {
        float plain = scores(config("", ""), "kingfisher").get(NS + "aliased");
        float raised = scores(config("", " ;\n    idx:boost 2.0"), "kingfisher").get(NS + "aliased");
        assertEquals(plain * 2f, raised, 1e-6f);
    }

    @Test
    public void boostIsExposedByLucConfig() {
        Dataset dataset = open(config(" ;\n    idx:boost 0.5", ""));
        try {
            String q = PREFIXES + "SELECT ?boost WHERE {\n"
                + "  (field:titlePrefix idx:boost ?boost) luc:config (\"default\") .\n"
                + "}";
            try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
                ResultSet rs = qe.execSelect();
                assertTrue(rs.hasNext());
                assertEquals(0.5, rs.next().getLiteral("boost").getDouble(), 0.0);
            }
        } finally {
            dataset.close();
        }
    }

    // ---- Rejected configurations

    @Test
    public void zeroBoostIsRejected() {
        AssemblerException ex = assertThrows(AssemblerException.class,
            () -> open(config(" ;\n    idx:boost 0", "")));
        assertTrue(rootMessage(ex).contains("idx:boost"), rootMessage(ex));
    }

    @Test
    public void negativeBoostIsRejected() {
        AssemblerException ex = assertThrows(AssemblerException.class,
            () -> open(config(" ;\n    idx:boost -1.5", "")));
        assertTrue(rootMessage(ex).contains("idx:boost"), rootMessage(ex));
    }

    @Test
    public void nonNumericBoostIsRejected() {
        AssemblerException ex = assertThrows(AssemblerException.class,
            () -> open(config(" ;\n    idx:boost \"low\"", "")));
        assertTrue(rootMessage(ex).contains("idx:boost"), rootMessage(ex));
    }

    @Test
    public void boostOnAnOccurrenceIsRejected() {
        String turtle = config("", "").replace(
            "[ idx:field field:titlePrefix ; sh:path ex:title ]",
            "[ idx:field field:titlePrefix ; sh:path ex:title ; idx:boost 0.5 ]");
        AssemblerException ex = assertThrows(AssemblerException.class, () -> open(turtle));
        assertTrue(rootMessage(ex).contains("boost"), rootMessage(ex));
    }
}
