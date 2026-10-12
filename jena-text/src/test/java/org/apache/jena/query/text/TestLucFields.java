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
import org.apache.jena.graph.Node;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.system.Txn;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code luc:fields}: field values for each hit, read from the index.
 */
public class TestLucFields {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final String NS = "http://example.org/";
    private static final String FIELD = "urn:jena:lucene:field#";

    private static final String PREFIXES =
        "PREFIX luc:   <urn:jena:lucene:index#>\n"
        + "PREFIX field: <" + FIELD + ">\n"
        + "PREFIX ex:    <" + NS + ">\n";

    private static final String CONFIG =
        "@prefix idx:   <urn:jena:lucene:index#> .\n"
        + "@prefix field: <" + FIELD + "> .\n"
        + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
        + "@prefix text:  <http://jena.apache.org/text#> .\n"
        + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
        + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
        + "@prefix ex:    <" + NS + "> .\n"
        + "field:title   idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n"
        + "field:body    idx:fieldName \"body\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n"
        + "field:code    idx:fieldName \"code\" ; idx:fieldType idx:KeywordField ; idx:sortable true .\n"
        + "field:tags    idx:fieldName \"tags\" ; idx:fieldType idx:KeywordField ; idx:sortable true ;"
        + " idx:multiValued true .\n"
        + "field:status  idx:fieldName \"status\" ; idx:fieldType idx:KeywordField ; idx:stored false .\n"
        + "field:idType  idx:fieldName \"idType\" ; idx:fieldType idx:KeywordField .\n"
        + "field:idValue idx:fieldName \"idValue\" ; idx:fieldType idx:KeywordField ; idx:sortable true .\n"
        + "ex:DocShape\n"
        + "    sh:targetClass ex:Doc ;\n"
        + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
        + "    sh:property [ idx:field field:body ; sh:path ex:body ] ;\n"
        + "    sh:property [ idx:field field:code ; sh:path ex:code ] ;\n"
        + "    sh:property [ idx:field field:tags ; sh:path ex:tag ] ;\n"
        + "    sh:property [ idx:field field:status ; sh:path ex:status ] ;\n"
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

    private static final int FILLERS = 12;

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @BeforeEach
    public void setUp() {
        Model config = ModelFactory.createDefaultModel();
        config.read(new StringReader(CONFIG), null, "TTL");
        dataset = (Dataset) Assembler.general().open(config.getResource(NS + "dataset"));
        textIndex = (ShaclTextIndexLucene) ((DatasetGraphText) dataset.asDatasetGraph()).getTextIndex();

        // "alpha" matches e1 in title and e2 in body; "gamma" matches e3 alone.
        Txn.executeWrite(dataset, () -> {
            Model m = dataset.getDefaultModel();
            Resource e1 = addDoc(m, "e1", "alpha one", "common delta", "C3", "live", "red", "blue");
            addIdentifier(m, e1, "doi", "10/1");
            addDoc(m, "e2", "beta two", "common alpha", "C1", "live", "green");
            addDoc(m, "e3", "gamma three", "common beta", "C2", "live");
            for (int i = 0; i < FILLERS; i++) {
                addDoc(m, String.format("f%02d", i), "filler " + i, "common filler", String.format("F%02d", i), "draft");
            }
        });
    }

    @AfterEach
    public void tearDown() {
        if (textIndex != null) {
            textIndex.setHitReadObserver(null);
        }
        if (dataset != null) {
            dataset.close();
        }
    }

    private static Resource addDoc(Model m, String id, String title, String body, String code, String status,
                                   String... tags) {
        Resource r = m.createResource(NS + id);
        r.addProperty(RDF.type, m.createResource(NS + "Doc"));
        r.addProperty(m.createProperty(NS + "title"), title);
        r.addProperty(m.createProperty(NS + "body"), body);
        r.addProperty(m.createProperty(NS + "code"), code);
        r.addProperty(m.createProperty(NS + "status"), status);
        for (String tag : tags) {
            r.addProperty(m.createProperty(NS + "tag"), tag);
        }
        return r;
    }

    private static void addIdentifier(Model m, Resource doc, String type, String value) {
        Resource ident = m.createResource();
        ident.addProperty(m.createProperty(NS + "idType"), type);
        ident.addProperty(m.createProperty(NS + "idValue"), value);
        doc.addProperty(m.createProperty(NS + "identifier"), ident);
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

    private static String lex(Binding b, String var) {
        Node n = b.get(var);
        if (n == null) {
            return null;
        }
        return n.isURI() ? n.getURI() : n.isLiteral() ? n.getLiteralLexicalForm() : n.toString();
    }

    private static String luc(String qs, String filter, String sort, int limit) {
        return "(\"default\" \"default\" \"" + qs + "\" '" + filter + "' '" + sort + "' " + limit + " 0)";
    }

    private static final String LIVE =
        "{\"op\":\"=\",\"args\":[{\"property\":\"" + FIELD + "status\"},\"live\"]}";

    private static final String CODE_ASC = "[{\"field\":\"" + FIELD + "code\",\"order\":\"asc\"}]";

    /** Records every stored-field read and doc-value read. */
    private static final class Counter implements HitReadObserver {
        final List<Collection<String>> reads = new ArrayList<>();
        final List<String> docValues = new ArrayList<>();

        @Override
        public synchronized void storedFieldsRead(int docId, Collection<String> fieldNames) {
            reads.add(new ArrayList<>(fieldNames));
        }

        @Override
        public synchronized void docValuesRead(int docId, String fieldName) {
            docValues.add(fieldName);
        }
    }

    private Counter count() {
        Counter counter = new Counter();
        textIndex.setHitReadObserver(counter);
        return counter;
    }

    // ---- The identifier shape --------------------------------------------------------

    @Test
    public void identifierFromAFilterOnlySearchReadsNoStoredField() {
        Counter counter = count();
        List<Binding> rows = select(
            "(?hit ?score ?total ?rank) luc:query " + luc("", LIVE, CODE_ASC, 100) + " .\n"
            + "(?hit field:code ?id) luc:fields () .");
        List<String> ids = new ArrayList<>();
        List<String> ranks = new ArrayList<>();
        for (Binding b : rows) {
            ids.add(lex(b, "id"));
            ranks.add(lex(b, "rank"));
        }
        assertEquals(List.of("C1", "C2", "C3"), ids, "one row per hit, in rank order");
        assertEquals(List.of("0", "1", "2"), ranks);
        assertEquals(List.of(), counter.reads, "an eligible field is read from its doc value");
        assertEquals(3, counter.docValues.size());
    }

    @Test
    public void identifierValueIsAPlainString() {
        List<Binding> rows = select(
            "(?hit) luc:query " + luc("gamma", "", "", 10) + " .\n"
            + "(?hit field:code ?id) luc:fields () .");
        assertEquals(1, rows.size());
        Node id = rows.get(0).get("id");
        assertTrue(id.isLiteral());
        assertEquals("C2", id.getLiteralLexicalForm());
        assertEquals("", id.getLiteralLanguage());
    }

    // ---- Field slot ------------------------------------------------------------------

    @Test
    public void variableFieldSlotReturnsEveryReturnableRootField() {
        Set<String> rows = new TreeSet<>();
        for (Binding b : select(
                "(?hit ?entity) luc:query " + luc("alpha", "", "", 10) + " .\n"
                + "(?hit ?field ?value) luc:fields () .")) {
            rows.add(lex(b, "entity").substring(NS.length()) + " " + lex(b, "field") + "=" + lex(b, "value"));
        }
        // Not status (idx:stored false), not idType or idValue (nested).
        Set<String> expected = new TreeSet<>(List.of(
            "e1 " + FIELD + "title=alpha one",
            "e1 " + FIELD + "body=common delta",
            "e1 " + FIELD + "code=C3",
            "e1 " + FIELD + "tags=blue",
            "e1 " + FIELD + "tags=red",
            "e2 " + FIELD + "title=beta two",
            "e2 " + FIELD + "body=common alpha",
            "e2 " + FIELD + "code=C1",
            "e2 " + FIELD + "tags=green"));
        assertEquals(expected, rows);
    }

    @Test
    public void constantFieldSelectsOne() {
        List<Binding> rows = select(
            "(?hit ?entity) luc:query " + luc("alpha", "", "", 10) + " .\n"
            + "(?hit field:title ?value) luc:fields () .");
        Map<String, String> byEntity = new TreeMap<>();
        for (Binding b : rows) {
            byEntity.put(lex(b, "entity"), lex(b, "value"));
        }
        assertEquals(Map.of(NS + "e1", "alpha one", NS + "e2", "beta two"), byEntity);
    }

    @Test
    public void multiValuedFieldGivesOneRowPerValue() {
        Set<String> tags = new TreeSet<>();
        for (Binding b : select(
                "(?hit) luc:query " + luc("one", "", "", 10) + " .\n"
                + "(?hit field:tags ?tag) luc:fields () .")) {
            tags.add(lex(b, "tag"));
        }
        assertEquals(Set.of("red", "blue"), tags);
    }

    @Test
    public void twoConstantPatternsGiveOneRowPerHitWithBothColumns() {
        Map<String, String> rows = new TreeMap<>();
        for (Binding b : select(
                "(?hit ?entity) luc:query " + luc("common", "", "", 100) + " .\n"
                + "(?hit field:code ?code) luc:fields () .\n"
                + "(?hit field:title ?title) luc:fields () .")) {
            assertNull(rows.put(lex(b, "entity"), lex(b, "code") + "|" + lex(b, "title")), "one row per hit");
        }
        assertEquals(3 + FILLERS, rows.size());
        assertEquals("C2|gamma three", rows.get(NS + "e3"));
        assertEquals("F07|filler 7", rows.get(NS + "f07"));
    }

    @Test
    public void hitWithoutTheFieldHasNoRow() {
        List<Binding> rows = select(
            "(?hit) luc:query " + luc("gamma", "", "", 10) + " .\n"
            + "(?hit field:tags ?tag) luc:fields () .");
        assertEquals(0, rows.size());
    }

    // ---- With luc:match --------------------------------------------------------------

    @Test
    public void distinctVariablesWithLucMatchGiveThePerHitProduct() {
        List<Binding> rows = select(
            "(?hit ?entity) luc:query " + luc("alpha", "", "", 10) + " .\n"
            + "(?hit ?mField ?mValue) luc:match () .\n"
            + "(?hit ?field ?value) luc:fields () .");
        // e1: 1 match x 5 field values; e2: 1 match x 4 field values.
        assertEquals(9, rows.size());
        for (Binding b : rows) {
            assertNotNull(b.get("mField"));
            assertNotNull(b.get("field"));
        }
    }

    @Test
    public void sharedFieldAndValueWithLucMatchGiveCommonPairs() {
        Set<String> expected = Set.of(NS + "e1 " + FIELD + "title=alpha one", NS + "e2 " + FIELD + "body=common alpha");
        Set<String> matchFirst = new TreeSet<>();
        for (Binding b : select(
                "(?hit ?entity) luc:query " + luc("alpha", "", "", 10) + " .\n"
                + "(?hit ?field ?value) luc:match () .\n"
                + "(?hit ?field ?value) luc:fields () .")) {
            matchFirst.add(lex(b, "entity") + " " + lex(b, "field") + "=" + lex(b, "value"));
        }
        assertEquals(expected, matchFirst);

        Set<String> fieldsFirst = new TreeSet<>();
        for (Binding b : select(
                "(?hit ?entity) luc:query " + luc("alpha", "", "", 10) + " .\n"
                + "(?hit ?field ?value) luc:fields () .\n"
                + "(?hit ?field ?value) luc:match () .")) {
            fieldsFirst.add(lex(b, "entity") + " " + lex(b, "field") + "=" + lex(b, "value"));
        }
        assertEquals(expected, fieldsFirst);
    }

    // ---- Errors ----------------------------------------------------------------------

    private void assertQueryError(String fieldLocal, String where) {
        QueryExecException ex = assertThrows(QueryExecException.class, () -> select(where));
        assertTrue(ex.getMessage().contains(FIELD + fieldLocal), "names the field: " + ex.getMessage());
    }

    @Test
    public void unknownFieldRaises() {
        assertQueryError("nope", "(?hit) luc:query " + luc("alpha", "", "", 10) + " .\n"
            + "(?hit field:nope ?v) luc:fields () .");
    }

    @Test
    public void unstoredFieldRaises() {
        assertQueryError("status", "(?hit) luc:query " + luc("alpha", "", "", 10) + " .\n"
            + "(?hit field:status ?v) luc:fields () .");
    }

    @Test
    public void nestedFieldRaises() {
        assertQueryError("idValue", "(?hit) luc:query " + luc("alpha", "", "", 10) + " .\n"
            + "(?hit field:idValue ?v) luc:fields () .");
    }

    @Test
    public void readAfterARefreshRaises() {
        SearchExecution se = new SearchExecution(TextIndexRegistry.DEFAULT_ID, List.of("default"), "gamma",
            null, null, textIndex, null, null);
        se.retain();
        List<SearchHit> hits = new ArrayList<>(se.getSearchHits(0, 10));
        se.release();
        assertEquals(1, hits.size());
        ShaclIndexMapping.FieldDef code = textIndex.getShaclMapping().findField(FIELD + "code");

        // No commit in between: the late read succeeds.
        assertEquals("C2", se.fieldValues(hits.get(0), code).get(0).getLiteralLexicalForm());

        SearchExecution later = new SearchExecution(TextIndexRegistry.DEFAULT_ID, List.of("default"), "gamma",
            null, null, textIndex, null, null);
        later.retain();
        List<SearchHit> laterHits = new ArrayList<>(later.getSearchHits(0, 10));
        later.release();
        Txn.executeWrite(dataset, () -> addDoc(dataset.getDefaultModel(), "e9", "later", "later", "L9", "live"));
        assertThrows(QueryExecException.class, () -> later.fieldValues(laterHits.get(0), code));
    }

    @Test
    public void valuesAreReadOncePerField() {
        Counter counter = count();
        select("(?hit) luc:query " + luc("common", "", "", 100) + " .\n"
            + "(?hit field:title ?title) luc:fields () .");
        assertEquals(3 + FILLERS, counter.reads.size(), "one stored read per hit");
        for (Collection<String> fields : counter.reads) {
            assertEquals(List.of("title"), fields);
        }
    }
}
