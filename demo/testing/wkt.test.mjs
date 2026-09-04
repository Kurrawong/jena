/* Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0 */

/*
 * Tests for the browser-side WKT parser. Run with:
 *
 *   node --test demo/testing/wkt.test.mjs
 *
 * No dependencies: Node's built-in test runner only.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const WKT = require('../app-static/wkt.js');

const EPSG = (code) => `<http://www.opengis.net/def/crs/EPSG/0/${code}> `;
const CRS84 = '<http://www.opengis.net/def/crs/OGC/1.3/CRS84> ';

// --- axis order ------------------------------------------------------------

test('bare WKT is CRS84, so lon lat', () => {
    const [s] = WKT.wktToShapes('POINT(121.66 -31.20)');
    assert.deepEqual(s.latlng, [-31.20, 121.66]);
});

test('EPSG:4326 is lat lon', () => {
    const [s] = WKT.wktToShapes(EPSG(4326) + 'POINT(-31.20 121.66)');
    assert.deepEqual(s.latlng, [-31.20, 121.66]);
});

test('GDA2020 and GDA94 are lat lon', () => {
    for (const code of [7844, 4283]) {
        const [s] = WKT.wktToShapes(EPSG(code) + 'POINT(-31.20 121.66)');
        assert.deepEqual(s.latlng, [-31.20, 121.66], `EPSG:${code}`);
    }
});

test('explicit CRS84 prefix is lon lat', () => {
    const [s] = WKT.wktToShapes(CRS84 + 'POINT(121.66 -31.20)');
    assert.deepEqual(s.latlng, [-31.20, 121.66]);
});

test('GDA2020 and bare CRS84 land on the same point', () => {
    const [a] = WKT.wktToShapes(EPSG(7844) + 'POINT(-31.20 121.66)');
    const [b] = WKT.wktToShapes('POINT(121.66 -31.20)');
    assert.deepEqual(a.latlng, b.latlng);
});

test('alternative CRS spellings resolve', () => {
    assert.equal(WKT.axisOrderFor('urn:ogc:def:crs:EPSG::4326'), 'latlon');
    assert.equal(WKT.axisOrderFor('EPSG:7844'), 'latlon');
    assert.equal(WKT.axisOrderFor('http://www.opengis.net/def/crs/OGC/1.3/CRS84'), 'lonlat');
});

test('a projected CRS is skipped rather than guessed', () => {
    // EPSG:28350 is MGA zone 50, in metres. Drawing metres as degrees would put the
    // shape in the Gulf of Guinea, so returning nothing is the safer answer.
    assert.equal(WKT.axisOrderFor('http://www.opengis.net/def/crs/EPSG/0/28350'), null);
    assert.deepEqual(WKT.wktToShapes(EPSG(28350) + 'POINT(390000 6540000)'), []);
});

// --- geometry types --------------------------------------------------------

test('LineString', () => {
    const s = WKT.wktToShapes('LINESTRING(118.90 -23.40, 120.60 -23.40)');
    assert.equal(s.length, 1);
    assert.equal(s[0].kind, 'line');
    assert.deepEqual(s[0].latlngs, [[-23.40, 118.90], [-23.40, 120.60]]);
});

test('MultiPoint, both legal spellings', () => {
    for (const wkt of ['MULTIPOINT((119.05 -22.75), (119.30 -22.60))',
                       'MULTIPOINT(119.05 -22.75, 119.30 -22.60)']) {
        const s = WKT.wktToShapes(wkt);
        assert.equal(s.length, 2, wkt);
        assert.deepEqual(s[0].latlng, [-22.75, 119.05]);
        assert.deepEqual(s[1].latlng, [-22.60, 119.30]);
    }
});

test('MultiLineString gives one line per member', () => {
    const s = WKT.wktToShapes('MULTILINESTRING((118.55 -20.40, 118.70 -20.90), (118.90 -21.20, 119.10 -21.60))');
    assert.equal(s.length, 2);
    assert.ok(s.every(x => x.kind === 'line'));
});

test('Polygon keeps its interior rings', () => {
    const s = WKT.wktToShapes(
        'POLYGON((121.20 -31.00, 121.80 -31.00, 121.80 -30.40, 121.20 -30.40, 121.20 -31.00),'
        + '(121.40 -30.80, 121.60 -30.80, 121.60 -30.60, 121.40 -30.60, 121.40 -30.80))');
    assert.equal(s.length, 1);
    assert.equal(s[0].kind, 'polygon');
    assert.equal(s[0].rings.length, 2, 'outer ring plus one hole');
    assert.equal(s[0].rings[1].length, 5, 'the hole survives with all its points');
});

test('MultiPolygon, each member with its own rings', () => {
    const s = WKT.wktToShapes(
        'MULTIPOLYGON(((118.20 -22.30, 118.30 -22.30, 118.30 -22.20, 118.20 -22.20, 118.20 -22.30)),'
        + '((118.45 -22.45, 118.55 -22.45, 118.55 -22.35, 118.45 -22.35, 118.45 -22.45)))');
    assert.equal(s.length, 2);
    assert.ok(s.every(x => x.kind === 'polygon' && x.rings.length === 1));
});

test('GeometryCollection flattens to its members', () => {
    const s = WKT.wktToShapes(
        'GEOMETRYCOLLECTION(POINT(129.75 -20.10),'
        + 'POLYGON((130.10 -20.40, 130.50 -20.40, 130.50 -20.10, 130.10 -20.10, 130.10 -20.40)))');
    assert.equal(s.length, 2);
    assert.equal(s[0].kind, 'point');
    assert.equal(s[1].kind, 'polygon');
});

test('nested GeometryCollection recurses', () => {
    const s = WKT.wktToShapes(
        'GEOMETRYCOLLECTION(POINT(1 2), GEOMETRYCOLLECTION(POINT(3 4), LINESTRING(5 6, 7 8)))');
    assert.equal(s.length, 3);
});

// --- CRS prefix with every type -------------------------------------------

test('a CRS prefix applies to every geometry type, not just points', () => {
    const s = WKT.wktToShapes(EPSG(7844) + 'LINESTRING(-23.40 118.90, -23.40 120.60)');
    assert.deepEqual(s[0].latlngs, [[-23.40, 118.90], [-23.40, 120.60]]);
});

// --- degenerate input ------------------------------------------------------

test('Z and M ordinates are dropped', () => {
    const [s] = WKT.wktToShapes('POINT Z (121.66 -31.20 350)');
    assert.deepEqual(s.latlng, [-31.20, 121.66]);
});

test('EMPTY yields no shapes and no throw', () => {
    assert.deepEqual(WKT.wktToShapes('POINT EMPTY'), []);
    assert.deepEqual(WKT.wktToShapes('GEOMETRYCOLLECTION EMPTY'), []);
});

test('malformed input yields no shapes and no throw', () => {
    for (const bad of ['NOT_WKT', 'POINT(', 'POINT(1)', '<unclosed POINT(1 2)', '', null]) {
        assert.deepEqual(WKT.wktToShapes(bad), [], JSON.stringify(bad));
    }
});

test('unbalanced parentheses do not hang or throw', () => {
    assert.deepEqual(WKT.wktToShapes('POLYGON((1 2, 3 4'), []);
});

// --- helpers ---------------------------------------------------------------

test('shapeBounds collects every coordinate', () => {
    const s = WKT.wktToShapes(
        'GEOMETRYCOLLECTION(POINT(1 2), LINESTRING(3 4, 5 6),'
        + 'POLYGON((0 0, 1 0, 1 1, 0 1, 0 0)))');
    assert.equal(WKT.shapeBounds(s).length, 1 + 2 + 5);
});

test('wktTypeName reads through a CRS prefix', () => {
    assert.equal(WKT.wktTypeName(EPSG(7844) + 'MULTILINESTRING((1 2, 3 4))'), 'MultiLineString');
    assert.equal(WKT.wktTypeName('POINT(1 2)'), 'Point');
    assert.equal(WKT.wktTypeName('NOT_WKT'), null);
});
