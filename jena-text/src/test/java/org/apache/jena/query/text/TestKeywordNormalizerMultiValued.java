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
 * Sorting on a KEYWORD field that is BOTH {@code multiValued} AND has an {@code idx:normalizer}.
 * This is the GSWA case ({@code field:nameSort} is multi-valued + normalized) and the intersection
 * that neither the #90 (normalizer, single-valued) nor #92/#93 (multi-valued, raw) tests covered.
 *
 * Multi-valued sortable KEYWORD fields use {@code SortedSetDocValues} with a MIN/MAX selector; the
 * normalizer must be applied to those bytes too, or the sort silently falls back to raw (case-sensitive).
 */
public class TestKeywordNormalizerMultiValued {

    private static final String NS = "http://example.org/";
    private static final String FP = "urn:jena:lucene:field#";
    private static final Node THING_CLASS = NodeFactory.createURI(NS + "Thing");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node NAME_PRED = NodeFactory.createURI(NS + "name");

    private static final String AEPFEL = "Äpfel";

    private Dataset dataset;
    private final Map<String, String> uriToLabel = new LinkedHashMap<>();

    @Before
    public void setUp() {
        TextQuery.init();

        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);
        // KEYWORD, sortable, MULTI-VALUED, with a lower-casing normalizer.
        FieldDef nameField = new FieldDef("name", FieldType.KEYWORD, null, null,
            true, true, false, true, true, false, false, null, new LowerCaseKeywordAnalyzer());

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

    private void addThing(String localName, String... names) {
        String uri = NS + localName;
        uriToLabel.put(uri, localName);
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource r = ResourceFactory.createResource(uri);
            model.add(r, RDF.type, ResourceFactory.createResource(NS + "Thing"));
            model.add(r, ResourceFactory.createProperty(NS + "title"), "thing");
            for (String name : names) {
                model.add(r, ResourceFactory.createProperty(NS + "name"), name);
            }
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private static FieldOccurrence occurrence(FieldDef field, Path path, Set<Node> predicates) {
        return new FieldOccurrence(
            field, path, ShaclIndexAssembler.extractPathVariants(path), predicates,
            null, null, null, null);
    }

    @After
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    /** One value per entity, but the field is multi-valued → exercises the SortedSet path. */
    @Test
    public void testMultiValuedNormalizedSortIsCaseInsensitive() {
        addThing("eZebra",  "Zebra");
        addThing("eapple",  "apple");
        addThing("eAepfel", AEPFEL);

        // Normalized keys: zebra, apple, äpfel → ascending: apple < zebra < äpfel.
        assertEquals("multi-valued + normalized KEYWORD sort must be case-insensitive",
            Arrays.asList("eapple", "eZebra", "eAepfel"), querySortedEntities());
    }

    /** Genuine multi-value entities: ascending uses the normalized MIN per entity. */
    @Test
    public void testMultiValuedNormalizedSortUsesNormalizedMin() {
        addThing("eOne", "Yak", "apple");  // normalized min = "apple"
        addThing("eTwo", "Beta");          // normalized     = "beta"

        // Normalized: "apple" (eOne) < "beta" (eTwo).
        // Raw bytes would pick eOne's min as "Yak" (0x59) and eTwo as "Beta" (0x42) → eTwo first (wrong).
        assertEquals("multi-valued MIN selector must use the normalized value",
            Arrays.asList("eOne", "eTwo"), querySortedEntities());
    }

    private List<String> querySortedEntities() {
        String sparql =
            "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"thing\" \"\" " +
            "    '{\"field\":\"" + FP + "name\",\"order\":\"asc\"}' 100 0) .\n" +
            "}";

        List<String> ordered = new ArrayList<>();
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                ordered.add(uriToLabel.get(sol.getResource("s").getURI()));
            }
        } finally {
            dataset.end();
        }
        return ordered;
    }
}
