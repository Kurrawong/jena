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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.text.ShaclIndexMapping.*;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.external.ExternalChildMerger;
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
 * End-to-end coverage for external content indexing — nested child records built from
 * a CSV source and joined to graph entities on the entity IRI. See
 * {@code docs/2026-07-27_external_content_indexing_design.md}.
 * <p>
 * The fixture is the design's narrow EAV shape: the graph holds samples, the CSV holds
 * one row per measurement, and the two meet on the sample IRI at document-construction
 * time. The tests assert the properties the design claims:
 * <ul>
 *   <li>rows become child documents of the matching entity, and only of that entity;</li>
 *   <li>an entity with no rows is still indexed, with graph fields and no children;</li>
 *   <li>a row matching no entity is counted and dropped — external content augments
 *       entities, it never creates them;</li>
 *   <li>an AND of {@code =} and a range folds into <em>one</em> child, while an AND
 *       across two properties is entity-level and explicitly not same-child;</li>
 *   <li>a not-stored value still filters, facets and sorts;</li>
 *   <li>a wrong join key fails the build via {@code idx:minMatchRate} rather than
 *       producing a successful build with nothing in it.</li>
 * </ul>
 */
public class TestExternalContentIndexing {

    private static final String NS = "http://example.org/";
    private static final String FP = "urn:jena:lucene:field#";
    private static final String MEASUREMENT_SCOPE = "measurement";
    private static final String PROPERTY_BAND_DIM = "measuredProperty_measuredBand";

    private static final Node SAMPLE_CLASS = NodeFactory.createURI(NS + "Sample");
    private static final Node NAME_PRED = NodeFactory.createURI(NS + "name");

    /**
     * Sorted by subject, as {@code idx:sorted true} asserts. {@code zz-orphan} is not in
     * the graph and must be counted as unmatched rather than creating an entity.
     */
    private static final String SORTED_CSV =
        "sample_iri,property,value,band\n"
        + "http://example.org/s1,Au,12.4,high\n"
        + "http://example.org/s1,Cu,0.7,low\n"
        + "http://example.org/s2,Au,0.3,low\n"
        + "http://example.org/s2,Cu,150.0,high\n"
        + "http://example.org/s3,Au,5.0,medium\n"
        + "http://example.org/zz-orphan,Au,9.9,medium\n";

    private Path dir;
    private Dataset baseDataset;
    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;
    private ShaclIndexMapping mapping;
    private ShaclBulkIndexer indexer;

    @Before
    public void setUp() throws IOException {
        TextQuery.init();
        dir = Files.createTempDirectory("external-content-test");
    }

    @After
    public void tearDown() throws IOException {
        if (dataset != null) dataset.close();
        if (baseDataset != null) baseDataset.close();
        if (textIndex != null) textIndex.close();
        if (dir != null) {
            try (var paths = Files.walk(dir)) {
                paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> p.toFile().delete());
            }
        }
    }

    // ---- fixture ----

    private Path writeCsv(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    /** The canonical fields; {@code measuredValue} is deliberately not stored. */
    private static FieldDef nameField() {
        return new FieldDef("name", FieldType.TEXT, null, true, true, false, false, false, true);
    }

    private static FieldDef propertyField() {
        return new FieldDef("measuredProperty", FieldType.KEYWORD, null,
            true, true, true, false, false, false);
    }

    private static FieldDef valueField() {
        return new FieldDef("measuredValue", FieldType.DOUBLE, null,
            false, true, true, true, false, false);
    }

    private static FieldDef bandField() {
        return new FieldDef("measuredBand", FieldType.KEYWORD, null,
            true, true, true, false, false, false);
    }

    private ExternalSourceDef csvSource(String location, String subjectColumn, String subjectPrefix,
                                        boolean sorted, ErrorPolicy onError, double minMatchRate,
                                        FieldDef property, FieldDef value, FieldDef band) {
        return new ExternalSourceDef(ExternalFormat.CSV, location, subjectColumn, -1, subjectPrefix,
            sorted, null, false, onError, minMatchRate,
            Arrays.asList(new ColumnBinding("property", -1, property),
                new ColumnBinding("value", -1, value),
                new ColumnBinding("band", -1, band)));
    }

    /** Build the index and dataset for a given source, then bulk-index the graph. */
    private void buildIndex(ExternalSourceDef source, FieldDef name, FieldDef property,
                            FieldDef value, FieldDef band, boolean withHierarchy) {
        List<HierarchyDef> hierarchies = withHierarchy
            ? List.of(new HierarchyDef(PROPERTY_BAND_DIM, Arrays.asList(property, band)))
            : Collections.emptyList();

        NestedDef measurements = new NestedDef(MEASUREMENT_SCOPE, source, hierarchies);

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "SampleShape"),
            Collections.singleton(SAMPLE_CLASS),
            "uri", "docType",
            Arrays.asList(name, property, value, band),
            Collections.singletonList(rootOccurrence(name, NAME_PRED)),
            Collections.emptyList(),
            Collections.singletonList(measurements));

        mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        textIndex = withHierarchy
            ? new ShaclTextIndexLucene(new ByteBuffersDirectory(), new ByteBuffersDirectory(), config)
            : new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);

        // No text wrapper on the loading side: bulk indexing is the supported path for
        // an external-bearing profile, exactly as tdb2.tdbloader would leave things.
        baseDataset = DatasetFactory.create();
        loadSamples(baseDataset);

        indexer = new ShaclBulkIndexer(baseDataset.asDatasetGraph(), textIndex, mapping);
        indexer.setFreshIndex(true);
        indexer.index();

        dataset = TextDatasetFactory.create(baseDataset, textIndex, true,
            new ShaclTextDocProducer(baseDataset.asDatasetGraph(), textIndex, mapping));
    }

    /** Standard build: sorted CSV, no prefix, skip-on-error, no match-rate floor. */
    private void buildStandardIndex(String csvContent, boolean sorted) throws IOException {
        Path csv = writeCsv("measurements.csv", csvContent);
        FieldDef property = propertyField();
        FieldDef value = valueField();
        FieldDef band = bandField();
        buildIndex(csvSource(csv.toString(), "sample_iri", null, sorted, ErrorPolicy.SKIP, 0.0,
            property, value, band), nameField(), property, value, band, false);
    }

    private static FieldOccurrence rootOccurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(field, PathFactory.pathLink(predicate),
            ShaclIndexAssembler.extractPathVariants(PathFactory.pathLink(predicate)),
            Collections.singleton(predicate), null, null, null, null);
    }

    /** s4 has no rows in any fixture — it must still be indexed, with no children. */
    private void loadSamples(Dataset target) {
        target.begin(ReadWrite.WRITE);
        try {
            Model model = target.getDefaultModel();
            addSample(model, "s1", "alpha");
            addSample(model, "s2", "bravo");
            addSample(model, "s3", "charlie");
            addSample(model, "s4", "delta");
            target.commit();
        } finally {
            target.end();
        }
    }

    private void addSample(Model model, String id, String name) {
        Resource sample = ResourceFactory.createResource(NS + id);
        model.add(sample, RDF.type, ResourceFactory.createResource(SAMPLE_CLASS.getURI()));
        model.add(sample, ResourceFactory.createProperty(NAME_PRED.getURI()), name);
    }

    // ---- query helpers ----

    /** Entity local names matching a CQL filter, sorted for stable comparison. */
    private List<String> filter(String cqlFilter) {
        return query(cqlFilter, "", true);
    }

    private List<String> query(String cqlFilter, String sortSpec, boolean sortResults) {
        String sparql =
            "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?s WHERE {\n"
            + "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"*\" '" + cqlFilter + "' "
            + "    '" + sortSpec + "' 100 0) .\n"
            + "}";

        List<String> names = new ArrayList<>();
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                names.add(sol.getResource("s").getURI().substring(NS.length()));
            }
        } finally {
            dataset.end();
        }
        if (sortResults) {
            Collections.sort(names);
        }
        return names;
    }

    private static String eq(String field, String value) {
        return "{\"op\":\"=\",\"args\":[{\"property\":\"" + FP + field + "\"},\"" + value + "\"]}";
    }

    private static String cmp(String op, String field, double value) {
        return "{\"op\":\"" + op + "\",\"args\":[{\"property\":\"" + FP + field + "\"}," + value + "]}";
    }

    private static String and(String... clauses) {
        return "{\"op\":\"and\",\"args\":[" + String.join(",", clauses) + "]}";
    }

    /** Sort by a nested value drawn from the child where measuredProperty = {@code property}. */
    private static String sortByProperty(String property, String order, String missing) {
        return "{\"field\":\"" + FP + "measuredValue\""
            + ",\"filter\":{\"field\":\"" + FP + "measuredProperty\",\"eq\":\"" + property + "\"}"
            + ",\"order\":\"" + order + "\",\"missing\":\"" + missing + "\"}";
    }

    private ExternalChildMerger.SourceStats onlyStats() {
        List<ExternalChildMerger.SourceStats> stats = indexer.getExternalSourceStats();
        assertEquals("one configured source", 1, stats.size());
        return stats.get(0);
    }

    // ---- tests ----

    /** The join: a row lands on the entity whose IRI is in its subject column, and on no other. */
    @Test
    public void rowsBecomeChildrenOfTheMatchingEntity() throws IOException {
        buildStandardIndex(SORTED_CSV, true);

        assertEquals("every sample carrying an Au measurement",
            Arrays.asList("s1", "s2", "s3"), filter(eq("measuredProperty", "Au")));
        assertEquals("only the two samples carrying Cu",
            Arrays.asList("s1", "s2"), filter(eq("measuredProperty", "Cu")));
    }

    /** An entity with no rows keeps its graph-derived document — it is not dropped, and
     *  it is not matched by a filter on a child field. */
    @Test
    public void entityWithNoRowsIsStillIndexed() throws IOException {
        buildStandardIndex(SORTED_CSV, true);

        assertEquals("all four samples are indexed", 4, indexer.getEntityCount());
        assertEquals("s4 is findable by its graph field",
            List.of("s4"), filter("{\"op\":\"=\",\"args\":[{\"property\":\"" + FP + "name\"},\"delta\"]}"));
        assertFalse("s4 has no measurement children",
            filter(cmp(">", "measuredValue", 0.0)).contains("s4"));
    }

    /** External content augments entities; it never creates them. The orphan row is
     *  counted, which is the diagnostic that catches a wrong join key. */
    @Test
    public void rowMatchingNoEntityIsCountedAndDropped() throws IOException {
        buildStandardIndex(SORTED_CSV, true);

        ExternalChildMerger.SourceStats stats = onlyStats();
        assertEquals("all six rows read", 6, stats.rowsRead());
        assertEquals("five rows attached", 5, stats.rowsMatched());
        assertEquals("the orphan row matched no entity", 1, stats.rowsUnmatched());
        assertEquals(0, stats.rowsSkipped());
        assertEquals("every entity of the profile was offered rows", 4, stats.entitiesSeen());
        assertEquals("three of four received children", 3, stats.entitiesMatched());
        assertEquals(0, indexer.getEntityCount() - 4);
    }

    /**
     * The decisive semantic: {@code =} and a range AND-ed together fold into one child,
     * so they must be satisfied by the <em>same</em> measurement.
     * <p>
     * s2 has Au 0.3 and Cu 150.0. A same-child filter for Au above 5 must not match it
     * on the strength of its Cu value.
     */
    @Test
    public void sameChildFilterMixesEqualityAndRange() throws IOException {
        buildStandardIndex(SORTED_CSV, true);

        assertEquals("Au at or above 5.0 — s1 (12.4) and s3 (5.0), not s2 (Au 0.3, Cu 150)",
            Arrays.asList("s1", "s3"),
            filter(and(eq("measuredProperty", "Au"), cmp(">=", "measuredValue", 5.0))));

        assertEquals("no sample has Au above 100 even though one has Cu 150",
            Collections.emptyList(),
            filter(and(eq("measuredProperty", "Au"), cmp(">", "measuredValue", 100.0))));
    }

    /**
     * Two same-child folds AND-ed at the parent mean "the entity has some Au above X
     * <em>and</em> some Cu above Y" — not "in the same measurement". s2 satisfies it
     * across two different children.
     */
    @Test
    public void andAcrossTwoPropertiesIsEntityLevelNotSameChild() throws IOException {
        buildStandardIndex(SORTED_CSV, true);

        String auClause = and(eq("measuredProperty", "Au"), cmp(">", "measuredValue", 0.2));
        String cuClause = and(eq("measuredProperty", "Cu"), cmp(">", "measuredValue", 100.0));

        assertEquals("s2 has Au 0.3 on one child and Cu 150 on another",
            List.of("s2"), filter(and(auClause, cuClause)));
    }

    /** A not-stored value still drives the block-join sort selector: entities order by
     *  the value on their Au child, and the sample with no Au child is placed by
     *  {@code missing} rather than dropped. */
    @Test
    public void sortsByExternalValueOfOneProperty() throws IOException {
        buildStandardIndex(SORTED_CSV, true);

        assertEquals("ascending Au: s2 (0.3), s3 (5.0), s1 (12.4), then s4 with no Au child",
            Arrays.asList("s2", "s3", "s1", "s4"),
            query("", sortByProperty("Au", "asc", "last"), false));
        assertEquals(Arrays.asList("s1", "s3", "s2", "s4"),
            query("", sortByProperty("Au", "desc", "last"), false));
        assertEquals("missing first keeps s4 in the result, at the front",
            Arrays.asList("s4", "s2", "s3", "s1"),
            query("", sortByProperty("Au", "asc", "first"), false));
    }

    /** idx:subjectPrefix turns a bare business key into the entity IRI. Repeating a long
     *  IRI prefix across tens of millions of rows is pure file bloat. */
    @Test
    public void subjectPrefixJoinsBareKeys() throws IOException {
        Path csv = writeCsv("bare.csv",
            "sample_id,property,value,band\n"
            + "s1,Au,12.4,high\n"
            + "s2,Cu,150.0,high\n");

        FieldDef property = propertyField();
        FieldDef value = valueField();
        FieldDef band = bandField();
        buildIndex(csvSource(csv.toString(), "sample_id", NS, true, ErrorPolicy.SKIP, 0.0,
            property, value, band), nameField(), property, value, band, false);

        assertEquals(List.of("s1"), filter(eq("measuredProperty", "Au")));
        assertEquals(List.of("s2"), filter(eq("measuredProperty", "Cu")));
        assertEquals(0, onlyStats().rowsUnmatched());
    }

    /** A wrong join key produces a technically successful build with near-zero matches.
     *  idx:minMatchRate is what turns that into a failure. */
    @Test
    public void minMatchRateFailsABadJoinKey() throws IOException {
        Path csv = writeCsv("wrong-prefix.csv",
            "sample_iri,property,value,band\n"
            + "http://wrong.example/s1,Au,12.4,high\n"
            + "http://wrong.example/s2,Cu,150.0,high\n");

        FieldDef property = propertyField();
        FieldDef value = valueField();
        FieldDef band = bandField();
        ExternalSourceDef source = csvSource(csv.toString(), "sample_iri", null, true,
            ErrorPolicy.SKIP, 0.5, property, value, band);

        TextIndexException e = assertThrows(TextIndexException.class,
            () -> buildIndex(source, nameField(), property, value, band, false));
        assertTrue("message should point at the join key: " + e.getMessage(),
            e.getMessage().contains("idx:subjectColumn"));
    }

    /** An unsorted source is buffered rather than rejected, and produces the same index. */
    @Test
    public void unsortedSourceIsBufferedAndProducesTheSameIndex() throws IOException {
        String shuffled =
            "sample_iri,property,value,band\n"
            + "http://example.org/s3,Au,5.0,medium\n"
            + "http://example.org/s1,Au,12.4,high\n"
            + "http://example.org/zz-orphan,Au,9.9,medium\n"
            + "http://example.org/s2,Cu,150.0,high\n"
            + "http://example.org/s1,Cu,0.7,low\n"
            + "http://example.org/s2,Au,0.3,low\n";

        buildStandardIndex(shuffled, false);

        assertEquals(Arrays.asList("s1", "s2", "s3"), filter(eq("measuredProperty", "Au")));
        assertEquals(Arrays.asList("s1", "s3"),
            filter(and(eq("measuredProperty", "Au"), cmp(">=", "measuredValue", 5.0))));
        assertEquals("the orphan is still counted", 1, onlyStats().rowsUnmatched());
    }

    /** An unparseable cell drops its row under the default skip policy, and is counted.
     *  A dropped row must not leave a half-populated child behind. */
    @Test
    public void unparseableCellSkipsTheRow() throws IOException {
        String withBadCell =
            "sample_iri,property,value,band\n"
            + "http://example.org/s1,Au,12.4,high\n"
            + "http://example.org/s2,Au,<0.5,low\n";

        buildStandardIndex(withBadCell, true);

        ExternalChildMerger.SourceStats stats = onlyStats();
        assertEquals(1, stats.rowsMatched());
        assertEquals("the detection-limit marker is not a double", 1, stats.rowsSkipped());
        assertEquals("s2 gets no child at all, not one with a property and no value",
            List.of("s1"), filter(eq("measuredProperty", "Au")));
    }

    /** An empty value cell is never coerced to zero — the row is dropped. */
    @Test
    public void emptyValueCellSkipsTheRow() throws IOException {
        String withEmptyCell =
            "sample_iri,property,value,band\n"
            + "http://example.org/s1,Au,12.4,high\n"
            + "http://example.org/s2,Au,,low\n";

        buildStandardIndex(withEmptyCell, true);

        assertEquals(1, onlyStats().rowsSkipped());
        assertEquals("s2 must not appear as Au = 0", Collections.emptyList(),
            filter(and(eq("measuredProperty", "Au"), cmp("<", "measuredValue", 1.0))));
    }

    /** idx:onError "fail" stops the build instead of counting. */
    @Test
    public void onErrorFailStopsTheBuild() throws IOException {
        Path csv = writeCsv("bad.csv",
            "sample_iri,property,value,band\n"
            + "http://example.org/s1,Au,not-a-number,high\n");

        FieldDef property = propertyField();
        FieldDef value = valueField();
        FieldDef band = bandField();
        ExternalSourceDef source = csvSource(csv.toString(), "sample_iri", null, true,
            ErrorPolicy.FAIL, 0.0, property, value, band);

        TextIndexException e = assertThrows(TextIndexException.class,
            () -> buildIndex(source, nameField(), property, value, band, false));
        assertTrue(e.getMessage().contains("not-a-number"));
    }

    /** Property → band is a native facet hierarchy over external children, with the
     *  drill-down staying correlated to the child the value came from. */
    @Test
    public void hierarchicalFacetsOverExternalChildren() throws IOException {
        Path csv = writeCsv("measurements.csv", SORTED_CSV);
        FieldDef property = propertyField();
        FieldDef value = valueField();
        FieldDef band = bandField();
        buildIndex(csvSource(csv.toString(), "sample_iri", null, true, ErrorPolicy.SKIP, 0.0,
            property, value, band), nameField(), property, value, band, true);

        Map<String, Long> top = toFacetMap(textIndex.getFacetCounts(
            null, null, List.of(PROPERTY_BAND_DIM), 10, 0).get(PROPERTY_BAND_DIM));
        assertEquals("three samples carry an Au measurement", Long.valueOf(3), top.get("Au"));
        assertEquals("two carry Cu", Long.valueOf(2), top.get("Cu"));

        Map<String, Long> underAu = toFacetMap(textIndex.getFacetCounts(
            null, null, List.of(PROPERTY_BAND_DIM), 10, 0,
            Map.of(PROPERTY_BAND_DIM, new String[] {"Au"})).get(PROPERTY_BAND_DIM));
        assertEquals(Long.valueOf(1), underAu.get("high"));
        assertEquals(Long.valueOf(1), underAu.get("medium"));
        assertEquals("s2's Au is low; its high band belongs to Cu and must not show here",
            Long.valueOf(1), underAu.get("low"));
    }

    /** A profile with an external source is rebuild-only: a live graph change must not
     *  quietly rewrite the document without its external children. */
    @Test
    public void liveGraphChangeDoesNotStripExternalChildren() throws IOException {
        buildStandardIndex(SORTED_CSV, true);

        dataset.begin(ReadWrite.WRITE);
        try {
            dataset.getDefaultModel().add(
                ResourceFactory.createResource(NS + "s1"),
                ResourceFactory.createProperty(NAME_PRED.getURI()), "alpha revised");
            dataset.commit();
        } finally {
            dataset.end();
        }

        assertEquals("s1 still carries the children the bulk build gave it",
            Arrays.asList("s1", "s3"),
            filter(and(eq("measuredProperty", "Au"), cmp(">=", "measuredValue", 5.0))));
    }

    private static Map<String, Long> toFacetMap(List<FacetValue> values) {
        Map<String, Long> map = new HashMap<>();
        if (values != null) {
            for (FacetValue value : values) {
                map.put(value.getValue(), value.getCount());
            }
        }
        return map;
    }
}
