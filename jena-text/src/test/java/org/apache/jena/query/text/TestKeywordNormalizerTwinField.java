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
 * The documented "twin field" pattern for a full-text label, exercised end to end:
 * ONE predicate ({@code ex:name}) populates BOTH a {@code TEXT} {@code defaultSearch}
 * field (for {@code luc:query} full-text search) AND a normalized sortable {@code KEYWORD}
 * field (for the sortSpec). This mirrors the GSWA config where {@code schema:name} feeds
 * {@code field:name} (TEXT) and a proposed {@code field:nameSort} (KEYWORD + idx:normalizer).
 *
 * Confirms: a single full-text query matches via the TEXT field while the results come back
 * in case-insensitive order via the normalized KEYWORD twin.
 */
public class TestKeywordNormalizerTwinField {

    private static final String NS = "http://example.org/";
    private static final String FP = "urn:jena:lucene:field#";
    private static final Node THING_CLASS = NodeFactory.createURI(NS + "Thing");
    private static final Node NAME_PRED = NodeFactory.createURI(NS + "name");

    private Dataset dataset;
    private final Map<String, String> uriToName = new LinkedHashMap<>();

    @Before
    public void setUp() {
        TextQuery.init();

        // Same predicate (ex:name) feeds both fields:
        //  - nameText: TEXT, defaultSearch -> tokenized for full-text search
        //  - nameSort: KEYWORD, sortable, lower-casing normalizer -> case-insensitive sort key
        FieldDef nameText = new FieldDef("nameText", FieldType.TEXT, null,
            true, true, false, false, false, true);
        FieldDef nameSort = new FieldDef("nameSort", FieldType.KEYWORD, null, null,
            true, true, false, true, false, false, false, null, new LowerCaseKeywordAnalyzer());

        List<FieldOccurrence> rootOccurrences = Arrays.asList(
            occurrence(nameText, PathFactory.pathLink(NAME_PRED), Collections.singleton(NAME_PRED)),
            occurrence(nameSort, PathFactory.pathLink(NAME_PRED), Collections.singleton(NAME_PRED)));

        IndexProfile thingProfile = new IndexProfile(
            NodeFactory.createURI(NS + "ThingShape"),
            Collections.singleton(THING_CLASS),
            "uri", "docType",
            Arrays.asList(nameText, nameSort),
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
            model.add(r, ResourceFactory.createProperty(NS + "name"), name);
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

    /**
     * Full-text search hits the shared TEXT field; the sortSpec on the normalized KEYWORD
     * twin returns the matches in case-insensitive order.
     */
    @Test
    public void testTwinFieldSearchViaTextSortViaNormalizedKeyword() {
        // All three share the search token "bonaparte" (found via the tokenized TEXT field).
        addThing("rZeta",  "Bonaparte Zeta");
        addThing("rAlpha", "bonaparte alpha");
        addThing("rMecca", "Bonaparte Mecca");

        String sparql =
            "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"bonaparte\" \"\" " +
            "    '{\"field\":\"" + FP + "nameSort\",\"order\":\"asc\"}' 100 0) .\n" +
            "}";

        List<String> ordered = new ArrayList<>();
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                ordered.add(uriToName.get(sol.getResource("s").getURI()));
            }
        } finally {
            dataset.end();
        }

        // The full-text term matched all three via the TEXT twin.
        assertEquals("full-text search should match all three via the TEXT field", 3, ordered.size());

        // Normalized (lower-cased) sort keys: "bonaparte alpha" < "bonaparte mecca" < "bonaparte zeta".
        assertEquals("sort must be case-insensitive via the normalized KEYWORD twin",
            Arrays.asList("bonaparte alpha", "Bonaparte Mecca", "Bonaparte Zeta"), ordered);

        // Raw-byte order (no normalizer) would put the capital-B names first: Mecca, Zeta, alpha.
        assertNotEquals("must not be raw-byte order",
            Arrays.asList("Bonaparte Mecca", "Bonaparte Zeta", "bonaparte alpha"), ordered);
    }
}
