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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.VectorSimilarity;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.embedding.EmbeddingConfig;
import org.apache.jena.query.text.embedding.EmbeddingProvider;
import org.apache.jena.query.text.embedding.EmbeddingProviders;
import org.apache.jena.query.text.embedding.HashingEmbeddingProvider;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RIOT;
import org.junit.Test;

/**
 * Assembler-level tests for {@code idx:VectorField} and the {@code idx:embedding} block.
 * <p>
 * Every rejection asserted here fails <em>silently</em> if it is not enforced — an
 * unfacetable field that produces no facets, a source field the shape never populates so
 * every entity embeds the same empty string. Those are the failures worth a test, because
 * nothing else in the system will ever report them.
 */
public class TestVectorAssembler {

    private static final String PREFIXES = """
        PREFIX sh:   <http://www.w3.org/ns/shacl#>
        PREFIX idx:  <urn:jena:lucene:index#>
        PREFIX field: <urn:jena:lucene:field#>
        PREFIX ex:   <http://example.org/>
        """;

    private static final String FIELDS = """
        field:title
            idx:fieldName "title" ;
            idx:fieldType idx:TextField ;
            idx:defaultSearch true .

        field:body
            idx:fieldName "body" ;
            idx:fieldType idx:TextField .
        """;

    private static final String SHAPE_OCCURRENCES = """
        ex:DocShape
            sh:targetClass ex:Document ;
            sh:property [ sh:path ex:title ; idx:field field:title ] ;
            sh:property [ sh:path ex:body  ; idx:field field:body  ] ;
        """;

    // ---- Happy path ----

    @Test
    public void testVectorFieldIsParsed() {
        ShaclIndexMapping mapping = parse(PREFIXES + FIELDS + """
            field:embedding
                idx:fieldName "embedding" ;
                idx:fieldType idx:VectorField ;
                idx:dimension 64 ;
                idx:similarity idx:Cosine ;
                idx:embeddingSource ( field:title field:body ) .
            """ + SHAPE_OCCURRENCES + """
                idx:vectorField field:embedding .
            """);

        List<FieldDef> vectorFields = mapping.getProfiles().get(0).getVectorFields();
        assertEquals(1, vectorFields.size());

        FieldDef embedding = vectorFields.get(0);
        assertEquals("embedding", embedding.getFieldName());
        assertTrue(embedding.isVector());
        assertNotNull(embedding.getVectorDef());
        assertEquals(64, embedding.getVectorDef().dimension());
        assertEquals(VectorSimilarity.COSINE, embedding.getVectorDef().similarity());
        assertEquals(List.of("urn:jena:lucene:field#title", "urn:jena:lucene:field#body"),
            embedding.getVectorDef().sourceFieldIRIs());
    }

    @Test
    public void testSimilarityDefaultsToCosine() {
        ShaclIndexMapping mapping = parse(PREFIXES + FIELDS + """
            field:embedding
                idx:fieldName "embedding" ;
                idx:fieldType idx:VectorField ;
                idx:dimension 64 ;
                idx:embeddingSource ( field:title ) .
            """ + SHAPE_OCCURRENCES + """
                idx:vectorField field:embedding .
            """);

        assertEquals(VectorSimilarity.COSINE,
            mapping.getProfiles().get(0).getVectorFields().get(0).getVectorDef().similarity());
    }

    // ---- Rejections ----

    @Test
    public void testMissingDimensionIsRejected() {
        assertRejected("""
            field:embedding
                idx:fieldName "embedding" ;
                idx:fieldType idx:VectorField ;
                idx:embeddingSource ( field:title ) .
            """, "idx:dimension");
    }

    @Test
    public void testMissingEmbeddingSourceIsRejected() {
        assertRejected("""
            field:embedding
                idx:fieldName "embedding" ;
                idx:fieldType idx:VectorField ;
                idx:dimension 64 .
            """, "idx:embeddingSource");
    }

    @Test
    public void testUnreachableEmbeddingSourceIsRejected() {
        // field:missing is declared but the shape has no occurrence for it, so every
        // entity would embed the same text with that field's contribution always absent.
        assertRejected("""
            field:missing
                idx:fieldName "missing" ;
                idx:fieldType idx:TextField .

            field:embedding
                idx:fieldName "embedding" ;
                idx:fieldType idx:VectorField ;
                idx:dimension 64 ;
                idx:embeddingSource ( field:title field:missing ) .
            """, "does not");
    }

    @Test
    public void testFacetableVectorFieldIsRejected() {
        assertRejected("""
            field:embedding
                idx:fieldName "embedding" ;
                idx:fieldType idx:VectorField ;
                idx:dimension 64 ;
                idx:facetable true ;
                idx:embeddingSource ( field:title ) .
            """, "facetable");
    }

    @Test
    public void testSortableVectorFieldIsRejected() {
        assertRejected("""
            field:embedding
                idx:fieldName "embedding" ;
                idx:fieldType idx:VectorField ;
                idx:dimension 64 ;
                idx:sortable true ;
                idx:embeddingSource ( field:title ) .
            """, "sortable");
    }

    @Test
    public void testDefaultSearchVectorFieldIsRejected() {
        assertRejected("""
            field:embedding
                idx:fieldName "embedding" ;
                idx:fieldType idx:VectorField ;
                idx:dimension 64 ;
                idx:defaultSearch true ;
                idx:embeddingSource ( field:title ) .
            """, "defaultSearch");
    }

    @Test
    public void testVectorTermsOnNonVectorFieldAreRejected() {
        assertRejected("""
            field:embedding
                idx:fieldName "embedding" ;
                idx:fieldType idx:TextField ;
                idx:dimension 64 ;
                idx:embeddingSource ( field:title ) .
            """, "only valid on");
    }

    // ---- idx:embedding block ----

    @Test
    public void testEmbeddingConfigIsParsed() {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString(PREFIXES + """
            ex:index
                idx:embedding [
                    idx:provider "hashing" ;
                    idx:model "test-model" ;
                    idx:modelPath "/models" ;
                    idx:dimension 128 ;
                    idx:option [ idx:optionName "queryPrefix" ; idx:optionValue "query: " ]
                ] .
            """, org.apache.jena.riot.Lang.TURTLE).parse(model);

        EmbeddingConfig config = ShaclIndexAssembler.parseEmbeddingConfig(
            model.getResource("http://example.org/index"));

        assertNotNull(config);
        assertEquals("hashing", config.provider());
        assertEquals("test-model", config.model());
        assertEquals("/models", config.modelPath());
        assertEquals(128, config.dimension());
        assertEquals("query: ", config.option("queryPrefix", null));
    }

    @Test
    public void testHashingProviderIsDiscoverableByName() {
        // The ServiceLoader wiring is easy to break by moving a file; nothing else fails
        // loudly when META-INF/services goes missing.
        assertTrue("hashing provider not discoverable: " + EmbeddingProviders.availableNames(),
            EmbeddingProviders.availableNames().contains("hashing"));

        EmbeddingProvider provider = EmbeddingProviders.create(
            new EmbeddingConfig("hashing", null, null, 64));
        assertEquals(64, provider.dimension());
        assertEquals(HashingEmbeddingProvider.MODEL_ID, provider.modelId());
    }

    @Test
    public void testUnknownProviderNamesAvailableOnes() {
        try {
            EmbeddingProviders.create(new EmbeddingConfig("nonesuch", null, null, 64));
            fail("Expected an unknown provider name to be rejected");
        } catch (TextIndexException ex) {
            assertTrue("Error should list available providers: " + ex.getMessage(),
                ex.getMessage().contains("hashing"));
        }
    }

    // ---- Helpers ----

    private static ShaclIndexMapping parse(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString(turtle, org.apache.jena.riot.Lang.TURTLE).parse(model);
        Resource shapesList = model.createList(model.getResource("http://example.org/DocShape"));
        return ShaclIndexAssembler.parseShapes(Assembler.general(), shapesList);
    }

    private static void assertRejected(String fieldBlock, String expectedFragment) {
        try {
            parse(PREFIXES + FIELDS + fieldBlock + SHAPE_OCCURRENCES + """
                idx:vectorField field:embedding .
                """);
            fail("Expected configuration to be rejected, mentioning: " + expectedFragment);
        } catch (TextIndexException ex) {
            assertTrue("Error message should mention '" + expectedFragment + "', got: " + ex.getMessage(),
                ex.getMessage().contains(expectedFragment));
        }
    }

    static {
        RIOT.init();
        RDFDataMgr.class.getName();
    }
}
