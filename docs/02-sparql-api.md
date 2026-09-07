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
- `luc:query` returns `?hit`; per-hit match detail comes from `luc:match`, and the
  nested child records that satisfied the filter from `luc:nestedMatch`.
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

Subject arity may be 1 to 5:

- `?hit`
- `?hit ?entity`
- `?hit ?entity ?score`
- `?hit ?entity ?score ?totalHits`
- `?hit ?entity ?score ?totalHits ?rank`

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

Object arity is always exactly 7.

### Arguments

| Position | Name | Type | Required | Notes |
|---|---|---|---|---|
| 1 | `indexSelector` | string literal | Yes | Usually `"default"`; may also be a configured index id or index IRI |
| 2 | `fieldSpec` | string literal | Yes | `"default"` or a JSON array of field IRIs |
| 3 | `queryString` | string literal | Yes | Lucene query string — or, when `fieldSpec` names a vector field, plain text to embed |
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

### `queryString`

Parsed by Lucene's classic
[`QueryParser`](https://lucene.apache.org/core/10_3_1/queryparser/org/apache/lucene/queryparser/classic/package-summary.html#package.description)
— `MultiFieldQueryParser` when `fieldSpec` names more than one field — and scored with BM25.
The full classic syntax is available with no extra configuration: phrases, `AND`/`OR`/`NOT`,
`+`/`-`, wildcards (`quan*`, `qu?ntum`, and **leading** wildcards such as `*tum`, which Lucene
disables by default and this index enables), fuzzy (`learnimg~1`), proximity
(`"machine networks"~2`), boosts (`quantum^4`), grouping and term ranges. A bare `*` is
short-circuited to a match-all.

The string is analyzed with each target field's query analyzer, so behaviour follows the
field: on a `KEYWORD` field the whole value is a single term.

Use `cqlFilter` for structured predicates — `=`, ranges, `in`, `between`, spatial, same-child
correlation. Field scoping belongs in `fieldSpec`: an embedded `fieldName:value` prefix in the
query string would use internal Lucene field names and bypass the field-IRI contract.

Pinned by `TestLuceneQuerySyntax`.

### Return bindings

| Variable | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Query-scoped join key for `luc:match` |
| `?entity` | IRI | Matched entity |
| `?score` | float | Lucene relevance score; for a sorted search, `1/(1+rank)` (see below) |
| `?totalHits` | `xsd:integer` | Total matching hits across the whole result set, independent of `limit` and `offset` |
| `?rank` | `xsd:integer` | Position in the whole result set, counting from 0. Continues across pages: `offset 5` starts at rank 5 |

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
- A negative `offset` is a query error. A negative `limit` is still accepted and means unlimited (offset then has no effect beyond skipping).

## Vector (semantic) search

Naming an `idx:VectorField` in `fieldSpec` switches `luc:query` from full-text retrieval to
nearest-neighbour search. Nothing else about the call changes:

```sparql
(?hit ?entity ?score) luc:query (
    "default"
    '["urn:jena:lucene:field#embedding"]'
    "copper ore drilling in the goldfields"
    '{"op":"=","args":[{"property":"urn:jena:lucene:field#state"},"WA"]}'
    "" 10 0)
```

There is deliberately **no separate `luc:knn`** and no extra argument. Routing it through
`fieldSpec` means CQL filters, sort specs, paging, `?totalHits` and `luc:facet` all keep
working exactly as documented above, and the query arity is unchanged.

`queryString` stops being a Lucene expression in this mode — wildcards, boolean operators
and field prefixes are not parsed. It is text handed to the embedding model.

### The filter is pushed into the search, not applied to its result

The `cqlFilter` is passed to Lucene's `KnnFloatVectorQuery` as a filter, so it is applied
**during** the HNSW graph traversal: the search returns k documents that already satisfy
the filter.

This is the whole reason to do vector search here rather than alongside a separate vector
store. Post-filtering — running an unconstrained KNN and then intersecting with the filter
— returns fewer than k results, and frequently zero, whenever the filter is selective.

### What is rejected

| Combination | Why |
|---|---|
| Two vector fields in one `fieldSpec` | No defined way to combine two independent similarity rankings |
| A vector field mixed with text fields | This is hybrid retrieval. A boolean `SHOULD` would add a BM25 score to a cosine similarity — incomparable scales, where whichever is numerically larger wins. Reciprocal rank fusion is the correct treatment and is not implemented; issue separate `luc:query` patterns |
| Empty `queryString`, or `"*"` | A similarity search has no match-all form — there is no point to be near |
| Sorting on the vector field | A vector has no total order. Sorting on *other* fields works normally |

### Facet counts and ?totalHits are scoped to the top-k

This is a genuine difference from the contract stated elsewhere in this document, and it is
not avoidable. `luc:facet` normally counts over the full matching set, but a KNN query
returns top-k *by construction* — there is no "all matching documents" for a similarity
search.

Under a vector query:

- `luc:facet` counts values **within the k nearest neighbours**, after filtering.
- `?totalHits` is the number of neighbours returned, bounded by k — not a corpus count.

A single per-index `idx:knnTopK` (default 100) drives hits, facets and totals alike, so all
three describe the same result set. Raising it widens the counted set at proportional cost;
paging past k returns nothing.

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

## luc:nestedMatch

### Syntax

```sparql
(?hit ?record ?field ?value) luc:nestedMatch ()
```

The object is always `()`.

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
| `?field` | IRI | Field IRI within the child |
| `?value` | IRI or literal | Stored field value |

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
- The projection is computed during the `luc:query` search, so it costs one extra
  block-restricted child query per hit and needs no second Lucene search.

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
    {"op":"=","args":[{"property":"urn:jena:lucene:field#identifierValueExact"},"Newmont"]}
  ]
}
```

Returns only entities whose ONE identifier record has propertyID="company" AND value="Newmont" — no cross-child matching where one identifier supplies the type and a different identifier supplies the value.

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

Any sort object may also carry `"missing": "first" | "last"` to place entities that have no
value for the sort field. Omit it to keep Lucene's own default placement.

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
`{"field":"urn:jena:lucene:field#identifierValue"}` sort finds no key on any parent and
imposes no order — whatever `cqlFilter` says. And even where a value *is* flattened onto
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

`luc:query`, `luc:facet`, `luc:match`, and `luc:nestedMatch` share a single Lucene
execution when these match:

- resolved index identity
- search field spec
- query string
- CQL filter
- sort spec

Facet-specific parameters such as requested facet fields, `maxValues`, and `minCount` are applied after the shared search step.
