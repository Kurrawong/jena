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
import java.util.HashSet;
import java.util.Set;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.Test;

/**
 * {@code text:defineAnalyzers} on a {@code text:TextIndexShacl}: the filters, tokenizers
 * and analyzers it names are what a field's {@code idx:analyzer} refers to.
 * <p>
 * The definitions live in a registry shared by the whole JVM, so every name here is one
 * no other test defines; a name defined elsewhere would make these pass by accident.
 */
public class TestShaclDefineAnalyzers {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final String NS = "http://example.org/shacl-define/";

    /** Splits on whitespace, then folds accents and case: "Café Racer" indexes as cafe, racer. */
    private static final String FOLDING_DEFINITIONS =
        "    [ text:defineTokenizer ex:whitespace ;\n"
        + "      text:tokenizer [ a text:GenericTokenizer ;\n"
        + "          text:class \"org.apache.lucene.analysis.core.WhitespaceTokenizer\" ] ]\n"
        + "    [ text:defineFilter ex:fold ;\n"
        + "      text:filter [ a text:GenericFilter ;\n"
        + "          text:class \"org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter\" ] ]\n";

    private static final String FOLDING_ANALYZER =
        "[ a text:ConfigurableAnalyzer ;\n"
        + "  text:tokenizer ex:whitespace ;\n"
        + "  text:filters ( ex:fold text:LowerCaseFilter ) ]";

    /**
     * One searchable title field.
     *
     * @param analyzer    the title's {@code idx:analyzer}
     * @param definitions the members of {@code text:defineAnalyzers}, or null for none
     */
    private static String config(String analyzer, String definitions) {
        return "@prefix idx:   <urn:jena:lucene:index#> .\n"
            + "@prefix field: <urn:jena:lucene:field#> .\n"
            + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix text:  <http://jena.apache.org/text#> .\n"
            + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
            + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
            + "@prefix ex:    <" + NS + "> .\n"
            + "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ;\n"
            + "    idx:defaultSearch true ;\n"
            + "    idx:analyzer " + analyzer + " .\n"
            + "ex:ThingShape\n"
            + "    sh:targetClass ex:Thing ;\n"
            + "    sh:property [ idx:field field:title ; sh:path ex:title ] .\n"
            + "ex:dataset rdf:type text:TextDataset ;\n"
            + "    text:dataset ex:base ;\n"
            + "    text:index ex:index .\n"
            + "ex:base rdf:type ja:MemoryDataset .\n"
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:directory \"mem\" ;\n"
            + (definitions == null ? "" : "    text:defineAnalyzers (\n" + definitions + "    ) ;\n")
            + "    text:shapes ( ex:ThingShape ) .\n";
    }

    private static Dataset open(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(turtle), null, "TTL");
        return (Dataset) Assembler.general().open(model.getResource(NS + "dataset"));
    }

    /** Index one "Café Racer" and return the entities {@code text} finds. */
    private static Set<String> search(String turtle, String text) {
        Dataset dataset = open(turtle);
        try {
            dataset.begin(ReadWrite.WRITE);
            try {
                dataset.getDefaultModel().read(new StringReader(
                    "<" + NS + "racer> a <" + NS + "Thing> ; <" + NS + "title> \"Café Racer\" ."), null, "TTL");
                dataset.commit();
            } finally {
                dataset.end();
            }
            String q = "PREFIX luc: <urn:jena:lucene:index#>\n"
                + "SELECT ?entity WHERE {\n"
                + "  (?hit ?entity) luc:query (\"default\" \"default\" \"" + text + "\" \"\" \"\" 10 0) .\n"
                + "}";
            Set<String> found = new HashSet<>();
            try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
                qe.execSelect().forEachRemaining(row -> found.add(row.getResource("entity").getURI()));
            }
            return found;
        } finally {
            dataset.close();
        }
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    @Test
    public void definedTokenizerAndFilterReachAFieldsAnalyzer() {
        assertEquals(Set.of(NS + "racer"),
            search(config(FOLDING_ANALYZER, FOLDING_DEFINITIONS), "cafe"));
    }

    @Test
    public void definedAnalyzerResolvesOnAField() {
        String definitions = FOLDING_DEFINITIONS
            + "    [ text:defineAnalyzer ex:folding ;\n"
            + "      text:analyzer " + FOLDING_ANALYZER + " ]\n";
        assertEquals(Set.of(NS + "racer"),
            search(config("[ a text:DefinedAnalyzer ; text:useAnalyzer ex:folding ]", definitions), "cafe"));
    }

    /** Before definitions were read, the field silently fell back to the default analyzer. */
    @Test
    public void undefinedDefinedAnalyzerIsRejected() {
        AssemblerException ex = assertThrows(AssemblerException.class,
            () -> open(config("[ a text:DefinedAnalyzer ; text:useAnalyzer ex:neverDefined ]", null)));
        assertTrue(rootMessage(ex).contains(NS + "neverDefined"), rootMessage(ex));
    }

    @Test
    public void addLangIsRejected() {
        String definitions =
            "    [ text:addLang \"qaa-x-shacl\" ; text:analyzer [ a text:StandardAnalyzer ] ]\n";
        AssemblerException ex = assertThrows(AssemblerException.class,
            () -> open(config("[ a text:StandardAnalyzer ]", definitions)));
        assertTrue(rootMessage(ex).contains("text:addLang"), rootMessage(ex));
    }
}
