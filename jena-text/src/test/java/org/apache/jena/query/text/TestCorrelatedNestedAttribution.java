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

import static org.junit.Assert.*;

import java.util.*;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.analyzer.EdgeNGramAnalyzer;
import org.apache.jena.query.text.analyzer.LowerCaseKeywordAnalyzer;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Same-child correlation on a {@code prov:qualifiedAttribution} scope, where role and agent
 * are reached by sequence paths through the attribution node.
 * <p>
 * The supported shape is two {@code =} clauses on KEYWORD fields, both values picked from
 * a list — that is what the demo sends and what docs/03-configuration.md recommends. The
 * n-gram twins of the agent name are also exercised, because the configuration is
 * permitted and its failure mode (an empty result that looks like broken correlation) is
 * worth pinning; they are not the recommended way to filter by a name.
 */
public class TestCorrelatedNestedAttribution {

    private static final String EX = "http://example.org/mining/";
    private static final String PROV = "http://www.w3.org/ns/prov#";
    private static final String FIELD_NS = "urn:jena:lucene:field#";

    private static final Node REPORT_CLASS = NodeFactory.createURI(EX + "MiningReport");
    private static final Node LABEL_PRED = RDFS.label.asNode();
    private static final Node QUALIFIED_ATTRIBUTION = NodeFactory.createURI(PROV + "qualifiedAttribution");
    private static final Node HAD_ROLE = NodeFactory.createURI(PROV + "hadRole");
    private static final Node AGENT = NodeFactory.createURI(PROV + "agent");

    private static final String ATTRIBUTION_SCOPE =
        PathFactory.pathLink(QUALIFIED_ATTRIBUTION).toString();

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @Before
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
            NodeFactory.createURI(FIELD_NS + "attributionRole"));

        FieldDef attributionAgentExact = new FieldDef("attributionAgentExact", FieldType.KEYWORD, null, null,
            true, true, false, false, true, false, false,
            NodeFactory.createURI(FIELD_NS + "attributionAgentExact"));

        // Demo config: EdgeNGramAnalyzer for indexing, LowerCaseKeywordAnalyzer for querying.
        // Whole-value prefixes only — see testNonPrefixAgentTextCannotMatchEdgeNGramField.
        FieldDef attributionAgentText = new FieldDef("attributionAgentText", FieldType.TEXT,
            new EdgeNGramAnalyzer(1, 20), new LowerCaseKeywordAnalyzer(),
            true, true, false, false, true, false, false,
            NodeFactory.createURI(FIELD_NS + "attributionAgentText"));

        // What a name field should use: per-word prefixes, query analyzer left implied.
        FieldDef attributionAgentWordText = new FieldDef("attributionAgentWordText", FieldType.TEXT,
            new EdgeNGramAnalyzer(1, 20, true), null,
            true, true, false, false, true, false, false,
            NodeFactory.createURI(FIELD_NS + "attributionAgentWordText"));

        List<FieldOccurrence> rootOccurrences = List.of(
            rootOccurrence(entityType, RDF.type.asNode()),
            rootOccurrence(title, LABEL_PRED));

        // Inside the attribution child: (prov:hadRole / rdfs:label) and (prov:agent / rdfs:label).
        List<FieldOccurrence> attributionOccurrences = List.of(
            nestedSeqOccurrence(attributionRole, HAD_ROLE, LABEL_PRED),
            nestedSeqOccurrence(attributionAgentExact, AGENT, LABEL_PRED),
            nestedSeqOccurrence(attributionAgentText, AGENT, LABEL_PRED),
            nestedSeqOccurrence(attributionAgentWordText, AGENT, LABEL_PRED));

        NestedDef attributions = new NestedDef(
            ATTRIBUTION_SCOPE,
            PathFactory.pathLink(QUALIFIED_ATTRIBUTION),
            List.of(new JoinStep(QUALIFIED_ATTRIBUTION, false)),
            Collections.singleton(QUALIFIED_ATTRIBUTION),
            attributionOccurrences,
            Collections.emptyList());

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(EX + "MiningReportShape"),
            Collections.singleton(REPORT_CLASS),
            "uri", "docType",
            Arrays.asList(entityType, title, attributionRole, attributionAgentExact,
                attributionAgentText, attributionAgentWordText),
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

            // Jones is the PI here — the one report the demo filter should return.
            addReport(m, "report-mia-2023", "Mount Isa Copper Resource Estimation 2023",
                new String[][] {
                    {"PrincipalInvestigator", "author-jones"},
                    {"Reviewer", "author-patel"}
                });

            // Jones appears, but only as Reviewer; Chen is the PI. A cross-child match
            // (role from one attribution, agent from another) would wrongly surface this.
            addReport(m, "report-mia-2021", "Mount Isa Lead-Zinc Exploration Summary",
                new String[][] {
                    {"PrincipalInvestigator", "author-chen"},
                    {"Reviewer", "author-jones"}
                });

            // No Jones at all.
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

    /** {@code attributions} is an array of {roleId, agentId} pairs, one per attribution node. */
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
        }
    }

    private static FieldOccurrence rootOccurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(field, PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            Collections.singleton(predicate),
            null, null, null, null);
    }

    /** A two-step sequence path evaluated relative to the attribution child node. */
    private static FieldOccurrence nestedSeqOccurrence(FieldDef field, Node first, Node second) {
        return new FieldOccurrence(field,
            PathFactory.pathSeq(PathFactory.pathLink(first), PathFactory.pathLink(second)),
            List.of(List.of(new JoinStep(first, false), new JoinStep(second, false))),
            new LinkedHashSet<>(Arrays.asList(first, second)),
            null, null, null, ATTRIBUTION_SCOPE);
    }

    @After
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    private Set<String> subjects(List<TextHit> hits) {
        Set<String> uris = new HashSet<>();
        for (TextHit hit : hits) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }

    private Set<String> query(String filterJson) {
        return subjects(textIndex.queryWithCql(
            null, null, CqlParser.parse(filterJson), null, null, null, 10, null));
    }

    // --- Sanity: the nested fields are indexed at all ---------------------------------

    @Test
    public void testRoleAloneMatches() {
        assertEquals("every report has a Principal Investigator",
            Set.of(EX + "report-mia-2023", EX + "report-mia-2021", EX + "report-bod-2022"),
            query("""
                {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Principal Investigator"]}
                """));
    }

    @Test
    public void testAgentExactAloneMatches() {
        assertEquals("Jones is attributed on both Mount Isa reports",
            Set.of(EX + "report-mia-2023", EX + "report-mia-2021"),
            query("""
                {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionAgentExact"},"Dr Sarah Jones"]}
                """));
    }

    @Test
    public void testAgentTextPrefixAloneMatches() {
        assertEquals("EdgeNGram indexing makes 'Dr Sarah' a prefix of 'Dr Sarah Jones'",
            Set.of(EX + "report-mia-2023", EX + "report-mia-2021"),
            query("""
                {"op":"text_query","args":[{"property":"urn:jena:lucene:field#attributionAgentText"},"Dr Sarah"]}
                """));
    }

    // --- The correlation itself -------------------------------------------------------

    /** The recommended shape: both sides exact, both values from a picklist. */
    @Test
    public void testRolePlusAgentExactCorrelatesOnSameAttribution() {
        assertEquals("only mia-2023 has Jones as the Principal Investigator",
            Set.of(EX + "report-mia-2023"),
            query("""
                {"op":"and","args":[
                  {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Principal Investigator"]},
                  {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionAgentExact"},"Dr Sarah Jones"]}
                ]}
                """));
    }

    @Test
    public void testRolePlusAgentTextPrefixCorrelatesOnSameAttribution() {
        assertEquals("text_query folds with the sibling role clause onto one attribution child",
            Set.of(EX + "report-mia-2023"),
            query("""
                {"op":"and","args":[
                  {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Principal Investigator"]},
                  {"op":"text_query","args":[{"property":"urn:jena:lucene:field#attributionAgentText"},"Dr Sarah"]}
                ]}
                """));
    }

    /**
     * The demo filter's shape: a root {@code entityType} clause AND a nested AND holding
     * the correlated pair. The fold has to run on the inner AND's args, not just the
     * top-level ones.
     */
    @Test
    public void testDemoFilterShapeWithNestedAnd() {
        assertEquals("nested AND subtree still folds the same-scope pair",
            Set.of(EX + "report-mia-2023"),
            query("""
                {"op":"and","args":[
                  {"op":"=","args":[{"property":"urn:jena:lucene:field#entityType"},"http://example.org/mining/MiningReport"]},
                  {"op":"and","args":[
                    {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Principal Investigator"]},
                    {"op":"text_query","args":[{"property":"urn:jena:lucene:field#attributionAgentText"},"Dr Sarah"]}
                  ]}
                ]}
                """));
    }

    /**
     * The exact demo case ("Qualified attribution: PI + Sarah Jones"), which returns
     * nothing in the running demo. The correlation is fine — see the tests above; the
     * miss is the analyzer pairing. {@link EdgeNGramAnalyzer} uses a KeywordTokenizer, so
     * the indexed terms are prefixes of the whole label ("d", "dr", "dr ", … "dr sarah
     * jones") and {@link LowerCaseKeywordAnalyzer} turns the input into the single term
     * "sarah jones", which is a substring but not a prefix. Matching mid-label words would
     * need a word-tokenizing analyzer on the text twin, not a change to the nested join.
     */
    @Test
    public void testNonPrefixAgentTextCannotMatchEdgeNGramField() {
        assertEquals("mid-string text does not match a prefix-only EdgeNGram field",
            Collections.emptySet(),
            query("""
                {"op":"and","args":[
                  {"op":"=","args":[{"property":"urn:jena:lucene:field#entityType"},"http://example.org/mining/MiningReport"]},
                  {"op":"and","args":[
                    {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Principal Investigator"]},
                    {"op":"text_query","args":[{"property":"urn:jena:lucene:field#attributionAgentText"},"Sarah Jones"]}
                  ]}
                ]}
                """));
    }

    /**
     * The same demo filter against a per-word n-gram twin of the agent field: the input the
     * demo actually sends now matches, and the correlation still holds — mia-2021, where
     * Jones is only the Reviewer, stays out.
     */
    @Test
    public void testPerWordAgentTextMatchesTheDemoInputAndStaysCorrelated() {
        assertEquals("word-tokenized n-grams match a surname mid-label",
            Set.of(EX + "report-mia-2023"),
            query("""
                {"op":"and","args":[
                  {"op":"=","args":[{"property":"urn:jena:lucene:field#entityType"},"http://example.org/mining/MiningReport"]},
                  {"op":"and","args":[
                    {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Principal Investigator"]},
                    {"op":"text_query","args":[{"property":"urn:jena:lucene:field#attributionAgentWordText"},"Sarah Jones"]}
                  ]}
                ]}
                """));
    }

    /** Without the role clause, both Mount Isa reports surface — the filter above is doing work. */
    @Test
    public void testPerWordAgentTextAloneMatchesBothMountIsaReports() {
        assertEquals(Set.of(EX + "report-mia-2023", EX + "report-mia-2021"),
            query("""
                {"op":"text_query","args":[{"property":"urn:jena:lucene:field#attributionAgentWordText"},"Sarah Jones"]}
                """));
    }
}
