---
title: "range facet implementation plan"
date: "2026-04-07"
---

# 2026-04-07 Range Facet Implementation Plan

## Status

This note records the agreed implementation plan for adding numeric range facets.

It does not update the existing user docs. The current edits in:

- `docs/01-user-guide.md`
- `docs/02-sparql-api.md`
- `docs/03-configuration.md`
- `docs/04-architecture.md`

still need a follow-up pass after the implementation lands, because several of the current proposed doc changes no longer match the settled design below.

## Review Verification (2026-04-07)

All key technical claims in this plan have been verified against the current codebase in `ShaclTextIndexLucene.java` and against Lucene 10.3.1 sources:

- **DOUBLE encoding** — verified in `lucene-core/.../document/DoubleField.java` line 73, `SortedNumericSelector.java` line 87, `DoubleLeafComparator.java` line 81, and `lucene-facet/.../MultiDoubleValuesSource.java` line 82. Store sortable-encoded longs; range facets must use `fromField(name, NumericUtils::sortableLongToDouble)`, not `fromDoubleField()`.
- **Multi-valued numeric docvalues** — confirmed current code at `ShaclTextIndexLucene.addFieldToDoc()` lines 584-624 writes a new `NumericDocValuesField` per value; Lucene silently overwrites. Switch to `SortedNumericDocValuesField`.
- **Numeric docvalues gating** — confirmed lines 593, 607, 621 check only `isSortable()`. Must become `facetable || sortable`.
- **Sorting** — confirmed `buildLuceneSort()` lines 1474-1503 uses basic `SortField`, not `SortedNumericSortField`.
- **SearchExecution caching bug** — confirmed `facetCountsComputed` boolean flag at line 172 caches the first caller's facet params and returns stale results for subsequent callers with different facet requests.
- **Lucene 10.3.1 APIs** — verified `MultiLongValuesSource`, `MultiDoubleValuesSource`, `LongRangeFacetCounts`, `DoubleRangeFacetCounts`, `SortedNumericDocValuesField`, `SortedNumericSortField` are all present in `lucene-facet-10.3.1.jar` and `lucene-core-10.3.1.jar`.
- **PropFuncArg arity** — framework has no hard limit; each PF validates its own. Expanding `TextFacetPF` from 3 to 5 slots is straightforward.

### Additional notes from review

- **Subject arity 4 error message** — `TextFacetPF` should reject arity 4 with an explicit message: "use 3 variables for flat/hierarchical facets or 5 for range facets". Arity 4 is ambiguous.
- **`FacetBucket` → RDF node conversion** — bounds stored as `String` in `FacetBucket` are converted to typed RDF nodes in `TextFacetPF.generateBindings()`. The conversion needs the field's `FieldType`, which is resolved from `FacetBucket.field` via `ShaclIndexMapping.findFieldByName()`.
- **`FacetsCollector` refactor** — step 4 of the implementation order requires refactoring `ShaclTextIndexLucene.getFacetCounts()` / `getFacetCountsWithCql()` (current lines 819, 1385) to accept both flat field names and range specs, performing flat + range counting from the same `FacetsCollector` in one pass.
- **Reindex migration** — the switch from `idx:sortable true` to `idx:facetable true` as the numeric faceting flag is a breaking change for anyone who followed the earlier docs. Call this out in release notes when the work lands.
- **DOUBLE encoding clarification on the "bug" claim** — the current raw-bits encoding (`Double.doubleToRawLongBits`) combined with the current basic `SortField.Type.DOUBLE` path actually sorts correctly for single-valued DOUBLEs (because `DoubleLeafComparator.longBitsToDouble` is the inverse of the stored encoding). The real bugs are (a) multi-valued numeric silently overwrites and (b) once we move to `SortedNumericSortField`, the selector wrap assumes sortable encoding. So the encoding change is driven by the move to `SortedNumericDocValuesField` + `SortedNumericSortField`, not by a pre-existing sort correctness bug. Worth rewording any "sort bug" framing in release notes.

## Settled Decisions

The implementation will follow these decisions:

- Keep range facets on the existing `luc:facet` property function.
- Keep flat, hierarchical, and range facet requests in the same `facetFields` JSON array.
- Use `idx:facetable true` as the public configuration flag for all faceting, including numeric range facets.
- Keep `idx:sortable true` as a sorting flag only.
- Support multi-valued numeric range facets in v1.
- Support multi-valued numeric sorting in the same change.
- Replace string range labels as the public result model with explicit numeric bounds.
- Preserve backward compatibility for existing flat facet queries where practical.

## Public API

### Request Shape

The `facetFields` JSON array will accept two element types:

- string field IRIs for flat and hierarchical facets
- range objects for numeric facets

Range object shape:

```json
{
  "field": "urn:jena:lucene:field#year",
  "ranges": [2020, 2022, 2024, 2026]
}
```

Validation rules:

- `field` must resolve to an `INT`, `LONG`, or `DOUBLE` field
- `ranges` must contain at least two entries
- non-null boundaries must be strictly increasing
- `null` is allowed only at the start and/or end
- `NaN` and infinities are rejected for `DOUBLE`
- requesting a numeric field as a bare string is an error
- requesting a range object on a non-numeric field is an error
- wildcard `"*"` expands only to flat and hierarchical facetable fields

### Result Shape

The settled result model is:

```sparql
(?field ?value ?low ?high ?count) luc:facet (...)
```

Binding semantics:

- flat facets bind `?field`, `?value`, `?count`
- hierarchical facets bind `?field`, `?value`, `?count`
- range facets bind `?field`, `?low`, `?high`, `?count`
- open-ended ranges leave the missing bound unbound
- `?count` remains `xsd:long`

Examples:

- flat facet row: `(?field=<...#category>, ?value="Technology", ?low=unbound, ?high=unbound, ?count=42)`
- closed range row: `(?field=<...#year>, ?value=unbound, ?low="2020"^^xsd:integer, ?high="2022"^^xsd:integer, ?count=35)`
- open-ended row: `(?field=<...#year>, ?value=unbound, ?low=unbound, ?high="2020"^^xsd:integer, ?count=12)`

### Backward Compatibility

The existing 3-slot flat facet form remains valid:

```sparql
(?field ?value ?count) luc:facet (...)
```

Compatibility rules:

- 3-slot subjects remain supported for flat and hierarchical facets
- 5-slot subjects are required for any request that includes a range object
- mixed flat and range requests are only supported with the 5-slot form
- if a range facet is requested with the legacy 3-slot form, raise an explicit error

This keeps existing flat facet queries working while avoiding an ambiguous row shape for range output.

## Configuration Model

### Public Semantics

The correct public model is:

- `idx:facetable true` means the field may be used in faceting
- `idx:sortable true` means the field may be used in sorting

Per field type:

- `KEYWORD` + `idx:facetable true`: flat and hierarchical faceting
- `INT` / `LONG` / `DOUBLE` + `idx:facetable true`: range faceting
- `KEYWORD` + `idx:sortable true`: keyword sorting
- `INT` / `LONG` / `DOUBLE` + `idx:sortable true`: numeric sorting

This removes the current design inconsistency where numeric range facets are documented as faceting but are actually tied to `idx:sortable true`.

### Indexing Consequence

Numeric docvalues must be written when either of these is true:

- `fieldDef.isFacetable()`
- `fieldDef.isSortable()`

This is required because numeric range facets consume docvalues even when the field is not sortable.

### Reindex Requirement

This change requires reindexing for any numeric field that is facetable or sortable.

Reasons:

- numeric facet availability is now keyed off `idx:facetable true`
- numeric docvalues storage will change
- numeric docvalues for multi-valued fields will move to `SortedNumericDocValuesField`
- `DOUBLE` docvalues for sortable and facetable fields will use sortable-long encoding in sorted numeric docvalues

## Lucene Storage Strategy

### Numeric DocValues

Use `SortedNumericDocValuesField` for numeric fields whenever docvalues are required.

That applies to:

- `INT`
- `LONG`
- `DOUBLE`

Use this condition:

- write numeric docvalues if `facetable || sortable`

Why this strategy:

- it supports both single-valued and multi-valued numerics with one storage model
- it avoids invalid repeated `NumericDocValuesField` writes on multi-valued numerics
- it lets range faceting use Lucene's multi-valued range APIs directly
- it lets sorting use `SortedNumericSortField`

### Numeric Encodings

Write values as follows:

- `INT`: store the integer value as a long in `SortedNumericDocValuesField`
- `LONG`: store the long value directly in `SortedNumericDocValuesField`
- `DOUBLE`: store `NumericUtils.doubleToSortableLong(value)` in `SortedNumericDocValuesField`

Keep sortable-encoded longs for `DOUBLE` docvalues in `SortedNumericDocValuesField`.

Rationale:

- `SortedNumericSortField(..., SortField.Type.DOUBLE, ...)` expects selected values to be sortable-encoded and converts them back internally
- raw IEEE double bits do not preserve numeric order inside `SortedNumericDocValues`, so `MIN` / `MAX` selection is wrong for multi-valued double sorting
- `DoubleRangeFacetCounts` expects actual double values from the values source, so the range facet path must decode sortable longs back to doubles

Implication:

- do not use `MultiDoubleValuesSource.fromDoubleField(fieldName)` against sortable-encoded `DOUBLE` docvalues
- use `MultiDoubleValuesSource.fromField(fieldName, NumericUtils::sortableLongToDouble)` for `DOUBLE` range facets

### Indexed and Stored Fields

Keep point fields and stored fields unchanged:

- `IntPoint`, `LongPoint`, `DoublePoint` remain the indexed query/filter fields
- `StoredField` remains the stored-value representation

Only the docvalues strategy changes.

## Sorting Semantics

### Multi-Valued Numeric Sorts

Use `SortedNumericSortField` for numeric sorts.

Selector policy:

- ascending sort uses `MIN`
- descending sort uses `MAX`

This yields intuitive behavior:

- ascending by year sorts on earliest year
- descending by year sorts on latest year

The selector rule must be documented explicitly because it becomes observable for multi-valued numerics.

### Keyword Sorts

Keep existing keyword sorting unchanged.

## Internal Result Model

The current `FacetValue(String value, long count)` model is no longer sufficient.

Replace it with a bucket model that can represent both flat and range output.

Recommended shape:

```java
final class FacetBucket {
    enum Kind { VALUE, RANGE }

    private final Kind kind;
    private final String value;
    private final String low;
    private final String high;
    private final long count;
}
```

Notes:

- flat buckets use `kind=VALUE`, `value != null`, `low/high == null`
- range buckets use `kind=RANGE`, `value == null`, `low/high` set as lexical numeric strings or null for open ends
- `count` stays `long`

Alternative names are fine, but the model must carry explicit bounds rather than a single display label.

## New Request Model

The current `List<String> facetFields` model is no longer sufficient.

Introduce a request model such as:

```java
final class FacetRequest {
    private final List<String> flatFields;
    private final List<RangeFacetSpec> rangeFields;
}

final class RangeFacetSpec {
    private final String fieldIri;
    private final List<String> boundaries;
}
```

The exact types may vary, but the request model must preserve:

- flat fields
- hierarchical fields
- numeric range specs
- enough canonical information to build a stable request cache key

## SearchExecution Refactor

### Problem To Solve

`SearchExecution` is currently keyed only by search parameters but caches a single facet result map. That is incorrect once facet requests can vary by:

- flat fields
- range specs
- `maxValues`
- `minCount`

### Required Change

Refactor `SearchExecution` so that:

- the main `SearchExecution` key remains search-only
- facet results are cached separately per facet request

Recommended structure:

```java
SearchExecution
  - search key: fields + query + cql + sort
  - facet cache: Map<FacetRequestKey, Map<String, List<FacetBucket>>>
```

`FacetRequestKey` must include:

- canonical flat field list
- canonical range spec list including boundaries
- `maxValues`
- `minCount`

This fixes correctness immediately, even before any deeper execution sharing work.

### Shared Search State

Implement true shared Lucene search state in the same change if practical.

Recommended design:

- add a lazily-built search snapshot owned by `SearchExecution`
- snapshot contains the compiled Lucene query, `TopDocs`, and `FacetsCollector`
- `luc:query` and `luc:facet` both read from this snapshot
- the snapshot is keyed only by search parameters, not facet parameters

Lifecycle requirement:

- the snapshot must be closed when the query iterators close
- use iterator close hooks or equivalent shared lifecycle management so readers are not leaked

If the lifecycle work turns out to be larger than expected, the minimum acceptable implementation for this branch is:

- search-only `SearchExecution` key
- per-request facet cache
- docs updated to stop claiming that differing facet requests share one finalized facet result

## Range Aggregation Strategy

### Flat and Hierarchical Facets

Keep the existing mechanisms:

- flat: `SortedSetDocValuesFacetCounts`
- hierarchical: `FastTaxonomyFacetCounts`

### Numeric Range Facets

Add range aggregation after the `FacetsCollector` step.

Use:

- `LongRangeFacetCounts` for `INT` and `LONG`
- `DoubleRangeFacetCounts` for `DOUBLE`

Source selection:

- `INT`: `MultiLongValuesSource.fromIntField(fieldName)`
- `LONG`: `MultiLongValuesSource.fromLongField(fieldName)`
- `DOUBLE`: `MultiDoubleValuesSource.fromField(fieldName, NumericUtils::sortableLongToDouble)`

Using the multi-valued sources for numeric fields keeps the code path uniform and supports both single-valued and multi-valued documents.

### Bucket Construction

Translate each boundary array into contiguous buckets:

- `[a, b, c]` => `[a, b)`, `[b, c)`
- `[null, a, b, null]` => `(-inf, a)`, `[a, b)`, `[b, +inf)`

Internally:

- `INT` and `LONG` map to long bounds
- `DOUBLE` maps to double bounds

The Lucene bucket labels must not be exposed directly as the public SPARQL output model.

## Property Function Changes

### `TextFacetPF`

Update `TextFacetPF` to:

- accept subject arity `1-3` for legacy flat/hierarchical calls
- accept subject arity `5` for the new general form
- reject subject arity `4`
- parse mixed JSON facet field arrays
- validate field types and boundary arrays
- require the 5-slot subject form for any request containing range objects
- bind either `?value` or `?low`/`?high` depending on bucket kind

Binding rules:

- numeric bounds are emitted as typed literals matching the field type
- open-ended bounds remain unbound
- `?count` is emitted as `xsd:long`

### `TextQueryPF`

If the shared search snapshot is implemented, update `TextQueryPF` to consume the same `SearchExecution` snapshot rather than running an independent Lucene query path.

## `ShaclTextIndexLucene` Changes

Primary implementation work in `ShaclTextIndexLucene`:

- change numeric docvalues writing to `SortedNumericDocValuesField`
- write numeric docvalues when `facetable || sortable`
- fix `DOUBLE` docvalues encoding
- update numeric sorting to `SortedNumericSortField`
- add range facet aggregation methods
- combine flat, hierarchical, and range buckets in one result map
- update `isFacetingEnabled()` so numeric-facetable fields count as faceting support

Secondary cleanup:

- stop describing `TEXT` fields as valid flat facet fields unless a separate faceting mechanism is added for them

## Validation and Error Messages

Add explicit errors for these cases:

- numeric field requested as a bare string facet target
- range object targeting a non-numeric field
- malformed `ranges` array
- mixed flat + range request with a 3-slot subject
- unsupported subject arity

Error messages should tell the user how to fix the request, for example:

- "Numeric facet field `<...#year>` requires a range object and the 5-slot luc:facet subject form"
- "Range object field `<...#category>` is not numeric; use a string facet target instead"

## Tests

Add coverage in `jena-text/src/test/java/org/apache/jena/query/text/` for:

- single-valued `INT` range facets
- single-valued `LONG` range facets
- single-valued `DOUBLE` range facets
- multi-valued numeric range facets
- open-ended buckets
- mixed flat + range facet requests
- legacy 3-slot flat facet compatibility
- explicit error on range requests using the 3-slot subject
- explicit error on bare numeric field facet targets
- explicit error on range objects for non-numeric fields
- numeric sorting on multi-valued fields with ascending=`MIN`, descending=`MAX`
- `SearchExecution` facet request cache key behavior

Add or update SPARQL integration tests for:

- `luc:facet` 5-slot output bindings
- mixed flat + range requests
- range facets with CQL filters
- range facets combined with `luc:query` in the same SPARQL query

## Documentation Updates After Code Lands

After implementation:

- rewrite `docs/01-user-guide.md` examples to use the 5-slot form for range facets
- rewrite `docs/02-sparql-api.md` to document the mixed request format and dual row shape
- rewrite `docs/03-configuration.md` so numeric range facets use `idx:facetable true`
- rewrite `docs/04-architecture.md` so `SearchExecution` is described correctly
- update `docs/05-testing.md` with range facet test coverage
- update demos and sample configs so numeric fields that should facet are marked `idx:facetable true`

Also correct these existing documentation issues:

- do not describe bare string facet targets as valid for `TEXT` fields
- document `?count` as `xsd:long`, not `xsd:integer`
- document the reindex requirement for numeric facetable/sortable fields

## Recommended Implementation Order

1. Introduce the new request and result model classes.
2. Change numeric docvalues storage to the new strategy.
3. Update numeric sorting to `SortedNumericSortField`.
4. Implement numeric range aggregation in `ShaclTextIndexLucene`.
5. Update `TextFacetPF` parsing, validation, and binding logic.
6. Refactor `SearchExecution` to use per-request facet cache keys.
7. If practical in the same branch, add true shared search snapshot support.
8. Add unit and integration tests.
9. Rewrite the public docs to match the implemented behavior.

## Definition Of Done

This work is done when all of the following are true:

- numeric range facets work through `luc:facet`
- `idx:facetable true` is the public faceting flag for numeric fields
- multi-valued numeric range facets work
- multi-valued numeric sorting works with documented selector semantics
- mixed flat + range facet requests work with the 5-slot subject form
- legacy flat facet queries continue to work with the 3-slot form
- `SearchExecution` no longer reuses incompatible finalized facet results
- docs and sample configs match the implementation
