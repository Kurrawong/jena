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
import java.util.TreeMap;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.system.Txn;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sideways facet counts on {@code luc:facet}, its default count mode: each requested field
 * that the filter selects on is counted with its own selection left out, and every other
 * field under the full filter.
 * <p>
 * Each test compares one sideways call against the calls that give the same counts in the
 * {@code "drilldown"} mode: one {@code luc:facet} per selected field, with that field's
 * clauses removed from the filter by hand. The counts must be equal.
 */
public class TestFacetSideways {

    static {
        JenaSystem.init();
        TextQuery.init();
    }

    private static final String NS = "http://example.org/";
    private static final String FIELD = "urn:jena:lucene:field#";
    private static final String HIERARCHY = "basin_subbasin";

    private static final String CONFIG =
        "@prefix idx:   <urn:jena:lucene:index#> .\n"
        + "@prefix field: <" + FIELD + "> .\n"
        + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
        + "@prefix text:  <http://jena.apache.org/text#> .\n"
        + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
        + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
        + "@prefix ex:    <" + NS + "> .\n"
        + "field:title     idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n"
        + "field:commodity idx:fieldName \"commodity\" ; idx:fieldType idx:KeywordField ;"
        + " idx:facetable true ; idx:multiValued true .\n"
        + "field:docType   idx:fieldName \"docType\" ; idx:fieldType idx:KeywordField ; idx:facetable true .\n"
        + "field:region    idx:fieldName \"region\" ; idx:fieldType idx:KeywordField ; idx:facetable true .\n"
        + "field:year      idx:fieldName \"year\" ; idx:fieldType idx:IntField ; idx:facetable true .\n"
        + "field:basin     idx:fieldName \"basin\" ; idx:fieldType idx:KeywordField ; idx:facetable true .\n"
        + "field:subbasin  idx:fieldName \"subbasin\" ; idx:fieldType idx:KeywordField ; idx:facetable true .\n"
        + "field:idType    idx:fieldName \"idType\" ; idx:fieldType idx:KeywordField .\n"
        + "ex:RecShape\n"
        + "    sh:targetClass ex:Rec ;\n"
        + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
        + "    sh:property [ idx:field field:commodity ; sh:path ex:commodity ] ;\n"
        + "    sh:property [ idx:field field:docType ; sh:path ex:docType ] ;\n"
        + "    sh:property [ idx:field field:region ; sh:path ex:region ] ;\n"
        + "    sh:property [ idx:field field:year ; sh:path ex:year ] ;\n"
        + "    sh:property [ idx:field field:basin ; sh:path ex:basin ] ;\n"
        + "    sh:property [ idx:field field:subbasin ; sh:path ex:subbasin ] ;\n"
        + "    idx:facetHierarchy ( field:basin field:subbasin ) ;\n"
        + "    idx:nested [\n"
        + "        idx:joinPath ex:identifier ;\n"
        + "        idx:property [ idx:field field:idType ; sh:path ex:idType ] ;\n"
        + "    ] .\n"
        + "ex:dataset rdf:type text:TextDataset ;\n"
        + "    text:dataset ex:base ;\n"
        + "    text:index ex:index .\n"
        + "ex:base rdf:type ja:MemoryDataset .\n"
        + "ex:index rdf:type text:TextIndexShacl ;\n"
        + "    text:directory \"mem\" ;\n"
        + "    text:storeValues true ;\n"
        + "    text:shapes ( ex:RecShape ) .\n";

    private static final String[] COMMODITIES = {"cu", "au", "ag", "zn"};
    private static final String[] DOC_TYPES = {"report", "map", "dataset"};
    private static final String[] REGIONS = {"north", "south"};
    private static final String[] BASINS = {"east", "west"};
    private static final int RECORDS = 48;

    private static final String COMMODITY_IN = in("commodity", "cu", "au");
    private static final String DOC_TYPE_EQ = eq("docType", "report");
    private static final String YEAR_BETWEEN =
        "{\"op\":\"between\",\"args\":[{\"property\":\"" + FIELD + "year\"},1980,1995]}";
    private static final String YEAR_RANGES =
        "{\"field\":\"" + FIELD + "year\",\"ranges\":[null,1980,1990,2000,null]}";

    private Dataset dataset;

    @BeforeEach
    public void setUp() {
        Model config = ModelFactory.createDefaultModel();
        config.read(new StringReader(CONFIG), null, "TTL");
        dataset = (Dataset) Assembler.general().open(config.getResource(NS + "dataset"));

        Txn.executeWrite(dataset, () -> {
            Model m = dataset.getDefaultModel();
            for (int i = 0; i < RECORDS; i++) {
                addRecord(m, i);
            }
        });
    }

    private static void addRecord(Model m, int i) {
        Resource r = m.createResource(NS + "rec" + i);
        r.addProperty(RDF.type, m.createResource(NS + "Rec"));
        r.addProperty(m.createProperty(NS + "title"), (i % 2 == 0 ? "copper" : "gold") + " record " + i);
        r.addProperty(m.createProperty(NS + "commodity"), COMMODITIES[i % 4]);
        if (i % 3 == 0) {
            r.addProperty(m.createProperty(NS + "commodity"), COMMODITIES[(i / 4) % 4]);
        }
        r.addProperty(m.createProperty(NS + "docType"), DOC_TYPES[(i / 2) % 3]);
        r.addProperty(m.createProperty(NS + "region"), REGIONS[(i / 5) % 2]);
        r.addLiteral(m.createProperty(NS + "year"), m.createTypedLiteral(1975 + (i * 7) % 30));
        String basin = BASINS[i % 2];
        r.addProperty(m.createProperty(NS + "basin"), basin);
        r.addProperty(m.createProperty(NS + "subbasin"), basin + "-" + ((i / 3) % 3));
        Resource id = m.createResource();
        id.addProperty(m.createProperty(NS + "idType"), i % 4 == 0 ? "doi" : "isbn");
        r.addProperty(m.createProperty(NS + "identifier"), id);
    }

    @AfterEach
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    // ---- Filters ---------------------------------------------------------------------

    private static String in(String field, String... values) {
        StringBuilder sb = new StringBuilder("{\"op\":\"in\",\"args\":[{\"property\":\"" + FIELD + field + "\"},[");
        for (int i = 0; i < values.length; i++) {
            sb.append(i > 0 ? "," : "").append('"').append(values[i]).append('"');
        }
        return sb.append("]]}").toString();
    }

    private static String eq(String field, String value) {
        return "{\"op\":\"=\",\"args\":[{\"property\":\"" + FIELD + field + "\"},\"" + value + "\"]}";
    }

    private static String and(String... clauses) {
        return "{\"op\":\"and\",\"args\":[" + String.join(",", clauses) + "]}";
    }

    private static String or(String... clauses) {
        return "{\"op\":\"or\",\"args\":[" + String.join(",", clauses) + "]}";
    }

    private static String fields(String... entries) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.length; i++) {
            String e = entries[i];
            sb.append(i > 0 ? "," : "");
            if (e.startsWith("{") || e.startsWith("\"")) {
                sb.append(e);
            } else {
                sb.append('"').append(e.equals("*") || e.equals(HIERARCHY) ? e : FIELD + e).append('"');
            }
        }
        return sb.append(']').toString();
    }

    // ---- Running luc:facet ---------------------------------------------------------------

    /**
     * Facet rows of a {@code "drilldown"} call, every field counted under the full filter, as
     * field local name → bucket → count. A range bucket is {@code low..high}.
     */
    private Map<String, Map<String, Long>> facet(String queryString, String facetFields, String filter) {
        return facet(queryString, facetFields, filter, 0, 0);
    }

    private Map<String, Map<String, Long>> facet(String queryString, String facetFields, String filter,
                                                 int maxValues, int minCount) {
        return facetRows(queryString, facetFields, filter, maxValues, minCount, " \"drilldown\"");
    }

    /** Facet rows of a call without the count mode argument, which counts sideways. */
    private Map<String, Map<String, Long>> sidewaysFacet(String queryString, String facetFields, String filter) {
        return sidewaysFacet(queryString, facetFields, filter, 0, 0);
    }

    private Map<String, Map<String, Long>> sidewaysFacet(String queryString, String facetFields, String filter,
                                                         int maxValues, int minCount) {
        return facetRows(queryString, facetFields, filter, maxValues, minCount, "");
    }

    /** {@code modeArgument} is appended after {@code minCount}: empty, or a space and the eighth argument. */
    private Map<String, Map<String, Long>> facetRows(String queryString, String facetFields, String filter,
                                                     int maxValues, int minCount, String modeArgument) {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?f ?v ?low ?high ?c WHERE {\n"
            + "  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" " + literal(queryString)
            + " " + literal(facetFields) + " " + literal(filter) + " " + maxValues + " " + minCount
            + modeArgument + ")\n"
            + "}";
        Map<String, Map<String, Long>> result = new TreeMap<>();
        Txn.executeRead(dataset, () -> {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    String field = name(sol.get("f"));
                    String bucket = sol.contains("v")
                        ? lexical(sol.get("v"))
                        : lexical(sol.get("low")) + ".." + lexical(sol.get("high"));
                    result.computeIfAbsent(field, k -> new TreeMap<>())
                        .put(bucket, sol.getLiteral("c").getLong());
                }
            }
        });
        return result;
    }

    private static String literal(String s) {
        return "'''" + s + "'''";
    }

    private static String name(RDFNode node) {
        String s = node.isURIResource() ? node.asResource().getURI() : node.asLiteral().getLexicalForm();
        return s.startsWith(FIELD) ? s.substring(FIELD.length()) : s;
    }

    private static String lexical(RDFNode node) {
        if (node == null) {
            return "*";
        }
        return node.isURIResource() ? node.asResource().getURI() : node.asLiteral().getLexicalForm();
    }

    private static Map<String, Long> field(Map<String, Map<String, Long>> rows, String field) {
        return rows.getOrDefault(field, Map.of());
    }

    // ---- Equal to the per-field calls with trimmed filters ---------------------------------

    @Test
    public void selectedFieldsLeaveOutTheirOwnClause() {
        String filter = and(COMMODITY_IN, DOC_TYPE_EQ, YEAR_BETWEEN);
        Map<String, Map<String, Long>> sw = sidewaysFacet("",
            fields("commodity", "docType", "region", YEAR_RANGES), filter);

        assertEquals(field(facet("", fields("commodity"), and(DOC_TYPE_EQ, YEAR_BETWEEN)), "commodity"),
            field(sw, "commodity"));
        assertEquals(field(facet("", fields("docType"), and(COMMODITY_IN, YEAR_BETWEEN)), "docType"),
            field(sw, "docType"));
        assertEquals(field(facet("", fields(YEAR_RANGES), and(COMMODITY_IN, DOC_TYPE_EQ)), "year"),
            field(sw, "year"));
        assertEquals(field(facet("", fields("region"), filter), "region"),
            field(sw, "region"));

        // Without the sideways flag the same call counts commodity under the full filter,
        // which gives different counts on this data.
        Map<String, Long> fullCommodity = field(facet("", fields("commodity"), filter), "commodity");
        assertNotEquals(fullCommodity, field(sw, "commodity"));
        assertTrue(field(sw, "commodity").getOrDefault("zn", 0L) > fullCommodity.getOrDefault("zn", 0L),
            "zn: sideways " + field(sw, "commodity") + ", full filter " + fullCommodity);
    }

    @Test
    public void sidewaysWithTextQuery() {
        String filter = and(COMMODITY_IN, DOC_TYPE_EQ);
        Map<String, Map<String, Long>> sw = sidewaysFacet("copper",
            fields("commodity", "docType", "region"), filter);

        assertEquals(field(facet("copper", fields("commodity"), DOC_TYPE_EQ), "commodity"), field(sw, "commodity"));
        assertEquals(field(facet("copper", fields("docType"), COMMODITY_IN), "docType"), field(sw, "docType"));
        assertEquals(field(facet("copper", fields("region"), filter), "region"), field(sw, "region"));
        assertFalse(field(sw, "commodity").isEmpty());
    }

    @Test
    public void singleClauseFilterIsADimension() {
        Map<String, Map<String, Long>> sw = sidewaysFacet("", fields("commodity", "docType"), COMMODITY_IN);

        assertEquals(field(facet("", fields("commodity"), ""), "commodity"), field(sw, "commodity"));
        assertEquals(field(facet("", fields("docType"), COMMODITY_IN), "docType"), field(sw, "docType"));
    }

    @Test
    public void severalClausesOnOneFieldFormOneDimension() {
        String filter = and(COMMODITY_IN, eq("commodity", "cu"), DOC_TYPE_EQ);
        Map<String, Map<String, Long>> sw = sidewaysFacet("", fields("commodity", "docType"), filter);

        assertEquals(field(facet("", fields("commodity"), DOC_TYPE_EQ), "commodity"), field(sw, "commodity"));
        assertEquals(field(facet("", fields("docType"), and(COMMODITY_IN, eq("commodity", "cu"))), "docType"),
            field(sw, "docType"));
    }

    @Test
    public void wildcardWithSideways() {
        String filter = and(COMMODITY_IN, eq("region", "north"));
        Map<String, Map<String, Long>> sw = sidewaysFacet("", fields("*"), filter);

        assertEquals(field(facet("", fields("commodity"), eq("region", "north")), "commodity"),
            field(sw, "commodity"));
        assertEquals(field(facet("", fields("region"), COMMODITY_IN), "region"), field(sw, "region"));
        assertEquals(field(facet("", fields("docType"), filter), "docType"), field(sw, "docType"));
    }

    @Test
    public void maxValuesAndMinCountApplyToSidewaysCounts() {
        String filter = and(COMMODITY_IN, DOC_TYPE_EQ);
        Map<String, Map<String, Long>> sw = sidewaysFacet("", fields("commodity", "docType"), filter, 2, 3);

        assertEquals(field(facet("", fields("commodity"), DOC_TYPE_EQ, 2, 3), "commodity"), field(sw, "commodity"));
        assertEquals(field(facet("", fields("docType"), COMMODITY_IN, 2, 3), "docType"), field(sw, "docType"));
    }

    @Test
    public void countsAcrossSegmentsWithDeletedDocuments() {
        // A second transaction rewrites some records, which deletes their documents in the
        // first segment, and adds records, which go to a new segment. Merging is turned off:
        // Lucene otherwise merges small segments on commit, and the deletions with them.
        ShaclTextIndexLucene index = (ShaclTextIndexLucene) ((DatasetGraphText) dataset.asDatasetGraph()).getTextIndex();
        index.getIndexWriter().getConfig().setMergePolicy(org.apache.lucene.index.NoMergePolicy.INSTANCE);
        Txn.executeWrite(dataset, () -> {
            Model m = dataset.getDefaultModel();
            for (int i = 0; i < RECORDS; i += 5) {
                Resource r = m.createResource(NS + "rec" + i);
                r.removeAll(m.createProperty(NS + "commodity"));
                r.addProperty(m.createProperty(NS + "commodity"), "zn");
            }
            for (int i = RECORDS; i < RECORDS + 12; i++) {
                addRecord(m, i);
            }
        });
        org.apache.lucene.search.IndexSearcher searcher = index.acquireSearcher();
        try {
            org.apache.lucene.index.IndexReader r = searcher.getIndexReader();
            assertTrue(r.leaves().size() > 1, "the index has several segments: " + r.leaves().size()
                + " leaves, maxDoc " + r.maxDoc() + ", numDocs " + r.numDocs());
            assertTrue(searcher.getIndexReader().hasDeletions(), "the index has deleted documents");
        } finally {
            index.releaseSearcher(searcher);
        }

        String filter = and(COMMODITY_IN, DOC_TYPE_EQ, YEAR_BETWEEN);
        Map<String, Map<String, Long>> sw = sidewaysFacet("",
            fields("commodity", "docType", "region", YEAR_RANGES), filter);

        assertEquals(field(facet("", fields("commodity"), and(DOC_TYPE_EQ, YEAR_BETWEEN)), "commodity"),
            field(sw, "commodity"));
        assertEquals(field(facet("", fields("docType"), and(COMMODITY_IN, YEAR_BETWEEN)), "docType"),
            field(sw, "docType"));
        assertEquals(field(facet("", fields(YEAR_RANGES), and(COMMODITY_IN, DOC_TYPE_EQ)), "year"),
            field(sw, "year"));
        assertEquals(field(facet("", fields("region"), filter), "region"), field(sw, "region"));
    }

    @Test
    public void selectionMatchingNothing() {
        String none = in("commodity", "none");
        String filter = and(none, DOC_TYPE_EQ);
        Map<String, Map<String, Long>> sw = sidewaysFacet("", fields("commodity", "docType", "region"), filter);

        assertEquals(field(facet("", fields("commodity"), DOC_TYPE_EQ), "commodity"), field(sw, "commodity"));
        assertFalse(field(sw, "commodity").isEmpty());
        assertEquals(field(facet("", fields("docType"), none), "docType"), field(sw, "docType"));
        assertEquals(Map.of(), field(sw, "region"));
    }

    @Test
    public void baseMatchingNothing() {
        String filter = and(COMMODITY_IN, DOC_TYPE_EQ);
        Map<String, Map<String, Long>> sw = sidewaysFacet("nosuchword", fields("commodity", "docType"), filter);

        assertEquals(facet("nosuchword", fields("commodity", "docType"), filter), sw);
        assertEquals(Map.of(), field(sw, "commodity"));
    }

    @Test
    public void orOfClausesOnOneFieldIsADimension() {
        // Two year buckets selected, ORed: one dimension, like an in.
        String years = or(
            "{\"op\":\"between\",\"args\":[{\"property\":\"" + FIELD + "year\"},1975,1979]}",
            "{\"op\":\"between\",\"args\":[{\"property\":\"" + FIELD + "year\"},1990,1999]}");
        String commodities = or(eq("commodity", "cu"), eq("commodity", "au"));
        String filter = and(years, commodities, DOC_TYPE_EQ);
        Map<String, Map<String, Long>> sw = sidewaysFacet("",
            fields(YEAR_RANGES, "commodity", "docType"), filter);

        assertEquals(field(facet("", fields(YEAR_RANGES), and(commodities, DOC_TYPE_EQ)), "year"), field(sw, "year"));
        assertEquals(field(facet("", fields("commodity"), and(years, DOC_TYPE_EQ)), "commodity"), field(sw, "commodity"));
        assertEquals(field(facet("", fields("docType"), and(years, commodities)), "docType"), field(sw, "docType"));
        assertNotEquals(field(facet("", fields(YEAR_RANGES), filter), "year"), field(sw, "year"));
    }

    // ---- Clauses that stay in the base query ------------------------------------------------

    @Test
    public void fieldInsideOrIsCountedUnderTheFullFilter() {
        String filter = and(or(in("commodity", "cu"), eq("region", "south")), DOC_TYPE_EQ);
        Map<String, Map<String, Long>> sw = sidewaysFacet("", fields("commodity", "region", "docType"), filter);

        assertEquals(field(facet("", fields("commodity"), filter), "commodity"), field(sw, "commodity"));
        assertEquals(field(facet("", fields("region"), filter), "region"), field(sw, "region"));
        assertEquals(field(facet("", fields("docType"), or(in("commodity", "cu"), eq("region", "south"))), "docType"),
            field(sw, "docType"));
    }

    @Test
    public void fieldWithAnIneligibleClauseIsCountedUnderTheFullFilter() {
        String notCu = "{\"op\":\"<>\",\"args\":[{\"property\":\"" + FIELD + "commodity\"},\"cu\"]}";
        String filter = and(COMMODITY_IN, notCu, DOC_TYPE_EQ);
        Map<String, Map<String, Long>> sw = sidewaysFacet("", fields("commodity", "docType"), filter);

        assertEquals(field(facet("", fields("commodity"), filter), "commodity"), field(sw, "commodity"));
        assertEquals(field(facet("", fields("docType"), and(COMMODITY_IN, notCu)), "docType"), field(sw, "docType"));
    }

    @Test
    public void unrequestedFieldStaysInTheBaseQuery() {
        String filter = and(COMMODITY_IN, DOC_TYPE_EQ);
        Map<String, Map<String, Long>> sw = sidewaysFacet("", fields("commodity"), filter);

        assertEquals(field(facet("", fields("commodity"), DOC_TYPE_EQ), "commodity"), field(sw, "commodity"));
        assertEquals(1, sw.size(), "only the requested field: " + sw);
    }

    @Test
    public void nestedClauseStaysInTheBaseQuery() {
        String doi = eq("idType", "doi");
        String filter = and(COMMODITY_IN, doi);
        Map<String, Map<String, Long>> sw = sidewaysFacet("", fields("commodity"), filter);

        assertEquals(field(facet("", fields("commodity"), doi), "commodity"), field(sw, "commodity"));
    }

    @Test
    public void hierarchyIsCountedUnderTheFullFilter() {
        String filter = and(eq("basin", "east"), COMMODITY_IN);
        Map<String, Map<String, Long>> sw = sidewaysFacet("", fields(HIERARCHY, "basin", "commodity"), filter);
        Map<String, Map<String, Long>> plain = facet("", fields(HIERARCHY, "basin"), filter);

        assertEquals(field(plain, HIERARCHY), field(sw, HIERARCHY));
        assertEquals(field(plain, "basin"), field(sw, "basin"));
        assertFalse(field(sw, HIERARCHY).isEmpty());
        assertEquals(field(facet("", fields("commodity"), eq("basin", "east")), "commodity"), field(sw, "commodity"));
    }

    // ---- Unchanged behaviour ---------------------------------------------------------------

    @Test
    public void withoutSelectionSidewaysEqualsDrilldown() {
        assertEquals(facet("", fields("commodity", "docType", YEAR_RANGES), ""),
            sidewaysFacet("", fields("commodity", "docType", YEAR_RANGES), ""));
        assertEquals(facet("copper", fields("commodity", "docType"), eq("region", "north")),
            sidewaysFacet("copper", fields("commodity", "docType"), eq("region", "north")));
    }

    @Test
    public void countModeSpellings() {
        String filter = and(COMMODITY_IN, DOC_TYPE_EQ);
        String ff = fields("commodity", "docType");
        Map<String, Map<String, Long>> omitted = sidewaysFacet("", ff, filter);
        assertEquals(omitted, facetRows("", ff, filter, 0, 0, " \"sideways\""));
        assertEquals(omitted, facetRows("", ff, filter, 0, 0, " \"\""));
        assertNotEquals(omitted, facet("", ff, filter));
    }

    @Test
    public void totalHitsOfLucQueryStayThoseOfTheFullFilter() {
        String filter = and(COMMODITY_IN, DOC_TYPE_EQ);
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?total ?f ?v ?c WHERE {\n"
            + "  { (?hit ?e ?score ?total) luc:query (\"default\" \"default\" \"\" " + literal(filter) + " \"\" 1 0) }\n"
            + "  UNION\n"
            + "  { (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"\" "
            + literal(fields("commodity")) + " " + literal(filter) + " 0 0) }\n"
            + "}";
        long[] total = {-1};
        Map<String, Long> commodity = new TreeMap<>();
        Txn.executeRead(dataset, () -> {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    if (sol.contains("total")) {
                        total[0] = sol.getLiteral("total").getLong();
                    } else {
                        commodity.put(lexical(sol.get("v")), sol.getLiteral("c").getLong());
                    }
                }
            }
        });

        long expectedTotal = 0;
        for (int i = 0; i < RECORDS; i++) {
            boolean report = DOC_TYPES[(i / 2) % 3].equals("report");
            String c1 = COMMODITIES[i % 4];
            String c2 = i % 3 == 0 ? COMMODITIES[(i / 4) % 4] : c1;
            boolean cuOrAu = c1.equals("cu") || c1.equals("au") || c2.equals("cu") || c2.equals("au");
            if (report && cuOrAu) {
                expectedTotal++;
            }
        }
        assertEquals(expectedTotal, total[0]);
        assertEquals(field(facet("", fields("commodity"), DOC_TYPE_EQ), "commodity"), commodity);
    }

    @Test
    public void sidewaysAndDrilldownCallsInOneQueryDoNotShareCounts() {
        String filter = and(COMMODITY_IN, DOC_TYPE_EQ);
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?kind ?v ?c WHERE {\n"
            + "  { (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"\" "
            + literal(fields("commodity")) + " " + literal(filter) + " 0 0 \"drilldown\") BIND(\"plain\" AS ?kind) }\n"
            + "  UNION\n"
            + "  { (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"\" "
            + literal(fields("commodity")) + " " + literal(filter) + " 0 0) BIND(\"sideways\" AS ?kind) }\n"
            + "}";
        Map<String, Map<String, Long>> byKind = new TreeMap<>();
        Txn.executeRead(dataset, () -> {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    byKind.computeIfAbsent(sol.getLiteral("kind").getString(), k -> new TreeMap<>())
                        .put(lexical(sol.get("v")), sol.getLiteral("c").getLong());
                }
            }
        });
        assertEquals(field(facet("", fields("commodity"), filter), "commodity"), byKind.get("plain"));
        assertEquals(field(facet("", fields("commodity"), DOC_TYPE_EQ), "commodity"), byKind.get("sideways"));
    }

    @Test
    public void unknownCountModeIsAnError() {
        assertQueryExecException("  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"\" "
            + literal(fields("commodity")) + " \"\" 0 0 \"results\")\n");
    }

    @Test
    public void facetFieldsObjectIsAnError() {
        assertQueryExecException("  (?f ?v ?low ?high ?c) luc:facet (\"default\" \"default\" \"\" "
            + literal("{\"fields\":" + fields("commodity") + "}") + " \"\" 0 0)\n");
    }

    private void assertQueryExecException(String pattern) {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\nSELECT * WHERE {\n" + pattern + "}";
        Txn.executeRead(dataset, () -> {
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                assertThrows(QueryExecException.class, () -> ResultSetFormatter.consume(qe.execSelect()));
            }
        });
    }
}
