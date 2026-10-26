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

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.assembler.TextAssembler;
import org.apache.jena.query.text.cql.CqlParser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sys.JenaSystem;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code idx:PeriodField}: a start and an end read from one period node, kept paired, and
 * queried by the temporal operators as the span from the start's start to the end's end.
 */
public class TestPeriodField {

    static {
        JenaSystem.init();
        TextAssembler.init();
    }

    private static final String NS = "http://example.org/";
    private static final String VALIDITY = "urn:jena:lucene:field#validity";

    private static final String PREFIXES = """
        @prefix idx:   <urn:jena:lucene:index#> .
        @prefix field: <urn:jena:lucene:field#> .
        @prefix sh:    <http://www.w3.org/ns/shacl#> .
        @prefix time:  <http://www.w3.org/2006/time#> .
        @prefix xsd:   <http://www.w3.org/2001/XMLSchema#> .
        @prefix ex:    <http://example.org/> .
        """;

    /** OWL-Time: the period node reaches its start and end through an instant each. */
    private static final String OWL_TIME_CONFIG = PREFIXES + """
        field:validity idx:fieldName "validity" ; idx:fieldType idx:PeriodField ; idx:multiValued true .
        ex:TenementShape sh:targetClass ex:Tenement ;
            sh:property [
                idx:field field:validity ;
                sh:path ex:validity ;
                idx:periodStart ( time:hasBeginning time:inXSDDate ) ;
                idx:periodEnd   ( time:hasEnd time:inXSDDate ) ;
            ] .
        """;

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @AfterEach
    public void tearDown() {
        if (dataset != null) {
            dataset.close();
        }
    }

    /**
     * Renewed in 2018 after lapsing in 2013: two periods, neither during 2011–2019. Paired
     * across periods, the 2018 start and the 2012 end would be.
     */
    @Test
    public void eachPeriodKeepsItsStartWithItsOwnEnd() {
        load(OWL_TIME_CONFIG, PREFIXES + """
            ex:renewed a ex:Tenement ; ex:validity
                [ time:hasBeginning [ time:inXSDDate "2010-01-01"^^xsd:date ] ;
                  time:hasEnd       [ time:inXSDDate "2012-12-31"^^xsd:date ] ] ,
                [ time:hasBeginning [ time:inXSDDate "2018-01-01"^^xsd:date ] ;
                  time:hasEnd       [ time:inXSDDate "2020-12-31"^^xsd:date ] ] .
            ex:mid-decade a ex:Tenement ; ex:validity
                [ time:hasBeginning [ time:inXSDDate "2013-01-01"^^xsd:date ] ;
                  time:hasEnd       [ time:inXSDDate "2014-12-31"^^xsd:date ] ] .
            """);

        assertEquals(Set.of("mid-decade"), matching("""
            {"op": "t_during", "args": [{"property": "%s"}, {"interval": ["2011-01-01", "2019-12-31"]}]}
            """.formatted(VALIDITY)));
    }

    /**
     * A missing bound is open: a tenement with no end date is still current. Open ends
     * compare as infinities, so an ongoing period finishes with an interval open at its end.
     */
    @Test
    public void aMissingBoundIsOpen() {
        load(OWL_TIME_CONFIG, PREFIXES + """
            ex:current a ex:Tenement ; ex:validity
                [ time:hasBeginning [ time:inXSDDate "2015-01-01"^^xsd:date ] ] .
            ex:since-forever a ex:Tenement ; ex:validity
                [ time:hasEnd [ time:inXSDDate "2000-12-31"^^xsd:date ] ] .
            ex:neither a ex:Tenement ; ex:validity [ ex:note "no dates" ] .
            """);

        assertEquals(Set.of("current"), matching("""
            {"op": "t_intersects", "args": [{"property": "%s"}, {"interval": ["2030-01-01", "2030-12-31"]}]}
            """.formatted(VALIDITY)));
        assertEquals(Set.of("since-forever"), matching("""
            {"op": "t_before", "args": [{"property": "%s"}, {"date": "2001-06-01"}]}
            """.formatted(VALIDITY)));
        assertEquals(Set.of("current"), matching("""
            {"op": "t_finishes", "args": [{"property": "%s"}, {"interval": ["2010-01-01", ".."]}]}
            """.formatted(VALIDITY)));
        assertEquals(Set.of("current", "since-forever"), matching("""
            {"op": "t_intersects", "args": [{"property": "%s"}, {"interval": ["..", ".."]}]}
            """.formatted(VALIDITY)), "a period node with neither bound has no span");
    }

    /** Each is logged and skipped; the entity is still indexed, with its other periods. */
    @Test
    public void aPeriodThatCannotBeReadIsSkipped() {
        load(OWL_TIME_CONFIG, PREFIXES + """
            ex:two-starts a ex:Tenement ; ex:validity
                [ time:hasBeginning [ time:inXSDDate "2010-01-01"^^xsd:date ] ,
                                    [ time:inXSDDate "2011-01-01"^^xsd:date ] ;
                  time:hasEnd       [ time:inXSDDate "2012-12-31"^^xsd:date ] ] .
            ex:ends-before-it-starts a ex:Tenement ; ex:validity
                [ time:hasBeginning [ time:inXSDDate "2020-01-01"^^xsd:date ] ;
                  time:hasEnd       [ time:inXSDDate "2010-12-31"^^xsd:date ] ] .
            ex:not-a-date a ex:Tenement ; ex:validity
                [ time:hasBeginning [ time:inXSDDate "soon"^^xsd:date ] ;
                  time:hasEnd       [ time:inXSDDate "2010-12-31"^^xsd:date ] ] .
            ex:one-good-one-bad a ex:Tenement ; ex:validity
                [ time:hasBeginning [ time:inXSDDate "2020-01-01"^^xsd:date ] ;
                  time:hasEnd       [ time:inXSDDate "2010-12-31"^^xsd:date ] ] ,
                [ time:hasBeginning [ time:inXSDDate "2001-01-01"^^xsd:date ] ;
                  time:hasEnd       [ time:inXSDDate "2001-12-31"^^xsd:date ] ] .
            """);

        assertEquals(Set.of("one-good-one-bad"), matching("""
            {"op": "t_intersects", "args": [{"property": "%s"}, {"interval": ["..", ".."]}]}
            """.formatted(VALIDITY)));
    }

    private void load(String config, String data) {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(config), null, "TTL");
        RDFList shapes = model.createList(model.listSubjectsWithProperty(
            model.createProperty("http://www.w3.org/ns/shacl#targetClass")).toList().toArray(new Resource[0]));
        ShaclIndexMapping mapping = ShaclIndexAssembler.parseShapes(Assembler.general(), shapes);

        TextIndexConfig indexConfig = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        indexConfig.setShaclMapping(mapping);
        textIndex = new ShaclTextIndexLucene(new ByteBuffersDirectory(), new ByteBuffersDirectory(), indexConfig);
        Dataset baseDs = DatasetFactory.create();
        dataset = TextDatasetFactory.create(baseDs, textIndex, true,
            new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping));

        dataset.begin(ReadWrite.WRITE);
        try {
            dataset.getDefaultModel().read(new StringReader(data), null, "TTL");
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private Set<String> matching(String cqlJson) {
        Set<String> names = new TreeSet<>();
        for (TextHit hit : textIndex.queryWithCql(List.of(VALIDITY), null, CqlParser.parse(cqlJson),
                null, null, null, 100, null)) {
            names.add(hit.getNode().getURI().substring(NS.length()));
        }
        return names;
    }
}
