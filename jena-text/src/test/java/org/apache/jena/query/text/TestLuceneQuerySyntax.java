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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The {@code queryString} argument of {@code luc:query} is parsed by Lucene's classic
 * {@link org.apache.lucene.queryparser.classic.QueryParser} (or {@code MultiFieldQueryParser}
 * across several fields), so the whole classic syntax is available on a plain {@code TEXT}
 * field with no extra configuration.
 * <p>
 * These tests pin the forms documented in 10-suggested-configuration.md — wildcard, leading
 * wildcard, fuzzy, phrase, proximity, boolean and required/prohibited terms, plus the
 * {@code "*"} match-all short-circuit.
 */
public class TestLuceneQuerySyntax {

    private static final String NS = "http://example.org/";

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @Before
    public void setUp() {
        TextQuery.init();

        Node titlePred = NodeFactory.createURI(NS + "title");

        // Plain TEXT field, no analyzer override: the index-wide default (StandardAnalyzer).
        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);

        List<FieldOccurrence> rootOccurrences = Collections.singletonList(
            occurrence(titleField, PathFactory.pathLink(titlePred), Collections.singleton(titlePred)));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "PaperShape"),
            Collections.singleton(NodeFactory.createURI(NS + "Paper")),
            "uri", "docType",
            Collections.singletonList(titleField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        config.setValueStored(true);

        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);

        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping);
        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);

        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            addPaper(model, "d1", "Quantum machine learning");
            addPaper(model, "d2", "Machine translation networks");
            addPaper(model, "d3", "Deep learning for physics");
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void addPaper(Model model, String id, String title) {
        Resource paper = ResourceFactory.createResource(NS + id);
        model.add(paper, RDF.type, ResourceFactory.createResource(NS + "Paper"));
        model.add(paper, ResourceFactory.createProperty(NS + "title"), title);
    }

    private static FieldOccurrence occurrence(FieldDef field, Path path, Set<Node> predicates) {
        return new FieldOccurrence(
            field,
            path,
            ShaclIndexAssembler.extractPathVariants(path),
            predicates,
            null, null, null, null);
    }

    @After
    public void tearDown() {
        if (dataset != null) dataset.close();
    }

    @Test
    public void testTrailingWildcard() {
        assertHits("quan*", "d1");
    }

    /** The parsers are built with setAllowLeadingWildcard(true), which Lucene disables by default. */
    @Test
    public void testLeadingWildcard() {
        assertHits("*tum", "d1");
    }

    @Test
    public void testSingleCharacterWildcard() {
        assertHits("qu?ntum", "d1");
    }

    @Test
    public void testGrouping() {
        assertHits("(machine OR deep) AND learning", "d1", "d3");
    }

    /** Boost parses and does not change which documents match, only their scores. */
    @Test
    public void testBoost() {
        assertHits("quantum^4 learning", "d1", "d3");
    }

    /** Lexical term range on a TEXT field: only "deep" falls in [a TO f]. */
    @Test
    public void testTermRange() {
        assertHits("[a TO f]", "d3");
    }

    @Test
    public void testFuzzy() {
        assertHits("learnimg~1", "d1", "d3");
    }

    @Test
    public void testPhraseRequiresAdjacency() {
        assertHits("\"machine learning\"", "d1");
    }

    /** Proximity: "machine" within 2 positions of "networks" — d2 is "machine translation networks". */
    @Test
    public void testProximitySlop() {
        assertHits("\"machine networks\"~2", "d2");
    }

    @Test
    public void testBooleanOperators() {
        assertHits("machine AND learning", "d1");
        assertHits("physics OR translation", "d2", "d3");
    }

    @Test
    public void testRequiredAndProhibitedTerms() {
        assertHits("+learning -physics", "d1");
    }

    /** A bare "*" short-circuits to MatchAllDocsQuery rather than a wildcard on one field. */
    @Test
    public void testMatchAll() {
        assertHits("*", "d1", "d2", "d3");
    }

    private void assertHits(String queryString, String... expectedLocalNames) {
        List<TextHit> hits = textIndex.queryWithCql(
            null, queryString, null, null, null, null, 10, null);
        Set<String> actual = new TreeSet<>();
        for (TextHit hit : hits) {
            actual.add(hit.getNode().getURI().substring(NS.length()));
        }
        assertEquals("query: " + queryString,
            new TreeSet<>(Arrays.asList(expectedLocalNames)), actual);
    }
}
