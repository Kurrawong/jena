# User Guide

This guide covers SHACL-mode text indexing and search.

## Mental Model

Each entity matching a configured `sh:targetClass` becomes one Lucene document with typed fields.

The main SHACL property functions are:

- `luc:query` for hit search
- `luc:match` for per-hit field/value details
- `luc:facet` for aggregate counts

## Quick Start

### 1. Configure a dataset

```turtle
@prefix text: <http://jena.apache.org/text#> .
@prefix tdb2: <http://jena.apache.org/2016/tdb#> .

<#ds> a text:TextDataset ;
    text:dataset <#baseDs> ;
    text:indexes <#index> .

<#baseDs> a tdb2:DatasetTDB2 ;
    tdb2:location "/path/to/tdb2" .
```

### 2. Define fields

```turtle
@prefix idx: <urn:jena:lucene:index#> .
@prefix sh:  <http://www.w3.org/ns/shacl#> .
@prefix field: <urn:jena:lucene:field#> .

field:title
    idx:fieldName "title" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true ;
    sh:path rdfs:label .

field:category
    idx:fieldName "category" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path ex:category .
```

### 3. Define a shape

```turtle
<#BookShape>
    sh:targetClass ex:Book ;
    sh:property field:title ;
    sh:property field:category .
```

### 4. Query it

```sparql
PREFIX luc: <urn:jena:lucene:index#>

SELECT ?entity ?score WHERE {
  (?hit ?entity ?score)
    luc:query ("default" "default" "machine learning" "" "" 20 0) .
}
ORDER BY DESC(?score)
```

## Query Model

Two selectors exist at query time:

- `indexSelector`: which index to use
- `fieldSpec`: which fields inside that index to search

These are separate on purpose.

### `luc:query`

```sparql
(?hit ?entity ?score ?rank ?totalHits)
  luc:query (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset)
```

Example with filter:

```sparql
(?hit ?entity ?score ?rank ?totalHits)
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

Example with sort:

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

Paging — second page of 10:

```sparql
(?hit ?entity ?score ?rank ?totalHits)
  luc:query ("default" "default" "learning" "" "" 10 10) .
```

Notes:

- `fieldSpec` is `"default"` or a JSON array of field IRIs.
- `cqlFilter` is a JSON object or `""`.
- `sortSpec` is a JSON object/array or `""`.
- `limit` is the page size; negative means unlimited.
- `offset` is the number of leading hits to skip; must be non-negative. `?totalHits` is independent of `offset`.
- `?match` is not part of `luc:query`.

### Graph Scoping

Target model:

- graph scoping is treated as a normal doc-level filter
- there is no dedicated `?graph` slot in the public SHACL query signature
- the reserved synthetic field IRI is `urn:jena:lucene:field#sourceGraph`

Example target filter:

```sparql
(?hit ?entity ?score)
  luc:query (
    "default"
    "default"
    "*"
    '{"op":"=","args":[{"property":"urn:jena:lucene:field#sourceGraph"},"http://example.org/graph/A"]}'
    ""
    20
    0
  ) .
```

Intended semantics:

- `sourceGraph` is multi-valued
- it records every graph touched while indexing the entity document
- strict graph partitioning should still be handled at index time when needed

### `luc:match`

```sparql
(?hit ?field ?value ?snippet) luc:match ()
```

Use it when you need to know which field matched:

```sparql
SELECT ?entity ?field ?value WHERE {
  (?hit ?entity ?score)
    luc:query ("default" '["urn:jena:lucene:field#title"]' "copper" "" "" 10 0) .
  (?hit ?field ?value) luc:match () .
}
```

### `luc:facet`

```sparql
(?field ?value ?low ?high ?count)
  luc:facet (indexSelector fieldSpec queryString facetFields cqlFilter maxValues minCount)
```

Flat facets use the same 5-slot subject form. On flat facet rows, `?value` is bound and `?low` / `?high` are left unbound.

Example:

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

## Field Identity

External SPARQL always uses field IRIs:

- query `fieldSpec`
- facet `facetFields`
- CQL `property`
- sort `"field"`
- returned `?field` bindings from `luc:match` and `luc:facet`

`idx:fieldName` is still important, but only as the internal Lucene field key.

## Fixed Arity

The SHACL API is strict:

- `luc:query` object arity is exactly 7
- `luc:facet` object arity is exactly 7
- no argument-shape guessing
- missing arguments fail fast

Use `""` placeholders when a slot is intentionally unused.

## Multiple Indexes

If you configure multiple indexes, use different `text:indexId` values and select one explicitly:

```sparql
(?hit ?entity ?score)
  luc:query ("objects" "default" "gold" "" "" 20 0) .
```

The selector may also be the index resource IRI if the index was configured as a URI resource.

## Troubleshooting

Common causes of failures:

- Wrong object arity.
- Using field names instead of field IRIs.
- Using an index selector that is not registered.
- Sorting on a non-sortable field.
- Requesting a field IRI that does not exist in the selected index.

If a query suddenly stops working after this change, check the first two object arguments first:

1. `indexSelector`
2. `fieldSpec`
