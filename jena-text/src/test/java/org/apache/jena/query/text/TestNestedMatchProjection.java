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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.HierarchyDef;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Projection of the <em>child records that satisfied the filter</em> back out of the
 * block-join index — {@link SearchHit#getNestedMatches()} and the {@code luc:nestedMatch}
 * property function.
 * <p>
 * The fixture is the {@code prov:qualifiedAttribution} scope also used by
 * {@link TestCorrelatedNestedAttribution}: each report carries two attribution children,
 * so "which child matched" has a wrong answer available and the tests can tell the two
 * apart. The grouping node is the point of the feature — with two matching children,
 * a flat (field, value) stream cannot say which role goes with which agent.
 */
public class TestNestedMatchProjection {

    private static final String EX = "http://example.org/mining/";
    private static final String PROV = "http://www.w3.org/ns/prov#";
    private static final String FIELD_NS = "urn:jena:lucene:field#";

    private static final Node REPORT_CLASS = NodeFactory.createURI(EX + "MiningReport");
    private static final Node LABEL_PRED = RDFS.label.asNode();
    private static final Node QUALIFIED_ATTRIBUTION = NodeFactory.createURI(PROV + "qualifiedAttribution");
    private static final Node HAD_ROLE = NodeFactory.createURI(PROV + "hadRole");
    private static final Node AGENT = NodeFactory.createURI(PROV + "agent");
    private static final Node NOTE = NodeFactory.createURI(PROV + "note");

    private static final String ATTRIBUTION_SCOPE =
        PathFactory.pathLink(QUALIFIED_ATTRIBUTION).toString();

    private static final String ROLE_FIELD = FIELD_NS + "attributionRole";
    private static final String AGENT_FIELD = FIELD_NS + "attributionAgentExact";
    private static final String NOTE_FIELD = FIELD_NS + "attributionNote";
    private static final String ROLE_AGENT_DIM = "attributionRole_attributionAgentExact";

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @BeforeEach
    public void setUp() {
        TextQuery.init();

        FieldDef entityType = new FieldDef("entityType", FieldType.KEYWORD, null, null,
            true, true, true, false, false, false, false,
            NodeFactory.createURI(FIELD_NS + "entityType"));

        FieldDef title = new FieldDef("title", FieldType.TEXT, null, null,
            true, true, false, false, false, true, false,
            NodeFactory.createURI(FIELD_NS + "title"));

        FieldDef attributionRole = new FieldDef("attributionRole", FieldType.KEYWORD, null, null,
            true, true, true, false, true, false, false,
            NodeFactory.createURI(ROLE_FIELD));

        FieldDef attributionAgentExact = new FieldDef("attributionAgentExact", FieldType.KEYWORD, null, null,
            true, true, false, false, true, false, false,
            NodeFactory.createURI(AGENT_FIELD));

        // Indexed but NOT stored: filterable, and deliberately absent from the projection.
        FieldDef attributionNote = new FieldDef("attributionNote", FieldType.KEYWORD, null, null,
            false, true, false, false, true, false, false,
            NodeFactory.createURI(NOTE_FIELD));

        List<FieldOccurrence> rootOccurrences = List.of(
            rootOccurrence(entityType, RDF.type.asNode()),
            rootOccurrence(title, LABEL_PRED));

        List<FieldOccurrence> attributionOccurrences = List.of(
            nestedSeqOccurrence(attributionRole, HAD_ROLE, LABEL_PRED),
            nestedSeqOccurrence(attributionAgentExact, AGENT, LABEL_PRED),
            nestedLinkOccurrence(attributionNote, NOTE));

        // A hierarchy over the scope, as the demo config has over (analyte, gradeUnits).
        // It changes how a LONE '=' on level 0 compiles — see
        // testLevelZeroHierarchyEqualityProjectsNoRecords.
        HierarchyDef roleAgentHierarchy = new HierarchyDef(ROLE_AGENT_DIM,
            Arrays.asList(attributionRole, attributionAgentExact));

        NestedDef attributions = new NestedDef(
            ATTRIBUTION_SCOPE,
            PathFactory.pathLink(QUALIFIED_ATTRIBUTION),
            List.of(new JoinStep(QUALIFIED_ATTRIBUTION, false)),
            Collections.singleton(QUALIFIED_ATTRIBUTION),
            attributionOccurrences,
            Collections.singletonList(roleAgentHierarchy));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(EX + "MiningReportShape"),
            Collections.singleton(REPORT_CLASS),
            "uri", "docType",
            Arrays.asList(entityType, title, attributionRole, attributionAgentExact, attributionNote),
            rootOccurrences,
            Collections.emptyList(),
            Collections.singletonList(attributions));

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        textIndex = new ShaclTextIndexLucene(
            new ByteBuffersDirectory(), new ByteBuffersDirectory(), config);

        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(
            baseDs.asDatasetGraph(), textIndex, mapping);

        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);
        loadData();
    }

    private void loadData() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model m = dataset.getDefaultModel();

            addRole(m, "PrincipalInvestigator", "Principal Investigator");
            addRole(m, "Reviewer", "Reviewer");

            addAgent(m, "author-jones", "Dr Sarah Jones");
            addAgent(m, "author-patel", "Dr Priya Patel");
            addAgent(m, "author-chen", "Prof Wei Chen");

            addReport(m, "report-mia-2023", "Mount Isa Copper Resource Estimation 2023",
                new String[][] {
                    {"PrincipalInvestigator", "author-jones"},
                    {"Reviewer", "author-patel"}
                });

            // Jones is only the Reviewer here.
            addReport(m, "report-mia-2021", "Mount Isa Lead-Zinc Exploration Summary",
                new String[][] {
                    {"PrincipalInvestigator", "author-chen"},
                    {"Reviewer", "author-jones"}
                });

            addReport(m, "report-bod-2022", "Boddington Gold Production Report 2022",
                new String[][] {
                    {"PrincipalInvestigator", "author-patel"},
                    {"Reviewer", "author-chen"}
                });

            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void addRole(Model m, String id, String label) {
        m.add(ResourceFactory.createResource(EX + id), RDFS.label, label);
    }

    private void addAgent(Model m, String id, String label) {
        Resource agent = ResourceFactory.createResource(EX + id);
        m.add(agent, RDF.type, ResourceFactory.createResource(EX + "Author"));
        m.add(agent, RDFS.label, label);
    }

    private void addReport(Model m, String id, String label, String[][] attributions) {
        Resource report = ResourceFactory.createResource(EX + id);
        m.add(report, RDF.type, ResourceFactory.createResource(EX + "MiningReport"));
        m.add(report, RDFS.label, label);
        for (int i = 0; i < attributions.length; i++) {
            Resource attribution = ResourceFactory.createResource(EX + id + "-attribution-" + i);
            m.add(report, ResourceFactory.createProperty(PROV + "qualifiedAttribution"), attribution);
            m.add(attribution, ResourceFactory.createProperty(PROV + "hadRole"),
                ResourceFactory.createResource(EX + attributions[i][0]));
            m.add(attribution, ResourceFactory.createProperty(PROV + "agent"),
                ResourceFactory.createResource(EX + attributions[i][1]));
            m.add(attribution, ResourceFactory.createProperty(PROV + "note"),
                "internal-" + attributions[i][0]);
        }
    }

    private static FieldOccurrence rootOccurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(field, PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            Collections.singleton(predicate),
            null, null, null, null);
    }

    private static FieldOccurrence nestedSeqOccurrence(FieldDef field, Node first, Node second) {
        return new FieldOccurrence(field,
            PathFactory.pathSeq(PathFactory.pathLink(first), PathFactory.pathLink(second)),
            List.of(List.of(new JoinStep(first, false), new JoinStep(second, false))),
            new LinkedHashSet<>(Arrays.asList(first, second)),
            null, null, null, ATTRIBUTION_SCOPE);
    }

    private static FieldOccurrence nestedLinkOccurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(field, PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            Collections.singleton(predicate),
            null, null, null, ATTRIBUTION_SCOPE);
    }

    @AfterEach
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    // ---- helpers ---------------------------------------------------------------------

    private List<SearchHit> search(String filterJson) {
        return textIndex.searchWithHitIds(
            null, null, CqlParser.parse(filterJson), null, null, null, 10);
    }

    private static SearchHit hitFor(List<SearchHit> hits, String uri) {
        for (SearchHit hit : hits) {
            if (hit.getEntityNode().isURI() && hit.getEntityNode().getURI().equals(uri)) {
                return hit;
            }
        }
        return null;
    }

    /** field IRI → values, for one projected child record. */
    private static Map<String, List<String>> fieldsOf(NestedMatch record) {
        Map<String, List<String>> byField = new HashMap<>();
        for (FieldMatch fm : record.getFieldMatches()) {
            byField.computeIfAbsent(fm.getFieldIRI().getURI(), k -> new ArrayList<>())
                .add(fm.getValue() == null ? null : fm.getValue().getLiteralLexicalForm());
        }
        return byField;
    }

    private static final String PI_AND_JONES = """
        {"op":"and","args":[
          {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Principal Investigator"]},
          {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionAgentExact"},"Dr Sarah Jones"]}
        ]}
        """;

    /** Both attributions of mia-2023 match, one clause each — two children, one parent. */
    private static final String PI_OR_REVIEWER = """
        {"op":"or","args":[
          {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Principal Investigator"]},
          {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Reviewer"]}
        ]}
        """;

    // ---- index layer -----------------------------------------------------------------

    @Test
    public void testMatchingChildRecordIsProjected() {
        List<SearchHit> hits = search(PI_AND_JONES);
        assertEquals(1, hits.size(), "only mia-2023 has Jones as Principal Investigator");

        List<NestedMatch> records = hits.get(0).getNestedMatches();
        assertEquals(1, records.size(), "exactly one attribution child satisfied the filter");

        Map<String, List<String>> fields = fieldsOf(records.get(0));
        assertEquals(List.of("Principal Investigator"), fields.get(ROLE_FIELD));
        assertEquals(List.of("Dr Sarah Jones"), fields.get(AGENT_FIELD));
    }

    @Test
    public void testNonMatchingSiblingChildIsNotProjected() {
        List<SearchHit> hits = search(PI_AND_JONES);
        List<NestedMatch> records = hits.get(0).getNestedMatches();

        Set<String> values = new HashSet<>();
        for (NestedMatch record : records) {
            for (List<String> vs : fieldsOf(record).values()) {
                values.addAll(vs);
            }
        }
        assertFalse(values.contains("Reviewer"),
            "the sibling attribution did not satisfy the filter");
        assertFalse(values.contains("Dr Priya Patel"),
            "the sibling attribution's agent must not leak into the projection");
    }

    @Test
    public void testUnstoredNestedFieldIsNotProjected() {
        List<SearchHit> hits = search(PI_AND_JONES);
        Map<String, List<String>> fields = fieldsOf(hits.get(0).getNestedMatches().get(0));
        assertFalse(fields.containsKey(NOTE_FIELD),
            "attributionNote is idx:stored false — indexed, but nothing to project");
    }

    /**
     * The case a flat (field, value) stream cannot express: two children of the same
     * parent match, and each pairs its own role with its own agent.
     */
    @Test
    public void testTwoMatchingChildrenAreProjectedAsSeparateRecords() {
        List<SearchHit> hits = search(PI_OR_REVIEWER);
        SearchHit mia2023 = hitFor(hits, EX + "report-mia-2023");
        assertNotNull(mia2023, "mia-2023 matches both branches of the OR");

        List<NestedMatch> records = mia2023.getNestedMatches();
        assertEquals(2, records.size(), "both attribution children satisfied the filter");

        assertEquals(2, new HashSet<>(List.of(
                records.get(0).getRecordId(), records.get(1).getRecordId())).size(),
            "each child record gets its own grouping node");

        Map<String, String> roleToAgent = new HashMap<>();
        for (NestedMatch record : records) {
            Map<String, List<String>> fields = fieldsOf(record);
            roleToAgent.put(fields.get(ROLE_FIELD).get(0), fields.get(AGENT_FIELD).get(0));
        }
        assertEquals(Map.of(
                "Principal Investigator", "Dr Sarah Jones",
                "Reviewer", "Dr Priya Patel"),
            roleToAgent,
            "role and agent stay paired within each child record");
    }

    @Test
    public void testRootOnlyFilterProjectsNoRecords() {
        List<SearchHit> hits = search("""
            {"op":"=","args":[{"property":"urn:jena:lucene:field#entityType"},"http://example.org/mining/MiningReport"]}
            """);
        assertEquals(3, hits.size(), "all three reports match the root clause");
        for (SearchHit hit : hits) {
            assertTrue(hit.getNestedMatches().isEmpty(),
                "no nested clause in the filter, so no child was selected by one");
        }
    }

    /**
     * A lone {@code =} on the <em>first level</em> of an {@code idx:facetHierarchy}
     * projects, like any other nested clause.
     * <p>
     * It did not before: the compiler answered a level-0 equality with a taxonomy
     * {@code DrillDownQuery} against the parent's facet ordinals, which matches the same
     * parents but carries no child query. That made a single facet tick — the most
     * ordinary interaction a UI has, and the one that emits {@code =} rather than
     * {@code in} — the one case where the matching child could not be recovered.
     * <p>
     * {@code =} and {@code in} over the same single value must now agree on both the
     * entities and the projection.
     */
    @Test
    public void testLevelZeroHierarchyEqualityProjectsLikeAnyNestedClause() {
        List<SearchHit> viaEquality = search("""
            {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Principal Investigator"]}
            """);
        assertEquals(3, viaEquality.size(), "every report has a Principal Investigator");
        for (SearchHit hit : viaEquality) {
            assertEquals(1, hit.getNestedMatches().size(),
                "the attribution that carried the matching role is recoverable");
        }

        List<SearchHit> viaIn = search("""
            {"op":"in","args":[{"property":"urn:jena:lucene:field#attributionRole"},["Principal Investigator"]]}
            """);
        assertEquals(3, viaIn.size(), "'in' over one value selects the same entities");
        for (SearchHit hit : viaIn) {
            assertEquals(1, hit.getNestedMatches().size(),
                "and projects the same single child record");
        }
    }

    /**
     * The projection must not depend on whether a field happens to be declared as a
     * hierarchy level: {@code attributionRole} is level 0 of one, {@code attributionNote}
     * belongs to no hierarchy at all, and a lone equality on either behaves the same way.
     */
    @Test
    public void testHierarchyAndNonHierarchyFieldsProjectAlike() {
        List<SearchHit> viaHierarchyField = search("""
            {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Reviewer"]}
            """);
        List<SearchHit> viaPlainField = search("""
            {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionNote"},"internal-Reviewer"]}
            """);

        assertEquals(viaPlainField.size(), viaHierarchyField.size(),
            "the two clauses select the same reports");
        for (SearchHit hit : viaHierarchyField) {
            assertEquals(1, hit.getNestedMatches().size(),
                "hierarchy level-0 field projects one child");
        }
        for (SearchHit hit : viaPlainField) {
            assertEquals(1, hit.getNestedMatches().size(),
                "non-hierarchy field projects one child");
        }
    }

    @Test
    public void testNegatedNestedClauseProjectsNoRecords() {
        List<SearchHit> hits = search("""
            {"op":"and","args":[
              {"op":"=","args":[{"property":"urn:jena:lucene:field#entityType"},"http://example.org/mining/MiningReport"]},
              {"op":"not","args":[
                {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionAgentExact"},"Dr Sarah Jones"]}
              ]}
            ]}
            """);
        assertEquals(1, hits.size(), "only bod-2022 has no Jones attribution");
        assertTrue(hits.get(0).getNestedMatches().isEmpty(),
            "a negated clause describes children that must NOT match — they are not results");
    }

    // ---- property function layer ------------------------------------------------------

    @Test
    public void testNestedMatchPFGroupsFieldsByRecord() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?s ?record ?field ?value WHERE {\n"
            + "  (?hit ?s) luc:query (\"default\" \"default\" \"\" '" + inline(PI_OR_REVIEWER) + "' \"\" 10 0) .\n"
            + "  (?hit ?record ?field ?value) luc:nestedMatch () .\n"
            + "}";

        Map<String, Map<String, String>> byRecord = new HashMap<>();
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                assertNotNull(sol.get("record"), "?record must be bound");
                if (!sol.getResource("s").getURI().equals(EX + "report-mia-2023")) {
                    continue;
                }
                byRecord
                    .computeIfAbsent(sol.get("record").toString(), k -> new HashMap<>())
                    .put(sol.getResource("field").getURI(), sol.getLiteral("value").getLexicalForm());
            }
        } finally {
            dataset.end();
        }

        assertEquals(2, byRecord.size(), "two child records for mia-2023");
        Set<Map<String, String>> rows = new HashSet<>(byRecord.values());
        assertEquals(Set.of(
                Map.of(ROLE_FIELD, "Principal Investigator", AGENT_FIELD, "Dr Sarah Jones"),
                Map.of(ROLE_FIELD, "Reviewer", AGENT_FIELD, "Dr Priya Patel")),
            rows,
            "each ?record groups one child's fields, correlated");
    }

    @Test
    public void testNestedMatchPFJoinsOnBoundHit() {
        String sparql = "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?s ?value WHERE {\n"
            + "  (?hit ?s) luc:query (\"default\" \"default\" \"\" '" + inline(PI_AND_JONES) + "' \"\" 10 0) .\n"
            + "  (?hit ?record ?field ?value) luc:nestedMatch () .\n"
            + "  FILTER (?field = <" + AGENT_FIELD + ">)\n"
            + "}";

        Set<String> agents = new HashSet<>();
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                agents.add(rs.next().getLiteral("value").getLexicalForm());
            }
        } finally {
            dataset.end();
        }
        assertEquals(Set.of("Dr Sarah Jones"), agents,
            "only the agent on the child that satisfied both clauses");
    }

    /** Collapse a text block into one line so it can sit inside a SPARQL string literal. */
    private static String inline(String json) {
        return json.replaceAll("\\s+", " ").trim();
    }
}
