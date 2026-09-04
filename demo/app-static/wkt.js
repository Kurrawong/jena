/* Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0 */

/*
 * WKT -> Leaflet.
 *
 * GeoSPARQL WKT literals may carry a CRS IRI prefix, `<crs> POINT(...)`, which no
 * general-purpose WKT library understands, and the axis order depends on that CRS. This
 * strips the prefix and normalises to Leaflet's [lat, lon] order.
 *
 * Axis order follows the CRS, matching what the indexer does server-side:
 *
 *   bare WKT, or CRS84   lon lat   (GeoSPARQL's default for an unprefixed literal)
 *   EPSG:4326            lat lon
 *   EPSG:4283  (GDA94)   lat lon
 *   EPSG:7844  (GDA2020) lat lon
 *
 * Any other CRS returns null rather than a guess. A projected CRS such as EPSG:28350
 * carries metres, and drawing metres as degrees would put the shape in the Gulf of
 * Guinea — a wrong shape on the map is worse than a missing one. Reprojecting needs a
 * geodesy library and is out of scope here.
 *
 * Z and M ordinates are accepted and dropped; only the first two are used.
 */

(function (global) {
    'use strict';

    /** EPSG codes that are geographic and lat/lon in EPSG axis order. */
    var LATLON_EPSG = { '4326': true, '4283': true, '7844': true };

    var warnedCrs = {};

    /**
     * Axis order for a CRS IRI: 'latlon', 'lonlat', or null if unsupported.
     * Accepts the spellings that turn up in real data:
     *   http://www.opengis.net/def/crs/EPSG/0/4326
     *   urn:ogc:def:crs:EPSG::4326
     *   EPSG:4326
     *   http://www.opengis.net/def/crs/OGC/1.3/CRS84
     */
    function axisOrderFor(crsIri) {
        var s = String(crsIri).trim();
        if (/CRS84/i.test(s)) return 'lonlat';
        if (!/EPSG/i.test(s)) return null;
        var m = /(\d{4,6})\s*$/.exec(s);
        if (!m) return null;
        return LATLON_EPSG[m[1]] ? 'latlon' : null;
    }

    /** Split `s` on commas that sit outside any parentheses. */
    function splitTop(s) {
        var out = [], depth = 0, start = 0;
        for (var i = 0; i < s.length; i++) {
            var c = s.charAt(i);
            if (c === '(') depth++;
            else if (c === ')') depth--;
            else if (c === ',' && depth === 0) { out.push(s.slice(start, i)); start = i + 1; }
        }
        out.push(s.slice(start));
        return out.map(function (x) { return x.trim(); }).filter(function (x) { return x.length > 0; });
    }

    /** Contents of the balanced (...) starting at `i`, plus the index just past it. */
    function balanced(text, i) {
        if (text.charAt(i) !== '(') return null;
        var depth = 0;
        for (var j = i; j < text.length; j++) {
            var c = text.charAt(j);
            if (c === '(') depth++;
            else if (c === ')') { depth--; if (depth === 0) return [text.slice(i + 1, j), j + 1]; }
        }
        return null;   // unbalanced
    }

    /** One "x y" (or "x y z") position as Leaflet [lat, lon]. */
    function position(s, latLon) {
        var n = s.trim().split(/\s+/).map(Number);
        if (n.length < 2 || !isFinite(n[0]) || !isFinite(n[1])) return null;
        return latLon ? [n[0], n[1]] : [n[1], n[0]];
    }

    /** A comma-separated run of positions, each optionally wrapped in its own parens. */
    function positions(s, latLon) {
        var out = [];
        var parts = splitTop(s);
        for (var i = 0; i < parts.length; i++) {
            var p = parts[i].replace(/^\(\s*/, '').replace(/\s*\)$/, '');
            var pos = position(p, latLon);
            if (!pos) return null;
            out.push(pos);
        }
        return out.length ? out : null;
    }

    /**
     * Parse one geometry from the front of `text`.
     * Returns { shapes: [...], rest: string } where each shape is
     *   { kind: 'point',   latlng:  [lat, lon] }
     *   { kind: 'line',    latlngs: [[lat, lon], ...] }
     *   { kind: 'polygon', rings:   [outer, hole1, ...] }
     */
    function parseGeometry(text, latLon) {
        var m = /^\s*([A-Za-z]+)\s*(ZM|Z|M)?\s*/.exec(text);
        if (!m) return null;
        var type = m[1].toUpperCase();
        var rest = text.slice(m[0].length);

        if (/^EMPTY/i.test(rest)) {
            return { shapes: [], rest: rest.replace(/^EMPTY/i, '') };
        }

        var b = balanced(rest, 0);
        if (!b) return null;
        var inner = b[0];
        rest = rest.slice(b[1]);

        var shapes = [];
        var i, parts, ring, rings, pts;

        switch (type) {
            case 'POINT':
                var pt = position(inner, latLon);
                if (!pt) return null;
                shapes.push({ kind: 'point', latlng: pt });
                break;

            case 'MULTIPOINT':
                pts = positions(inner, latLon);
                if (!pts) return null;
                for (i = 0; i < pts.length; i++) shapes.push({ kind: 'point', latlng: pts[i] });
                break;

            case 'LINESTRING':
                pts = positions(inner, latLon);
                if (!pts) return null;
                shapes.push({ kind: 'line', latlngs: pts });
                break;

            case 'MULTILINESTRING':
                parts = splitTop(inner);
                for (i = 0; i < parts.length; i++) {
                    var lb = balanced(parts[i], 0);
                    pts = positions(lb ? lb[0] : parts[i], latLon);
                    if (!pts) return null;
                    shapes.push({ kind: 'line', latlngs: pts });
                }
                break;

            case 'POLYGON':
                rings = [];
                parts = splitTop(inner);
                for (i = 0; i < parts.length; i++) {
                    var rb = balanced(parts[i], 0);
                    ring = positions(rb ? rb[0] : parts[i], latLon);
                    if (!ring) return null;
                    rings.push(ring);
                }
                // Leaflet reads ring 0 as the outline and the rest as holes.
                shapes.push({ kind: 'polygon', rings: rings });
                break;

            case 'MULTIPOLYGON':
                parts = splitTop(inner);
                for (i = 0; i < parts.length; i++) {
                    var pb = balanced(parts[i], 0);
                    if (!pb) return null;
                    rings = [];
                    var ringParts = splitTop(pb[0]);
                    for (var k = 0; k < ringParts.length; k++) {
                        var rb2 = balanced(ringParts[k], 0);
                        ring = positions(rb2 ? rb2[0] : ringParts[k], latLon);
                        if (!ring) return null;
                        rings.push(ring);
                    }
                    shapes.push({ kind: 'polygon', rings: rings });
                }
                break;

            case 'GEOMETRYCOLLECTION':
                parts = splitTop(inner);
                for (i = 0; i < parts.length; i++) {
                    var sub = parseGeometry(parts[i], latLon);
                    if (!sub) return null;
                    shapes = shapes.concat(sub.shapes);
                }
                break;

            default:
                return null;
        }

        return { shapes: shapes, rest: rest };
    }

    /**
     * Parse a geo:asWKT literal into drawable shapes in Leaflet [lat, lon] order.
     * Returns [] for an unsupported CRS, unparseable input or an EMPTY geometry.
     */
    function wktToShapes(literal) {
        if (!literal) return [];
        var s = String(literal).trim();
        var order = 'lonlat';   // bare WKT is CRS84, which is lon/lat

        if (s.charAt(0) === '<') {
            var close = s.indexOf('>');
            if (close < 0) return [];
            var crs = s.slice(1, close);
            s = s.slice(close + 1).trim();
            order = axisOrderFor(crs);
            if (!order) {
                if (!warnedCrs[crs]) {
                    warnedCrs[crs] = true;
                    if (global.console && console.warn) {
                        console.warn('Unsupported CRS for map display, geometry skipped: ' + crs
                            + ' (a projected CRS would need reprojection, which the browser does not do)');
                    }
                }
                return [];
            }
        }

        var parsed;
        try {
            parsed = parseGeometry(s, order === 'latlon');
        } catch (e) {
            return [];
        }
        return parsed ? parsed.shapes : [];
    }

    /** Every [lat, lon] in a shape list, for fitting map bounds. */
    function shapeBounds(shapes) {
        var out = [];
        for (var i = 0; i < shapes.length; i++) {
            var sh = shapes[i];
            if (sh.kind === 'point') out.push(sh.latlng);
            else if (sh.kind === 'line') out.push.apply(out, sh.latlngs);
            else if (sh.kind === 'polygon') {
                for (var r = 0; r < sh.rings.length; r++) out.push.apply(out, sh.rings[r]);
            }
        }
        return out;
    }

    /** A short label for a WKT literal, e.g. "MultiLineString". Null if unparseable. */
    function wktTypeName(literal) {
        var s = String(literal || '').trim();
        if (s.charAt(0) === '<') {
            var close = s.indexOf('>');
            if (close < 0) return null;
            s = s.slice(close + 1).trim();
        }
        var m = /^\s*([A-Za-z]+)/.exec(s);
        if (!m) return null;
        var t = m[1].toUpperCase();
        var pretty = {
            POINT: 'Point', LINESTRING: 'LineString', POLYGON: 'Polygon',
            MULTIPOINT: 'MultiPoint', MULTILINESTRING: 'MultiLineString',
            MULTIPOLYGON: 'MultiPolygon', GEOMETRYCOLLECTION: 'GeometryCollection',
        };
        return pretty[t] || null;
    }

    var api = {
        wktToShapes: wktToShapes,
        shapeBounds: shapeBounds,
        wktTypeName: wktTypeName,
        axisOrderFor: axisOrderFor,
    };

    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else global.WKT = api;

})(typeof globalThis !== 'undefined' ? globalThis : this);
