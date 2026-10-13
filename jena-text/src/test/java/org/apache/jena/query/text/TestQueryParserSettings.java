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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.system.Txn;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code text:splitOnWhitespace} and {@code text:autoGeneratePhraseQueries}: how the query
 * parser treats a code with punctuation, such as {@code E45/5326}, across a text field that
 * splits it and an identifier field that keeps it whole.
 *
 * <p>Search text is written as a client sends it after escaping Lucene syntax, so
 * {@code E45/5326} arrives as {@code E45\/5326}. In the Java strings below each backslash
 * is doubled once for Java and once for the SPARQL string literal.
 */
public class TestQueryParserSettings {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final String NS = "http://example.org/";
    private static final String FIELD = "urn:jena:lucene:field#";

    private static final String SHAPES =
        "@prefix idx:   <urn:jena:lucene:index#> .\n"
        + "@prefix field: <" + FIELD + "> .\n"
        + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
        + "@prefix text:  <http://jena.apache.org/text#> .\n"
        + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
        + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
        + "@prefix ex:    <" + NS + "> .\n"
        + "field:label      idx:fieldName \"label\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n"
        + "field:identifier idx:fieldName \"identifier\" ; idx:fieldType idx:TextField ;"
        + " idx:defaultSearch true ; idx:analyzer [ a text:LowerCaseKeywordAnalyzer ] .\n"
        + "field:kind       idx:fieldName \"kind\" ; idx:fieldType idx:KeywordField ; idx:facetable true .\n"
        + "field:siteLabel  idx:fieldName \"siteLabel\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n"
        + "ex:RecordShape\n"
        + "    sh:targetClass ex:Record ;\n"
        + "    sh:property [ idx:field field:label ; sh:path ex:label ] ;\n"
        + "    sh:property [ idx:field field:identifier ; sh:path ex:identifier ] ;\n"
        + "    sh:property [ idx:field field:kind ; sh:path ex:kind ] ;\n"
        + "    idx:nested [\n"
        + "        idx:joinPath ex:site ;\n"
        + "        idx:property [ idx:field field:siteLabel ; sh:path ex:label ] ;\n"
        + "    ] .\n"
        + "ex:dataset rdf:type text:TextDataset ;\n"
        + "    text:dataset ex:base ;\n"
        + "    text:index ex:index .\n"
        + "ex:base rdf:type ja:MemoryDataset .\n";

    private Dataset dataset;

    @AfterEach
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    /** Open the dataset with {@code indexSettings} added to the index resource. */
    private void open(String indexSettings) {
        Model config = ModelFactory.createDefaultModel();
        config.read(new StringReader(SHAPES
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:directory \"mem\" ;\n"
            + indexSettings
            + "    text:shapes ( ex:RecordShape ) .\n"), null, "TTL");
        dataset = (Dataset) Assembler.general().open(config.getResource(NS + "dataset"));
        Txn.executeWrite(dataset, () -> {
            Model m = dataset.getDefaultModel();
            record(m, "tenement", "Tenement", "E45/5326", "E45/5326");
            record(m, "otherTenement", "Tenement", "E45/1000", "E45 Project");
            record(m, "report", "Report", "R-1", "Report on 5326 drillholes");
            Resource siteReport = record(m, "siteReport", "Report", "R-2", "Site survey");
            Resource site = m.createResource(NS + "site1");
            site.addProperty(m.createProperty(NS + "label"), "Site E45 east");
            siteReport.addProperty(m.createProperty(NS + "site"), site);
            record(m, "ferich", "Report", "R-3", "Fe-rich granite");
            record(m, "feApart", "Report", "R-4", "Fe deposit with rich ore");
            record(m, "sand", "Report", "R-5", "sand");
            record(m, "clay", "Report", "R-6", "clay");
            record(m, "spaced", "Tenement", "A 12", "Unrelated");
        });
    }

    private static Resource record(Model m, String id, String kind, String identifier, String label) {
        Resource r = m.createResource(NS + id);
        r.addProperty(RDF.type, m.createResource(NS + "Record"));
        r.addProperty(m.createProperty(NS + "kind"), kind);
        r.addProperty(m.createProperty(NS + "identifier"), identifier);
        r.addProperty(m.createProperty(NS + "label"), label);
        return r;
    }

    /** Local names of the entities {@code luc:query} returns for {@code qs}. */
    private Set<String> search(String qs) {
        String q = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?s WHERE { (?hit ?s) luc:query (\"default\" \"default\" \"" + qs + "\" \"\" \"\" 100 0) }";
        return Txn.calculateRead(dataset, () -> {
            Set<String> found = new TreeSet<>();
            try (QueryExecution qe = QueryExecution.dataset(dataset).query(q).build()) {
                qe.execSelect().forEachRemaining(row ->
                    found.add(row.getResource("s").getURI().substring(NS.length())));
            }
            return found;
        });
    }

    /** {@code kind} facet counts {@code luc:facet} returns for {@code qs}. */
    private Map<String, Integer> kindCounts(String qs) {
        String q = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?value ?count WHERE { (?field ?value ?low ?high ?count) luc:facet (\"default\" \"default\" \""
            + qs + "\" '[\"" + FIELD + "kind\"]' \"\" 0 1) }";
        return Txn.calculateRead(dataset, () -> {
            Map<String, Integer> counts = new TreeMap<>();
            try (QueryExecution qe = QueryExecution.dataset(dataset).query(q).build()) {
                qe.execSelect().forEachRemaining(row ->
                    counts.put(row.getLiteral("value").getLexicalForm(), row.getLiteral("count").getInt()));
            }
            return counts;
        });
    }

    // ---- Defaults: both settings on --------------------------------------------------

    @Test
    public void codeMatchesOnlyWhereItsPiecesAreAdjacent() {
        open("");
        // otherTenement has e45 alone, report has 5326 alone, siteReport has e45 in a site.
        assertEquals(Set.of("tenement"), search("E45\\\\/5326"));
    }

    @Test
    public void facetCountsUseTheSameParserSettings() {
        open("");
        assertEquals(Map.of("Tenement", 1), kindCounts("E45\\\\/5326"));
    }

    @Test
    public void hyphenatedWordMatchesOnlyAdjacentPieces() {
        open("");
        assertEquals(Set.of("ferich"), search("Fe\\\\-rich"));
    }

    @Test
    public void separateWordsStillMatchAnyWord() {
        open("");
        assertEquals(Set.of("clay", "sand"), search("sand clay"));
    }

    @Test
    public void codeContainingASpaceIsNotFoundWhole() {
        open("");
        assertEquals(Set.of(), search("A 12"));
    }

    @Test
    public void codeContainingAnEscapedSpaceIsFoundWhole() {
        open("");
        assertEquals(Set.of("spaced"), search("A\\\\ 12"));
    }

    @Test
    public void explicitTrueMatchesTheDefaults() {
        open("    text:splitOnWhitespace true ;\n    text:autoGeneratePhraseQueries true ;\n");
        assertEquals(Set.of("tenement"), search("E45\\\\/5326"));
        assertEquals(Set.of(), search("A 12"));
    }

    // ---- Both settings off: Lucene's own defaults ------------------------------------

    @Test
    public void bothOffMatchesEitherPieceOfACode() {
        open("    text:splitOnWhitespace false ;\n    text:autoGeneratePhraseQueries false ;\n");
        assertEquals(Set.of("otherTenement", "report", "siteReport", "tenement"), search("E45\\\\/5326"));
        assertEquals(Map.of("Report", 2, "Tenement", 2), kindCounts("E45\\\\/5326"));
    }

    @Test
    public void bothOffFindsACodeContainingASpaceWhole() {
        open("    text:splitOnWhitespace false ;\n    text:autoGeneratePhraseQueries false ;\n");
        assertEquals(Set.of("spaced"), search("A 12"));
    }

    // ---- One setting on --------------------------------------------------------------

    @Test
    public void splitOnlyKeepsEitherPieceMatching() {
        open("    text:autoGeneratePhraseQueries false ;\n");
        assertEquals(Set.of("otherTenement", "report", "siteReport", "tenement"), search("E45\\\\/5326"));
        assertEquals(Set.of(), search("A 12"));
    }

    @Test
    public void phrasesWithoutSplittingAreRejected() {
        AssemblerException ex = assertThrows(AssemblerException.class,
            () -> open("    text:splitOnWhitespace false ;\n"));
        assertTrue(rootMessage(ex).contains("text:autoGeneratePhraseQueries"), rootMessage(ex));
    }

    @Test
    public void nonBooleanSettingIsRejected() {
        AssemblerException ex = assertThrows(AssemblerException.class,
            () -> open("    text:splitOnWhitespace \"yes\" ;\n"));
        assertTrue(rootMessage(ex).contains("text:splitOnWhitespace"), rootMessage(ex));
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
