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

import static org.apache.jena.query.text.ShaclTurtleFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Configuration errors in {@code sh:node} steps, their filters and occurrence value
 * constraints: each is reported when the index is assembled, by a
 * {@link TextIndexException} that names what is wrong.
 */
public class TestShaclConditionRejections {

    private static void assertRejected(String shapes, String expectedInMessage) {
        Throwable ex = assertThrows(Throwable.class, () -> openIndex(shapes).close());
        String chain = messageChain(ex);
        assertTrue(hasTextIndexException(ex), "a configuration error, not " + ex);
        assertTrue(chain.contains(expectedInMessage),
            "the error names " + expectedInMessage + ": " + chain);
    }

    private static boolean hasTextIndexException(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof TextIndexException) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testClosedShapeInFilterIsRejected() {
        assertRejected("""
            field:v idx:fieldName "v" ; idx:fieldType idx:KeywordField .
            ex:Shape sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:part ;
                    sh:node [
                        sh:closed true ;
                        sh:property [ sh:path ex:v ; idx:field field:v ] ;
                    ] ;
                ] .
            """, "sh:closed");
    }

    @Test
    public void testClosedOnOccurrenceIsRejected() {
        assertRejected("""
            field:v idx:fieldName "v" ; idx:fieldType idx:KeywordField .
            ex:Shape sh:targetClass ex:Thing ;
                sh:property [ sh:path ex:v ; idx:field field:v ; sh:closed true ] .
            """, "sh:closed");
    }

    @Test
    public void testIndexTermInsideConditionIsRejected() {
        assertRejected("""
            field:v idx:fieldName "v" ; idx:fieldType idx:KeywordField .
            field:w idx:fieldName "w" ; idx:fieldType idx:KeywordField .
            ex:Shape sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:part ;
                    sh:node [
                        sh:or ( [ sh:property [ sh:path ex:w ; idx:field field:w ] ] [ sh:class ex:Part ] ) ;
                        sh:property [ sh:path ex:v ; idx:field field:v ] ;
                    ] ;
                ] .
            """, "from the idx: namespace");
    }

    @Test
    public void testSparqlComponentParameterIsRejected() {
        assertRejected("""
            field:v idx:fieldName "v" ; idx:fieldType idx:KeywordField .
            ex:MaxWidgetsComponent a sh:ConstraintComponent ;
                sh:parameter [ sh:path ex:maxWidgets ] ;
                sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER(true) }" ] .
            ex:Shape sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:part ;
                    sh:node [
                        ex:maxWidgets 3 ;
                        sh:property [ sh:path ex:v ; idx:field field:v ] ;
                    ] ;
                ] .
            """, EX + "maxWidgets");
    }

    @Test
    public void testConstraintOnStepIsRejected() {
        assertRejected("""
            field:v idx:fieldName "v" ; idx:fieldType idx:KeywordField .
            ex:Shape sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:part ;
                    sh:minCount 1 ;
                    sh:node [ sh:property [ sh:path ex:v ; idx:field field:v ] ] ;
                ] .
            """, "sh:minCount");
    }

    @Test
    public void testSelfOnStepIsRejected() {
        assertRejected("""
            field:v idx:fieldName "v" ; idx:fieldType idx:KeywordField .
            ex:Shape sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:part ;
                    idx:self true ;
                    sh:node [ sh:property [ sh:path ex:v ; idx:field field:v ] ] ;
                ] .
            """, "idx:self");
    }
}
