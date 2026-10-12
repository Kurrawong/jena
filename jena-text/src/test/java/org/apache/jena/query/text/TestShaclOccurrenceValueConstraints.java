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

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The constraints on a field occurrence filter the values it reaches, one value at a time,
 * evaluated by jena-shacl as a node shape applied to each value node. Constraints that
 * describe the set of values rather than one value are rejected at configuration time.
 * <p>
 * Design: {@code docs/2026-10-12_shacl_node_occurrence_filters_design.md}.
 */
public class TestShaclOccurrenceValueConstraints {

    private static final String SHAPE = """
        field:v idx:fieldName "v" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
        ex:Shape sh:targetClass ex:Thing ;
            sh:property [ sh:path ex:v ; idx:field field:v ; %s ] .
        """;

    private static ShaclTextIndexLucene openWithConstraint(String constraint) {
        return openIndex(String.format(SHAPE, constraint));
    }

    /** constraint, data for ex:t's ex:v values (plus any supporting triples), expected values. */
    static Stream<Arguments> acceptedConstraints() {
        return Stream.of(
            Arguments.of("sh:class ex:Person",
                "ex:t ex:v ex:alice , ex:bot . ex:alice a ex:Person . ex:bot a ex:Robot .",
                List.of(EX + "alice")),
            Arguments.of("sh:datatype xsd:integer",
                "ex:t ex:v 1 , \"x\" , \"abc\"^^xsd:integer .",
                List.of("1")),
            Arguments.of("sh:nodeKind sh:IRI",
                "ex:t ex:v ex:a , \"lit\" .",
                List.of(EX + "a")),
            Arguments.of("sh:in ( \"a\" \"b\" )",
                "ex:t ex:v \"a\" , \"b\" , \"c\" .",
                List.of("a", "b")),
            Arguments.of("sh:pattern \"^AB\"",
                "ex:t ex:v \"ABC\" , \"XAB\" , \"abc\" .",
                List.of("ABC")),
            Arguments.of("sh:pattern \"^ab\" ; sh:flags \"i\"",
                "ex:t ex:v \"ABC\" , \"XAB\" .",
                List.of("ABC")),
            Arguments.of("sh:minLength 3",
                "ex:t ex:v \"ab\" , \"abc\" .",
                List.of("abc")),
            Arguments.of("sh:maxLength 2",
                "ex:t ex:v \"ab\" , \"abc\" .",
                List.of("ab")),
            Arguments.of("sh:languageIn ( \"en\" )",
                "ex:t ex:v \"x\"@en , \"y\"@fr .",
                List.of("x")),
            Arguments.of("sh:minInclusive 5",
                "ex:t ex:v 4 , 5 , 6 .",
                List.of("5", "6")),
            Arguments.of("sh:maxInclusive 5",
                "ex:t ex:v 4 , 5 , 6 .",
                List.of("4", "5")),
            Arguments.of("sh:minExclusive 5",
                "ex:t ex:v 4 , 5 , 6 .",
                List.of("6")),
            Arguments.of("sh:maxExclusive 5",
                "ex:t ex:v 4 , 5 , 6 .",
                List.of("4")),
            Arguments.of("sh:node [ sh:property [ sh:path ex:status ; sh:hasValue ex:Active ] ]",
                "ex:t ex:v ex:a , ex:b . ex:a ex:status ex:Active . ex:b ex:status ex:Retired .",
                List.of(EX + "a")),
            Arguments.of("sh:and ( [ sh:minLength 2 ] [ sh:maxLength 3 ] )",
                "ex:t ex:v \"a\" , \"ab\" , \"abcd\" .",
                List.of("ab")),
            Arguments.of("sh:or ( [ sh:datatype xsd:integer ] [ sh:datatype xsd:decimal ] )",
                "ex:t ex:v 1 , 1.5 , \"x\" .",
                List.of("1", "1.5")),
            Arguments.of("sh:not [ sh:in ( \"b\" ) ]",
                "ex:t ex:v \"a\" , \"b\" .",
                List.of("a")),
            Arguments.of("sh:xone ( [ sh:minLength 2 ] [ sh:pattern \"^a\" ] )",
                "ex:t ex:v \"a\" , \"ab\" , \"bc\" , \"b\" .",
                List.of("a", "bc"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedConstraints")
    public void testAcceptedConstraintFiltersValues(String constraint, String data, List<String> expected) {
        ShaclTextIndexLucene index = openWithConstraint(constraint);
        try {
            Entity entity = build(index, parse("ex:t a ex:Thing . " + data).getGraph(), uri("t"));
            assertEquals(expected, values(entity, "v"));
        } finally {
            index.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "sh:hasValue \"a\"",
        "sh:minCount 1",
        "sh:maxCount 1",
        "sh:uniqueLang true",
        "sh:qualifiedValueShape [ sh:datatype xsd:string ] ; sh:qualifiedMinCount 1",
        "sh:equals ex:w",
        "sh:disjoint ex:w",
        "sh:lessThan ex:w",
        "sh:lessThanOrEquals ex:w",
    })
    public void testSetConstraintOnOccurrenceIsRejected(String constraint) {
        Exception ex = assertThrows(Exception.class, () -> openWithConstraint(constraint));
        String name = constraint.substring(0, constraint.indexOf(' '));
        assertTrue(messageChain(ex).contains(name),
            "the error names " + name + ": " + messageChain(ex));
    }

    @Test
    public void testSparqlConstraintOnOccurrenceIsRejected() {
        Exception ex = assertThrows(Exception.class, () -> openWithConstraint(
            "sh:sparql [ sh:select \"SELECT $this WHERE { FILTER(false) }\" ]"));
        assertTrue(messageChain(ex).contains("sh:sparql"), messageChain(ex));
    }

    @Test
    public void testSparqlConstraintInFilterIsRejected() {
        Exception ex = assertThrows(Exception.class, () -> openIndex("""
            field:v idx:fieldName "v" ; idx:fieldType idx:KeywordField .
            ex:Shape sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:part ;
                    sh:node [
                        sh:sparql [ sh:select "SELECT $this WHERE { FILTER(false) }" ] ;
                        sh:property [ sh:path ex:v ; idx:field field:v ] ;
                    ] ;
                ] .
            """));
        assertTrue(messageChain(ex).contains("sh:sparql"), messageChain(ex));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "sh:zeroOrMorePath", "sh:oneOrMorePath", "sh:zeroOrOnePath" })
    public void testRepeatingPathIsRejectedOnOccurrenceAndInFilter(String form) {
        Exception onOccurrence = assertThrows(Exception.class, () -> openIndex("""
            field:v idx:fieldName "v" ; idx:fieldType idx:KeywordField .
            ex:Shape sh:targetClass ex:Thing ;
                sh:property [ sh:path [ %s ex:part ] ; idx:field field:v ] .
            """.formatted(form)));
        assertTrue(messageChain(onOccurrence).contains("Unsupported SHACL path"), messageChain(onOccurrence));

        Exception inFilter = assertThrows(Exception.class, () -> openIndex("""
            field:v idx:fieldName "v" ; idx:fieldType idx:KeywordField .
            ex:Shape sh:targetClass ex:Thing ;
                sh:property [
                    sh:path ex:part ;
                    sh:node [
                        sh:property [ sh:path [ %s ex:part ] ; sh:minCount 1 ] ;
                        sh:property [ sh:path ex:v ; idx:field field:v ] ;
                    ] ;
                ] .
            """.formatted(form)));
        assertTrue(messageChain(inFilter).contains("Unsupported SHACL path"), messageChain(inFilter));
    }

    @Test
    public void testClassAdmitsSubclassInstance() {
        ShaclTextIndexLucene index = openWithConstraint("sh:class ex:Person");
        try {
            Entity entity = build(index, parse("""
                ex:t a ex:Thing ; ex:v ex:student , ex:bot , ex:person .
                ex:Student rdfs:subClassOf ex:Person .
                ex:student a ex:Student .
                ex:bot a ex:Robot .
                ex:person a ex:Person .
                """).getGraph(), uri("t"));
            assertEquals(List.of(EX + "person", EX + "student"), values(entity, "v"),
                "an instance of a subclass is a SHACL instance of the class; an unrelated type is not");
        } finally {
            index.close();
        }
    }

    @Test
    public void testFingerprintChangesWhenValueConstraintChanges() {
        String first = fingerprint("sh:pattern \"^A\"");
        assertEquals(first, fingerprint("sh:pattern \"^A\""),
            "the same configuration parsed twice has the same fingerprint");
        assertNotEquals(first, fingerprint("sh:pattern \"^B\""),
            "a changed value constraint changes the fingerprint");
    }

    private static String fingerprint(String constraint) {
        ShaclTextIndexLucene index = openWithConstraint(constraint);
        try {
            ShaclIndexMapping mapping = index.getShaclMapping();
            return ShaclConfigFingerprint.fingerprint(mapping, true, mapping.getFacetFieldNames());
        } finally {
            index.close();
        }
    }

    /**
     * A check reads only the triples it needs. jena-shacl copies the data graph's
     * prefixes into each new validation context, which on TDB2 reads the dataset's prefix
     * table once per checked value; the index never formats a report, so it must not.
     */
    @Test
    public void testCheckingValuesDoesNotReadDataPrefixes() {
        ShaclTextIndexLucene index = openWithConstraint("sh:datatype xsd:integer");
        try {
            org.apache.jena.graph.Graph data = new org.apache.jena.sparql.graph.GraphWrapper(
                    parse("ex:t a ex:Thing ; ex:v 1 , \"x\" .").getGraph()) {
                @Override
                public org.apache.jena.shared.PrefixMapping getPrefixMapping() {
                    throw new AssertionError("the data graph's prefixes were read");
                }
            };
            assertEquals(List.of("1"), values(build(index, data, uri("t")), "v"));
        } finally {
            index.close();
        }
    }
}
