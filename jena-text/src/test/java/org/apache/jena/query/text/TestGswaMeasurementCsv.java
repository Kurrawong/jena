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
 * Coverage for the exact shape of the GSWA downhole summary extract
 * (`dh_summary_measurements.csv`: {@code collar_id,property,value,below_detection}),
 * whose particulars are not incidental:
 * <ul>
 *   <li>the join key is a <b>bare integer</b>, so it needs {@code idx:subjectPrefix};</li>
 *   <li>values arrive in <b>scientific notation</b> ({@code 8.900000102585182e-05}) —
 *       trace-element assays span many orders of magnitude;</li>
 *   <li>{@code below_detection} is a co-located flag, so "above 1 ppm <em>and</em> not
 *       a detection-limit placeholder" must correlate within one child;</li>
 *   <li>collar ids are 6 <b>and</b> 7 digits, which makes lexical and numeric ordering
 *       disagree — and the merge is lexical.</li>
 * </ul>
 * The fixtures are inline rather than a checked-in sample of the real file: they keep
 * the tests hermetic, and a CSV cannot carry an Apache licence header without the
 * header line becoming a data row.
 */
public class TestGswaMeasurementCsv {

    private static final String NS = "http://example.org/";
    private static final String COLLAR_NS = "https://linked.data.gov.au/dataset/gswa/collar/";
    private static final String FP = "urn:jena:lucene:field#";

    private static final Node COLLAR_CLASS = NodeFactory.createURI(NS + "Collar");
    private static final Node NAME_PRED = NodeFactory.createURI(NS + "name");

    private Path dir;
    private Dataset baseDataset;
    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;
    private ShaclBulkIndexer indexer;

    @Before
    public void setUp() throws IOException {
        TextQuery.init();
        dir = Files.createTempDirectory("gswa-measurement-test");
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

    /**
     * Real rows from the head of the extract, verbatim apart from selection. Note the
     * Au value: five significant figures at 1e-05, which only survives as a double.
     */
    private static final String MEASUREMENTS_CSV =
        "collar_id,property,value,below_detection\n"
        + "1000000,As,98.4000015258789,f\n"
        + "1000000,Au,8.900000102585182e-05,f\n"
        + "1000000,Cu,83.5,f\n"
        + "1000000,Ni,443,f\n"
        + "1000001,Au,0.0025,t\n"
        + "1000001,Cu,12.25,f\n"
        + "1000002,Au,1.5,f\n";

    /** Collar ids of both widths, in the order the SQL dump emits them — text order. */
    private static final String LEXICALLY_SORTED_CSV =
        "collar_id,property,value,below_detection\n"
        + "1175968,Au,1.0,f\n"
        + "117597,Au,2.0,f\n"
        + "1175971,Au,3.0,f\n"
        + "117598,Au,4.0,f\n";

    /** The same rows in numeric order — what `ORDER BY collar_id` on an integer column
     *  produces, and what the lexical merge cannot consume. */
    private static final String NUMERICALLY_SORTED_CSV =
        "collar_id,property,value,below_detection\n"
        + "117597,Au,2.0,f\n"
        + "117598,Au,4.0,f\n"
        + "1175968,Au,1.0,f\n"
        + "1175971,Au,3.0,f\n";

    private Path writeCsv(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private static FieldDef nameField() {
        return new FieldDef("collarName", FieldType.TEXT, null, true, true, false, false, false, true);
    }

    private static FieldDef analyteField() {
        return new FieldDef("analyte", FieldType.KEYWORD, null,
            true, true, true, false, false, false);
    }

    private static FieldDef valueField() {
        return new FieldDef("analyteValue", FieldType.DOUBLE, null,
            false, true, true, true, false, false);
    }

    private static FieldDef belowDetectionField() {
        return new FieldDef("belowDetection", FieldType.KEYWORD, null,
            true, true, true, false, false, false);
    }

    /** Build the index over a measurements CSV joined to synthetic collars. */
    private void buildIndex(String csvContent, String... collarIds) throws IOException {
        Path csv = writeCsv("measurements.csv", csvContent);

        FieldDef name = nameField();
        FieldDef analyte = analyteField();
        FieldDef value = valueField();
        FieldDef belowDetection = belowDetectionField();

        ExternalSourceDef source = new ExternalSourceDef(ExternalFormat.CSV, csv.toString(),
            "collar_id", -1, COLLAR_NS, null, false, ErrorPolicy.SKIP,
            Arrays.asList(new ColumnBinding("property", -1, analyte),
                new ColumnBinding("value", -1, value),
                new ColumnBinding("below_detection", -1, belowDetection)));

        NestedDef measurements = new NestedDef("measurement", source, Collections.emptyList());

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "CollarShape"),
            Collections.singleton(COLLAR_CLASS),
            "uri", "docType",
            Arrays.asList(name, analyte, value, belowDetection),
            Collections.singletonList(rootOccurrence(name, NAME_PRED)),
            Collections.emptyList(),
            Collections.singletonList(measurements));

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);

        baseDataset = DatasetFactory.create();
        baseDataset.begin(ReadWrite.WRITE);
        try {
            Model model = baseDataset.getDefaultModel();
            for (String collarId : collarIds) {
                Resource collar = ResourceFactory.createResource(COLLAR_NS + collarId);
                model.add(collar, RDF.type, ResourceFactory.createResource(COLLAR_CLASS.getURI()));
                model.add(collar, ResourceFactory.createProperty(NAME_PRED.getURI()), "hole " + collarId);
            }
            baseDataset.commit();
        } finally {
            baseDataset.end();
        }

        indexer = new ShaclBulkIndexer(baseDataset.asDatasetGraph(), textIndex, mapping);
        indexer.setFreshIndex(true);
        indexer.index();

        dataset = TextDatasetFactory.create(baseDataset, textIndex, true,
            new ShaclTextDocProducer(baseDataset.asDatasetGraph(), textIndex, mapping));
    }

    private static FieldOccurrence rootOccurrence(FieldDef field, Node predicate) {
        return new FieldOccurrence(field, PathFactory.pathLink(predicate),
            ShaclIndexAssembler.extractPathVariants(PathFactory.pathLink(predicate)),
            Collections.singleton(predicate), null, null, null, null);
    }

    // ---- query helpers ----

    /** Collar ids matching a CQL filter, sorted for stable comparison. */
    private List<String> filter(String cqlFilter) {
        String sparql =
            "PREFIX luc: <urn:jena:lucene:index#>\n"
            + "SELECT ?s WHERE {\n"
            + "  (?hit ?s ?score) luc:query (\"default\" \"default\" \"*\" '" + cqlFilter + "' \"\" 100 0) .\n"
            + "}";

        List<String> ids = new ArrayList<>();
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                ids.add(sol.getResource("s").getURI().substring(COLLAR_NS.length()));
            }
        } finally {
            dataset.end();
        }
        Collections.sort(ids);
        return ids;
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

    // ---- tests ----

    /** The bare integer key becomes an entity IRI by concatenation — no key-construction
     *  expression, and no long IRI repeated across 29.7 million rows. */
    @Test
    public void bareIntegerKeyJoinsViaSubjectPrefix() throws IOException {
        buildIndex(MEASUREMENTS_CSV, "1000000", "1000001", "1000002");

        ExternalChildMerger.SourceStats stats = indexer.getExternalSourceStats().get(0);
        assertEquals(7, stats.rowsRead());
        assertEquals("every row found its collar", 7, stats.rowsMatched());
        assertEquals(0, stats.rowsUnmatched());

        assertEquals(Arrays.asList("1000000", "1000001", "1000002"), filter(eq("analyte", "Au")));
        assertEquals(List.of("1000000"), filter(eq("analyte", "Ni")));
    }

    /**
     * Trace assays are written in scientific notation and span many orders of
     * magnitude. {@code 8.9e-05} must compare as a number, not sort as text — lexically
     * it would land between "83.5" and "98.4".
     */
    @Test
    public void scientificNotationParsesAsADouble() throws IOException {
        buildIndex(MEASUREMENTS_CSV, "1000000", "1000001", "1000002");

        assertEquals("8.9e-05 is below 0.001, despite the lexical form starting with '8'",
            List.of("1000000"),
            filter(and(eq("analyte", "Au"), cmp("<", "analyteValue", 0.001))));

        assertEquals("0.0025 and 1.5 are above it",
            Arrays.asList("1000001", "1000002"),
            filter(and(eq("analyte", "Au"), cmp(">", "analyteValue", 0.001))));

        assertEquals("the As value sits between the two Cu-scale numbers",
            List.of("1000000"),
            filter(and(eq("analyte", "As"), cmp(">", "analyteValue", 90.0),
                cmp("<", "analyteValue", 100.0))));
    }

    /**
     * {@code below_detection} is on the same row, so it is on the same child, so it
     * correlates. "Au present above a threshold and not a detection-limit placeholder"
     * is one exact child query.
     */
    @Test
    public void belowDetectionFlagCorrelatesWithItsOwnMeasurement() throws IOException {
        buildIndex(MEASUREMENTS_CSV, "1000000", "1000001", "1000002");

        assertEquals("collar 1000001's only reported Au is below detection",
            List.of("1000001"),
            filter(and(eq("analyte", "Au"), eq("belowDetection", "t"))));

        assertEquals("real Au determinations only",
            Arrays.asList("1000000", "1000002"),
            filter(and(eq("analyte", "Au"), eq("belowDetection", "f"))));

        // 1000001 has a below-detection Au AND an above-detection Cu. Asking for a
        // below-detection Cu must not match it on the strength of the Au flag.
        assertEquals(Collections.emptyList(),
            filter(and(eq("analyte", "Cu"), eq("belowDetection", "t"))));
    }

    /**
     * The extract is sorted as <em>text</em>, which is what the merge needs. Collar ids
     * of mixed width prove it: 1175968 precedes 117597 lexically but follows it
     * numerically, and this file is in the former order.
     */
    @Test
    public void textSortedExtractWithMixedIdWidthsStreamsCleanly() throws IOException {
        buildIndex(LEXICALLY_SORTED_CSV, "1175968", "117597", "1175971", "117598");

        ExternalChildMerger.SourceStats stats = indexer.getExternalSourceStats().get(0);
        assertEquals("all four rows matched despite the width change", 4, stats.rowsMatched());
        assertEquals(0, stats.rowsUnmatched());
        // Sorted lexically for comparison, which is why 1175968 leads: at index 5 it
        // has '6' against 117597's '7'. The same disagreement the merge has to respect.
        assertEquals(Arrays.asList("1175968", "117597", "1175971", "117598"),
            filter(eq("analyte", "Au")));
    }

    /**
     * What used to be the trap. Re-exporting with {@code ORDER BY collar_id} on an
     * integer column produces numeric order, which is <em>not</em> the lexical order
     * the merge consumes — {@code 1175968} sorts before {@code 117597} because at
     * index 5 it has '6' against '7'. That disagreement once had to be discovered by
     * the operator and fixed with {@code LC_ALL=C sort}.
     * <p>
     * The source is now ordered internally, so a numerically-sorted extract is simply
     * indexed correctly and the trap no longer exists.
     */
    @Test
    public void numericallySortedExtractIsIndexedCorrectly() throws IOException {
        buildIndex(NUMERICALLY_SORTED_CSV, "1175968", "117597", "1175971", "117598");

        ExternalChildMerger.SourceStats stats = indexer.getExternalSourceStats().get(0);
        assertEquals("every row lands despite the numeric export order", 4, stats.rowsMatched());
        assertEquals(0, stats.rowsUnmatched());
        assertEquals(Arrays.asList("1175968", "117597", "1175971", "117598"),
            filter(eq("analyte", "Au")));
    }

    /** The same extract in lexical order gives an identical index — the input order
     *  is no longer observable in the result. */
    @Test
    public void exportOrderDoesNotAffectTheResult() throws IOException {
        buildIndex(NUMERICALLY_SORTED_CSV, "1175968", "117597", "1175971", "117598");
        List<String> fromNumeric = filter(eq("analyte", "Au"));

        buildIndex(LEXICALLY_SORTED_CSV, "1175968", "117597", "1175971", "117598");
        assertEquals(fromNumeric, filter(eq("analyte", "Au")));
    }

    /** A measurement whose collar is not in the graph is counted and dropped — the
     *  normal case when the assay extract is broader than the loaded collar set. */
    @Test
    public void measurementsForUnloadedCollarsAreCounted() throws IOException {
        buildIndex(MEASUREMENTS_CSV, "1000000");

        ExternalChildMerger.SourceStats stats = indexer.getExternalSourceStats().get(0);
        assertEquals(4, stats.rowsMatched());
        assertEquals("collars 1000001 and 1000002 are not loaded: 3 rows dropped",
            3, stats.rowsUnmatched());
        assertEquals(1, stats.entitiesSeen());
        assertEquals(1, stats.entitiesMatched());
    }
}
