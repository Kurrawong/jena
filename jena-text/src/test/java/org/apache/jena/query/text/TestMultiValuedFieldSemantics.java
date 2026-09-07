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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * How a multi-valued field quantifies over its values.
 * <p>
 * Asked while reviewing the spatial case, where {@code s_within} on a multi-valued
 * geometry requires <em>every</em> shape to satisfy the relation, against GeoSPARQL's
 * existential reading. The obvious follow-up is whether ordinary multi-valued fields have
 * the same problem.
 * <p>
 * They do not, and the reason is structural rather than lucky. A scalar field is an
 * inverted index: a document matches a term or range query if <em>any</em> of its values
 * matches, so every positive predicate is existential for free. Negation is then built as
 * {@code MatchAll MUST_NOT <positive>}, which inverts the quantifier to "no value
 * matches" — and that is what people mean by {@code <>} on a multi-valued field.
 * <p>
 * A shape field is not an inverted index over values. Lucene evaluates
 * {@code WITHIN}/{@code CONTAINS}/{@code DISJOINT} over every triangle on the document at
 * once, treating them as one composite geometry, and offers no existential form except
 * {@code INTERSECTS}. That is why the spatial case needs separate work and this one does
 * not.
 * <p>
 * The other side of the contrast is pinned in {@code TestSpatialFiltering} by
 * {@code testWithinOnMultiValuedFieldRequiresEveryShape} and
 * {@code testDisjointOnMultiValuedFieldRequiresEveryShape}, and the divergence those
 * record is Kurrawong/jena#179. Read together, the two files say the asymmetry is a
 * property of the index structure and not of the filter language: nothing here needs the
 * fix that one does.
 * <p>
 * Each test below discriminates between the two readings rather than merely exercising
 * the query. The {@code mixed} entity carries Gold <em>and</em> Copper, depth 50
 * <em>and</em> 900: an all-of implementation would drop it from every positive query
 * here, and an existential negation would wrongly return it for {@code <> Gold}.
 */
public class TestMultiValuedFieldSemantics {

    private static final String NS = "http://example.org/";
    private static final String FP = "urn:jena:lucene:field#";

    private static final Node THING = NodeFactory.createURI(NS + "Thing");
    private static final Node COMMODITY = NodeFactory.createURI(NS + "commodity");
    private static final Node DEPTH = NodeFactory.createURI(NS + "depth");
    private static final Node NOTE = NodeFactory.createURI(NS + "note");

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @Before
    public void setUp() {
        // multiValued on all three: one entity carries several commodities, several
        // depths and several notes.
        FieldDef commodity = new FieldDef("commodity", FieldType.KEYWORD, null,
            true, true, false, false, true, false, false,
            NodeFactory.createURI(FP + "commodity"));
        FieldDef depth = new FieldDef("depth", FieldType.INT, null,
            true, true, false, true, true, false, false,
            NodeFactory.createURI(FP + "depth"));
        FieldDef note = new FieldDef("note", FieldType.TEXT, null,
            true, true, false, false, true, true, false,
            NodeFactory.createURI(FP + "note"));

        List<FieldOccurrence> occurrences = List.of(
            occurrence(commodity, COMMODITY),
            occurrence(depth, DEPTH),
            occurrence(note, NOTE));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "ThingShape"),
            Collections.singleton(THING),
            "uri", "docType",
            Arrays.asList(commodity, depth, note),
            occurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        config.setValueStored(true);

        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);

        Dataset baseDs = DatasetFactory.create();
        dataset = TextDatasetFactory.create(baseDs, textIndex, true,
            new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping));

        dataset.begin(ReadWrite.WRITE);
        try {
            Model m = dataset.getDefaultModel();
            // Gold AND Copper, shallow AND deep, two notes.
            addThing(m, "mixed", new String[] { "Gold", "Copper" },
                new int[] { 50, 900 }, new String[] { "alpha reading", "beta reading" });
            // Gold only, shallow only.
            addThing(m, "gold-only", new String[] { "Gold" },
                new int[] { 60 }, new String[] { "alpha reading" });
            // Neither.
            addThing(m, "iron-only", new String[] { "Iron" },
                new int[] { 70 }, new String[] { "gamma reading" });
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void addThing(Model m, String id, String[] commodities, int[] depths, String[] notes) {
        Resource r = ResourceFactory.createResource(NS + id);
        m.add(r, RDF.type, ResourceFactory.createResource(NS + "Thing"));
        for (String c : commodities) m.add(r, ResourceFactory.createProperty(NS, "commodity"), c);
        for (int d : depths) m.addLiteral(r, ResourceFactory.createProperty(NS, "depth"), d);
        for (String n : notes) m.add(r, ResourceFactory.createProperty(NS, "note"), n);
    }

    private static FieldOccurrence occurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(field, PathFactory.pathLink(predicate),
            List.of(List.of(new JoinStep(predicate, false))),
            Collections.singleton(predicate), null, null, null, null);
    }

    @After
    public void tearDown() {
        if (dataset != null) dataset.close();
    }

    private Set<String> urisFor(CqlExpression filter) {
        Set<String> uris = new HashSet<>();
        for (TextHit hit : textIndex.queryWithCql(null, "*", filter, null, null, null, 100, null)) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }

    // --- positive predicates are existential, which is what people mean ---------

    @Test
    public void testEqualityMatchesIfAnyValueMatches() {
        Set<String> uris = urisFor(new CqlExpression.CqlComparison("=", FP + "commodity", "Gold"));
        assertTrue("mixed has Gold among its commodities", uris.contains(NS + "mixed"));
        assertTrue(uris.contains(NS + "gold-only"));
        assertFalse(uris.contains(NS + "iron-only"));
    }

    @Test
    public void testOneEntityMatchesTwoMutuallyExclusiveValues() {
        // The direct proof, and the one assertion that cannot be satisfied by an all-of
        // implementation however it is written: no single value is both Gold and Copper,
        // so requiring every value to match could not return 'mixed' for both queries.
        // Existential quantification is the only reading that does.
        assertTrue("mixed matches = Gold",
            urisFor(new CqlExpression.CqlComparison("=", FP + "commodity", "Gold"))
                .contains(NS + "mixed"));
        assertTrue("and the same entity matches = Copper",
            urisFor(new CqlExpression.CqlComparison("=", FP + "commodity", "Copper"))
                .contains(NS + "mixed"));
    }

    @Test
    public void testInMatchesIfAnyValueMatches() {
        Set<String> uris = urisFor(new CqlExpression.CqlIn(FP + "commodity", List.of("Copper", "Iron")));
        assertTrue("mixed has Copper", uris.contains(NS + "mixed"));
        assertTrue(uris.contains(NS + "iron-only"));
        assertFalse(uris.contains(NS + "gold-only"));
    }

    @Test
    public void testRangeMatchesIfAnyValueIsInRange() {
        // mixed has depths 50 and 900, so it satisfies both a shallow and a deep range.
        assertTrue("shallow value counts",
            urisFor(new CqlExpression.CqlBetween(FP + "depth", 0, 100)).contains(NS + "mixed"));
        assertTrue("deep value counts too",
            urisFor(new CqlExpression.CqlBetween(FP + "depth", 800, 1000)).contains(NS + "mixed"));
    }

    @Test
    public void testTextQueryMatchesIfAnyValueMatches() {
        Set<String> uris = urisFor(new CqlExpression.CqlTextQuery(FP + "note", "beta"));
        assertTrue("only mixed carries the beta note", uris.contains(NS + "mixed"));
        assertFalse(uris.contains(NS + "gold-only"));
    }

    // --- negation inverts the quantifier, which is also what people mean --------

    @Test
    public void testNotEqualMeansNoValueMatches() {
        // The one case where the quantifier is universal, and rightly so. 'mixed' has
        // Copper as well as Gold, so an existential reading would return it for
        // "commodity <> Gold". Nobody means that: they mean "has no Gold".
        Set<String> uris = urisFor(new CqlExpression.CqlComparison("<>", FP + "commodity", "Gold"));

        assertFalse("mixed has Gold, so it is excluded", uris.contains(NS + "mixed"));
        assertFalse(uris.contains(NS + "gold-only"));
        assertTrue("iron-only has no Gold", uris.contains(NS + "iron-only"));
    }

    @Test
    public void testNotEqualIsNotSimplyTheComplementOfSomeOtherValue() {
        // Guards the reading above: 'mixed' does hold a commodity that is not Gold, so
        // this only passes because the query asks "no value is Gold" rather than
        // "some value is not Gold".
        assertTrue("mixed really does carry a non-Gold commodity",
            urisFor(new CqlExpression.CqlComparison("=", FP + "commodity", "Copper"))
                .contains(NS + "mixed"));
    }
}
