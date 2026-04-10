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
import org.apache.jena.query.text.TextIndexException;
import org.apache.jena.query.text.ShaclTextIndexLucene;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.path.*;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
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

    /**
     * Build a valid text:shapes index spec in the model.
     */
    private Resource buildShaclIndexSpec(Model model) {
        // Define the shape
        Resource bookShape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(
                model.createProperty(SH, "property"),
                model.createResource()
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "label")
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
                    .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true))
                    .addProperty(model.createProperty(SH, "path"), RDFS.label)
            );

        // Build the shapes list
        RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });

        // Build the index spec
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
            assertTrue("Should be in SHACL mode", index.isShaclMode());
            ShaclIndexMapping mapping = index.getShaclMapping();
            assertNotNull(mapping);
            assertEquals(1, mapping.getProfiles().size());

            ShaclIndexMapping.IndexProfile profile = mapping.getProfiles().get(0);
            assertEquals(1, profile.getFields().size());
            assertEquals("label", profile.getFields().get(0).getFieldName());
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

        // Shape with inverse path: sh:path [ sh:inversePath ex:wrote ]
        Resource bookShape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(
                model.createProperty(SH, "property"),
                model.createResource()
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "title")
                    .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true))
                    .addProperty(model.createProperty(SH, "path"), RDFS.label)
            )
            .addProperty(
                model.createProperty(SH, "property"),
                model.createResource()
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "wroteBy")
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
                    .addProperty(model.createProperty(SH, "path"),
                        model.createResource()
                            .addProperty(model.createProperty(SH, "inversePath"),
                                model.createResource(EX + "wrote")))
            );

        RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            ShaclIndexMapping mapping = index.getShaclMapping();
            FieldDef wroteByField = null;
            for (FieldDef f : mapping.getProfiles().get(0).getFields()) {
                if ("wroteBy".equals(f.getFieldName())) {
                    wroteByField = f;
                }
            }
            assertNotNull("Should have wroteBy field", wroteByField);
            assertTrue("wroteBy should have complex path", wroteByField.hasComplexPath());
            assertTrue("wroteBy path should be P_Inverse", wroteByField.getPath() instanceof P_Inverse);
        } finally {
            index.close();
        }
    }

    @Test
    public void testQueryAnalyzerAssembled() {
        Model model = createModel();

        // Shape with a field that has idx:analyzer (edge n-gram) and idx:queryAnalyzer (lowercase keyword)
        Resource bookShape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(
                model.createProperty(SH, "property"),
                model.createResource()
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "title")
                    .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true))
                    .addProperty(model.createProperty(SH, "path"), RDFS.label)
            )
            .addProperty(
                model.createProperty(SH, "property"),
                model.createResource()
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifier")
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
                    .addProperty(model.createProperty(IndexVocab.NS, "analyzer"),
                        model.createResource().addProperty(RDF.type, TextVocab.edgeNGramAnalyzer))
                    .addProperty(model.createProperty(IndexVocab.NS, "queryAnalyzer"),
                        model.createResource().addProperty(RDF.type, TextVocab.lowerCaseKeywordAnalyzer))
                    .addProperty(model.createProperty(SH, "path"), model.createResource(EX + "identifier"))
            );

        RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            ShaclIndexMapping mapping = index.getShaclMapping();
            FieldDef idField = null;
            for (FieldDef f : mapping.getProfiles().get(0).getFields()) {
                if ("identifier".equals(f.getFieldName())) {
                    idField = f;
                }
            }
            assertNotNull("Should have identifier field", idField);
            assertNotNull("identifier should have index analyzer", idField.getAnalyzer());
            assertNotNull("identifier should have query analyzer", idField.getQueryAnalyzer());
            assertNotSame("index and query analyzers should be different instances",
                idField.getAnalyzer(), idField.getQueryAnalyzer());
        } finally {
            index.close();
        }
    }

    @Test
    public void testDateFieldRequiresLiteralMetadata() {
        Model model = createModel();

        Resource bookShape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(
                model.createProperty(SH, "property"),
                model.createResource()
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "eventDate")
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.DateField)
                    .addProperty(model.createProperty(SH, "path"), model.createResource(EX + "eventDate"))
            );

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
    public void testSequencePathParsed() {
        Model model = createModel();

        // Shape with sequence path: sh:path ( ex:author ex:name )
        Resource authorPath = model.createList(new RDFNode[]{
            model.createResource(EX + "author"),
            model.createResource(EX + "name")
        }).asResource();

        Resource bookShape = model.createResource(EX + "BookShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
            .addProperty(
                model.createProperty(SH, "property"),
                model.createResource()
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "title")
                    .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true))
                    .addProperty(model.createProperty(SH, "path"), RDFS.label)
            )
            .addProperty(
                model.createProperty(SH, "property"),
                model.createResource()
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "authorName")
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
                    .addProperty(model.createProperty(SH, "path"), authorPath)
            );

        RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            ShaclIndexMapping mapping = index.getShaclMapping();
            FieldDef authorNameField = null;
            for (FieldDef f : mapping.getProfiles().get(0).getFields()) {
                if ("authorName".equals(f.getFieldName())) {
                    authorNameField = f;
                }
            }
            assertNotNull("Should have authorName field", authorNameField);
            assertTrue("authorName should have complex path", authorNameField.hasComplexPath());
            assertTrue("authorName path should be P_Seq", authorNameField.getPath() instanceof P_Seq);

            // Verify leaf predicates extracted for change listener
            assertEquals("Should have 2 leaf predicates", 2, authorNameField.getPredicates().size());
        } finally {
            index.close();
        }
    }

    @Test
    public void testNestedHierarchyParsed() {
        Model model = createModel();

        Resource identifierType = model.createResource("urn:jena:lucene:field#identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(IndexVocab.NS, "facetable"), model.createTypedLiteral(true))
            .addProperty(model.createProperty(SH, "path"), model.createResource("https://schema.org/propertyID"));

        Resource identifierValueExact = model.createResource("urn:jena:lucene:field#identifierValueExact")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifierValueExact")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(IndexVocab.NS, "facetable"), model.createTypedLiteral(true))
            .addProperty(model.createProperty(SH, "path"), model.createResource("https://schema.org/value"));

        Resource identifierValueText = model.createResource("urn:jena:lucene:field#identifierValueText")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifierValueText")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
            .addProperty(model.createProperty(IndexVocab.NS, "analyzer"),
                model.createResource().addProperty(RDF.type, TextVocab.edgeNGramAnalyzer))
            .addProperty(model.createProperty(IndexVocab.NS, "queryAnalyzer"),
                model.createResource().addProperty(RDF.type, TextVocab.lowerCaseKeywordAnalyzer))
            .addProperty(model.createProperty(SH, "path"), model.createResource("https://schema.org/value"));

        Resource nested = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "joinPath"), model.createResource("https://schema.org/identifier"))
            .addProperty(model.createProperty(IndexVocab.NS, "property"), identifierType)
            .addProperty(model.createProperty(IndexVocab.NS, "property"), identifierValueExact)
            .addProperty(model.createProperty(IndexVocab.NS, "property"), identifierValueText)
            .addProperty(model.createProperty(IndexVocab.NS, "facetHierarchy"),
                model.createList(new RDFNode[] { identifierType, identifierValueExact }));

        Resource boreholeShape = model.createResource(EX + "BoreholeShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Borehole"))
            .addProperty(model.createProperty(SH, "property"),
                model.createResource()
                    .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "title")
                    .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true))
                    .addProperty(model.createProperty(SH, "path"), RDFS.label))
            .addProperty(model.createProperty(IndexVocab.NS, "nested"), nested);

        RDFNode shapesList = model.createList(new RDFNode[] { boreholeShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
        try {
            ShaclIndexMapping.IndexProfile profile = index.getShaclMapping().getProfiles().get(0);
            assertEquals("Should parse nested definition", 1, profile.getNestedDefs().size());
            ShaclIndexMapping.NestedDef nestedDef = profile.getNestedDefs().get(0);
            assertTrue("joinPath should be a simple predicate", nestedDef.getJoinPath() instanceof P_Link);
            assertEquals("Nested scope name should be derived from joinPath",
                "<https://schema.org/identifier>", nestedDef.getNestedName());
            assertEquals("Simple join path should produce one join step", 1, nestedDef.getJoinSteps().size());
            assertFalse("Simple join step should be forward", nestedDef.getJoinSteps().get(0).isInverse());
            assertTrue("join predicates should contain the join predicate",
                nestedDef.getJoinPredicates().contains(model.createResource("https://schema.org/identifier").asNode()));
            assertEquals("Nested fields should be available on the profile", 4, profile.getFields().size());
            assertEquals("Nested block should define one hierarchy", 1, nestedDef.getHierarchies().size());
            ShaclIndexMapping.HierarchyDef hierarchy = nestedDef.getHierarchies().get(0);
            assertEquals("identifierType_identifierValueExact", hierarchy.getDimensionName());
            assertEquals("First hierarchy level should be identifierType",
                "urn:jena:lucene:field#identifierType", hierarchy.getLevel(0).getFieldIRI().getURI());
            assertEquals("Second hierarchy level should be identifierValueExact",
                "urn:jena:lucene:field#identifierValueExact", hierarchy.getLevel(1).getFieldIRI().getURI());
            assertTrue("Nested fields should carry nested scope metadata",
                hierarchy.getLevel(0).isNestedScoped());
        } finally {
            index.close();
        }
    }

    @Test
    public void testNestedInverseJoinPathParsed() {
        Model model = createModel();

        Resource identifierType = model.createResource("urn:jena:lucene:field#identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(SH, "path"), model.createResource("https://schema.org/propertyID"));

        Resource nested = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "joinPath"),
                model.createResource().addProperty(model.createProperty(SH, "inversePath"),
                    model.createResource("https://schema.org/about")))
            .addProperty(model.createProperty(IndexVocab.NS, "property"), identifierType);

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
            ShaclIndexMapping.NestedDef nestedDef = index.getShaclMapping().getProfiles().get(0).getNestedDefs().get(0);
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
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(SH, "path"), model.createResource("https://schema.org/propertyID"));

        Resource joinPath = model.createList(new RDFNode[] {
            model.createResource("https://example.org/hasIdentifierLink"),
            model.createResource("https://example.org/identifierNode")
        }).asResource();

        Resource nested = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "joinPath"), joinPath)
            .addProperty(model.createProperty(IndexVocab.NS, "property"), identifierType);

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
            ShaclIndexMapping.NestedDef nestedDef = index.getShaclMapping().getProfiles().get(0).getNestedDefs().get(0);
            assertTrue(nestedDef.getJoinPath() instanceof P_Seq);
            assertEquals(2, nestedDef.getJoinSteps().size());
            assertEquals("https://example.org/hasIdentifierLink", nestedDef.getJoinSteps().get(0).getPredicate().getURI());
            assertEquals("https://example.org/identifierNode", nestedDef.getJoinSteps().get(1).getPredicate().getURI());
            assertFalse(nestedDef.getJoinSteps().get(0).isInverse());
            assertFalse(nestedDef.getJoinSteps().get(1).isInverse());
        } finally {
            index.close();
        }
    }

    @Test
    public void testFieldCannotBeUsedInBothRootAndNestedScope() {
        Model model = createModel();

        Resource identifierType = model.createResource("urn:jena:lucene:field#identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "identifierType")
            .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.KeywordField)
            .addProperty(model.createProperty(SH, "path"), model.createResource("https://schema.org/propertyID"));

        Resource nested = model.createResource()
            .addProperty(model.createProperty(IndexVocab.NS, "joinPath"), model.createResource("https://schema.org/identifier"))
            .addProperty(model.createProperty(IndexVocab.NS, "property"), identifierType);

        Resource boreholeShape = model.createResource(EX + "BoreholeShape")
            .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Borehole"))
            .addProperty(model.createProperty(SH, "property"), identifierType)
            .addProperty(model.createProperty(IndexVocab.NS, "nested"), nested);

        RDFNode shapesList = model.createList(new RDFNode[] { boreholeShape });
        Resource indexSpec = model.createResource(EX + "index")
            .addProperty(RDF.type, TextVocab.textIndexShacl)
            .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
            .addProperty(TextVocab.pShapes, shapesList);

        try {
            Assembler.general().open(indexSpec);
            fail("Expected root/nested field reuse to be rejected");
        } catch (AssemblerException ex) {
            assertTrue(ex.getMessage().contains("cannot be used both as a root field and as an idx:nested property"));
        }
    }

}
