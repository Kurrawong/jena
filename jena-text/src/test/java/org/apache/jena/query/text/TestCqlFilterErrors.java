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

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A CQL filter the index cannot run is the request's fault, so {@code luc:query} and
 * {@code luc:facet} raise it as a {@link QueryParseException}. Fuseki answers that with a
 * 400 even when it is raised during execution ({@code SPARQLQueryProcessor}, "late stage
 * static error"), where any other exception is a 500.
 */
public class TestCqlFilterErrors {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final String NS = "http://example.org/";

    private static final String CONFIG = """
        @prefix idx:   <urn:jena:lucene:index#> .
        @prefix field: <urn:jena:lucene:field#> .
        @prefix sh:    <http://www.w3.org/ns/shacl#> .
        @prefix text:  <http://jena.apache.org/text#> .
        @prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .
        @prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix ex:    <http://example.org/> .

        field:title idx:fieldName "title" ; idx:fieldType idx:TextField ; idx:defaultSearch true .
        field:kind  idx:fieldName "kind"  ; idx:fieldType idx:KeywordField ; idx:facetable true .
        field:made  idx:fieldName "made"  ; idx:fieldType idx:TemporalField ; idx:storeLiteralMetadata true .
        ex:ThingShape sh:targetClass ex:Thing ;
            sh:property [ idx:field field:title ; sh:path ex:title ] ;
            sh:property [ idx:field field:kind  ; sh:path ex:kind ] ;
            sh:property [ idx:field field:made  ; sh:path ex:made ] .

        ex:dataset rdf:type text:TextDataset ; text:dataset ex:base ; text:index ex:index .
        ex:base rdf:type ja:MemoryDataset .
        ex:index rdf:type text:TextIndexShacl ; text:directory "mem" ; text:shapes ( ex:ThingShape ) .
        """;

    /** Names a keyword field, which a temporal operator cannot be applied to. */
    private static final String TEMPORAL_ON_KEYWORD =
        "{\\\"op\\\": \\\"t_after\\\", \\\"args\\\": [{\\\"property\\\": \\\"urn:jena:lucene:field#kind\\\"}, "
        + "{\\\"date\\\": \\\"2020-01-01\\\"}]}";

    private Dataset dataset;

    @BeforeEach
    public void setUp() {
        Model config = ModelFactory.createDefaultModel();
        config.read(new StringReader(CONFIG), null, "TTL");
        dataset = (Dataset) Assembler.general().open(config.getResource(NS + "dataset"));
        dataset.begin(ReadWrite.WRITE);
        try {
            dataset.getDefaultModel().read(new StringReader("""
                @prefix ex:  <http://example.org/> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                ex:a a ex:Thing ; ex:title "red apple" ; ex:kind "fruit" ; ex:made "2021-06-30"^^xsd:date .
                """), null, "TTL");
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    @AfterEach
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    @Test
    public void lucQueryRaisesAFilterItCannotRunAsAParseError() {
        QueryParseException ex = assertThrows(QueryParseException.class, () -> select(
            "(?hit ?s) luc:query (\"default\" \"default\" \"*\" \"" + TEMPORAL_ON_KEYWORD + "\" \"\" 10 0)"));
        assertTrue(ex.getMessage().contains("TemporalField"), ex.getMessage());
    }

    @Test
    public void lucFacetRaisesAFilterItCannotRunAsAParseError() {
        QueryParseException ex = assertThrows(QueryParseException.class, () -> select(
            "(?field ?value ?low ?high ?count) luc:facet (\"default\" \"default\" \"*\" "
            + "'[\"urn:jena:lucene:field#kind\"]' \"" + TEMPORAL_ON_KEYWORD + "\" 10 0)"));
        assertTrue(ex.getMessage().contains("TemporalField"), ex.getMessage());
    }

    @Test
    public void malformedFilterJsonIsAParseError() {
        assertThrows(QueryParseException.class, () -> select(
            "(?hit ?s) luc:query (\"default\" \"default\" \"*\" \"{not json\" \"\" 10 0)"));
    }

    private void select(String pattern) {
        String q = "PREFIX luc: <urn:jena:lucene:index#>\nSELECT * WHERE { " + pattern + " }";
        try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
            ResultSetFormatter.consume(qe.execSelect());
        }
    }
}
