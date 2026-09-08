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
import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.implementation.datatype.WKTDatatype;
import org.apache.jena.query.text.cql.CqlExpression;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for spatial filtering via WKT literals and LatLonShape fields.
 */
public class TestSpatialFiltering {

    private static final String NS = "http://example.org/";
    private static final String FP = "urn:jena:lucene:field#";
    private static final String GEO = "http://www.opengis.net/ont/geosparql#";
    private static final Node SITE_CLASS = NodeFactory.createURI(NS + "Site");
    private static final Node TITLE_PRED = NodeFactory.createURI(NS + "title");
    private static final Node ASWKT_PRED = NodeFactory.createURI(GEO + "asWKT");

    private Dataset dataset;
    private ShaclTextIndexLucene textIndex;
    private ShaclIndexMapping mapping;

    @Before
    public void setUp() {
        FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
            true, true, false, false, false, true);

        // multiValued: a Feature may carry more than one geometry, and the relation
        // semantics for that case are exactly what several tests below pin.
        FieldDef locationField = new FieldDef("location", FieldType.LATLON, null,
            true, true, false, false, true, false);

        List<FieldOccurrence> rootOccurrences = Arrays.asList(
            occurrence(titleField, PathFactory.pathLink(TITLE_PRED), Collections.singleton(TITLE_PRED)),
            occurrence(locationField, PathFactory.pathLink(ASWKT_PRED), Collections.singleton(ASWKT_PRED)));

        IndexProfile siteProfile = new IndexProfile(
            NodeFactory.createURI(NS + "SiteShape"),
            Collections.singleton(SITE_CLASS),
            "uri", "docType",
            Arrays.asList(titleField, locationField),
            rootOccurrences,
            Collections.emptyList(),
            Collections.emptyList());

        mapping = new ShaclIndexMapping(Collections.singletonList(siteProfile));
        EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

        TextIndexConfig config = new TextIndexConfig(defn);
        config.setShaclMapping(mapping);
        config.setValueStored(true);

        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        textIndex = new ShaclTextIndexLucene(dir, config);

        Dataset baseDs = DatasetFactory.create();
        ShaclTextDocProducer producer = new ShaclTextDocProducer(
            baseDs.asDatasetGraph(), textIndex, mapping);

        dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);

        loadTestData();
    }

    private void loadTestData() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();

            // Brolga Ridge, QLD — EPSG:4326 (lat/lon order)
            addSite(model, "brolga-ridge", "Brolga Ridge Mine",
                "<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(-20.73 139.49)");

            // Spinifex Dome, SA — EPSG:4326
            addSite(model, "spinifex-dome", "Spinifex Dome",
                "<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(-30.43 136.88)");

            // Wattle Downs, WA — EPSG:4326
            addSite(model, "wattle-downs", "Wattle Downs Gold Mine",
                "<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(-32.77 116.35)");

            // Multipart site in WA — two disjoint footprints stored as a MultiPolygon
            addSite(model, "redgum-cluster", "Redgum Cluster Project",
                "<http://www.opengis.net/def/crs/EPSG/0/4326> MULTIPOLYGON(((-22.30 118.20, -22.30 118.30, -22.20 118.30, -22.20 118.20, -22.30 118.20)),((-22.45 118.45, -22.45 118.55, -22.35 118.55, -22.35 118.45, -22.45 118.45)))");

            // Kurrajong Valley, NSW — CRS84 (bare WKT, lon/lat order)
            addSite(model, "kurrajong-valley", "Kurrajong Valley Operations",
                "POINT(148.99 -33.47)");

            // Auckland, NZ — outside Australia bbox (should be excluded)
            addSite(model, "auckland", "Auckland Site",
                "<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(-36.85 174.76)");

            // A haul road as a LINESTRING, bare CRS84 (lon lat). Both endpoints are
            // outside the WA bbox on longitude; the segment passes straight through it.
            // Proves true segment intersection rather than vertex-in-box.
            addSite(model, "haul-road", "Haul Road",
                "LINESTRING(114.0 -25.0, 121.0 -25.0)");

            // Drill collars as a MULTIPOINT, one in WA and one in NSW.
            addSite(model, "drill-collars", "Drill Collars",
                "MULTIPOINT((116.5 -30.0), (149.5 -33.0))");

            // Rail spurs as a MULTILINESTRING, both in WA.
            addSite(model, "rail-spurs", "Rail Spurs",
                "MULTILINESTRING((116.0 -31.0, 116.5 -31.0), (117.0 -30.0, 117.5 -30.0))");

            // A project with a point in QLD and a polygon in WA.
            addSite(model, "project-mixed", "Mixed Project",
                "GEOMETRYCOLLECTION(POINT(145.0 -20.0), POLYGON((118.0 -23.0, 118.5 -23.0, 118.5 -22.5, 118.0 -22.5, 118.0 -23.0)))");

            // A closed ring expressed as a LINESTRING, not a POLYGON. A line has no
            // interior, so a bbox strictly inside the ring must not match it.
            addSite(model, "ring-as-line", "Ring As Line",
                "LINESTRING(116.0 -33.0, 116.7 -33.0, 116.7 -32.5, 116.0 -32.5, 116.0 -33.0)");
            // Sits inside the ring body of the donut query polygon used below, i.e.
            // within the outer ring but outside the hole. Wattle Downs sits in the hole.
            addSite(model, "ring-body", "Ring Body Site",
                "<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(-32.77 116.95)");

            // Genuinely multi-valued: two separate geo:asWKT triples on one entity, one
            // in WA and one in NSW. Exercises the relation semantics for a field with
            // more than one indexed shape.
            addSite(model, "twin-sites", "Twin Sites",
                "<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(-30.00 116.50)");
            model.addLiteral(
                ResourceFactory.createResource(NS + "twin-sites"),
                ResourceFactory.createProperty(GEO, "asWKT"),
                ResourceFactory.createTypedLiteral(
                    "<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(-33.00 149.50)",
                    org.apache.jena.datatypes.TypeMapper.getInstance()
                        .getSafeTypeByName(GEO + "wktLiteral")));

            // A large area used for s_contains: the indexed shape contains the query.
            addSite(model, "big-area", "Big Area",
                "<http://www.opengis.net/def/crs/EPSG/0/4326> POLYGON((-27.0 120.0, -27.0 125.0, -23.0 125.0, -23.0 120.0, -27.0 120.0))");

            // No geometry at all. Must be absent from every spatial relation, including
            // s_disjoint: GeoSPARQL's rewrite rule never binds a geometry for it, and
            // CQL2 makes a predicate with a NULL geometry evaluate to NULL.
            Resource noGeom = ResourceFactory.createResource(NS + "no-geometry");
            model.add(noGeom, RDF.type, ResourceFactory.createResource(NS + "Site"));
            model.add(noGeom, ResourceFactory.createProperty(NS, "title"), "No Geometry Site");

            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    private void addSite(Model model, String id, String title, String wkt) {
        Resource site = ResourceFactory.createResource(NS + id);
        model.add(site, RDF.type, ResourceFactory.createResource(NS + "Site"));
        model.add(site, ResourceFactory.createProperty(NS, "title"), title);
        model.addLiteral(site, ResourceFactory.createProperty(GEO, "asWKT"),
            ResourceFactory.createTypedLiteral(wkt,
                org.apache.jena.datatypes.TypeMapper.getInstance()
                    .getSafeTypeByName(GEO + "wktLiteral")));
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
    public void testBboxReturnsEntitiesWithinBounds() {
        // Australia bbox: [112, -44, 154, -10] (swLon, swLat, neLon, neLat)
        CqlExpression filter = new CqlExpression.CqlSpatial(
            "s_intersects", FP + "location", "{\"bbox\":[112,-44,154,-10]}");

        List<TextHit> results = textIndex.queryWithCql(
            null, "*", filter, null, null, null, 100, null);

        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }

        // 4 Australian sites should match
        assertTrue("Brolga Ridge should be in results", uris.contains(NS + "brolga-ridge"));
        assertTrue("Spinifex Dome should be in results", uris.contains(NS + "spinifex-dome"));
        assertTrue("Wattle Downs should be in results", uris.contains(NS + "wattle-downs"));
        assertTrue("Kurrajong Valley should be in results", uris.contains(NS + "kurrajong-valley"));
        // Auckland is outside Australia
        assertFalse("Auckland should NOT be in results", uris.contains(NS + "auckland"));
    }

    @Test
    public void testBboxExcludesEntitiesOutsideBounds() {
        // Small bbox around WA only: [115, -34, 120, -20]
        CqlExpression filter = new CqlExpression.CqlSpatial(
            "s_intersects", FP + "location", "{\"bbox\":[115,-34,120,-20]}");

        List<TextHit> results = textIndex.queryWithCql(
            null, "*", filter, null, null, null, 100, null);

        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }

        // Only Wattle Downs is in WA bbox
        assertTrue("Wattle Downs should be in results", uris.contains(NS + "wattle-downs"));
        assertTrue("Redgum Cluster should be in results", uris.contains(NS + "redgum-cluster"));
        assertFalse("Brolga Ridge should NOT be in WA bbox", uris.contains(NS + "brolga-ridge"));
        assertFalse("Spinifex Dome should NOT be in WA bbox", uris.contains(NS + "spinifex-dome"));
    }

    @Test
    public void testMultiPolygonMatchesAnyMemberPolygon() {
        CqlExpression filter = new CqlExpression.CqlSpatial(
            "s_intersects", FP + "location", "{\"bbox\":[118.24,-22.28,118.28,-22.22]}");

        List<TextHit> results = textIndex.queryWithCql(
            null, "*", filter, null, null, null, 100, null);

        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }

        assertTrue("Redgum Cluster should match when the bbox intersects one member polygon",
            uris.contains(NS + "redgum-cluster"));
        assertFalse("Wattle Downs should NOT be in the Redgum bbox", uris.contains(NS + "wattle-downs"));
    }

    @Test
    public void testCombinedTextAndSpatialFilter() {
        // Text search for "mine" + spatial filter for Australia
        CqlExpression filter = new CqlExpression.CqlSpatial(
            "s_intersects", FP + "location", "{\"bbox\":[112,-44,154,-10]}");

        List<TextHit> results = textIndex.queryWithCql(
            null, "mine", filter, null, null, null, 100, null);

        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }

        // "Brolga Ridge Mine" and "Wattle Downs Gold Mine" contain "mine"
        assertTrue("Brolga Ridge Mine should match", uris.contains(NS + "brolga-ridge"));
        assertTrue("Wattle Downs Gold Mine should match", uris.contains(NS + "wattle-downs"));
        // "Spinifex Dome" doesn't contain "mine"
        assertFalse("Spinifex Dome should NOT match text 'mine'", uris.contains(NS + "spinifex-dome"));
    }

    @Test
    public void testCrs84AxisSwap() {
        // Kurrajong Valley was indexed with bare WKT (CRS84: lon/lat order).
        // Verify it's findable with a bbox around its location.
        // Kurrajong is at ~(-33.47, 148.99) in lat/lon
        CqlExpression filter = new CqlExpression.CqlSpatial(
            "s_intersects", FP + "location", "{\"bbox\":[148,-34,150,-33]}");

        List<TextHit> results = textIndex.queryWithCql(
            null, "*", filter, null, null, null, 100, null);

        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }

        assertTrue("Kurrajong Valley (CRS84) should be found in its bbox", uris.contains(NS + "kurrajong-valley"));
    }

    @Test
    public void testEpsg4326NoSwap() {
        // Brolga Ridge was indexed with EPSG:4326 (lat/lon order).
        // Verify it's at the correct location: lat=-20.73, lon=139.49
        CqlExpression filter = new CqlExpression.CqlSpatial(
            "s_intersects", FP + "location", "{\"bbox\":[139,-21,140,-20]}");

        List<TextHit> results = textIndex.queryWithCql(
            null, "*", filter, null, null, null, 100, null);

        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }

        assertTrue("Brolga Ridge (EPSG:4326) should be found", uris.contains(NS + "brolga-ridge"));
    }

    @Test
    public void testUnsupportedSpatialOpThrows() {
        // An operator we cannot push to Lucene must fail loudly. Dropping it silently
        // widens the result set, which is a wrong answer rather than a missing one.
        CqlExpression filter = new CqlExpression.CqlSpatial(
            "s_touches", FP + "location", "{\"bbox\":[112,-44,154,-10]}");

        TextIndexException e = assertThrows(TextIndexException.class, () ->
            textIndex.queryWithCql(null, "*", filter, null, null, null, 100, null));

        assertTrue("Message should name the offending operator: " + e.getMessage(),
            e.getMessage().contains("s_touches"));
    }

    @Test
    public void testUnsupportedQueryGeometryThrows() {
        // Second silent-drop path: a supported operator with a query geometry the
        // compiler does not understand also produced a dropped residual.
        CqlExpression filter = new CqlExpression.CqlSpatial(
            "s_intersects", FP + "location",
            "{\"type\":\"Circle\",\"coordinates\":[116.35,-32.77],\"radius\":1000}");

        TextIndexException e = assertThrows(TextIndexException.class, () ->
            textIndex.queryWithCql(null, "*", filter, null, null, null, 100, null));

        assertTrue("Message should name the offending geometry type: " + e.getMessage(),
            e.getMessage().contains("Circle"));
    }

    @Test
    public void testUnsupportedSpatialOpInsideAndThrows() {
        // The AND fold keeps pushable siblings and drops the residual, so this used to
        // return the title match unfiltered by geometry.
        CqlExpression filter = new CqlExpression.CqlAnd(Arrays.asList(
            new CqlExpression.CqlComparison("=", FP + "title", "Wattle Downs Gold Mine"),
            new CqlExpression.CqlSpatial("s_touches", FP + "location",
                "{\"bbox\":[112,-44,154,-10]}")));

        assertThrows(TextIndexException.class, () ->
            textIndex.queryWithCql(null, "*", filter, null, null, null, 100, null));
    }

    @Test
    public void testUnsupportedSpatialOpInsideOrThrows() {
        // The OR fold abandons the whole disjunction when any branch is unpushable, so
        // this used to drop every arm of the OR, not just the spatial one.
        CqlExpression filter = new CqlExpression.CqlOr(Arrays.asList(
            new CqlExpression.CqlComparison("=", FP + "title", "Wattle Downs Gold Mine"),
            new CqlExpression.CqlSpatial("s_touches", FP + "location",
                "{\"bbox\":[112,-44,154,-10]}")));

        assertThrows(TextIndexException.class, () ->
            textIndex.queryWithCql(null, "*", filter, null, null, null, 100, null));
    }

    @Test
    public void testParseWktToLuceneFieldsPoint() {
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location",
                "<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(-33.87 151.21)", true);

        assertFalse("Should produce fields for a point", fields.isEmpty());
        // Should have LatLonShape fields + LatLonPoint + StoredField
        boolean hasStored = false;
        for (org.apache.lucene.index.IndexableField f : fields) {
            if (f instanceof org.apache.lucene.document.StoredField) {
                hasStored = true;
            }
        }
        assertTrue("Should include stored field", hasStored);
    }

    @Test
    public void testParseWktToLuceneFieldsPolygon() {
        String wkt = "<http://www.opengis.net/def/crs/EPSG/0/4326> POLYGON((-22.8 118.0, -22.8 119.2, -21.8 119.2, -21.8 118.0, -22.8 118.0))";
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location", wkt, false);

        assertFalse("Should produce fields for a polygon", fields.isEmpty());
    }

    @Test
    public void testParseWktToLuceneFieldsMultiPolygon() {
        String wkt = "<http://www.opengis.net/def/crs/EPSG/0/4326> MULTIPOLYGON(((-22.30 118.20, -22.30 118.30, -22.20 118.30, -22.20 118.20, -22.30 118.20)),((-22.45 118.45, -22.45 118.55, -22.35 118.55, -22.35 118.45, -22.45 118.45)))";
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location", wkt, false);

        assertFalse("Should produce fields for a multipolygon", fields.isEmpty());
    }

    @Test
    public void testInvalidWktProducesNoFields() {
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location", "NOT_WKT", false);

        assertTrue("Invalid WKT should produce empty fields", fields.isEmpty());
    }

    // ------------------------------------------------------------------
    // GDA2020 / GDA94 axis order
    // ------------------------------------------------------------------

    @Test
    public void testGda2020PointIsIndexed() {
        // EPSG:7844 (GDA2020) is a lat/lon CRS, like EPSG:4326. Its coordinates must be
        // swapped to x=lon, y=lat before indexing. Treating it as already-WGS84 skips
        // that, and Lucene then rejects a longitude presented as a latitude, so the
        // value is silently dropped and the entity becomes unfindable.
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location",
                "<http://www.opengis.net/def/crs/EPSG/0/7844> POINT(-31.20 121.66)", false);
        assertFalse("A GDA2020 point must index", fields.isEmpty());
    }

    @Test
    public void testGda94PointIsIndexed() {
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location",
                "<http://www.opengis.net/def/crs/EPSG/0/4283> POINT(-30.75 121.47)", false);
        assertFalse("A GDA94 point must index", fields.isEmpty());
    }

    @Test
    public void testGda2020PolygonIsIndexed() {
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location",
                "<http://www.opengis.net/def/crs/EPSG/0/7844> POLYGON((-31.00 121.20, "
                + "-31.00 121.80, -30.40 121.80, -30.40 121.20, -31.00 121.20))", false);
        assertFalse("A GDA2020 polygon must index", fields.isEmpty());
    }

    @Test
    public void testGda2020AndCrs84AgreeOnLocation() {
        // The same place written as GDA2020 (lat/lon, prefixed) and as bare CRS84
        // (lon/lat) must land in the same spot. EPSG publishes the GDA2020 -> WGS 84
        // transformation as a null transformation, so the coordinates pass through.
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            addSite(model, "gda2020-site", "GDA2020 Site",
                "<http://www.opengis.net/def/crs/EPSG/0/7844> POINT(-31.20 121.66)");
            addSite(model, "crs84-site", "CRS84 Site", "POINT(121.66 -31.20)");
            dataset.commit();
        } finally {
            dataset.end();
        }

        CqlExpression filter = new CqlExpression.CqlSpatial("s_intersects", FP + "location",
            "{\"bbox\":[121.64,-31.22,121.68,-31.18]}");
        List<TextHit> results = textIndex.queryWithCql(
            null, "*", filter, null, null, null, 100, null);
        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }

        assertTrue("The GDA2020 site should be found", uris.contains(NS + "gda2020-site"));
        assertTrue("The CRS84 site should be found", uris.contains(NS + "crs84-site"));
    }

    // ------------------------------------------------------------------
    // Geometry types beyond Point/Polygon/MultiPolygon
    // ------------------------------------------------------------------

    private Set<String> urisFor(CqlExpression filter) {
        List<TextHit> results = textIndex.queryWithCql(
            null, "*", filter, null, null, null, 100, null);
        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }

    private static CqlExpression bbox(double swLon, double swLat, double neLon, double neLat) {
        return new CqlExpression.CqlSpatial("s_intersects", FP + "location",
            "{\"bbox\":[" + swLon + "," + swLat + "," + neLon + "," + neLat + "]}");
    }

    @Test
    public void testLineStringSegmentCrossingBboxMatches() {
        // WA box on longitude 115..120; the haul road runs 114 -> 121 at latitude -25,
        // so neither endpoint is inside but the segment crosses.
        Set<String> uris = urisFor(bbox(115, -34, 120, -20));
        assertTrue("Haul road segment crosses the box and should match", uris.contains(NS + "haul-road"));
    }

    @Test
    public void testMultiPointMatchesEitherMember() {
        assertTrue("WA collar should match the WA box",
            urisFor(bbox(115, -34, 120, -20)).contains(NS + "drill-collars"));
        assertTrue("NSW collar should match the NSW box",
            urisFor(bbox(148, -35, 151, -31)).contains(NS + "drill-collars"));
    }

    @Test
    public void testMultiLineStringIsIndexed() {
        assertTrue("Rail spurs should match a box covering them",
            urisFor(bbox(115, -32, 118, -29)).contains(NS + "rail-spurs"));
    }

    @Test
    public void testGeometryCollectionMatchesEitherMember() {
        assertTrue("QLD point member should match the QLD box",
            urisFor(bbox(144, -21, 146, -19)).contains(NS + "project-mixed"));
        assertTrue("WA polygon member should match the WA box",
            urisFor(bbox(117.9, -23.1, 118.6, -22.4)).contains(NS + "project-mixed"));
    }

    @Test
    public void testClosedLineStringHasNoInterior() {
        // A ring drawn as a LINESTRING is a line, not an area. A box strictly inside it
        // touches none of its segments, so it must not match.
        Set<String> uris = urisFor(bbox(116.3, -32.8, 116.4, -32.7));
        assertFalse("A box inside a closed LINESTRING must not match it",
            uris.contains(NS + "ring-as-line"));
        // ... but a box straddling one of its segments must.
        assertTrue("A box crossing the ring's edge should match",
            urisFor(bbox(116.6, -33.1, 116.8, -32.9)).contains(NS + "ring-as-line"));
    }

    @Test
    public void testParseWktToLuceneFieldsLineString() {
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location",
                "LINESTRING(114.0 -25.0, 121.0 -25.0)", true);

        assertFalse("Should produce fields for a linestring", fields.isEmpty());
        boolean hasStored = false;
        for (org.apache.lucene.index.IndexableField f : fields) {
            if (f instanceof org.apache.lucene.document.StoredField) {
                hasStored = true;
            }
        }
        assertTrue("A supported geometry must still be stored when requested", hasStored);
    }

    @Test
    public void testParseWktToLuceneFieldsMultiLineString() {
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location",
                "MULTILINESTRING((116.0 -31.0, 116.5 -31.0), (117.0 -30.0, 117.5 -30.0))", false);
        assertFalse("Should produce fields for a multilinestring", fields.isEmpty());
    }

    @Test
    public void testParseWktToLuceneFieldsMultiPoint() {
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location",
                "MULTIPOINT((116.5 -30.0), (149.5 -33.0))", false);
        assertFalse("Should produce fields for a multipoint", fields.isEmpty());
    }

    @Test
    public void testParseWktToLuceneFieldsGeometryCollection() {
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location",
                "GEOMETRYCOLLECTION(POINT(145.0 -20.0), LINESTRING(114.0 -25.0, 121.0 -25.0), "
                + "POLYGON((118.0 -23.0, 118.5 -23.0, 118.5 -22.5, 118.0 -22.5, 118.0 -23.0)))", false);
        assertFalse("Should produce fields for a geometry collection", fields.isEmpty());
    }

    @Test
    public void testDegenerateLineStringStillIndexes() {
        // A line whose points are all identical is degenerate but Lucene accepts it,
        // indexing it as a zero-length shape. Pinned so the behaviour is a decision
        // rather than an accident.
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location",
                "LINESTRING(116.0 -31.0, 116.0 -31.0)", false);
        assertFalse("Lucene accepts a zero-length line", fields.isEmpty());
    }

    @Test
    public void testSinglePointLineStringProducesNoFields() {
        // JTS rejects a one-point LINESTRING outright. The indexer must downgrade that
        // to a warning and skip the value, not fail the enclosing transaction.
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location",
                "LINESTRING(116.0 -31.0)", false);
        assertTrue("Invalid WKT should produce no fields and no exception", fields.isEmpty());
    }

    @Test
    public void testAntimeridianLineStringIsNotSplit() {
        // Lucene does not split geometries at the antimeridian. A line written from
        // 179 to -179 is read as spanning the long way round the globe rather than the
        // 2-degree short hop. Pinned here so the limitation is visible and documented.
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location",
                "LINESTRING(179.0 -17.0, -179.0 -17.0)", false);
        assertFalse("An antimeridian-spanning line still indexes", fields.isEmpty());
    }

    @Test
    public void testTwoPointLineStringIsIndexed() {
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseWktToLuceneFields("location",
                "LINESTRING(116.0 -31.0, 116.5 -31.5)", false);
        assertFalse("A minimal two-point line is valid and should index", fields.isEmpty());
    }

    // ------------------------------------------------------------------
    // Interior rings (holes) in a GeoJSON query polygon
    // ------------------------------------------------------------------

    /**
     * A donut centred on Wattle Downs: outer ring roughly +/-1 degree, hole roughly
     * +/-0.2 degrees. GeoJSON rings are [lon, lat]; ring 0 is the shell, rings 1..n
     * are holes.
     */
    private static final String DONUT_AROUND_WATTLE_DOWNS =
        "{\"type\":\"Polygon\",\"coordinates\":["
        + "[[115.35,-33.77],[117.35,-33.77],[117.35,-31.77],[115.35,-31.77],[115.35,-33.77]],"
        + "[[116.15,-32.97],[116.55,-32.97],[116.55,-32.57],[116.15,-32.57],[116.15,-32.97]]"
        + "]}";

    @Test
    public void testQueryPolygonHoleExcludesEntityInsideHole() {
        CqlExpression filter = new CqlExpression.CqlSpatial(
            "s_intersects", FP + "location", DONUT_AROUND_WATTLE_DOWNS);

        List<TextHit> results = textIndex.queryWithCql(
            null, "*", filter, null, null, null, 100, null);
        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }

        assertFalse("Wattle Downs sits in the hole and must not match",
            uris.contains(NS + "wattle-downs"));
        assertTrue("A site in the ring body must still match",
            uris.contains(NS + "ring-body"));
    }

    @Test
    public void testQueryPolygonWithTwoHoles() {
        // Two holes: one over Wattle Downs, one over the ring-body site. Both are excluded,
        // proving rings 1..n are all applied rather than only the first.
        String twoHoles =
            "{\"type\":\"Polygon\",\"coordinates\":["
            + "[[115.35,-33.77],[117.35,-33.77],[117.35,-31.77],[115.35,-31.77],[115.35,-33.77]],"
            + "[[116.15,-32.97],[116.55,-32.97],[116.55,-32.57],[116.15,-32.57],[116.15,-32.97]],"
            + "[[116.75,-32.97],[117.15,-32.97],[117.15,-32.57],[116.75,-32.57],[116.75,-32.97]]"
            + "]}";
        CqlExpression filter = new CqlExpression.CqlSpatial(
            "s_intersects", FP + "location", twoHoles);

        List<TextHit> results = textIndex.queryWithCql(
            null, "*", filter, null, null, null, 100, null);
        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }

        assertFalse("Wattle Downs sits in the first hole", uris.contains(NS + "wattle-downs"));
        assertFalse("Ring-body site sits in the second hole", uris.contains(NS + "ring-body"));
    }

    // ------------------------------------------------------------------
    // Relations beyond INTERSECTS, and the full GeoJSON query geometry set
    // ------------------------------------------------------------------

    private Set<String> urisForOp(String op, String geometryJson) {
        CqlExpression filter = new CqlExpression.CqlSpatial(op, FP + "location", geometryJson);
        List<TextHit> results = textIndex.queryWithCql(
            null, "*", filter, null, null, null, 100, null);
        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }

    private static String bboxJson(double swLon, double swLat, double neLon, double neLat) {
        return "{\"bbox\":[" + swLon + "," + swLat + "," + neLon + "," + neLat + "]}";
    }

    @Test
    public void testWithinMatchesShapeInsideQueryGeometry() {
        // Wattle Downs (116.35, -32.77) is inside a generous WA box.
        Set<String> uris = urisForOp("s_within", bboxJson(115, -34, 118, -31));
        assertTrue("Wattle Downs is within the box", uris.contains(NS + "wattle-downs"));
        assertFalse("Kurrajong Valley is in NSW, not within the WA box",
            uris.contains(NS + "kurrajong-valley"));
    }

    @Test
    public void testDisjointMatchesShapesOutsideQueryGeometry() {
        Set<String> uris = urisForOp("s_disjoint", bboxJson(115, -34, 118, -31));
        assertFalse("Wattle Downs is inside the box, so not disjoint from it",
            uris.contains(NS + "wattle-downs"));
        assertTrue("Kurrajong Valley is far away and disjoint", uris.contains(NS + "kurrajong-valley"));
    }

    @Test
    public void testContainsMatchesIndexedShapeContainingQueryGeometry() {
        // big-area spans lat -27..-23, lon 120..125. The query box sits well inside it.
        Set<String> uris = urisForOp("s_contains", bboxJson(121.0, -26.0, 122.0, -25.0));
        assertTrue("The indexed polygon contains the query box", uris.contains(NS + "big-area"));
        assertFalse("A point cannot contain a box", uris.contains(NS + "wattle-downs"));
    }

    // --- spec-derived semantics -------------------------------------------------

    @Test
    public void testWithinOnMultiValuedFieldRequiresEveryShape() {
        // twin-sites has two indexed points, one in WA and one in NSW. Under DE-9IM a
        // feature's geometry is one collection, so it is within the WA box only if all
        // of it is. Lucene's per-document WITHIN agrees.
        String waBox = bboxJson(115, -34, 118, -28);
        assertTrue("It intersects the WA box via its WA point",
            urisForOp("s_intersects", waBox).contains(NS + "twin-sites"));
        assertFalse("But it is not wholly within the WA box",
            urisForOp("s_within", waBox).contains(NS + "twin-sites"));
    }

    @Test
    public void testDisjointOnMultiValuedFieldRequiresEveryShape() {
        // Disjoint from a box only if every one of its shapes is.
        String waBox = bboxJson(115, -34, 118, -28);
        assertFalse("One of its points is inside the box, so it is not disjoint",
            urisForOp("s_disjoint", waBox).contains(NS + "twin-sites"));
    }

    @Test
    public void testGeometryLessEntityMatchesNoRelation() {
        // GeoSPARQL's query rewrite never binds a geometry for such a feature and CQL2
        // makes a NULL geometry yield a NULL predicate, so it is in neither result.
        String box = bboxJson(115, -34, 118, -31);
        assertFalse("Absent from intersects",
            urisForOp("s_intersects", box).contains(NS + "no-geometry"));
        assertFalse("Absent from disjoint too, which is the surprising half",
            urisForOp("s_disjoint", box).contains(NS + "no-geometry"));
        assertFalse("Absent from within", urisForOp("s_within", box).contains(NS + "no-geometry"));
    }

    @Test
    public void testBoundaryContactIsNotWithin() {
        // DE-9IM sfWithin (T*F**F***) needs a non-empty interior-interior intersection,
        // so a point exactly on the query boundary is NOT within. Lucene agrees, which
        // is worth pinning because it is easy to assume the opposite.
        String edgeBox = bboxJson(116.35, -34.0, 118.0, -31.0);  // west edge on Wattle Downs's lon
        assertFalse("A point on the query boundary is not within it",
            urisForOp("s_within", edgeBox).contains(NS + "wattle-downs"));

        // Move the edge west so the point is strictly inside, and it matches.
        String insetBox = bboxJson(116.30, -34.0, 118.0, -31.0);
        assertTrue("A point strictly inside is within",
            urisForOp("s_within", insetBox).contains(NS + "wattle-downs"));
    }

    // --- GeoJSON query geometry types ------------------------------------------

    @Test
    public void testQueryGeometryPoint() {
        // big-area spans lat -27..-23, lon 120..125.
        String point = "{\"type\":\"Point\",\"coordinates\":[122.0,-25.0]}";
        assertTrue("A point inside the indexed polygon should match",
            urisForOp("s_intersects", point).contains(NS + "big-area"));
    }

    @Test
    public void testQueryGeometryLineString() {
        String line = "{\"type\":\"LineString\",\"coordinates\":[[121.0,-25.0],[124.0,-25.0]]}";
        assertTrue("A line crossing the indexed polygon should match",
            urisForOp("s_intersects", line).contains(NS + "big-area"));
    }

    @Test
    public void testQueryGeometryMultiPoint() {
        // One point inside big-area, one inside the redgum-cluster multipolygon.
        String multiPoint = "{\"type\":\"MultiPoint\",\"coordinates\":[[122.0,-25.0],[118.25,-22.25]]}";
        Set<String> uris = urisForOp("s_intersects", multiPoint);
        assertTrue("Should match big-area", uris.contains(NS + "big-area"));
        assertTrue("Should match redgum-cluster", uris.contains(NS + "redgum-cluster"));
    }

    @Test
    public void testQueryGeometryMultiLineString() {
        String multiLine = "{\"type\":\"MultiLineString\",\"coordinates\":"
            + "[[[121.0,-25.0],[124.0,-25.0]],[[118.21,-22.25],[118.29,-22.25]]]}";
        Set<String> uris = urisForOp("s_intersects", multiLine);
        assertTrue("First line crosses big-area", uris.contains(NS + "big-area"));
        assertTrue("Second line crosses redgum-cluster", uris.contains(NS + "redgum-cluster"));
    }

    // ------------------------------------------------------------------
    // The datatype decides the serialisation; the lexical form is a fallback
    // ------------------------------------------------------------------

    private static final String WKT_DT = "http://www.opengis.net/ont/geosparql#wktLiteral";
    private static final String GEOJSON_DT = "http://www.opengis.net/ont/geosparql#geoJSONLiteral";
    private static final String GML_DT = "http://www.opengis.net/ont/geosparql#gmlLiteral";
    private static final String STRING_DT = "http://www.w3.org/2001/XMLSchema#string";

    private static final String A_POINT_WKT = "POINT(121.66 -31.20)";
    private static final String A_POINT_GEOJSON = "{\"type\":\"Point\",\"coordinates\":[121.66,-31.20]}";

    @Test
    public void testDeclaredDatatypeDecidesTheSerialisation() {
        assertFalse("geo:wktLiteral is read as WKT",
            ShaclTextIndexLucene.parseGeometryToLuceneFields(
                "location", A_POINT_WKT, WKT_DT, false).isEmpty());
        assertFalse("geo:geoJSONLiteral is read as GeoJSON",
            ShaclTextIndexLucene.parseGeometryToLuceneFields(
                "location", A_POINT_GEOJSON, GEOJSON_DT, false).isEmpty());
    }

    @Test
    public void testDatatypeContradictingTheLexicalFormIsNotIndexed() {
        // The declaration wins. Sniffing would silently index a value as the opposite
        // serialisation from the one the data says it is, which hides a data error.
        assertTrue("JSON typed as geo:wktLiteral is a data error, not GeoJSON to sniff",
            ShaclTextIndexLucene.parseGeometryToLuceneFields(
                "location-a", A_POINT_GEOJSON, WKT_DT, false).isEmpty());
        assertTrue("WKT typed as geo:geoJSONLiteral is a data error, not WKT to sniff",
            ShaclTextIndexLucene.parseGeometryToLuceneFields(
                "location-b", A_POINT_WKT, GEOJSON_DT, false).isEmpty());
    }

    /**
     * Collect what {@link ShaclTextIndexLucene} logs while {@code body} runs.
     * <p>
     * Needed because two of the datatype branches change only the message. GML was always
     * left unindexed; what changed is that it now says so, instead of reporting a WKT
     * parse failure.
     */
    private static List<String> captureLogs(Runnable body) {
        org.apache.logging.log4j.core.Logger logger =
            (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager
                .getLogger(ShaclTextIndexLucene.class);
        List<String> messages = Collections.synchronizedList(new java.util.ArrayList<>());
        org.apache.logging.log4j.core.appender.AbstractAppender appender =
            new org.apache.logging.log4j.core.appender.AbstractAppender(
                    "capture-" + System.nanoTime(), null, null, true, null) {
                @Override
                public void append(org.apache.logging.log4j.core.LogEvent event) {
                    messages.add(event.getMessage().getFormattedMessage());
                }
            };
        appender.start();
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
        return messages;
    }

    private static boolean anyMentions(List<String> messages, String... needles) {
        for (String m : messages) {
            boolean all = true;
            for (String n : needles) {
                if (!m.contains(n)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testGmlIsDeclinedByNameRatherThanAsAWktParseFailure() {
        // GML went unindexed before this too -- it starts with '<', so the WKT reader took
        // it and failed. What changed is the report: "Failed to parse WKT" named neither
        // the cause nor the fix. So the message is the assertion here.
        String gml = "<gml:Point srsName=\"urn:ogc:def:crs:EPSG::4326\">"
            + "<gml:pos>-31.20 121.66</gml:pos></gml:Point>";
        List<String> logs = captureLogs(() ->
            assertTrue("GML is not supported and is not indexed",
                ShaclTextIndexLucene.parseGeometryToLuceneFields("location-gml", gml, GML_DT, false)
                    .isEmpty()));

        assertTrue("the warning should name GML and the field: " + logs,
            anyMentions(logs, "GML", "location-gml"));
        assertFalse("and should not blame WKT parsing: " + logs,
            anyMentions(logs, "Failed to parse WKT"));
    }

    @Test
    public void testUntypedGeometryIsReportedOncePerField() {
        // The value indexes, but it is invisible to geof: functions, so it is worth saying
        // -- once, not once per row.
        List<String> logs = captureLogs(() -> {
            for (int i = 0; i < 5; i++) {
                ShaclTextIndexLucene.parseGeometryToLuceneFields(
                    "location-once", A_POINT_WKT, STRING_DT, false);
            }
        });
        long mentions = logs.stream().filter(m -> m.contains("location-once")).count();
        assertEquals("five untyped values, one warning", 1, mentions);
    }

    @Test
    public void testUntypedGeometryStillIndexesBySniffing() {
        // GIS exports routinely land as xsd:string. Refusing these would leave the field
        // silently empty, so they are still indexed -- both serialisations.
        assertFalse("xsd:string WKT still indexes",
            ShaclTextIndexLucene.parseGeometryToLuceneFields(
                "location-c", A_POINT_WKT, STRING_DT, false).isEmpty());
        assertFalse("xsd:string GeoJSON still indexes",
            ShaclTextIndexLucene.parseGeometryToLuceneFields(
                "location-d", A_POINT_GEOJSON, STRING_DT, false).isEmpty());
        // and an external source, which has no datatype at all, is unaffected
        assertFalse("a CSV column value has no datatype and still indexes",
            ShaclTextIndexLucene.parseGeometryToLuceneFields(
                "location-e", A_POINT_WKT, false).isEmpty());
    }

    // ------------------------------------------------------------------
    // Coincident geometry, and the two-pass recipe the docs recommend
    // ------------------------------------------------------------------

    @Test
    public void testCoincidentGeometryIsNotWithinOrContains() {
        // lucene-core's WITHIN and CONTAINS are not boundary-neutral: an indexed geometry
        // identical to the query geometry satisfies neither, though DE-9IM says both hold.
        // A shared edge is read as boundary contact, not containment. Same root cause as
        // s_equals being unavailable, so the "use two passes" advice in 09-spatial.md
        // depends on this staying true.
        String wa = "{\"type\":\"Polygon\",\"coordinates\":[[[112.0,-36.0],[129.0,-36.0],"
            + "[129.0,-13.0],[112.0,-13.0],[112.0,-36.0]]]}";

        dataset.begin(ReadWrite.WRITE);
        try {
            addSite(dataset.getDefaultModel(), "exact-box", "Exact Box",
                "POLYGON((112.0 -36.0, 129.0 -36.0, 129.0 -13.0, 112.0 -13.0, 112.0 -36.0))");
            dataset.commit();
        } finally {
            dataset.end();
        }

        assertTrue("s_intersects matches a coincident geometry",
            urisForOp("s_intersects", wa).contains(NS + "exact-box"));
        assertFalse("s_disjoint does not, which agrees with s_intersects",
            urisForOp("s_disjoint", wa).contains(NS + "exact-box"));
        assertFalse("s_within does NOT match a coincident geometry, though DE-9IM says it should",
            urisForOp("s_within", wa).contains(NS + "exact-box"));
        assertFalse("s_contains does NOT match a coincident geometry either",
            urisForOp("s_contains", wa).contains(NS + "exact-box"));
    }

    /**
     * The DE-9IM patterns 09-spatial.md tells people to use for pass two.
     * <p>
     * Advice in a document that nothing exercises goes stale silently. This pins the
     * three patterns in that table, and the one function in GeoSPARQL's simple-features
     * set that cannot be used in their place.
     */
    @Test
    public void testJenaSfEqualsMissesIdenticalPoints() throws Exception {
        GeometryWrapper point = WKTDatatype.INSTANCE.parse("POINT(1 1)");
        GeometryWrapper samePoint = WKTDatatype.INSTANCE.parse("POINT(1 1)");
        GeometryWrapper poly = WKTDatatype.INSTANCE.parse("POLYGON((0 0,2 0,2 2,0 2,0 0))");
        GeometryWrapper samePoly = WKTDatatype.INSTANCE.parse("POLYGON((0 0,2 0,2 2,0 2,0 0))");
        GeometryWrapper inner = WKTDatatype.INSTANCE.parse("POLYGON((0.5 0.5,1.5 0.5,1.5 1.5,0.5 1.5,0.5 0.5))");

        // equals
        assertTrue("relate equals matches identical points",
            point.relate(samePoint, "T*F**FFF*"));
        assertTrue("relate equals matches identical polygons",
            poly.relate(samePoly, "T*F**FFF*"));
        assertFalse("relate equals rejects different polygons",
            poly.relate(inner, "T*F**FFF*"));

        // covers / covered by
        assertTrue("relate covers", poly.relate(inner, "T*****FF*"));
        assertFalse("covers is directional", inner.relate(poly, "T*****FF*"));
        assertTrue("relate covered by", inner.relate(poly, "T*F**F***"));

        // Jena's SfEqualsFF uses the fixed pattern TFFFTFFFT, which requires
        // boundary-to-boundary intersection. A point has no boundary, so it never matches.
        // If this assertion starts failing, upstream has fixed it and 09-spatial.md should
        // stop steering people away from geof:sfEquals.
        assertFalse("jena-geosparql's sfEquals pattern misses identical points",
            point.relate(samePoint, "TFFFTFFFT"));
        assertTrue("but it is right for polygons, which do have boundaries",
            poly.relate(samePoly, "TFFFTFFFT"));
    }

    /**
     * A mistyped {@code geof:relate} pattern of the right length silently matches nothing.
     * <p>
     * Wrong length raises, which is fine. But nine characters that are not DE-9IM symbols,
     * or one transposed symbol, return {@code false} for every pair -- a filter that
     * quietly excludes everything. That is the reason 09-spatial.md steers people to the
     * named {@code sf*} functions wherever one exists, and hand-written patterns only for
     * equals and covers.
     */
    @Test
    public void testMistypedRelatePatternIsSilentlyFalse() throws Exception {
        GeometryWrapper a = WKTDatatype.INSTANCE.parse("POINT(1 1)");
        GeometryWrapper b = WKTDatatype.INSTANCE.parse("POINT(1 1)");

        assertTrue("the correct equals pattern matches", a.relate(b, "T*F**FFF*"));
        assertFalse("nine characters of nonsense are simply false", a.relate(b, "NONSENSE!"));
        assertFalse("and so is one transposed symbol", a.relate(b, "T*F**FFG*"));

        // A length error does raise, so only same-length typos are dangerous.
        assertThrows(IllegalArgumentException.class, () -> a.relate(b, "T*F"));
        assertThrows(IllegalArgumentException.class, () -> a.relate(b, "T*F**FFF*X"));
    }

    /**
     * The relate patterns 09-spatial.md gives, against the named functions they replace.
     * <p>
     * {@code within} and {@code contains} are equivalent to their patterns, so callers
     * should use the named function. {@code equals} is the exception, and the safer
     * substitute is the two named functions rather than the pattern, since a mistyped
     * pattern is silently false.
     */
    @Test
    public void testEqualsEquivalences() throws Exception {
        String[][] pairs = {
            { "POINT(1 1)", "POINT(1 1)", "true" },
            { "POLYGON((0 0,2 0,2 2,0 2,0 0))", "POLYGON((0 0,2 0,2 2,0 2,0 0))", "true" },
            { "LINESTRING(0 0,2 2)", "LINESTRING(0 0,2 2)", "true" },
            { "POLYGON((0.5 0.5,1.5 0.5,1.5 1.5,0.5 1.5,0.5 0.5))",
              "POLYGON((0 0,2 0,2 2,0 2,0 0))", "false" },
            { "POINT(9 9)", "POINT(1 1)", "false" },
        };
        for (String[] pair : pairs) {
            GeometryWrapper a = WKTDatatype.INSTANCE.parse(pair[0]);
            GeometryWrapper b = WKTDatatype.INSTANCE.parse(pair[1]);
            boolean expected = Boolean.parseBoolean(pair[2]);
            String where = pair[0] + " vs " + pair[1];

            // equals == within AND contains, and that identity holds here, unlike in Lucene
            boolean substitute = a.getXYGeometry().within(b.getXYGeometry())
                && a.getXYGeometry().contains(b.getXYGeometry());
            assertEquals("within AND contains is equals for " + where, expected, substitute);
            assertEquals("and agrees with the pattern for " + where,
                expected, a.relate(b, "T*F**FFF*"));

            // the named within/contains agree with the patterns the doc lists for them
            assertEquals("sfWithin equals its pattern for " + where,
                a.getXYGeometry().within(b.getXYGeometry()), a.relate(b, "T*F**F***"));
            assertEquals("sfContains equals its pattern for " + where,
                a.getXYGeometry().contains(b.getXYGeometry()), a.relate(b, "T*****FF*"));
        }
    }

    private static final String[] COVERS =
        { "T*****FF*", "*T****FF*", "***T**FF*", "****T*FF*" };
    private static final String[] COVERED_BY =
        { "T*F**F***", "*TF**F***", "**FT*F***", "**F*TF***" };

    private static boolean anyPattern(GeometryWrapper a, GeometryWrapper b, String[] patterns)
            throws Exception {
        for (String p : patterns) {
            if (a.relate(b, p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code covers} and {@code coveredBy} need all four DE-9IM patterns, not the first.
     * <p>
     * The single patterns {@code T*****FF*} and {@code T*F**F***} are {@code contains} and
     * {@code within}, which are not boundary-neutral -- the very reason to want
     * {@code covers}. A line along a polygon's edge is covered by it but not within it.
     * Checked against JTS, which has both predicates directly.
     */
    @Test
    public void testCoversNeedsAPatternDisjunction() throws Exception {
        GeometryWrapper poly = WKTDatatype.INSTANCE.parse("POLYGON((0 0,2 0,2 2,0 2,0 0))");
        GeometryWrapper same = WKTDatatype.INSTANCE.parse("POLYGON((0 0,2 0,2 2,0 2,0 0))");
        GeometryWrapper edge = WKTDatatype.INSTANCE.parse("LINESTRING(0 0,2 0)");
        GeometryWrapper inner =
            WKTDatatype.INSTANCE.parse("POLYGON((0.5 0.5,1.5 0.5,1.5 1.5,0.5 1.5,0.5 0.5))");
        GeometryWrapper away = WKTDatatype.INSTANCE.parse("POLYGON((5 5,6 5,6 6,5 6,5 5))");

        // the disjunction agrees with JTS
        assertTrue(anyPattern(poly, edge, COVERS));
        assertTrue(poly.getXYGeometry().covers(edge.getXYGeometry()));
        assertTrue(anyPattern(poly, inner, COVERS));
        assertTrue(anyPattern(poly, same, COVERS));
        assertFalse(anyPattern(poly, away, COVERS));
        assertTrue(anyPattern(edge, poly, COVERED_BY));
        assertTrue(anyPattern(inner, poly, COVERED_BY));
        assertFalse(anyPattern(poly, inner, COVERED_BY));

        // and the first pattern alone is not enough: it is contains/within, which the
        // boundary case fails
        assertFalse("T*****FF* alone is contains, and misses the boundary case",
            poly.relate(edge, "T*****FF*"));
        assertFalse("T*F**F*** alone is within, and misses the boundary case",
            edge.relate(poly, "T*F**F***"));
    }

    @Test
    public void testZeroAreaQueryGeometryDoesNotMatchPointIndexedData() {
        // Lucene computes shape relations against indexed triangles. When neither side
        // has area -- a Point or LineString query against a POINT-indexed entity -- no
        // intersection is reported, even via the dedicated newPointQuery API. Areal
        // query geometries (bbox, Polygon) match point data as expected.
        //
        // Pinned because it is a silent empty result, not an error.
        String pointOnWattleDowns = "{\"type\":\"Point\",\"coordinates\":[116.35,-32.77]}";
        assertFalse("A Point query does not match POINT-indexed data",
            urisForOp("s_intersects", pointOnWattleDowns).contains(NS + "wattle-downs"));

        String lineThroughWattleDowns =
            "{\"type\":\"LineString\",\"coordinates\":[[115.0,-32.77],[118.0,-32.77]]}";
        assertFalse("A LineString query does not match POINT-indexed data",
            urisForOp("s_intersects", lineThroughWattleDowns).contains(NS + "wattle-downs"));

        // The areal equivalent of the same query does match.
        assertTrue("A small bbox over the same point does match",
            urisForOp("s_intersects", bboxJson(116.34, -32.78, 116.36, -32.76))
                .contains(NS + "wattle-downs"));
    }

    @Test
    public void testQueryGeometryMultiPolygon() {
        String multiPolygon = "{\"type\":\"MultiPolygon\",\"coordinates\":["
            + "[[[116.0,-33.0],[116.7,-33.0],[116.7,-32.5],[116.0,-32.5],[116.0,-33.0]]],"
            + "[[[148.5,-33.7],[149.5,-33.7],[149.5,-33.2],[148.5,-33.2],[148.5,-33.7]]]]}";
        Set<String> uris = urisForOp("s_intersects", multiPolygon);
        assertTrue("First polygon covers Wattle Downs", uris.contains(NS + "wattle-downs"));
        assertTrue("Second polygon covers Kurrajong Valley", uris.contains(NS + "kurrajong-valley"));
    }

    @Test
    public void testQueryGeometryCollection() {
        String collection = "{\"type\":\"GeometryCollection\",\"geometries\":["
            + "{\"type\":\"Point\",\"coordinates\":[122.0,-25.0]},"
            + "{\"bbox\":[148.5,-33.7,149.5,-33.2]}]}";
        Set<String> uris = urisForOp("s_intersects", collection);
        assertTrue("Point member matches big-area", uris.contains(NS + "big-area"));
        assertTrue("bbox member matches Kurrajong Valley", uris.contains(NS + "kurrajong-valley"));
    }

    @Test
    public void testMultiGeometryQueryWithinIsUnion() {
        // Lucene treats several query geometries as a union, so a shape within either
        // one satisfies WITHIN.
        String multiPolygon = "{\"type\":\"MultiPolygon\",\"coordinates\":["
            + "[[[116.0,-33.0],[116.7,-33.0],[116.7,-32.5],[116.0,-32.5],[116.0,-33.0]]],"
            + "[[[148.5,-33.7],[149.5,-33.7],[149.5,-33.2],[148.5,-33.2],[148.5,-33.7]]]]}";
        Set<String> uris = urisForOp("s_within", multiPolygon);
        assertTrue("Wattle Downs is within the first member", uris.contains(NS + "wattle-downs"));
        assertTrue("Kurrajong Valley is within the second member", uris.contains(NS + "kurrajong-valley"));
    }

    @Test
    public void testQueryPolygonHoleAppliesToWithinToo() {
        assertFalse("Wattle Downs is in the hole, so not within the donut",
            urisForOp("s_within", DONUT_AROUND_WATTLE_DOWNS).contains(NS + "wattle-downs"));
    }

    // --- still unsupported ------------------------------------------------------

    @Test
    public void testTouchesStillThrows() {
        // s_touches, s_crosses, s_overlaps and s_equals have no Lucene relation and must
        // keep raising rather than silently widening the result set.
        TextIndexException e = assertThrows(TextIndexException.class, () ->
            urisForOp("s_touches", bboxJson(115, -34, 118, -31)));
        assertTrue("Message should name the operator: " + e.getMessage(),
            e.getMessage().contains("s_touches"));
    }


    // ------------------------------------------------------------------
    // s_dwithin — distance queries
    // ------------------------------------------------------------------

    private static String dwithin(double lon, double lat, double metres) {
        return "{\"op\":\"s_dwithin\",\"args\":[{\"property\":\"" + FP + "location\"},"
            + "{\"type\":\"Point\",\"coordinates\":[" + lon + "," + lat + "]}," + metres + "]}";
    }

    private Set<String> urisForCqlJson(String json) {
        CqlExpression filter = org.apache.jena.query.text.cql.CqlParser.parse(json);
        List<TextHit> results = textIndex.queryWithCql(
            null, "*", filter, null, null, null, 100, null);
        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }
        return uris;
    }

    @Test
    public void testDwithinSmallRadiusMatchesOnlyTheNearestSite() {
        // 20 km around Wattle Downs. The next nearest fixture, ring-body, is ~56 km away.
        Set<String> uris = urisForCqlJson(dwithin(116.35, -32.77, 20000));
        assertTrue("Wattle Downs is at the centre", uris.contains(NS + "wattle-downs"));
        assertFalse("Ring-body is ~56 km away and outside 20 km", uris.contains(NS + "ring-body"));
        assertFalse("Kurrajong Valley is in NSW", uris.contains(NS + "kurrajong-valley"));
    }

    @Test
    public void testDwithinLargeRadiusSpansTheContinentButNotBeyond() {
        Set<String> uris = urisForCqlJson(dwithin(116.35, -32.77, 4000000));
        assertTrue("Kurrajong Valley is ~3000 km away, inside 4000 km",
            uris.contains(NS + "kurrajong-valley"));
        assertTrue("Brolga Ridge is inside 4000 km", uris.contains(NS + "brolga-ridge"));
        assertFalse("Auckland is ~5300 km away and outside", uris.contains(NS + "auckland"));
    }

    @Test
    public void testDwithinReachesAnAreaShape() {
        // big-area spans lat -27..-23, lon 120..125. A circle centred just west of it
        // with a radius long enough to reach must match; a shorter one must not.
        assertTrue("A circle reaching the polygon matches",
            urisForCqlJson(dwithin(119.0, -25.0, 200000)).contains(NS + "big-area"));
        assertFalse("A circle stopping short does not",
            urisForCqlJson(dwithin(119.0, -25.0, 20000)).contains(NS + "big-area"));
    }

    @Test
    public void testDwithinExcludesGeometryLessEntity() {
        assertFalse("An entity with no geometry is in no spatial result",
            urisForCqlJson(dwithin(116.35, -32.77, 4000000)).contains(NS + "no-geometry"));
    }

    @Test
    public void testDwithinRequiresAPointGeometry() {
        String withPolygon = "{\"op\":\"s_dwithin\",\"args\":[{\"property\":\"" + FP + "location\"},"
            + "{\"type\":\"Polygon\",\"coordinates\":[[[115.0,-33.0],[117.0,-33.0],[117.0,-32.0],[115.0,-32.0],[115.0,-33.0]]]},"
            + "1000]}";
        TextIndexException e = assertThrows(TextIndexException.class, () -> urisForCqlJson(withPolygon));
        assertTrue("Message should say a point is required: " + e.getMessage(),
            e.getMessage().contains("Point"));
    }

    @Test
    public void testDwithinRequiresADistance() {
        String noDistance = "{\"op\":\"s_dwithin\",\"args\":[{\"property\":\"" + FP + "location\"},"
            + "{\"type\":\"Point\",\"coordinates\":[116.35,-32.77]}]}";
        assertThrows(TextIndexException.class, () -> urisForCqlJson(noDistance));
    }

    @Test
    public void testTopologicalOperatorRejectsADistanceArgument() {
        // A third argument is only meaningful for s_dwithin. Accepting and ignoring it
        // would silently answer a different question than the one asked.
        String threeArgs = "{\"op\":\"s_intersects\",\"args\":[{\"property\":\"" + FP + "location\"},"
            + "{\"bbox\":[112,-44,154,-10]},1000]}";
        assertThrows(TextIndexException.class, () -> urisForCqlJson(threeArgs));
    }
    // ------------------------------------------------------------------
    // GeoJSON literals (geo:asGeoJSON) as an alternative to WKT
    // ------------------------------------------------------------------

    @Test
    public void testGeoJsonPointIsIndexed() {
        // RFC 7946 fixes GeoJSON to WGS84 lon/lat with no CRS member, so there is no
        // prefix to strip and no axis order to decide.
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseGeometryToLuceneFields("location",
                "{\"type\":\"Point\",\"coordinates\":[121.66,-31.20]}", true);
        assertFalse("A GeoJSON point must index", fields.isEmpty());
    }

    @Test
    public void testGeoJsonAndWktAgreeOnLocation() {
        // The same place as GeoJSON and as EPSG:4326 WKT must produce the same fields.
        List<org.apache.lucene.index.IndexableField> viaGeoJson =
            ShaclTextIndexLucene.parseGeometryToLuceneFields("location",
                "{\"type\":\"Point\",\"coordinates\":[121.66,-31.20]}", false);
        List<org.apache.lucene.index.IndexableField> viaWkt =
            ShaclTextIndexLucene.parseGeometryToLuceneFields("location",
                "<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(-31.20 121.66)", false);
        assertFalse(viaGeoJson.isEmpty());
        assertEquals("GeoJSON and WKT for one place should index identically",
            viaWkt.size(), viaGeoJson.size());
    }

    @Test
    public void testGeoJsonEveryGeometryTypeIsIndexed() {
        String[] geoms = {
            "{\"type\":\"Point\",\"coordinates\":[121.66,-31.20]}",
            "{\"type\":\"MultiPoint\",\"coordinates\":[[119.05,-22.75],[119.30,-22.60]]}",
            "{\"type\":\"LineString\",\"coordinates\":[[118.90,-23.40],[120.60,-23.40]]}",
            "{\"type\":\"MultiLineString\",\"coordinates\":[[[118.55,-20.40],[118.70,-20.90]],[[118.90,-21.20],[119.10,-21.60]]]}",
            "{\"type\":\"Polygon\",\"coordinates\":[[[121.20,-31.00],[121.80,-31.00],[121.80,-30.40],[121.20,-30.40],[121.20,-31.00]]]}",
            "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[118.20,-22.30],[118.30,-22.30],[118.30,-22.20],[118.20,-22.20],[118.20,-22.30]]]]}",
            "{\"type\":\"GeometryCollection\",\"geometries\":[{\"type\":\"Point\",\"coordinates\":[129.75,-20.10]},{\"type\":\"LineString\",\"coordinates\":[[130.1,-20.4],[130.5,-20.1]]}]}",
        };
        for (String g : geoms) {
            List<org.apache.lucene.index.IndexableField> fields =
                ShaclTextIndexLucene.parseGeometryToLuceneFields("location", g, false);
            assertFalse("Should index GeoJSON: " + g.substring(0, Math.min(40, g.length())),
                fields.isEmpty());
        }
    }

    @Test
    public void testGeoJsonPolygonHoleIsIndexed() {
        // Ring 0 is the shell, rings 1..n are holes, exactly as on the query side.
        List<org.apache.lucene.index.IndexableField> withHole =
            ShaclTextIndexLucene.parseGeometryToLuceneFields("location",
                "{\"type\":\"Polygon\",\"coordinates\":["
                + "[[121.20,-31.00],[121.80,-31.00],[121.80,-30.40],[121.20,-30.40],[121.20,-31.00]],"
                + "[[121.40,-30.80],[121.60,-30.80],[121.60,-30.60],[121.40,-30.60],[121.40,-30.80]]]}",
                false);
        assertFalse("A GeoJSON polygon with a hole must index", withHole.isEmpty());
    }

    @Test
    public void testMalformedGeoJsonProducesNoFields() {
        for (String bad : new String[] {
                "{not json",
                "{\"type\":\"Nonsense\",\"coordinates\":[1,2]}",
                "{\"type\":\"Point\"}" }) {
            List<org.apache.lucene.index.IndexableField> fields =
                ShaclTextIndexLucene.parseGeometryToLuceneFields("location", bad, false);
            assertTrue("Malformed GeoJSON should be skipped, not thrown: " + bad,
                fields.isEmpty());
        }
    }

    @Test
    public void testGeoJsonLiteralIsSearchableThroughTheIndex() {
        dataset.begin(ReadWrite.WRITE);
        try {
            Model model = dataset.getDefaultModel();
            Resource site = ResourceFactory.createResource(NS + "geojson-site");
            model.add(site, RDF.type, ResourceFactory.createResource(NS + "Site"));
            model.add(site, ResourceFactory.createProperty(NS, "title"), "GeoJSON Site");
            model.addLiteral(site, ResourceFactory.createProperty(GEO, "asWKT"),
                ResourceFactory.createTypedLiteral(
                    "{\"type\":\"Point\",\"coordinates\":[121.66,-31.20]}",
                    org.apache.jena.datatypes.TypeMapper.getInstance()
                        .getSafeTypeByName(GEO + "geoJSONLiteral")));
            dataset.commit();
        } finally {
            dataset.end();
        }

        CqlExpression filter = new CqlExpression.CqlSpatial("s_intersects", FP + "location",
            "{\"bbox\":[121.64,-31.22,121.68,-31.18]}");
        List<TextHit> results = textIndex.queryWithCql(
            null, "*", filter, null, null, null, 100, null);
        Set<String> uris = new HashSet<>();
        for (TextHit hit : results) {
            uris.add(hit.getNode().getURI());
        }
        assertTrue("A GeoJSON-valued geometry should be spatially searchable",
            uris.contains(NS + "geojson-site"));
    }

    @Test
    public void testGeoJsonFeatureIsUnwrapped() {
        // A GIS export usually emits Feature objects rather than bare geometries.
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseGeometryToLuceneFields("location",
                "{\"type\":\"Feature\",\"properties\":{\"name\":\"x\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[121.66,-31.20]}}", false);
        assertFalse("A GeoJSON Feature should index its geometry", fields.isEmpty());
    }

    @Test
    public void testGeoJsonFeatureCollectionIndexesEveryMember() {
        // JTS reads a FeatureCollection as a GeometryCollection of every member's
        // geometry, so both points are indexed and nothing is silently dropped.
        List<org.apache.lucene.index.IndexableField> fields =
            ShaclTextIndexLucene.parseGeometryToLuceneFields("location",
                "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[121.66,-31.20]}},"
                + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[130.00,-25.00]}}]}",
                false);
        assertFalse("A FeatureCollection should index", fields.isEmpty());

        // Both members are searchable: a box over either one finds it.
        List<org.apache.lucene.index.IndexableField> first =
            ShaclTextIndexLucene.parseGeometryToLuceneFields("location",
                "{\"type\":\"Point\",\"coordinates\":[121.66,-31.20]}", false);
        assertEquals("Two members should index as two point shapes",
            first.size() * 2, fields.size());
    }



    // ------------------------------------------------------------------
    // RFC 7946 allows an optional "bbox" member on any GeoJSON object
    // ------------------------------------------------------------------

    @Test
    public void testGeoJsonGeometryBboxMemberDoesNotOverrideCoordinates() {
        // RFC 7946 permits an optional "bbox" on any geometry object; it is metadata,
        // not the geometry. GIS exports emit it routinely. Treating it as CQL2's bbox
        // form would query the bounding box instead of the shape -- a wider result set,
        // silently.
        //
        // The coordinates here are over Indonesia; the bbox is over Wattle Downs. Only a
        // reading that honours the coordinates gets this right.
        String polygonElsewhere =
            "{\"type\":\"Polygon\",\"bbox\":[116.3,-32.8,116.4,-32.7],\"coordinates\":"
            + "[[[100.0,0.0],[101.0,0.0],[101.0,1.0],[100.0,1.0],[100.0,0.0]]]}";

        assertFalse("The bbox member must not stand in for the polygon",
            urisForOp("s_intersects", polygonElsewhere).contains(NS + "wattle-downs"));
    }

    @Test
    public void testGeoJsonGeometryBboxMemberIsIgnoredWhenConsistent() {
        // The usual case: a bbox that genuinely describes the polygon. The result must
        // be the same as without it.
        String withBbox =
            "{\"type\":\"Polygon\",\"bbox\":[116.0,-33.0,116.7,-32.5],\"coordinates\":"
            + "[[[116.0,-33.0],[116.7,-33.0],[116.7,-32.5],[116.0,-32.5],[116.0,-33.0]]]}";
        String withoutBbox =
            "{\"type\":\"Polygon\",\"coordinates\":"
            + "[[[116.0,-33.0],[116.7,-33.0],[116.7,-32.5],[116.0,-32.5],[116.0,-33.0]]]}";

        assertEquals("An optional bbox member should not change the result",
            urisForOp("s_intersects", withoutBbox), urisForOp("s_intersects", withBbox));
        assertTrue("and Wattle Downs is inside that polygon",
            urisForOp("s_intersects", withBbox).contains(NS + "wattle-downs"));
    }

    @Test
    public void testCql2BboxFormStillWorks() {
        // The CQL2 bbox form has no "type", and must keep working.
        assertTrue("A bare bbox is still the CQL2 bbox form",
            urisForOp("s_intersects", "{\"bbox\":[116.3,-32.8,116.4,-32.7]}")
                .contains(NS + "wattle-downs"));
    }
}
