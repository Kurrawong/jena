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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.system.Txn;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.SortedSetSortField;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ordering ties by entity IRI, and cursor paging through {@code luc:query}'s eighth
 * argument and {@code luc:cursor}.
 * <p>
 * The fixture is the shape a search client sorts on: a case-folded sortable KEYWORD field
 * and a multi-valued sortable INT field. Six items load in the order e, b, a, c, d, f. b
 * and e tie on both fields and on relevance, so index order puts e first, and re-inserting
 * either one flips it. Only an IRI tiebreak gives one answer throughout.
 * <pre>
 *   item  title    n       body                  rating
 *   a     Alpha    3       copper                2
 *   b     beta     1, 5    copper copper         4
 *   c     Gamma    2       copper                1
 *   d     -        4       copper copper copper  5
 *   e     Beta     1, 5    copper copper         3
 *   f     delta    -       copper                6
 * </pre>
 */
public class TestCursorPaging {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final String NS = "http://example.org/";
    private static final String FIELD = "urn:jena:lucene:field#";
    private static final String ENTITY = "urn:jena:lucene:index#entity";

    private static final String PREFIXES =
        "PREFIX luc:   <urn:jena:lucene:index#>\n"
        + "PREFIX ex:    <" + NS + ">\n";

    private static final String CONFIG =
        "@prefix idx:   <urn:jena:lucene:index#> .\n"
        + "@prefix field: <" + FIELD + "> .\n"
        + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
        + "@prefix text:  <http://jena.apache.org/text#> .\n"
        + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
        + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
        + "@prefix ex:    <" + NS + "> .\n"
        + "field:title idx:fieldName \"title\" ; idx:fieldType idx:KeywordField ; idx:sortable true ;\n"
        + "    idx:normalizer [ a text:LowerCaseKeywordAnalyzer ] .\n"
        + "field:n     idx:fieldName \"n\" ; idx:fieldType idx:IntField ; idx:sortable true ;\n"
        + "    idx:multiValued true .\n"
        + "field:body  idx:fieldName \"body\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n"
        + "field:note  idx:fieldName \"note\" ; idx:fieldType idx:KeywordField .\n"
        + "field:rating idx:fieldName \"rating\" ; idx:fieldType idx:IntField ; idx:facetable true .\n"
        + "ex:ItemShape\n"
        + "    sh:targetClass ex:Item ;\n"
        + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
        + "    sh:property [ idx:field field:n ; sh:path ex:n ] ;\n"
        + "    sh:property [ idx:field field:body ; sh:path ex:body ] ;\n"
        + "    sh:property [ idx:field field:note ; sh:path ex:note ] ;\n"
        + "    sh:property [ idx:field field:rating ; sh:path ex:rating ] .\n"
        + "ex:dataset rdf:type text:TextDataset ;\n"
        + "    text:dataset ex:base ;\n"
        + "    text:index ex:index .\n"
        + "ex:base rdf:type ja:MemoryDataset .\n"
        + "ex:index rdf:type text:TextIndexShacl ;\n"
        + "    text:directory \"mem\" ;\n"
        + "    text:storeValues true ;\n"
        + "    text:shapes ( ex:ItemShape ) .\n";

    private static final String TITLE_ASC = "{\"field\":\"" + FIELD + "title\",\"order\":\"asc\",\"missing\":\"last\"}";
    private static final String N_ASC = "{\"field\":\"" + FIELD + "n\",\"order\":\"asc\",\"missing\":\"last\"}";
    private static final String N_DESC = "{\"field\":\"" + FIELD + "n\",\"order\":\"desc\",\"missing\":\"last\"}";
    private static final String ENTITY_ASC = "{\"field\":\"" + ENTITY + "\",\"order\":\"asc\"}";
    private static final String ENTITY_DESC = "{\"field\":\"" + ENTITY + "\",\"order\":\"desc\"}";

    private static final Map<String, Integer> RATINGS = Map.of("a", 2, "b", 4, "c", 1, "d", 5, "e", 3, "f", 6);

    /** title asc, n asc, ties by IRI. */
    private static final List<String> TITLE_ORDER = List.of("a", "b", "e", "f", "c", "d");

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @BeforeEach
    public void setUp() {
        Model config = ModelFactory.createDefaultModel();
        config.read(new StringReader(CONFIG), null, "TTL");
        dataset = (Dataset) Assembler.general().open(config.getResource(NS + "dataset"));
        textIndex = (ShaclTextIndexLucene) ((DatasetGraphText) dataset.asDatasetGraph()).getTextIndex();
        for (String item : List.of("e", "b", "a", "c", "d", "f")) {
            insert(item);
        }
    }

    @AfterEach
    public void tearDown() {
        if (textIndex != null) {
            textIndex.setLegacyNoEntityDocValue(false);
        }
        if (dataset != null) {
            dataset.close();
        }
    }

    // ---- Fixture -------------------------------------------------------------------

    private void insert(String item) {
        Txn.executeWrite(dataset, () -> {
            Model m = dataset.getDefaultModel();
            Resource r = m.createResource(NS + item);
            r.addProperty(RDF.type, m.createResource(NS + "Item"));
            switch (item) {
                case "a" -> describe(m, r, "Alpha", "copper", 3);
                case "b" -> describe(m, r, "beta", "copper copper", 1, 5);
                case "c" -> describe(m, r, "Gamma", "copper", 2);
                case "d" -> describe(m, r, null, "copper copper copper", 4);
                case "e" -> describe(m, r, "Beta", "copper copper", 1, 5);
                case "f" -> describe(m, r, "delta", "copper");
                default -> describe(m, r, item, "copper");
            }
            Integer rating = RATINGS.get(item);
            if (rating != null) {
                r.addLiteral(m.createProperty(NS + "rating"), m.createTypedLiteral(rating.intValue()));
            }
        });
    }

    private static void describe(Model m, Resource r, String title, String body, int... ns) {
        if (title != null) {
            r.addProperty(m.createProperty(NS + "title"), title);
        }
        r.addProperty(m.createProperty(NS + "body"), body);
        for (int n : ns) {
            r.addLiteral(m.createProperty(NS + "n"), m.createTypedLiteral(n));
        }
    }

    /** Remove every triple of {@code item} and add them again: a new document, later in index order. */
    private void reinsert(String item) {
        Txn.executeWrite(dataset, () -> dataset.getDefaultModel().removeAll(
            dataset.getDefaultModel().createResource(NS + item), null, null));
        insert(item);
    }

    // ---- Queries -------------------------------------------------------------------

    private record Row(String entity, int rank, String cursor) {}

    private static String sort(String... specs) {
        return specs.length == 0 ? "" : "[" + String.join(",", specs) + "]";
    }

    /**
     * One page. {@code after} null uses the seven-argument form; any other value is passed as
     * the eighth argument, and a non-empty one also binds each hit's cursor.
     */
    private List<Row> page(String qs, String sort, int limit, int offset, String after) {
        String args = "(\"default\" \"default\" \"" + qs + "\" \"\" '" + sort + "' " + limit + " " + offset
            + (after == null ? "" : " \"" + after + "\"") + ")";
        String cursor = after == null || after.isEmpty() ? "" : "  ?hit luc:cursor ?cursor .\n";
        List<Binding> rows = select("SELECT ?entity ?rank ?cursor WHERE {\n"
            + "  (?hit ?entity ?score ?totalHits ?rank) luc:query " + args + " .\n"
            + cursor
            + "} ORDER BY ?rank");
        List<Row> page = new ArrayList<>();
        for (Binding b : rows) {
            String cursorValue = b.contains("cursor") ? b.get("cursor").getLiteralLexicalForm() : null;
            page.add(new Row(b.get("entity").getURI().substring(NS.length()),
                Integer.parseInt(b.get("rank").getLiteralLexicalForm()), cursorValue));
        }
        return page;
    }

    private List<Binding> select(String query) {
        return Txn.calculateRead(dataset, () -> {
            try (QueryExecution qe = QueryExecution.dataset(dataset).query(PREFIXES + query).build()) {
                List<Binding> rows = new ArrayList<>();
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    rows.add(rs.nextBinding());
                }
                return rows;
            }
        });
    }

    /** Page through the whole result set by cursor, starting from {@code "*"}. */
    private List<Row> walk(String qs, String sort, int pageSize) {
        List<Row> all = new ArrayList<>();
        String after = "*";
        for (int i = 0; i < 20; i++) {
            List<Row> page = page(qs, sort, pageSize, 0, after);
            if (page.isEmpty()) {
                return all;
            }
            all.addAll(page);
            after = page.get(page.size() - 1).cursor();
        }
        return fail("The cursor walk did not end: " + all);
    }

    private static List<String> entities(List<Row> rows) {
        return rows.stream().map(Row::entity).toList();
    }

    private static List<Integer> ranks(List<Row> rows) {
        return rows.stream().map(Row::rank).toList();
    }

    private QueryException failure(String qs, String sort, String after) {
        return assertThrows(QueryException.class, () -> page(qs, sort, 10, 0, after));
    }

    // ---- The entity IRI sort key -----------------------------------------------------

    @Test
    public void entityKeyBreaksTiesByIri() {
        assertEquals(TITLE_ORDER, entities(page("", sort(TITLE_ASC, N_ASC, ENTITY_ASC), 10, 0, null)));
    }

    /** Index order flips with every re-insertion; the IRI tiebreak does not. */
    @Test
    public void entityKeyOrderSurvivesReinsertion() {
        String sort = sort(TITLE_ASC, N_ASC, ENTITY_ASC);
        reinsert("e");
        assertEquals(TITLE_ORDER, entities(page("", sort, 10, 0, null)));
        reinsert("b");
        assertEquals(TITLE_ORDER, entities(page("", sort, 10, 0, null)));
    }

    @Test
    public void entityKeyDescending() {
        assertEquals(List.of("b", "e", "d", "a", "c", "f"),
            entities(page("", sort(N_DESC, ENTITY_ASC), 10, 0, null)));
        assertEquals(List.of("e", "b", "d", "a", "c", "f"),
            entities(page("", sort(N_DESC, ENTITY_DESC), 10, 0, null)));
        assertEquals(List.of("f", "e", "d", "c", "b", "a"),
            entities(page("", sort(ENTITY_DESC), 10, 0, null)));
    }

    @Test
    public void entityKeyOnAnIndexWithoutTheIriDocValueIsAnError() {
        textIndex.setLegacyNoEntityDocValue(true);
        insert("g");
        QueryException ex = failure("", sort(TITLE_ASC, ENTITY_ASC), null);
        assertTrue(ex.getMessage().contains("rebuild"), ex.getMessage());
        assertThrows(QueryException.class, () -> page("", "", 10, 0, "*"));
    }

    // ---- Sort fields that cannot sort ------------------------------------------------

    @Test
    public void unknownSortFieldIsAnError() {
        QueryException ex = failure("", sort("{\"field\":\"" + FIELD + "nope\"}"), null);
        assertTrue(ex.getMessage().contains(FIELD + "nope"), ex.getMessage());
    }

    @Test
    public void misspelledEntityKeyIsAnError() {
        failure("", sort(TITLE_ASC, "{\"field\":\"" + ENTITY + "Iri\"}"), null);
    }

    @Test
    public void fieldWithoutSortDocValuesIsAnError() {
        QueryException ex = failure("", sort("{\"field\":\"" + FIELD + "note\"}"), null);
        assertTrue(ex.getMessage().contains("idx:sortable"), ex.getMessage());
    }

    /** A numeric field writes its sort doc values for idx:facetable as well as idx:sortable. */
    @Test
    public void numericFacetableFieldSorts() {
        assertEquals(List.of("c", "a", "e", "b", "d", "f"),
            entities(page("", sort("{\"field\":\"" + FIELD + "rating\"}"), 10, 0, null)));
    }

    @Test
    public void textFieldSortIsAnError() {
        failure("", sort("{\"field\":\"" + FIELD + "body\"}"), null);
    }

    @Test
    public void failedSearchRaisesRatherThanReturningNoHits() {
        failure("copper AND (", "", null);
    }

    // ---- Cursor paging -----------------------------------------------------------------

    @Test
    public void cursorWalkVisitsEveryHitOnceInSortOrder() {
        List<Row> walked = walk("", sort(TITLE_ASC, N_ASC), 2);
        assertEquals(TITLE_ORDER, entities(walked));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), ranks(walked));
    }

    @Test
    public void cursorWalkWithAPageOfOne() {
        assertEquals(TITLE_ORDER, entities(walk("", sort(TITLE_ASC, N_ASC), 1)));
    }

    /** Multi-valued sorts take the highest value descending; b and e tie on 5. */
    @Test
    public void cursorWalkDescendingOverMultiValuedField() {
        assertEquals(List.of("b", "e", "d", "a", "c", "f"), entities(walk("", sort(N_DESC), 2)));
    }

    /** An explicit trailing IRI key is used as given rather than appended again. */
    @Test
    public void cursorWalkWithExplicitEntityKey() {
        assertEquals(List.of("e", "b", "d", "a", "c", "f"),
            entities(walk("", sort(N_DESC, ENTITY_DESC), 4)));
    }

    /** With no query string every hit scores the same, so relevance order is IRI order. */
    @Test
    public void cursorWalkInRelevanceOrderOverEqualScores() {
        assertEquals(List.of("a", "b", "c", "d", "e", "f"), entities(walk("", "", 2)));
    }

    /** d matches most often, then b and e, then a, c and f; equal scores order by IRI. */
    @Test
    public void cursorWalkInRelevanceOrder() {
        List<String> order = List.of("d", "b", "e", "a", "c", "f");
        assertEquals(order, entities(page("copper", "", 10, 0, "*")));
        assertEquals(order, entities(walk("copper", "", 2)));
    }

    /** A cursor names sort values, not a document, so it outlives the documents it was read from. */
    @Test
    public void cursorSurvivesReinsertingEveryItem() {
        String sort = sort(TITLE_ASC, N_ASC);
        List<Row> first = page("", sort, 2, 0, "*");
        assertEquals(List.of("a", "b"), entities(first));
        for (String item : List.of("f", "d", "c", "a", "b", "e")) {
            reinsert(item);
        }
        List<Row> second = page("", sort, 2, 0, first.get(1).cursor());
        assertEquals(List.of("e", "f"), entities(second));
        assertEquals(List.of(2, 3), ranks(second));
    }

    @Test
    public void offsetCountsFromTheCursor() {
        String sort = sort(TITLE_ASC, N_ASC);
        List<Row> jumped = page("", sort, 2, 2, "*");
        assertEquals(List.of("e", "f"), entities(jumped));
        assertEquals(List.of(2, 3), ranks(jumped));

        List<Row> next = page("", sort, 2, 0, jumped.get(1).cursor());
        assertEquals(List.of("c", "d"), entities(next));
        assertEquals(List.of(4, 5), ranks(next));

        List<Row> skipped = page("", sort, 2, 1, jumped.get(0).cursor());
        assertEquals(List.of("c", "d"), entities(skipped));
        assertEquals(List.of(4, 5), ranks(skipped));
    }

    /** A cursor is a position in the sort, so a different query string or filter can continue from it. */
    @Test
    public void cursorIsNotTiedToTheQuery() {
        String sort = sort(TITLE_ASC, N_ASC);
        String cursor = page("", sort, 2, 0, "*").get(1).cursor();
        assertEquals(List.of("e", "f", "c", "d"), entities(page("copper", sort, 10, 0, cursor)));
    }

    @Test
    public void cursorPastTheLastHitGivesAnEmptyPage() {
        List<Row> all = page("", sort(TITLE_ASC, N_ASC), 10, 0, "*");
        assertEquals(List.of(), page("", sort(TITLE_ASC, N_ASC), 10, 0, all.get(all.size() - 1).cursor()));
    }

    @Test
    public void cursorIsUrlSafe() {
        for (Row row : page("", sort(TITLE_ASC, N_ASC), 10, 0, "*")) {
            assertTrue(row.cursor().matches("[A-Za-z0-9_-]+"), row.cursor());
        }
    }

    /** An empty eighth argument is the seven-argument form. */
    @Test
    public void emptyEighthArgumentLeavesTheSearchAsBefore() {
        String sort = sort(TITLE_ASC, N_ASC);
        assertEquals(page("", sort, 10, 0, null), page("", sort, 10, 0, ""));
        assertEquals(page("copper", "", 10, 1, null), page("copper", "", 10, 1, ""));
    }

    @Test
    public void cursorFromAnotherSortIsAnError() {
        String cursor = page("", sort(TITLE_ASC, N_ASC), 2, 0, "*").get(1).cursor();
        QueryException ex = failure("", sort(N_ASC), cursor);
        assertTrue(ex.getMessage().contains("sort"), ex.getMessage());
        failure("", "", cursor);
    }

    @Test
    public void malformedCursorIsAnError() {
        failure("", "", "not-a-cursor");
        failure("", "", "!!");
    }

    @Test
    public void lucCursorNeedsACursorModeSearch() {
        QueryException ex = assertThrows(QueryException.class, () -> select(
            "SELECT * WHERE {\n"
            + "  (?hit ?entity) luc:query (\"default\" \"default\" \"\" \"\" \"\" 10 0) .\n"
            + "  ?hit luc:cursor ?cursor .\n"
            + "}"));
        assertTrue(ex.getMessage().contains("\"*\""), ex.getMessage());
    }

    @Test
    public void lucCursorWithAConstantCursorKeepsThatHit() {
        Row b = page("", sort(TITLE_ASC, N_ASC), 10, 0, "*").get(1);
        List<Binding> rows = select("SELECT ?entity WHERE {\n"
            + "  (?hit ?entity) luc:query (\"default\" \"default\" \"\" \"\" '" + sort(TITLE_ASC, N_ASC) + "' 10 0 \"*\") .\n"
            + "  ?hit luc:cursor \"" + b.cursor() + "\" .\n"
            + "}");
        assertEquals(1, rows.size());
        assertEquals(NS + "b", rows.get(0).get("entity").getURI());
    }

    /** With ?hit unbound, every cached hit of the search paged by cursor, each with its own cursor. */
    @Test
    public void lucCursorWithHitUnboundGivesEveryCachedHit() {
        List<Row> page = page("", sort(TITLE_ASC, N_ASC), 3, 0, "*");
        List<Binding> rows = select("SELECT DISTINCT ?h ?cursor WHERE {\n"
            + "  (?hit) luc:query (\"default\" \"default\" \"\" \"\" '" + sort(TITLE_ASC, N_ASC) + "' 3 0 \"*\") .\n"
            + "  ?h luc:cursor ?cursor .\n"
            + "}");
        assertEquals(page.stream().map(Row::cursor).sorted().toList(),
            rows.stream().map(b -> b.get("cursor").getLiteralLexicalForm()).sorted().toList());
    }

    @Test
    public void nineObjectArgumentsIsAnError() {
        assertThrows(QueryException.class, () -> select(
            "SELECT * WHERE { (?hit) luc:query (\"default\" \"default\" \"\" \"\" \"\" 10 0 \"*\" \"x\") }"));
    }

    // ---- The token -----------------------------------------------------------------------

    @Test
    public void tokenRoundTripsEveryValueType() {
        Sort sort = new Sort(
            SortField.FIELD_SCORE,
            new SortField("s", SortField.Type.STRING),
            new SortField("missing", SortField.Type.STRING),
            new SortField("i", SortField.Type.INT),
            new SortField("l", SortField.Type.LONG),
            new SortField("d", SortField.Type.DOUBLE),
            new SortedNumericSortField("sn", SortField.Type.INT),
            new SortedSetSortField("ss", false),
            new SortField("iri", SortField.Type.STRING_VAL));
        Object[] values = {
            1.25f, new BytesRef("é x"), null, -7, Long.MIN_VALUE, Double.NEGATIVE_INFINITY, 42,
            new BytesRef(""), new BytesRef("http://example.org/a")};
        SearchCursor.Order order = new SearchCursor.Order("test", sort);

        String token = SearchCursor.encode(order, 41, values);
        SearchCursor cursor = SearchCursor.parse(token);
        FieldDoc after = cursor.after(order, 100);

        assertArrayEquals(values, after.fields);
        assertEquals(42, cursor.firstRank());
        assertFalse(cursor.isStart());
        assertTrue(SearchCursor.parse("*").isStart());
        assertEquals(0, SearchCursor.parse("*").firstRank());
    }

    @Test
    public void tokenOfAnotherOrderIsRejected() {
        Sort sort = new Sort(new SortField("i", SortField.Type.INT));
        String token = SearchCursor.encode(new SearchCursor.Order("one", sort), 0, new Object[] {1});
        assertThrows(QueryExecException.class,
            () -> SearchCursor.parse(token).after(new SearchCursor.Order("two", sort), 100));
    }

    @Test
    public void tokenWithValuesOfTheWrongTypeIsRejected() {
        Sort ints = new Sort(new SortField("i", SortField.Type.INT));
        Sort longs = new Sort(new SortField("i", SortField.Type.LONG));
        String token = SearchCursor.encode(new SearchCursor.Order("same", ints), 0, new Object[] {1});
        assertThrows(QueryExecException.class,
            () -> SearchCursor.parse(token).after(new SearchCursor.Order("same", longs), 100));
    }
}
