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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.system.Txn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two equality clauses on fields of one {@code idx:nested} block, declared in Turtle,
 * must hold for the same child record. Each record here has two identifiers, and no
 * single identifier carries both the scheme and the value a cross-child filter asks for.
 */
public class TestNestedSameChildFromTurtle {

    private static final String SHAPES = String.join("\n",
        "field:entityType idx:fieldName \"entityType\" ; idx:fieldType idx:KeywordField ;",
        "    idx:stored false ; idx:multiValued true .",
        "field:identifierScheme idx:fieldName \"identifierScheme\" ; idx:fieldType idx:KeywordField ; idx:stored false .",
        "field:identifierValue idx:fieldName \"identifierValue\" ; idx:fieldType idx:KeywordField ; idx:stored false .",
        "ex:Shape sh:targetClass schema:Dataset ;",
        "    sh:property [ idx:field field:entityType ; sh:path rdf:type ] ;",
        "    idx:nested [",
        "        idx:joinPath schema:identifier ;",
        "        idx:property [ idx:field field:identifierScheme ; sh:path schema:propertyID ] ;",
        "        idx:property [ idx:field field:identifierValue ; sh:path schema:value ] ] .");

    private static final String DATA = String.join("\n",
        "<http://example.org/r1> a <https://schema.org/Dataset> ;",
        "    <https://schema.org/identifier>",
        "        [ <https://schema.org/propertyID> <urn:scheme:handle> ; <https://schema.org/value> \"1/1\" ] ,",
        "        [ <https://schema.org/propertyID> <urn:scheme:nt> ; <https://schema.org/value> \"CR1\" ] .");

    private Dataset dataset;

    @BeforeEach
    public void setUp() {
        ShaclTextIndexLucene index = ShaclTurtleFixture.openIndex(SHAPES);
        dataset = ShaclTurtleFixture.liveDataset(index);
        Txn.executeWrite(dataset, () ->
            RDFParser.fromString(DATA, Lang.TURTLE).parse(dataset.getDefaultModel()));
    }

    private static String and(String scheme, String value, String prefix) {
        return "{\"op\":\"and\",\"args\":["
            + "{\"op\":\"=\",\"args\":[{\"property\":\"" + prefix + "identifierScheme\"},\"" + scheme + "\"]},"
            + "{\"op\":\"=\",\"args\":[{\"property\":\"" + prefix + "identifierValue\"},\"" + value + "\"]}]}";
    }

    private List<String> entities(String filter) {
        String q = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?e WHERE { (?h ?e) luc:query (\"default\" \"default\" \"\" '" + filter + "' \"\" 10 0) }";
        return Txn.calculateRead(dataset, () -> {
            try (QueryExecution qe = QueryExecution.dataset(dataset).query(q).build()) {
                List<String> out = new ArrayList<>();
                ResultSet rs = qe.execSelect();
                rs.forEachRemaining(row -> out.add(row.get("e").toString()));
                return out;
            }
        });
    }

    @Test
    public void shortNamesMatchSameChildOnly() {
        assertEquals(List.of("http://example.org/r1"), entities(and("urn:scheme:handle", "1/1", "")));
        assertEquals(List.of(), entities(and("urn:scheme:handle", "CR1", "")));
    }

    @Test
    public void fieldIrisMatchSameChildOnly() {
        String p = "urn:jena:lucene:field#";
        assertEquals(List.of("http://example.org/r1"), entities(and("urn:scheme:handle", "1/1", p)));
        assertEquals(List.of(), entities(and("urn:scheme:handle", "CR1", p)));
    }
}
