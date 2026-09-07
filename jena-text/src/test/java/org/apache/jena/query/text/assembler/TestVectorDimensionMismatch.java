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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.text.TextIndexException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.Test;

/**
 * The assembler refuses an {@code idx:dimension} that disagrees with what the configured
 * embedding provider actually produces.
 * <p>
 * This is a correctness boundary rather than a nicety. Lucene fixes a KNN field's dimension
 * at index time, so a mismatch cannot be recovered from at query time — and it is the kind
 * of edit (swap the model, forget the dimension) that looks harmless in a diff.
 * <p>
 * Previously the only thing asserting this lived in a test that read the demo's
 * {@code config.ttl} from the working directory, re-implemented the comparison itself, and
 * skipped silently when the directory was not found. It therefore never exercised
 * {@link ShaclTextIndexAssembler}'s check at all, and covered one particular config file
 * rather than the behaviour.
 */
public class TestVectorDimensionMismatch {

    private static final String SHAPE = """
        ex:ThingShape
            sh:targetClass ex:Thing ;
            sh:property [ idx:field field:title ; sh:path ex:title ] ;
            idx:vectorField field:embedding .

        field:title
            idx:fieldName "title" ;
            idx:fieldType idx:TextField ;
            idx:defaultSearch true .
        """;

    @Test
    public void testDeclaredDimensionMustMatchTheProvider() {
        // hashing produces 64-dimensional vectors; the field claims 128.
        String message = expectRejection(() -> openIndex(vectorField(128), embedding(64)));
        assertTrue("Error should name the field: " + message, message.contains("embedding"));
        assertTrue("Error should give both dimensions: " + message,
            message.contains("128") && message.contains("64"));
    }

    @Test
    public void testMatchingDimensionIsAccepted() {
        // The same shape with the dimensions agreeing must assemble, or the test above
        // would pass for the wrong reason.
        openIndex(vectorField(64), embedding(64));
    }

    @Test
    public void testVectorFieldWithoutAnEmbeddingBlockIsRejected() {
        String message = expectRejection(() -> openIndex(vectorField(64), ""));
        assertTrue("Error should mention idx:embedding: " + message,
            message.contains("idx:embedding"));
    }

    /**
     * Run something that must be refused, and return the message that explains why.
     * <p>
     * Jena's assembler wraps whatever a type assembler throws in an AssemblerException, so
     * the TextIndexException carrying the explanation is the cause rather than the thing
     * caught. Asserting on the wrapper's message would pass whatever the real reason was.
     */
    private static String expectRejection(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof TextIndexException) {
                    return cause.getMessage();
                }
            }
            throw new AssertionError("Rejected, but not with a TextIndexException", ex);
        }
        throw new AssertionError("Expected the configuration to be rejected");
    }

    private static String vectorField(int dimension) {
        return """
            field:embedding
                idx:fieldName "embedding" ;
                idx:fieldType idx:VectorField ;
                idx:dimension %d ;
                idx:embeddingSource ( field:title ) .
            """.formatted(dimension);
    }

    private static String embedding(int dimension) {
        return """
                idx:embedding [ idx:provider "hashing" ; idx:dimension %d ] ;
            """.formatted(dimension);
    }

    private static void openIndex(String vectorFieldBlock, String embeddingBlock) {
        String turtle = """
            @prefix idx:   <urn:jena:lucene:index#> .
            @prefix field: <urn:jena:lucene:field#> .
            @prefix sh:    <http://www.w3.org/ns/shacl#> .
            @prefix text:  <http://jena.apache.org/text#> .
            @prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix ex:    <http://example.org/> .
            """ + SHAPE + vectorFieldBlock + """
            ex:index rdf:type text:TextIndexShacl ;
                text:shapes ( ex:ThingShape ) ;
                text:directory "mem" ;
            """ + embeddingBlock + "    .\n";

        Model model = ModelFactory.createDefaultModel();
        model.read(new java.io.StringReader(turtle), null, "TTL");
        Resource indexSpec = model.getResource("http://example.org/index");
        Assembler.general().open(indexSpec);
    }
}
