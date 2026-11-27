# SPARQL API Reference

All SHACL-mode property functions use the `luc:` namespace:

```sparql
PREFIX luc: <urn:jena:lucene:index#>
```

This page does not cover the classic `text:query` property function. It remains available
as in upstream Jena.

> Note
> [Graph Scoping](#graph-scoping) describes the target API model. Indexing and query
> support for its synthetic graph field is deferred.

## Overview

Rules that apply to every property function:

- The index selector is always the first object argument.
- A field reference is a field IRI. A CQL filter also accepts an `idx:fieldName` (see
  [Naming a field](#naming-a-field)).
- `luc:query` returns `?hit`. Per-hit detail comes from three other property functions
  that join on `?hit`: `luc:match` (which fields matched), `luc:nestedMatch` (the nested
  child records the filter selected) and `luc:fields` (field values).
- `luc:query` has no `?match` slot.
- Arguments are fixed in position and number. Two optional eighth arguments are the
  exceptions: `after` on `luc:query` and `countMode` on `luc:facet`. The type of an
  argument never changes how the others are read.
- Use `""` for an unused `cqlFilter` or `sortSpec`.
- Highlighting is reserved for later and is not part of the `luc:query` signature.

## luc:query

### Syntax

```sparql
(?hit ?entity ?score ?totalHits)
  luc:query (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset)

(?hit ?entity ?score ?totalHits ?rank ?cursor)
  luc:query (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset after)
```

The subject list has 1 to 6 elements, in this order:

| Elements | Subject list |
|---|---|
| 1 | `(?hit)` |
| 2 | `(?hit ?entity)` |
| 3 | `(?hit ?entity ?score)` |
| 4 | `(?hit ?entity ?score ?totalHits)` |
| 5 | `(?hit ?entity ?score ?totalHits ?rank)` |
| 6 | `(?hit ?entity ?score ?totalHits ?rank ?cursor)`. Needs the eighth argument, `after` |

`?score`, `?totalHits`, `?rank` and `?cursor` must be variables. `?entity` may be a
variable or an IRI to filter on.

`luc:query` reads the entity IRI from the index only when the subject list has an
`?entity` element. `(?hit)` alone reads no stored field. Use it when the query needs only
`luc:match`, `luc:nestedMatch` or `luc:fields` rows.

There is no `?graph` element; see [Graph Scoping](#graph-scoping). An older query that
names `?graph` as the sixth element has no eighth argument, so it fails when the query is
built.

The object list has 7 arguments, or 8 with `after` for [cursor paging](#cursor-paging).

### Arguments

| Position | Name | Type | Required | Notes |
|---|---|---|---|---|
| 1 | `indexSelector` | string literal | Yes | Usually `"default"`. Can also be a configured index id or index IRI |
| 2 | `fieldSpec` | string literal | Yes | `"default"` or a JSON array of field IRIs |
| 3 | `queryString` | string literal | Yes | Lucene query string |
| 4 | `cqlFilter` | string literal | Yes | CQL2-JSON object, or `""` |
| 5 | `sortSpec` | string literal | Yes | JSON sort object or array, or `""`. See [Sort Specs](#sort-specs) |
| 6 | `limit` | integer literal | Yes | Page size. `0` gives the default page of 10. A negative value returns every hit from `offset` on |
| 7 | `offset` | integer literal | Yes | Number of hits to skip. `0` is the first page. Must not be negative. `offset + limit` must fit in a signed 32-bit integer. With a cursor in `after`, counts from the hit after the cursor |
| 8 | `after` | string literal | No | `"*"` starts cursor paging at the first hit. A hit's `?cursor` continues after that hit. `""` is the same as no eighth argument. See [Cursor paging](#cursor-paging) |

### `fieldSpec`

Values:

- `"default"`: all fields marked for default search
- `'["urn:jena:lucene:field#title"]'`
- `'["urn:jena:lucene:field#title","urn:jena:lucene:field#description"]'`

An unknown field IRI is a query error.

### `queryString`

Lucene's classic
[`QueryParser`](https://lucene.apache.org/core/10_3_1/queryparser/org/apache/lucene/queryparser/classic/package-summary.html#package.description)
parses the string. With more than one field in `fieldSpec`, `MultiFieldQueryParser` parses
it. Hits are scored with BM25.

The full classic syntax is available with no extra configuration:

- phrases
- `AND`, `OR`, `NOT`, `+` and `-`
- wildcards: `quan*`, `qu?ntum`, and leading wildcards such as `*tum`. Lucene disables
  leading wildcards by default; this index enables them.
- fuzzy terms: `learnimg~1`
- proximity: `"machine networks"~2`
- boosts: `quantum^4`
- grouping and term ranges

A bare `*` matches all documents.

The parser splits the string at whitespace. It then analyzes each word with the query
analyzer of each target field:

- On a `KEYWORD` field, or a `TEXT` field with a keyword analyzer, each word is one term.
  A value that contains a space matches only if the space is escaped: `A\ 12`.
- Where an analyzer splits one word into pieces, the pieces must occur together and in
  order. `StandardAnalyzer` splits `E45/5326` into `e45` and `5326`.
- Separate words match any of the words.

`text:splitOnWhitespace` and `text:autoGeneratePhraseQueries` change this. See
[Index Resources](03-configuration.md#index-resources).

A client that passes user input as plain text must escape the characters that the
classic parser reads as syntax. `/` occurs often in codes. Unescaped, `E45/5326` starts a
regular expression and the search fails.

Put structured predicates in `cqlFilter`: `=`, ranges, `in`, `between`, spatial operators
and same-child correlation. Put field scoping in `fieldSpec`. A `fieldName:value` prefix
in the query string uses internal Lucene field names and bypasses field IRIs.

Tests: `TestLuceneQuerySyntax`, `TestQueryParserSettings`.

### Return bindings

| Variable | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Join key for `luc:match`, `luc:nestedMatch` and `luc:fields`. Each `luc:query` in a query labels its hits differently |
| `?entity` | IRI | The matched entity |
| `?score` | float | Lucene relevance score. For a sorted search, `1/(1+rank)`. See [`?score` under a sort spec](#score-under-a-sort-spec) |
| `?totalHits` | `xsd:integer` | Number of matching hits in the whole result set. `limit` and `offset` do not change it |
| `?rank` | `xsd:integer` | Position in the whole result set, from 0. Continues across pages: with `offset 5` the first hit has rank 5. After a cursor, ranks continue from the cursor's rank |
| `?cursor` | string literal | This hit's cursor. Pass it as `after` to get the hits after this one. Only with `after`. See [Cursor paging](#cursor-paging) |

#### When to use `?rank`

SPARQL results arrive in order, so most queries do not need `?rank`. Use it when the
consumer receives an unordered result, such as a `CONSTRUCT` graph.

`?score` cannot restore the order:

- A match-all query (`*`) gives every document the score `1.0`.
- Relevance scores can tie: two documents that match the query terms equally get the same score.

`?rank` increases strictly and gives the order the engine returned, for relevance order
and for a sort spec.

#### `?score` under a sort spec

Lucene does not score documents when a sort is applied. For a sorted search, `?score` is
`1/(1+rank)`: the first hit has `1.0`, the second `0.5`, and so on. The value depends only
on rank, so a hit keeps its score when a later page runs the search again with a larger
window.

A sorted search's `?score` is not a relevance value. Use `?rank` for order.

### Examples

Search all default-search fields:

```sparql
(?hit ?entity ?score)
  luc:query ("default" "default" "machine learning" "" "" 20 0) .
```

Search one field:

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

Sort by one field:

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

Sort by two fields:

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

`limit` and `offset` select a page. The second page of 10 hits:

```sparql
(?hit ?entity ?score ?totalHits)
  luc:query ("default" "default" "learning" "" "" 10 10) .
```

- `?totalHits` is the full match count. Use it to calculate the number of pages.
- Lucene collects `offset + limit` hits and `luc:query` returns the last `limit` of them.
  The cost grows with `offset`. For deep pages, use [cursor paging](#cursor-paging).
- When `luc:query` and `luc:facet` share a search (see [Shared Execution](#shared-execution)),
  the cached hit list grows to the largest window in the query. Each caller gets its own
  page.
- A negative `offset` is a query error. A negative `limit` returns every hit from `offset`
  on. A `limit` of `0` returns 10 hits.
- Hits that tie on every sort key are in Lucene's index order. That order changes when an
  entity is re-inserted. For a stable order, add the [entity IRI key](#entity-iri) as the
  last sort key, or use cursor paging, which adds it.

### Cursor paging

A cursor marks one hit's place in the result order. `luc:query` with a cursor in `after`
returns the hits after that place.

#### Walk a result set

1. For the first page, pass `"*"` as `after` and bind `?cursor` as the sixth subject
   element.
2. For each next page, pass the last hit's `?cursor` as `after`. Keep all other arguments
   the same.
3. Stop at the last page (see [Find the last page](#find-the-last-page)).

```sparql
SELECT ?entity ?rank ?cursor WHERE {
  (?hit ?entity ?score ?totalHits ?rank ?cursor)
    luc:query ("default" "default" "" "" '[{"field":"urn:jena:lucene:field#title"}]' 10 0 "*") .
} ORDER BY ?rank
```

Next page:

```sparql
  luc:query ("default" "default" "" "" '[{"field":"urn:jena:lucene:field#title"}]' 10 0
             "<the last hit's cursor>") .
```

With a cursor, `offset` counts from the hit after the cursor. With `"*"`, `offset` counts
from the first hit; use this to start at a deep page and continue by cursor.

#### Find the last page

An empty page ends the walk. To stop one page earlier, use one of these methods:

| Method | Rule | Cost and accuracy |
|---|---|---|
| `?totalHits` | Another page follows while the last hit's `?rank` + 1 < `?totalHits` | Runs a separate count. Can be wrong by the number of entities added or removed during the walk |
| Look ahead | Ask for `limit` + 1 hits. Show `limit` hits and continue from the cursor of the last hit shown. Fewer hits than requested means the last page | No count. Exact for the current index |

Each hit has its own cursor, so a walk can continue from any hit in a page.

#### Order

With `after`, `luc:query` appends the [entity IRI key](#entity-iri), ascending, to
`sortSpec` when `sortSpec` does not already include it. With no `sortSpec`, the order is
relevance, then entity IRI. The key makes the order total, so a page boundary between two
hits cannot skip or repeat a hit. Start every walk from `"*"`, so that all pages use this
order.

#### Cursor content

- A cursor is opaque. It is unpadded base64url (`A-Z a-z 0-9 - _`), so it can go in a URL
  query parameter without escaping.
- It holds the hit's sort values (the entity IRI included), its rank and a hash of the
  sort. It holds no Lucene document ID, so it stays valid across commits, merges and
  re-inserted entities. It does not expire.
- It works only with the `sortSpec` it was read from. It works with any `queryString` and
  `cqlFilter`.
- After a cursor, `?rank` and a sorted search's `?score` continue from the cursor's rank.
  If the index changed after the cursor was read, they count from the hit's rank at that
  time.

#### Limits

- Relevance order with a non-empty `queryString`: a commit or a merge can change BM25
  scores. A page read after such a change can skip or repeat hits near the page boundary.
  Sorted searches, and searches with an empty `queryString`, are not affected.
- An entity that matches two shapes has one document per shape, with the same IRI. If the
  two documents tie on every key and a page ends between them, the second is not
  returned.
- The cursor's rank + 1 + `offset` + `limit` must fit in a signed 32-bit integer.
  Otherwise the error is `luc:query cursor position + offset + limit exceeds maximum`.

#### Errors

A sixth subject element without `after`, or with `after` set to `""`, fails when the query
is built. The message starts `luc:query's sixth subject element, ?cursor, needs paging by
cursor`.

These errors start with a fixed marker that a client can match:

| Marker | Cause |
|---|---|
| `[LUC_CURSOR_MALFORMED]` | `after` is not a cursor that `luc:query` returned: not base64url, truncated, extended, or holding values of the wrong types |
| `[LUC_CURSOR_OTHER_SORT]` | `after` is a cursor from a search with a different `sortSpec`. Relevance order counts as a different sort |
| `[LUC_ENTITY_SORT_NEEDS_REBUILD]` | The entity IRI key is used, in `sortSpec` or by cursor paging, on an index with documents older than the entity IRI doc value |

The markers are part of the API and change only with notice. Other errors have no marker.
Fuseki returns every query error as HTTP 500 with a `text/plain` body that holds the
message, marker first. A client that needs a 4xx status maps the markers itself.

#### Cost

Measured over 60,000 hits in an index of 100,000 entities:

- Sorted on a field or on the entity IRI key: 1–5 ms per page after a cursor, at any
  depth. The same page by `offset` at depth 24,000 or 59,990 took 11–35 ms.
- Relevance order: about 10 ms per page after a cursor, at any depth, because each page
  scores every hit. To read a whole result set, sort it, at least by the entity IRI key.

## luc:match

### Syntax

```sparql
(?hit ?field ?value ?snippet) luc:match ()
```

The object is always `()`. The field element takes a variable or a field IRI. An IRI keeps
only the matches on that field:

```sparql
(?hit field:title ?value) luc:match () .
```

A constant in the value element keeps only that value. This happens when `?field` and
`?value` are shared with a `luc:fields` pattern that is evaluated first.

### Purpose

`luc:match` is the only property function that gives per-hit match detail. It joins to
`luc:query` through `?hit` and returns one row per matched field.

The first `luc:match` call computes match details for every hit `luc:query` returned, in
one pass over the index. Later calls read them from memory. A query without `luc:match`
does not compute them.

### Return bindings

| Variable | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Join key from `luc:query` |
| `?field` | IRI | The field IRI that matched. A variable, or a field IRI to filter on |
| `?value` | IRI or literal | The field value the query matched. Unbound for a field with `idx:stored false` |
| `?snippet` | literal | Reserved for highlighting |

On a multi-valued field, `?value` is the first value the query matches. A field read from
doc values (see [03-configuration.md](03-configuration.md#where-values-are-kept)) lists its
values in byte order. When no single value matches, for example for a phrase across two
values, `?value` is the first value in byte order.

### Example

```sparql
SELECT ?entity ?score ?field ?value WHERE {
  (?hit ?entity ?score)
    luc:query ("default" '["urn:jena:lucene:field#title"]' "copper" "" "" 10 0) .
  (?hit ?field ?value) luc:match () .
}
```

## luc:nestedMatch

### Syntax

```sparql
(?hit ?record ?field ?value) luc:nestedMatch ()
```

The object is always `()`. The field element takes a variable or a field IRI. An IRI keeps
only that field's values.

### Purpose

`luc:nestedMatch` returns the `idx:nested` child documents that satisfied the CQL filter.
`luc:match` reports which fields matched the text query. `luc:nestedMatch` reports which
child records the filter selected and what they contain. It joins to `luc:query` through
`?hit`.

It returns only fields declared `idx:stored true` inside the nested block. A field that is
indexed but not stored can be filtered on, but returns no value.

### Return bindings

| Variable | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Join key from `luc:query` |
| `?record` | blank node | Grouping key, one per matching child document |
| `?field` | IRI | Field IRI within the child. A variable, or a field IRI to filter on |
| `?value` | IRI or literal | Field value |

When a filter selects two children of one entity, `?record` shows which values belong to
which child.

### Example

Reports where Sarah Jones was the Principal Investigator, with the matching attribution
from a `prov:qualifiedAttribution` nested block:

```sparql
SELECT ?entity ?record ?field ?value WHERE {
  (?hit ?entity)
    luc:query ("default" "default" ""
      '''{"op":"and","args":[
         {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Principal Investigator"]},
         {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionAgentExact"},"Dr Sarah Jones"]}
       ]}'''
      "" 10 0) .
  (?hit ?record ?field ?value) luc:nestedMatch () .
}
```

### Behaviour and limits

- A filter with no nested clause returns no rows.
- A negated nested clause (`{"op":"not",...}`) returns no rows for that clause.
- The rows do not depend on how a clause is written. `=` and `in` on the same value return
  the same records. A clause on an `idx:facetHierarchy` level returns the same records as a
  clause on any other field: a nested-scoped hierarchy field compiles to a block join at
  every level. A root-scoped hierarchy field compiles to a taxonomy drill-down.
- At most 100 child records per hit are returned. For a hit with more, the first 100 are
  returned and a warning is logged.
- The first `luc:nestedMatch` call computes the records for every hit `luc:query`
  returned: one child query per hit, against the index state that `luc:query` searched. A
  query without `luc:nestedMatch` does not compute them.

## luc:fields

### Syntax

```sparql
(?hit ?field ?value) luc:fields ()
```

The object is always `()`.

### Purpose

`luc:fields` returns field values for each hit from the Lucene index, with no join to the
graph. Use it to get one or two values for many hits, such as an identifier for every hit
of a large search. It joins to `luc:query` through `?hit`.

It acts like a triple pattern with `?hit` as subject, with one row per value. A variable in
the field element ranges over every returnable root field. A field IRI selects one field.
Use one pattern per field to get one column per field.

A returnable root field has `idx:stored true` and an occurrence on the shape itself. A
field that occurs only inside an `idx:nested` block is not returnable. `LATLON` fields are
not returned.

### Return bindings

| Variable | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Join key from `luc:query` |
| `?field` | IRI | A variable, or the field IRI to return |
| `?value` | IRI or literal | One value. A constant keeps only that value |

The values are the terms `luc:match` returns for the same field. A multi-valued field
gives one row per value. A field read from doc values gives each distinct value once, in
byte order. A field read from stored fields gives values in indexing order.

### Example

The identifier of every hit of a filtered search:

```sparql
PREFIX luc:   <urn:jena:lucene:index#>
PREFIX field: <https://example.org/field#>

SELECT ?id WHERE {
  (?hit) luc:query ("default" "default" "" '<CQL2-JSON>' "" 300000 0) .
  (?hit field:collarId ?id) luc:fields () .
}
```

With only `?hit` bound, `luc:query` reads no entity IRI. When `field:collarId` is a
sortable `KEYWORD` field, `luc:fields` reads it from its doc value, and the query reads no
stored field.

### With luc:match

`luc:fields` and `luc:match` both join on `?hit`, so each hit gives every combination of
its `luc:match` rows and its `luc:fields` rows. Use different variable names in the two
patterns. If both use `(?hit ?field ?value)`, they also join on `?field` and `?value` and
keep only the pairs that both produce.

### Errors

Each of these is a query error that names the field:

- a field IRI not configured in any index of the dataset. In a query over two indexes, a
  field of one index gives no rows for the hits of the other.
- a field with `idx:stored false`
- a field that occurs only inside an `idx:nested` block. Use `luc:nestedMatch` for those.
- a read after the index was refreshed under the search (see [Shared Execution](#shared-execution))

The errors are raised on the first call, because the index is known only after
`luc:query` has run.

### Reads

The first call for a field reads it for every hit `luc:query` returned, in one pass in
Lucene doc-ID order, and caches the values. Later calls are lookups. With `?hit` unbound,
it reads every hit `luc:query` cached. The cache holds one entry per value.

## luc:facet

### Syntax

```sparql
(?field ?value ?low ?high ?count)
  luc:facet (indexSelector fieldSpec queryString facetFields cqlFilter maxValues minCount)

(?field ?value ?low ?high ?count)
  luc:facet (indexSelector fieldSpec queryString facetFields cqlFilter maxValues minCount countMode)
```

The subject list is always five variables. Flat facet rows bind `?value` and leave `?low`
and `?high` unbound. Range facet rows bind `?low` and `?high`; an open end of a range is
unbound.

The object list has 7 arguments, or 8 with `countMode`.

### Arguments

| Position | Name | Type | Required | Notes |
|---|---|---|---|---|
| 1 | `indexSelector` | string literal | Yes | Usually `"default"` |
| 2 | `fieldSpec` | string literal | Yes | `"default"` or a JSON array of field IRIs for the search |
| 3 | `queryString` | string literal | Yes | Lucene query string |
| 4 | `facetFields` | string literal | Yes | JSON array of field IRIs and range facet objects |
| 5 | `cqlFilter` | string literal | Yes | CQL2-JSON object, or `""` |
| 6 | `maxValues` | integer literal | Yes | Maximum values per field. `0` means all values |
| 7 | `minCount` | integer literal | Yes | Minimum count for a value to be returned |
| 8 | `countMode` | string literal | No | `"sideways"` (default) or `"drilldown"`. `""` is the same as no eighth argument. See [Count modes](#count-modes) |

### `facetFields`

A flat facet is a field IRI:

```json
["urn:jena:lucene:field#category","urn:jena:lucene:field#author"]
```

A range facet is an object:

```json
[
  {"field":"urn:jena:lucene:field#year","ranges":[null,2020,2023,null]}
]
```

One array can hold both flat and range facets.

`"*"` expands to all flat and hierarchical facetable fields. It does not expand to numeric
range facets.

### Count modes

`luc:facet` counts in one of two modes. The names come from Lucene's `DrillSideways`.

| `countMode` | A field the filter selects on | Every other field |
|---|---|---|
| `"sideways"`, the default | Counted under the filter without that field's own clauses | Counted under the full filter |
| `"drilldown"` | Counted under the full filter | Counted under the full filter |

Use `"sideways"` for a search form. With commodity `cu` or `au` selected, the commodity
facet shows how many records each commodity gives under the other filters, so the user
can add or change values. Document type is counted under the full filter.

Use `"drilldown"` for charts and summaries of the result set. A selected field's
drill-down counts contain only the selected values, and their sum equals what `luc:query`
returns.

- For a field that the filter does not select on, the two modes give the same counts.
- In sideways mode, a selected field's counts do not add up to `luc:query`'s `?totalHits`.
- The rows do not show the mode a field was counted in. The rules in
  [Which fields are counted sideways](#which-fields-are-counted-sideways) decide it from the
  filter alone.
- To show both kinds of count for a field, add a second `luc:facet` with `"drilldown"` to
  the same query. The two calls are computed separately.

```sparql
(?field ?value ?low ?high ?count)
  luc:facet (
    "default"
    "default"
    "copper"
    '["urn:jena:lucene:field#commodity", "urn:jena:lucene:field#documentType"]'
    '''{"op":"and","args":[
       {"op":"in","args":[{"property":"urn:jena:lucene:field#commodity"},["cu","au"]]},
       {"op":"between","args":[{"property":"urn:jena:lucene:field#year"},1980,1995]}]}'''
    50
    1
  ) .
```

In this example, commodity is counted under the text query and the year range. Document
type is counted under the text query, the year range and the commodity selection. The
counts are equal to one `"drilldown"` call per selected field, with that field's clauses
removed from the filter by the client.

#### Which fields are counted sideways

A requested field is counted sideways, as a **dimension**, when all of these conditions
are true:

- At least one top-level clause of the filter is on the field. The top-level clauses are
  the arguments of a top-level `and`, or the filter itself when it is a single clause.
- Each of those clauses is `=` or `in` on a facetable `KEYWORD` field. For a field
  requested as a range facet, `between`, `<`, `<=`, `>` and `>=` also qualify, so a year
  range facet is counted without the year range clause. An `or` whose branches all qualify
  and name the same field also qualifies, such as several selected year buckets.
- The field occurs nowhere else in the filter, for example in an `or` with another field
  or in a `not`.
- The field is not in an `idx:nested` scope and is not a level of an
  `idx:facetHierarchy`.
- The CQL compiler turns those clauses into one Lucene query in full. An `or` that
  contains an empty `or` does not qualify, because the compiler pushes an `or` only when it
  can push every branch.

Several clauses on one field form one dimension and are left out together. All other
clauses stay in the base filter, which applies to all counts: text and spatial clauses,
`like`, `<>`, `not`, an `or` across fields, `entityIri`, and clauses on fields that were
not requested.

A requested field that is not a dimension is counted under the full filter, as in
`"drilldown"` mode. This includes a field in an `or` with another field, hierarchy
dimensions (with the drill-down path the filter gives them) and fields in an `idx:nested`
scope. These fields are counted, so one fixed query text works for every state of a
form.

The count mode does not change `luc:query` in the same SPARQL query. Its hits and
`?totalHits` use the full filter.

Sideways counts use Lucene's `DrillSideways`, which runs one search for the full filter and
one search per dimension. See
[2026-11-13_luc_facet_sideways_counts_design.md](2026-11-13_luc_facet_sideways_counts_design.md)
for the design and measurements.

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

## luc:config

### Syntax

```sparql
(?s ?p ?o) luc:config (indexSelector)
```

### Purpose

`luc:config` returns the selected index's configuration as triples. A client can use it to
find the fields and what each field supports, without a copy of the configuration file.
The index must set [`text:exposeConfig true`](03-configuration.md#textexposeconfig).
Otherwise the call is a query error.

The result is every triple reachable from the index resource in the configuration file,
without following `rdf:type`. Only settings written in the file are returned. The defaults
are in the tables of [03-configuration.md](03-configuration.md).

### Arguments

| Position | Meaning |
|---|---|
| `indexSelector` | The index, as for `luc:query` |

### Subject elements

Each of `?s`, `?p` and `?o` is a variable or a constant. The call matches like one triple
pattern over the configuration, so a constant restricts the result. Blank nodes are the
same in every call within one query, so several calls can join through them.

### Examples

The whole configuration as RDF:

```sparql
PREFIX luc: <urn:jena:lucene:index#>

CONSTRUCT { ?s ?p ?o } WHERE { (?s ?p ?o) luc:config ("default") }
```

The facetable fields and their types:

```sparql
PREFIX luc: <urn:jena:lucene:index#>
PREFIX idx: <urn:jena:lucene:index#>

SELECT ?field ?type WHERE {
  (?field idx:facetable true) luc:config ("default") .
  (?field idx:fieldType ?type) luc:config ("default") .
}
```

The path of each field of one shape:

```sparql
PREFIX luc: <urn:jena:lucene:index#>
PREFIX idx: <urn:jena:lucene:index#>
PREFIX sh:  <http://www.w3.org/ns/shacl#>

SELECT ?field ?path WHERE {
  (<http://example.org/config#BookShape> sh:property ?occ) luc:config ("default") .
  (?occ idx:field ?field) luc:config ("default") .
  (?occ sh:path ?path) luc:config ("default") .
}
```

### Limits

- A SPARQL property path in the query runs against the dataset, and `luc:config`'s output
  is not part of the dataset. So `rdf:rest*/rdf:first` cannot walk a list such as a
  sequence `sh:path`. Use one `luc:config` call per list cell, or `CONSTRUCT` the
  configuration and query the result.
- Query errors: an index without `text:exposeConfig true`, an unknown selector, and a
  selector that is not a literal.
- Query build errors: a subject that is not three elements, and an object that is not one
  selector.

## luc:stats

### Syntax

```sparql
(?s ?p ?o) luc:stats (indexSelector)
```

### Purpose

`luc:stats` returns figures about the selected index's Lucene contents as triples: the
number of entities and documents, the index size, and for each field the number of
documents, distinct terms, tokens or points. It is always available; no setting controls
it.

### Arguments

| Position | Meaning |
|---|---|
| `indexSelector` | The index, as for `luc:query` |

### Subject elements

As for [`luc:config`](#lucconfig): each of `?s`, `?p` and `?o` is a variable or a constant,
and the call matches like one triple pattern.

### Returned triples

The subjects are the nodes `luc:config` uses, so the two join. Index figures have the
index resource as subject: the URI or blank node that declares the index in the
configuration file. Field figures have the field IRI as subject. Every value is an
`xsd:long`.

| Subject | Predicate | Value |
|---|---|---|
| index | `luc:entityCount` | Live entities |
| index | `luc:documentCount` | Live Lucene documents, including the child documents of `idx:nested` blocks |
| index | `luc:deletedDocumentCount` | Deleted documents not yet merged away |
| index | `luc:segmentCount` | Lucene segments |
| index | `luc:sizeInBytes` | Size of the main index's segment files. The taxonomy index is not included |
| `TextField`, `KeywordField` | `luc:documentCount` | Documents with at least one term in the field |
| `TextField`, `KeywordField` | `luc:termCount` | Distinct terms. For a keyword field, distinct values |
| `TextField`, `KeywordField` | `luc:tokenCount` | Terms indexed, counting repeats |
| `IntField`, `LongField`, `DoubleField`, `TemporalField`, `LatLonField` | `luc:documentCount` | Documents with at least one value in the field |
| the same | `luc:pointCount` | Points indexed. A `LatLonField` line or polygon is indexed as several triangles, and each triangle counts |

A field with no indexed values reports `0`. A field declared `idx:indexed false` has no
terms or points, so it also reports `0`.

### Examples

All figures as RDF:

```sparql
PREFIX luc: <urn:jena:lucene:index#>

CONSTRUCT { ?s ?p ?o } WHERE { (?s ?p ?o) luc:stats ("default") }
```

Distinct values of each keyword field, joined with `luc:config`, which needs
[`text:exposeConfig true`](03-configuration.md#textexposeconfig):

```sparql
PREFIX luc: <urn:jena:lucene:index#>
PREFIX idx: <urn:jena:lucene:index#>

SELECT ?field ?values WHERE {
  (?field idx:fieldType idx:KeywordField) luc:config ("default") .
  (?field luc:termCount ?values) luc:stats ("default") .
}
```

### Limits

- Field figures come from Lucene's index statistics, which include deleted documents that
  are not yet merged away. After updates and deletes, the figures can be higher than what a
  query finds, until segment merging removes the deleted documents.
  `luc:deletedDocumentCount` gives the number of such documents.
- A field inside an `idx:nested` block is held on child documents, so its
  `luc:documentCount` counts child records.
- The figures come from the searcher `luc:query` uses, so they include every committed
  update. They are cached until the next commit. With more than one segment,
  `luc:termCount` reads every term of every text and keyword field. On a large index, the
  first call after a commit takes longer than later calls.
- Query errors: an unknown selector, and a selector that is not a literal.
- Query build errors: a subject that is not three elements, and an object that is not one
  selector.

## CQL2-JSON Filters

The `property` entry is a field IRI or an `idx:fieldName` (see
[Naming a field](#naming-a-field)).

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

### `text_query` — analyzer-aware text matching

`=` compares exact terms and applies no analyzer. For text matching through an analyzer,
such as edge-n-gram typeahead, a lowercased keyword, standard tokenisation or stemming,
use `text_query`:

```json
{"op":"text_query","args":[{"property":"urn:jena:lucene:field#title"},"gold mine"]}
```

The text is tokenised with the first analyzer found in this order: the field's query
analyzer, the field's index analyzer, the index-wide analyzer. A `TEXT` field that
configures no analyzer is searched with `StandardAnalyzer`.

| Tokens | Lucene query |
|---|---|
| one | `TermQuery` |
| more than one | `PhraseQuery` (positional) |
| none, for example input of only stopwords | matches nothing |

The field's analyzer decides what the field matches. A mismatch gives an empty result and
no error. See
[03-configuration.md → Choosing an Analyzer for a TEXT Field](03-configuration.md#choosing-an-analyzer-for-a-text-field).
An edge-n-gram field matches words in the middle of a value only when it declares
`text:tokenized true`.

Which operator to use:

| Operator | Use for |
|---|---|
| `=` | Exact equality on `KEYWORD`, numeric and temporal fields; root-level entity-type pivots |
| `text_query` | `TEXT` fields with an analyzer: typeahead, full-text search, edge-n-gram |
| `like` | Wildcard patterns (`%`, `_`) on `KEYWORD` and `TEXT` fields |

### Nested same-child filters

Use these for `idx:nested` child records, such as qualified identifiers,
`prov:qualifiedAttribution` records and location assessments. Clauses on fields in the
same nested scope, in the same CQL subtree, fold into one block join. A parent matches
only when one child satisfies all of those clauses.

**Qualified identifier, two `KEYWORD` clauses** (`schema:identifier` with `propertyID` and
`value`):

```json
{
  "op": "and",
  "args": [
    {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierType"},"company"]},
    {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierValueExact"},"Bellbird"]}
  ]
}
```

This returns only entities with one identifier record that has `propertyID="company"` and
`value="Bellbird"`. An entity where one identifier has the type and another has the value
does not match.

**Identifier with typeahead** (`KEYWORD` type and edge-n-gram value):

```json
{
  "op": "and",
  "args": [
    {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierType"},"anumber"]},
    {"op":"text_query","args":[{"property":"urn:jena:lucene:field#identifierValueText"},"A-94"]}
  ]
}
```

A borehole matches only when one identifier record has `propertyID="anumber"` and a value
that matches the `A-94` n-gram. The field's analyzer normalises `A-94`; with
`LowerCaseKeywordAnalyzer` it becomes `a-94`, so the typeahead ignores the case of the
input.

**Qualified attribution** (`prov:qualifiedAttribution` with `hadRole` and `agent`):

```json
{
  "op": "and",
  "args": [
    {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Principal Investigator"]},
    {"op":"text_query","args":[{"property":"urn:jena:lucene:field#attributionAgentText"},"Sarah Jones"]}
  ]
}
```

A report matches only when one attribution record has
`hadRole="Principal Investigator"` and an agent that matches `Sarah Jones`. A report with
the role on one attribution and the name on another does not match.

`attributionAgentText` is a `TEXT` field with no analyzer override. The input is tokenised
the same way as the index, so `"Sarah Jones"` matches `"Dr Sarah Jones"` as a phrase. Do
not use an n-gram twin field for names. See
[03-configuration.md → Person names](03-configuration.md#person-names).

The fold applies within one CQL subtree. A clause in `queryString` and a clause in
`cqlFilter` are in different subtrees and do not fold. For same-child matching, put both
clauses in `cqlFilter`, with `=` and `text_query` as shown above.

### Naming a field

A filter can name a field by its IRI or by its `idx:fieldName`. Both forms work in
`luc:query` and `luc:facet`.

A name that resolves to no field raises `TextIndexException`. A filter on a field that
exists but is not indexed also raises it.

In `luc:facet`, a facet spec that matches no field and no hierarchy dimension raises an
error.

### Clauses that raise an error

The CQL compiler applies every clause of a filter, or refuses the query with a message
that names the clause. Inside an `or`, one clause that cannot be applied refuses the whole
query. These clauses raise an error:

| Clause | Reason |
|---|---|
| `text_query` or `like` on a numeric, temporal or spatial field | No defined meaning |
| A range operator on a `KEYWORD` field | No order to compare against |
| An operator other than `=` and `<>` on `entityIri` | Only `=` and `<>` are defined for `entityIri` |
| A property that names no field, or a field that is not indexed | Nothing to filter on |

## Graph Scoping

This section describes the target model. It is not implemented. Design:
[graph filtering target model](2026-04-08-graph-filtering-target-model.md) and
[source graph indexing](2026-07-31_source_graph_indexing_design.md).

An entity's document is built from a union of all graphs, so its values can come from
several graphs. There is no single graph to bind per hit.

- Graph scoping is a document-level CQL filter. `luc:query` has no `?graph` result
  element.
- The filter target is a reserved synthetic field IRI,
  `urn:jena:lucene:field#sourceGraph`.
- The field is a multi-valued `KEYWORD` field that holds every graph read while indexing
  the entity's document.

Example filter:

```json
{"op":"=","args":[{"property":"urn:jena:lucene:field#sourceGraph"},"http://example.org/graph/A"]}
```

Consequences:

- A graph restriction works like any other CQL field filter.
- The property function signatures do not change.
- Graph provenance applies to the whole document. It is not recorded per match.

Deferred design:

- An index-time option may restrict indexing to a configured set of graphs. With that
  option, `sourceGraph` holds only the indexed graphs.

## Sort Specs

A sort field is a field IRI. A Lucene field name is not accepted.

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

| Key | Values | Default |
|---|---|---|
| `field` | Field IRI, or the [entity IRI key](#entity-iri) | Required |
| `order` | `"asc"` or `"desc"` | `"asc"` |
| `missing` | `"first"` or `"last"`: where entities with no value for the field go | Lucene's placement for a flat sort; `"last"` with a `selector` |
| `selector` | See [Nested Sort Selector](#nested-sort-selector) | None |

Any other key is an error. The message lists the expected keys. A sort spec that uses the
key `filter` gets a message that names `selector`.

### Sort errors

A sort that the index cannot carry out is a query error:

- an unknown field IRI, including a misspelled [entity IRI key](#entity-iri)
- a field without sort doc values. A `KEYWORD` field needs `idx:sortable`. A numeric or
  temporal field needs `idx:sortable` or `idx:facetable`, which write the same doc values.
- a `TEXT` or `LatLon` field
- a field that occurs only inside `idx:nested` blocks, without a
  [selector](#nested-sort-selector)

A search that fails for one of these reasons, or because its `queryString` does not
parse, raises an error.

### Entity IRI

The reserved key `urn:jena:lucene:index#entity` sorts by entity IRI:

```json
[
  {"field":"urn:jena:lucene:field#title","order":"asc","missing":"last"},
  {"field":"urn:jena:lucene:index#entity","order":"asc"}
]
```

As the last key, it gives hits that tie on all other keys an order that does not change
when an entity is updated or re-inserted. It also works as any other key: with `"desc"`,
alone, or first.

- IRIs compare by code point, which is the byte order of their UTF-8 form. Jena's
  `ORDER BY STR(?entity)` also compares by code point (since apache/jena GH-4300), so the
  key and `ORDER BY` give the same order, including for characters beyond U+FFFF.
- `missing` has no effect, because every entity has an IRI.
- A `selector` is an error.

The key reads the entity IRI doc value. Indexes have written this doc value since
2026-10-12 (`d6f0091`, #195). If an index holds any document written before then, a sort
by the key or a `luc:query` that pages by cursor raises `[LUC_ENTITY_SORT_NEEDS_REBUILD]`.
Rebuild the index to fix it.

The key adds cost to a deep `offset` when many hits tie on the earlier keys, because each
tied hit is compared by IRI. At depth 59,990 the cost doubled on a year sort and was four
times higher in relevance order. A sort with unique values, and the first pages of any
sort, cost the same with or without the key.

### Nested Sort Selector

To sort by a value from one specific nested child record, add a `selector` to the sort
object. For example, to sort by the identifier value where `identifierType` is
`companyID`:

```json
{
  "field":    "urn:jena:lucene:field#identifierValue",
  "selector": {"field":"urn:jena:lucene:field#identifierType","eq":"companyID"},
  "order":    "asc",
  "missing":  "last"
}
```

| Key | Meaning |
|-----|---------|
| `field` | The child field to sort on. Must be `idx:sortable` |
| `selector.field` | The discriminator field on the same child document |
| `selector.eq` | The value the discriminator must equal: a string, number or boolean |
| `order` | `asc` (default) or `desc`, as for a flat sort |
| `missing` | `first` or `last`: where entities with no matching child go. Default `last` |

The nested scope comes from `field`. It is not part of the sort spec.

- The `selector` chooses which child supplies the sort key. It removes no entities. An
  entity with no matching child stays in the results and is placed by `missing`.
- To restrict which entities appear, add a clause to `cqlFilter`. A filter clause and a
  selector can be used together.
- When an entity has several matching children, the key is the minimum (ascending) or the
  maximum (descending). With one record per type, the minimum and maximum are equal.
- Both fields must be in the same `idx:nested` block, so that they are on one child
  document.
- A `selector` on a field that is not nested is an error:
  `Sort selector requires a nested field`.
- Lucene sorts before `limit` and `offset` select the page, so pages over a
  selector-ordered result set are correct across page boundaries.

#### Why a sort needs its own selector

`cqlFilter` chooses which documents are returned. A sort reads its key from the doc values
of a document that is already chosen. A filter on the discriminator therefore does not
change the sort key.

A flat sort on a field that occurs only in nested blocks finds no key on any parent, so it
is an error, whatever `cqlFilter` contains. Where a field also occurs on the parent, a
flat sort uses all the entity's values: an entity with companyID `C-200` and govID `A-000`
sorts on `A-000`. With a `selector`, the key comes from the selected child only.

Data model for the example above. No other index configuration is needed:

```turtle
:sampleShape idx:nested [
    idx:joinPath sdo:identifier ;
    # discriminator -> sort selector
    idx:property [ idx:field field:identifierType  ; sh:path sdo:propertyID ] ;
    # value         -> sort key
    idx:property [ idx:field field:identifierValue ; sh:path sdo:value ] ;
] .

field:identifierType
    idx:fieldName "identifierType" ; idx:fieldType idx:KeywordField ;
    idx:indexed true ; idx:facetable true .

field:identifierValue
    idx:fieldName "identifierValue" ; idx:fieldType idx:KeywordField ;
    idx:sortable true .
```

## Shared Execution

`luc:query`, `luc:facet`, `luc:match`, `luc:nestedMatch` and `luc:fields` share one Lucene
search when these match:

- resolved index
- search field spec
- query string
- CQL filter
- sort spec

`luc:facet` applies its own parameters, such as facet fields, `maxValues` and `minCount`,
after the shared search.

A `luc:query` that pages by cursor runs its own search. Its key also includes the `after`
argument, so `luc:facet` does not share it.

`luc:match`, `luc:nestedMatch` and `luc:fields` find their search through `?hit`. Each
search in a query labels its hits with its own prefix. In a query with two `luc:query`
patterns over different searches, each `?hit` joins to the right search.

Entity IRIs, match details, child records and field values are read after the search,
and only when the query asks for them. They are read against the index state that the
search used. The search holds its Lucene searcher until `luc:query`'s results are consumed
or the query is closed or cancelled.

The query fails with an error that asks for it to be run again when both of these are
true:

- A read happens after the searcher is released, or a later `luc:query` over the same
  search needs more hits than were fetched.
- The index was committed after the search.
