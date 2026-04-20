# SPARQL API Reference

All SHACL-mode property functions use the `luc:` namespace:

```sparql
PREFIX luc: <urn:jena:lucene:index#>
```

Classic `text:query` remains available upstream and is not covered here.

> Note
> The graph-scoping model described here is the target API model. Real indexing/query support for the synthetic graph field is deferred.

## Overview

Public API rules:

- Index selection is explicit and always the first object argument.
- Field references are always field IRIs, never `idx:fieldName`.
- `luc:query` returns `?hit`; per-hit match detail comes from `luc:match`.
- `luc:query` no longer returns `?match`.
- Parsing is fixed-position and fixed-arity. There is no shape-based argument inference.
- Use `""` as the placeholder for an unused `cqlFilter` or `sortSpec`.
- Highlight is reserved for later and is not part of the active supported `luc:query` signature.

## luc:query

### Syntax

```sparql
(?hit ?entity ?score ?totalHits)
  luc:query (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset)
```

Subject arity may be 1 to 4:

- `?hit`
- `?hit ?entity`
- `?hit ?entity ?score`
- `?hit ?entity ?score ?totalHits`

Object arity is always exactly 7.

### Arguments

| Position | Name | Type | Required | Notes |
|---|---|---|---|---|
| 1 | `indexSelector` | string literal | Yes | Usually `"default"`; may also be a configured index id or index IRI |
| 2 | `fieldSpec` | string literal | Yes | `"default"` or a JSON array of field IRIs |
| 3 | `queryString` | string literal | Yes | Lucene query string |
| 4 | `cqlFilter` | string literal | Yes | CQL2-JSON object, or `""` |
| 5 | `sortSpec` | string literal | Yes | JSON sort object/array, or `""` |
| 6 | `limit` | integer literal | Yes | Page size. Negative means unlimited |
| 7 | `offset` | integer literal | Yes | Number of leading hits to skip. `0` = first page. Must be non-negative. `offset + limit` must fit in a signed 32-bit int |

### `fieldSpec`

Supported values:

- `"default"`
- `'["urn:jena:lucene:field#title"]'`
- `'["urn:jena:lucene:field#title","urn:jena:lucene:field#description"]'`

Unknown field IRIs fail fast.

### Return bindings

| Variable | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Query-scoped join key for `luc:match` |
| `?entity` | IRI | Matched entity |
| `?score` | float | Lucene relevance score |
| `?totalHits` | `xsd:integer` | Total matching hits across the whole result set, independent of `limit` and `offset` |

`?match` is not part of `luc:query`.

### Examples

Search all default-search fields:

```sparql
(?hit ?entity ?score)
  luc:query ("default" "default" "machine learning" "" "" 20 0) .
```

Search a specific field IRI:

```sparql
(?hit ?entity ?score)
  luc:query (
    "default"
    '["urn:jena:lucene:field#title"]'
    "machine learning"
    ""
    ""
    20
    0
  ) .
```

Search with a CQL filter:

```sparql
(?hit ?entity ?score ?totalHits)
  luc:query (
    "default"
    "default"
    "learning"
    '{"op":"=","args":[{"property":"urn:jena:lucene:field#category"},"Technology"]}'
    ""
    20
    0
  ) .
```

Search with sort:

```sparql
(?hit ?entity ?score)
  luc:query (
    "default"
    "default"
    "learning"
    ""
    '{"field":"urn:jena:lucene:field#year","order":"desc"}'
    10
    0
  ) .
```

Multi-sort:

```sparql
(?hit ?entity ?score)
  luc:query (
    "default"
    "default"
    "learning"
    ""
    '[{"field":"urn:jena:lucene:field#year","order":"desc"},{"field":"urn:jena:lucene:field#title"}]'
    10
    0
  ) .
```

### Paging

`limit` and `offset` form a page window. Fetch the second page of 10 results:

```sparql
(?hit ?entity ?score ?totalHits)
  luc:query ("default" "default" "learning" "" "" 10 10) .
```

Notes:

- `?totalHits` always reflects the full match count, not the page size. Use it to compute page counts.
- Lucene fetches `offset + limit` hits internally and the PF exposes only the slice. Very deep offsets therefore cost proportionally more.
- When `luc:query` and `luc:facet` share a search (same selector, field spec, query string, filter, sort), the cached hit list grows to the largest window seen in the query; each caller gets its own slice.
- A negative `offset` is a query error. A negative `limit` is still accepted and means unlimited (offset then has no effect beyond skipping).

## luc:match

### Syntax

```sparql
(?hit ?field ?value ?snippet) luc:match ()
```

The object is always `()`.

### Purpose

`luc:match` is the only per-hit match-detail API. It joins to `luc:query` through `?hit` and returns one row per matched field.

### Return bindings

| Variable | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Join key from `luc:query` |
| `?field` | IRI | Field IRI that matched |
| `?value` | IRI or literal | Stored field value |
| `?snippet` | literal | Reserved for later highlighting support |

### Example

```sparql
SELECT ?entity ?score ?field ?value WHERE {
  (?hit ?entity ?score)
    luc:query ("default" '["urn:jena:lucene:field#title"]' "copper" "" "" 10 0) .
  (?hit ?field ?value) luc:match () .
}
```

## luc:facet

### Syntax

```sparql
(?field ?value ?low ?high ?count)
  luc:facet (indexSelector fieldSpec queryString facetFields cqlFilter maxValues minCount)
```

The active supported subject form is the 5-slot form above.
Flat facets use the same 5-slot form. On flat facet rows, `?value` is bound and `?low` / `?high` are left unbound.

Object arity is always exactly 7.

### Arguments

| Position | Name | Type | Required | Notes |
|---|---|---|---|---|
| 1 | `indexSelector` | string literal | Yes | Usually `"default"` |
| 2 | `fieldSpec` | string literal | Yes | `"default"` or JSON array of field IRIs for search scoping |
| 3 | `queryString` | string literal | Yes | Lucene query string |
| 4 | `facetFields` | string literal | Yes | JSON array of field IRIs and/or range facet objects |
| 5 | `cqlFilter` | string literal | Yes | CQL2-JSON object, or `""` |
| 6 | `maxValues` | integer literal | Yes | `0` means all values |
| 7 | `minCount` | integer literal | Yes | Minimum count threshold |

### `facetFields`

Flat facet targets use field IRIs:

```json
["urn:jena:lucene:field#category","urn:jena:lucene:field#author"]
```

Range facets use objects:

```json
[
  {"field":"urn:jena:lucene:field#year","ranges":[null,2020,2023,null]}
]
```

Mixed flat + range requests are allowed in the same array.

Wildcard:

- `"*"` expands to all flat and hierarchical facetable fields
- it does not expand numeric range facets

### Examples

Flat facets:

```sparql
(?field ?value ?low ?high ?count)
  luc:facet (
    "default"
    "default"
    "learning"
    '["urn:jena:lucene:field#category"]'
    ""
    10
    0
  ) .
```

Filtered facets:

```sparql
(?field ?value ?low ?high ?count)
  luc:facet (
    "default"
    "default"
    "learning"
    '["urn:jena:lucene:field#author"]'
    '{"op":"=","args":[{"property":"urn:jena:lucene:field#category"},"Technology"]}'
    10
    0
  ) .
```

Range facets:

```sparql
(?field ?value ?low ?high ?count)
  luc:facet (
    "default"
    "default"
    "*"
    '[{"field":"urn:jena:lucene:field#year","ranges":[null,2000,2010,2020,null]}]'
    ""
    20
    0
  ) .
```

## CQL2-JSON Filters

The `property` entry is always a field IRI.

Exact match:

```json
{"op":"=","args":[{"property":"urn:jena:lucene:field#state"},"http://example.org/mining/state/WA"]}
```

Conjunction:

```json
{
  "op":"and",
  "args":[
    {"op":"=","args":[{"property":"urn:jena:lucene:field#commodity"},"http://example.org/mining/commodity/Gold"]},
    {"op":"=","args":[{"property":"urn:jena:lucene:field#state"},"http://example.org/mining/state/WA"]}
  ]
}
```

Spatial:

```json
{"op":"s_intersects","args":[{"property":"urn:jena:lucene:field#location"},{"bbox":[112,-44,154,-10]}]}
```

## Graph Scoping

Target model:

- graph scoping is a normal doc-level filter, not a dedicated `?graph` result slot
- the public filter target is a reserved synthetic field IRI
- the recommended reserved field is `urn:jena:lucene:field#sourceGraph`
- it is intended to be a multi-valued KEYWORD field populated with every graph touched while indexing the entity document

Example target filter:

```json
{"op":"=","args":[{"property":"urn:jena:lucene:field#sourceGraph"},"http://example.org/graph/A"]}
```

This means:

- query-time graph restriction behaves like any other CQL field filter
- property-function signatures stay simple
- graph provenance is doc-level, not per-match

Related deferred design:

- an index-time option may later restrict indexing to a configured graph set
- when such restriction is used, `sourceGraph` naturally reflects only those indexed graphs

## Sort Specs

Sort fields are field IRIs, not Lucene field names.

Single sort:

```json
{"field":"urn:jena:lucene:field#year","order":"desc"}
```

Multi-sort:

```json
[
  {"field":"urn:jena:lucene:field#year","order":"desc"},
  {"field":"urn:jena:lucene:field#title"}
]
```

## Shared Execution

`luc:query`, `luc:facet`, and `luc:match` share a single Lucene execution when these match:

- resolved index identity
- search field spec
- query string
- CQL filter
- sort spec

Facet-specific parameters such as requested facet fields, `maxValues`, and `minCount` are applied after the shared search step.
