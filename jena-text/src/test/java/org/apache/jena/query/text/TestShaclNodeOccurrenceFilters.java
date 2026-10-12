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
import java.util.Map;

import org.apache.jena.graph.Graph;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.ShaclIndexMapping.FieldDef;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

/**
 * A {@code sh:property} with {@code sh:path} and {@code sh:node}, and no {@code idx:field},
 * is a step: it follows the path, keeps the reached nodes that conform to the
 * {@code sh:node} shape's filter, and evaluates that shape's field occurrences from each
 * kept node.
 * <p>
 * Design: {@code docs/2026-10-12_shacl_node_occurrence_filters_design.md}.
 */
public class TestShaclNodeOccurrenceFilters {

    /** The identifier example: the CollarId identifier's value, as a root field. */
    private static final String COLLAR_ID_SHAPE = """
        field:label idx:fieldName "label" ; idx:fieldType idx:KeywordField .
        field:collarId idx:fieldName "collarId" ; idx:fieldType idx:KeywordField ;
            idx:sortable true ; idx:multiValued %s .
        ex:Shape sh:targetClass ex:Site ;
            sh:property [ sh:path rdfs:label ; idx:field field:label ] ;
            sh:property [
                sh:path schema:identifier ;
                sh:node [
                    sh:property [ sh:path schema:propertyID ; sh:hasValue ex:CollarId ] ;
                    sh:property [ sh:path schema:value ; idx:field field:collarId ] ;
                ] ;
            ] .
        """;

    private static final String SITES = """
        ex:site1 a ex:Site ; rdfs:label "one" ;
            schema:identifier [ schema:propertyID ex:CollarId ; schema:value "2297211" ] ,
                              [ schema:propertyID ex:HoleId ; schema:value "H-1" ] .
        ex:site2 a ex:Site ; rdfs:label "two" ;
            schema:identifier [ schema:propertyID ex:HoleId ; schema:value "H-2" ] .
        ex:site3 a ex:Site ; rdfs:label "three" ;
            schema:identifier [ schema:propertyID ex:CollarId ; schema:value "C-31" ] ,
                              [ schema:propertyID ex:CollarId ; schema:value "C-32" ] .
        """;

    private static String collarIdShape(boolean multiValued) {
        return String.format(COLLAR_ID_SHAPE, multiValued);
    }

    private static void load(Dataset dataset, String turtle) {
        dataset.begin(ReadWrite.WRITE);
        try {
            dataset.getDefaultModel().add(parse(turtle));
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private static Graph graph(String turtle) {
        return parse(turtle).getGraph();
    }

    @Test
    public void testIdentifierIndexedAsSortableRootField() {
        ShaclTextIndexLucene index = openIndex(collarIdShape(false));
        try {
            ShaclIndexMapping mapping = index.getShaclMapping();
            FieldDef collarId = mapping.findFieldByName("collarId");
            assertNotNull(collarId, "a field reached through a step is a field of the profile");
            assertNull(mapping.findNestedDefForFieldName("collarId"), "and it is a root field");

            Dataset dataset = liveDataset(index);
            load(dataset, SITES);

            String site1 = EX + "site1";
            assertEquals(List.of("2297211"), stored(parentDoc(index, site1), "collarId"),
                "only the CollarId identifier's value is indexed");
            assertEquals("2297211", sortKey(index, site1, "collarId"),
                "the field is sortable on the entity's own document");

            assertNotNull(parentDoc(index, EX + "site2"), "a site without a CollarId is still indexed");
            assertEquals(List.of(), stored(parentDoc(index, EX + "site2"), "collarId"),
                "and has no CollarId value");
            dataset.close();
        } finally {
            index.close();
        }
    }

    @Test
    public void testTwoKeptIdentifiersGiveTwoValues() {
        ShaclTextIndexLucene index = openIndex(collarIdShape(true));
        try {
            Entity site3 = build(index, graph(SITES), uri("site3"));
            assertEquals(List.of("C-31", "C-32"), values(site3, "collarId"));
        } finally {
            index.close();
        }
    }

    @Test
    public void testTwoKeptIdentifiersOnSingleValuedFieldWarn() {
        ShaclTextIndexLucene index = openIndex(collarIdShape(false));
        try {
            Dataset dataset = liveDataset(index);
            List<String> logs = captureLogs(ShaclTextIndexLucene.class, () -> load(dataset, SITES));
            assertEquals(1, stored(parentDoc(index, EX + "site3"), "collarId").size(),
                "a single-valued field keeps one value");
            assertTrue(logs.stream().anyMatch(m -> m.contains("collarId") && m.contains("site3")
                    && m.contains("multiValued")),
                "and the surplus value is reported: " + logs);
            dataset.close();
        } finally {
            index.close();
        }
    }

    @Test
    public void testFilterWithInOverTwoKinds() {
        ShaclTextIndexLucene index = openIndex("""
            field:id idx:fieldName "id" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            ex:Shape sh:targetClass ex:Site ;
                sh:property [
                    sh:path schema:identifier ;
                    sh:node [
                        sh:property [ sh:path schema:propertyID ; sh:in ( ex:CollarId ex:HoleId ) ] ;
                        sh:property [ sh:path schema:value ; idx:field field:id ] ;
                    ] ;
                ] .
            """);
        try {
            Entity site = build(index, graph("""
                ex:s a ex:Site ;
                    schema:identifier [ schema:propertyID ex:CollarId ; schema:value "C" ] ,
                                      [ schema:propertyID ex:HoleId ; schema:value "H" ] ,
                                      [ schema:propertyID ex:OtherId ; schema:value "O" ] .
                """), uri("s"));
            assertEquals(List.of("C", "H"), values(site, "id"));
        } finally {
            index.close();
        }
    }

    @Test
    public void testFilterWithMinCountOnInnerProperty() {
        ShaclTextIndexLucene index = openIndex("""
            field:id idx:fieldName "id" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            ex:Shape sh:targetClass ex:Site ;
                sh:property [
                    sh:path schema:identifier ;
                    sh:node [
                        sh:property [ sh:path ex:verifiedBy ; sh:minCount 1 ] ;
                        sh:property [ sh:path schema:value ; idx:field field:id ] ;
                    ] ;
                ] .
            """);
        try {
            Entity site = build(index, graph("""
                ex:s a ex:Site ;
                    schema:identifier [ schema:value "verified" ; ex:verifiedBy ex:alice ] ,
                                      [ schema:value "unverified" ] .
                """), uri("s"));
            assertEquals(List.of("verified"), values(site, "id"));
        } finally {
            index.close();
        }
    }

    @Test
    public void testFilterWithConstraintOnTheShapeItself() {
        ShaclTextIndexLucene index = openIndex("""
            field:id idx:fieldName "id" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            ex:Shape sh:targetClass ex:Site ;
                sh:property [
                    sh:path schema:identifier ;
                    sh:node [
                        sh:class ex:PrimaryIdentifier ;
                        sh:property [ sh:path schema:value ; idx:field field:id ] ;
                    ] ;
                ] .
            """);
        try {
            Entity site = build(index, graph("""
                ex:s a ex:Site ;
                    schema:identifier [ a ex:PrimaryIdentifier ; schema:value "primary" ] ,
                                      [ a ex:SecondaryIdentifier ; schema:value "secondary" ] .
                """), uri("s"));
            assertEquals(List.of("primary"), values(site, "id"));
        } finally {
            index.close();
        }
    }

    @Test
    public void testPropertyWithFieldlessNodeShapeIsPartOfFilter() {
        ShaclTextIndexLucene index = openIndex("""
            field:id idx:fieldName "id" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            ex:ActiveShape sh:property [ sh:path ex:status ; sh:hasValue ex:Active ] .
            ex:Shape sh:targetClass ex:Site ;
                sh:property [
                    sh:path schema:identifier ;
                    sh:node [
                        sh:property [ sh:path ex:issuer ; sh:node ex:ActiveShape ] ;
                        sh:property [ sh:path schema:value ; idx:field field:id ] ;
                    ] ;
                ] .
            """);
        try {
            Entity site = build(index, graph("""
                ex:s a ex:Site ;
                    schema:identifier [ ex:issuer ex:active ; schema:value "kept" ] ,
                                      [ ex:issuer ex:active , ex:retired ; schema:value "dropped" ] .
                ex:active ex:status ex:Active .
                ex:retired ex:status ex:Retired .
                """), uri("s"));
            assertEquals(List.of("kept"), values(site, "id"),
                "a node is kept when every ex:issuer conforms to the named shape");
        } finally {
            index.close();
        }
    }

    private static final String SAMPLE_STEPS = """
        field:sampleId idx:fieldName "sampleId" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
        field:sampleLabel idx:fieldName "sampleLabel" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
        ex:Shape sh:targetClass ex:Site ;
            sh:property [
                sh:path ex:hasSample ;
                sh:node [
                    sh:property [ sh:path ex:status ; sh:hasValue ex:Accepted ] ;
                    sh:property [ sh:path rdfs:label ; idx:field field:sampleLabel ] ;
                    sh:property [
                        sh:path schema:identifier ;
                        sh:node [
                            sh:property [ sh:path schema:propertyID ; sh:hasValue ex:SampleId ] ;
                            sh:property [ sh:path schema:value ; idx:field field:sampleId ] ;
                        ] ;
                    ] ;
                ] ;
            ] .
        """;

    private static final String SAMPLES = """
        ex:s a ex:Site ;
            ex:hasSample ex:accepted , ex:rejected .
        ex:accepted ex:status ex:Accepted ; rdfs:label "accepted" ;
            schema:identifier [ schema:propertyID ex:SampleId ; schema:value "S-1" ] ,
                              [ schema:propertyID ex:HoleId ; schema:value "H-1" ] .
        ex:rejected ex:status ex:Rejected ; rdfs:label "rejected" ;
            schema:identifier [ schema:propertyID ex:SampleId ; schema:value "S-2" ] .
        """;

    @Test
    public void testStepInsideStep() {
        ShaclTextIndexLucene index = openIndex(SAMPLE_STEPS);
        try {
            Entity site = build(index, graph(SAMPLES), uri("s"));
            assertEquals(List.of("accepted"), values(site, "sampleLabel"),
                "the outer filter keeps only the accepted sample");
            assertEquals(List.of("S-1"), values(site, "sampleId"),
                "the inner step runs from the kept sample and keeps only its SampleId");
        } finally {
            index.close();
        }
    }

    @Test
    public void testStepInsideNestedBlockFeedsChildDocument() {
        ShaclTextIndexLucene index = openIndex("""
            field:sampleName idx:fieldName "sampleName" ; idx:fieldType idx:KeywordField .
            field:childSampleId idx:fieldName "childSampleId" ; idx:fieldType idx:KeywordField .
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
            """);
        try {
            ShaclIndexMapping mapping = index.getShaclMapping();
            assertNotNull(mapping.findNestedDefForFieldName("childSampleId"),
                "a field reached through a step inside idx:nested belongs to the nested block");

            Dataset dataset = liveDataset(index);
            load(dataset, SAMPLES);
            String site = EX + "s";
            assertEquals(List.of(), stored(parentDoc(index, site), "childSampleId"),
                "the value is not flattened onto the parent");
            Map<String, List<String>> byName = new java.util.TreeMap<>();
            for (org.apache.lucene.document.Document child : childDocs(index, site)) {
                byName.put(child.get("sampleName"), stored(child, "childSampleId"));
            }
            assertEquals(Map.of("accepted", List.of("S-1"), "rejected", List.of("S-2")), byName,
                "each child carries its own SampleId only");
            dataset.close();
        } finally {
            index.close();
        }
    }

    @Test
    public void testOwnValueConstraintDoesNotDecideWhetherNodeIsKept() {
        ShaclTextIndexLucene index = openIndex("""
            field:id idx:fieldName "id" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            field:kind idx:fieldName "kind" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            ex:Shape sh:targetClass ex:Site ;
                sh:property [
                    sh:path schema:identifier ;
                    sh:node [
                        sh:property [ sh:path schema:propertyID ; sh:hasValue ex:CollarId ] ;
                        sh:property [ sh:path schema:value ; idx:field field:id ; sh:datatype xsd:integer ] ;
                        sh:property [ sh:path schema:propertyID ; idx:field field:kind ] ;
                    ] ;
                ] .
            """);
        try {
            Entity site = build(index, graph("""
                ex:s a ex:Site ;
                    schema:identifier [ schema:propertyID ex:CollarId ; schema:value "A" , 5 ] .
                """), uri("s"));
            assertEquals(List.of(EX + "CollarId"), values(site, "kind"),
                "the identifier is kept although one of its values fails sh:datatype");
            assertEquals(List.of("5"), values(site, "id"),
                "and the field's own constraint filters its values one at a time");
        } finally {
            index.close();
        }
    }

    @Test
    public void testChangeListenerFollowsFilterAndFieldChanges() {
        ShaclTextIndexLucene index = openIndex(collarIdShape(false));
        Dataset dataset = liveDataset(index);
        try {
            Model model = parse("");
            Resource site = model.createResource(EX + "site");
            Resource identifier = model.createResource();
            Property propertyID = model.createProperty(SCHEMA + "propertyID");
            Property value = model.createProperty(SCHEMA + "value");
            Property identifierProp = model.createProperty(SCHEMA + "identifier");
            Resource collarId = model.createResource(EX + "CollarId");
            Resource holeId = model.createResource(EX + "HoleId");

            write(dataset, m -> {
                m.add(site, RDF.type, m.createResource(EX + "Site"));
                m.add(site, identifierProp, identifier);
                m.add(identifier, propertyID, collarId);
                m.add(identifier, value, "2297211");
            });
            assertEquals(List.of("2297211"), collarIds(index));

            write(dataset, m -> {
                m.remove(identifier, propertyID, collarId);
                m.add(identifier, propertyID, holeId);
            });
            assertEquals(List.of(), collarIds(index),
                "changing the kind to another one removes the value");

            write(dataset, m -> {
                m.remove(identifier, propertyID, holeId);
                m.add(identifier, propertyID, collarId);
            });
            assertEquals(List.of("2297211"), collarIds(index), "changing it back restores it");

            write(dataset, m -> {
                m.remove(identifier, value, m.createLiteral("2297211"));
                m.add(identifier, value, "2297212");
            });
            assertEquals(List.of("2297212"), collarIds(index), "changing schema:value updates it");

            write(dataset, m -> {
                m.remove(identifier, propertyID, collarId);
                m.remove(identifier, value, m.createLiteral("2297212"));
                m.remove(site, identifierProp, identifier);
            });
            assertEquals(List.of(), collarIds(index), "deleting the blank node's triples removes it");
        } finally {
            dataset.close();
            index.close();
        }
    }

    @Test
    public void testChangeListenerFollowsTypeOnFilteredNode() {
        ShaclTextIndexLucene index = openIndex("""
            field:id idx:fieldName "id" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            ex:Shape sh:targetClass ex:Site ;
                sh:property [
                    sh:path schema:identifier ;
                    sh:node [
                        sh:class ex:PrimaryIdentifier ;
                        sh:property [ sh:path schema:value ; idx:field field:id ] ;
                    ] ;
                ] .
            """);
        Dataset dataset = liveDataset(index);
        try {
            Model model = parse("");
            Resource site = model.createResource(EX + "site");
            Resource identifier = model.createResource();
            Resource primary = model.createResource(EX + "PrimaryIdentifier");
            write(dataset, m -> {
                m.add(site, RDF.type, m.createResource(EX + "Site"));
                m.add(site, m.createProperty(SCHEMA + "identifier"), identifier);
                m.add(identifier, m.createProperty(SCHEMA + "value"), "P-1");
            });
            assertEquals(List.of(), stored(parentDoc(index, EX + "site"), "id"));

            write(dataset, m -> m.add(identifier, RDF.type, primary));
            assertEquals(List.of("P-1"), stored(parentDoc(index, EX + "site"), "id"),
                "typing the identifier makes it pass the filter");

            write(dataset, m -> m.remove(identifier, RDF.type, primary));
            assertEquals(List.of(), stored(parentDoc(index, EX + "site"), "id"),
                "removing the type makes it fail again");
        } finally {
            dataset.close();
            index.close();
        }
    }

    private static List<String> collarIds(ShaclTextIndexLucene index) {
        return stored(parentDoc(index, EX + "site"), "collarId");
    }

    private static void write(Dataset dataset, java.util.function.Consumer<Model> change) {
        dataset.begin(ReadWrite.WRITE);
        try {
            change.accept(dataset.getDefaultModel());
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    @Test
    public void testFingerprintChangesWhenFilterChanges() {
        String collar = collarIdShape(false);
        String hole = collar.replace("sh:hasValue ex:CollarId", "sh:hasValue ex:HoleId");
        assertNotEquals(collar, hole);

        String first = fingerprint(collar);
        assertEquals(first, fingerprint(collar),
            "the same configuration parsed twice has the same fingerprint");
        assertNotEquals(first, fingerprint(hole), "a changed filter changes the fingerprint");
    }

    private static String fingerprint(String shapes) {
        ShaclTextIndexLucene index = openIndex(shapes);
        try {
            ShaclIndexMapping mapping = index.getShaclMapping();
            return ShaclConfigFingerprint.fingerprint(mapping, true, mapping.getFacetFieldNames());
        } finally {
            index.close();
        }
    }

    @Test
    public void testBulkIndexerAndChangeListenerProduceSameDocuments() {
        String shapes = """
            field:collarId idx:fieldName "collarId" ; idx:fieldType idx:KeywordField ;
                idx:sortable true ; idx:multiValued true .
            field:sampleId idx:fieldName "sampleId" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            field:sampleName idx:fieldName "sampleName" ; idx:fieldType idx:KeywordField .
            field:childSampleId idx:fieldName "childSampleId" ; idx:fieldType idx:KeywordField .
            field:operator idx:fieldName "operator" ; idx:fieldType idx:KeywordField ; idx:multiValued true .
            ex:Shape sh:targetClass ex:Site ;
                sh:property [ sh:path ex:operator ; sh:class ex:Organisation ; idx:field field:operator ] ;
                sh:property [
                    sh:path schema:identifier ;
                    sh:node [
                        sh:property [ sh:path schema:propertyID ; sh:in ( ex:CollarId ) ] ;
                        sh:property [ sh:path schema:value ; idx:field field:collarId ] ;
                    ] ;
                ] ;
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
        // A change to rdfs:subClassOf is not tracked, so the class hierarchy is loaded
        // before the instances, as a reindex would see it.
        String ontology = "ex:Company rdfs:subClassOf ex:Organisation .\n";
        String data = ontology + SITES + SAMPLES + """
            ex:site1 ex:operator ex:acme , ex:nobody .
            ex:acme a ex:Company .
            ex:nobody a ex:Person .
            """;

        ShaclTextIndexLucene live = openIndex(shapes);
        ShaclTextIndexLucene bulk = openIndex(shapes);
        try {
            Dataset dataset = liveDataset(live);
            load(dataset, ontology);
            load(dataset, data);
            dataset.close();

            DatasetGraph dsg = DatasetGraphFactory.createTxnMem();
            dsg.executeWrite(() -> graph(data).find().forEachRemaining(dsg.getDefaultGraph()::add));
            ShaclBulkIndexer indexer = new ShaclBulkIndexer(dsg, bulk, bulk.getShaclMapping());
            indexer.index();

            Map<String, List<String>> liveDocs = renderAll(live);
            assertEquals(4, liveDocs.size(), "four sites indexed: " + liveDocs.keySet());
            assertTrue(liveDocs.get(EX + "site1").toString().contains("operator=" + EX + "acme"),
                "sh:class admits the subclass instance: " + liveDocs.get(EX + "site1"));
            assertEquals(liveDocs, renderAll(bulk));
        } finally {
            live.close();
            bulk.close();
        }
    }
}
