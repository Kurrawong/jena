# Spatial Filtering

SHACL-mode text search supports spatial filtering via WKT literals indexed as Lucene `LatLonShape` fields. Entities with `geo:asWKT` properties can be filtered by geographic region using CQL2-JSON spatial operators.

## Configuration

Add a `LatLonField` to your shape definition, pointing at the `geo:asWKT` predicate:

```turtle
PREFIX idx:   <urn:jena:lucene:index#>
PREFIX field: <urn:jena:lucene:field#>
PREFIX sh:    <http://www.w3.org/ns/shacl#>
PREFIX geo:   <http://www.opengis.net/ont/geosparql#>

field:location
    idx:fieldName "location" ;
    idx:fieldType idx:LatLonField .

:SiteShape
    sh:targetClass ex:Site ;
    sh:property [ idx:field field:location ; sh:path geo:asWKT ] ;
    # ... other fields ...
```

`LatLonField` does not support `idx:facetable` or `idx:sortable` (spatial fields are neither sortable nor facetable).

## Supported geometry types

All eight JTS geometry types are indexed:

| WKT | Notes |
|---|---|
| `POINT(x y)` | |
| `LINESTRING(x1 y1, x2 y2, ...)` | a line has no interior, so a region strictly inside a closed ring drawn as a `LINESTRING` does not intersect it |
| `POLYGON((...))` | interior rings (holes) are honoured |
| `MULTIPOINT((x1 y1), (x2 y2), ...)` | |
| `MULTILINESTRING((...), (...))` | |
| `MULTIPOLYGON(((...)), ((...)))` | |
| `GEOMETRYCOLLECTION(...)` | members are indexed recursively, so collections may nest |

Members of a collection are indexed onto the same Lucene field, so an entity matches if
any member matches. A geometry that cannot be indexed is logged as a warning and skipped,
and in that case the WKT is **not** stored either — an entity is never left with a
retrievable geometry that no spatial filter can find.

### Antimeridian

Lucene does not split geometries at the antimeridian. A line written from longitude 179
to −179 is read as spanning the long way round the globe, not the two-degree short hop.
Split such geometries into two parts before loading if the short crossing is what you
mean.

## GeoJSON literals

A `LatLonField` accepts **either** WKT or GeoJSON. Bind whichever predicate the data
uses:

```turtle
:SiteShape
    sh:property [ idx:field field:location ; sh:path geo:asWKT ] ;
    # ... or ...
    sh:property [ idx:field field:location ; sh:path geo:asGeoJSON ] .
```

### How the serialisation is decided

**The datatype decides, when the value declares one.** A `geo:wktLiteral` is read as WKT
and a `geo:geoJSONLiteral` as GeoJSON. The declaration is authoritative — a value typed
`geo:wktLiteral` whose lexical form is a JSON object is treated as a data error and is not
indexed, rather than being quietly taken as GeoJSON. `geo:gmlLiteral` is declined by name:
GML is not supported, and the log says so rather than reporting a WKT parse failure.

**An untyped value is sniffed**, by testing whether it starts with `{`. So a geometry
typed only `xsd:string` — routine in data converted from GIS exports — still indexes, and
so does a value from an external CSV source, which has no datatype at all. Refusing these
would leave the field silently empty, which is the failure this index tries hardest to
avoid.

There is a catch worth knowing when you sniff, reported once per field in the log:

> a geometry with no GeoSPARQL datatype is **invisible to every `geof:` function**

Those functions require `geo:wktLiteral` or `geo:geoJSONLiteral` and return *unbound*
without one, and an unbound value in a `FILTER` is silently false. So an `xsd:string`
geometry filters fine through `luc:query` and then silently matches nothing in SPARQL.
If you plan to use the [two-pass form](#getting-equals-covers-touches-crosses-or-overlaps-use-two-passes),
type your literals.

GeoJSON is simpler than WKT here because RFC 7946 fixes it to WGS84 longitude/latitude
and forbids a CRS member, so there is no prefix to strip and no axis order to decide.
An optional RFC 7946 `bbox` member is ignored, as it should be. Every geometry type is
supported, holes included, and a `Feature` or `FeatureCollection` wrapper is accepted as
well as a bare geometry — a `FeatureCollection` indexes every
member's geometry, so nothing is dropped.

A field may mix the two across entities. The same location expressed as GeoJSON and as
EPSG:4326 WKT indexes identically.

## CRS handling

Lucene indexes all coordinates in WGS84 (latitude/longitude in degrees). The indexer automatically handles CRS detection and normalisation:

| Input CRS | Axis order in WKT | Handling |
|---|---|---|
| Bare WKT (no prefix) | lon, lat (CRS84 default) | Automatic axis normalisation |
| `<http://www.opengis.net/def/crs/EPSG/0/4326>` | lat, lon | Used directly |
| `<http://www.opengis.net/def/crs/EPSG/0/4283>` (GDA94) | lat, lon | Axes swapped, no datum transform |
| `<http://www.opengis.net/def/crs/EPSG/0/7844>` (GDA2020) | lat, lon | Axes swapped, no datum transform |
| Other CRS (e.g. EPSG:28350) | Varies | Transformed to WGS84 via Apache SIS |

### Why GDA2020 and GDA94 get no datum transform

EPSG publishes the GDA2020 to WGS 84 transformation as a **null transformation**: the
coordinates are identical. WGS84 is defined only to about a metre, GDA2020 is ITRF2014 at
epoch 2020.0, and Lucene quantises to roughly a centimetre, so applying a transform would
be arithmetic with no effect. Both are treated as WGS84-equivalent and only the axis order
is corrected.

The axis swap is done explicitly rather than left to `GeometryWrapper`. Apache SIS as
bundled does not recognise either CRS, so `getXYGeometry()` returns their lat/lon
coordinates untouched. Before this was fixed, a longitude arrived where Lucene expects a
latitude, failed the -90..90 check, and the geometry was discarded with only a warning —
the entity indexed with no location and could never match a spatial filter.

If you hold GDA2020 data, **keep the `<...EPSG/0/7844>` prefix** on `geo:asWKT`. Dropping
it to make bare CRS84 asserts a datum your data is not in.

Clients then have to strip that prefix themselves, because no general-purpose WKT library
understands the `<crs-iri> GEOMETRY(...)` form and the axis order depends on the CRS. The
demo does this in `demo/app-static/wkt.js`, which is a usable reference: it strips the
prefix, maps EPSG:4326, 4283 and 7844 to lat/lon and bare or CRS84 literals to lon/lat,
handles every geometry type including polygon holes, and returns nothing for a projected
CRS rather than drawing metres as degrees.

### Examples in data

```turtle
@prefix geo: <http://www.opengis.net/ont/geosparql#> .

# EPSG:4326 — lat/lon order (explicit CRS prefix)
ex:site-a geo:asWKT "<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(-33.87 151.21)"^^geo:wktLiteral .

# CRS84 — lon/lat order (bare WKT, no prefix, GeoSPARQL default)
ex:site-b geo:asWKT "POINT(151.21 -33.87)"^^geo:wktLiteral .

# Both index to the same location: Sydney, Australia
```

## Operators

| CQL2 operator | `lucene-core` relation | Meaning |
|---|---|---|
| `s_intersects` | `INTERSECTS` | the indexed shape and the query geometry share any point |
| `s_within` | `WITHIN` | the indexed shape lies inside the query geometry |
| `s_contains` | `CONTAINS` | the indexed shape encloses the query geometry |
| `s_disjoint` | `DISJOINT` | the indexed shape and the query geometry share no point |

CQL2 argument order is `op(property, geometry)`, so `s_within` reads as *property within
geometry*.

### Which module this uses, and what that leaves out

Spatial filtering is built on **`lucene-core`** — `LatLonShape` fields, queried through
`ShapeField.QueryRelation`. That enum has exactly four constants, `INTERSECTS`, `WITHIN`,
`CONTAINS` and `DISJOINT`, and they are the four rows above. `s_dwithin` is not one of
them; it compiles to a circle query (see [Distance queries](#distance-queries)).

The four CQL2 operators with no counterpart there are **`s_equals`, `s_touches`,
`s_crosses` and `s_overlaps`**. They are parsed and then **raise**, rather than being
silently dropped.

Lucene's other spatial module, **`lucene-spatial-extras`**, does define a richer
`SpatialOperation` set — eight operations including `IsEqualTo` and `Overlaps`, plus
`BBoxIntersects` and `BBoxWithin`. Its `IsWithin` and `Contains` are also documented as
*boundary-neutral* (OGC `CoveredBy` and `Covers`), which `lucene-core`'s are not — see
[Coincident geometry](#coincident-geometry-is-not-within-or-contains).

We do not use it, for four reasons:

- it is not a dependency, and pulls in Spatial4j;
- it would index the geometry field a second way, as prefix-tree cells plus serialised
  geometry doc values, rather than reusing the existing BKD field;
- exact predicates there are evaluated by `SerializedDVStrategy`, whose own javadoc says
  it is *"not at all fast; designed to be used in conjunction with another index based
  SpatialStrategy that is approximated"*;
- `CompositeSpatialStrategy`, the strategy that combines the two, excludes `Disjoint` and
  the `BBox` operations — so adopting it would **lose `s_disjoint`**, which works today.

Its own design is therefore the two-pass shape described below: an approximate index to
narrow, exact geometry to refine. That is available already, without the dependency.

### Getting equals, covers, touches, crosses or overlaps: use two passes

Every one of these predicates *implies* intersection, so `s_intersects` is a sound
superset filter for all of them. Narrow with Lucene, then refine on the geometry:

```sparql
PREFIX luc:  <urn:jena:lucene:index#>
PREFIX geo:  <http://www.opengis.net/ont/geosparql#>
PREFIX geof: <http://www.opengis.net/def/function/geosparql/>

SELECT ?entity WHERE {
  # pass 1 — indexed, and facets and paging still work
  (?hit ?entity ?score) luc:query (
    "default" "default" "*"
    '{"op":"s_intersects","args":[{"property":"urn:jena:lucene:field#location"},
                                  {"bbox":[115,-34,118,-31]}]}'
    "" 1000 0)

  # pass 2 — exact, on the geometry itself
  ?entity geo:hasGeometry/geo:asWKT ?wkt .
  FILTER(geof:relate(?wkt, "POLYGON((115 -34,118 -34,118 -31,115 -31,115 -34))"^^geo:wktLiteral,
                     "T*F**FFF*"))
}
```

**Pass 2 needs the geometry datatype.** `geof:` functions require `geo:wktLiteral` (or
`geo:geoJSONLiteral`); given an `xsd:string` or a plain literal they return *unbound*, and
an unbound value in a `FILTER` is silently false. So a geometry that indexes happily —
this index accepts an untyped literal, see [GeoJSON literals](#geojson-literals) — can
still match in pass 1 and then vanish in pass 2, with no error anywhere. If your geometry
literals are not typed, fix the data before relying on two passes.

Pass 2 is not pushed down: `geof:` functions are filter functions, evaluated per binding
with no index behind them. That is fine here precisely because pass 1 is selective — these
predicates only hold for geometries that already intersect. It is not fine for a query
whose first pass matches most of the corpus.

What to call in pass 2, by predicate:

| predicate | pass 2 |
|---|---|
| touches | `geof:sfTouches` |
| crosses | `geof:sfCrosses` |
| overlaps | `geof:sfOverlaps` |
| within | `geof:sfWithin` |
| contains | `geof:sfContains` |
| equals | `geof:relate(?a, ?b, "T*F**FFF*")` — **not** `geof:sfEquals` |
| covers | a disjunction, below |
| covered by | a disjunction, below |

`sfTouches`, `sfCrosses`, `sfOverlaps`, `sfWithin` and `sfContains` delegate to the JTS
predicate of the same name and are correct. Prefer them: a hand-written pattern is a
nine-character string with no spell check.

**A mistyped pattern of the right length silently matches nothing.** `geof:relate` rejects
a pattern that is not nine characters — `IllegalArgumentException`, "Should be length 9" —
but nine characters that are not DE-9IM symbols, or one transposed symbol, return `false`
for every pair. `T*F**FFG*` filters everything out and reports nothing. Pinned by
`testMistypedRelatePatternIsSilentlyFalse`.

**`covers` and `coveredBy` cannot be written as one pattern.** Each is a disjunction of
four, and GeoSPARQL's simple-features set has no `sfCovers`:

```sparql
# A covers B
FILTER(geof:relate(?a, ?b, "T*****FF*") || geof:relate(?a, ?b, "*T****FF*")
    || geof:relate(?a, ?b, "***T**FF*") || geof:relate(?a, ?b, "****T*FF*"))

# A is covered by B
FILTER(geof:relate(?a, ?b, "T*F**F***") || geof:relate(?a, ?b, "*TF**F***")
    || geof:relate(?a, ?b, "**FT*F***") || geof:relate(?a, ?b, "**F*TF***"))
```

Do not reach for the single first pattern of either. `T*****FF*` alone is **`contains`**,
and `T*F**F***` alone is **`within`** — and those are *not* boundary-neutral, which is the
whole reason you would want `covers` instead. A line lying exactly along a polygon's edge
is `coveredBy` that polygon but not `within` it. Verified against JTS `covers()` and
`coveredBy()` across seven cases in `testCoversNeedsAPatternDisjunction`.

Nor is `geof:ehCovers` the answer. Egenhofer's eight relations are mutually **exclusive**,
so a polygon does not `ehCovers` itself — it `ehEquals` itself. `geof:ehCovers` returns
`false` for identical geometries.

**Do not use `geof:sfEquals` on points.** It is the one function in that set implemented
as a fixed DE-9IM pattern rather than a JTS predicate, and the pattern is `TFFFTFFFT`,
which requires boundary-to-boundary intersection. A point has an empty boundary, so it
can never match: `geof:sfEquals` returns `false` for two **identical** points, while
`geof:relate(..., "T*F**FFF*")` correctly returns `true`. Polygons and linestrings are
unaffected, because they have boundaries. Measured against jena-geosparql 4.10.0 and
6.2.0-SNAPSHOT, and pinned by `testJenaSfEqualsMissesIdenticalPoints` so the advice
changes if upstream fixes it.

Note that `touches` is a *disjunction* of three DE-9IM patterns in OGC, and which one
applies depends on the dimensions involved — two polygons sharing an edge match
`F***T****`, a line meeting a point at its endpoint matches `F**T*****`. Use
`geof:sfTouches` rather than picking a pattern.

### Coincident geometry is not `within` or `contains`

`lucene-core`'s `WITHIN` and `CONTAINS` are not boundary-neutral. An indexed geometry
**identical to the query geometry** satisfies neither:

| indexed vs identical query polygon | result | DE-9IM |
|---|---|---|
| `s_intersects` | matches | correct |
| `s_disjoint` | does not match | correct |
| `s_within` | **does not match** | DE-9IM says it should |
| `s_contains` | **does not match** | DE-9IM says it should |

So an entity whose footprint exactly equals the search polygon is absent from an
`s_within` result. This is the same root cause as `s_equals` being unavailable: a shared
edge is classified as boundary contact rather than containment. It also generalises
[boundary contact](#relation-semantics) — that is not a point-on-edge quirk but the rule.

If exact-match entities matter, use the two-pass form above. Pinned by
`testCoincidentGeometryIsNotWithinOrContains`.

## Query geometries

Either the CQL2 `bbox` form or a GeoJSON object:

| Form | Example |
|---|---|
| `bbox` | `{"bbox":[112,-44,154,-10]}` |
| `Point` | `{"type":"Point","coordinates":[116.35,-32.77]}` |
| `MultiPoint` | `{"type":"MultiPoint","coordinates":[[...],[...]]}` |
| `LineString` | `{"type":"LineString","coordinates":[[...],[...]]}` |
| `MultiLineString` | `{"type":"MultiLineString","coordinates":[[[...]],[[...]]]}` |
| `Polygon` | `{"type":"Polygon","coordinates":[shell, hole1, ...]}` |
| `MultiPolygon` | `{"type":"MultiPolygon","coordinates":[[shell],[shell]]}` |
| `GeometryCollection` | `{"type":"GeometryCollection","geometries":[...]}` |

Several query geometries are **unioned**: a shape satisfying the relation against any one
of them matches. GeoJSON coordinate order is `[lon, lat]`.

The CQL2 `bbox` form is recognised by having **no** `type`. RFC 7946 permits an optional
`bbox` member on any GeoJSON object, where it is metadata describing the geometry rather
than the geometry itself, and GIS exports emit it routinely — so a geometry carrying one
is read from its `coordinates`, not its `bbox`.

### Zero-area query geometries do not match point data

A `Point` or `LineString` query geometry matches areal indexed shapes (polygons) but
**not** `POINT`-indexed entities. Lucene computes relations against indexed triangles, and
when neither side has area no intersection is reported. This holds for the dedicated
point-query API too, so it is a property of `LatLonShape` rather than a choice here.

The practical consequence: to find point-indexed entities at a location, use a small
`bbox` or `Polygon` rather than a `Point`. Point-in-polygon queries — the common case —
work exactly as expected. This is a silent empty result rather than an error, so it is
pinned by a test.

## Distance queries

`s_dwithin` matches entities within a radius of a point. It is an **extension**: CQL2 1.0's
spatial classes are purely topological and define no distance operator.

```json
{"op":"s_dwithin","args":[
  {"property":"urn:jena:lucene:field#location"},
  {"type":"Point","coordinates":[116.35,-32.77]},
  5000
]}
```

The third argument is the radius in **metres**, always. There is no units argument: Lucene's
circle takes metres, and a units parameter would be a second parser and a second class of
bug for no gain.

The geometry must be a GeoJSON `Point`; a radius around anything else is not expressible as
a single circle and raises. A third argument on a *topological* operator also raises rather
than being ignored, since accepting it would answer a different question than the one asked.

Unlike a `Point` used as a topological query geometry, a circle has area, so `s_dwithin`
matches point-indexed entities correctly.

## Relation semantics

These follow from DE-9IM, which both CQL2 (via OGC Simple Features clause 6.1.15) and
GeoSPARQL 1.1 (Table 2) require. Lucene agrees on all of them.

**A multi-valued field is evaluated as one collection, which is a divergence from
GeoSPARQL.** Lucene indexes every value of a field into one document, and `WITHIN`,
`CONTAINS` and `DISJOINT` then require *every* shape on that document to satisfy the
relation. An entity with a WA point and an NSW point *intersects* a WA box but is not
*within* it and is not *disjoint* from it.

GeoSPARQL says otherwise. Its query-rewrite rule is a graph pattern:

```sparql
?f1 geo:hasDefaultGeometry ?g1 . ?f2 geo:hasDefaultGeometry ?g2 . ?g1 geo:sfWithin ?g2
```

With several geometries bound to `?g1` that is **existential**: the feature matches if
*any* of its geometries is within. So the spec-aligned answer for a feature carrying
several separate geometry literals is any-of, and Lucene gives all-of.

The distinction that matters is between one literal and several. A single
`MULTIPOLYGON` is one geometry, and DE-9IM on it genuinely does require the whole thing to
be inside; all-of is right there. Several separate `geo:asWKT` literals are several
geometries, and any-of is right. Lucene cannot tell them apart, because both end up as
shapes on one document.

`s_intersects` is unaffected, since any-of and all-of coincide for intersection. The
divergence is limited to `s_within`, `s_contains` and `s_disjoint`.

It matters most when a field aggregates geometries from *related* resources, for example
an `sh:path` with an inverse step pulling in the positions of every borehole attached to a
report. Requiring all of them to fall inside a search box is almost never what is meant.
Until this is addressed, prefer `s_intersects` on a multi-valued geometry field, or model
the geometries as a nested scope so each gets its own document.

**An entity with no geometry matches no relation, including `s_disjoint`.** GeoSPARQL's
query-rewrite rule must bind a geometry before the relation function runs, and CQL2 makes
a predicate with a NULL geometry evaluate to NULL. Lucene only visits documents that have
the field. All three agree, so an entity with no `geo:asWKT` is absent from every spatial
result — which is easy to get wrong for `s_disjoint`.

**Boundary contact is not "within".** DE-9IM `sfWithin` is `T*F**F***`, requiring a
non-empty interior-interior intersection, so a point lying exactly on the query polygon's
edge is not within it. Lucene agrees. Note also that coordinates are quantised to roughly
a centimetre on encoding, so exact boundary coincidence is not something to rely on
either way.

## Querying with CQL2-JSON spatial filters

Use a spatial operator in the CQL2-JSON filter argument of `luc:query`:

```sparql
PREFIX luc: <urn:jena:lucene:index#>

SELECT ?entity ?score WHERE {
    (?hit ?entity ?score) luc:query ("default" "*"
        '{"op":"s_intersects","args":[{"property":"urn:jena:lucene:field#location"},{"bbox":[112,-44,154,-10]}]}'
        20)
}
```

The `bbox` array follows the CQL2 convention: `[swLon, swLat, neLon, neLat]`.

A GeoJSON `Polygon` may also be given. Ring 0 is the exterior shell and rings 1..n are
interior rings (holes); holes are honoured, so a donut-shaped query polygon does not
match entities sitting inside the hole. GeoJSON coordinate order is `[lon, lat]`.

### Combining text search with spatial filter

```sparql
SELECT ?entity ?score WHERE {
    (?hit ?entity ?score) luc:query ("default" "gold mine"
        '{"op":"s_intersects","args":[{"property":"urn:jena:lucene:field#location"},{"bbox":[115,-35,120,-30]}]}'
        20)
}
```

This returns entities matching "gold mine" that are within the Western Australia bounding box.

### Combining with other CQL2 filters

Spatial filters can be combined with property filters using `and`:

```sparql
SELECT ?entity ?score WHERE {
    (?hit ?entity ?score) luc:query ("default" "*"
        '{"op":"and","args":[{"op":"=","args":[{"property":"urn:jena:lucene:field#state"},"WA"]},{"op":"s_intersects","args":[{"property":"urn:jena:lucene:field#location"},{"bbox":[115,-35,120,-30]}]}]}'
        20)
}
```

## Current limitations

- `s_equals`, `s_crosses`, `s_overlaps` and `s_touches` have no `lucene-core` relation and
  raise. See [two passes](#getting-equals-covers-touches-crosses-or-overlaps-use-two-passes).
- An indexed geometry identical to the query geometry does not satisfy `s_within` or
  `s_contains`; see [Coincident geometry](#coincident-geometry-is-not-within-or-contains).
- A zero-area query geometry does not match point-indexed data; see "Query geometries" below.

## Unsupported spatial filters raise

A spatial filter that cannot be pushed to Lucene raises `TextIndexException`; it is
never ignored. This covers an unsupported operator, an unsupported query geometry, a
property that names no field, and a property whose field is not a `LatLonField`.

The alternative would be to drop the filter, which is what earlier versions did. A
dropped spatial filter is not applied anywhere — nothing re-evaluates it in ARQ — so the
query silently returns **more** rows than were asked for. Inside an `or` it is worse: the
whole disjunction is abandoned, so every other branch is dropped too. Raising turns a
wrong answer into a refused one.

Note that query APIs take **field IRIs**, not bare field names. A bare name resolves to
no field and now raises rather than silently dropping the filter.
