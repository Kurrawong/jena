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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.StringReader;
import java.util.List;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.assembler.TextAssembler;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.Test;

/**
 * The configuration fingerprint must be stable against everything that does not change
 * what is written to the index, and sensitive to everything that does.
 * <p>
 * These tests drive the fingerprint from Turtle rather than from hand-built
 * {@link ShaclIndexMapping} objects, because the properties being pinned are properties of
 * the configuration a deployment actually writes — declaration order, prefix choice,
 * comments — and none of those survive to be tested at the object level.
 *
 * @see ShaclConfigFingerprint
 */
public class TestShaclConfigFingerprint {

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    private static final String PREFIXES =
        "@prefix idx:   <urn:jena:lucene:index#> .\n"
        + "@prefix field: <urn:jena:lucene:field#> .\n"
        + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
        + "@prefix text:  <http://jena.apache.org/text#> .\n"
        + "@prefix ex:    <http://example.org/> .\n";

    private static final String FIELDS =
        "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n"
        + "field:commodity idx:fieldName \"commodity\" ; idx:fieldType idx:KeywordField ;\n"
        + "    idx:indexed true ; idx:facetable true ; idx:multiValued true .\n"
        + "field:state idx:fieldName \"state\" ; idx:fieldType idx:KeywordField ;\n"
        + "    idx:indexed true ; idx:facetable true .\n";

    private static final String SHAPE =
        "ex:ReportShape\n"
        + "    sh:targetClass ex:Report ;\n"
        + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
        + "    sh:property [ idx:field field:commodity ; sh:path ex:commodity ] ;\n"
        + "    sh:property [ idx:field field:state ; sh:path ex:state ] .\n";

    /** Parse a shapes graph and fingerprint it with neutral non-mapping settings. */
    private String fingerprintOf(String turtle) {
        return fingerprintOf(turtle, false, List.of());
    }

    private String fingerprintOf(String turtle, boolean storeValues, List<String> facetFields) {
        return fingerprintOf(turtle, storeValues, facetFields, null, "http://example.org/ReportShape");
    }

    private String fingerprintOf(String turtle, boolean storeValues, List<String> facetFields,
                                 String baseUri, String shapeUri) {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(turtle), baseUri, "TTL");
        Resource shapesList = model.createList(new Resource[] { model.getResource(shapeUri) })
            .as(RDFList.class);
        ShaclIndexMapping mapping = ShaclIndexAssembler.parseShapes(Assembler.general(), shapesList);
        return ShaclConfigFingerprint.fingerprint(mapping, storeValues, facetFields);
    }

    private String baseline() {
        return fingerprintOf(PREFIXES + FIELDS + SHAPE);
    }

    // ---- Stability: things that must not change the fingerprint.

    @Test
    public void sameConfigParsedTwiceGivesSameFingerprint() {
        assertEquals(baseline(), baseline());
    }

    @Test
    public void fieldDeclarationOrderIsNotSignificant() {
        String reordered =
            "field:state idx:fieldName \"state\" ; idx:fieldType idx:KeywordField ;\n"
            + "    idx:indexed true ; idx:facetable true .\n"
            + "field:commodity idx:fieldName \"commodity\" ; idx:fieldType idx:KeywordField ;\n"
            + "    idx:indexed true ; idx:facetable true ; idx:multiValued true .\n"
            + "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n";
        assertEquals(baseline(), fingerprintOf(PREFIXES + reordered + SHAPE));
    }

    @Test
    public void propertyOrderWithinAShapeIsNotSignificant() {
        String reordered =
            "ex:ReportShape\n"
            + "    sh:property [ idx:field field:state ; sh:path ex:state ] ;\n"
            + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
            + "    sh:targetClass ex:Report ;\n"
            + "    sh:property [ idx:field field:commodity ; sh:path ex:commodity ] .\n";
        assertEquals(baseline(), fingerprintOf(PREFIXES + FIELDS + reordered));
    }

    @Test
    public void prefixChoiceIsNotSignificant() {
        String otherPrefixes =
            "@prefix indexing: <urn:jena:lucene:index#> .\n"
            + "@prefix f:       <urn:jena:lucene:field#> .\n"
            + "@prefix shacl:   <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix example: <http://example.org/> .\n";
        String fields =
            "f:title indexing:fieldName \"title\" ; indexing:fieldType indexing:TextField ; indexing:defaultSearch true .\n"
            + "f:commodity indexing:fieldName \"commodity\" ; indexing:fieldType indexing:KeywordField ;\n"
            + "    indexing:indexed true ; indexing:facetable true ; indexing:multiValued true .\n"
            + "f:state indexing:fieldName \"state\" ; indexing:fieldType indexing:KeywordField ;\n"
            + "    indexing:indexed true ; indexing:facetable true .\n";
        String shape =
            "example:ReportShape\n"
            + "    shacl:targetClass example:Report ;\n"
            + "    shacl:property [ indexing:field f:title ; shacl:path example:title ] ;\n"
            + "    shacl:property [ indexing:field f:commodity ; shacl:path example:commodity ] ;\n"
            + "    shacl:property [ indexing:field f:state ; shacl:path example:state ] .\n";
        assertEquals(baseline(), fingerprintOf(otherPrefixes + fields + shape));
    }

    @Test
    public void commentsAndWhitespaceAreNotSignificant() {
        String commented =
            "## A comment explaining why this shape exists.\n"
            + "\n\n"
            + SHAPE.replace(" ;\n", " ;   ## trailing note\n");
        assertEquals(baseline(), fingerprintOf(PREFIXES + FIELDS + commented));
    }

    /**
     * Blank node labels are assigned by the parser and differ between parses. If they
     * reached the fingerprint, every restart would look like a configuration change —
     * and the property shapes above are all blank nodes, so this is not a corner case.
     */
    @Test
    public void blankNodeLabelsAreNotSignificant() {
        String withBlankShapeNode = PREFIXES + FIELDS + SHAPE;
        String first = fingerprintOf(withBlankShapeNode);
        String second = fingerprintOf(withBlankShapeNode);
        assertEquals(first, second);
    }

    /**
     * The location of the configuration file must not change the fingerprint.
     * <p>
     * This is not hypothetical: the {@code @prefix : <#>} idiom, which
     * {@code demo/app-static/config.ttl} uses, resolves shape names against the
     * configuration file's own URI. The same shape is
     * {@code file:///build/config.ttl#ReportShape} on an indexing machine and
     * {@code file:///srv/config.ttl#ReportShape} on a serving one. An end-to-end run
     * caught this reporting a MISMATCH purely because the directory had changed —
     * exactly the false positive the design set out to avoid.
     */
    @Test
    public void configFileLocationIsNotSignificant() {
        String relativePrefixShape = PREFIXES
            + "@prefix : <#> .\n"
            + FIELDS
            + ":ReportShape\n"
            + "    sh:targetClass ex:Report ;\n"
            + "    sh:property [ idx:field field:title ; sh:path ex:title ] ;\n"
            + "    sh:property [ idx:field field:commodity ; sh:path ex:commodity ] ;\n"
            + "    sh:property [ idx:field field:state ; sh:path ex:state ] .\n";

        String atBuildMachine = fingerprintOf(relativePrefixShape, false, List.of(),
            "file:///build/config.ttl", "file:///build/config.ttl#ReportShape");
        String atServingMachine = fingerprintOf(relativePrefixShape, false, List.of(),
            "file:///srv/fuseki/config.ttl", "file:///srv/fuseki/config.ttl#ReportShape");

        assertEquals(atBuildMachine, atServingMachine,
            "moving the config file must not look like a configuration change");
    }

    /** Renaming a shape changes nothing on disk, so it must not demand a reindex. */
    @Test
    public void shapeNameIsNotSignificant() {
        String renamed = SHAPE.replace("ex:ReportShape", "ex:SurveyReportShape");
        assertEquals(baseline(),
            fingerprintOf(PREFIXES + FIELDS + renamed, false, List.of(),
                null, "http://example.org/SurveyReportShape"));
    }

    /** A boost weights a field at query time only, so tuning it must not demand a reindex. */
    @Test
    public void searchBoostIsNotSignificant() {
        String boosted = FIELDS.replace(
            "idx:fieldType idx:TextField ; idx:defaultSearch true .",
            "idx:fieldType idx:TextField ; idx:defaultSearch true ; idx:boost 0.5 .");
        assertNotEquals(FIELDS, boosted);
        assertEquals(baseline(), fingerprintOf(PREFIXES + boosted + SHAPE));
    }

    /** idx:copyOf describes two fields' values and changes neither, so it must not demand a reindex. */
    @Test
    public void copyDeclarationIsNotSignificant() {
        String prefixField =
            "field:titlePrefix idx:fieldName \"titlePrefix\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n";
        String shape = SHAPE.replace(" .\n",
            " ;\n    sh:property [ idx:field field:titlePrefix ; sh:path ex:title ] .\n");
        String copy = "field:titlePrefix idx:copyOf field:title .\n";
        assertNotEquals(SHAPE, shape);
        assertEquals(fingerprintOf(PREFIXES + FIELDS + prefixField + shape),
            fingerprintOf(PREFIXES + FIELDS + prefixField + copy + shape));
    }

    // ---- Sensitivity: things that must change the fingerprint.

    @Test
    public void facetableFlagIsSignificant() {
        String changed = FIELDS.replace(
            "field:state idx:fieldName \"state\" ; idx:fieldType idx:KeywordField ;\n    idx:indexed true ; idx:facetable true .",
            "field:state idx:fieldName \"state\" ; idx:fieldType idx:KeywordField ;\n    idx:indexed true ; idx:facetable false .");
        assertNotEquals(baseline(), fingerprintOf(PREFIXES + changed + SHAPE));
    }

    @Test
    public void sortableFlagIsSignificant() {
        String changed = FIELDS.replace("idx:indexed true ; idx:facetable true .",
                                        "idx:indexed true ; idx:facetable true ; idx:sortable true .");
        assertNotEquals(baseline(), fingerprintOf(PREFIXES + changed + SHAPE));
    }

    @Test
    public void multiValuedFlagIsSignificant() {
        String changed = FIELDS.replace("idx:facetable true ; idx:multiValued true .",
                                        "idx:facetable true ; idx:multiValued false .");
        assertNotEquals(baseline(), fingerprintOf(PREFIXES + changed + SHAPE));
    }

    /** {@code idx:stored} defaults to true, so this compares the two explicit settings. */
    @Test
    public void storedFlagIsSignificant() {
        String storedTrue = FIELDS.replace(
            "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .",
            "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true ; idx:stored true .");
        String storedFalse = FIELDS.replace(
            "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .",
            "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true ; idx:stored false .");
        assertNotEquals(fingerprintOf(PREFIXES + storedTrue + SHAPE),
                        fingerprintOf(PREFIXES + storedFalse + SHAPE));
    }

    @Test
    public void fieldTypeIsSignificant() {
        String changed = FIELDS.replace("field:state idx:fieldName \"state\" ; idx:fieldType idx:KeywordField ;",
                                        "field:state idx:fieldName \"state\" ; idx:fieldType idx:TextField ;");
        assertNotEquals(baseline(), fingerprintOf(PREFIXES + changed + SHAPE));
    }

    @Test
    public void addingAFieldIsSignificant() {
        String extraField = FIELDS
            + "field:year idx:fieldName \"year\" ; idx:fieldType idx:IntField ; idx:indexed true .\n";
        String shape = SHAPE.replace(
            "    sh:property [ idx:field field:state ; sh:path ex:state ] .",
            "    sh:property [ idx:field field:state ; sh:path ex:state ] ;\n"
            + "    sh:property [ idx:field field:year ; sh:path ex:year ] .");
        assertNotEquals(baseline(), fingerprintOf(PREFIXES + extraField + shape));
    }

    @Test
    public void removingAFieldIsSignificant() {
        String shape = SHAPE.replace(
            "    sh:property [ idx:field field:state ; sh:path ex:state ] .",
            "    sh:property [ idx:field field:state ; sh:path ex:state ] .")
            .replace("    sh:property [ idx:field field:commodity ; sh:path ex:commodity ] ;\n", "");
        assertNotEquals(baseline(), fingerprintOf(PREFIXES + FIELDS + shape));
    }

    @Test
    public void propertyPathIsSignificant() {
        String shape = SHAPE.replace("sh:path ex:commodity", "sh:path ex:mineral");
        assertNotEquals(baseline(), fingerprintOf(PREFIXES + FIELDS + shape));
    }

    @Test
    public void addingAHierarchyIsSignificant() {
        String shape = SHAPE.replace(
            "    sh:property [ idx:field field:state ; sh:path ex:state ] .",
            "    sh:property [ idx:field field:state ; sh:path ex:state ] ;\n"
            + "    idx:facetHierarchy ( field:state field:commodity ) .");
        assertNotEquals(baseline(), fingerprintOf(PREFIXES + FIELDS + shape));
    }

    /** {@code (continent country state)} is not {@code (state country continent)}. */
    @Test
    public void hierarchyLevelOrderIsSignificant() {
        String forward = SHAPE.replace(
            "    sh:property [ idx:field field:state ; sh:path ex:state ] .",
            "    sh:property [ idx:field field:state ; sh:path ex:state ] ;\n"
            + "    idx:facetHierarchy ( field:state field:commodity ) .");
        String reversed = SHAPE.replace(
            "    sh:property [ idx:field field:state ; sh:path ex:state ] .",
            "    sh:property [ idx:field field:state ; sh:path ex:state ] ;\n"
            + "    idx:facetHierarchy ( field:commodity field:state ) .");
        assertNotEquals(fingerprintOf(PREFIXES + FIELDS + forward),
                        fingerprintOf(PREFIXES + FIELDS + reversed));
    }

    @Test
    public void targetClassIsSignificant() {
        String shape = SHAPE.replace("sh:targetClass ex:Report ;", "sh:targetClass ex:Survey ;");
        assertNotEquals(baseline(), fingerprintOf(PREFIXES + FIELDS + shape));
    }

    // ---- Settings passed alongside the mapping.

    @Test
    public void storeValuesIsSignificant() {
        assertNotEquals(fingerprintOf(PREFIXES + FIELDS + SHAPE, false, List.of()),
                        fingerprintOf(PREFIXES + FIELDS + SHAPE, true, List.of()));
    }

    @Test
    public void facetFieldsAreSignificant() {
        assertNotEquals(fingerprintOf(PREFIXES + FIELDS + SHAPE, false, List.of()),
                        fingerprintOf(PREFIXES + FIELDS + SHAPE, false, List.of("commodity")));
    }

    @Test
    public void facetFieldOrderIsNotSignificant() {
        assertEquals(fingerprintOf(PREFIXES + FIELDS + SHAPE, false, List.of("commodity", "state")),
                     fingerprintOf(PREFIXES + FIELDS + SHAPE, false, List.of("state", "commodity")));
    }

    // ---- Documented limits.

    /**
     * Analyzer <em>parameters</em> are outside the fingerprint: {@code FieldDef} holds an
     * {@link org.apache.lucene.analysis.Analyzer} instance, not the RDF that configured
     * it. A stopword list is treated as data, on the same footing as the triples being
     * indexed. This test pins the current behaviour so that a future fix has an assertion
     * to invert rather than a comment to find.
     */
    @Test
    public void analyzerParametersAreNotCovered() {
        String withStopwordsA = FIELDS.replace(
            "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .",
            "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true ;\n"
            + "    idx:analyzer [ a text:StandardAnalyzer ; text:stopWords ( \"a\" \"the\" ) ] .");
        String withStopwordsB = FIELDS.replace(
            "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .",
            "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true ;\n"
            + "    idx:analyzer [ a text:StandardAnalyzer ; text:stopWords ( \"of\" \"and\" \"but\" ) ] .");
        assertEquals(fingerprintOf(PREFIXES + withStopwordsA + SHAPE),
                     fingerprintOf(PREFIXES + withStopwordsB + SHAPE),
                     "analyzer parameters are documented as outside the fingerprint");
    }

    /** The counterpart to the limit above: the analyzer <em>class</em> is covered. */
    @Test
    public void analyzerClassIsSignificant() {
        String standard = FIELDS.replace(
            "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .",
            "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true ;\n"
            + "    idx:analyzer [ a text:StandardAnalyzer ] .");
        String keyword = FIELDS.replace(
            "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .",
            "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true ;\n"
            + "    idx:analyzer [ a text:KeywordAnalyzer ] .");
        assertNotEquals(fingerprintOf(PREFIXES + standard + SHAPE),
                        fingerprintOf(PREFIXES + keyword + SHAPE));
    }

    // ---- Index layout: a change to what a field writes must change the fingerprint.

    private static final String EVENT_FIELDS =
        "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n";

    private static final String TEMPORAL_FIELD =
        "field:when idx:fieldName \"when\" ; idx:fieldType idx:TemporalField ; idx:storeLiteralMetadata true .\n";

    private String eventFingerprint(String fields, String occurrences) {
        String shape = "ex:EventShape sh:targetClass ex:Event ;\n"
            + "    sh:property [ idx:field field:title ; sh:path ex:title ]" + occurrences + " .\n";
        return fingerprintOf(PREFIXES + fields + shape, false, List.of(), null, "http://example.org/EventShape");
    }

    /**
     * A TemporalField also writes a span per value, for the temporal operators. An index
     * built before that cannot answer them, so its fingerprint must no longer match and
     * startup reports the mismatch. The value is the fingerprint before the span field.
     */
    @Test
    public void anIndexWithATemporalFieldBuiltBeforeSpansNoLongerMatches() {
        assertNotEquals("sha256:eed41ef703987798134814e9b22af271b973d7ff5275ee352dac057f4177fc96",
            eventFingerprint(EVENT_FIELDS + TEMPORAL_FIELD, " ;\n    sh:property [ idx:field field:when ; sh:path ex:when ]"));
    }

    /** ... and an index with no temporal field is unaffected, so it raises no false alarm. */
    @Test
    public void anIndexWithNoTemporalFieldKeepsItsFingerprint() {
        assertEquals("sha256:f72340cfbd41d959db2a2ae4b783e6cc6d383f03f763828d17ba6f185d3f4b68",
            eventFingerprint(EVENT_FIELDS, ""));
    }

    private static final String PERIOD_FIELD =
        "field:term idx:fieldName \"term\" ; idx:fieldType idx:PeriodField .\n";

    private String periodFingerprint(String startPath, String endPath) {
        return eventFingerprint(EVENT_FIELDS + PERIOD_FIELD,
            " ;\n    sh:property [ idx:field field:term ; sh:path ex:term ;"
            + " idx:periodStart " + startPath + " ; idx:periodEnd " + endPath + " ]");
    }

    /**
     * The start and end paths decide what a period spans. Swapping them indexes different
     * spans, so it must change the fingerprint, though the set of predicates is the same.
     */
    @Test
    public void periodStartAndEndPathsAreSignificant() {
        assertNotEquals(periodFingerprint("ex:begins", "ex:ends"), periodFingerprint("ex:ends", "ex:begins"));
        assertNotEquals(periodFingerprint("ex:begins", "ex:ends"), periodFingerprint("ex:begins", "ex:finishes"));
    }

    // ---- Format.

    @Test
    public void fingerprintIsPrefixedShaHex() {
        String fp = baseline();
        assertEquals(7 + 64, fp.length(), "expected 'sha256:' plus 64 hex characters, got: " + fp);
        assertEquals("sha256:", fp.substring(0, 7));
    }

    /**
     * The serialisation is what a diagnostic diff would show, so it must be readable and
     * must actually mention the things it claims to cover.
     */
    @Test
    public void serialisationNamesTheVersionAndTheFields() {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(PREFIXES + FIELDS + SHAPE), null, "TTL");
        Resource shapesList = model.createList(new Resource[] { model.getResource("http://example.org/ReportShape") })
            .as(RDFList.class);
        ShaclIndexMapping mapping = ShaclIndexAssembler.parseShapes(Assembler.general(), shapesList);
        String text = ShaclConfigFingerprint.serialise(mapping, false, List.of());

        org.junit.jupiter.api.Assertions.assertTrue(
            text.startsWith("shaclIndexConfig/v" + ShaclConfigFingerprint.FINGERPRINT_VERSION),
            "serialisation should declare its format version, got: " + text.lines().findFirst().orElse(""));
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("\"commodity\""), "should name the commodity field");
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("facetable=true"), "flags should be explicit");
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("sortable=false"),
            "false flags must be written, not omitted - otherwise an all-false field renders as no field");
    }
}
