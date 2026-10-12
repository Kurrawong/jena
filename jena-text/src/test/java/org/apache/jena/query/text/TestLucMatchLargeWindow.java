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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.StringReader;
import java.time.Duration;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.system.Txn;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * {@code luc:match} and {@code luc:nestedMatch} over 300,000 hits finish in time linear in
 * the number of hits.
 * <p>
 * Each incoming {@code ?hit} used to scan every cached hit, which is 4.5 × 10<sup>10</sup>
 * comparisons at this size and does not finish within the timeout. The timeout is
 * generous for a linear join, so it fails only on the quadratic one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestLucMatchLargeWindow {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final int HITS = 300_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private static final String NS = "http://example.org/";
    private static final String FIELD = "urn:jena:lucene:field#";

    private static final String CONFIG =
        "@prefix idx:   <urn:jena:lucene:index#> .\n"
        + "@prefix field: <" + FIELD + "> .\n"
        + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
        + "@prefix text:  <http://jena.apache.org/text#> .\n"
        + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
        + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
        + "@prefix ex:    <" + NS + "> .\n"
        + "field:title   idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n"
        + "field:idType  idx:fieldName \"idType\" ; idx:fieldType idx:KeywordField .\n"
        + "field:idValue idx:fieldName \"idValue\" ; idx:fieldType idx:KeywordField .\n"
        + "ex:DocShape\n"
        + "    sh:targetClass ex:Doc ;\n"
        + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
        + "    idx:nested [\n"
        + "        idx:joinPath ex:identifier ;\n"
        + "        idx:property [ idx:field field:idType ; sh:path ex:idType ] ;\n"
        + "        idx:property [ idx:field field:idValue ; sh:path ex:idValue ] ;\n"
        + "    ] .\n"
        + "ex:dataset rdf:type text:TextDataset ;\n"
        + "    text:dataset ex:base ;\n"
        + "    text:index ex:index .\n"
        + "ex:base rdf:type ja:MemoryDataset .\n"
        + "ex:index rdf:type text:TextIndexShacl ;\n"
        + "    text:directory \"mem\" ;\n"
        + "    text:storeValues true ;\n"
        + "    text:shapes ( ex:DocShape ) .\n";

    private static final String PREFIXES = "PREFIX luc: <urn:jena:lucene:index#>\n";

    private Dataset dataset;

    @BeforeAll
    public void buildIndex() {
        Model config = ModelFactory.createDefaultModel();
        config.read(new StringReader(CONFIG), null, "TTL");
        dataset = (Dataset) Assembler.general().open(config.getResource(NS + "dataset"));
        ShaclTextIndexLucene textIndex =
            (ShaclTextIndexLucene) ((DatasetGraphText) dataset.asDatasetGraph()).getTextIndex();

        // Written straight to the index: the base dataset plays no part in luc:query,
        // luc:match or luc:nestedMatch, and building 300,000 entities through it is slow.
        ShaclIndexMapping.IndexProfile profile = textIndex.getShaclMapping().getProfiles().get(0);
        String scope = profile.getNestedDefs().get(0).getNestedName();
        for (int i = 0; i < HITS; i++) {
            Entity entity = new Entity(NS + "e" + i, null);
            entity.put("title", "common entity " + i);
            Entity.NestedRecord record = new Entity.NestedRecord();
            record.addValue("idType", "doi");
            record.addValue("idValue", "10/" + i);
            entity.addNestedRecord(scope, record);
            textIndex.updateEntityForProfile(entity, profile, true);
        }
        textIndex.commit();
    }

    @AfterAll
    public void close() {
        if (dataset != null) {
            dataset.close();
        }
    }

    private long count(String where) {
        String q = PREFIXES + "SELECT * WHERE {\n" + where + "\n}";
        return Txn.calculateRead(dataset, () -> {
            try (QueryExecution qe = QueryExecution.dataset(dataset).query(q).build()) {
                long rows = 0;
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    rs.next();
                    rows++;
                }
                return rows;
            }
        });
    }

    @Test
    public void lucMatchIsLinear() {
        long rows = assertTimeoutPreemptively(TIMEOUT, () -> count(
            "(?hit ?entity) luc:query (\"default\" \"default\" \"common\" \"\" \"\" " + HITS + " 0) .\n"
            + "(?hit ?field ?value) luc:match () ."));
        assertEquals(HITS, rows);
    }

    @Test
    public void lucNestedMatchIsLinear() {
        String doi = "{\"op\":\"=\",\"args\":[{\"property\":\"" + FIELD + "idType\"},\"doi\"]}";
        long rows = assertTimeoutPreemptively(TIMEOUT, () -> count(
            "(?hit) luc:query (\"default\" \"default\" \"\" '" + doi + "' \"\" " + HITS + " 0) .\n"
            + "(?hit ?record ?field ?value) luc:nestedMatch () ."));
        // Two stored fields on the one matching child.
        assertEquals(2L * HITS, rows);
    }
}
