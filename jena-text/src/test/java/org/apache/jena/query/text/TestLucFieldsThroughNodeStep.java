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
import org.apache.jena.rdf.model.*;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.system.Txn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The identifier case end to end: an identifier modelled as
 * {@code ex:identifier [ ex:propertyID ex:CollarId ; ex:value "..." ]} is indexed through
 * an {@code sh:node} step into a root sortable {@code KEYWORD} field, and
 * {@code luc:fields} returns it for each hit from the field's doc value.
 */
public class TestLucFieldsThroughNodeStep {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final String NS = "http://example.org/";
    private static final String FIELD = "urn:jena:lucene:field#";

    private static final String CONFIG = """
        @prefix idx:   <urn:jena:lucene:index#> .
        @prefix field: <urn:jena:lucene:field#> .
        @prefix sh:    <http://www.w3.org/ns/shacl#> .
        @prefix text:  <http://jena.apache.org/text#> .
        @prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .
        @prefix rdfs:  <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix ex:    <http://example.org/> .
        field:label idx:fieldName "label" ; idx:fieldType idx:TextField ; idx:defaultSearch true .
        field:collarId idx:fieldName "collarId" ; idx:fieldType idx:KeywordField ; idx:sortable true .
        ex:SiteShape sh:targetClass ex:Site ;
            sh:property [ sh:path rdfs:label ; idx:field field:label ] ;
            sh:property [
                sh:path ex:identifier ;
                sh:node [
                    sh:property [ sh:path ex:propertyID ; sh:hasValue ex:CollarId ] ;
                    sh:property [ sh:path ex:value ; idx:field field:collarId ] ;
                ] ;
            ] .
        ex:dataset a text:TextDataset ; text:dataset ex:base ; text:index ex:index .
        ex:base a ja:MemoryDataset .
        ex:index a text:TextIndexShacl ; text:directory "mem" ; text:shapes ( ex:SiteShape ) .
        """;

    private static final String DATA = """
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix ex:   <http://example.org/> .
        ex:site1 a ex:Site ; rdfs:label "site one" ;
            ex:identifier [ ex:propertyID ex:CollarId ; ex:value "C-3" ] ,
                          [ ex:propertyID ex:HoleId ; ex:value "A-1" ] .
        ex:site2 a ex:Site ; rdfs:label "site two" ;
            ex:identifier [ ex:propertyID ex:HoleId ; ex:value "A-2" ] .
        ex:site3 a ex:Site ; rdfs:label "site three" ;
            ex:identifier [ ex:propertyID ex:CollarId ; ex:value "C-1" ] .
        ex:site4 a ex:Site ; rdfs:label "site four" .
        """;

    private static final String PREFIXES = "PREFIX luc: <urn:jena:lucene:index#>\n"
        + "PREFIX field: <" + FIELD + ">\n";

    private static final String SEARCH = "(\"default\" \"default\" \"site\" '' "
        + "'[{\"field\":\"" + FIELD + "collarId\",\"order\":\"asc\"}]' 10 0)";

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    /** Records every stored-field read and doc-value read. */
    private static final class Counter implements HitReadObserver {
        final List<Collection<String>> storedReads = new ArrayList<>();
        final List<String> docValueReads = new ArrayList<>();

        @Override
        public synchronized void storedFieldsRead(int docId, Collection<String> fieldNames) {
            storedReads.add(new ArrayList<>(fieldNames));
        }

        @Override
        public synchronized void docValuesRead(int docId, String fieldName) {
            docValueReads.add(fieldName);
        }
    }

    @BeforeEach
    public void setUp() {
        Model config = ModelFactory.createDefaultModel();
        config.read(new StringReader(CONFIG), null, "TTL");
        dataset = (Dataset) Assembler.general().open(config.getResource(NS + "dataset"));
        textIndex = (ShaclTextIndexLucene) ((DatasetGraphText) dataset.asDatasetGraph()).getTextIndex();
        Txn.executeWrite(dataset, () -> dataset.getDefaultModel().read(new StringReader(DATA), null, "TTL"));
    }

    @AfterEach
    public void tearDown() {
        textIndex.setHitReadObserver(null);
        dataset.close();
    }

    private List<Binding> select(String where) {
        String q = PREFIXES + "SELECT * WHERE {\n" + where + "\n}";
        return Txn.calculateRead(dataset, () -> {
            try (QueryExecution qe = QueryExecution.dataset(dataset).query(q).build()) {
                List<Binding> rows = new ArrayList<>();
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    rows.add(rs.nextBinding());
                }
                return rows;
            }
        });
    }

    @Test
    public void stepFieldIsRootSortableAndHeldInDocValues() {
        ShaclIndexMapping.FieldDef collarId = textIndex.getShaclMapping().findFieldByName("collarId");
        assertNotNull(collarId);
        assertNull(textIndex.getShaclMapping().findNestedDefForFieldName("collarId"));
        assertTrue(collarId.isValueInDocValues(), "a sortable KEYWORD reached through a step is doc-value only");
    }

    @Test
    public void hitOnlySearchReturnsCollarIdsFromDocValues() {
        Counter counter = new Counter();
        textIndex.setHitReadObserver(counter);
        List<String> ids = new ArrayList<>();
        for (Binding b : select("(?hit) luc:query " + SEARCH + " .\n"
                + "(?hit field:collarId ?id) luc:fields () .")) {
            ids.add(b.get("id").getLiteralLexicalForm());
        }
        assertEquals(List.of("C-1", "C-3"), ids,
            "one row per hit with a CollarId, in sort order; no HoleId value, no row for the others");
        assertEquals(List.of(), counter.storedReads, "no stored field is read");
        assertFalse(counter.docValueReads.isEmpty());
        assertTrue(counter.docValueReads.stream().allMatch("collarId"::equals), counter.docValueReads.toString());
    }

    @Test
    public void entityAndCollarIdPair() {
        Counter counter = new Counter();
        textIndex.setHitReadObserver(counter);
        Map<String, String> byEntity = new TreeMap<>();
        Set<String> entities = new TreeSet<>();
        for (Binding b : select("(?hit ?entity) luc:query " + SEARCH + " .\n"
                + "OPTIONAL { (?hit field:collarId ?id) luc:fields () . }")) {
            String entity = b.get("entity").getURI().substring(NS.length());
            entities.add(entity);
            if (b.get("id") != null) {
                assertNull(byEntity.put(entity, b.get("id").getLiteralLexicalForm()), "one value per entity");
            }
        }
        assertEquals(Set.of("site1", "site2", "site3", "site4"), entities, "every site is a hit");
        assertEquals(Map.of("site1", "C-3", "site3", "C-1"), byEntity,
            "a site with only another identifier kind, or none, has no CollarId");
        for (Collection<String> read : counter.storedReads) {
            assertFalse(read.contains("collarId"), "collarId is never read from a stored field: " + read);
        }
    }
}
