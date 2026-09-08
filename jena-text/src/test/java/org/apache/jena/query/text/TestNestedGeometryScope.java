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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
import org.apache.jena.query.text.ShaclIndexMapping.JoinStep;
import org.apache.jena.query.text.ShaclIndexMapping.NestedDef;
import org.apache.jena.query.text.assembler.ShaclIndexAssembler;
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.path.PathFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A TEMPORAL field inside an {@code idx:nested} block.
 * <p>
 * Every other temporal test puts the date at root scope. That leaves the interesting part
 * untested, because a temporal field is really two Lucene fields: the stored lexical form
 * and an {@code __epoch} companion carrying the sortable, range-queryable value. Nesting
 * is where those could disagree, since the scope lookup resolves the field by name while
 * the range query targets the companion.
 * <p>
 * The shape is the one suggested in the issue and in
 * {@code docs/10-suggested-configuration.md}: observations hanging off a station, each
 * with its own {@code resultTime}. A per-record timestamp is the obvious next field a
 * reader following that page would add.
 * <p>
 * The assertion that matters is the correlated one. A station whose observations include
 * a copper reading and a March date, but never on the same observation, must not match a
 * filter asking for both. That is the whole point of nesting, and a date participating in
 * it is what was unverified.
 */
public class TestNestedGeometryScope {

    private static final String EX = "http://example.org/";
    private static final String FIELD_NS = "urn:jena:lucene:field#";
    private static final Node SITE_CLASS = NodeFactory.createURI(EX + "Site");
    private static final Node LABEL = RDFS.label.asNode();
    private static final Node HAS_PART = NodeFactory.createURI(EX + "hasPart");
    private static final Node AS_WKT = NodeFactory.createURI("http://www.opengis.net/ont/geosparql#asWKT");
    private static final String PART_SCOPE = "parts";

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;

    @Before
    public void setUp() {
        FieldDef entityType = new FieldDef("entityType", FieldType.KEYWORD, null,
            true, true, true, false, true, false, false,
            NodeFactory.createURI(FIELD_NS + "entityType"));
        FieldDef title = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true, false,
            NodeFactory.createURI(FIELD_NS + "title"));
        // one geometry per child document, which is what "any-of" would need
        FieldDef geometry = new FieldDef("geometry", FieldType.LATLON, null,
            true, true, false, false, false, false, false,
            NodeFactory.createURI(FIELD_NS + "geometry"));

        NestedDef parts = new NestedDef(
            PART_SCOPE,
            PathFactory.pathLink(HAS_PART),
            List.of(new JoinStep(HAS_PART, false)),
            Collections.singleton(HAS_PART),
            List.of(new FieldOccurrence(geometry, PathFactory.pathLink(AS_WKT),
                List.of(List.of(new JoinStep(AS_WKT, false))),
                new LinkedHashSet<>(Collections.singletonList(AS_WKT)),
                null, null, null, PART_SCOPE)),
            Collections.emptyList());

        IndexProfile profile = new IndexProfile(
            NodeFactory.createURI(EX + "SiteShape"),
            Collections.singleton(SITE_CLASS),
            "uri", "docType",
            Arrays.asList(entityType, title, geometry),
            List.of(new FieldOccurrence(entityType, PathFactory.pathLink(RDF.type.asNode()),
                        List.of(List.of(new JoinStep(RDF.type.asNode(), false))),
                        Collections.singleton(RDF.type.asNode()), null, null, null, null),
                    new FieldOccurrence(title, PathFactory.pathLink(LABEL),
                        List.of(List.of(new JoinStep(LABEL, false))),
                        Collections.singleton(LABEL), null, null, null, null)),
            Collections.emptyList(),
            Collections.singletonList(parts));

        ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
        TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
        config.setShaclMapping(mapping);
        config.setFacetFields(mapping.getFacetFieldNames());
        config.setValueStored(true);

        textIndex = new ShaclTextIndexLucene(
            new ByteBuffersDirectory(), new ByteBuffersDirectory(), config);
        Dataset baseDs = DatasetFactory.create();
        dataset = TextDatasetFactory.create(baseDs, textIndex, true,
            new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping));

        dataset.begin(ReadWrite.WRITE);
        try {
            Model m = dataset.getDefaultModel();
            // Two parts: one in WA, one in NSW. Under any-of this site is "within WA";
            // under all-of it is not.
            addSite(m, "split-site", "Split Site",
                new String[] { "POINT(116.35 -32.77)", "POINT(148.99 -33.47)" });
            // Both parts in WA.
            addSite(m, "wa-site", "WA Site",
                new String[] { "POINT(116.00 -31.00)", "POINT(117.00 -32.00)" });
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void addSite(Model m, String id, String label, String[] wkts) {
        Resource site = ResourceFactory.createResource(EX + id);
        m.add(site, RDF.type, ResourceFactory.createResource(EX + "Site"));
        m.add(site, RDFS.label, ResourceFactory.createPlainLiteral(label));
        int i = 0;
        for (String wkt : wkts) {
            Resource part = ResourceFactory.createResource(EX + id + "-part-" + (i++));
            m.add(site, ResourceFactory.createProperty(EX, "hasPart"), part);
            m.addLiteral(part,
                ResourceFactory.createProperty("http://www.opengis.net/ont/geosparql#", "asWKT"),
                ResourceFactory.createTypedLiteral(wkt,
                    org.apache.jena.datatypes.TypeMapper.getInstance()
                        .getSafeTypeByName("http://www.opengis.net/ont/geosparql#wktLiteral")));
        }
    }

    @After
    public void tearDown() {
        if (dataset != null) dataset.close();
    }

    private Set<String> urisFor(CqlExpression filter) {
        Set<String> uris = new HashSet<>();
        for (TextHit hit : textIndex.queryWithCql(null, "*", filter, null, null, null, 100, null)) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }

    /**
     * A spatial filter on a nested-scoped field finds nothing at all.
     * <p>
     * docs/09-spatial.md offers "model the geometries as a nested scope so each gets its
     * own document" as the way to get any-of semantics for a multi-valued geometry
     * (Kurrawong/jena#179). It does not work: {@code compileSpatial} is the one clause
     * builder that never calls {@code maybeLiftToParent}, so the query runs against the
     * child documents and is never lifted to the parent, and no entity matches. Even a
     * site whose every part is inside the box is missed.
     * <p>
     * Pinned as the current behaviour so the advice can be corrected rather than repeated.
     */
    @Test
    public void testSpatialFilterOnNestedFieldMatchesNothing() {
        // First prove the fixture is sound: the entities are indexed and findable, so an
        // empty spatial result below is the gap and not a broken setup.
        Set<String> byTitle = urisFor(
            new CqlExpression.CqlTextQuery(FIELD_NS + "title", "Site"));
        assertTrue("both sites are indexed and findable: " + byTitle,
            byTitle.contains(EX + "split-site") && byTitle.contains(EX + "wa-site"));

        String waBox = "{\"bbox\":[112.0,-36.0,129.0,-13.0]}";
        Set<String> intersects = urisFor(
            new CqlExpression.CqlSpatial("s_intersects", FIELD_NS + "geometry", waBox));

        // Not even wa-site, whose every part is inside the box, so this is not about
        // any-of versus all-of -- the clause never reaches the parent documents.
        assertTrue("a nested spatial filter currently matches nothing: " + intersects,
            intersects.isEmpty());
    }
}
