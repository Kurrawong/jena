---
title: "Spatial: full geometry-type coverage and CQL spatial operators"
date: "2026-09-04"
status: "Plan; nothing built yet"
issues: "#159 #160 #161"
---

# Spatial: Full Geometry-Type Coverage and CQL Spatial Operators

Implementation plan for issues [#159](https://github.com/Kurrawong/jena/issues/159),
[#160](https://github.com/Kurrawong/jena/issues/160) and
[#161](https://github.com/Kurrawong/jena/issues/161), split into five PRs that land in
a fixed order. Every claim about current behaviour was verified against `main` at
`536b979b4f` by reading the cited line. The line numbers quoted in the three issues
were taken from a different branch and are off by up to ~200 lines; the numbers here
are the ones that apply to `main`.

## Why the issues are not a sufficient spec

The issues are accurate problem statements, but implementing straight from them would
leave six decisions unmade or wrong:

1. **There is a second silent-drop path the issues miss.** `compileSpatial` returns a
   residual not only for unsupported *operators* (`CqlToLuceneCompiler.java:646`) but
   also for unsupported *query geometries* — any GeoJSON that is neither `bbox` nor
   `Polygon` falls off the end of the method at `:690` as `CompileResult(null, spatial)`.
   An `s_intersects` against a GeoJSON `Point` is dropped exactly like an `s_within`.
   PR 1 has to close both.
2. **The residual policy has to be decided, and scoped.** Non-spatial residuals (a
   comparison on a non-indexed field, `:482`) go down the same drop path. Whether those
   should also throw is a real question with callers on the other side of it. This plan
   scopes fail-loudly to spatial only and files the wider question as a follow-up.
3. **An existing test asserts the bug.** `TestSpatialFiltering.testUnsupportedSpatialOpIsResidual`
   (`:288`) asserts that `s_within` returns `>= 4` unfiltered rows. It must be inverted
   in PR 1, not left to fail.
4. **Lucene relation semantics on multi-valued and geometry-less docs are not obvious**
   and the CQL2 spec does not settle them for us. `WITHIN` on a doc with two shapes,
   `DISJOINT` on a doc with no shape — each needs a test that pins the behaviour and a
   doc line that states it.
5. **`s_dwithin` has no CQL2-JSON syntax to copy.** It needs designing (argument shape,
   units, parser change), which is why it is its own PR.
6. **PR order matters.** Fail-loudly must land before the operator implementations, or
   the "not yet supported" set has no defined behaviour between PRs.

## Current state (verified)

### Index side — `ShaclTextIndexLucene.parseWktToLuceneFields` (`:1580-1625`)

Called from both literal-value paths (`:1414` and `:1482`). Dispatch is a three-arm
`if/else if/else if` on `Point`, `Polygon`, `MultiPolygon`; the `else` at `:1615`
logs `Unsupported geometry type for LATLON field` and returns. Two consequences:

- The five other JTS types (`LineString`, `MultiLineString`, `LinearRing`,
  `MultiPoint`, `GeometryCollection`) index **nothing** for the field.
- The `if (stored) fields.add(new StoredField(...))` sits *after* the early return, so
  a skipped geometry is not stored either.

`jtsPolygonToLucene` (`:1637-1659`) already handles interior rings correctly.

### Query side — `CqlToLuceneCompiler.compileSpatial` (`:644-697`)

- Accepts only `"s_intersects"` (`:646`); seven other ops return residual.
- Accepts only `bbox` (`:660`) and GeoJSON `Polygon` (`:675`); anything else returns
  residual at `:690`.
- Polygon path reads `coordinates.get(0)` only (`:679`): interior rings dropped.
- Both branches hard-code `QueryRelation.INTERSECTS`.
- Precedent for throwing already exists in this method: bbox of the wrong size throws
  `TextIndexException` (`:663`).

`CqlParser.SPATIAL_OPS` (`:49-52`) whitelists all eight ops; `parseSpatial` (`:163`)
requires exactly two args and stores the geometry as its raw JSON string.

### What happens to a residual

Nothing. All four consumers in `ShaclTextIndexLucene` (`:793`, `:2563`, `:2650`,
`:2699`) are `if (residual != null) log.warn(...)` and the value is never read again.
There is no ARQ fallback. The AND fold (`:148-154`) keeps pushable siblings and drops
the residual; the OR fold (`:259-263`) abandons the **entire disjunction** the moment
one branch is unpushable. So an `s_within` inside an OR silently deletes every other
arm of that OR too.

### Lucene 10.3.1 surface available and unused

`LatLonShape.createIndexableFields(String, geo.Line)`; `newGeometryQuery(String,
QueryRelation, LatLonGeometry...)` with `Point`, `Line`, `Polygon`, `Rectangle`,
`Circle`; `newDistanceQuery(String, QueryRelation, Circle...)`;
`QueryRelation.{INTERSECTS, WITHIN, CONTAINS, DISJOINT}`. Verified with `javap`
against the jar in `~/.m2`.

## PR plan

| # | Title | Closes | Size | Depends on |
|---|-------|--------|------|-----------|
| 1 | Spatial: raise on unpushable spatial filters instead of dropping them | #160 (part) | S | — |
| 2 | Spatial: index every JTS geometry type | #159 | M | — |
| 3 | Spatial: honour interior rings in GeoJSON query polygons | #161 | S | — |
| 4 | Spatial: `s_within`, `s_contains`, `s_disjoint` and all GeoJSON query geometries | #160 (rest) | M | 1, 3 |
| 5 | Spatial: `s_dwithin` distance queries | — (new issue) | M | 1, 4 |

PRs 1, 2 and 3 are independent and can be raised in parallel. PR 4 rebases on 1 and 3.
PR 5 rebases on 4. Each PR follows CLAUDE.md: red commit first, green commit second,
docs in the same PR.

---

### PR 1 — Raise on unpushable spatial filters

**Change.** In `compileSpatial`, replace both `return new CompileResult(null, spatial)`
sites (`:647`, `:690`) with `throw new TextIndexException(...)`. Messages name the op or
the geometry type and list what *is* supported, e.g.

```
Spatial operator 's_within' is not supported. Supported: s_intersects.
Spatial query geometry 'Point' is not supported. Supported: bbox, Polygon.
```

The supported lists are built from constants so PRs 4 and 5 update one place.

Because the throw happens inside `compileExpr`, it propagates through the AND/OR/NOT
folds and out of every caller — `queryWithCql`, the facet paths, `ShaclTextQueryPF`
and `TextFacetPF` — with no changes to those sites. The four `log.warn` residual sites
stay as they are for non-spatial residuals.

**Why throw rather than post-filter.** The property function returns entity IRIs, not
geometries. A JTS post-filter would need the WKT, which is only in the Lucene doc when
`idx:stored` is set, and otherwise means a dataset read per hit inside the PF. That is
a design change, not a bug fix, and it is not what any of the three issues asks for.

**Tests** (`TestSpatialFiltering`, JUnit 4 to match the file):

- Invert `testUnsupportedSpatialOpIsResidual` → `testUnsupportedSpatialOpThrows`:
  `s_within` throws `TextIndexException` whose message contains `s_within`.
- `testUnsupportedQueryGeometryThrows`: `s_intersects` with `{"type":"Point",...}`
  throws (this is the second silent path).
- `testUnsupportedSpatialOpInsideOrThrows`: `{"op":"or",[<pushable =>, <s_within>]}`
  throws rather than returning the `=` branch's rows. Pins the OR-fold hazard.
- `testUnsupportedSpatialOpInsideAndThrows`: same for AND.

**Docs.** `09-spatial.md` "Current limitations": replace the second bullet
("treated as residual ... not applied") with the new behaviour.

**Follow-up issue to file, not fix here:** should *non-spatial* residuals also throw?
Today a comparison on a non-indexed field silently widens the result set the same way.

---

### PR 2 — Index every JTS geometry type

**Change.** Replace the dispatch chain in `parseWktToLuceneFields` with a recursive
helper:

```java
private static void addGeometryFields(List<IndexableField> out, String fieldName, Geometry geom) {
    switch (geom) {
        case Point p            -> Collections.addAll(out, LatLonShape.createIndexableFields(fieldName, p.getY(), p.getX()));
        case LineString ls      -> Collections.addAll(out, LatLonShape.createIndexableFields(fieldName, jtsLineToLucene(ls)));
        case Polygon poly       -> Collections.addAll(out, LatLonShape.createIndexableFields(fieldName, jtsPolygonToLucene(poly)));
        case GeometryCollection gc -> { for (int i = 0; i < gc.getNumGeometries(); i++) addGeometryFields(out, fieldName, gc.getGeometryN(i)); }
        default                 -> log.warn("Unsupported geometry type for LATLON field '{}': {}", fieldName, geom.getGeometryType());
    }
}
```

`LinearRing extends LineString` and `MultiPoint`, `MultiLineString`, `MultiPolygon`
all extend `GeometryCollection` in JTS, so four cases cover all eight types and the
existing `MultiPolygon` loop is subsumed. `jtsLineToLucene` mirrors the coordinate
extraction in `jtsPolygonToLucene` (`y → lat`, `x → lon`) into a `geo.Line`.

Move the `if (stored)` block so the WKT is stored whenever the helper produced at least
one field. Keep the outer `catch (Exception)` (`:1622`) — it is what turns a Lucene
`IllegalArgumentException` on a degenerate shape into a warning rather than a failed
transaction, and that is the right call for an indexer.

**Tests.**

Unit level, against `parseWktToLuceneFields` directly (matches the four existing
`testParseWktToLuceneFields*` tests):

- `LINESTRING`, `MULTILINESTRING`, `MULTIPOINT`, `GEOMETRYCOLLECTION(POINT, LINESTRING,
  POLYGON)`: each produces non-empty fields and, with `stored=true`, a `StoredField`.
- `LINESTRING(1 1, 1 1)` (degenerate): no exception escapes, fields empty.

Integration level, extending `loadTestData` with new sites and querying through
`queryWithCql` as the existing tests do:

- **`pipeline`** — CRS84 `LINESTRING(114 -25, 121 -25)`. Both endpoints outside the WA
  bbox `[115,-34,120,-20]`, the segment passes through it. Must match: this proves
  true segment intersection, not vertex-in-box.
- **`bores`** — `MULTIPOINT` with one point in WA and one in NSW. Matches the WA bbox
  and the NSW bbox.
- **`tenement-complex`** — `GEOMETRYCOLLECTION` with a Point in QLD and a Polygon in
  WA. Matches both bboxes.
- **`ring-as-line`** — a closed `LINESTRING` around Boddington. A bbox strictly inside
  the ring but not touching it must **not** match (a line has no interior).
- **`dateline`** — `LINESTRING(179 -17, -179 -17)`. Assert whichever way Lucene reads
  it; the test exists to pin behaviour, and the doc gains a line saying lines and
  polygons are not split at the antimeridian. Not fixing that here.

**Docs.** `09-spatial.md` "Supported geometry types": list all eight; remove the
"logged as warnings and skipped" sentence; add the antimeridian note. `05-testing.md`
test count.

---

### PR 3 — Honour interior rings in GeoJSON query polygons

**Change.** Extract `geoJsonPolygonToLucene(JsonArray coordinates)` from the inline
code at `:675-688`. Ring 0 is the shell; rings `1..n` become `geo.Polygon` holes passed
to the `Polygon(lats, lons, holes...)` constructor, exactly as `jtsPolygonToLucene`
does for indexed polygons. PR 4 reuses this helper.

**Tests.**

- `testQueryPolygonHoleExcludesEntityInsideHole`: donut around Boddington (outer ring
  ~±1°, inner ring ~±0.2°). Boddington is **not** returned. Add a site inside the ring
  body that **is** returned.
- Multi-hole variant with two sites, one in each hole.

**Docs.** One sentence under "Querying" noting holes are honoured.

---

### PR 4 — Relations and full GeoJSON query-geometry support

**Change.** Two orthogonal generalisations of `compileSpatial`, plus tests for their
product.

*Operators.* Map via a small table:

| CQL op | `QueryRelation` |
|---|---|
| `s_intersects` | `INTERSECTS` |
| `s_within` | `WITHIN` |
| `s_contains` | `CONTAINS` |
| `s_disjoint` | `DISJOINT` |

`s_equals`, `s_crosses`, `s_overlaps`, `s_touches` keep throwing from PR 1 with a
message that says they have no Lucene equivalent. CQL argument order is
`op(property, geometry)`, so `s_within` means *indexed shape within query geometry*,
which is Lucene's `WITHIN`; `s_contains` is *indexed shape contains query geometry*,
which is Lucene's `CONTAINS`. No inversion needed.

*Query geometries.* New helper `geoJsonToLatLonGeometries(JsonObject) →
List<LatLonGeometry>` handling `bbox` (→ `Rectangle`), `Point`, `MultiPoint`,
`LineString`, `MultiLineString`, `Polygon` (via PR 3's helper), `MultiPolygon`,
`GeometryCollection` (recursive). The query becomes one call:

```java
LatLonShape.newGeometryQuery(fieldName, relation, geometries.toArray(LatLonGeometry[]::new));
```

The existing `newBoxQuery` special case goes; `Rectangle` through `newGeometryQuery`
is the same query.

**Semantics to pin with tests, then state in the docs.** These are Lucene's rules,
which is what we ship, but nobody should have to discover them:

- **`WITHIN` on a multi-valued field** — Lucene requires *every* indexed shape on the
  doc to be within the query geometry. A site with a point in WA and a point in NSW is
  *not* within the WA bbox. Test with `bores` from PR 2.
- **`DISJOINT` on a doc with no geometry** — Lucene's shape queries only match docs
  that have the field. A site with no `geo:asWKT` is neither intersecting nor
  disjoint. Test with a geometry-less site; assert it is absent from both.
- **`CONTAINS` against a point-indexed doc** — matches only if the query geometry is
  that same point. Test with a Point query on `mount-isa`.
- **Multi-geometry query with `WITHIN`** — Lucene treats the query geometries as a
  union. Test that a shape within *either* of two query polygons matches.

**Test matrix** — the four relations × {bbox, Point, LineString, Polygon-with-hole,
MultiPolygon, GeometryCollection} query geometries, against the fixture set from PR 2.
Not every cell needs its own method, but every cell needs an assertion; a parameterised
loop over a table is fine and keeps the file readable. Plus the four semantic tests
above, plus one that `s_touches` still throws after this PR.

**Docs.** `09-spatial.md`: replace the `s_intersects`-only query section with an
operator table and a query-geometry table; add a "Semantics" subsection with the four
bullets above; delete the first and second "Current limitations" bullets.

---

### PR 5 — `s_dwithin` distance queries

**Design.** CQL2-JSON has no core distance operator; CQL1 had `DWITHIN(prop, geom,
distance, units)`. Proposed shape, three args:

```json
{"op":"s_dwithin","args":[{"property":"urn:jena:lucene:field#location"},
                          {"type":"Point","coordinates":[116.35,-32.77]},
                          5000]}
```

Distance is **metres**, always; no units argument. Lucene's `geo.Circle` takes metres,
and a units argument is a second parser and a second class of bug for no gain.

**Change.**

- `CqlParser.SPATIAL_OPS` gains `s_dwithin`; `parseSpatial` accepts 2 or 3 args and
  rejects a third arg on any op other than `s_dwithin`. `CqlSpatial` gains a nullable
  `Double distance` component.
- `compileSpatial`: for `s_dwithin` the geometry must be a `Point` (throw otherwise,
  with a message), build `new Circle(lat, lon, metres)`, and issue
  `LatLonShape.newDistanceQuery(fieldName, INTERSECTS, circle)`.

**Tests.**

- 100 km around Boddington returns `boddington` only; 3 000 km returns every mainland
  site and not `auckland`.
- `pipeline` line from PR 2 passes ~1° from a point: a radius that reaches the line
  matches, a shorter one does not. Proves line-vs-circle intersection, not
  vertex-in-circle.
- `s_dwithin` with a Polygon geometry throws. `s_intersects` with three args throws.

**Docs.** New "Distance queries" section in `09-spatial.md`; delete the third
"Current limitations" bullet. File the issue when raising the PR so it has a number to
close.

---

## Deliberately out of scope

- **Post-filtering `s_equals` / `s_crosses` / `s_overlaps` / `s_touches` with JTS.**
  Needs the WKT at query time, which means either `idx:stored` or a dataset read per
  hit. If wanted, it is a design doc of its own.
- **Distance sorting / nearest-first.** No `LatLonDocValuesField` is written and
  `LatLonField` rejects `idx:sortable` (`:2770`). A separate feature, not a gap.
- **Antimeridian splitting.** Lucene does not split lines or polygons across ±180°;
  PR 2 pins the behaviour in a test and documents it.
- **`XYShape` / projected CRS indexing.** Everything is transformed to WGS84 on
  ingest; that is correct.
- **Throwing on non-spatial residuals.** Same hazard, different blast radius; filed
  as a follow-up from PR 1.

## Verification per PR

```bash
mvn test -pl jena-text -Dtest=TS_Text 2>&1 | grep -E "Tests run|-- in .*TestSpatialFiltering"
```

`TestSpatialFiltering` is already in `TS_Text.@SelectClasses` (`TS_Text.java:116`), so
new methods run; confirm the `-- in` line and that the total test count rises by the
number of methods added. Then the full module:

```bash
mvn test -pl jena-text
```
