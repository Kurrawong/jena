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

import static org.junit.Assert.*;

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
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestDateLiteralRoundTrip {

    private static final String NS = "http://example.org/";
    private static final String FP = "urn:jena:lucene:field#";
    private static final Node EVENT_CLASS = NodeFactory.createURI(NS + "Event");
    private static final Node LABEL_PRED = RDFS.label.asNode();
    private static final Node EVENT_DATE_PRED = NodeFactory.createURI(NS + "eventDate");
    private static final Node EVENT_TS_PRED = NodeFactory.createURI(NS + "eventTimestamp");
    private static final Node NOTE_PRED = NodeFactory.createURI(NS + "note");

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @Before
    public void setUp() {
        FieldDef labelField = new FieldDef("label", FieldType.TEXT, null,
            true, true, false, false, false, true);
        FieldDef dateField = new FieldDef("eventDate", FieldType.TEMPORAL, null, null,
            true, true, false, true, false, false, true);
        FieldDef tsField = new FieldDef("eventTimestamp", FieldType.TEMPORAL, null, null,
            true, true, false, true, false, false, true);
        FieldDef noteField = new FieldDef("note", FieldType.TEXT, null, null,
            true, true, false, false, false, false, true);

        List<FieldOccurrence> rootOccurrences = Arrays.asList(
            occurrence(labelField, PathFactory.pathLink(LABEL_PRED), Collections.singleton(LABEL_PRED)),
            occurrence(dateField, PathFactory.pathLink(EVENT_DATE_PRED), Collections.singleton(EVENT_DATE_PRED)),
            occurrence(tsField, PathFactory.pathLink(EVENT_TS_PRED), Collections.singleton(EVENT_TS_PRED)),
            occurrence(noteField, PathFactory.pathLink(NOTE_PRED), Collections.singleton(NOTE_PRED)));

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "EventShape"),
            Collections.singleton(EVENT_CLASS),
            "uri", "docType",
            Arrays.asList(labelField, dateField, tsField, noteField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setValueStored(true);

        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);
        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping);
        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);

        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            addEvent(model, "event-1", "Alpha launch", "2024-03-04", "2024-03-04T10:15:30+10:00", "English note", "en");
            addEvent(model, "event-2", "Beta launch", "2025-01-10", "2025-01-10T05:00:00Z", "French note", "fr");
            addInvalidDateEvent(model, "event-3", "Broken launch", "2024-13-40");
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private static FieldOccurrence occurrence(FieldDef field, Path path, Set<Node> predicates) {
        return new FieldOccurrence(
            field,
            path,
            ShaclIndexAssembler.extractPathVariants(path),
            predicates,
            null, null, null, null);
    }

    @After
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    @Test
    public void testDateBetweenReturnsTypedDateLiteral() {
        CqlExpression filter = new CqlExpression.CqlBetween(FP + "eventDate", "2024-01-01", "2024-12-31");
        List<TextHit> results = textIndex.queryWithCql(List.of(FP + "eventDate"), null, filter, null, null, null, 10, null);

        assertEquals(1, results.size());
        assertEquals(NS + "event-1", results.get(0).getNode().getURI());
        assertEquals(NodeFactory.createLiteralDT("2024-03-04", org.apache.jena.datatypes.xsd.XSDDatatype.XSDdate),
            results.get(0).getLiteral());
    }

    @Test
    public void testDateTimeComparisonReturnsTypedDateTimeLiteral() {
        CqlExpression filter = new CqlExpression.CqlComparison(">=", FP + "eventTimestamp", "2025-01-01T00:00:00Z");
        List<TextHit> results = textIndex.queryWithCql(List.of(FP + "eventTimestamp"), null, filter, null, null, null, 10, null);

        assertEquals(1, results.size());
        assertEquals(NS + "event-2", results.get(0).getNode().getURI());
        assertEquals(NodeFactory.createLiteralDT("2025-01-10T05:00:00Z", org.apache.jena.datatypes.xsd.XSDDatatype.XSDdateTime),
            results.get(0).getLiteral());
    }

    @Test
    public void testLanguageTaggedLiteralRoundTripsFromStoredMetadata() {
        List<TextHit> results = textIndex.queryWithCql(List.of(FP + "note"), "English", null, null, null, null, 10, null);

        assertEquals(1, results.size());
        assertEquals(NodeFactory.createLiteralLang("English note", "en"), results.get(0).getLiteral());
    }

    @Test
    public void testInvalidDateDoesNotParticipateInRangeQuery() {
        CqlExpression filter = new CqlExpression.CqlComparison(">=", FP + "eventDate", "2024-01-01");
        List<TextHit> results = textIndex.queryWithCql(List.of(FP + "eventDate"), null, filter, null, null, null, 10, null);

        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }
        assertFalse(uris.contains(NS + "event-3"));
    }

    private void addEvent(Model model, String localId, String label, String date, String timestamp, String note, String lang) {
        Resource event = ResourceFactory.createResource(NS + localId);
        model.add(event, RDF.type, ResourceFactory.createResource(NS + "Event"));
        model.add(event, RDFS.label, label);
        model.addLiteral(event, ResourceFactory.createProperty(NS, "eventDate"),
            ResourceFactory.createTypedLiteral(date, org.apache.jena.datatypes.xsd.XSDDatatype.XSDdate));
        model.addLiteral(event, ResourceFactory.createProperty(NS, "eventTimestamp"),
            ResourceFactory.createTypedLiteral(timestamp, org.apache.jena.datatypes.xsd.XSDDatatype.XSDdateTime));
        model.add(event, ResourceFactory.createProperty(NS, "note"),
            ResourceFactory.createLangLiteral(note, lang));
    }

    private void addInvalidDateEvent(Model model, String localId, String label, String invalidDate) {
        Resource event = ResourceFactory.createResource(NS + localId);
        model.add(event, RDF.type, ResourceFactory.createResource(NS + "Event"));
        model.add(event, RDFS.label, label);
        model.addLiteral(event, ResourceFactory.createProperty(NS, "eventDate"),
            ResourceFactory.createTypedLiteral(invalidDate, org.apache.jena.datatypes.xsd.XSDDatatype.XSDdate));
    }
}
