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

- **Point** — `POINT(x y)`
- **Polygon** — `POLYGON((x1 y1, x2 y2, ...))` (with optional holes)
- **MultiPolygon** — `MULTIPOLYGON(((...)), ((...)), ...)` indexed as multiple polygons on the same Lucene field

Other geometry types (MultiPoint, LineString, etc.) are logged as warnings and skipped during indexing.

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
it to make bare CRS84 asserts a datum your data is not in. To serve geometry to web
clients, publish `geo:asGeoJSON` alongside: RFC 7946 fixes GeoJSON to WGS84 lon/lat with no
CRS member, so every map library reads it natively and none has to understand the
GeoSPARQL prefix. The WKT stays authoritative and is what this indexer reads.

### Examples in data

```turtle
@prefix geo: <http://www.opengis.net/ont/geosparql#> .

# EPSG:4326 — lat/lon order (explicit CRS prefix)
ex:site-a geo:asWKT "<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(-33.87 151.21)"^^geo:wktLiteral .

# CRS84 — lon/lat order (bare WKT, no prefix, GeoSPARQL default)
ex:site-b geo:asWKT "POINT(151.21 -33.87)"^^geo:wktLiteral .

# Both index to the same location: Sydney, Australia
```

## Querying with CQL2-JSON spatial filters

Use the `s_intersects` operator in the CQL2-JSON filter argument of `luc:query`:

```sparql
PREFIX luc: <urn:jena:lucene:index#>

SELECT ?entity ?score WHERE {
    (?hit ?entity ?score) luc:query ("default" "*"
        '{"op":"s_intersects","args":[{"property":"urn:jena:lucene:field#location"},{"bbox":[112,-44,154,-10]}]}'
        20)
}
```

The `bbox` array follows the CQL2 convention: `[swLon, swLat, neLon, neLat]`.

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

- Only `s_intersects` with `bbox` geometry is supported (MVP). Other spatial operators (`s_within`, `s_contains`, `s_disjoint`, etc.) and GeoJSON geometry types will be added incrementally.
- Unsupported spatial operators are treated as residual (logged as a warning, not applied as a filter).
- Distance queries are not yet supported.
