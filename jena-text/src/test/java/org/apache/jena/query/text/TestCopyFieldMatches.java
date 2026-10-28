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
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.Test;

/**
 * {@code luc:match} and {@code luc:nestedMatch} report a match on a copy field, which declares
 * {@code idx:copyOf}, under its source field, once. The assembler has checked the source field
 * holds every value the copy indexes, so the value reported is one of its own.
 */
public class TestCopyFieldMatches {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final String NS = "http://example.org/copy-field-match/";
    private static final String FIELD = "urn:jena:lucene:field#";

    private static final String PREFIXES =
        "PREFIX luc:   <urn:jena:lucene:index#>\n"
        + "PREFIX idx:   <urn:jena:lucene:index#>\n"
        + "PREFIX field: <" + FIELD + ">\n";

    private static final String TITLE = FIELD + "title";
    private static final String PART_NAME = FIELD + "partName";

    private static final String DATA =
        "@prefix ex: <" + NS + "> .\n"
        + "ex:bore     a ex:Report ; ex:title \"Bore\" .\n"
        + "ex:borehole a ex:Report ; ex:title \"Borehole BH-12\" .\n"
        + "ex:logged   a ex:Report ; ex:title \"Groundwater survey\" , \"Borehole log\" .\n"
        + "ex:site     a ex:Report ; ex:title \"Site\" ; ex:part ex:p1 .\n"
        + "ex:p1       ex:partName \"Pump house\"@en .\n"
        // Through ( ex:altTitle ex:title ), "Borehole log" is the first value and "Bore" the second.
        + "ex:mixed    a ex:Report ; ex:altTitle \"Borehole log\" ; ex:title \"Bore\" .\n";

    private static final String NGRAM =
        "idx:analyzer [ a text:EdgeNGramAnalyzer ;\n"
        + "        text:minGram 2 ; text:maxGram 20 ; text:tokenized true ]";

    private static String config(String titleExtra, String prefixExtra) {
        return config(titleExtra, prefixExtra, "", "ex:title");
    }

    /**
     * A title and its edge-n-gram copy field, and a nested part name and its copy field.
     *
     * @param titleExtra    extra properties on {@code field:title}
     * @param prefixExtra   extra properties on its copy field, {@code field:titlePrefix}
     * @param partNameExtra extra properties on {@code field:partName}
     * @param titlePath     the path feeding both the title and its copy field
     */
    private static String config(String titleExtra, String prefixExtra, String partNameExtra, String titlePath) {
        return "@prefix idx:   <urn:jena:lucene:index#> .\n"
            + "@prefix field: <" + FIELD + "> .\n"
            + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix text:  <http://jena.apache.org/text#> .\n"
            + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
            + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
            + "@prefix ex:    <" + NS + "> .\n"
            + "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ;\n"
            + "    idx:defaultSearch true" + titleExtra + " .\n"
            + "field:titlePrefix idx:fieldName \"titlePrefix\" ; idx:fieldType idx:TextField ;\n"
            + "    idx:defaultSearch true ; idx:copyOf field:title ;\n"
            + "    " + NGRAM + prefixExtra + " .\n"
            + "field:partName idx:fieldName \"partName\" ; idx:fieldType idx:TextField ;\n"
            + "    idx:defaultSearch true" + partNameExtra + " .\n"
            + "field:partNamePrefix idx:fieldName \"partNamePrefix\" ; idx:fieldType idx:TextField ;\n"
            + "    idx:defaultSearch true ; idx:copyOf field:partName ;\n"
            + "    " + NGRAM + " .\n"
            + "ex:ReportShape\n"
            + "    sh:targetClass ex:Report ;\n"
            + "    sh:property [ idx:field field:title ; sh:path " + titlePath + " ] ;\n"
            + "    sh:property [ idx:field field:titlePrefix ; sh:path " + titlePath + " ] ;\n"
            + "    idx:nested [\n"
            + "        idx:joinPath ex:part ;\n"
            + "        idx:property [ idx:field field:partName ; sh:path ex:partName ] ;\n"
            + "        idx:property [ idx:field field:partNamePrefix ; sh:path ex:partName ] ;\n"
            + "    ] .\n"
            + "ex:dataset rdf:type text:TextDataset ;\n"
            + "    text:dataset ex:base ;\n"
            + "    text:index ex:index .\n"
            + "ex:base rdf:type ja:MemoryDataset .\n"
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:directory \"mem\" ;\n"
            + "    text:storeValues true ;\n"
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

    /**
     * Each hit's {@code luc:match} rows for {@code text}, as "field = value", by entity.
     *
     * @param field the field slot: a variable, or a field to keep only its rows
     */
    private static Map<String, List<String>> matches(String turtle, String text, String field) {
        return rows(turtle, text, "  (?hit " + field + " ?value) luc:match () .\n", field);
    }

    private static Map<String, List<String>> nestedMatches(String turtle, String text) {
        return rows(turtle, text, "  (?hit ?record ?field ?value) luc:nestedMatch () .\n", "?field");
    }

    private static Map<String, List<String>> rows(String turtle, String text, String pattern, String field) {
        Map<String, List<String>> rows = new TreeMap<>();
        Dataset dataset = open(turtle);
        try {
            String q = PREFIXES + "SELECT * WHERE {\n"
                + "  (?hit ?entity) luc:query (\"default\" \"default\" \"" + text + "\" \"\" \"\" 50 0) .\n"
                + pattern
                + "}";
            try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
                qe.execSelect().forEachRemaining(row -> {
                    String name = field.startsWith("?")
                        ? row.getResource(field.substring(1)).getURI()
                        : field.replace("field:", FIELD);
                    RDFNode value = row.get("value");
                    rows.computeIfAbsent(row.getResource("entity").getURI(), k -> new ArrayList<>())
                        .add(name + " = " + (value == null ? null : term(value)));
                });
            }
        } finally {
            dataset.close();
        }
        rows.values().forEach(Collections::sort);
        return rows;
    }

    /** A literal's text, with its language tag when it has one. */
    private static String term(RDFNode value) {
        String lang = value.asLiteral().getLanguage();
        return value.asLiteral().getString() + (lang.isEmpty() ? "" : "@" + lang);
    }

    @Test
    public void wholeWordHitIsOneRowUnderTheField() {
        assertEquals(List.of(TITLE + " = Bore"), matches(config("", ""), "bore", "?field").get(NS + "bore"));
    }

    @Test
    public void prefixOnlyHitIsReportedUnderTheField() {
        assertEquals(List.of(TITLE + " = Borehole BH-12"),
            matches(config("", ""), "bore", "?field").get(NS + "borehole"));
    }

    /** Asking for the source field's matches includes those only its copy found. */
    @Test
    public void namingTheFieldReturnsItsPrefixOnlyHits() {
        Map<String, List<String>> rows = matches(config("", ""), "bore", "field:title");
        assertEquals(List.of(TITLE + " = Borehole BH-12"), rows.get(NS + "borehole"));
    }

    @Test
    public void namingTheCopyFieldReturnsNoRows() {
        assertEquals(Map.of(), matches(config("", ""), "bore", "field:titlePrefix"));
    }

    /** The value is the source field's own, so the copy field need not store it. */
    @Test
    public void copyFieldNeedNotBeStored() {
        assertEquals(List.of(TITLE + " = Borehole BH-12"),
            matches(config("", " ;\n    idx:stored false"), "bore", "?field").get(NS + "borehole"));
    }

    /**
     * Of a multi-valued field's values, the one the prefix matched. "Groundwater survey" is
     * not the first in byte order, so falling back to the first value would report
     * "Borehole log".
     */
    @Test
    public void multiValuedFieldReportsTheValueThePrefixMatched() {
        String multiValued = " ;\n    idx:multiValued true";
        assertEquals(List.of(TITLE + " = Groundwater survey"),
            matches(config(multiValued, multiValued), "groundw", "?field").get(NS + "logged"));
    }

    /** A hit found through a nested block has its rows in luc:nestedMatch, not luc:match. */
    @Test
    public void nestedMatchProjectsTheFieldOnce() {
        assertEquals(List.of(PART_NAME + " = Pump house"), nestedMatches(config("", ""), "pump").get(NS + "site"));
    }

    /**
     * The source field keeps the value's language tag and its copy does not, as the check
     * allows: the source field's own literal is projected, once, not the copy's untagged value
     * beside it.
     */
    @Test
    public void nestedMatchProjectsTheFieldsOwnLiteral() {
        String tagged = config("", "", " ;\n    idx:storeLiteralMetadata true", "ex:title");
        assertEquals(List.of(PART_NAME + " = Pump house@en"), nestedMatches(tagged, "pump").get(NS + "site"));
    }

    /**
     * "bore" matches the title's second value, "Bore", as a word, and its copy field's first,
     * "Borehole log", as a prefix. The field's own match decides the value, so the hit is
     * one row, not one per value the two picked.
     */
    @Test
    public void fieldsOwnMatchDecidesTheValue() {
        String multiValued = " ;\n    idx:multiValued true";
        String ordered = config(multiValued, multiValued, "", "[ sh:alternativePath ( ex:altTitle ex:title ) ]");
        assertEquals(List.of(TITLE + " = Bore"), matches(ordered, "bore", "?field").get(NS + "mixed"));
    }

    /**
     * The title keeps no value and its copy field does. A whole word matches both, and the
     * copy's value stands in for the title's, as it does for a prefix-only hit.
     */
    @Test
    public void storedCopyStandsInForAnUnstoredSource() {
        String unstoredTitle = config(" ;\n    idx:stored false", "");
        assertEquals(List.of(TITLE + " = Bore"), matches(unstoredTitle, "bor", "?field").get(NS + "bore"));
        assertEquals(List.of(TITLE + " = Bore"), matches(unstoredTitle, "bore", "?field").get(NS + "bore"));
    }
}
