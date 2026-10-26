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
import java.util.*;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.query.text.ShaclIndexMapping.FieldOccurrence;
import org.apache.jena.query.text.ShaclIndexMapping.FieldType;
import org.apache.jena.query.text.ShaclIndexMapping.IndexProfile;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a temporal operator means where Prez's fixture does not reach: a field with more
 * than one value, an entity with none, and the filters that cannot be run.
 */
public class TestTemporalOperatorSemantics {

    private static final String NS = "http://example.org/";
    private static final String WHEN = "urn:jena:lucene:field#when";
    private static final String KIND = "urn:jena:lucene:field#kind";

    private static final String FIXTURE = """
        @prefix ex:  <http://example.org/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

        ex:new-years-eve-and-day a ex:Thing ;
            ex:when "2021-01-01"^^xsd:date , "2021-12-31"^^xsd:date ;
            ex:kind "both" .
        ex:mid-june a ex:Thing ;
            ex:when "2021-06-15"^^xsd:date ;
            ex:kind "june" .
        ex:undated a ex:Thing ;
            ex:kind "none" .
        """;

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @BeforeEach
    public void setUp() {
        FieldDef when = new FieldDef("when", FieldType.TEMPORAL, null, null,
            false, true, false, false, true, false, true);
        FieldDef kind = new FieldDef("kind", FieldType.KEYWORD, null, null,
            true, true, false, false, false, false, false);
        IndexProfile profile = new IndexProfile(NodeFactory.createURI(NS + "Shape"),
            Collections.singleton(NodeFactory.createURI(NS + "Thing")), "uri", "docType",
            List.of(when, kind), List.of(occurrence(when, NS + "when"), occurrence(kind, NS + "kind")),
            Collections.emptyList(), Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);
        Dataset baseDs = DatasetFactory.create();
        dataset = TextDatasetFactory.create(baseDs, textIndex, true,
            new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping));

        dataset.begin(ReadWrite.WRITE);
        try {
            dataset.getDefaultModel().read(new StringReader(FIXTURE), null, "TTL");
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    @AfterEach
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    /** New Year's Day and New Year's Eve are two days, not the year between them. */
    @Test
    public void aPropertyOperandKeepsEachValuesStartWithItsOwnEnd() {
        assertEquals(Set.of(), matching("""
            {"op": "t_contains", "args": [{"property": "%s"}, {"date": "2021-06-15"}]}
            """.formatted(WHEN)));
    }

    /** As Prez's SPARQL binds each end to its own variable: any start with any end. */
    @Test
    public void eachPropertyEndOfAnIntervalIsBoundOnItsOwn() {
        assertEquals(Set.of("new-years-eve-and-day"), matching("""
            {"op": "t_contains", "args": [{"interval": [{"property": "%s"}, {"property": "%s"}]}, {"date": "2021-06-15"}]}
            """.formatted(WHEN, WHEN)));
    }

    /** A positive predicate holds when any one value satisfies it, t_disjoint included. */
    @Test
    public void aMultiValuedFieldMatchesWhenAnyValueDoes() {
        assertEquals(Set.of("new-years-eve-and-day"), matching("""
            {"op": "t_intersects", "args": [{"property": "%s"}, {"date": "2021-01-01"}]}
            """.formatted(WHEN)));
        assertEquals(Set.of("new-years-eve-and-day", "mid-june"), matching("""
            {"op": "t_disjoint", "args": [{"property": "%s"}, {"date": "2021-01-01"}]}
            """.formatted(WHEN)));
    }

    /**
     * A predicate on a missing value is NULL, not true (OGC 21-065r2 Req 14C), so an entity
     * with no value matches no operator. Negating one keeps the compiler's NOT, which does.
     */
    @Test
    public void anEntityWithNoValueMatchesOnlyANegation() {
        assertFalse(matching("""
            {"op": "t_disjoint", "args": [{"property": "%s"}, {"interval": ["..", ".."]}]}
            """.formatted(WHEN)).contains("undated"));
        assertFalse(matching("""
            {"op": "t_intersects", "args": [{"property": "%s"}, {"interval": ["..", ".."]}]}
            """.formatted(WHEN)).contains("undated"));
        assertEquals(Set.of("mid-june", "undated"), matching("""
            {"op": "not", "args": [{"op": "t_intersects", "args": [{"property": "%s"}, {"date": "2021-01-01"}]}]}
            """.formatted(WHEN)));
    }

    @Test
    public void twoLiteralsAreAConstant() {
        assertEquals(Set.of("new-years-eve-and-day", "mid-june", "undated"), matching("""
            {"op": "t_before", "args": [{"date": "2020-01-01"}, {"date": "2021-01-01"}]}
            """));
        assertEquals(Set.of(), matching("""
            {"op": "t_after", "args": [{"date": "2020-01-01"}, {"date": "2021-01-01"}]}
            """));
    }

    @Test
    public void aFilterThatCannotBeRunRaises() {
        assertRaises("compares two properties", """
            {"op": "t_before", "args": [{"property": "%s"}, {"property": "%s"}]}
            """.formatted(WHEN, WHEN));
        assertRaises("unknown field", """
            {"op": "t_before", "args": [{"property": "urn:jena:lucene:field#nope"}, {"date": "2021-01-01"}]}
            """);
        assertRaises("TemporalField", """
            {"op": "t_before", "args": [{"property": "%s"}, {"date": "2021-01-01"}]}
            """.formatted(KIND));
        assertRaises("not a date or a timestamp", """
            {"op": "t_before", "args": [{"property": "%s"}, {"timestamp": "yesterday"}]}
            """.formatted(WHEN));
        assertRaises("interval must be [start, end]", """
            {"op": "t_before", "args": [{"property": "%s"}, {"interval": ["2021-01-01"]}]}
            """.formatted(WHEN));
        assertRaises("requires exactly 2 arguments", """
            {"op": "t_before", "args": [{"property": "%s"}]}
            """.formatted(WHEN));
        assertRaises("operand must be", """
            {"op": "t_before", "args": [{"property": "%s"}, "2021-01-01"]}
            """.formatted(WHEN));
    }

    private void assertRaises(String messagePart, String cqlJson) {
        TextIndexException ex = assertThrows(TextIndexException.class, () -> matching(cqlJson));
        assertTrue(ex.getMessage().contains(messagePart), ex.getMessage());
    }

    private Set<String> matching(String cqlJson) {
        Set<String> names = new TreeSet<>();
        for (TextHit hit : textIndex.queryWithCql(List.of(KIND), null, CqlParser.parse(cqlJson),
                null, null, null, 100, null)) {
            names.add(hit.getNode().getURI().substring(NS.length()));
        }
        return names;
    }

    private static FieldOccurrence occurrence(FieldDef field, String predicate) {
        Node p = NodeFactory.createURI(predicate);
        Path path = PathFactory.pathLink(p);
        return new FieldOccurrence(field, path, ShaclIndexAssembler.extractPathVariants(path),
            Collections.singleton(p), null, null, null, null);
    }
}
