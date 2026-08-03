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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclTextIndexLucene;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

/**
 * Parsing and validation of {@code idx:self} — an occurrence bound to the focus node
 * rather than to a path from it.
 *
 * @see org.apache.jena.query.text.TestSelfBoundOccurrences for the behaviour it produces
 */
public class TestSelfOccurrenceAssembler {

    private static final String SH = "http://www.w3.org/ns/shacl#";
    private static final String EX = "http://example.org/";
    private static final String FIELD_NS = "urn:jena:lucene:field#";

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    private Resource field(Model model, String name, Resource type) {
        return model.createResource(FIELD_NS + name)
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), name)
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), type)
            .addProperty(model.createProperty(IndexVocab.NS, "facetable"), model.createTypedLiteral(true));
    }

    private Resource selfOccurrence(Model model, Resource field) {
        return model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "field"), field)
            .addProperty(model.createProperty(IndexVocab.NS, "self"), model.createTypedLiteral(true));
    }

    private Resource pathOccurrence(Model model, Resource field, RDFNode path) {
        return model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "field"), field)
            .addProperty(model.createProperty(SH, "path"), path);
    }

    /** A nested block joined on {@code ex:hasDisplayTable}, with the given occurrences. */
    private Resource nested(Model model, Resource... occurrences) {
        Resource nested = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "joinPath"),
                model.createResource(EX + "hasDisplayTable"));
        for (Resource occurrence : occurrences) {
            nested.addProperty(model.createProperty(IndexVocab.NS, "property"), occurrence);
        }
        return nested;
    }

    private Resource indexSpec(Model model, Resource shape) {
        return model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, model.createList(new RDFNode[] { shape }));
    }

    private Resource shape(Model model) {
        return model.createResource(EX + "DocumentShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Document"));
    }

    private AssemblerException openExpectingFailure(Model model, Resource shape) {
        return assertThrows(AssemblerException.class,
            () -> Assembler.general().open(indexSpec(model, shape)));
    }

    @Test
    public void testSelfOccurrenceInNestedBlockParses() {
        Model model = ModelFactory.createDefaultModel();
        Resource displayTable = field(model, "displayTable", IndexVocab.KeywordField);
        Resource grouping = field(model, "grouping", IndexVocab.KeywordField);

        Resource shape = shape(model).addProperty(model.createProperty(IndexVocab.NS, "nested"),
            nested(model,
                selfOccurrence(model, displayTable),
                pathOccurrence(model, grouping, model.createResource(EX + "hasGrouping"))));

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec(model, shape));
        try {
            IndexProfile profile = index.getShaclMapping().getProfiles().get(0);
            assertEquals(1, profile.getNestedDefs().size());

            FieldOccurrence self = profile.getNestedDefs().get(0).getOccurrences().stream()
                .filter(o -> o.getField().getFieldName().equals("displayTable"))
                .findFirst().orElseThrow();

            assertTrue("occurrence is self-bound", self.isSelf());
            assertEquals("a self occurrence has no path", null, self.getPath());
            assertTrue("and contributes no predicates to change tracking",
                self.getPredicates().isEmpty());
        } finally {
            index.close();
        }
    }

    /** At root scope the focus node is the entity itself. */
    @Test
    public void testSelfOccurrenceAtRootParses() {
        Model model = ModelFactory.createDefaultModel();
        Resource entityIri = field(model, "entityIri", IndexVocab.KeywordField);

        Resource shape = shape(model)
            .addProperty(model.createProperty(SH, "property"), selfOccurrence(model, entityIri));

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec(model, shape));
        try {
            IndexProfile profile = index.getShaclMapping().getProfiles().get(0);
            assertTrue(profile.getRootOccurrences().get(0).isSelf());
        } finally {
            index.close();
        }
    }

    @Test
    public void testSelfAndPathTogetherIsRejected() {
        Model model = ModelFactory.createDefaultModel();
        Resource displayTable = field(model, "displayTable", IndexVocab.KeywordField);

        Resource occurrence = selfOccurrence(model, displayTable)
            .addProperty(model.createProperty(SH, "path"), model.createResource(EX + "hasGrouping"));
        Resource shape = shape(model)
            .addProperty(model.createProperty(IndexVocab.NS, "nested"), nested(model, occurrence));

        assertTrue(openExpectingFailure(model, shape).getMessage()
            .contains("has both idx:self and sh:path"));
    }

    @Test
    public void testOccurrenceWithNeitherSelfNorPathIsRejected() {
        Model model = ModelFactory.createDefaultModel();
        Resource displayTable = field(model, "displayTable", IndexVocab.KeywordField);

        Resource occurrence = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "field"), displayTable);
        Resource shape = shape(model)
            .addProperty(model.createProperty(IndexVocab.NS, "nested"), nested(model, occurrence));

        assertTrue(openExpectingFailure(model, shape).getMessage()
            .contains("is missing sh:path (or idx:self)"));
    }

    /** {@code idx:self false} is a mistake, not a way to say "no self binding". */
    @Test
    public void testSelfFalseIsRejected() {
        Model model = ModelFactory.createDefaultModel();
        Resource displayTable = field(model, "displayTable", IndexVocab.KeywordField);

        Resource occurrence = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "field"), displayTable)
            .addProperty(model.createProperty(IndexVocab.NS, "self"), model.createTypedLiteral(false));
        Resource shape = shape(model)
            .addProperty(model.createProperty(IndexVocab.NS, "nested"), nested(model, occurrence));

        assertTrue(openExpectingFailure(model, shape).getMessage().contains("idx:self on"));
    }

    /** A focus node is a resource, so a numeric field has nothing to bind. */
    @Test
    public void testSelfOnNumericFieldIsRejected() {
        Model model = ModelFactory.createDefaultModel();
        Resource count = field(model, "count", IndexVocab.IntField);

        Resource shape = shape(model)
            .addProperty(model.createProperty(IndexVocab.NS, "nested"),
                nested(model, selfOccurrence(model, count)));

        String message = openExpectingFailure(model, shape).getMessage();
        assertTrue(message.contains("idx:self"));
        assertTrue(message.contains("INT"));
    }

    /** An external child is a row, so there is no focus node to bind. */
    @Test
    public void testSelfOnExternalColumnIsRejected() {
        Model model = ModelFactory.createDefaultModel();
        Resource analyte = field(model, "analyte", IndexVocab.KeywordField);

        Resource column = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "field"), analyte)
            .addProperty(model.createProperty(IndexVocab.NS, "columnName"), "analyte")
            .addProperty(model.createProperty(IndexVocab.NS, "self"), model.createTypedLiteral(true));

        Resource source = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "format"),
                model.createResource(IndexVocab.NS + "CsvFile"))
            .addProperty(model.createProperty(IndexVocab.NS, "location"), "assays.csv")
            .addProperty(model.createProperty(IndexVocab.NS, "subjectColumn"), "hole")
            .addProperty(model.createProperty(IndexVocab.NS, "column"), column);

        Resource shape = shape(model)
            .addProperty(model.createProperty(IndexVocab.NS, "nested"),
                model.createResource()
                    .addProperty(model.createProperty(IndexVocab.NS, "nestedName"), "assays")
                    .addProperty(model.createProperty(IndexVocab.NS, "externalSource"), source));

        assertTrue(openExpectingFailure(model, shape).getMessage()
            .contains("must not carry idx:self"));
    }

    /** A path occurrence is unaffected by any of this. */
    @Test
    public void testPathOccurrenceIsNotSelfBound() {
        Model model = ModelFactory.createDefaultModel();
        Resource grouping = field(model, "grouping", IndexVocab.KeywordField);

        Resource shape = shape(model).addProperty(model.createProperty(SH, "property"),
            pathOccurrence(model, grouping, model.createResource(EX + "hasGrouping")));

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec(model, shape));
        try {
            assertFalse(index.getShaclMapping().getProfiles().get(0)
                .getRootOccurrences().get(0).isSelf());
        } finally {
            index.close();
        }
    }
}
