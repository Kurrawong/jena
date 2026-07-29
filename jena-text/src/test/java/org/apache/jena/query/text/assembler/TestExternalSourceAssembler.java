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

package org.apache.jena.query.text.assembler;

import static org.junit.Assert.*;

import java.io.StringReader;
import java.util.Collections;
import java.util.List;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.text.ShaclIndexMapping;
import org.apache.jena.query.text.ShaclIndexMapping.ColumnBinding;
import org.apache.jena.query.text.ShaclIndexMapping.ErrorPolicy;
import org.apache.jena.query.text.ShaclIndexMapping.ExternalFormat;
import org.apache.jena.query.text.ShaclIndexMapping.ExternalSourceDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.TextIndexException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sys.JenaSystem;
import org.junit.Test;

/**
 * Config parsing for {@code idx:externalSource} — the Turtle a deployment actually
 * writes. See {@code docs/2026-07-27_external_content_indexing_design.md}.
 */
public class TestExternalSourceAssembler {

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    private static final String PREFIXES =
        "@prefix idx:   <urn:jena:lucene:index#> .\n"
        + "@prefix field: <urn:jena:lucene:field#> .\n"
        + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
        + "@prefix ex:    <http://example.org/> .\n"
        + "@prefix xsd:   <http://www.w3.org/2001/XMLSchema#> .\n";

    private static final String FIELDS =
        "field:sampleName idx:fieldName \"sampleName\" ; idx:fieldType idx:TextField ; idx:defaultSearch true .\n"
        + "field:measuredProperty idx:fieldName \"measuredProperty\" ; idx:fieldType idx:KeywordField ;\n"
        + "    idx:indexed true ; idx:facetable true ; idx:stored true .\n"
        + "field:measuredValue idx:fieldName \"measuredValue\" ; idx:fieldType idx:DoubleField ;\n"
        + "    idx:indexed true ; idx:facetable true ; idx:sortable true ; idx:stored false .\n";

    /** Parse a shapes graph and return the single profile it defines. */
    private IndexProfile parseSingleProfile(String shapeTurtle) {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(PREFIXES + FIELDS + shapeTurtle), null, "TTL");
        Resource shapesList = model.createList(new Resource[] { model.getResource("http://example.org/SampleShape") })
            .as(RDFList.class);
        ShaclIndexMapping mapping = ShaclIndexAssembler.parseShapes(Assembler.general(), shapesList);
        assertEquals(1, mapping.getProfiles().size());
        return mapping.getProfiles().get(0);
    }

    private static final String NARROW_SOURCE_SHAPE =
        "ex:SampleShape\n"
        + "    sh:targetClass ex:Sample ;\n"
        + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
        + "    idx:nested [\n"
        + "        idx:nestedName \"measurement\" ;\n"
        + "        idx:externalSource [\n"
        + "            idx:format        idx:CsvFile ;\n"
        + "            idx:location      \"/data/measurements.csv\" ;\n"
        + "            idx:subjectColumn \"sample_iri\" ;\n"
        + "            idx:column [ idx:columnName \"property\" ; idx:field field:measuredProperty ] ;\n"
        + "            idx:column [ idx:columnName \"value\" ;    idx:field field:measuredValue ] ;\n"
        + "        ] ;\n"
        + "    ] .\n";

    @Test
    public void parsesNarrowCsvSource() {
        IndexProfile profile = parseSingleProfile(NARROW_SOURCE_SHAPE);

        assertEquals(1, profile.getNestedDefs().size());
        NestedDef nested = profile.getNestedDefs().get(0);
        assertTrue("nested block is external", nested.isExternal());
        assertEquals("measurement", nested.getNestedName());
        assertNull("an external block has no join path", nested.getJoinPath());
        assertTrue("and no field occurrences", nested.getOccurrences().isEmpty());

        ExternalSourceDef source = nested.getExternalSource();
        assertEquals(ExternalFormat.CSV, source.getFormat());
        assertEquals("/data/measurements.csv", source.getLocation());
        assertEquals("sample_iri", source.getSubjectColumn());
        assertNull(source.getSubjectPrefix());
        assertEquals("skip is the default error policy", ErrorPolicy.SKIP, source.getOnError());
        assertEquals(0.0, source.getMinMatchRate(), 0.0);
        assertEquals(2, source.getColumns().size());
    }

    /** Bound fields become fields of the profile, so they reach the facet config,
     *  the field-name lookup and the query API exactly like graph-derived fields. */
    @Test
    public void boundFieldsAreProfileFields() {
        IndexProfile profile = parseSingleProfile(NARROW_SOURCE_SHAPE);

        assertEquals(3, profile.getFields().size());
        ColumnBinding value = profile.getNestedDefs().get(0).getExternalSource().getColumns().stream()
            .filter(c -> "value".equals(c.getColumnName())).findFirst().orElseThrow();
        assertEquals("measuredValue", value.getField().getFieldName());
        assertEquals(FieldType.DOUBLE, value.getField().getFieldType());
        assertFalse("the value is not stored", value.getField().isStored());
        assertTrue("but it is sortable", value.getField().isSortable());
    }

    @Test
    public void parsesOptionalSourceProperties() {
        IndexProfile profile = parseSingleProfile(
            "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format        idx:TsvFile ;\n"
            + "            idx:location      \"/data/meas-*.tsv\" ;\n"
            + "            idx:subjectColumn \"sample_id\" ;\n"
            + "            idx:subjectPrefix \"https://ex.org/id/sample/\" ;\n"
            + "            idx:onError       \"fail\" ;\n"
            + "            idx:minMatchRate  \"0.75\"^^xsd:double ;\n"
            + "            idx:column [ idx:columnName \"property\" ; idx:field field:measuredProperty ] ;\n"
            + "        ] ;\n"
            + "    ] .\n");

        ExternalSourceDef source = profile.getNestedDefs().get(0).getExternalSource();
        assertEquals(ExternalFormat.TSV, source.getFormat());
        assertEquals("https://ex.org/id/sample/", source.getSubjectPrefix());
        assertEquals(ErrorPolicy.FAIL, source.getOnError());
        assertEquals(0.75, source.getMinMatchRate(), 1e-9);
    }

    @Test
    public void parsesHeaderlessPositionalBinding() {
        IndexProfile profile = parseSingleProfile(
            "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format             idx:CsvFile ;\n"
            + "            idx:location           \"/data/m.csv\" ;\n"
            + "            idx:headerless         true ;\n"
            + "            idx:subjectColumnIndex 0 ;\n"
            + "            idx:column [ idx:columnIndex 1 ; idx:field field:measuredProperty ] ;\n"
            + "            idx:column [ idx:columnIndex 2 ; idx:field field:measuredValue ] ;\n"
            + "        ] ;\n"
            + "    ] .\n");

        ExternalSourceDef source = profile.getNestedDefs().get(0).getExternalSource();
        assertTrue(source.isHeaderless());
        assertEquals(0, source.getSubjectColumnIndex());
        assertEquals("columns keep declaration index order", 1, source.getColumns().get(0).getColumnIndex());
        assertEquals(2, source.getColumns().get(1).getColumnIndex());
    }

    /** Children come from the graph or from rows, never both — the two are different
     *  ways of populating the same child collection and cannot be combined. */
    @Test
    public void joinPathAndExternalSourceAreMutuallyExclusive() {
        TextIndexException e = assertThrows(TextIndexException.class, () -> parseSingleProfile(
            "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:joinPath ex:measurement ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format idx:CsvFile ; idx:location \"/data/m.csv\" ;\n"
            + "            idx:subjectColumn \"sample_iri\" ;\n"
            + "            idx:column [ idx:columnName \"property\" ; idx:field field:measuredProperty ] ;\n"
            + "        ] ;\n"
            + "    ] .\n"));
        assertTrue(e.getMessage(), e.getMessage().contains("idx:joinPath"));
    }

    /** There is no join path to derive a scope name from, so it must be given. */
    @Test
    public void externalBlockRequiresNestedName() {
        TextIndexException e = assertThrows(TextIndexException.class, () -> parseSingleProfile(
            "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:externalSource [\n"
            + "            idx:format idx:CsvFile ; idx:location \"/data/m.csv\" ;\n"
            + "            idx:subjectColumn \"sample_iri\" ;\n"
            + "            idx:column [ idx:columnName \"property\" ; idx:field field:measuredProperty ] ;\n"
            + "        ] ;\n"
            + "    ] .\n"));
        assertTrue(e.getMessage(), e.getMessage().contains("idx:nestedName"));
    }

    /** A bound column supplies the value; an sh:path would be a second, contradicting
     *  source for the same field. Externality is derived from the binding, so there is
     *  no flag that could disagree with it either. */
    @Test
    public void boundColumnMustNotCarryAPath() {
        TextIndexException e = assertThrows(TextIndexException.class, () -> parseSingleProfile(
            "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format idx:CsvFile ; idx:location \"/data/m.csv\" ;\n"
            + "            idx:subjectColumn \"sample_iri\" ;\n"
            + "            idx:column [ idx:columnName \"property\" ; sh:path ex:prop ;\n"
            + "                         idx:field field:measuredProperty ] ;\n"
            + "        ] ;\n"
            + "    ] .\n"));
        assertTrue(e.getMessage(), e.getMessage().contains("sh:path"));
    }

    @Test
    public void columnNeedsExactlyOneOfNameOrIndex() {
        TextIndexException e = assertThrows(TextIndexException.class, () -> parseSingleProfile(
            "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format idx:CsvFile ; idx:location \"/data/m.csv\" ;\n"
            + "            idx:subjectColumn \"sample_iri\" ;\n"
            + "            idx:column [ idx:columnName \"property\" ; idx:columnIndex 1 ;\n"
            + "                         idx:field field:measuredProperty ] ;\n"
            + "        ] ;\n"
            + "    ] .\n"));
        assertTrue(e.getMessage(), e.getMessage().contains("idx:columnName"));
    }

    @Test
    public void unknownFormatIsRejected() {
        TextIndexException e = assertThrows(TextIndexException.class, () -> parseSingleProfile(
            "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format idx:ParquetFile ; idx:location \"/data/m.parquet\" ;\n"
            + "            idx:subjectColumn \"sample_iri\" ;\n"
            + "            idx:column [ idx:columnName \"property\" ; idx:field field:measuredProperty ] ;\n"
            + "        ] ;\n"
            + "    ] .\n"));
        assertTrue(e.getMessage(), e.getMessage().contains("idx:format"));
    }

    /** A temporal field needs literal metadata a bare cell cannot carry. Rejecting it at
     *  config time beats discovering it a hundred million rows into a build. */
    @Test
    public void unsupportedColumnFieldTypeIsRejected() {
        TextIndexException e = assertThrows(TextIndexException.class, () -> parseSingleProfile(
            "field:when idx:fieldName \"when\" ; idx:fieldType idx:TemporalField ;\n"
            + "    idx:storeLiteralMetadata true .\n"
            + "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format idx:CsvFile ; idx:location \"/data/m.csv\" ;\n"
            + "            idx:subjectColumn \"sample_iri\" ;\n"
            + "            idx:column [ idx:columnName \"when\" ; idx:field field:when ] ;\n"
            + "        ] ;\n"
            + "    ] .\n"));
        assertTrue(e.getMessage(), e.getMessage().contains("TEMPORAL"));
    }

    @Test
    public void externalSourceRequiresLocationAndSubjectColumn() {
        assertThrows(TextIndexException.class, () -> parseSingleProfile(
            "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format idx:CsvFile ;\n"
            + "            idx:subjectColumn \"sample_iri\" ;\n"
            + "            idx:column [ idx:columnName \"property\" ; idx:field field:measuredProperty ] ;\n"
            + "        ] ;\n"
            + "    ] .\n"));
    }

    /** A single delta file, and the default op column name. */
    @Test
    public void parsesASingleDelta() {
        IndexProfile profile = parseSingleProfile(
            "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format idx:CsvFile ; idx:location \"/data/m.csv\" ;\n"
            + "            idx:subjectColumn \"sample_iri\" ;\n"
            + "            idx:delta \"/data/m-2026-07.csv\" ;\n"
            + "            idx:column [ idx:columnName \"property\" ; idx:field field:measuredProperty ] ;\n"
            + "        ] ;\n"
            + "    ] .\n");

        ExternalSourceDef source = profile.getNestedDefs().get(0).getExternalSource();
        assertTrue(source.hasDeltas());
        assertEquals(List.of("/data/m-2026-07.csv"), source.getDeltaLocations());
        assertEquals("op", source.getOpColumn());
    }

    /** Several deltas as an ordered list, plus a renamed op column. */
    @Test
    public void parsesOrderedDeltaListAndOpColumn() {
        IndexProfile profile = parseSingleProfile(
            "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format idx:CsvFile ; idx:location \"/data/m.csv\" ;\n"
            + "            idx:subjectColumn \"sample_iri\" ;\n"
            + "            idx:delta ( \"/data/d1.csv\" \"/data/d2.csv\" ) ;\n"
            + "            idx:opColumn \"operation\" ;\n"
            + "            idx:column [ idx:columnName \"property\" ; idx:field field:measuredProperty ] ;\n"
            + "        ] ;\n"
            + "    ] .\n");

        ExternalSourceDef source = profile.getNestedDefs().get(0).getExternalSource();
        assertEquals("order is taken from the list, not from statement order",
            List.of("/data/d1.csv", "/data/d2.csv"), source.getDeltaLocations());
        assertEquals("operation", source.getOpColumn());
    }

    /** Repeated idx:delta statements have no defined order, and deltas are
     *  order-sensitive, so this must not be silently accepted. */
    @Test
    public void repeatedDeltaStatementsAreRejected() {
        TextIndexException e = assertThrows(TextIndexException.class, () -> parseSingleProfile(
            "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format idx:CsvFile ; idx:location \"/data/m.csv\" ;\n"
            + "            idx:subjectColumn \"sample_iri\" ;\n"
            + "            idx:delta \"/data/d1.csv\" ;\n"
            + "            idx:delta \"/data/d2.csv\" ;\n"
            + "            idx:column [ idx:columnName \"property\" ; idx:field field:measuredProperty ] ;\n"
            + "        ] ;\n"
            + "    ] .\n"));
        assertTrue(e.getMessage(), e.getMessage().contains("one list"));
    }

    /**
     * Merging a delta with its base is a per-subject operation and so needs one
     * ordering across both, but that ordering is now established by SortingRowSource
     * rather than demanded of the operator. Config that would once have been rejected
     * for lacking {@code idx:sorted} is accepted.
     */
    @Test
    public void deltaOnAnUnorderedSourceIsAccepted() {
        IndexProfile profile = parseSingleProfile(
            "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format idx:CsvFile ; idx:location \"/data/m.csv\" ;\n"
            + "            idx:subjectColumn \"sample_iri\" ;\n"
            + "            idx:delta \"/data/d1.csv\" ;\n"
            + "            idx:column [ idx:columnName \"property\" ; idx:field field:measuredProperty ] ;\n"
            + "        ] ;\n"
            + "    ] .\n");

        ExternalSourceDef source = profile.getNestedDefs().get(0).getExternalSource();
        assertTrue(source.hasDeltas());
        assertEquals(Collections.singletonList("/data/d1.csv"), source.getDeltaLocations());
    }

    /** A hierarchy over external children is ordinary config — the levels just have to
     *  be fields of the same scope. */
    @Test
    public void facetHierarchyOverExternalFieldsIsAccepted() {
        IndexProfile profile = parseSingleProfile(
            "field:measuredBand idx:fieldName \"measuredBand\" ; idx:fieldType idx:KeywordField ;\n"
            + "    idx:facetable true .\n"
            + "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format idx:CsvFile ; idx:location \"/data/m.csv\" ;\n"
            + "            idx:subjectColumn \"sample_iri\" ;\n"
            + "            idx:column [ idx:columnName \"property\" ; idx:field field:measuredProperty ] ;\n"
            + "            idx:column [ idx:columnName \"band\" ;     idx:field field:measuredBand ] ;\n"
            + "        ] ;\n"
            + "        idx:facetHierarchy ( field:measuredProperty field:measuredBand ) ;\n"
            + "    ] .\n");

        NestedDef nested = profile.getNestedDefs().get(0);
        assertEquals(1, nested.getHierarchies().size());
        assertEquals("measuredProperty_measuredBand", nested.getHierarchies().get(0).getDimensionName());
    }

    /** A hierarchy level that is not populated by this source would silently never
     *  produce a facet path. */
    @Test
    public void facetHierarchyLevelOutsideTheSourceIsRejected() {
        assertThrows(TextIndexException.class, () -> parseSingleProfile(
            "ex:SampleShape\n"
            + "    sh:targetClass ex:Sample ;\n"
            + "    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;\n"
            + "    idx:nested [\n"
            + "        idx:nestedName \"measurement\" ;\n"
            + "        idx:externalSource [\n"
            + "            idx:format idx:CsvFile ; idx:location \"/data/m.csv\" ;\n"
            + "            idx:subjectColumn \"sample_iri\" ;\n"
            + "            idx:column [ idx:columnName \"property\" ; idx:field field:measuredProperty ] ;\n"
            + "        ] ;\n"
            + "        idx:facetHierarchy ( field:measuredProperty field:sampleName ) ;\n"
            + "    ] .\n"));
    }
}
