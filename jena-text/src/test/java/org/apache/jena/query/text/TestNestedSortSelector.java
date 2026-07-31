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
import java.util.List;
import java.util.TreeSet;

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
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
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
 * End-to-end coverage for the nested sort selector — "order by the nested value where the
 * co-located discriminator = X" (see
 * {@code docs/2026-07-02_nested_sort_selector_design.md}).
 * <p>
 * Entities carry repeated qualified identifiers: {@code (identifierType, identifierValue,
 * identifierRank)} triples on one child doc each. The tests drive {@code luc:query} with the
 * selector sort spec and assert:
 * <ul>
 *   <li>parents order by the value drawn from the <em>matching</em> child, not by the
 *       decorrelated MIN/MAX over the flattened bag;</li>
 *   <li>the selector never drops entities — those with no matching child are placed per
 *       {@code missing} (default last), in both sort directions;</li>
 *   <li>ordering happens before the {@code limit}/{@code offset} window, so pagination is
 *       correct across a page boundary;</li>
 *   <li>a selector composes with, and is independent of, a {@code cqlFilter}.</li>
 * </ul>
 */
public class TestNestedSortSelector {

    private static final String NS = "http://example.org/";
    private static final String SCHEMA = "https://schema.org/";
    private static final String FP = "urn:jena:lucene:field#";

    private static final Node BOREHOLE_CLASS = NodeFactory.createURI(NS + "Borehole");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node IDENTIFIER_PRED = NodeFactory.createURI(SCHEMA + "identifier");
    private static final Node ID_TYPE_PRED = NodeFactory.createURI(SCHEMA + "propertyID");
    private static final Node ID_VALUE_PRED = NodeFactory.createURI(SCHEMA + "value");
    private static final Node ID_RANK_PRED = NodeFactory.createURI(SCHEMA + "position");

    private Dataset dataset;

    @Before
    public void setUp() {
        TextQuery.init();

        // TEXT default-search field so one query string matches every entity.
        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);
        // Discriminator: indexed (queryable for the child filter), not sortable.
        FieldDef idType = new FieldDef("identifierType", FieldType.KEYWORD, null,
            true, true, true, false, true, false);
        // Sort key: sortable. Multi-valued because the parent doc flattens every identifier
        // value into one bag — the child docs still hold one value each.
        FieldDef idValue = new FieldDef("identifierValue", FieldType.KEYWORD, null,
            true, true, false, true, true, false);
        FieldDef idRank = new FieldDef("identifierRank", FieldType.INT, null,
            true, true, false, true, true, false);

        NestedDef identifierNest = new NestedDef(
            "identifier",
            PathFactory.pathLink(IDENTIFIER_PRED),
            Collections.singletonList(new JoinStep(IDENTIFIER_PRED, false)),
            Collections.singleton(IDENTIFIER_PRED),
            Arrays.asList(occurrence(idType, ID_TYPE_PRED),
                occurrence(idValue, ID_VALUE_PRED),
                occurrence(idRank, ID_RANK_PRED)),
            Collections.emptyList());

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "BoreholeShape"),
            Collections.singleton(BOREHOLE_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, idType, idValue, idRank),
            Collections.singletonList(occurrence(titleField, TITLE_PRED)),
            Collections.emptyList(),
            Collections.singletonList(identifierNest));

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);
        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        ShaclTextIndexLucene textIndex =
            new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);
        Dataset base = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(
            base.asDatasetGraph(), textIndex, mapping);
        dataset = TextDatasetFactory.create(base, textIndex, true, producer);
    }

    @After
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    private static FieldOccurrence occurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(
            field, PathFactory.pathLink(predicate),
            ShaclIndexAssembler.extractPathVariants(PathFactory.pathLink(predicate)),
            Collections.singleton(predicate),
            null, null, null, null);
    }

    /**
     * Four entities whose companyID order differs from every other ordering in the fixture —
     * insertion order, and the MIN/MAX over the whole bag of identifier values — so a passing
     * selector test cannot be explained by an incidental tie-break.
     * <pre>
     *   entity   companyID    other identifiers    MIN over all values
     *   bh1      B-100        govID Z-999          B-100
     *   bh2      A-050        govID A-001          A-001
     *   bh3      (none)       govID M-500          M-500
     *   bh4      C-200        govID A-000          A-000
     * </pre>
     * companyID ascending: bh2, bh1, bh4, (bh3 has no key).
     * MIN over all values:  bh4, bh2, bh1, bh3.
     */
    private void addStandardFixtures() {
        addBorehole("bh1", new String[][] {{"companyID", "B-100", "20"}, {"govID", "Z-999", "1"}});
        addBorehole("bh2", new String[][] {{"companyID", "A-050", "30"}, {"govID", "A-001", "2"}});
        addBorehole("bh3", new String[][] {{"govID", "M-500", "3"}});
        addBorehole("bh4", new String[][] {{"companyID", "C-200", "10"}, {"govID", "A-000", "4"}});
    }

    /** Each row of {@code identifiers} is {type, value, rank} and becomes one child doc. */
    private void addBorehole(String localName, String[][] identifiers) {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource bh = ResourceFactory.createResource(NS + localName);
            model.add(bh, RDF.type, ResourceFactory.createResource(BOREHOLE_CLASS.getURI()));
            model.add(bh, ResourceFactory.createProperty(TITLE_PRED.getURI()), "borehole");
            for (int i = 0; i < identifiers.length; i++) {
                Resource idNode = ResourceFactory.createResource(NS + localName + "-id-" + i);
                model.add(bh, ResourceFactory.createProperty(IDENTIFIER_PRED.getURI()), idNode);
                model.add(idNode, ResourceFactory.createProperty(ID_TYPE_PRED.getURI()), identifiers[i][0]);
                model.add(idNode, ResourceFactory.createProperty(ID_VALUE_PRED.getURI()), identifiers[i][1]);
                model.add(idNode, ResourceFactory.createProperty(ID_RANK_PRED.getURI()),
                    model.createTypedLiteral(Integer.parseInt(identifiers[i][2])));
            }
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    // ---- Sort-spec JSON helpers ----

    private static String selectorSort(String valueField, String selectorField, String selectorValue,
            String order, String missing) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"field\":\"").append(FP).append(valueField).append("\"")
          .append(",\"selector\":{\"field\":\"").append(FP).append(selectorField)
          .append("\",\"eq\":\"").append(selectorValue).append("\"}")
          .append(",\"order\":\"").append(order).append("\"");
        if (missing != null) {
            sb.append(",\"missing\":\"").append(missing).append("\"");
        }
        return sb.append("}").toString();
    }

    private List<String> query(String sortSpec) {
        return query(sortSpec, "", 100, 0);
    }

    /** Run {@code luc:query} and return the matched entity local names in result order. */
    private List<String> query(String sortSpec, String cqlFilter, int limit, int offset) {
        String sparql =
            "PREFIX luc: <urn:jena:lucene:index#>\n" +
            "SELECT ?s WHERE {\n" +
            "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"borehole\" '" + cqlFilter + "' " +
            "    '" + sortSpec + "' " + limit + " " + offset + ") .\n" +
            "}";

        List<String> ordered = new ArrayList<>();
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                ordered.add(sol.getResource("s").getURI().substring(NS.length()));
            }
        } finally {
            dataset.end();
        }
        return ordered;
    }

    // ---- Tests ----

    /**
     * The core claim: parents order by the identifier value taken from the companyID child.
     * The same field without a selector cannot express this — nested values live on the child
     * docs, so every parent is keyless and the flat sort imposes no order at all.
     */
    @Test
    public void testSortByNestedValueWhereDiscriminatorMatches() {
        addStandardFixtures();

        List<String> selectorOrder =
            query(selectorSort("identifierValue", "identifierType", "companyID", "asc", null));
        assertEquals("parents must order by the companyID identifier value",
            Arrays.asList("bh2", "bh1", "bh4", "bh3"), selectorOrder);

        List<String> flatOrder = query("{\"field\":\"" + FP + "identifierValue\",\"order\":\"asc\"}");
        assertEquals("the flat sort still returns every entity",
            new TreeSet<>(selectorOrder), new TreeSet<>(flatOrder));
        assertNotEquals("the selector must discriminate, not fall back to the flat sort",
            flatOrder, selectorOrder);
    }

    /** Descending reverses the matched-child keys and still keeps the unmatched entity last. */
    @Test
    public void testSortDescending() {
        addStandardFixtures();

        assertEquals(Arrays.asList("bh4", "bh1", "bh2", "bh3"),
            query(selectorSort("identifierValue", "identifierType", "companyID", "desc", null)));
    }

    /**
     * The selector chooses a sort key; it must not filter. Every entity survives, and the one
     * with no companyID child lands per {@code missing} — default last, explicitly first when
     * asked, in both directions.
     */
    @Test
    public void testMissingPlacement() {
        addStandardFixtures();

        assertEquals("default missing placement is last",
            Arrays.asList("bh2", "bh1", "bh4", "bh3"),
            query(selectorSort("identifierValue", "identifierType", "companyID", "asc", null)));
        assertEquals(Arrays.asList("bh3", "bh2", "bh1", "bh4"),
            query(selectorSort("identifierValue", "identifierType", "companyID", "asc", "first")));
        assertEquals(Arrays.asList("bh2", "bh1", "bh4", "bh3"),
            query(selectorSort("identifierValue", "identifierType", "companyID", "asc", "last")));
        assertEquals(Arrays.asList("bh3", "bh4", "bh1", "bh2"),
            query(selectorSort("identifierValue", "identifierType", "companyID", "desc", "first")));
        assertEquals(Arrays.asList("bh4", "bh1", "bh2", "bh3"),
            query(selectorSort("identifierValue", "identifierType", "companyID", "desc", "last")));
    }

    /** A numeric child value sorts numerically, not lexically (rank 10 &lt; 20 &lt; 30). */
    @Test
    public void testNumericNestedValue() {
        addStandardFixtures();

        assertEquals(Arrays.asList("bh4", "bh1", "bh2", "bh3"),
            query(selectorSort("identifierRank", "identifierType", "companyID", "asc", null)));
        assertEquals(Arrays.asList("bh2", "bh1", "bh4", "bh3"),
            query(selectorSort("identifierRank", "identifierType", "companyID", "desc", null)));
        assertEquals("missing rank placed first on request",
            Arrays.asList("bh3", "bh4", "bh1", "bh2"),
            query(selectorSort("identifierRank", "identifierType", "companyID", "asc", "first")));
    }

    /**
     * The sort runs inside Lucene, before the limit/offset window is cut, so paging returns
     * consecutive slices of the fully ordered result rather than re-ordering a page.
     */
    @Test
    public void testPaginationAcrossPageBoundary() {
        addStandardFixtures();

        String sort = selectorSort("identifierValue", "identifierType", "companyID", "asc", null);
        assertEquals(Arrays.asList("bh2", "bh1"), query(sort, "", 2, 0));
        assertEquals(Arrays.asList("bh4", "bh3"), query(sort, "", 2, 2));
    }

    /**
     * Selector and {@code cqlFilter} answer different questions and compose: the filter
     * decides which entities appear, the selector decides their order.
     */
    @Test
    public void testComposesWithCqlFilter() {
        addStandardFixtures();

        String cql = "{\"op\":\"=\",\"args\":[{\"property\":\"" + FP + "identifierType\"},\"companyID\"]}";
        assertEquals(Arrays.asList("bh2", "bh1", "bh4"),
            query(selectorSort("identifierValue", "identifierType", "companyID", "asc", null),
                cql, 100, 0));
    }

    /**
     * With more than one matching child the parent key collapses MIN ascending / MAX
     * descending, so bh5's key is D-100 going up and D-900 coming down.
     */
    @Test
    public void testMultipleMatchingChildrenCollapseMinMax() {
        addBorehole("bh1", new String[][] {{"companyID", "C-500", "1"}});
        addBorehole("bh5", new String[][] {{"companyID", "D-100", "2"}, {"companyID", "D-900", "3"}});
        addBorehole("bh6", new String[][] {{"companyID", "E-000", "4"}});

        assertEquals("ascending takes the MIN of the matching children",
            Arrays.asList("bh1", "bh5", "bh6"),
            query(selectorSort("identifierValue", "identifierType", "companyID", "asc", null)));
        assertEquals("descending takes the MAX of the matching children",
            Arrays.asList("bh6", "bh5", "bh1"),
            query(selectorSort("identifierValue", "identifierType", "companyID", "desc", null)));
    }

    /** A selector whose discriminator value matches nothing leaves every entity unmatched. */
    @Test
    public void testNoChildMatchesSelector() {
        addStandardFixtures();

        List<String> result =
            query(selectorSort("identifierValue", "identifierType", "noSuchType", "asc", null));
        assertEquals("all entities are retained when no child matches", 4, result.size());
    }
}
