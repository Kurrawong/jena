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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.junit.jupiter.api.Test;

/**
 * The change listener keeps a document built through {@code sh:node} steps and value
 * constraints equal to a fresh build of the current data, whatever order the triples
 * change in. Each test applies one change per transaction and, after each, compares the
 * live index with a bulk build of the same data.
 */
public class TestShaclStepChangeTracking {

    private static final String COLLAR_ID_SHAPE = """
        field:collarId idx:fieldName "collarId" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
        ex:Shape sh:targetClass ex:Site ;
            sh:property [
                sh:path schema:identifier ;
                sh:node [
                    sh:property [ sh:path schema:propertyID ; sh:hasValue ex:CollarId ] ;
                    sh:property [ sh:path schema:value ; idx:field field:collarId ] ;
                ] ;
            ] .
        """;

    private static final String SAMPLE_SHAPE = """
        field:sampleId idx:fieldName "sampleId" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
        field:sampleName idx:fieldName "sampleName" ; idx:fieldType idx:KeywordField .
        field:childSampleId idx:fieldName "childSampleId" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
        ex:Shape sh:targetClass ex:Site ;
            sh:property [
                sh:path ex:hasSample ;
                sh:node [
                    sh:property [ sh:path ex:status ; sh:hasValue ex:Accepted ] ;
                    sh:property [
                        sh:path schema:identifier ;
                        sh:node [
                            sh:property [ sh:path schema:propertyID ; sh:hasValue ex:SampleId ] ;
                            sh:property [ sh:path schema:value ; idx:field field:sampleId ] ;
                        ] ;
                    ] ;
                ] ;
            ] ;
            idx:nested [
                idx:joinPath ex:hasSample ;
                idx:property [ sh:path rdfs:label ; idx:field field:sampleName ] ;
                idx:property [
                    sh:path schema:identifier ;
                    sh:node [
                        sh:property [ sh:path schema:propertyID ; sh:hasValue ex:SampleId ] ;
                        sh:property [ sh:path schema:value ; idx:field field:childSampleId ] ;
                    ] ;
                ] ;
            ] .
        """;

    private static final String SAMPLES = """
        ex:s a ex:Site ; ex:hasSample ex:accepted .
        ex:accepted ex:status ex:Accepted ; rdfs:label "accepted" ;
            schema:identifier ex:id1 .
        ex:id1 schema:propertyID ex:SampleId ; schema:value "S-1" .
        """;

    /**
     * The identifier's three triples, deleted one per transaction in every order. After
     * each deletion the document holds the CollarId exactly when all three remain.
     */
    @Test
    public void testDeletingIdentifierTriplesInAnyOrder() {
        String data = """
            ex:site a ex:Site ; schema:identifier _:id .
            _:id schema:propertyID ex:CollarId ; schema:value "C-1" .
            """;
        Model initial = parse(data);
        List<Statement> idTriples = new ArrayList<>();
        initial.listStatements().forEachRemaining(s -> {
            if (!s.getObject().equals(initial.createResource(EX + "Site"))) {
                idTriples.add(s);
            }
        });
        assertEquals(3, idTriples.size());

        for (List<Integer> order : permutations(List.of(0, 1, 2))) {
            ShaclTextIndexLucene index = openIndex(COLLAR_ID_SHAPE);
            Dataset dataset = liveDataset(index);
            try {
                write(dataset, m -> m.add(initial));
                assertEquals(List.of("C-1"), stored(parentDoc(index, EX + "site"), "collarId"));
                for (int i : order) {
                    Statement stmt = idTriples.get(i);
                    write(dataset, m -> m.remove(stmt));
                    assertEquals(List.of(), stored(parentDoc(index, EX + "site"), "collarId"),
                        "after deleting " + stmt + " in order " + order);
                    assertMatchesFreshBuild(index, dataset, COLLAR_ID_SHAPE);
                }
            } finally {
                dataset.close();
                index.close();
            }
        }
    }

    /** A second CollarId identifier, its link added before and after its own triples. */
    @Test
    public void testAddingSecondMatchingIdentifier() {
        for (boolean linkFirst : new boolean[] { true, false }) {
            ShaclTextIndexLucene index = openIndex(COLLAR_ID_SHAPE);
            Dataset dataset = liveDataset(index);
            try {
                write(dataset, m -> m.add(parse("""
                    ex:site a ex:Site ; schema:identifier ex:id1 .
                    ex:id1 schema:propertyID ex:CollarId ; schema:value "C-1" .
                    """)));
                Model second = parse("ex:id2 schema:propertyID ex:CollarId ; schema:value \"C-2\" .");
                Model link = parse("ex:site schema:identifier ex:id2 .");
                if (linkFirst) {
                    write(dataset, m -> m.add(link));
                    write(dataset, m -> m.add(second));
                } else {
                    write(dataset, m -> m.add(second));
                    write(dataset, m -> m.add(link));
                }
                assertEquals(List.of("C-1", "C-2"), stored(parentDoc(index, EX + "site"), "collarId"),
                    "linkFirst=" + linkFirst);
                assertMatchesFreshBuild(index, dataset, COLLAR_ID_SHAPE);
            } finally {
                dataset.close();
                index.close();
            }
        }
    }

    /** Data in a named graph is followed like data in the default graph. */
    @Test
    public void testEntityInNamedGraph() {
        ShaclTextIndexLucene index = openIndex(COLLAR_ID_SHAPE);
        Dataset dataset = liveDataset(index);
        String g = EX + "g";
        try {
            writeNamed(dataset, g, m -> m.add(parse("""
                ex:site a ex:Site ; schema:identifier ex:id1 .
                ex:id1 schema:propertyID ex:CollarId ; schema:value "C-1" .
                """)));
            assertEquals(List.of("C-1"), stored(parentDoc(index, EX + "site"), "collarId"));

            writeNamed(dataset, g, m -> {
                m.remove(parse("ex:id1 schema:propertyID ex:CollarId ."));
                m.add(parse("ex:id1 schema:propertyID ex:HoleId ."));
            });
            assertEquals(List.of(), stored(parentDoc(index, EX + "site"), "collarId"),
                "a filter change in the named graph removes the value");

            writeNamed(dataset, g, m -> {
                m.remove(parse("ex:id1 schema:propertyID ex:HoleId ."));
                m.add(parse("ex:id1 schema:propertyID ex:CollarId ."));
            });
            assertEquals(List.of("C-1"), stored(parentDoc(index, EX + "site"), "collarId"),
                "and changing it back restores it");

            writeNamed(dataset, g, m -> m.remove(parse("ex:site schema:identifier ex:id1 .")));
            assertEquals(List.of(), stored(parentDoc(index, EX + "site"), "collarId"),
                "deleting the link removes it");
        } finally {
            dataset.close();
            index.close();
        }
    }

    /**
     * A {@code sh:class} inside a filter, on a node one hop beyond the kept node. Typing
     * that node with a subclass makes the sample pass; removing the type makes it fail.
     */
    @Test
    public void testTypeChangeOnIntermediateNodeInFilter() {
        String shapes = """
            field:sample idx:fieldName "sample" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            ex:Shape sh:targetClass ex:Site ;
                sh:property [
                    sh:path ex:hasSample ;
                    sh:node [
                        sh:property [ sh:path ex:operator ; sh:class ex:Organisation ; sh:minCount 1 ] ;
                        sh:property [ sh:path rdfs:label ; idx:field field:sample ] ;
                    ] ;
                ] .
            """;
        ShaclTextIndexLucene index = openIndex(shapes);
        Dataset dataset = liveDataset(index);
        try {
            write(dataset, m -> m.add(parse("""
                ex:Company rdfs:subClassOf ex:Organisation .
                ex:s a ex:Site ; ex:hasSample ex:sample1 .
                ex:sample1 rdfs:label "one" ; ex:operator ex:op .
                """)));
            assertEquals(List.of(), stored(parentDoc(index, EX + "s"), "sample"),
                "an untyped operator fails sh:class");

            write(dataset, m -> m.add(parse("ex:op a ex:Company .")));
            assertEquals(List.of("one"), stored(parentDoc(index, EX + "s"), "sample"),
                "typing the operator with a subclass passes the filter");
            assertMatchesFreshBuild(index, dataset, shapes);

            write(dataset, m -> m.remove(parse("ex:op a ex:Company .")));
            assertEquals(List.of(), stored(parentDoc(index, EX + "s"), "sample"),
                "removing the type fails it again");
            assertMatchesFreshBuild(index, dataset, shapes);
        } finally {
            dataset.close();
            index.close();
        }
    }

    /** A step inside a step, and a step inside {@code idx:nested}, under live changes. */
    @Test
    public void testChangesBeneathNestedAndInnerSteps() {
        ShaclTextIndexLucene index = openIndex(SAMPLE_SHAPE);
        Dataset dataset = liveDataset(index);
        try {
            write(dataset, m -> m.add(parse(SAMPLES)));
            assertEquals(List.of("S-1"), stored(parentDoc(index, EX + "s"), "sampleId"));
            assertEquals(Map.of("accepted", List.of("S-1")), childIds(index));

            write(dataset, m -> {
                m.remove(parse("ex:id1 schema:propertyID ex:SampleId ."));
                m.add(parse("ex:id1 schema:propertyID ex:HoleId ."));
            });
            assertEquals(List.of(), stored(parentDoc(index, EX + "s"), "sampleId"),
                "the inner filter, two steps down, is followed");
            assertEquals(Map.of("accepted", List.of()), childIds(index),
                "and so is the filter of a step inside idx:nested");
            assertMatchesFreshBuild(index, dataset, SAMPLE_SHAPE);

            write(dataset, m -> {
                m.remove(parse("ex:id1 schema:propertyID ex:HoleId ."));
                m.add(parse("ex:id1 schema:propertyID ex:SampleId ."));
            });
            assertEquals(Map.of("accepted", List.of("S-1")), childIds(index));

            write(dataset, m -> m.remove(parse("ex:accepted ex:status ex:Accepted .")));
            assertEquals(List.of(), stored(parentDoc(index, EX + "s"), "sampleId"),
                "the outer filter failing drops the inner step's values");
            assertEquals(Map.of("accepted", List.of("S-1")), childIds(index),
                "the nested block has no outer filter");
            assertMatchesFreshBuild(index, dataset, SAMPLE_SHAPE);

            write(dataset, m -> m.add(parse("ex:accepted ex:status ex:Accepted .")));
            write(dataset, m -> m.remove(parse("ex:accepted schema:identifier ex:id1 .")));
            assertEquals(List.of(), stored(parentDoc(index, EX + "s"), "sampleId"),
                "deleting the inner link removes the value");
            assertEquals(Map.of("accepted", List.of()), childIds(index));
            assertMatchesFreshBuild(index, dataset, SAMPLE_SHAPE);
        } finally {
            dataset.close();
            index.close();
        }
    }

    /**
     * A filter change under a step inside {@code idx:nested}, with no root occurrence
     * reading the same predicate: it must be walked back through the join path to the
     * parent, not treated as a change to the child node's own document.
     */
    @Test
    public void testFilterChangeInNestedOnlyStep() {
        String shapes = """
            field:sampleName idx:fieldName "sampleName" ; idx:fieldType idx:KeywordField .
            field:childSampleId idx:fieldName "childSampleId" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            ex:Shape sh:targetClass ex:Site ;
                idx:nested [
                    idx:joinPath ex:hasSample ;
                    idx:property [ sh:path rdfs:label ; idx:field field:sampleName ] ;
                    idx:property [
                        sh:path schema:identifier ;
                        sh:node [
                            sh:property [ sh:path schema:propertyID ; sh:hasValue ex:SampleId ] ;
                            sh:property [ sh:path schema:value ; idx:field field:childSampleId ] ;
                        ] ;
                    ] ;
                ] .
            """;
        ShaclTextIndexLucene index = openIndex(shapes);
        Dataset dataset = liveDataset(index);
        try {
            write(dataset, m -> m.add(parse(SAMPLES)));
            assertEquals(Map.of("accepted", List.of("S-1")), childIds(index));

            write(dataset, m -> {
                m.remove(parse("ex:id1 schema:propertyID ex:SampleId ."));
                m.add(parse("ex:id1 schema:propertyID ex:HoleId ."));
            });
            assertEquals(Map.of("accepted", List.of()), childIds(index),
                "the child's filter change rebuilds the parent's block");
            assertMatchesFreshBuild(index, dataset, shapes);
        } finally {
            dataset.close();
            index.close();
        }
    }

    /** {@code sh:equals} in a filter reads a second property; a change to it is followed. */
    @Test
    public void testPairConstraintInFilterIsFollowed() {
        String shapes = """
            field:v idx:fieldName "v" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            ex:Shape sh:targetClass ex:Site ;
                sh:property [
                    sh:path ex:part ;
                    sh:node [
                        sh:property [ sh:path ex:declared ; sh:equals ex:measured ] ;
                        sh:property [ sh:path ex:v ; idx:field field:v ] ;
                    ] ;
                ] .
            """;
        ShaclTextIndexLucene index = openIndex(shapes);
        Dataset dataset = liveDataset(index);
        try {
            write(dataset, m -> m.add(parse("""
                ex:s a ex:Site ; ex:part ex:p .
                ex:p ex:v "x" ; ex:declared 1 ; ex:measured 1 .
                """)));
            assertEquals(List.of("x"), stored(parentDoc(index, EX + "s"), "v"));

            write(dataset, m -> {
                m.remove(parse("ex:p ex:measured 1 ."));
                m.add(parse("ex:p ex:measured 2 ."));
            });
            assertEquals(List.of(), stored(parentDoc(index, EX + "s"), "v"),
                "a change to the compared property fails the filter");
            assertMatchesFreshBuild(index, dataset, shapes);
        } finally {
            dataset.close();
            index.close();
        }
    }

    /**
     * An occurrence's value constraints read triples of the value node: its type for
     * {@code sh:class} (here through a subclass, which the exact-class lookup does not
     * see) and its properties for {@code sh:node}. A change to either is followed.
     */
    @Test
    public void testValueConstraintChangesAreFollowed() {
        String shapes = """
            field:operator idx:fieldName "operator" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            field:partner idx:fieldName "partner" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            ex:Shape sh:targetClass ex:Site ;
                sh:property [ sh:path ex:operator ; sh:class ex:Organisation ; idx:field field:operator ] ;
                sh:property [ sh:path ex:partner ; idx:field field:partner ;
                    sh:node [ sh:property [ sh:path ex:status ; sh:hasValue ex:Active ] ] ] .
            """;
        ShaclTextIndexLucene index = openIndex(shapes);
        Dataset dataset = liveDataset(index);
        try {
            write(dataset, m -> m.add(parse("""
                ex:Company rdfs:subClassOf ex:Organisation .
                ex:s a ex:Site ; ex:operator ex:op ; ex:partner ex:p .
                ex:p ex:status ex:Retired .
                """)));
            assertEquals(List.of(), stored(parentDoc(index, EX + "s"), "operator"));
            assertEquals(List.of(), stored(parentDoc(index, EX + "s"), "partner"));

            write(dataset, m -> m.add(parse("ex:op a ex:Company .")));
            assertEquals(List.of(EX + "op"), stored(parentDoc(index, EX + "s"), "operator"),
                "typing the value with a subclass admits it");

            write(dataset, m -> {
                m.remove(parse("ex:p ex:status ex:Retired ."));
                m.add(parse("ex:p ex:status ex:Active ."));
            });
            assertEquals(List.of(EX + "p"), stored(parentDoc(index, EX + "s"), "partner"),
                "a change the value's sh:node reads admits it");
            assertMatchesFreshBuild(index, dataset, shapes);

            write(dataset, m -> m.remove(parse("ex:op a ex:Company .")));
            assertEquals(List.of(), stored(parentDoc(index, EX + "s"), "operator"),
                "removing the subclass type drops it");
            assertMatchesFreshBuild(index, dataset, shapes);
        } finally {
            dataset.close();
            index.close();
        }
    }

    // ---- Helpers

    private static Map<String, List<String>> childIds(ShaclTextIndexLucene index) {
        Map<String, List<String>> byName = new java.util.TreeMap<>();
        for (org.apache.lucene.document.Document child : childDocs(index, EX + "s")) {
            byName.put(child.get("sampleName"), stored(child, "childSampleId"));
        }
        return byName;
    }

    /** The live index holds what a bulk build of the dataset's default graph writes. */
    private static void assertMatchesFreshBuild(ShaclTextIndexLucene live, Dataset dataset, String shapes) {
        DatasetGraph copy = DatasetGraphFactory.createTxnMem();
        dataset.begin(ReadWrite.READ);
        try {
            copy.executeWrite(() -> dataset.asDatasetGraph().getDefaultGraph().find()
                .forEachRemaining(copy.getDefaultGraph()::add));
        } finally {
            dataset.end();
        }
        ShaclTextIndexLucene fresh = openIndex(shapes);
        try {
            new ShaclBulkIndexer(copy, fresh, fresh.getShaclMapping()).index();
            assertEquals(renderAll(fresh), renderAll(live), "live index equals a fresh build");
        } finally {
            fresh.close();
        }
    }

    private static void write(Dataset dataset, Consumer<Model> change) {
        dataset.begin(ReadWrite.WRITE);
        try {
            change.accept(dataset.getDefaultModel());
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private static void writeNamed(Dataset dataset, String graph, Consumer<Model> change) {
        dataset.begin(ReadWrite.WRITE);
        try {
            change.accept(dataset.getNamedModel(graph));
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private static List<List<Integer>> permutations(List<Integer> items) {
        List<List<Integer>> result = new ArrayList<>();
        if (items.isEmpty()) {
            result.add(new ArrayList<>());
            return result;
        }
        for (int i = 0; i < items.size(); i++) {
            List<Integer> rest = new ArrayList<>(items);
            Integer head = rest.remove(i);
            for (List<Integer> tail : permutations(rest)) {
                tail.add(0, head);
                result.add(tail);
            }
        }
        return result;
    }
}
