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
import org.apache.jena.query.text.analyzer.EdgeNGramAnalyzer;
import org.apache.jena.query.text.analyzer.LowerCaseKeywordAnalyzer;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The analyzer configurations a production search UI actually needs on a TEXT field,
 * each exercised through {@code text_query} with the inputs a user would type.
 * <p>
 * Three shapes, indexing the same value:
 * <ul>
 *   <li><b>prose</b> — no {@code idx:analyzer}; whole-word search over descriptions</li>
 *   <li><b>whole-value prefix</b> — {@code EdgeNGramAnalyzer} as-is; typeahead over
 *       identifiers, where the user types from the start and punctuation must not split</li>
 *   <li><b>per-word prefix</b> — {@code EdgeNGramAnalyzer} with {@code tokenized true};
 *       typeahead over names and titles, where any word can be typed</li>
 * </ul>
 * None of them declares {@code idx:queryAnalyzer}: the query side each needs is implied
 * by the index side, and asserting that here keeps the defaulting honest.
 */
public class TestTypeaheadFieldConfigurations {

    private static final String EX = "http://example.org/mining/";
    private static final String FIELD_NS = "urn:jena:lucene:field#";

    private static final Node REPORT_CLASS = NodeFactory.createURI(EX + "MiningReport");
    private static final Node AUTHOR_NAME = NodeFactory.createURI(EX + "authorName");
    private static final Node IDENTIFIER = NodeFactory.createURI(EX + "identifier");

    private static final String JONES = EX + "report-jones";
    private static final String CHEN = EX + "report-chen";
    private static final String SANDERSON = EX + "report-sanderson";

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;
    private ShaclIndexMapping mapping;

    @Before
    public void setUp() {
        TextQuery.init();

        FieldDef title = textField("title", null);
        // Prose search: the index-wide default analyzer (StandardAnalyzer).
        FieldDef nameProse = textField("nameProse", null);
        FieldDef nameWholePrefix = textField("nameWholePrefix", new EdgeNGramAnalyzer(1, 20));
        FieldDef nameWordPrefix = textField("nameWordPrefix", new EdgeNGramAnalyzer(1, 20, true));
        FieldDef idWholePrefix = textField("idWholePrefix", new EdgeNGramAnalyzer(1, 20));
        FieldDef idWordPrefix = textField("idWordPrefix", new EdgeNGramAnalyzer(1, 20, true));

        List<FieldOccurrence> occurrences = List.of(
            occurrence(title, RDFS.label.asNode()),
            occurrence(nameProse, AUTHOR_NAME),
            occurrence(nameWholePrefix, AUTHOR_NAME),
            occurrence(nameWordPrefix, AUTHOR_NAME),
            occurrence(idWholePrefix, IDENTIFIER),
            occurrence(idWordPrefix, IDENTIFIER));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(EX + "MiningReportShape"),
            Collections.singleton(REPORT_CLASS),
            "uri", "docType",
            Arrays.asList(title, nameProse, nameWholePrefix, nameWordPrefix,
                idWholePrefix, idWordPrefix),
            occurrences,
            Collections.emptyList(),
            Collections.emptyList());

        mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        textIndex = new ShaclTextIndexLucene(
            new ByteBuffersDirectory(), new ByteBuffersDirectory(), config);

        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(
            baseDs.asDatasetGraph(), textIndex, mapping);
        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);

        dataset.begin(ReadWrite.WRITE);
        try {
            Model m = dataset.getDefaultModel();
            addReport(m, "report-jones", "Mount Isa Copper Resource Estimation 2023",
                "Dr Sarah Jones", "RPT-MIA-2023-001");
            addReport(m, "report-chen", "Mount Isa Lead-Zinc Exploration Summary",
                "Prof Wei Chen", "RPT-MIA-2021-001");
            // Shares a prefix with Jones — separates prefix matching from word matching.
            addReport(m, "report-sanderson", "Olympic Dam Expansion Feasibility Study",
                "Dr Sarah Sanderson", "RPT-OD-2024-001");
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private static FieldDef textField(String name, org.apache.lucene.analysis.Analyzer analyzer) {
        // No query analyzer supplied — the paired default has to do the right thing.
        return new FieldDef(name, FieldType.TEXT, analyzer, null,
            true, true, false, false, false, false, false,
            NodeFactory.createURI(FIELD_NS + name));
    }

    private static FieldOccurrence occurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(field, PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            Collections.singleton(predicate), null, null, null, null);
    }

    private void addReport(Model m, String id, String label, String authorName, String identifier) {
        Resource report = ResourceFactory.createResource(EX + id);
        m.add(report, RDF.type, ResourceFactory.createResource(EX + "MiningReport"));
        m.add(report, RDFS.label, label);
        m.add(report, ResourceFactory.createProperty(EX + "authorName"), authorName);
        m.add(report, ResourceFactory.createProperty(EX + "identifier"), identifier);
    }

    @After
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    private Set<String> search(String field, String text) {
        String json = "{\"op\":\"text_query\",\"args\":[{\"property\":\"" + FIELD_NS + field
            + "\"},\"" + text + "\"]}";
        Set<String> uris = new HashSet<>();
        for (TextHit hit : textIndex.queryWithCql(null, null, CqlParser.parse(json),
                null, null, null, 10, null)) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }

    private Set<String> searchLike(String field, String pattern) {
        String json = "{\"op\":\"like\",\"args\":[{\"property\":\"" + FIELD_NS + field
            + "\"},\"" + pattern + "\"]}";
        Set<String> uris = new HashSet<>();
        for (TextHit hit : textIndex.queryWithCql(null, null, CqlParser.parse(json),
                null, null, null, 10, null)) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }

    // --- The implied query analyzers --------------------------------------------------

    @Test
    public void testEdgeNGramFieldsGetTheirPairedQueryAnalyzer() {
        assertTrue("whole-value n-grams query as one lowercased keyword",
            mapping.findField(FIELD_NS + "nameWholePrefix").getQueryAnalyzer()
                instanceof LowerCaseKeywordAnalyzer);
        assertTrue("per-word n-grams query through a word tokenizer",
            mapping.findField(FIELD_NS + "nameWordPrefix").getQueryAnalyzer()
                instanceof StandardAnalyzer);
        assertNull("a plain TEXT field keeps the index-wide default",
            mapping.findField(FIELD_NS + "nameProse").getQueryAnalyzer());
    }

    // --- Prose (no idx:analyzer) ------------------------------------------------------

    @Test
    public void testProseFieldMatchesWholeWordsAndPhrases() {
        assertEquals("a single word anywhere in the value",
            Set.of(JONES), search("nameProse", "Jones"));
        assertEquals("a multi-word phrase",
            Set.of(JONES), search("nameProse", "Sarah Jones"));
        assertEquals("case-insensitive",
            Set.of(JONES, SANDERSON), search("nameProse", "sarah"));
        assertEquals("but no prefix matching — this is why typeahead needs n-grams",
            Collections.emptySet(), search("nameProse", "Jon"));
    }

    /**
     * The prefix escape hatch on a plain BM25 field, for when a name box needs to complete
     * a half-typed word and there is no n-gram twin. {@code like} compiles to a
     * {@link org.apache.lucene.search.WildcardQuery}, which is matched against indexed
     * terms <em>without</em> analysis — so the caller must lowercase the pattern itself,
     * and it cannot span two words.
     */
    @Test
    public void testLikeGivesPrefixMatchingOnAProseFieldWithCaveats() {
        assertEquals("a lowercased prefix pattern reaches the indexed token",
            Set.of(JONES), searchLike("nameProse", "jo%"));
        assertEquals("un-lowercased input silently matches nothing",
            Collections.emptySet(), searchLike("nameProse", "Jo%"));
        assertEquals("a wildcard cannot span the space between two tokens",
            Collections.emptySet(), searchLike("nameProse", "sarah jo%"));
    }

    // --- Whole-value prefix (identifiers) ---------------------------------------------

    @Test
    public void testWholeValuePrefixSuitsIdentifiers() {
        assertEquals("typed from the start, punctuation intact",
            Set.of(JONES), search("idWholePrefix", "RPT-MIA-2023"));
        assertEquals("a shorter prefix widens to both MIA reports",
            Set.of(JONES, CHEN), search("idWholePrefix", "RPT-MIA"));
        assertEquals("case-insensitive",
            Set.of(JONES), search("idWholePrefix", "rpt-mia-2023-001"));
        assertEquals("a mid-value fragment cannot match",
            Collections.emptySet(), search("idWholePrefix", "MIA-2023"));
    }

    @Test
    public void testWholeValuePrefixOnANameOnlyMatchesFromTheFirstWord() {
        assertEquals(Set.of(JONES, SANDERSON), search("nameWholePrefix", "Dr Sarah"));
        assertEquals("this is the trap: a surname is not a prefix of the full name",
            Collections.emptySet(), search("nameWholePrefix", "Sarah Jones"));
    }

    // --- Per-word prefix (names, titles) ----------------------------------------------

    @Test
    public void testPerWordPrefixMatchesAnyWordOfAName() {
        assertEquals("a surname on its own",
            Set.of(JONES), search("nameWordPrefix", "Jones"));
        assertEquals("a prefix of a surname — typeahead mid-typing",
            Set.of(JONES), search("nameWordPrefix", "Jon"));
        assertEquals("a given name shared by two people",
            Set.of(JONES, SANDERSON), search("nameWordPrefix", "Sarah"));
        assertEquals("a single letter narrows as the user keeps typing",
            Set.of(JONES, SANDERSON), search("nameWordPrefix", "S"));
    }

    @Test
    public void testPerWordPrefixMatchesMultiWordInputAsAnAdjacentPhrase() {
        assertEquals("the demo's failing input now works",
            Set.of(JONES), search("nameWordPrefix", "Sarah Jones"));
        assertEquals("still a prefix on the last word typed",
            Set.of(JONES), search("nameWordPrefix", "Sarah Jo"));
        assertEquals("full name from the start",
            Set.of(JONES), search("nameWordPrefix", "Dr Sarah Jones"));
        assertEquals("words must be adjacent and in order, not merely both present",
            Collections.emptySet(), search("nameWordPrefix", "Jones Sarah"));
    }

    @Test
    public void testPerWordPrefixSplitsIdentifiersOnPunctuation() {
        assertEquals("a mid-identifier segment becomes reachable",
            Set.of(JONES), search("idWordPrefix", "2023"));
        assertEquals("segments in order still work",
            Set.of(JONES), search("idWordPrefix", "RPT-MIA-2023"));
        assertEquals("and so does a bare prefix of the first segment",
            Set.of(JONES, CHEN, SANDERSON), search("idWordPrefix", "RP"));
    }
}
