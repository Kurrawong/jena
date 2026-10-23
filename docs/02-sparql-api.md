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
- `luc:query` returns `?hit`; per-hit match detail comes from `luc:match`, the
  nested child records that satisfied the filter from `luc:nestedMatch`, field
  values from `luc:fields`, and the hit's paging cursor from `luc:cursor`.
- `luc:query` no longer returns `?match`.
- Parsing is fixed-position and fixed-arity, except for `luc:query`'s optional eighth
  argument, `after`. There is no shape-based argument inference.
- Use `""` as the placeholder for an unused `cqlFilter` or `sortSpec`.
- Highlight is reserved for later and is not part of the active supported `luc:query` signature.

## luc:query

### Syntax

```sparql
(?hit ?entity ?score ?totalHits)
  luc:query (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset)

(?hit ?entity ?score ?totalHits)
  luc:query (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset after)
```

Subject arity may be 1 to 5:

- `?hit`
- `?hit ?entity`
- `?hit ?entity ?score`
- `?hit ?entity ?score ?totalHits`
- `?hit ?entity ?score ?totalHits ?rank`

The entity IRI is read from the index only when the subject list has an `?entity` slot,
as a variable or as an IRI to filter on. `(?hit)` alone reads no stored field, which
suits a query that needs only `luc:match` or `luc:nestedMatch` rows.

`?rank` sits last rather than beside `?score`, where it would read more naturally. It was
added after the earlier positions were already in use, and appending it was the only
placement that left an existing subject list binding what it already bound.

There is no `?graph` slot. A sixth position briefly existed and never bound a value: a
SHACL document is assembled from a union view over every graph, so an entity's indexed
values may come from several graphs at once and there is no single source graph to bind.
Graph provenance is modelled as a doc-level field instead — see
[graph filtering target model](2026-04-08-graph-filtering-target-model.md) and
[source graph indexing](2026-07-31_source_graph_indexing_design.md), both design-only.
A query still naming the old slot is rejected at build time rather than silently binding
`?graph` to a rank integer.

Object arity is 7, or 8 with `after` for [cursor paging](#cursor-paging).

### Arguments

| Position | Name | Type | Required | Notes |
|---|---|---|---|---|
| 1 | `indexSelector` | string literal | Yes | Usually `"default"`; may also be a configured index id or index IRI |
| 2 | `fieldSpec` | string literal | Yes | `"default"` or a JSON array of field IRIs |
| 3 | `queryString` | string literal | Yes | Lucene query string |
| 4 | `cqlFilter` | string literal | Yes | CQL2-JSON object, or `""` |
| 5 | `sortSpec` | string literal | Yes | JSON sort object/array, or `""` |
| 6 | `limit` | integer literal | Yes | Page size. Negative means unlimited. `0` gives the default page of 10 |
| 7 | `offset` | integer literal | Yes | Number of leading hits to skip. `0` = first page. Must be non-negative. `offset + limit` must fit in a signed 32-bit int. With `after`, counted from the hit after the cursor |
| 8 | `after` | string literal | No | Cursor paging: `"*"` for the first page, or a cursor from `luc:cursor` to continue after that hit. `""`, like leaving it out, is no cursor paging. See [Cursor paging](#cursor-paging) |

### `fieldSpec`

Supported values:

- `"default"`
- `'["urn:jena:lucene:field#title"]'`
- `'["urn:jena:lucene:field#title","urn:jena:lucene:field#description"]'`

Unknown field IRIs fail fast.

### `queryString`

Parsed by Lucene's classic
[`QueryParser`](https://lucene.apache.org/core/10_3_1/queryparser/org/apache/lucene/queryparser/classic/package-summary.html#package.description)
— `MultiFieldQueryParser` when `fieldSpec` names more than one field — and scored with BM25.
The full classic syntax is available with no extra configuration: phrases, `AND`/`OR`/`NOT`,
`+`/`-`, wildcards (`quan*`, `qu?ntum`, and **leading** wildcards such as `*tum`, which Lucene
disables by default and this index enables), fuzzy (`learnimg~1`), proximity
(`"machine networks"~2`), boosts (`quantum^4`), grouping and term ranges. A bare `*` is
short-circuited to a match-all.

The parser cuts the string at whitespace, then analyzes each word with each target field's
query analyzer, so behaviour follows the field. On a `KEYWORD` field, or a `TEXT` field with a
keyword analyzer, each word is a single term, and a value containing a space matches only if
the space is escaped (`A\ 12`). Where an analyzer splits one word into pieces, as
`StandardAnalyzer` splits `E45/5326` into `e45` and `5326`, the pieces must appear together and
in order. Separate words still match any of the words. `text:splitOnWhitespace` and
`text:autoGeneratePhraseQueries` turn this off; see
[Index Resources](03-configuration.md#index-resources).

A search client that passes user input through as plain text should escape the characters the
classic parser treats as syntax. `/` is the one that most often appears in codes: unescaped,
`E45/5326` starts a regular expression and the search fails.

Use `cqlFilter` for structured predicates — `=`, ranges, `in`, `between`, spatial, same-child
correlation. Field scoping belongs in `fieldSpec`: an embedded `fieldName:value` prefix in the
query string would use internal Lucene field names and bypass the field-IRI contract.

Pinned by `TestLuceneQuerySyntax` and `TestQueryParserSettings`.

### Return bindings

| Variable | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Query-scoped join key for `luc:match` and `luc:nestedMatch`. Each `luc:query` in a query labels its hits distinctly |
| `?entity` | IRI | Matched entity |
| `?score` | float | Lucene relevance score; for a sorted search, `1/(1+rank)` (see below) |
| `?totalHits` | `xsd:integer` | Total matching hits across the whole result set, independent of `limit` and `offset` |
| `?rank` | `xsd:integer` | Position in the whole result set, counting from 0. Continues across pages: `offset 5` starts at rank 5, and a page after a cursor continues from the cursor's rank |

`?match` is not part of `luc:query`.

#### Use `?rank` for order, not `?score`

SPARQL results arrive in order, so most queries never need `?rank`. It exists for
consumers that receive an **unordered** result set — most obviously a `CONSTRUCT` graph,
where order is not a property of the payload at all.

`?score` cannot fill that role:

- A match-all query (`*`) scores **every** document `1.0`, so there is nothing to sort on.
- Real relevance scores tie routinely — two hits at `3.6702733` in the demo dataset.

`?rank` is the position itself, so it is always strictly increasing and always recovers
the order the engine returned, whether that order came from relevance or from a sort spec.

#### `?score` under a sort spec

Lucene does not score documents when a sort is applied, so a sorted search has no
relevance to report. Rather than binding `NaN`, `?score` then carries `1/(1+rank)`: the
first hit scores `1.0`, the second `0.5`, and so on. The value depends only on rank, so a
hit keeps the same score when a later page re-runs the search with a larger window.

Do not read a sorted search's `?score` as a relevance magnitude, and prefer `?rank` when
what you want is order.

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
- A negative `offset` is a query error. A negative `limit` returns every hit from `offset` on. A `limit` of `0` returns the default page of 10.
- Ties keep Lucene's index order, which changes when an entity is re-inserted. Add the
  [entity IRI key](#entity-iri) as the last sort key for an order that does not, or page
  by cursor, which adds it for you.

### Cursor paging

A deep `offset` is costly: Lucene collects `offset + limit` hits to return `limit` of
them. A cursor instead names a hit's place in the order, and the next page is the
`limit` hits after it, however deep.

Pass `"*"` as the eighth argument for the first page, and ask each hit for its cursor
with [`luc:cursor`](#luccursor):

```sparql
SELECT ?entity ?rank ?cursor WHERE {
  (?hit ?entity ?score ?totalHits ?rank)
    luc:query ("default" "default" "" "" '[{"field":"urn:jena:lucene:field#title"}]' 10 0 "*") .
  ?hit luc:cursor ?cursor .
} ORDER BY ?rank
```

For the next page, keep every other argument and pass the last hit's cursor as the eighth:

```sparql
  luc:query ("default" "default" "" "" '[{"field":"urn:jena:lucene:field#title"}]' 10 0
             "<the last hit's cursor>") .
```

An empty page means the walk is over.

**Cost.** Over 60,000 hits of 100,000 entities, a page after a cursor in an order sorted
on a field, or on the IRI key, cost 1–5 ms at any depth, against 11–35 ms for the same
page by `offset` at depths of 24,000 and 59,990. In relevance order each page scores and
compares every hit, about 10 ms for 60,000 hits at any depth, so to harvest a whole result
set, sort it, by the IRI key if by nothing else. The IRI key makes a deep `offset` dearer
where ties are common, as every hit tied on the earlier keys is compared by IRI: at depth
59,990, twice the cost on a year sort and four times in relevance order, against no
difference on a sort whose values are unique. The first pages cost the same either way.

**The order is total.** Paged by cursor, `luc:query` appends the
[entity IRI key](#entity-iri), ascending, unless `sortSpec` already sorts by it. With no
`sortSpec`, hits order by relevance and then entity IRI. A page that starts from `"*"`
and a page that continues from a cursor are in the same order, so start every walk from
`"*"`. To jump to a page whose `next` link should be a cursor, pass `"*"` with an
`offset`: `offset` then counts from the start, and the page's cursors continue the same
order.

**Cursors.** A cursor is opaque. It is unpadded base64url (`A-Z a-z 0-9 - _`), so it can
go in a URL query parameter unescaped, and it holds the hit's sort values, its entity IRI
and its rank. It names values rather than a Lucene document, so it stays valid across
commits, merges and re-inserted entities: the next page is the hits that sort after it in
the index as it is now. It does not expire.

- A cursor is tied to the sort it was read from. Used with a different `sortSpec` it is a
  query error, as is a string that is not a cursor `luc:cursor` returned. It is not tied
  to `queryString` or `cqlFilter`.
- `?rank`, and a sorted search's `?score`, continue from the cursor's rank. If the index
  has changed since the cursor was read, they count from where the hit was then.
- In relevance order over a text query, BM25 scores depend on index statistics, which a
  commit, and even a merge, can change. A page after such a change continues from the
  cursor's score among the new scores, so hits near the boundary can be skipped or
  repeated. A sort on fields, or a search with no `queryString`, whose hits all score the
  same, is not affected.
- An entity that matches two shapes has a document for each, with the same IRI. If both
  tie on every key and a page ends between them, the second is not returned.
- The IRI key needs the entity IRI doc value: see [Entity IRI](#entity-iri).

**Errors.** Three errors start with a fixed marker, so a client can tell them apart without
parsing the message:

| Marker | Raised when |
|---|---|
| `[LUC_CURSOR_MALFORMED]` | `after` is not a cursor `luc:cursor` returned: not base64url, truncated, extended, or holding values of the wrong types |
| `[LUC_CURSOR_OTHER_SORT]` | `after` is a cursor read from a search with a different `sortSpec`, relevance order included |
| `[LUC_ENTITY_SORT_NEEDS_REBUILD]` | the IRI key is used, by `sortSpec` or by paging by cursor, on an index with documents older than the entity IRI doc value |

The markers are part of the API and change only with notice. Other errors, an unknown sort
field among them, carry none. Through Fuseki every `luc:query` error, these included, is an
HTTP 500 whose `text/plain` body is the message, marker first; Fuseki gives no 4xx for an
error raised while a query runs, so map the markers to a client error yourself.

## luc:match

### Syntax

```sparql
(?hit ?field ?value ?snippet) luc:match ()
```

The object is always `()`. The field slot takes a variable or a field IRI; an IRI keeps
only the matches on that field:

```sparql
(?hit field:title ?value) luc:match () .
```

A constant in the value slot keeps only that value. This is what happens when `?field`
and `?value` are shared with a `luc:fields` pattern evaluated first.

### Purpose

`luc:match` is the only per-hit match-detail API. It joins to `luc:query` through `?hit` and returns one row per matched field.

Match details are computed only when a query uses `luc:match`. The first call computes
them for every hit `luc:query` has returned, in one pass over the index, and later calls
read them from memory. A query without `luc:match` does not compute them.

### Return bindings

| Variable | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Join key from `luc:query` |
| `?field` | IRI | Field IRI that matched; a variable or a field IRI to filter on |
| `?value` | IRI or literal | The field value the query matched. Unbound for a field with `idx:stored false` |
| `?snippet` | literal | Reserved for later highlighting support |

On a multi-valued field, `?value` is the first value the query matches. A field read
from doc values (see [03-configuration.md](03-configuration.md#where-values-are-kept))
lists its values in byte order, so when no single value matches, for example a phrase
spanning values, the first value in byte order is returned.

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

The object is always `()`. The field slot takes a variable or a field IRI; an IRI keeps
only that field's values.

### Purpose

`luc:nestedMatch` projects the `idx:nested` child documents that satisfied the CQL
filter. Where `luc:match` answers "which fields of the *text query* matched on the
entity", this answers "which child records did the *filter* select, and what do they
contain". It joins to `luc:query` through `?hit`, exactly as `luc:match` does.

Only fields declared `idx:stored true` inside the nested block are projected; an
indexed-but-unstored field can be filtered on but has nothing to return.

### Return bindings

| Variable | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Join key from `luc:query` |
| `?record` | blank node | Grouping key — one per matching child document |
| `?field` | IRI | Field IRI within the child; a variable or a field IRI to filter on |
| `?value` | IRI or literal | Field value |

`?record` is the point of the API. When a filter selects two children of the same
entity, a flat stream of `(?field ?value)` rows cannot say which value belongs with
which key; grouping by `?record` keeps each child's fields together.

### Example

Given a `prov:qualifiedAttribution` nested block, "reports where Sarah Jones was the
Principal Investigator", returning the attribution that matched:

```sparql
SELECT ?entity ?record ?field ?value WHERE {
  (?hit ?entity)
    luc:query ("default" "default" ""
      '{"op":"and","args":[
         {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Principal Investigator"]},
         {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionAgentExact"},"Dr Sarah Jones"]}
       ]}'
      "" 10 0) .
  (?hit ?record ?field ?value) luc:nestedMatch () .
}
```

### Behaviour and limits

- A filter with **no** nested clause projects nothing. Nothing selected a child, so
  there is no "the child that matched" to report — returning every child of every
  scope would be a different feature.
- A **negated** nested clause (`{"op":"not",...}`) projects nothing for that clause.
  It describes children that must *not* match, which are not why the entity surfaced.
- Projection does not depend on how a clause is written. `=` and `in` over the same
  value, and a clause on a field that is an `idx:facetHierarchy` level versus one that
  isn't, all project the same records. This required a compiler change: a lone `=` on
  the *first level* of a hierarchy used to compile to a taxonomy `DrillDownQuery` on the
  parent, which selects the same entities but carries no child query. Nested-scoped
  fields now stay on the block-join path at every level. Root-scoped hierarchy fields
  keep the drill-down — they have no children to lose.
- At most 100 child records per hit are projected. Exceeding that is logged, not
  silently trimmed.
- The projection is computed on the first `luc:nestedMatch` call, for every hit
  `luc:query` has returned: one block-restricted child query per hit, against the index
  state `luc:query` searched. A query without `luc:nestedMatch` does not compute it.

## luc:fields

### Syntax

```sparql
(?hit ?field ?value) luc:fields ()
```

The object is always `()`.

### Purpose

`luc:fields` returns field values for each hit from the Lucene index, with no join to
the graph. It suits queries that need one or two values for many hits, such as an
identifier for every hit of a large search. It joins to `luc:query` through `?hit`.

It behaves like a triple pattern with `?hit` as subject: one row per value. A variable
in the field slot ranges over every returnable root field; a field IRI selects one field.
One pattern per field gives one column per field.

A returnable root field has `idx:stored true` and an occurrence on the shape itself, not
only inside an `idx:nested` block. `LATLON` fields are not returned.

### Return bindings

| Variable | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Join key from `luc:query` |
| `?field` | IRI | A variable, or the field IRI to return |
| `?value` | IRI or literal | One value. A constant keeps only that value |

Values are the terms `luc:match` returns for the same field. A multi-valued field gives
one row per value. A field read from doc values gives each distinct value once, in byte
order; one read from stored fields gives values in indexing order.

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

Binding only `?hit` means `luc:query` reads no entity IRI. When `field:collarId` is a
sortable `KEYWORD`, its value is read from its doc value and the query reads no stored
field.

### With luc:match

Both join on `?hit`, so a hit's rows are every combination of its `luc:match` rows and
its `luc:fields` rows. Use different variable names. With `(?hit ?field ?value)` in
both, the patterns also join on `?field` and `?value` and keep only the pairs both
produce.

### Errors

Each raises a query error naming the field:

- a field IRI that is not configured in any index of the dataset; in a query over two
  indexes, a field of one gives no rows for the hits of the other
- a field with `idx:stored false`
- a field that occurs only inside an `idx:nested` block; `luc:nestedMatch` returns those
- a read after the index was refreshed under the search (see [Shared Execution](#shared-execution))

They are raised on the first call, because the index is known only once `luc:query`
has run.

### Reads

The first call for a field reads it for every hit `luc:query` has returned, in one pass
in Lucene doc-ID order, and caches the values; later calls are lookups. With `?hit`
unbound, it reads every hit `luc:query` cached. The cache holds one entry per value.

## luc:cursor

### Syntax

```sparql
?hit luc:cursor ?cursor
```

### Purpose

`luc:cursor` returns a hit's cursor: a string literal that, passed back as `luc:query`'s
eighth argument, continues with the hits after this one. See
[Cursor paging](#cursor-paging). It joins to `luc:query` through `?hit`, as `luc:match`
does.

Only a `luc:query` paged by cursor, given `"*"` or a cursor as its eighth argument, has
cursors. Asking for one from any other search is a query error: its order is not total,
so a cursor from it could not be continued without skipping or repeating hits.

### Return bindings

| Variable | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Join key from `luc:query` |
| `?cursor` | string literal | The hit's cursor. A constant keeps only the hit with that cursor |

A cursor is built from the sort values the search already holds, so it reads nothing from
the index. With `?hit` unbound it gives a row for every cached hit of every search paged
by cursor in the query.

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

## luc:config

### Syntax

```sparql
(?s ?p ?o) luc:config (indexSelector)
```

### Purpose

Returns the selected index's configuration as triples, so a client can find out which
fields exist and what each supports without a copy of the configuration file. The index
must set [`text:exposeConfig true`](03-configuration.md#textexposeconfig); otherwise the
call is a query error.

What is returned is the configuration as written: every triple reachable from the index
resource, without following `rdf:type`. A setting left at its default is absent, not
written out. The defaults are in the tables of [03-configuration.md](03-configuration.md).

### Arguments

| Position | Meaning |
|---|---|
| `indexSelector` | The index, as for `luc:query` |

### Subject slots

Each of `?s`, `?p` and `?o` is a variable or a constant. The call matches like one triple
pattern over the configuration, so a constant restricts the result. Blank nodes are
stable across calls in one query, so several calls join through them.

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

The path feeding each field of one shape:

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

- A SPARQL property path in the query is evaluated against the dataset, not against
  `luc:config`'s output, so `rdf:rest*/rdf:first` cannot walk a list such as a sequence
  `sh:path`. Use one `luc:config` call per list cell, or `CONSTRUCT` the configuration and
  query the result.
- Errors: an index without `text:exposeConfig true`, an unknown selector, and a selector
  that is not a literal are query errors. A subject that is not three slots, or an object
  that is not one selector, is a query build error.

## luc:stats

### Syntax

```sparql
(?s ?p ?o) luc:stats (indexSelector)
```

### Purpose

Returns figures about the selected index's Lucene contents as triples: how many entities
and documents it holds, its size, and per field how many documents, distinct terms,
tokens or points it has. No setting enables or disables it.

### Arguments

| Position | Meaning |
|---|---|
| `indexSelector` | The index, as for `luc:query` |

### Subject slots

As for [`luc:config`](#lucconfig): each of `?s`, `?p` and `?o` is a variable or a
constant, and the call matches like one triple pattern.

### Returned triples

The subjects are the nodes `luc:config` uses, so the two join. Index-level figures have
the index resource as subject: the URI or blank node the configuration file declares the
index with. Field figures have the field IRI. Every value is an `xsd:long`.

| Subject | Predicate | Value |
|---|---|---|
| index | `luc:entityCount` | Live entities |
| index | `luc:documentCount` | Live Lucene documents, including the child documents of `idx:nested` blocks |
| index | `luc:deletedDocumentCount` | Deleted documents not yet merged away |
| index | `luc:segmentCount` | Lucene segments |
| index | `luc:sizeInBytes` | Size of the main index's segment files; the taxonomy index is not included |
| `TextField`, `KeywordField` | `luc:documentCount` | Documents with at least one term in the field |
| `TextField`, `KeywordField` | `luc:termCount` | Distinct terms. For a keyword field, distinct values |
| `TextField`, `KeywordField` | `luc:tokenCount` | Terms indexed, counting repeats |
| `IntField`, `LongField`, `DoubleField`, `TemporalField`, `LatLonField` | `luc:documentCount` | Documents with at least one value in the field |
| the same | `luc:pointCount` | Points indexed. A `LatLonField` line or polygon is indexed as several triangles, each counted |

A field with no indexed values reports `0`. A field declared with `idx:indexed false` has
no terms or points, so it reports `0` too.

### Examples

All figures as RDF:

```sparql
PREFIX luc: <urn:jena:lucene:index#>

CONSTRUCT { ?s ?p ?o } WHERE { (?s ?p ?o) luc:stats ("default") }
```

Distinct values of each keyword field, joining with `luc:config`, which needs
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

- Field figures come from Lucene's index statistics, which include documents that are
  deleted but not yet merged away. After updates and deletes they can exceed what a
  query finds, until segment merging removes the deleted documents.
  `luc:deletedDocumentCount` says how many there are.
- A field inside an `idx:nested` block is held on child documents, so its
  `luc:documentCount` counts child records, not entities.
- The figures come from the searcher `luc:query` uses, so they include every committed
  update. They are cached until the next commit. With more than one segment,
  `luc:termCount` is counted by reading every term of every text and keyword field, so on
  a large index the first call after a commit takes longer than the calls after it.
- Errors: an unknown selector and a selector that is not a literal are query errors. A
  subject that is not three slots, or an object that is not one selector, is a query
  build error.

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

### `text_query` — analyzer-aware text matching

`=` is exact-term equality and does not apply an analyzer. For analyzer-mediated text matching (edge-ngram typeahead, lowercased keyword, standard tokenisation, stemming, etc.) use `text_query`:

```json
{"op":"text_query","args":[{"property":"urn:jena:lucene:field#title"},"gold mine"]}
```

The supplied text is tokenised through the field's query analyzer, then its index analyzer, then the index-wide one — so a `TEXT` field that configures no analyzer at all is still searched with the default (`StandardAnalyzer`) rather than by raw term:

- single token → `TermQuery`
- multiple tokens → `PhraseQuery` (positional)
- empty token stream (e.g. all-stopword input) → matches nothing rather than everything

What a field will and will not match follows from its analyzer, and a mismatch shows up as an empty result rather than an error — see [03-configuration.md → Choosing an Analyzer for a TEXT Field](03-configuration.md#choosing-an-analyzer-for-a-text-field). In particular, an edge-n-gram field only matches mid-value words when it declares `text:tokenized true`.

When to choose which:

| Operator | Use for |
|---|---|
| `=` | `KEYWORD`, numeric, temporal exact equality; root-level entity-type pivots |
| `text_query` | analyzer-backed `TEXT` fields — typeahead, full-text search, edge-ngram |
| `like` | wildcard pattern matching (`%`, `_`) on KEYWORD/TEXT fields |

### Nested same-child filters

For `idx:nested` child records (qualified identifiers, prov:qualifiedAttribution, location assessments, etc.) clauses that reference fields in the same nested scope and live in the same CQL subtree are folded into one block-join: a parent surfaces only when ONE child satisfies ALL the in-scope clauses.

**Qualified identifier — both clauses are KEYWORD** (`schema:identifier` with `propertyID` + `value`):

```json
{
  "op": "and",
  "args": [
    {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierType"},"company"]},
    {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierValueExact"},"Bellbird"]}
  ]
}
```

Returns only entities whose ONE identifier record has propertyID="company" AND value="Bellbird" — no cross-child matching where one identifier supplies the type and a different identifier supplies the value.

**Identifier with text/typeahead** (KEYWORD type + edge-ngram value):

```json
{
  "op": "and",
  "args": [
    {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierType"},"anumber"]},
    {"op":"text_query","args":[{"property":"urn:jena:lucene:field#identifierValueText"},"A-94"]}
  ]
}
```

Same-child guarantee: a borehole surfaces only when ONE identifier record has propertyID="anumber" AND its value matches the "A-94" n-gram. The text analyzer normalises "A-94" through whatever the field configures (e.g. `LowerCaseKeywordAnalyzer` → "a-94"), so case-insensitive typeahead works regardless of input case.

**Qualified attribution** (`prov:qualifiedAttribution` with `hadRole` + `agent`):

```json
{
  "op": "and",
  "args": [
    {"op":"=","args":[{"property":"urn:jena:lucene:field#attributionRole"},"Principal Investigator"]},
    {"op":"text_query","args":[{"property":"urn:jena:lucene:field#attributionAgentText"},"Sarah Jones"]}
  ]
}
```

A report surfaces only when ONE qualified-attribution record has hadRole="Principal Investigator" AND an agent matching "Sarah Jones" — not where the role is on one attribution and the name is on another.

`attributionAgentText` here is a plain `TEXT` field with no analyzer override, so the input is tokenised the same way the index was and `"Sarah Jones"` matches `"Dr Sarah Jones"` as a phrase. Reaching for an n-gram twin instead is a common mistake — see [03-configuration.md → Names want BM25, not n-grams](03-configuration.md#names-want-bm25-not-n-grams).

**Boundary worth knowing:** the same-child fold operates within one CQL filter subtree. If the type clause sits in `cqlFilter` and the text clause sits in `queryString` (the separate text input on `luc:query`), they are not in the same CqlAnd and the fold cannot apply — each lifts independently. For same-child correctness, put both clauses in `cqlFilter` (using `=` and `text_query` as shown above).

### Naming a field

A filter may name a field by its canonical IRI or by its `idx:fieldName`. Both spellings
work, and both work for `luc:facet` too.

A name that resolves to **nothing** raises `TextIndexException`, as does filtering on a
field that exists but is not indexed. Neither is silently ignored.

That matters more than it sounds. An ignored clause is not applied anywhere, so the query
answers with **more** rows than were asked for, and nothing in the result says so. A typo
in a field name used to return the whole dataset. Inside an `or` it was worse: the fold
abandons the whole disjunction when one branch cannot be pushed, so a single bad name
dropped every other branch as well.

The same rule applies to `luc:facet`: a spec matching no field and no hierarchy dimension
raises rather than returning an empty bucket list, which was indistinguishable from a real
field with no values.

### A filter is applied, or the query is refused

The rule is not limited to names that resolve to nothing. **Any** clause the compiler
cannot turn into a Lucene query raises:

| Clause | Why it cannot be pushed |
|---|---|
| `text_query` or `like` on a numeric, temporal or spatial field | no defined meaning |
| a range operator on a `KEYWORD` field | no ordering to compare against |
| any operator other than `=` and `<>` on `entityIri` | only those two are defined for it |
| a property naming no field, or a field that is not indexed | nothing to filter on |

All of these used to become a *residual*, and nothing evaluates residuals: every caller
logs and discards them. The distinction between "no such field" and "this operator does
not suit that field" never reached the caller, who saw the same thing either way, a filter
that was ignored and a result set larger than it should be.

Inside an `or` it was worse again. One unpushable branch made the whole disjunction a
residual, so **every** branch was dropped and the query returned rows matching none of
them.

There is now one rule across the compiler: a filter is applied in full, or the query is
refused with a message naming the clause.

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

A sort the index cannot carry out is a query error rather than a key that silently orders
nothing:

- an unknown field IRI, including a misspelled [entity IRI key](#entity-iri)
- a field without sort doc values: a `KEYWORD` field needs `idx:sortable`; a numeric or
  temporal field needs `idx:sortable` or `idx:facetable`, which write the same doc values
- a `TEXT` or `LatLon` field
- a field that occurs only inside `idx:nested` blocks, without a [selector](#nested-sort-selector)

A search that fails, for any of these reasons or because its `queryString` does not
parse, raises an error rather than returning zero hits.

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

Any sort object may also carry `"missing": "first" | "last"` to place entities that have no
value for the sort field. Omit it to keep Lucene's own default placement.

### Entity IRI

The reserved key `urn:jena:lucene:index#entity` sorts by entity IRI:

```json
[
  {"field":"urn:jena:lucene:field#title","order":"asc","missing":"last"},
  {"field":"urn:jena:lucene:index#entity","order":"asc"}
]
```

As the last key it breaks ties in an order that does not depend on when documents were
written. Without it, hits that tie on every key keep Lucene's index order, which changes
when an entity is updated or re-inserted. It works like any other key too: `"desc"`,
alone, or first.

IRIs compare by code point: the bytes of their UTF-8 form, in order. Jena's own
`ORDER BY STR(?entity)` compares UTF-16 code units instead. The two agree on every pair of
IRIs except where the first difference sets a character beyond U+FFFF against one from
U+E000 to U+FFFF: the key puts `x\uFF21` before `x\U0001F600`, Jena's `ORDER BY` the other way
round. `missing` has no effect, since every entity has an IRI, and a `selector` is an error.

The key reads the entity IRI doc value, which indexes have written since 2026-10-12
(`d6f0091`, #195). An index holding any document written before then has no value to sort
that document by, so sorting by the key, or paging by cursor, raises an error asking for
the index to be rebuilt.

### Nested Sort Selector

To order by a value drawn from one specific nested child record — "sort by the identifier
value *where* `identifierType = companyID`" — add a `selector` to the sort object:

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
| `field` | the child value field to sort on; must be `idx:sortable` |
| `selector.field` | the co-located discriminator on the same child document |
| `selector.eq` | the value that discriminator must equal |
| `order` | `asc` (default) / `desc`, as for a flat sort |
| `missing` | `first` \| `last` — placement of entities with no matching child. Default `last` |

The nested scope is inferred from `field`; it is not part of the wire format.

The `selector` **chooses a sort key; it does not restrict the result set**. It picks which
child supplies the key and never removes entities: those with no matching child keep their
place in the results and are positioned by `missing`. To also restrict *which* entities
appear, state that independently in `cqlFilter` — the two compose.

> **Renamed from `filter`.** This key was called `filter` in earlier commits. It is not
> accepted as an alias — a sort spec carrying `filter` (or any other unrecognised key) is
> rejected with an error naming `selector`, rather than being silently ignored and
> returning plausible-looking rows in the wrong order.

When an entity has several matching children the parent key collapses MIN (ascending) /
MAX (descending). The normal one-record-per-type case makes MIN = MAX.

#### Why select and filter separately?

Because the two act on different things. `cqlFilter` decides *which documents*
come back; a sort reads its key from doc-values on a document that has already been
picked. Filtering on the discriminator therefore does nothing at all to the sort key.

Concretely: nested values live on the child documents, not the parent, so a plain
`{"field":"urn:jena:lucene:field#identifierValue"}` sort finds no key on any parent, and
is refused as an error — whatever `cqlFilter` says. And even where a value *is* flattened onto
the parent, the sort collapses the entity's whole bag: an entity whose companyID is
`C-200` but whose govID is `A-000` sorts on `A-000`. The filter chose the entity; the
sort key still came from the wrong record.

The sort's `selector` is what keeps the child identity long enough to say "take the
value from *this* child". The same split exists elsewhere: Elasticsearch's nested sort
takes its own `nested.filter` independent of the query, and in SQL the discriminator
belongs in the `LEFT JOIN ... ON` clause — move it to `WHERE` and the outer join
collapses, dropping the very rows you wanted to keep.

The selector is nested-only. A flat multivalued field is a decorrelated bag with no
surviving per-value discriminator, so a `selector` on a root-scoped field is an error
(`Sort selector requires a nested field`). Both fields must belong to the *same*
`idx:nested` block — that is what puts them on one child document.

Data-modelling requirements (no extra index configuration):

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

Because the sort happens inside Lucene, it is applied before `limit`/`offset` cut the
page — pagination over a selector-ordered result set stays correct across page boundaries.

## Shared Execution

`luc:query`, `luc:facet`, `luc:match`, `luc:nestedMatch` and `luc:fields` share a
single Lucene execution when these match:

- resolved index identity
- search field spec
- query string
- CQL filter
- sort spec

Facet-specific parameters such as requested facet fields, `maxValues`, and `minCount` are applied after the shared search step.

A `luc:query` paged by cursor is a search of its own, keyed also by its `after` argument;
`luc:facet` does not share it.

`luc:match`, `luc:nestedMatch`, `luc:fields` and `luc:cursor` find their search through `?hit`: each search in a
query labels its hits with its own prefix, so a query with two `luc:query` patterns over
different searches joins each `?hit` to the right one.

Entity IRIs, match details, child records and field values are read after the search, and only when
the query asks for them. They are read against the index state the search used: the
search holds its Lucene searcher until `luc:query`'s results are consumed or the query
is closed or cancelled. If a read happens after that, or a later `luc:query` over the
same search needs more hits than were fetched, and the index has been committed in the
meantime, the query fails with an error asking for it to be re-run, rather than return
values from a different index state.
