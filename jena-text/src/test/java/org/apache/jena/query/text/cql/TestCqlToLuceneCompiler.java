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

package org.apache.jena.query.text.cql;

import static org.junit.Assert.*;

import java.util.*;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.text.ShaclIndexMapping;
import org.apache.jena.query.text.TextIndexException;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.HierarchyDef;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.search.*;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for CQL-to-Lucene compiler: pushdown/residual split, field type mappings.
 */
public class TestCqlToLuceneCompiler {

    private static final String FP = "urn:jena:lucene:field#";

    private CqlToLuceneCompiler compiler;

    @Before
    public void setUp() {
        FieldDef stateField = new FieldDef("state", FieldType.KEYWORD, null,
            true, true, true, false, false, false);
        FieldDef yearField = new FieldDef("year", FieldType.INT, null,
            true, true, false, true, false, false);
        FieldDef depthField = new FieldDef("depth", FieldType.DOUBLE, null,
            true, true, false, true, false, false);
        FieldDef nameField = new FieldDef("name", FieldType.KEYWORD, null,
            true, true, false, false, false, false);
        FieldDef locationField = new FieldDef("location", FieldType.LATLON, null,
            true, true, false, false, false, false);
        FieldDef notIndexedField = new FieldDef("notes", FieldType.TEXT, null,
            true, false, false, false, false, false);
        List<FieldOccurrence> rootOccurrences = List.of(
            occurrence(stateField, "http://example.org/state"),
            occurrence(yearField, "http://example.org/year"),
            occurrence(depthField, "http://example.org/depth"),
            occurrence(nameField, "http://example.org/name"),
            occurrence(locationField, "http://example.org/location"),
            occurrence(notIndexedField, "http://example.org/notes"));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI("http://example.org/Shape"),
            Collections.singleton(NodeFactory.createURI("http://example.org/Thing")),
            "uri", "docType",
            Arrays.asList(stateField, yearField, depthField, nameField, locationField, notIndexedField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        compiler = new CqlToLuceneCompiler(mapping);
    }

    @Test
    public void testEqualKeyword() {
        CqlExpression expr = new CqlExpression.CqlComparison("=", FP + "state", "WA");
        CqlToLuceneCompiler.CompileResult r = compiler.compile(expr);

        assertNotNull("Should push down keyword equal", r.pushed());
        assertNull("No residual for indexed keyword", r.residual());
        assertTrue(r.pushed() instanceof TermQuery);
    }

    @Test
    public void testNotEqualKeyword() {
        CqlExpression expr = new CqlExpression.CqlComparison("<>", FP + "state", "WA");
        CqlToLuceneCompiler.CompileResult r = compiler.compile(expr);

        assertNotNull(r.pushed());
        assertNull(r.residual());
        assertTrue(r.pushed() instanceof BooleanQuery);
    }

    @Test
    public void testEqualInt() {
        CqlExpression expr = new CqlExpression.CqlComparison("=", FP + "year", 2020);
        CqlToLuceneCompiler.CompileResult r = compiler.compile(expr);

        assertNotNull(r.pushed());
        assertNull(r.residual());
    }

    @Test
    public void testGreaterThanInt() {
        CqlExpression expr = new CqlExpression.CqlComparison(">", FP + "year", 2020);
        CqlToLuceneCompiler.CompileResult r = compiler.compile(expr);

        assertNotNull(r.pushed());
        assertNull(r.residual());
    }

    @Test
    public void testRangeDouble() {
        CqlExpression expr = new CqlExpression.CqlComparison(">=", FP + "depth", 100.0);
        CqlToLuceneCompiler.CompileResult r = compiler.compile(expr);

        assertNotNull(r.pushed());
        assertNull(r.residual());
    }

    @Test
    public void testNonIndexedFieldThrows() {
        // Was a residual, and residuals are discarded, so filtering on a stored-but-not-
        // indexed field quietly returned every row instead of none.
        CqlExpression expr = new CqlExpression.CqlComparison("=", FP + "notes", "important");
        assertThrows(TextIndexException.class, () -> compiler.compile(expr));
    }

    @Test
    public void testUnknownFieldThrows() {
        CqlExpression expr = new CqlExpression.CqlComparison("=", "nonexistent", "val");
        assertThrows(TextIndexException.class, () -> compiler.compile(expr));
    }

    @Test
    public void testAndWithAnUnpushableClauseThrows() {
        // An AND used to push its pushable half and hand back the rest as a residual,
        // which callers discard. The result was a query filtered by one clause and
        // silently not the other. Now the whole thing is refused.
        CqlExpression pushable = new CqlExpression.CqlComparison("=", FP + "state", "WA");
        CqlExpression unpushable = new CqlExpression.CqlLike(FP + "year", "20%");
        CqlExpression and = new CqlExpression.CqlAnd(List.of(pushable, unpushable));

        assertThrows(TextIndexException.class, () -> compiler.compile(and));
    }

    @Test
    public void testAndOfPushableClausesPushesWhole() {
        CqlExpression and = new CqlExpression.CqlAnd(List.of(
            new CqlExpression.CqlComparison("=", FP + "state", "WA"),
            new CqlExpression.CqlComparison("=", FP + "year", 2023)));

        CqlToLuceneCompiler.CompileResult r = compiler.compile(and);

        assertNotNull("Both clauses push", r.pushed());
        assertNull("and nothing is left over", r.residual());
    }

    @Test
    public void testAndFullPush() {
        CqlExpression a = new CqlExpression.CqlComparison("=", FP + "state", "WA");
        CqlExpression b = new CqlExpression.CqlComparison(">", FP + "year", 2020);
        CqlExpression and = new CqlExpression.CqlAnd(List.of(a, b));

        CqlToLuceneCompiler.CompileResult r = compiler.compile(and);

        assertNotNull(r.pushed());
        assertNull("All pushable, no residual", r.residual());
    }

    @Test
    public void testOrAllPushable() {
        CqlExpression a = new CqlExpression.CqlComparison("=", FP + "state", "WA");
        CqlExpression b = new CqlExpression.CqlComparison("=", FP + "state", "OR");
        CqlExpression or = new CqlExpression.CqlOr(List.of(a, b));

        CqlToLuceneCompiler.CompileResult r = compiler.compile(or);

        assertNotNull("Should push OR when all pushable", r.pushed());
        assertNull(r.residual());
    }

    @Test
    public void testOrWithAnUnpushableBranchThrows() {
        // The worst of the old cases. One unpushable branch made the whole disjunction a
        // residual, so every other branch was dropped too and the query returned rows that
        // matched none of them.
        CqlExpression pushable = new CqlExpression.CqlComparison("=", FP + "state", "WA");
        CqlExpression unpushable = new CqlExpression.CqlLike(FP + "year", "20%");
        CqlExpression or = new CqlExpression.CqlOr(List.of(pushable, unpushable));

        assertThrows(TextIndexException.class, () -> compiler.compile(or));
    }

    @Test
    public void testNotPushable() {
        CqlExpression inner = new CqlExpression.CqlComparison("=", FP + "state", "WA");
        CqlExpression not = new CqlExpression.CqlNot(inner);

        CqlToLuceneCompiler.CompileResult r = compiler.compile(not);

        assertNotNull(r.pushed());
        assertNull(r.residual());
        assertTrue(r.pushed() instanceof BooleanQuery);
    }

    @Test
    public void testNotOfAnUnpushableClauseThrows() {
        CqlExpression inner = new CqlExpression.CqlLike(FP + "year", "20%");
        CqlExpression not = new CqlExpression.CqlNot(inner);

        assertThrows(TextIndexException.class, () -> compiler.compile(not));
    }

    @Test
    public void testInKeyword() {
        CqlExpression in = new CqlExpression.CqlIn(FP + "state", List.of("WA", "OR", "CA"));
        CqlToLuceneCompiler.CompileResult r = compiler.compile(in);

        assertNotNull(r.pushed());
        assertNull(r.residual());
        assertTrue(r.pushed() instanceof TermInSetQuery);
    }

    @Test
    public void testInNumeric() {
        CqlExpression in = new CqlExpression.CqlIn(FP + "year", List.of(2020, 2021, 2022));
        CqlToLuceneCompiler.CompileResult r = compiler.compile(in);

        assertNotNull(r.pushed());
        assertNull(r.residual());
    }

    @Test
    public void testBetweenInt() {
        CqlExpression btw = new CqlExpression.CqlBetween(FP + "year", 2020, 2025);
        CqlToLuceneCompiler.CompileResult r = compiler.compile(btw);

        assertNotNull(r.pushed());
        assertNull(r.residual());
    }

    @Test
    public void testBetweenDouble() {
        CqlExpression btw = new CqlExpression.CqlBetween(FP + "depth", 10.0, 100.0);
        CqlToLuceneCompiler.CompileResult r = compiler.compile(btw);

        assertNotNull(r.pushed());
        assertNull(r.residual());
    }

    @Test
    public void testDateEqualityUsesTemporalCompanionField() {
        FieldDef eventDateField = new FieldDef("eventDate", FieldType.TEMPORAL, null, null,
            true, true, false, true, false, false, true);
        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI("http://example.org/DateShape"),
            Collections.singleton(NodeFactory.createURI("http://example.org/Event")),
            "uri", "docType",
            Collections.singletonList(eventDateField),
            Collections.singletonList(occurrence(eventDateField, "http://example.org/eventDate")),
            Collections.emptyList(),
            Collections.emptyList());

        CqlToLuceneCompiler dateCompiler =
            new CqlToLuceneCompiler(new ShaclIndexMapping(Collections.singletonList(profile)));

        CqlExpression expr = new CqlExpression.CqlComparison("=", FP + "eventDate", "2024-03-01");
        CqlToLuceneCompiler.CompileResult r = dateCompiler.compile(expr);

        assertNotNull(r.pushed());
        assertNull(r.residual());
        assertTrue(r.pushed().toString().contains("eventDate__epoch"));
    }

    @Test
    public void testDateBetweenUsesTemporalCompanionField() {
        FieldDef eventDateField = new FieldDef("eventDate", FieldType.TEMPORAL, null, null,
            true, true, false, true, false, false, true);
        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI("http://example.org/DateShape"),
            Collections.singleton(NodeFactory.createURI("http://example.org/Event")),
            "uri", "docType",
            Collections.singletonList(eventDateField),
            Collections.singletonList(occurrence(eventDateField, "http://example.org/eventDate")),
            Collections.emptyList(),
            Collections.emptyList());

        CqlToLuceneCompiler dateCompiler =
            new CqlToLuceneCompiler(new ShaclIndexMapping(Collections.singletonList(profile)));

        CqlExpression expr = new CqlExpression.CqlBetween(FP + "eventDate", "2024-01-01", "2024-12-31");
        CqlToLuceneCompiler.CompileResult r = dateCompiler.compile(expr);

        assertNotNull(r.pushed());
        assertNull(r.residual());
        assertTrue(r.pushed().toString().contains("eventDate__epoch"));
    }

    @Test
    public void testLikeKeyword() {
        CqlExpression like = new CqlExpression.CqlLike(FP + "name", "Gold%");
        CqlToLuceneCompiler.CompileResult r = compiler.compile(like);

        assertNotNull(r.pushed());
        assertNull(r.residual());
        assertTrue(r.pushed() instanceof WildcardQuery);
    }

    @Test
    public void testSpatialOnUnknownFieldThrows() {
        // Query APIs take field IRIs, not bare field names. A bare name resolves to no
        // field, which used to yield a residual — and residuals are discarded, so the
        // spatial filter silently vanished and the query returned unfiltered rows.
        String polygon = "{\"type\":\"Polygon\",\"coordinates\":[[[118.2,-22.3],[118.3,-22.3],[118.3,-22.2],[118.2,-22.2],[118.2,-22.3]]]}";
        CqlExpression spatial = new CqlExpression.CqlSpatial("s_intersects", "nosuchfield", polygon);

        TextIndexException e = assertThrows(TextIndexException.class, () -> compiler.compile(spatial));
        assertTrue("Message should name the unresolved field: " + e.getMessage(),
            e.getMessage().contains("nosuchfield"));
    }

    @Test
    public void testSpatialOnNonSpatialFieldThrows() {
        // A spatial operator against a non-spatial field cannot be pushed; it must not be dropped.
        CqlExpression spatial = new CqlExpression.CqlSpatial(
            "s_intersects", FP + "state", "{\"bbox\":[118.2,-22.3,118.3,-22.2]}");

        TextIndexException e = assertThrows(TextIndexException.class, () -> compiler.compile(spatial));
        assertTrue("Message should name the field type: " + e.getMessage(),
            e.getMessage().contains("KEYWORD"));
    }

    @Test
    public void testSpatialBboxByFieldIriPushesDown() {
        String bbox = "{\"bbox\":[118.2,-22.3,118.3,-22.2]}";
        CqlExpression spatial = new CqlExpression.CqlSpatial("s_intersects", FP + "location", bbox);
        CqlToLuceneCompiler.CompileResult r = compiler.compile(spatial);

        assertNotNull(r.pushed());
        assertNull(r.residual());
    }

    @Test
    public void testSingleHierarchyLevelEqualityUsesDrillDownQuery() {
        FieldDef stateField = new FieldDef("state", FieldType.KEYWORD, null,
            true, true, true, false, false, false);
        FieldDef commodityField = new FieldDef("commodity", FieldType.KEYWORD, null,
            true, true, true, false, false, false);
        HierarchyDef hierarchy = new HierarchyDef("state_commodity", List.of(stateField, commodityField));
        List<FieldOccurrence> rootOccurrences = List.of(
            occurrence(stateField, "http://example.org/state"),
            occurrence(commodityField, "http://example.org/commodity"));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI("http://example.org/Shape"),
            Collections.singleton(NodeFactory.createURI("http://example.org/Thing")),
            "uri", "docType",
            List.of(stateField, commodityField),
            rootOccurrences,
            List.of(hierarchy),
            Collections.emptyList());

        CqlToLuceneCompiler hierarchyCompiler =
            new CqlToLuceneCompiler(new ShaclIndexMapping(Collections.singletonList(profile)), new FacetsConfig());

        CqlExpression expr = new CqlExpression.CqlComparison("=", FP + "state", "WA");
        CqlToLuceneCompiler.CompileResult r = hierarchyCompiler.compile(expr);

        assertNotNull(r.pushed());
        assertNull(r.residual());
        assertTrue(r.pushed() instanceof DrillDownQuery);
    }

    // --- Reserved property: urn:jena:lucene:index#entityIri (issue #73) ---

    private static final String ENTITY_IRI = ShaclIndexMapping.ENTITY_IRI_PROPERTY;
    private static final String SAMPLE_IRI = "http://example.org/borehole/BH123456";

    @Test
    public void testEntityIriEqualityIsTermQueryOnDocIdField() {
        CqlExpression expr = new CqlExpression.CqlComparison("=", ENTITY_IRI, SAMPLE_IRI);
        CqlToLuceneCompiler.CompileResult r = compiler.compile(expr);

        assertNotNull("=' on entityIri must push down", r.pushed());
        assertNull("No residual for entityIri equality", r.residual());
        assertTrue("Should be a TermQuery", r.pushed() instanceof TermQuery);
        TermQuery tq = (TermQuery) r.pushed();
        assertEquals("Field must be the configured docIdField (default 'uri')",
            "uri", tq.getTerm().field());
        assertEquals(SAMPLE_IRI, tq.getTerm().text());
    }

    @Test
    public void testEntityIriNotEqualityIsMatchAllMinusTerm() {
        CqlExpression expr = new CqlExpression.CqlComparison("<>", ENTITY_IRI, SAMPLE_IRI);
        CqlToLuceneCompiler.CompileResult r = compiler.compile(expr);

        assertNotNull(r.pushed());
        assertNull(r.residual());
        assertTrue(r.pushed() instanceof BooleanQuery);
        BooleanQuery bq = (BooleanQuery) r.pushed();
        boolean hasMatchAllMust = bq.clauses().stream()
            .anyMatch(c -> c.occur() == BooleanClause.Occur.MUST
                && c.query() instanceof MatchAllDocsQuery);
        boolean hasTermMustNot = bq.clauses().stream()
            .anyMatch(c -> c.occur() == BooleanClause.Occur.MUST_NOT
                && c.query() instanceof TermQuery);
        assertTrue("Should contain MatchAllDocsQuery as MUST", hasMatchAllMust);
        assertTrue("Should contain TermQuery as MUST_NOT", hasTermMustNot);
    }

    @Test
    public void testEntityIriInIsTermInSetQuery() {
        CqlExpression in = new CqlExpression.CqlIn(ENTITY_IRI,
            List.of("http://example.org/a", "http://example.org/b"));
        CqlToLuceneCompiler.CompileResult r = compiler.compile(in);

        assertNotNull(r.pushed());
        assertNull(r.residual());
        assertTrue(r.pushed() instanceof TermInSetQuery);
    }

    @Test
    public void testEntityIriEmptyInIsMatchNoDocs() {
        CqlExpression in = new CqlExpression.CqlIn(ENTITY_IRI, Collections.emptyList());
        CqlToLuceneCompiler.CompileResult r = compiler.compile(in);

        assertNotNull(r.pushed());
        assertNull(r.residual());
        assertTrue(r.pushed() instanceof MatchNoDocsQuery);
    }

    @Test
    public void testEntityIriRangeThrows() {
        // A range over an entity IRI is meaningless. It used to become a residual, which
        // is discarded, so the constraint silently disappeared.
        CqlExpression expr = new CqlExpression.CqlComparison(">", ENTITY_IRI, SAMPLE_IRI);
        TextIndexException e = assertThrows(TextIndexException.class, () -> compiler.compile(expr));
        assertTrue("Message should say which operators are defined: " + e.getMessage(),
            e.getMessage().contains("'=' and '<>'"));
    }

    @Test
    public void testEntityIriBetweenThrows() {
        CqlExpression btw = new CqlExpression.CqlBetween(ENTITY_IRI,
            "http://example.org/a", "http://example.org/z");
        assertThrows(TextIndexException.class, () -> compiler.compile(btw));
    }

    @Test
    public void testEntityIriLikeThrows() {
        CqlExpression like = new CqlExpression.CqlLike(ENTITY_IRI, "http://example.org/%");
        assertThrows(TextIndexException.class, () -> compiler.compile(like));
    }

    @Test
    public void testEntityIriCombinedWithFieldFilter() {
        CqlExpression entityFilter = new CqlExpression.CqlComparison("=", ENTITY_IRI, SAMPLE_IRI);
        CqlExpression stateFilter = new CqlExpression.CqlComparison("=", FP + "state", "WA");
        CqlExpression and = new CqlExpression.CqlAnd(List.of(entityFilter, stateFilter));

        CqlToLuceneCompiler.CompileResult r = compiler.compile(and);

        assertNotNull("AND of two pushable filters should push fully", r.pushed());
        assertNull(r.residual());
    }

    @Test
    public void testEntityIriHonoursCustomDocIdField() {
        FieldDef nameField = new FieldDef("name", FieldType.KEYWORD, null,
            true, true, false, false, false, false);
        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI("http://example.org/Shape"),
            Collections.singleton(NodeFactory.createURI("http://example.org/Thing")),
            "entityUri", "docType",
            Collections.singletonList(nameField),
            Collections.singletonList(occurrence(nameField, "http://example.org/name")),
            Collections.emptyList(),
            Collections.emptyList());

        CqlToLuceneCompiler customCompiler =
            new CqlToLuceneCompiler(new ShaclIndexMapping(Collections.singletonList(profile)));

        CqlExpression expr = new CqlExpression.CqlComparison("=", ENTITY_IRI, SAMPLE_IRI);
        CqlToLuceneCompiler.CompileResult r = customCompiler.compile(expr);

        assertNotNull(r.pushed());
        TermQuery tq = (TermQuery) r.pushed();
        assertEquals("Reserved entityIri must resolve to the configured docIdField",
            "entityUri", tq.getTerm().field());
    }

    @Test
    public void testMultiProfileMismatchedDocIdFieldRejectedAtConstruction() {
        FieldDef nameA = new FieldDef("nameA", FieldType.KEYWORD, null,
            true, true, false, false, false, false);
        FieldDef nameB = new FieldDef("nameB", FieldType.KEYWORD, null,
            true, true, false, false, false, false);
        IndexProfile a = new IndexProfile(
            NodeFactory.createURI("http://example.org/ShapeA"),
            Collections.singleton(NodeFactory.createURI("http://example.org/A")),
            "uri", "docType",
            Collections.singletonList(nameA),
            Collections.singletonList(occurrence(nameA, "http://example.org/nameA")),
            Collections.emptyList(),
            Collections.emptyList());
        IndexProfile b = new IndexProfile(
            NodeFactory.createURI("http://example.org/ShapeB"),
            Collections.singleton(NodeFactory.createURI("http://example.org/B")),
            "entityUri", "docType",
            Collections.singletonList(nameB),
            Collections.singletonList(occurrence(nameB, "http://example.org/nameB")),
            Collections.emptyList(),
            Collections.emptyList());

        try {
            new ShaclIndexMapping(Arrays.asList(a, b));
            fail("Expected TextIndexException for mismatched docIdField across profiles");
        } catch (org.apache.jena.query.text.TextIndexException e) {
            assertTrue("Error message should mention idx:docIdField",
                e.getMessage().contains("idx:docIdField") || e.getMessage().contains("docIdField"));
        }
    }

    private static FieldOccurrence occurrence(FieldDef field, String predicateUri) {
        Node predicate = NodeFactory.createURI(predicateUri);
        return new FieldOccurrence(
            field,
            PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            Collections.singleton(predicate),
            null,
            null,
            null,
            null);
    }

    // ------------------------------------------------------------------
    // Unresolvable and unusable field references (#172)
    // ------------------------------------------------------------------

    @Test
    public void testComparisonByBareFieldNameResolves() {
        // The facet path already accepts a bare name; the filter path must agree, or the
        // same spelling works for faceting and is silently ignored for filtering.
        CqlExpression expr = new CqlExpression.CqlComparison("=", "state", "WA");
        CqlToLuceneCompiler.CompileResult r = compiler.compile(expr);

        assertNotNull("A bare field name should resolve", r.pushed());
        assertNull("and leave no residual", r.residual());
    }

    @Test
    public void testComparisonOnUnknownFieldThrows() {
        // Unresolvable meant a residual, and residuals are discarded, so the clause
        // vanished and the query returned everything.
        CqlExpression expr = new CqlExpression.CqlComparison("=", FP + "nosuchfield", "x");
        TextIndexException e = assertThrows(TextIndexException.class, () -> compiler.compile(expr));
        assertTrue("Message should name the property: " + e.getMessage(),
            e.getMessage().contains("nosuchfield"));
    }

    @Test
    public void testComparisonOnNonIndexedFieldThrows() {
        // 'notes' is declared but not indexed, so it cannot be filtered on. Dropping the
        // clause silently widens the result set just as an unknown field does.
        CqlExpression expr = new CqlExpression.CqlComparison("=", FP + "notes", "x");
        TextIndexException e = assertThrows(TextIndexException.class, () -> compiler.compile(expr));
        assertTrue("Message should name the property: " + e.getMessage(),
            e.getMessage().contains("notes"));
    }

    @Test
    public void testInOnUnknownFieldThrows() {
        CqlExpression expr = new CqlExpression.CqlIn(FP + "nosuchfield", List.of("a", "b"));
        assertThrows(TextIndexException.class, () -> compiler.compile(expr));
    }

    @Test
    public void testBetweenOnUnknownFieldThrows() {
        CqlExpression expr = new CqlExpression.CqlBetween(FP + "nosuchfield", 1, 2);
        assertThrows(TextIndexException.class, () -> compiler.compile(expr));
    }

    @Test
    public void testLikeOnUnknownFieldThrows() {
        CqlExpression expr = new CqlExpression.CqlLike(FP + "nosuchfield", "a%");
        assertThrows(TextIndexException.class, () -> compiler.compile(expr));
    }

    @Test
    public void testTextQueryOnUnknownFieldThrows() {
        CqlExpression expr = new CqlExpression.CqlTextQuery(FP + "nosuchfield", "term");
        assertThrows(TextIndexException.class, () -> compiler.compile(expr));
    }

    @Test
    public void testUnknownFieldInsideAndThrows() {
        // The AND fold keeps pushable siblings and drops the residual, so this used to
        // return the state match unfiltered by the second clause.
        CqlExpression expr = new CqlExpression.CqlAnd(List.of(
            new CqlExpression.CqlComparison("=", FP + "state", "WA"),
            new CqlExpression.CqlComparison("=", FP + "nosuchfield", "x")));
        assertThrows(TextIndexException.class, () -> compiler.compile(expr));
    }

    @Test
    public void testLikeOnANumericFieldThrows() {
        CqlExpression like = new CqlExpression.CqlLike(FP + "year", "20%");
        TextIndexException e = assertThrows(TextIndexException.class, () -> compiler.compile(like));
        assertTrue("Message should name the type: " + e.getMessage(), e.getMessage().contains("INT"));
    }

    @Test
    public void testTextQueryOnANumericFieldThrows() {
        CqlExpression tq = new CqlExpression.CqlTextQuery(FP + "year", "2023");
        assertThrows(TextIndexException.class, () -> compiler.compile(tq));
    }

    @Test
    public void testRangeOnAKeywordFieldThrows() {
        // A keyword has no ordering the range operators can use.
        CqlExpression expr = new CqlExpression.CqlComparison(">", FP + "state", "WA");
        assertThrows(TextIndexException.class, () -> compiler.compile(expr));
    }
}
