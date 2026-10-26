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

import java.util.*;

import org.apache.jena.datatypes.xsd.XSDDatatype;
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
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code =}, {@code <} and {@code between} on a TemporalField compare a value's start:
 * when its span begins, in UTC. A date with an offset starts at midnight at that offset,
 * and a year or year-month starts on its first day.
 */
public class TestTemporalComparisonsByStart {

    private static final String NS = "http://example.org/";
    private static final String FP = "urn:jena:lucene:field#";
    private static final Node EVENT_CLASS = NodeFactory.createURI(NS + "Event");
    private static final Node WHEN = NodeFactory.createURI(NS + "when");

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @BeforeEach
    public void setUp() {
        FieldDef whenField = new FieldDef("when", FieldType.TEMPORAL, null, null,
            true, true, false, true, false, false, true);
        Path path = PathFactory.pathLink(WHEN);
        FieldOccurrence whenOccurrence = new FieldOccurrence(whenField, path,
            ShaclIndexAssembler.extractPathVariants(path), Collections.singleton(WHEN),
            null, null, null, null);

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(NS + "EventShape"),
            Collections.singleton(EVENT_CLASS),
            "uri", "docType",
            List.of(whenField),
            List.of(whenOccurrence),
            Collections.emptyList(),
            Collections.emptyList());

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        config.setValueStored(true);

        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);
        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping);
        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);

        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            addEvent(model, "date", ResourceFactory.createTypedLiteral("2021-06-30", XSDDatatype.XSDdate));
            addEvent(model, "date-offset", ResourceFactory.createTypedLiteral("2021-06-30+10:00", XSDDatatype.XSDdate));
            addEvent(model, "year-month", ResourceFactory.createTypedLiteral("2021-06", XSDDatatype.XSDgYearMonth));
            addEvent(model, "year", ResourceFactory.createTypedLiteral("2021", XSDDatatype.XSDgYear));
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

    @Test
    public void anOffsetDateComparesByWhenItsDayStarts() {
        assertEquals(Set.of("date-offset"), matching(new CqlExpression.CqlComparison("=", FP + "when", "2021-06-29T14:00:00Z")));
        assertEquals(Set.of("date-offset"), matching(new CqlExpression.CqlBetween(FP + "when",
            "2021-06-29T00:00:00Z", "2021-06-29T23:59:59Z")));
    }

    @Test
    public void aYearAndAYearMonthCompareByTheirFirstDay() {
        assertEquals(Set.of("year"), matching(new CqlExpression.CqlComparison("=", FP + "when", "2021-01-01")));
        assertEquals(Set.of("year-month"), matching(new CqlExpression.CqlComparison("=", FP + "when", "2021-06-01")));
    }

    private Set<String> matching(CqlExpression filter) {
        Set<String> names = new HashSet<>();
        for (TextHit hit : textIndex.queryWithCql(List.of(FP + "when"), null, filter, null, null, null, 10, null)) {
            names.add(hit.getNode().getURI().substring(NS.length()));
        }
        return names;
    }

    private static void addEvent(Model model, String name, Literal when) {
        Resource event = ResourceFactory.createResource(NS + name);
        model.add(event, RDF.type, ResourceFactory.createResource(EVENT_CLASS.getURI()));
        model.add(event, ResourceFactory.createProperty(WHEN.getURI()), when);
    }
}
