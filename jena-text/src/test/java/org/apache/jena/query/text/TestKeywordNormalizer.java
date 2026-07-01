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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
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
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.analyzer.LowerCaseKeywordAnalyzer;
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
 * Feature test for {@code idx:normalizer} on a KEYWORD field. The counterpart baseline
 * {@link TestKeywordRawSortAndExactMatch} asserts that a KEYWORD field WITHOUT a normalizer
 * is raw/case-sensitive; this asserts that adding a lower-casing normalizer makes both
 * the sort key and exact match case-insensitive, per
 * {@code docs/2026-06-25_keyword_normalizer_for_sortable_fields.md}.
 */
public class TestKeywordNormalizer {

    private static final String NS = "http://example.org/";
    private static final String FP = "urn:jena:lucene:field#";
    private static final Node THING_CLASS = NodeFactory.createURI(NS + "Thing");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node NAME_PRED = NodeFactory.createURI(NS + "name");

    private static final String AEPFEL = "Äpfel";

    private Dataset dataset;
    private final Map<String, String> uriToName = new LinkedHashMap<>();

    @Before
    public void setUp() {
        TextQuery.init();

        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);
        // KEYWORD, sortable, WITH a lower-casing normalizer (the feature under test).
        FieldDef nameField = new FieldDef("name", FieldType.KEYWORD, null, null,
            true, true, false, true, false, false, false, null, new LowerCaseKeywordAnalyzer());

        List<FieldOccurrence> rootOccurrences = Arrays.asList(
            occurrence(titleField, PathFactory.pathLink(TITLE_PRED), Collections.singleton(TITLE_PRED)),
            occurrence(nameField, PathFactory.pathLink(NAME_PRED), Collections.singleton(NAME_PRED)));

        IndexProfile thingProfile = new IndexProfile(
            NodeFactory.createURI(NS + "ThingShape"),
            Collections.singleton(THING_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, nameField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(thingProfile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setValueStored(true);

        ShaclTextIndexLucene textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);
        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(
            baseDs.asDatasetGraph(), textIndex, mapping);
        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);
    }

    private void addThing(String localName, String name) {
        String uri = NS + localName;
        uriToName.put(uri, name);
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource r = ResourceFactory.createResource(uri);
            model.add(r, RDF.type, ResourceFactory.createResource(NS + "Thing"));
            model.add(r, ResourceFactory.createProperty(NS + "title"), "thing");
            model.add(r, ResourceFactory.createProperty(NS + "name"), name);
            dataset.commit();
        } finally {
            dataset.end();
        }
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
        if (dataset != null) {
            dataset.close();
        }
    }

    /** With a lower-casing normalizer the KEYWORD sort key is case-insensitive. */
    @Test
    public void testNormalizedSortIsCaseInsensitive() {
        addThing("rZebra", "Zebra");
        addThing("rApple", "apple");
        addThing("rAepfel", AEPFEL);

        List<String> orderedNames = querySortedNames();

        // Lower-cased sort keys: zebra, apple, äpfel -> ascending bytes a(0x61) < z(0x7A) < ä(0xC3).
        List<String> collated = Arrays.asList("apple", "Zebra", AEPFEL);
        assertEquals("Normalizer must make KEYWORD sort case-insensitive", collated, orderedNames);

        // And it differs from the raw-byte order the baseline (no normalizer) produces.
        List<String> rawByteOrder = Arrays.asList("Zebra", "apple", AEPFEL);
        assertNotEquals("Sort must no longer be raw-byte order", rawByteOrder, orderedNames);
    }

    /** With a normalizer, an exact '=' match on the KEYWORD field is case-insensitive. */
    @Test
    public void testNormalizedExactMatchIsCaseInsensitive() {
        addThing("rSmith", "Smith");

        assertEquals("lower-case value must match indexed \"Smith\"", 1, countEqualsFilter("smith"));
        assertEquals("upper-case value must match indexed \"Smith\"", 1, countEqualsFilter("SMITH"));
        assertEquals("exact-case value must still match", 1, countEqualsFilter("Smith"));
    }

    private List<String> querySortedNames() {
        String sparql =
            "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"thing\" \"\" " +
            "    '{\"field\":\"" + FP + "name\"}' 100 0) .\n" +
            "}";

        List<String> orderedNames = new ArrayList<>();
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                orderedNames.add(uriToName.get(sol.getResource("s").getURI()));
            }
        } finally {
            dataset.end();
        }
        return orderedNames;
    }

    private int countEqualsFilter(String value) {
        String sparql =
            "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"thing\" " +
            "    '{\"op\":\"=\",\"args\":[{\"property\":\"" + FP + "name\"},\"" + value + "\"]}' " +
            "    \"\" 100 0) .\n" +
            "}";

        int count = 0;
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                rs.next();
                count++;
            }
        } finally {
            dataset.end();
        }
        return count;
    }
}
