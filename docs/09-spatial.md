# Spatial Filtering

A `LatLonField` indexes WKT or GeoJSON geometry literals as Lucene `LatLonShape` fields.
CQL2-JSON spatial operators in the `cqlFilter` argument of `luc:query` and `luc:facet`
then filter entities by location.

## Configuration

Define a `LatLonField` and bind it to the geometry predicate:

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
    sh:property [ idx:field field:location ; sh:path geo:asWKT ] .
```

A `LatLonField` cannot be faceted or sorted on. A sort on it raises, and so does an `=`
filter.

## Indexed geometry

### Geometry types

| WKT | Notes |
|---|---|
| `POINT(x y)` | |
| `LINESTRING(x1 y1, x2 y2, ...)` | A line has no interior. A query region inside a closed ring drawn as a `LINESTRING` does not intersect it |
| `POLYGON((...))` | Holes are indexed |
| `MULTIPOINT((x1 y1), (x2 y2), ...)` | |
| `MULTILINESTRING((...), (...))` | |
| `MULTIPOLYGON(((...)), ((...)))` | |
| `GEOMETRYCOLLECTION(...)` | Members are indexed recursively, so collections can nest |

The members of a collection go into the same Lucene field, so an entity matches if any
member matches.

If a geometry cannot be indexed, the index logs a warning and skips it. It also skips the
stored copy, so every stored geometry is one that spatial filters can find.

### WKT and GeoJSON literals

A `LatLonField` accepts WKT and GeoJSON. Bind the predicate that the data uses:

```turtle
:SiteShape
    sh:property [ idx:field field:location ; sh:path geo:asGeoJSON ] .
```

The literal's datatype selects the parser:

| Datatype | Result |
|---|---|
| `geo:wktLiteral` | Parsed as WKT. A value that starts with `{` is a data error and is not indexed |
| `geo:geoJSONLiteral` | Parsed as GeoJSON. A value that does not start with `{` is a data error and is not indexed |
| `geo:gmlLiteral` | Not indexed. The log says that GML is not supported |
| Any other datatype, such as `xsd:string` | Parsed as GeoJSON if it starts with `{`, otherwise as WKT. A warning is logged once per field |
| None (an `idx:externalSource` column) | Parsed as GeoJSON if it starts with `{`, otherwise as WKT. No warning |

The warning for other datatypes exists because GeoSPARQL `geof:` functions return unbound
for a geometry without `geo:wktLiteral` or `geo:geoJSONLiteral`. An unbound value in a
`FILTER` is false. Such a geometry matches in `luc:query` and matches nothing in a
`geof:` filter. Give geometry literals a GeoSPARQL datatype before you use the
[two-pass form](#other-relations-two-passes).

GeoJSON is always WGS84 longitude and latitude (RFC 7946), so it has no CRS to handle.
The index ignores a GeoJSON `bbox` member. It indexes every geometry type, holes
included. It accepts a bare geometry, a `Feature` and a `FeatureCollection`, and indexes
every geometry in a `FeatureCollection`.

One field can hold WKT for some entities and GeoJSON for others. The same location in
GeoJSON and in EPSG:4326 WKT gives the same index entry.

### Coordinate reference systems

Lucene stores WGS84 latitude and longitude in degrees. The index converts WKT as
follows:

| CRS in the WKT | Axis order | Conversion |
|---|---|---|
| None (CRS84, the GeoSPARQL default) | lon, lat | Axes normalised |
| `<http://www.opengis.net/def/crs/EPSG/0/4326>` | lat, lon | Axes normalised, no transform |
| `<http://www.opengis.net/def/crs/EPSG/0/4283>` (GDA94) | lat, lon | Axes swapped, no datum transform |
| `<http://www.opengis.net/def/crs/EPSG/0/7844>` (GDA2020) | lat, lon | Axes swapped, no datum transform |
| Any other CRS, for example EPSG:28350 | Depends on the CRS | Transformed to WGS84 with Apache SIS |

```turtle
PREFIX geo: <http://www.opengis.net/ont/geosparql#>

# EPSG:4326: lat, lon
ex:site-a geo:asWKT "<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(-33.87 151.21)"^^geo:wktLiteral .

# CRS84: lon, lat
ex:site-b geo:asWKT "POINT(151.21 -33.87)"^^geo:wktLiteral .
```

Both literals index the same point in Sydney.

GDA94 and GDA2020 get no datum transform. EPSG defines the GDA2020 to WGS 84
transformation as a null transformation, and Lucene stores coordinates to about one
centimetre. The bundled Apache SIS does not recognise either CRS, so the index swaps the
axes itself.

Keep the `<...EPSG/0/7844>` prefix on GDA2020 data. Bare WKT states CRS84, which is a
different datum. A client that draws such a literal must remove the prefix and pick the
axis order from the CRS. `demo/app-static/wkt.js` does this: it reads EPSG:4326, 4283 and
7844 as lat/lon and bare or CRS84 WKT as lon/lat, handles every geometry type with holes,
and returns nothing for a projected CRS.

### Antimeridian

Lucene does not split geometries at the antimeridian. A line from longitude 179 to −179
goes the long way round the globe. To index the short crossing, split the geometry into
two parts before loading.

## Queries

### Examples

Entities inside a bounding box:

```sparql
PREFIX luc: <urn:jena:lucene:index#>

SELECT ?entity ?score WHERE {
  (?hit ?entity ?score) luc:query (
    "default" "default" "*"
    '{"op":"s_intersects","args":[{"property":"urn:jena:lucene:field#location"},{"bbox":[112,-44,154,-10]}]}'
    "" 20 0) .
}
```

Entities that match "gold mine" inside a box over south-west Western Australia:

```sparql
(?hit ?entity ?score) luc:query (
  "default" "default" "gold mine"
  '{"op":"s_intersects","args":[{"property":"urn:jena:lucene:field#location"},{"bbox":[115,-35,120,-30]}]}'
  "" 20 0) .
```

A spatial filter combined with another filter:

```sparql
(?hit ?entity ?score) luc:query (
  "default" "default" "*"
  '{"op":"and","args":[{"op":"=","args":[{"property":"urn:jena:lucene:field#state"},"WA"]},{"op":"s_intersects","args":[{"property":"urn:jena:lucene:field#location"},{"bbox":[115,-35,120,-30]}]}]}'
  "" 20 0) .
```

### Operators

The argument order is `op(property, geometry)`, so `s_within` means "the indexed shape is
within the query geometry".

| CQL2 operator | Lucene `ShapeField.QueryRelation` | Matches when |
|---|---|---|
| `s_intersects` | `INTERSECTS` | The indexed shape and the query geometry share a point |
| `s_within` | `WITHIN` | The indexed shape is inside the query geometry |
| `s_contains` | `CONTAINS` | The indexed shape encloses the query geometry |
| `s_disjoint` | `DISJOINT` | The indexed shape and the query geometry share no point |
| `s_dwithin` | Circle query | The indexed shape is within a distance of a point. See [Distance queries](#distance-queries) |

`s_equals`, `s_touches`, `s_crosses` and `s_overlaps` have no Lucene relation. They
raise `TextIndexException`. To test them, use
[two passes](#other-relations-two-passes).

### Query geometries

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

- `bbox` is `[swLon, swLat, neLon, neLat]`. An object with no `type` is read as a
  `bbox`. An object with a `type` is read from its `coordinates`, and its RFC 7946 `bbox`
  member is ignored.
- GeoJSON coordinates are `[lon, lat]`.
- In a `Polygon`, ring 0 is the shell and the other rings are holes. An entity inside a
  hole does not match.
- A query with several geometries matches a shape that satisfies the relation with any
  one of them.

### Distance queries

`s_dwithin` matches entities within a radius of a point. CQL2 1.0 defines no distance
operator, so `s_dwithin` is an extension.

```json
{"op":"s_dwithin","args":[
  {"property":"urn:jena:lucene:field#location"},
  {"type":"Point","coordinates":[116.35,-32.77]},
  5000
]}
```

- The third argument is the radius in metres. There is no units argument.
- The geometry must be a GeoJSON `Point`. Any other geometry raises.
- A third argument on any other spatial operator raises.
- A circle has area, so `s_dwithin` matches point data.

## Results to check

### Point and line query geometries do not match points

A `Point` or `LineString` query geometry matches polygons. It does not match entities
indexed as `POINT`. Lucene computes relations on triangles, and when neither side has
area it reports no intersection. The query returns no rows and no error.

To find point data at a location, use a small `bbox` or `Polygon`. A point-in-polygon
query, with point data and a polygon query geometry, works.

### Identical geometry fails `s_within` and `s_contains`

| Indexed geometry identical to the query polygon | Result | DE-9IM |
|---|---|---|
| `s_intersects` | Matches | Matches |
| `s_disjoint` | No match | No match |
| `s_within` | **No match** | Matches |
| `s_contains` | **No match** | Matches |

Lucene's `WITHIN` and `CONTAINS` treat a shared edge as boundary contact. An entity whose
footprint equals the search polygon is missing from an `s_within` result. To include such
entities, use [two passes](#other-relations-two-passes).
Test: `testCoincidentGeometryIsNotWithinOrContains`.

### Boundary contact

A point on the edge of the query polygon is not within it. DE-9IM `sfWithin` is
`T*F**F***`, which needs an interior intersection, and Lucene agrees. Coordinates are
stored to about one centimetre, so do not depend on exact boundary contact.

### Multi-valued fields: every value must match

Lucene writes every value of a field into one document and keeps no record of which value
each part came from. `WITHIN`, `CONTAINS` and `DISJOINT` then require every shape in the
document to satisfy the relation. An entity with a point in WA and a point in NSW
intersects a WA box. It is not within the box, and it is not disjoint from it.

GeoSPARQL evaluates each geometry of a feature separately: a feature matches if any of its
geometries matches. The two readings agree when the field holds one `MULTIPOINT` or
`MULTIPOLYGON` literal. They differ when the field holds several separate literals, for
example the positions of every borehole reached by an inverse `sh:path`.

`s_intersects` gives the same result under both readings.

On a multi-valued geometry field:

- Use `s_intersects`.
- For `s_within`, `s_contains` or `s_disjoint` per value, use
  [two passes](#other-relations-two-passes). The
  SPARQL filter tests each stored geometry separately.

A spatial filter on a geometry field inside an `idx:nested` block matches no entities.
`compileSpatial` runs the query on the child documents and never lifts it to the parent. Test: `TestNestedGeometryScope`.

### Entities without geometry

An entity with no value in the field matches no spatial operator, `s_disjoint` included.
GeoSPARQL and CQL2 give the same result.

## Other relations: two passes

Equals, covers, covered by, touches, crosses and overlaps all imply intersection. To test
one of them:

1. Filter with `s_intersects` in `luc:query`. This pass uses the index, so facets and
   paging work.
2. Test the exact relation on the geometry with a `geof:` filter function.

```sparql
PREFIX luc:  <urn:jena:lucene:index#>
PREFIX geo:  <http://www.opengis.net/ont/geosparql#>
PREFIX geof: <http://www.opengis.net/def/function/geosparql/>

SELECT ?entity WHERE {
  (?hit ?entity ?score) luc:query (
    "default" "default" "*"
    '{"op":"s_intersects","args":[{"property":"urn:jena:lucene:field#location"},{"bbox":[115,-34,118,-31]}]}'
    "" 1000 0) .
  ?entity geo:hasGeometry/geo:asWKT ?wkt .
  FILTER(geof:relate(?wkt, "POLYGON((115 -34,118 -34,118 -31,115 -31,115 -34))"^^geo:wktLiteral,
                     "T*F**FFF*"))
}
```

The second pass needs geometry literals typed `geo:wktLiteral` or `geo:geoJSONLiteral`.
For any other datatype, `geof:` functions return unbound, and the `FILTER` removes the
row without an error.

The second pass runs once for each row of the first pass and uses no index. Keep the
first pass selective: a first pass that matches most of the data makes the query slow.

### Function for each relation

| Relation | Second pass |
|---|---|
| touches | `geof:sfTouches` |
| crosses | `geof:sfCrosses` |
| overlaps | `geof:sfOverlaps` |
| within | `geof:sfWithin` |
| contains | `geof:sfContains` |
| equals | `geof:sfWithin(?a, ?b) && geof:sfContains(?a, ?b)`, or `geof:relate(?a, ?b, "T*F**FFF*")` |
| covers | Four `geof:relate` patterns joined with `\|\|`, below |
| covered by | Four `geof:relate` patterns joined with `\|\|`, below |

`sfTouches`, `sfCrosses`, `sfOverlaps`, `sfWithin` and `sfContains` call the JTS predicate
of the same name. Use them in place of `geof:relate` patterns where they exist.

```sparql
# A covers B
FILTER(geof:relate(?a, ?b, "T*****FF*") || geof:relate(?a, ?b, "*T****FF*")
    || geof:relate(?a, ?b, "***T**FF*") || geof:relate(?a, ?b, "****T*FF*"))

# A is covered by B
FILTER(geof:relate(?a, ?b, "T*F**F***") || geof:relate(?a, ?b, "*TF**F***")
    || geof:relate(?a, ?b, "**FT*F***") || geof:relate(?a, ?b, "**F*TF***"))
```

Test: `testCoversNeedsAPatternDisjunction`, which compares the patterns with JTS
`covers()` and `coveredBy()` in seven cases.

### Pitfalls in the second pass

- **`geof:sfEquals` on points.** It uses the fixed pattern `TFFFTFFFT`, which needs a
  boundary intersection. A point has no boundary, so two identical points are not equal.
  Polygons and lines are not affected. Use the equals forms in the table. Measured with
  jena-geosparql 4.10.0 and 6.2.0-SNAPSHOT. Test: `testJenaSfEqualsMissesIdenticalPoints`.
- **A wrong `geof:relate` pattern of nine characters.** A pattern of any other length
  raises `IllegalArgumentException`. A nine-character pattern with a wrong symbol, such as
  `T*F**FFG*`, returns `false` for every pair. Test:
  `testMistypedRelatePatternIsSilentlyFalse`.
- **One pattern for covers or covered by.** `T*****FF*` alone is contains, and
  `T*F**F***` alone is within. Neither includes boundary cases. For example, a line along
  the edge of a polygon is covered by the polygon and is not within it.
- **`geof:ehCovers`.** Egenhofer relations exclude each other. A polygon is `ehEquals` to
  itself, so `geof:ehCovers` returns `false` for identical geometries.
- **One pattern for touches.** Touches has three DE-9IM patterns, and the right one
  depends on the dimensions of the geometries. Use `geof:sfTouches`.

The `sfWithin && sfContains` form of equals gives the same result as `T*F**FFF*` for
identical points, polygons and lines, and for inner, outer and disjoint pairs. Test:
`testEqualsEquivalences`.

Use `geof:relate` when the relation is data. The pattern can be a variable, for example
`VALUES (?pattern) { ... } BIND(geof:relate(?a, ?b, ?pattern) AS ?ok)`. SPARQL cannot
take a function IRI from a variable.

### GeoSPARQL property functions

`geo:sfWithin` and the other GeoSPARQL property functions, `spatial:nearby` and the box
functions use jena-geosparql's query rewrite and its own STRtree spatial index. They return
no rows when `geosparql:queryRewrite` is false, or when the geometry in that spatial index
is not reached through `geo:hasGeometry`. Use the `geof:` filter functions in the second
pass.

## Errors

A spatial filter that cannot be applied raises `TextIndexException`. These cases raise:

- an unsupported operator;
- an unsupported or empty query geometry;
- a `property` that names no field;
- a `property` whose field is not a `LatLonField`.

The `property` is the field IRI, or the field's `idx:fieldName`.

An `or` that contains such a filter also raises, so no branch of the filter is dropped.

## Lucene modules

Spatial filtering uses `lucene-core`: `LatLonShape` fields queried with
`ShapeField.QueryRelation`, which has four relations.

`lucene-spatial-extras` has eight operations, including `IsEqualTo` and `Overlaps`, and
boundary-inclusive `IsWithin` and `Contains`. The index does not use it, for these
reasons:

- It is not a dependency, and it needs Spatial4j.
- It indexes geometry a second time, as prefix-tree cells and serialised geometry doc
  values.
- Its exact predicates use `SerializedDVStrategy`, which its javadoc describes as slow
  and meant for use after an approximate strategy.
- `CompositeSpatialStrategy`, which combines the two, does not support `Disjoint` or the
  `BBox` operations. `s_disjoint` would stop working.

The two-pass form follows the same design: an approximate index pass, then an exact pass
on the geometry.
