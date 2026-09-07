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

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.query.text.ShaclIndexMapping;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.ShaclTextIndexLucene;
import org.apache.jena.query.text.TextIndexException;
import org.apache.jena.query.text.analyzer.EdgeNGramAnalyzer;
import org.apache.jena.query.text.analyzer.LowerCaseKeywordAnalyzer;
import org.apache.jena.rdf.model.*;
import org.apache.jena.sparql.path.P_Inverse;
import org.apache.jena.sparql.path.P_Link;
import org.apache.jena.sparql.path.P_Seq;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.Test;

/**
 * Tests for SHACL assembler config parsing.
 */
public class TestShaclAssembler {

    private static final String SH = "http://www.w3.org/ns/shacl#";
    private static final String EX = "http://example.org/";

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    private Model createModel() {
        return ModelFactory.createDefaultModel();
    }

    private Resource occurrence(Model model, Resource field, RDFNode pathNode) {
        return model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "field"), field)
            .addProperty(model.createProperty(SH, "path"), pathNode);
    }

    private Resource buildShaclIndexSpec(Model model) {
        Resource labelField = model.createResource(EX + "labelField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "label")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
            .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true));

        Resource bookShape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, labelField, RDFS.label));

        RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });
        return model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);
    }

    @Test
    public void testShaclShapesParsed() {
        Model model = createModel();
        Resource indexSpec = buildShaclIndexSpec(model);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            assertTrue(index.isShaclMode());
            ShaclIndexMapping mapping = index.getShaclMapping();
            assertNotNull(mapping);
            assertEquals(1, mapping.getProfiles().size());

            IndexProfile profile = mapping.getProfiles().get(0);
            assertEquals(1, profile.getFields().size());
            assertEquals("label", profile.getFields().get(0).getFieldName());
            assertEquals(1, profile.getRootOccurrences().size());
        } finally {
            index.close();
        }
    }

    @Test
    public void testDerivedEntityDefinition() {
        Model model = createModel();
        Resource indexSpec = buildShaclIndexSpec(model);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            assertNotNull(index.getDocDef());
            assertEquals("uri", index.getDocDef().getEntityField());
            assertEquals("label", index.getDocDef().getPrimaryField());
            assertEquals(RDFS.label.asNode(), index.getDocDef().getPrimaryPredicate());
        } finally {
            index.close();
        }
    }

    @Test
    public void testInversePathParsed() {
        Model model = createModel();

        Resource titleField = model.createResource(EX + "titleField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "title")
            .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true));
        Resource wroteByField = model.createResource(EX + "wroteByField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "wroteBy")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField);

        Resource inversePath = model.createResource()
            .addProperty(model.createProperty(SH, "inversePath"), model.createResource(EX + "wrote"));

        Resource bookShape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, titleField, RDFS.label))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, wroteByField, inversePath));

        RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            FieldOccurrence wroteBy = findRootOccurrence(index.getShaclMapping().getProfiles().get(0), "wroteBy");
            assertNotNull(wroteBy);
            assertTrue(wroteBy.getPath() instanceof P_Inverse);
        } finally {
            index.close();
        }
    }

    @Test
    public void testQueryAnalyzerAssembled() {
        Model model = createModel();

        Resource titleField = model.createResource(EX + "titleField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "title")
            .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true));
        Resource identifierField = model.createResource(EX + "identifierField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifier")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
            .addProperty(model.createProperty(IndexVocab.NS, "analyzer"),
                model.createResource().addProperty(RDF.type, TextVocab.edgeNGramAnalyzer))
            .addProperty(model.createProperty(IndexVocab.NS, "queryAnalyzer"),
                model.createResource().addProperty(RDF.type, TextVocab.lowerCaseKeywordAnalyzer));

        Resource bookShape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, titleField, RDFS.label))
            .addProperty(model.createProperty(SH, "property"),
                occurrence(model, identifierField, model.createResource(EX + "identifier")));

        RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            FieldDef idField = index.getShaclMapping().findField(identifierField.getURI());
            assertNotNull(idField);
            assertNotNull(idField.getAnalyzer());
            assertNotNull(idField.getQueryAnalyzer());
            assertNotSame(idField.getAnalyzer(), idField.getQueryAnalyzer());
        } finally {
            index.close();
        }
    }

    /**
     * The recommended typeahead config in docs/03-configuration.md: an edge-n-gram field
     * declares its mode and nothing else, and the matching query analyzer is implied.
     */
    @Test
    public void testEdgeNGramFieldsImplyTheirQueryAnalyzer() {
        Model model = createModel();

        Resource titleField = model.createResource(EX + "titleField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "title")
            .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true));
        // Whole-value prefixes: an identifier typed from the start.
        Resource identifierField = model.createResource(EX + "identifierField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifier")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
            .addProperty(model.createProperty(IndexVocab.NS, "analyzer"),
                model.createResource().addProperty(RDF.type, TextVocab.edgeNGramAnalyzer));
        // Per-word prefixes: a name, where any word can be typed.
        Resource agentField = model.createResource(EX + "agentField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "agentText")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
            .addProperty(model.createProperty(IndexVocab.NS, "analyzer"),
                model.createResource()
                    .addProperty(RDF.type, TextVocab.edgeNGramAnalyzer)
                    .addProperty(TextVocab.pTokenized, model.createTypedLiteral(true)));

        Resource bookShape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, titleField, RDFS.label))
            .addProperty(model.createProperty(SH, "property"),
                occurrence(model, identifierField, model.createResource(EX + "identifier")))
            .addProperty(model.createProperty(SH, "property"),
                occurrence(model, agentField, model.createResource(EX + "agent")));

        RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            FieldDef idField = index.getShaclMapping().findField(identifierField.getURI());
            assertTrue("text:tokenized defaults to false",
                idField.getAnalyzer() instanceof EdgeNGramAnalyzer ngram && !ngram.isTokenized());
            assertTrue("whole-value n-grams imply a whole-value query analyzer",
                idField.getQueryAnalyzer() instanceof LowerCaseKeywordAnalyzer);

            FieldDef textField = index.getShaclMapping().findField(agentField.getURI());
            assertTrue("text:tokenized true selects per-word n-grams",
                textField.getAnalyzer() instanceof EdgeNGramAnalyzer ngram && ngram.isTokenized());
            assertTrue("per-word n-grams imply a word-tokenizing query analyzer",
                textField.getQueryAnalyzer() instanceof StandardAnalyzer);
        } finally {
            index.close();
        }
    }

    @Test
    public void testDateFieldRequiresLiteralMetadata() {
        Model model = createModel();

        Resource dateField = model.createResource(EX + "eventDateField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "eventDate")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.DateField);

        Resource bookShape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(model.createProperty(SH, "property"),
                occurrence(model, dateField, model.createResource(EX + "eventDate")));

        RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        AssemblerException ex = assertThrows(AssemblerException.class,
            () -> Assembler.general().open(indexSpec));
        assertTrue(ex.getCause() instanceof TextIndexException);
        assertTrue(ex.getCause().getMessage().contains("requires idx:storeLiteralMetadata true"));
    }

    @Test
    public void testNormalizerOnKeywordFieldParsed() {
        Model model = createModel();

        Resource labelField = model.createResource(EX + "labelField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "label")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
            .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true));
        Resource nameField = model.createResource(EX + "nameField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "name")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(IndexVocab.NS, "sortable"), model.createTypedLiteral(true))
            .addProperty(model.createProperty(IndexVocab.NS, "normalizer"),
                model.createResource().addProperty(RDF.type, TextVocab.lowerCaseKeywordAnalyzer));

        Resource bookShape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, labelField, RDFS.label))
            .addProperty(model.createProperty(SH, "property"),
                occurrence(model, nameField, model.createResource(EX + "name")));

        RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            FieldDef fd = index.getShaclMapping().findFieldByName("name");
            assertNotNull("name field must be parsed", fd);
            assertNotNull("idx:normalizer must be resolved to an analyzer", fd.getNormalizer());
        } finally {
            index.close();
        }
    }

    @Test
    public void testNormalizerOnNonKeywordFieldRejected() {
        Model model = createModel();

        // idx:normalizer on a TEXT field is a configuration error (fail fast).
        Resource labelField = model.createResource(EX + "labelField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "label")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
            .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true))
            .addProperty(model.createProperty(IndexVocab.NS, "normalizer"),
                model.createResource().addProperty(RDF.type, TextVocab.lowerCaseKeywordAnalyzer));

        Resource bookShape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, labelField, RDFS.label));

        RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        AssemblerException ex = assertThrows(AssemblerException.class,
            () -> Assembler.general().open(indexSpec));
        assertTrue(ex.getCause() instanceof TextIndexException);
        assertTrue(ex.getCause().getMessage().contains("only valid on KEYWORD"));
    }

    @Test
    public void testTemporalFieldVocabResolvesToTemporal() {
        // Issue #69: idx:TemporalField is the new canonical resource, idx:DateField and
        // idx:DateTimeField are deprecated aliases — all three must produce TEMPORAL.
        for (Resource fieldTypeRes : new Resource[]{
                IndexVocab.TemporalField, IndexVocab.DateField, IndexVocab.DateTimeField }) {
            Model model = createModel();

            Resource tempField = model.createResource(EX + "eventDateField_" + fieldTypeRes.getLocalName())
                .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "eventDate")
                .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), fieldTypeRes)
                .addProperty(model.createProperty(IndexVocab.NS, "storeLiteralMetadata"),
                    model.createTypedLiteral(true));

            Resource bookShape = model.createResource(EX + "BookShape_" + fieldTypeRes.getLocalName())
                .addProperty(model.createProperty(SH, "targetClass"),
                    model.createResource(EX + "Book_" + fieldTypeRes.getLocalName()))
                .addProperty(model.createProperty(SH, "property"),
                    occurrence(model, tempField, model.createResource(EX + "eventDate")));

            RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });
            Resource indexSpec = model.createResource(EX + "index_" + fieldTypeRes.getLocalName())
                .addProperty(RDF.type, TextVocab.textIndexShacl)
                .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
                .addProperty(TextVocab.pShapes, shapesList);

            ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
            try {
                FieldDef fd = index.getShaclMapping().findFieldByName("eventDate");
                assertNotNull("Field eventDate must be parsed", fd);
                assertEquals("All three vocab resources must produce FieldType.TEMPORAL ("
                    + fieldTypeRes.getLocalName() + ")",
                    org.apache.jena.query.text.ShaclIndexMapping.FieldType.TEMPORAL,
                    fd.getFieldType());
            } finally {
                index.close();
            }
        }
    }

    @Test
    public void testSequencePathParsed() {
        Model model = createModel();

        Resource titleField = model.createResource(EX + "titleField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "title")
            .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true));
        Resource authorNameField = model.createResource(EX + "authorNameField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "authorName")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField);

        Resource authorPath = model.createList(new RDFNode[]{
            model.createResource(EX + "author"),
            model.createResource(EX + "name")
        }).asResource();

        Resource bookShape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, titleField, RDFS.label))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, authorNameField, authorPath));

        RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            FieldOccurrence authorName = findRootOccurrence(index.getShaclMapping().getProfiles().get(0), "authorName");
            assertNotNull(authorName);
            assertTrue(authorName.getPath() instanceof P_Seq);
            assertEquals(2, authorName.getPredicates().size());
        } finally {
            index.close();
        }
    }

    @Test
    public void testNestedHierarchyParsed() {
        Model model = createModel();

        Resource titleField = model.createResource(EX + "titleField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "title")
            .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true));
        Resource identifierType = model.createResource("urn:jena:lucene:field#identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(IndexVocab.NS, "facetable"), model.createTypedLiteral(true));
        Resource identifierValueExact = model.createResource("urn:jena:lucene:field#identifierValueExact")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifierValueExact")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(IndexVocab.NS, "facetable"), model.createTypedLiteral(true));
        Resource identifierValueText = model.createResource("urn:jena:lucene:field#identifierValueText")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifierValueText")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
            .addProperty(model.createProperty(IndexVocab.NS, "analyzer"),
                model.createResource().addProperty(RDF.type, TextVocab.edgeNGramAnalyzer))
            .addProperty(model.createProperty(IndexVocab.NS, "queryAnalyzer"),
                model.createResource().addProperty(RDF.type, TextVocab.lowerCaseKeywordAnalyzer));

        Resource nested = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "joinPath"), model.createResource("https://schema.org/identifier"))
            .addProperty(model.createProperty(IndexVocab.NS, "property"),
                occurrence(model, identifierType, model.createResource("https://schema.org/propertyID")))
            .addProperty(model.createProperty(IndexVocab.NS, "property"),
                occurrence(model, identifierValueExact, model.createResource("https://schema.org/value")))
            .addProperty(model.createProperty(IndexVocab.NS, "property"),
                occurrence(model, identifierValueText, model.createResource("https://schema.org/value")))
            .addProperty(model.createProperty(IndexVocab.NS, "facetHierarchy"),
                model.createList(new RDFNode[] { identifierType, identifierValueExact }));

        Resource boreholeShape = model.createResource(EX + "BoreholeShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Borehole"))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, titleField, RDFS.label))
            .addProperty(model.createProperty(IndexVocab.NS, "nested"), nested);

        RDFNode shapesList = model.createList(new RDFNode[] { boreholeShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            IndexProfile profile = index.getShaclMapping().getProfiles().get(0);
            assertEquals(1, profile.getNestedDefs().size());
            NestedDef nestedDef = profile.getNestedDefs().get(0);
            assertTrue(nestedDef.getJoinPath() instanceof P_Link);
            assertEquals("<https://schema.org/identifier>", nestedDef.getNestedName());
            assertEquals(1, nestedDef.getJoinSteps().size());
            assertFalse(nestedDef.getJoinSteps().get(0).isInverse());
            assertTrue(nestedDef.getJoinPredicates().contains(model.createResource("https://schema.org/identifier").asNode()));
            assertEquals(4, profile.getFields().size());
            assertEquals(1, nestedDef.getHierarchies().size());
            assertEquals(3, nestedDef.getOccurrences().size());
            assertEquals("identifierType_identifierValueExact", nestedDef.getHierarchies().get(0).getDimensionName());
        } finally {
            index.close();
        }
    }

    @Test
    public void testNestedInverseJoinPathParsed() {
        Model model = createModel();

        Resource identifierType = model.createResource("urn:jena:lucene:field#identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField);

        Resource nested = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "joinPath"),
                model.createResource().addProperty(model.createProperty(SH, "inversePath"),
                    model.createResource("https://schema.org/about")))
            .addProperty(model.createProperty(IndexVocab.NS, "property"),
                occurrence(model, identifierType, model.createResource("https://schema.org/propertyID")));

        Resource boreholeShape = model.createResource(EX + "BoreholeShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Borehole"))
            .addProperty(model.createProperty(IndexVocab.NS, "nested"), nested);

        RDFNode shapesList = model.createList(new RDFNode[] { boreholeShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            NestedDef nestedDef = index.getShaclMapping().getProfiles().get(0).getNestedDefs().get(0);
            assertTrue(nestedDef.getJoinPath() instanceof P_Inverse);
            assertEquals(1, nestedDef.getJoinSteps().size());
            assertTrue(nestedDef.getJoinSteps().get(0).isInverse());
            assertEquals("https://schema.org/about", nestedDef.getJoinSteps().get(0).getPredicate().getURI());
        } finally {
            index.close();
        }
    }

    @Test
    public void testNestedSequenceJoinPathParsed() {
        Model model = createModel();

        Resource identifierType = model.createResource("urn:jena:lucene:field#identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField);

        Resource joinPath = model.createList(new RDFNode[] {
            model.createResource("https://example.org/hasIdentifierLink"),
            model.createResource("https://example.org/identifierNode")
        }).asResource();

        Resource nested = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "joinPath"), joinPath)
            .addProperty(model.createProperty(IndexVocab.NS, "property"),
                occurrence(model, identifierType, model.createResource("https://schema.org/propertyID")));

        Resource boreholeShape = model.createResource(EX + "BoreholeShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Borehole"))
            .addProperty(model.createProperty(IndexVocab.NS, "nested"), nested);

        RDFNode shapesList = model.createList(new RDFNode[] { boreholeShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            NestedDef nestedDef = index.getShaclMapping().getProfiles().get(0).getNestedDefs().get(0);
            assertTrue(nestedDef.getJoinPath() instanceof P_Seq);
            assertEquals(2, nestedDef.getJoinSteps().size());
            assertEquals("https://example.org/hasIdentifierLink", nestedDef.getJoinSteps().get(0).getPredicate().getURI());
            assertEquals("https://example.org/identifierNode", nestedDef.getJoinSteps().get(1).getPredicate().getURI());
        } finally {
            index.close();
        }
    }

    @Test
    public void testSharedCanonicalFieldCanAppearInMultipleRootOccurrences() {
        Model model = createModel();

        Resource parentField = model.createResource("urn:jena:lucene:field#hasParent")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "hasParent")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(IndexVocab.NS, "multiValued"), model.createTypedLiteral(true));

        Resource surveyShape = model.createResource(EX + "SurveyShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Survey"))
            .addProperty(model.createProperty(SH, "property"),
                occurrence(model, parentField,
                    model.createResource().addProperty(model.createProperty(SH, "inversePath"),
                        model.createResource("https://schema.org/about"))));

        Resource wellShape = model.createResource(EX + "WellShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Well"))
            .addProperty(model.createProperty(SH, "property"),
                occurrence(model, parentField, model.createResource("http://purl.org/dc/terms/hasPart")));

        RDFNode shapesList = model.createList(new RDFNode[] { surveyShape, wellShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            ShaclIndexMapping mapping = index.getShaclMapping();
            assertNotNull(mapping.findField(parentField.getURI()));
            assertEquals(2, mapping.getProfiles().size());
        } finally {
            index.close();
        }
    }

    @Test
    public void testOccurrenceConstraintsParsed() {
        Model model = createModel();

        Resource parentField = model.createResource("urn:jena:lucene:field#hasParent")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "hasParent")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField);
        Resource valueField = model.createResource("urn:jena:lucene:field#sampleValue")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "sampleValue")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField);

        Resource shape = model.createResource(EX + "SurveyShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Survey"))
            .addProperty(model.createProperty(SH, "property"),
                model.createResource()
                    .addProperty(model.createProperty(IndexVocab.NS, "field"), parentField)
                    .addProperty(model.createProperty(SH, "path"),
                        model.createResource().addProperty(model.createProperty(SH, "inversePath"),
                            model.createResource(EX + "about")))
                    .addProperty(model.createProperty(SH, "class"), model.createResource(EX + "Borehole"))
                    .addProperty(model.createProperty(SH, "nodeKind"), model.createResource(SH + "IRI")))
            .addProperty(model.createProperty(SH, "property"),
                model.createResource()
                    .addProperty(model.createProperty(IndexVocab.NS, "field"), valueField)
                    .addProperty(model.createProperty(SH, "path"), model.createResource(EX + "value"))
                    .addProperty(model.createProperty(SH, "nodeKind"), model.createResource(SH + "Literal"))
                    .addProperty(model.createProperty(SH, "datatype"),
                        model.createResource(XSDDatatype.XSDinteger.getURI())));

        RDFNode shapesList = model.createList(new RDFNode[] { shape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            IndexProfile profile = index.getShaclMapping().getProfiles().get(0);
            FieldOccurrence parentOccurrence = findRootOccurrence(profile, "hasParent");
            FieldOccurrence valueOccurrence = findRootOccurrence(profile, "sampleValue");

            assertEquals(model.createResource(EX + "Borehole").asNode(), parentOccurrence.getRequiredClass());
            assertEquals(ShaclIndexMapping.NodeKindConstraint.IRI, parentOccurrence.getNodeKindConstraint());
            assertEquals(ShaclIndexMapping.NodeKindConstraint.LITERAL, valueOccurrence.getNodeKindConstraint());
            assertEquals(XSDDatatype.XSDinteger.getURI(), valueOccurrence.getDatatype().getURI());
        } finally {
            index.close();
        }
    }

    @Test
    public void testLegacyFieldResourceSyntaxRejected() {
        Model model = createModel();

        Resource legacyField = model.createResource(EX + "legacyField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "title")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
            .addProperty(model.createProperty(SH, "path"), RDFS.label);

        Resource shape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(model.createProperty(SH, "property"), legacyField);

        RDFNode shapesList = model.createList(new RDFNode[] { shape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        AssemblerException ex = assertThrows(AssemblerException.class,
            () -> Assembler.general().open(indexSpec));
        assertTrue(ex.getMessage().contains("mixes occurrence data with canonical field metadata"));
    }

    @Test
    public void testRootHierarchyCannotReferenceNestedOnlyField() {
        Model model = createModel();

        Resource titleField = model.createResource(EX + "titleField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "title")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField);
        Resource identifierType = model.createResource(EX + "identifierTypeField")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField);

        Resource nested = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "joinPath"),
                model.createResource("https://schema.org/identifier"))
            .addProperty(model.createProperty(IndexVocab.NS, "property"),
                occurrence(model, identifierType, model.createResource("https://schema.org/propertyID")));

        Resource shape = model.createResource(EX + "BoreholeShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Borehole"))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, titleField, RDFS.label))
            .addProperty(model.createProperty(IndexVocab.NS, "nested"), nested)
            .addProperty(model.createProperty(IndexVocab.NS, "facetHierarchy"),
                model.createList(new RDFNode[] { titleField, identifierType }));

        RDFNode shapesList = model.createList(new RDFNode[] { shape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        AssemblerException ex = assertThrows(AssemblerException.class,
            () -> Assembler.general().open(indexSpec));
        assertTrue(ex.getCause() instanceof TextIndexException);
        assertTrue(ex.getCause().getMessage().contains("same scope"));
    }

    @Test
    public void testConflictingCanonicalFieldAnalyzersRejected() {
        Model model = createModel();

        Resource identifierFieldA = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifier")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
            .addProperty(model.createProperty(IndexVocab.NS, "analyzer"),
                model.createResource().addProperty(RDF.type, TextVocab.edgeNGramAnalyzer));
        Resource identifierFieldB = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifier")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
            .addProperty(model.createProperty(IndexVocab.NS, "analyzer"),
                model.createResource().addProperty(RDF.type, TextVocab.lowerCaseKeywordAnalyzer));

        Resource shape = model.createResource(EX + "SpecimenShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Specimen"))
            .addProperty(model.createProperty(SH, "property"),
                occurrence(model, identifierFieldA, model.createResource(EX + "identifierA")))
            .addProperty(model.createProperty(SH, "property"),
                occurrence(model, identifierFieldB, model.createResource(EX + "identifierB")));

        RDFNode shapesList = model.createList(new RDFNode[] { shape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        AssemblerException ex = assertThrows(AssemblerException.class,
            () -> Assembler.general().open(indexSpec));
        assertTrue(ex.getCause() instanceof TextIndexException);
        assertTrue(ex.getCause().getMessage().contains("defined inconsistently"));
    }

    private static FieldOccurrence findRootOccurrence(IndexProfile profile, String fieldName) {
        return profile.getRootOccurrences().stream()
            .filter(o -> fieldName.equals(o.getField().getFieldName()))
            .findFirst()
            .orElse(null);
    }

    // ------------------------------------------------------------------
    // Named facet hierarchies (#171)
    // ------------------------------------------------------------------

    @Test
    public void testNamedFacetHierarchyIsAddressedByItsIRI() {
        // A hierarchy declared as a resource with idx:levels is addressed by its IRI, and
        // that IRI is the taxonomy dimension. One identifier, not two. Naming a hierarchy
        // therefore changes its on-disk key and needs a reindex.
        Model model = createModel();

        Resource titleField = model.createResource("urn:jena:lucene:field#title")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "title")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField);
        Resource identifierType = model.createResource("urn:jena:lucene:field#identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(IndexVocab.NS, "facetable"), model.createTypedLiteral(true));
        Resource identifierValueExact = model.createResource("urn:jena:lucene:field#identifierValueExact")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifierValueExact")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(IndexVocab.NS, "facetable"), model.createTypedLiteral(true));

        String dimIRI = "http://example.org/dim/identifierPath";
        Resource hierarchy = model.createResource(dimIRI)
            .addProperty(model.createProperty(IndexVocab.NS, "levels"),
                model.createList(new RDFNode[] { identifierType, identifierValueExact }));

        Resource nested = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "joinPath"), model.createResource("https://schema.org/identifier"))
            .addProperty(model.createProperty(IndexVocab.NS, "property"),
                occurrence(model, identifierType, model.createResource("https://schema.org/propertyID")))
            .addProperty(model.createProperty(IndexVocab.NS, "property"),
                occurrence(model, identifierValueExact, model.createResource("https://schema.org/value")))
            .addProperty(model.createProperty(IndexVocab.NS, "facetHierarchy"), hierarchy);

        Resource shape = model.createResource(EX + "BoreholeShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Borehole"))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, titleField, RDFS.label))
            .addProperty(model.createProperty(IndexVocab.NS, "nested"), nested);

        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, model.createList(new RDFNode[] { shape }));

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            NestedDef nestedDef = index.getShaclMapping().getProfiles().get(0).getNestedDefs().get(0);
            var hier = nestedDef.getHierarchies().get(0);

            assertNotNull("The hierarchy should carry its IRI", hier.getDimensionIRI());
            assertEquals(dimIRI, hier.getDimensionIRI().getURI());
            assertEquals("The IRI is the taxonomy dimension", dimIRI, hier.getDimensionName());

            assertEquals(java.util.List.of(dimIRI),
                index.resolveFacetFieldNames(java.util.List.of(dimIRI)));

            // The derived name is not an alias. A named hierarchy has one address, so the
            // old name no longer reaches the dimension. (On a branch carrying #173 this
            // raises; here it simply fails to resolve.)
            assertNotEquals("The derived name must not still address the dimension",
                java.util.List.of(dimIRI),
                index.resolveFacetFieldNames(java.util.List.of("identifierType_identifierValueExact")));
        } finally {
            index.close();
        }
    }

    @Test
    public void testBareListFacetHierarchyHasNoIRI() {
        // The existing form keeps working and carries no IRI.
        Model model = createModel();

        Resource titleField = model.createResource("urn:jena:lucene:field#title")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "title")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField);
        Resource a = model.createResource("urn:jena:lucene:field#levelA")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "levelA")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(IndexVocab.NS, "facetable"), model.createTypedLiteral(true));
        Resource b = model.createResource("urn:jena:lucene:field#levelB")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "levelB")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(IndexVocab.NS, "facetable"), model.createTypedLiteral(true));

        Resource shape = model.createResource(EX + "Shape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Thing"))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, titleField, RDFS.label))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, a, model.createResource(EX + "a")))
            .addProperty(model.createProperty(SH, "property"), occurrence(model, b, model.createResource(EX + "b")))
            .addProperty(model.createProperty(IndexVocab.NS, "facetHierarchy"),
                model.createList(new RDFNode[] { a, b }));

        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, model.createList(new RDFNode[] { shape }));

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            var hier = index.getShaclMapping().getProfiles().get(0).getHierarchies().get(0);
            assertNull("A bare RDF list carries no IRI", hier.getDimensionIRI());
            assertEquals("levelA_levelB", hier.getDimensionName());
        } finally {
            index.close();
        }
    }
}
