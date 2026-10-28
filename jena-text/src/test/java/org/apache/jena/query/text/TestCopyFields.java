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

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.*;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.assembler.TextAssembler;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code idx:copyOf}: a copy field declaring the source field that indexes the same values,
 * analysed differently, as the edge-n-gram copy of a title does. The declaration is checked
 * when the index is assembled, against the occurrences the index actually reads, so a
 * consumer that reports a match on the copy field as one on its source field never reports
 * a value the source field does not hold.
 */
public class TestCopyFields {

    static {
        JenaSystem.init();
        TextQuery.init();
        TextAssembler.init();
    }

    private static final String NS = "http://example.org/copy-field/";
    private static final String FIELD = "urn:jena:lucene:field#";

    private static final String PREFIXES =
        "@prefix idx:   <urn:jena:lucene:index#> .\n"
        + "@prefix field: <" + FIELD + "> .\n"
        + "@prefix rdfs:  <http://www.w3.org/2000/01/rdf-schema#> .\n"
        + "@prefix sh:    <http://www.w3.org/ns/shacl#> .\n"
        + "@prefix skos:  <http://www.w3.org/2004/02/skos/core#> .\n"
        + "@prefix text:  <http://jena.apache.org/text#> .\n"
        + "@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .\n"
        + "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
        + "@prefix ex:    <" + NS + "> .\n";

    private static final String FIELDS =
        "field:title idx:fieldName \"title\" ; idx:fieldType idx:TextField ;\n"
        + "    idx:defaultSearch true .\n"
        + "field:titlePrefix idx:fieldName \"titlePrefix\" ; idx:fieldType idx:TextField ;\n"
        + "    idx:defaultSearch true ;\n"
        + "    idx:analyzer [ a text:EdgeNGramAnalyzer ;\n"
        + "        text:minGram 2 ; text:maxGram 20 ; text:tokenized true ] .\n";

    private static final String COPY = "field:titlePrefix idx:copyOf field:title .\n";

    private static final String TITLE_BY_LABEL = "; sh:property [ idx:field field:title ; sh:path rdfs:label ]";
    private static final String PREFIX_BY_LABEL = "; sh:property [ idx:field field:titlePrefix ; sh:path rdfs:label ]";

    /** The field fed by the label of each ex:author, a step through sh:node. */
    private static String byAuthorLabel(String field, String authorConstraints) {
        return "; sh:property [ sh:path ex:author ; sh:node [ " + authorConstraints
            + " sh:property [ idx:field field:" + field + " ; sh:path rdfs:label ] ] ]";
    }

    private static String byLabelIn(String field, String language) {
        return "; sh:property [ idx:field field:" + field + " ; sh:path rdfs:label ; "
            + "sh:languageIn ( \"" + language + "\" ) ]";
    }

    private static String byAlternatives(String field, String alternatives) {
        return "; sh:property [ idx:field field:" + field + " ; "
            + "sh:path [ sh:alternativePath ( " + alternatives + " ) ] ]";
    }

    private static String nested(String properties) {
        return "; idx:nested [ idx:joinPath ex:part ; " + properties + " ]";
    }

    // The index keeps only the first value of a field that isn't idx:multiValued: in the
    // order of the field's occurrences, then of what each path reaches.
    private static final String ALTERNATIVES_IN_OTHER_ORDERS =
        byAlternatives("title", "skos:prefLabel rdfs:label")
        + byAlternatives("titlePrefix", "rdfs:label skos:prefLabel");
    private static final String BY_TWO_PATHS = TITLE_BY_LABEL
        + "; sh:property [ idx:field field:title ; sh:path skos:prefLabel ]"
        + PREFIX_BY_LABEL
        + "; sh:property [ idx:field field:titlePrefix ; sh:path skos:prefLabel ]";
    private static final String MULTI_VALUED_TITLE = "field:title idx:multiValued true .\n";
    private static final String MULTI_VALUED_PREFIX = "field:titlePrefix idx:multiValued true .\n";

    private static final String RDFS_LABEL = "<http://www.w3.org/2000/01/rdf-schema#label>";

    /**
     * Assemble the shapes: ex:ReportShape with {@code report}, and ex:SiteShape with
     * {@code site} unless it is empty.
     */
    private static ShaclIndexMapping parse(String report, String site, String extra) {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(PREFIXES + FIELDS + extra
            + "ex:ReportShape sh:targetClass ex:Report " + report + " .\n"
            + (site.isEmpty() ? "" : "ex:SiteShape sh:targetClass ex:Site " + site + " .\n")), null, "TTL");
        List<Resource> shapes = new ArrayList<>();
        shapes.add(model.getResource(NS + "ReportShape"));
        if (!site.isEmpty()) {
            shapes.add(model.getResource(NS + "SiteShape"));
        }
        RDFList list = model.createList(shapes.iterator()).as(RDFList.class);
        return ShaclIndexAssembler.parseShapes(Assembler.general(), list);
    }

    private static void assertRejected(String report, String site, String extra, String... fragments) {
        TextIndexException ex = assertThrows(TextIndexException.class, () -> parse(report, site, extra));
        for (String fragment : fragments) {
            assertTrue(ex.getMessage().contains(fragment), "expected \"" + fragment + "\" in: " + ex.getMessage());
        }
    }

    // ---- Accepted: the source field holds every value the copy field indexes

    static Stream<Arguments> copies() {
        return Stream.of(
            Arguments.of("every shape", TITLE_BY_LABEL + PREFIX_BY_LABEL, TITLE_BY_LABEL + PREFIX_BY_LABEL, ""),
            // the copy field need not be in every shape the source field is
            Arguments.of("fewer shapes", TITLE_BY_LABEL + PREFIX_BY_LABEL, TITLE_BY_LABEL, ""),
            Arguments.of("an alternative path",
                byAlternatives("title", "skos:prefLabel rdfs:label")
                + byAlternatives("titlePrefix", "skos:prefLabel rdfs:label"), "", ""),
            Arguments.of("nested",
                nested("idx:property [ idx:field field:title ; sh:path rdfs:label ] ; "
                    + "idx:property [ idx:field field:titlePrefix ; sh:path rdfs:label ]"), "", ""),
            Arguments.of("both idx:self",
                nested("idx:property [ idx:field field:title ; idx:self true ] ; "
                    + "idx:property [ idx:field field:titlePrefix ; idx:self true ]"), "", ""),
            Arguments.of("one step",
                "; sh:property [ sh:path ex:author ; sh:node [ "
                + "sh:property [ idx:field field:title ; sh:path rdfs:label ] ; "
                + "sh:property [ idx:field field:titlePrefix ; sh:path rdfs:label ] ] ]", "", ""),
            // two steps that read the same values, each feeding one of the fields
            Arguments.of("equal steps", byAuthorLabel("title", "") + byAuthorLabel("titlePrefix", ""), "", ""),
            Arguments.of("equal filtered steps",
                byAuthorLabel("title", "sh:property [ sh:path ex:role ; sh:hasValue ex:Lead ] ;")
                + byAuthorLabel("titlePrefix", "sh:property [ sh:path ex:role ; sh:hasValue ex:Lead ] ;"), "", ""),
            Arguments.of("equal constraints", byLabelIn("title", "en") + byLabelIn("titlePrefix", "en"), "", ""),
            Arguments.of("both multi-valued", TITLE_BY_LABEL + PREFIX_BY_LABEL, "",
                MULTI_VALUED_TITLE + MULTI_VALUED_PREFIX),
            // a multi-valued source field holds every value, whichever comes first
            Arguments.of("multi-valued source, alternatives in other orders", ALTERNATIVES_IN_OTHER_ORDERS, "",
                MULTI_VALUED_TITLE),
            Arguments.of("multi-valued source, two paths each", BY_TWO_PATHS, "", MULTI_VALUED_TITLE)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    public void copyIsAccepted(String name, String report, String site, String extra) {
        assertNotNull(parse(report, site, COPY + extra));
    }

    // ---- Rejected: the source field may lack a value the copy field indexes

    static Stream<Arguments> notCopies() {
        String fedButNotSource = "feeds 'titlePrefix' and no occurrence of 'title' matches it";
        return Stream.of(
            Arguments.of("a different path", TITLE_BY_LABEL + PREFIX_BY_LABEL,
                TITLE_BY_LABEL + "; sh:property [ idx:field field:titlePrefix ; sh:path skos:prefLabel ]", "",
                new String[] { "shape <" + NS + "SiteShape>",
                    "sh:path <http://www.w3.org/2004/02/skos/core#prefLabel> " + fedButNotSource,
                    "fed there by the occurrence sh:path " + RDFS_LABEL + " instead" }),
            Arguments.of("missing from a shape", TITLE_BY_LABEL + PREFIX_BY_LABEL, PREFIX_BY_LABEL, "",
                new String[] { "shape <" + NS + "SiteShape>", "sh:path " + RDFS_LABEL + " " + fedButNotSource }),
            Arguments.of("other nesting",
                nested("idx:property [ idx:field field:title ; sh:path rdfs:label ]") + PREFIX_BY_LABEL, "", "",
                new String[] { "sh:path " + RDFS_LABEL + " " + fedButNotSource,
                    "sh:path " + RDFS_LABEL + " in idx:nested <" + NS + "part> instead" }),
            // two blocks with one join path, and so one name, write separate child documents
            Arguments.of("separate blocks with one name",
                nested("idx:property [ idx:field field:title ; sh:path rdfs:label ]")
                + nested("idx:property [ idx:field field:titlePrefix ; sh:path rdfs:label ]"), "", "",
                new String[] { fedButNotSource, "of 2 with that name", "separate child documents" }),
            Arguments.of("idx:self or a path",
                nested("idx:property [ idx:field field:title ; idx:self true ] ; "
                    + "idx:property [ idx:field field:titlePrefix ; sh:path rdfs:label ]"), "", "",
                new String[] { fedButNotSource, "idx:self in idx:nested <" + NS + "part> instead" }),
            // the same local path, but an author's label rather than the resource's
            Arguments.of("through a step", TITLE_BY_LABEL + byAuthorLabel("titlePrefix", ""), "", "",
                new String[] { "sh:path <" + NS + "author> then sh:path " + RDFS_LABEL + " " + fedButNotSource }),
            Arguments.of("other language", byLabelIn("title", "en") + byLabelIn("titlePrefix", "fr"), "", "",
                new String[] { fedButNotSource, "\"fr\"" }),
            Arguments.of("only one constrained", TITLE_BY_LABEL + byLabelIn("titlePrefix", "en"), "", "",
                new String[] { "with the value constraints", fedButNotSource }),
            // only the title's step keeps authors with a role
            Arguments.of("a filtered step",
                byAuthorLabel("title", "sh:property [ sh:path ex:role ; sh:hasValue ex:Lead ] ;")
                + byAuthorLabel("titlePrefix", ""), "", "",
                new String[] { fedButNotSource, "filtered by", "<" + NS + "Lead>" }),
            // the same role property, but in the prefix's step it feeds a field, so it
            // doesn't filter the authors that step reaches
            Arguments.of("a filter or a field",
                byAuthorLabel("title", "sh:property [ sh:path ex:role ; sh:in ( \"lead\" ) ] ;")
                + byAuthorLabel("titlePrefix",
                    "sh:property [ idx:field field:role ; sh:path ex:role ; sh:in ( \"lead\" ) ] ;"), "",
                "field:role idx:fieldName \"role\" ; idx:fieldType idx:KeywordField .\n",
                new String[] { "sh:path <" + NS + "author> then sh:path " + RDFS_LABEL + " " + fedButNotSource,
                    "filtered by" }),
            // the declaring field keeps labels after the first, which the title drops
            Arguments.of("a single-valued source of a multi-valued copy", TITLE_BY_LABEL + PREFIX_BY_LABEL, "",
                MULTI_VALUED_PREFIX,
                new String[] { "'titlePrefix' is idx:multiValued and 'title' is not" }),
            // each single-valued field may keep a different first label
            Arguments.of("single-valued, alternatives in other orders", ALTERNATIVES_IN_OTHER_ORDERS, "", "",
                new String[] { "reach their values in different orders", "idx:multiValued" }),
            Arguments.of("single-valued, two paths each", BY_TWO_PATHS, "", "",
                new String[] { "'titlePrefix' is fed by 2 occurrences", "idx:multiValued" })
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notCopies")
    public void notACopyIsRejected(String name, String report, String site, String extra, String[] fragments) {
        assertRejected(report, site, COPY + extra, fragments);
    }

    @Test
    public void sourceMustBeATextField() {
        assertRejected(TITLE_BY_LABEL + PREFIX_BY_LABEL + "; sh:property [ idx:field field:sortTitle ; sh:path rdfs:label ]", "",
            "field:sortTitle idx:fieldName \"sortTitle\" ; idx:fieldType idx:KeywordField .\n"
            + "field:titlePrefix idx:copyOf field:sortTitle .\n",
            "'sortTitle' has type KEYWORD");
    }

    @Test
    public void copyFieldMustBeATextField() {
        assertRejected(TITLE_BY_LABEL + "; sh:property [ idx:field field:code ; sh:path rdfs:label ]", "",
            "field:code idx:fieldName \"code\" ; idx:fieldType idx:KeywordField ; idx:copyOf field:title .\n",
            "'code' has type KEYWORD");
    }

    @Test
    public void fieldCannotBeACopyOfItself() {
        assertRejected(TITLE_BY_LABEL + PREFIX_BY_LABEL, "", "field:titlePrefix idx:copyOf field:titlePrefix .\n",
            "a copy of itself");
    }

    @Test
    public void sourceMustBeAFieldOfTheIndex() {
        assertRejected(TITLE_BY_LABEL + PREFIX_BY_LABEL, "", "field:titlePrefix idx:copyOf field:nothing .\n",
            "<" + FIELD + "nothing>", "no shape");
    }

    /** A consumer follows one declaration to the field it reports a match under. */
    @Test
    public void sourceMustNotItselfBeACopy() {
        assertRejected(TITLE_BY_LABEL + PREFIX_BY_LABEL + "; sh:property [ idx:field field:titleStem ; sh:path rdfs:label ]", "",
            COPY + "field:titleStem idx:fieldName \"titleStem\" ; idx:fieldType idx:TextField ;\n"
            + "    idx:copyOf field:titlePrefix .\n",
            "'titlePrefix' is itself idx:copyOf 'title'");
    }

    @Test
    public void sourceMustBeNamedByIri() {
        assertRejected(TITLE_BY_LABEL + PREFIX_BY_LABEL, "", "field:titlePrefix idx:copyOf \"title\" .\n",
            "must name a field by its IRI");
    }

    @Test
    public void copyOfOneFieldOnly() {
        assertRejected(TITLE_BY_LABEL + PREFIX_BY_LABEL + "; sh:property [ idx:field field:label ; sh:path rdfs:label ]", "",
            COPY + "field:label idx:fieldName \"label\" ; idx:fieldType idx:TextField .\n"
            + "field:titlePrefix idx:copyOf field:label .\n",
            "more than one field");
    }

    @Test
    public void copyOfOnAnOccurrenceIsRejected() {
        assertRejected(TITLE_BY_LABEL
            + "; sh:property [ idx:field field:titlePrefix ; sh:path rdfs:label ; idx:copyOf field:title ]", "", "",
            "copyOf");
    }

    // ---- luc:config

    @Test
    public void declarationIsExposedByLucConfig() {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(PREFIXES + FIELDS + COPY
            + "ex:ReportShape sh:targetClass ex:Report " + TITLE_BY_LABEL + PREFIX_BY_LABEL + " .\n"
            + "ex:dataset rdf:type text:TextDataset ;\n"
            + "    text:dataset ex:base ;\n"
            + "    text:index ex:index .\n"
            + "ex:base rdf:type ja:MemoryDataset .\n"
            + "ex:index rdf:type text:TextIndexShacl ;\n"
            + "    text:directory \"mem\" ;\n"
            + "    text:exposeConfig true ;\n"
            + "    text:shapes ( ex:ReportShape ) .\n"), null, "TTL");
        Dataset dataset = (Dataset) Assembler.general().open(model.getResource(NS + "dataset"));
        try {
            String q = "PREFIX luc:   <urn:jena:lucene:index#>\n"
                + "PREFIX idx:   <urn:jena:lucene:index#>\n"
                + "PREFIX field: <" + FIELD + ">\n"
                + "SELECT ?source WHERE {\n"
                + "  (field:titlePrefix idx:copyOf ?source) luc:config (\"default\") .\n"
                + "}";
            try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
                ResultSet rs = qe.execSelect();
                assertTrue(rs.hasNext());
                assertEquals(FIELD + "title", rs.next().getResource("source").getURI());
                assertFalse(rs.hasNext());
            }
        } finally {
            dataset.close();
        }
    }
}
