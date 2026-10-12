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
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.system.Txn;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.search.IndexSearcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What {@code luc:query} reads per hit, and how {@code luc:match} and
 * {@code luc:nestedMatch} find the hit they are given.
 * <p>
 * {@code luc:query} reads the entity IRI only when the query binds it, computes match
 * details only when {@code luc:match} asks, and holds its searcher until its iterator
 * closes so those reads can happen later. A skipped read has no other observable result,
 * so reads are counted through {@link HitReadObserver}.
 */
public class TestLucQueryHitPath {

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
        + "field:idType  idx:fieldName \"idType\" ; idx:fieldType idx:KeywordField .\n"
        + "field:idValue idx:fieldName \"idValue\" ; idx:fieldType idx:KeywordField .\n"
        + "ex:DocShape\n"
        + "    sh:targetClass ex:Doc ;\n"
        + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
        + "    sh:property [ idx:field field:body ; sh:path ex:body ] ;\n"
        + "    sh:property [ idx:field field:code ; sh:path ex:code ] ;\n"
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

    /** Hits "common": the three documents below and the fillers. */
    private static final int FILLERS = 22;
    private static final int COMMON_HITS = 3 + FILLERS;

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @BeforeEach
    public void setUp() {
        Model config = ModelFactory.createDefaultModel();
        config.read(new StringReader(CONFIG), null, "TTL");
        dataset = (Dataset) Assembler.general().open(config.getResource(NS + "dataset"));
        textIndex = (ShaclTextIndexLucene) ((DatasetGraphText) dataset.asDatasetGraph()).getTextIndex();

        // "delta" ranks e1 (three times), e3 (twice), e2 (once) in bodies of similar length.
        // "alpha" matches e1 in title and e2 in body; "gamma" matches e3 alone.
        Txn.executeWrite(dataset, () -> {
            Model m = dataset.getDefaultModel();
            Resource e1 = addDoc(m, "e1", "alpha one", "common delta delta delta", "C3");
            addIdentifier(m, e1, "doi", "10/1");
            addIdentifier(m, e1, "isbn", "978-1");
            Resource e2 = addDoc(m, "e2", "beta two", "common alpha delta", "C1");
            addIdentifier(m, e2, "doi", "10/2");
            addDoc(m, "e3", "gamma three", "common beta delta delta", "C2");
            for (int i = 0; i < FILLERS; i++) {
                addDoc(m, String.format("f%02d", i), "filler " + i, "common filler", String.format("F%02d", i));
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

    private static Resource addDoc(Model m, String id, String title, String body, String code) {
        Resource r = m.createResource(NS + id);
        r.addProperty(RDF.type, m.createResource(NS + "Doc"));
        r.addProperty(m.createProperty(NS + "title"), title);
        r.addProperty(m.createProperty(NS + "body"), body);
        r.addProperty(m.createProperty(NS + "code"), code);
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

    private static Node ex(String local) {
        return NodeFactory.createURI(NS + local);
    }

    private static String luc(String qs, String filter, String sort, int limit) {
        return "(\"default\" \"default\" \"" + qs + "\" '" + filter + "' '" + sort + "' " + limit + " 0)";
    }

    /** Records every stored-field read and match computation. */
    private static final class Counter implements HitReadObserver {
        final List<Collection<String>> reads = new ArrayList<>();
        int matches;

        @Override
        public synchronized void storedFieldsRead(int docId, Collection<String> fieldNames) {
            reads.add(new ArrayList<>(fieldNames));
        }

        @Override
        public synchronized void matchesComputed(int docId) {
            matches++;
        }
    }

    private Counter count() {
        Counter counter = new Counter();
        textIndex.setHitReadObserver(counter);
        return counter;
    }

    // ---- Entity IRI only when bound ------------------------------------------------

    @Test
    public void hitOnlyReadsNoStoredField() {
        Counter counter = count();
        List<Binding> rows = select("(?hit) luc:query " + luc("common", "", "", 100) + " .");
        assertEquals(COMMON_HITS, rows.size());
        assertEquals(List.of(), counter.reads, "(?hit) alone must read no stored field");
    }

    @Test
    public void entityReadsTheEntityFieldOnly() {
        Counter counter = count();
        List<Binding> rows = select("(?hit ?entity) luc:query " + luc("common", "", "", 100) + " .");
        assertEquals(COMMON_HITS, rows.size());
        String entityField = textIndex.getDocDef().getEntityField();
        assertEquals(COMMON_HITS, counter.reads.size(), "one read per hit");
        for (Collection<String> fields : counter.reads) {
            assertEquals(List.of(entityField), fields);
        }
    }

    @Test
    public void entityUnderRelevanceKeepsRankOrder() {
        List<Binding> rows = select("(?hit ?entity ?score ?total ?rank) luc:query " + luc("delta", "", "", 10) + " .");
        List<Node> entities = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            entities.add(rows.get(i).get("entity"));
            assertEquals(String.valueOf(i), lex(rows.get(i), "rank"));
        }
        assertEquals(List.of(ex("e1"), ex("e3"), ex("e2")), entities);
    }

    @Test
    public void entityUnderSortKeepsSortOrder() {
        String asc = "[{\"field\":\"" + FIELD + "code\",\"order\":\"asc\"}]";
        String desc = "[{\"field\":\"" + FIELD + "code\",\"order\":\"desc\"}]";
        List<Node> ascending = new ArrayList<>();
        for (Binding b : select("(?hit ?entity) luc:query " + luc("delta", "", asc, 10) + " .")) {
            ascending.add(b.get("entity"));
        }
        List<Node> descending = new ArrayList<>();
        for (Binding b : select("(?hit ?entity) luc:query " + luc("delta", "", desc, 10) + " .")) {
            descending.add(b.get("entity"));
        }
        assertEquals(List.of(ex("e2"), ex("e3"), ex("e1")), ascending);
        assertEquals(List.of(ex("e1"), ex("e3"), ex("e2")), descending);
    }

    @Test
    public void constantEntityFilters() {
        List<Binding> rows = select("(?hit ex:e3 ?score) luc:query " + luc("delta", "", "", 10) + " .");
        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).get("hit"));
    }

    @Test
    public void entityReadAfterAnEarlierSearchThatDidNotBindIt() {
        // Same key, so one shared search: the first branch reads no IRI, the second needs it.
        List<Binding> rows = select(
            "{ (?h1) luc:query " + luc("delta", "", "", 10) + " . }\n"
            + "UNION\n"
            + "{ (?h2 ?entity) luc:query " + luc("delta", "", "", 10) + " . }");
        List<Node> entities = new ArrayList<>();
        for (Binding b : rows) {
            if (b.get("entity") != null) {
                entities.add(b.get("entity"));
            }
        }
        assertEquals(6, rows.size());
        assertEquals(List.of(ex("e1"), ex("e3"), ex("e2")), entities);
    }

    // ---- Match details only when luc:match is used ---------------------------------

    @Test
    public void noMatchesComputedWithoutLucMatch() {
        Counter counter = count();
        List<Binding> rows = select("(?hit ?entity) luc:query " + luc("alpha", "", "", 10) + " .");
        assertEquals(2, rows.size());
        assertEquals(0, counter.matches, "no luc:match, so no match details");
    }

    @Test
    public void lucMatchReadsOnlyMatchedFields() {
        Counter counter = count();
        List<Binding> rows = select(
            "(?hit ?entity) luc:query " + luc("alpha", "", "", 10) + " .\n"
            + "(?hit ?field ?value) luc:match () .");
        assertEquals(2, rows.size());
        for (Collection<String> fields : counter.reads) {
            assertFalse(fields.contains("code"), "decoded an unmatched field: " + fields);
        }
    }

    @Test
    public void lucMatchRowsUnchanged() {
        Map<String, String> byEntity = new TreeMap<>();
        for (Binding b : select(
                "(?hit ?entity) luc:query " + luc("alpha", "", "", 10) + " .\n"
                + "(?hit ?field ?value) luc:match () .")) {
            byEntity.put(lex(b, "entity"), lex(b, "field") + "=" + lex(b, "value"));
        }
        assertEquals(Map.of(
            NS + "e1", FIELD + "title=alpha one",
            NS + "e2", FIELD + "body=common alpha delta"), byEntity);
    }

    // ---- Hit lookup ----------------------------------------------------------------

    @Test
    public void twoSearchesEachResolveTheirOwnHits() {
        List<Binding> rows = select(
            "(?h1 ?e1) luc:query " + luc("alpha", "", "", 10) + " .\n"
            + "(?h1 ?f1 ?v1) luc:match () .\n"
            + "(?h2 ?e2) luc:query " + luc("gamma", "", "", 10) + " .\n"
            + "(?h2 ?f2 ?v2) luc:match () .");
        assertEquals(2, rows.size());
        for (Binding b : rows) {
            assertEquals(NS + "e3", lex(b, "e2"));
            assertEquals(FIELD + "title", lex(b, "f2"));
            assertEquals("gamma three", lex(b, "v2"));
            if (lex(b, "e1").equals(NS + "e1")) {
                assertEquals("alpha one", lex(b, "v1"));
            } else {
                assertEquals("common alpha delta", lex(b, "v1"));
            }
        }
    }

    @Test
    public void constantFieldInLucMatchFilters() {
        List<Binding> rows = select(
            "(?hit ?entity) luc:query " + luc("alpha", "", "", 10) + " .\n"
            + "(?hit field:title ?value) luc:match () .");
        assertEquals(1, rows.size());
        assertEquals(NS + "e1", lex(rows.get(0), "entity"));
        assertEquals("alpha one", lex(rows.get(0), "value"));
    }

    @Test
    public void constantFieldInNestedMatchFilters() {
        String doi = "{\"op\":\"=\",\"args\":[{\"property\":\"" + FIELD + "idType\"},\"doi\"]}";
        Set<String> rows = new TreeSet<>();
        for (Binding b : select(
                "(?hit ?entity) luc:query " + luc("", doi, "", 10) + " .\n"
                + "(?hit ?record field:idValue ?value) luc:nestedMatch () .")) {
            assertNotNull(b.get("record"));
            rows.add(lex(b, "entity") + " " + lex(b, "value"));
        }
        assertEquals(Set.of(NS + "e1 10/1", NS + "e2 10/2"), rows);
    }

    // ---- Negative limit ------------------------------------------------------------

    @Test
    public void negativeLimitReturnsEveryHit() {
        assertEquals(COMMON_HITS, select("(?hit) luc:query " + luc("common", "", "", -1) + " .").size());
    }

    @Test
    public void zeroLimitReturnsTheDefaultPage() {
        assertEquals(10, select("(?hit) luc:query " + luc("common", "", "", 0) + " .").size());
    }

    // ---- Held searcher -------------------------------------------------------------

    /** References to the current reader beyond the SearcherManager's own. */
    private int readerRefs() {
        IndexSearcher searcher = textIndex.acquireSearcher();
        try {
            // Minus the one just taken.
            return searcher.getIndexReader().getRefCount() - 1;
        } finally {
            textIndex.releaseSearcher(searcher);
        }
    }

    private static final String MANY_HITS = "(?hit ?entity) luc:query (\"default\" \"default\" \"common\" \"\" \"\" -1 0) .";

    @Test
    public void searcherHeldWhileIteratingAndReleasedWhenFinished() {
        int before = readerRefs();
        Txn.executeRead(dataset, () -> {
            try (QueryExecution qe = QueryExecution.dataset(dataset).query(PREFIXES + "SELECT * { " + MANY_HITS + " }").build()) {
                ResultSet rs = qe.execSelect();
                rs.next();
                assertEquals(before + 1, readerRefs(), "the search holds one reference while its iterator is open");
                while (rs.hasNext()) {
                    rs.next();
                }
            }
        });
        assertEquals(before, readerRefs());
    }

    @Test
    public void searcherReleasedWhenQueryIsAbandoned() {
        int before = readerRefs();
        Txn.executeRead(dataset, () -> {
            try (QueryExecution qe = QueryExecution.dataset(dataset).query(PREFIXES + "SELECT * { " + MANY_HITS + " }").build()) {
                ResultSet rs = qe.execSelect();
                rs.next();
                assertEquals(before + 1, readerRefs());
            }
        });
        assertEquals(before, readerRefs());
    }

    @Test
    public void searcherReleasedWhenQueryIsCancelled() {
        int before = readerRefs();
        Txn.executeRead(dataset, () -> {
            try (QueryExecution qe = QueryExecution.dataset(dataset).query(PREFIXES + "SELECT * { " + MANY_HITS + " }").build()) {
                ResultSet rs = qe.execSelect();
                rs.next();
                assertEquals(before + 1, readerRefs());
                qe.abort();
                assertThrows(QueryCancelledException.class, () -> {
                    while (rs.hasNext()) {
                        rs.next();
                    }
                });
            }
        });
        assertEquals(before, readerRefs());
    }

    @Test
    public void closingAQueryAfterTheIndexClosedDoesNotFail() {
        Txn.executeRead(dataset, () -> {
            QueryExecution qe = QueryExecution.dataset(dataset).query(PREFIXES + "SELECT * { " + MANY_HITS + " }").build();
            ResultSet rs = qe.execSelect();
            rs.next();
            textIndex.close();
            assertDoesNotThrow(qe::close);
        });
    }

    /**
     * The sorted subquery drains luc:query, whose iterator closes and releases the
     * searcher, before luc:match runs: luc:match reads against a newly acquired searcher
     * after checking the reader is unchanged.
     */
    @Test
    public void lucMatchAfterLucQueryClosedGivesTheSameRows() {
        String query = "(?hit ?entity) luc:query " + luc("alpha", "", "", 10) + " .";
        String match = "(?hit ?field ?value) luc:match () .";
        Set<String> inline = new TreeSet<>();
        for (Binding b : select(query + "\n" + match)) {
            inline.add(lex(b, "entity") + " " + lex(b, "field") + " " + lex(b, "value"));
        }
        Set<String> grouped = new TreeSet<>();
        for (Binding b : select("{ SELECT * { " + query + " } ORDER BY ?entity }\n" + match)) {
            grouped.add(lex(b, "entity") + " " + lex(b, "field") + " " + lex(b, "value"));
        }
        assertEquals(2, inline.size());
        assertEquals(inline, grouped);
    }

    // ---- Late reads against SearchExecution -----------------------------------------

    private SearchExecution search(String qs) {
        return new SearchExecution(TextIndexRegistry.DEFAULT_ID, List.of("default"), qs, null, null,
            textIndex, null, null);
    }

    private void addLater(String id, String title) {
        Txn.executeWrite(dataset, () -> addDoc(dataset.getDefaultModel(), id, title, "later", "L"));
    }

    @Test
    public void lateReadWithoutCommitSucceeds() {
        SearchExecution se = search("gamma");
        se.retain();
        List<SearchHit> hits = new ArrayList<>(se.getSearchHits(0, 10));
        se.release();
        assertEquals(1, hits.size());
        List<FieldMatch> matches = se.fieldMatches(hits.get(0));
        assertEquals(1, matches.size());
        assertEquals(FIELD + "title", matches.get(0).getFieldIRI().getURI());
        assertEquals("gamma three", matches.get(0).getValue().getLiteralLexicalForm());
    }

    @Test
    public void lateReadAfterCommitRaises() {
        SearchExecution se = search("gamma");
        se.retain();
        List<SearchHit> hits = new ArrayList<>(se.getSearchHits(0, 10));
        se.release();
        addLater("e4", "gamma four");
        assertThrows(QueryExecException.class, () -> se.fieldMatches(hits.get(0)));
    }

    @Test
    public void windowRerunDiscardsValuesReadAgainstTheOldSearcher() {
        SearchExecution se = search("gamma");
        se.retain();
        SearchHit first = se.getSearchHits(0, 1).get(0);
        assertEquals("gamma three", se.fieldMatches(first).get(0).getValue().getLiteralLexicalForm());
        se.release();

        // Outranks e3: two occurrences in a shorter title.
        addLater("e5", "gamma gamma");

        se.retain();
        try {
            List<SearchHit> wide = se.getSearchHits(0, 10);
            assertEquals(2, wide.size());
            SearchHit top = se.findHit(wide.get(0).getHitId());
            assertNotNull(top);
            assertEquals("gamma gamma", se.fieldMatches(top).get(0).getValue().getLiteralLexicalForm());
            SearchHit second = se.findHit(wide.get(1).getHitId());
            assertEquals("gamma three", se.fieldMatches(second).get(0).getValue().getLiteralLexicalForm());
        } finally {
            se.release();
        }
    }
}
