---
title: "Field projection for luc:query hits"
date: "2026-10-12"
status: "Designed; nothing implemented"
---

# Field Projection for luc:query Hits

This design lets a SPARQL query return stored field values for every `luc:query` hit
straight from the Lucene document, so that an identifier for each of 300,000 hits needs
no join back to the RDF store. It adds a `returnFields` argument to `luc:query` and a
`luc:fields` property function that emits the values.

Claims about current behaviour were checked against `5872e83981`.

## The requirement

The Discovery Portal needs about 300,000 CollarIds from one search with filters, which it
passes to a stored procedure (requested 2026-10-09; 10,000 is not enough and a bulk
download does not fit). Today the identifier is reached through the store:

```sparql
SELECT ?id WHERE {
  (?hit ?entity) luc:query ("default" "default" "<text>" '<CQL2-JSON>' "" 300000 0) .
  ?entity ex:collarId ?id .
}
```

Measured on local NVMe with the TDB2 and Lucene files evicted from the page cache before
each run:

| State | Time | Disk reads | Data read |
|---|---|---|---|
| Fully cold | 66 s | 440,797 | 16.4 GB |
| Cold except `nodes.dat`, read in first (5.8 s) | 44.5 s | 320,776 | 4.8 GB |
| Warm | 9.4 s | 0 | 0 |

The triple pattern makes TDB2 turn each entity IRI into a node ID through `nodes.dat`
(18.4 GB, hash-keyed, so each lookup is a random read), scan `SPOG.dat`, and turn each
`?id` back into a term through `nodes-data.obj`. `nodes.dat` accounted for 11.6 GB of the
16.4 GB. The lookups run one at a time, so elapsed time is roughly reads × per-read
latency. Cloud disks have 5–10 times the per-read latency of local NVMe, which matches
shared dev's cold rate of about 1 ms per record.

## Why no existing API covers it

`luc:match` returns stored values, but only for fields the text query matched
(`ShaclTextIndexLucene.computeFieldMatches`). A filter-driven search with query string `*`
compiles to a `MatchAllDocsQuery`, for which match computation is skipped, so it returns
nothing. Making the query string match the identifier field would replace the user's own
text query. `luc:nestedMatch` returns only nested children selected by the filter.

## What already happens per hit

`ShaclTextIndexLucene.searchWithHitIds` loads every hit's stored document to read the
entity IRI:

```java
Document doc = storedFields.document(sd.doc);
String uri = doc.get(entityField);
```

`StoredFields.document` decodes the whole stored document, so every stored field of every
hit is already read and then discarded. Projection keeps the requested values from that
same read. It adds no Lucene I/O.

## API

### `luc:query` gains an eighth argument

```sparql
(?hit ?entity ?score ?totalHits ?rank)
  luc:query (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset returnFields)
```

`returnFields` is a JSON array of field IRIs, or `""` for none. Object arity becomes
exactly 8. SHACL mode carries no backward-compatibility requirement, so the 7-argument
form is removed rather than kept as an alternative.

The field list belongs to `luc:query` because the values have to be read while the search
holds its `IndexSearcher`. A Lucene doc ID is only valid against the reader it came from,
and the searcher is released when `searchWithHitIds` returns. The comment on
`computeNestedMatches` records the same constraint. A property function evaluated after
the search cannot read the values itself.

### `luc:fields` emits the values

```sparql
(?hit ?field ?value) luc:fields ()
```

| Variable | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Join key from `luc:query` |
| `?field` | IRI | A field named in `returnFields`; a variable or a constant IRI |
| `?value` | IRI or literal | One stored value |

It returns one row per stored value, the same shape as `luc:match` and `luc:nestedMatch`.
`?field` may be a constant, which selects one field, so one pattern per field produces one
column per field:

```sparql
PREFIX luc: <urn:jena:lucene:index#>
PREFIX field: <https://example.org/field#>

SELECT ?id ?name WHERE {
  (?hit) luc:query ("default" "default" "<text>" '<CQL2-JSON>' "" 300000 0
                    '["https://example.org/field#collarId","https://example.org/field#name"]') .
  (?hit field:collarId ?id) luc:fields () .
  (?hit field:name ?name) luc:fields () .
}
```

A multi-valued field gives one row per value, as a triple pattern over a multi-valued
predicate would. Rows come in `luc:query` order, because ARQ evaluates `luc:fields` once
per incoming binding.

### Errors

Each of the following raises a query error naming the field, rather than returning no
rows:

- an IRI in `returnFields` that is not a configured field (as for `fieldSpec`)
- a field with `idx:stored false`, which has no value to return
- a field inside an `idx:nested` block (see [Out of scope](#out-of-scope))
- a constant `?field` in `luc:fields` that is not in the `returnFields` of the search
  that produced `?hit`

This follows the existing rule in [02-sparql-api.md](02-sparql-api.md#a-filter-is-applied-or-the-query-is-refused):
a request the index cannot honour is refused.

### Alternatives rejected

| Alternative | Why rejected |
|---|---|
| Extend `luc:match` | It means "fields the text query matched". Returning unmatched fields from it changes that meaning for existing queries |
| Bind values in extra `luc:query` subject slots, e.g. `(?hit ?entity ?score ?totalHits ?rank ?collarId)` | Subject slots are positional and already carry five meanings. A multi-valued field in a slot has no single value to bind |
| Leave `luc:query` at 7 arguments and have `luc:fields` register its constant field IRIs at `build()` time for `luc:query` to read | Depends on ARQ constructing every stage's iterator before `luc:query` executes. That is not established, and a variable `?field` would give `luc:query` no list to read |

## Where values come from

Values come from stored fields only. Doc values are not a faithful copy of the value
written to the store:

| Field | Doc values written | Holds the original value? |
|---|---|---|
| `KEYWORD`, `idx:sortable` | `SortedDocValuesField` or `SortedSetDocValuesField` | No when `idx:normalizer` is set: it holds the normalized sort key |
| `KEYWORD`, `idx:facetable` | `SortedSetDocValuesFacetField` | Encoded in the shared facet field, not under the field's name |
| `KEYWORD`, neither | none | — |
| `INT`, `LONG`, `DOUBLE`, `idx:facetable` or `idx:sortable` | `SortedNumericDocValuesField` | The number, but not the lexical form or datatype |
| `TEMPORAL`, `idx:facetable` or `idx:sortable` | `SortedNumericDocValuesField` on an epoch twin | No: precision and timezone are not kept |
| Numeric or `TEMPORAL`, neither | none | — |

`idx:stored` defaults to `true` ([03-configuration.md](03-configuration.md#field-properties)),
so most configured fields can already be projected without reindexing.

Values are turned into RDF terms by the existing `fieldValueToNode`, which applies
`idx:storeLiteralMetadata` through `LiteralFieldSupport.reconstructNode` and aligns each
value with its datatype and language twin by index. `luc:fields` therefore returns the
same term `luc:match` returns for the same stored value. Whether that term equals the
source triple's object is governed by
[2026-04-09_date_literal_round_trip_design.md](2026-04-09_date_literal_round_trip_design.md).

## Read path

In `searchWithHitIds`, replace `storedFields.document(sd.doc)` with a
`StoredFieldVisitor` that accepts the entity field, each field in `returnFields`, and
their datatype and language twins. This decodes less than the current whole-document
load, so a query with no `returnFields` also gets cheaper. The values are kept on
`SearchHit` as a list of `FieldMatch`, the type `luc:match` already uses.

Hits are read in rank order, not doc-ID order. Stored fields are compressed in blocks
within each segment, so 300,000 hits touch some subset of those blocks, in a different
order for each sort. The current code already performs those reads, so projection adds
none. The cold cost of the Lucene side alone has not been measured and is part of the
[measurement plan](#measurement).

## Shared execution

`SearchExecution.buildKey` is unchanged: `returnFields` is not part of the key, so
`luc:facet` still shares the search with `luc:query`. A `SearchExecution` records the set
of fields it has loaded. If a later `luc:query` with the same key asks for a field
outside that set, the search re-runs with the union, as it already re-runs when the hit
window grows.

## Joining on `?hit`

`luc:match` and `luc:nestedMatch` cannot serve 300,000 hits as written, and `luc:fields`
must not copy them:

- **Quadratic join.** When `?hit` arrives bound, each scans every cached hit for an
  equal hit ID. For 300,000 hits that is 300,000 scans of 300,000 entries.
  `SearchExecution` should hold a `Map<Node, SearchHit>` built with the hit list, so each
  incoming binding costs one lookup.
- **Ambiguous search.** `findSearchExecution` returns the first `SearchExecution` found in
  the context, and `SearchHit` labels hits `hit0`, `hit1`, … in every search. With two
  `luc:query` calls in one query, `?hit` from the second can resolve against the first.
  Hit labels should carry a per-search prefix, and the property function should find the
  search by hit ID through a context-wide map.

The map and labels are introduced for `luc:fields`. Moving `luc:match` and
`luc:nestedMatch` onto them is a small follow-up and removes the same defects there.

## Memory

The hit list is already materialised in full: `SearchExecution` caches up to
`offset + limit` `SearchHit` objects. Projection adds one `FieldMatch` and one `Node` per
value. For one short identifier field on 300,000 hits that is an estimated 30–60 MB of
heap, not measured. Streaming hits instead of caching them would change how
`luc:facet`, `luc:match` and `?totalHits` share state, and this requirement does not need
it.

## Related defects found while designing

These are outside this design and need their own failing test and fix:

1. **A negative `limit` returns 10 hits.** [02-sparql-api.md](02-sparql-api.md#paging)
   says a negative `limit` means unlimited. `parseLimit` keeps `-1`, but
   `ShaclTextQueryPF.exec` then applies `args.limit > 0 ? args.limit : 10`. A client
   asking for every hit with `-1` gets ten, so a query that needs every hit has to pass
   an explicit limit.
2. **Match computation runs whether or not `luc:match` is used.** For any query string
   other than `*`, `searchWithHitIds` calls `computeFieldMatches`, which loads each hit's
   stored document a second time and evaluates `Weight.matches` on it. A 300,000-hit
   portal search with a text term pays that for every hit even when no `luc:match`
   appears in the query.

## Out of scope

- Fields inside `idx:nested` blocks. Their values live on child documents, and which
  child to return is the question `luc:nestedMatch` answers. Projecting all children of
  every hit would be a different feature.
- TDB2-side changes: page-cache prewarming, readahead, parallel node-ID resolution, disk
  tier.
- Any change on the Discovery Portal or stored-procedure side.

## Implementation

| Change | Where |
|---|---|
| Parse `returnFields`; object arity 8 | `ShaclTextQueryPF.build` and `parseArgs`, `QueryArgs` |
| Loaded-field set, re-run on a wider set, hit-ID map | `SearchExecution` |
| Restricted stored-field visitor; keep projected values on the hit | `ShaclTextIndexLucene.searchWithHitIds`, `SearchHit` |
| Per-search hit labels | `SearchHit` constructor |
| The property function | New `TextFieldsPF`; `IndexVocab.pfFields = NS + "fields"`; registered in `TextQuery` beside `pfNestedMatch` |
| Every existing `luc:query` call site in tests, docs and the demo gains `""` as an eighth argument | `jena-text/src/test`, `docs/`, `demo/` |
| Docs | `luc:query` arguments and a `luc:fields` section in [02-sparql-api.md](02-sparql-api.md); the `idx:stored` row in [03-configuration.md](03-configuration.md#field-properties) names `luc:fields`; a row in [README.md](README.md) |

## Tests

Written first and seen to fail, per the repository's test discipline. New classes are
JUnit 5 and must be added to `TS_Text`'s `@SelectClasses`; a class missing from that list
never runs.

- A single-valued `KEYWORD` field with a filter-only search (`*`) returns one row per
  hit, in rank order, with the stored value. This is the Discovery Portal shape.
- The same with a text query string and with a sort spec, so rank order is checked under
  relevance and under a sort.
- A multi-valued field returns one row per value.
- `INT`, `LONG`, `DOUBLE` and `TEMPORAL` fields return the same term `luc:match` returns
  for the same value, with and without `idx:storeLiteralMetadata`.
- A `KEYWORD` field with `idx:normalizer` returns the raw value, not the normalized key.
- Two constant-field `luc:fields` patterns give one row per hit with both columns.
- Each error in [Errors](#errors) raises and names the field.
- Two `luc:query` calls with different searches in one query: each `?hit` resolves to its
  own search's values.
- `luc:query` and `luc:facet` with the same search share one `SearchExecution` when
  `returnFields` is set.
- A second `luc:query` with the same key and a wider `returnFields` gets the extra field.
- 300,000 hits complete in time linear in the hit count. Use a synthetic index sized to
  make a quadratic join fail a generous timeout, not a wall-clock benchmark.

## Measurement

On the same local dataset and NVMe as the baseline table, evict the TDB2 and Lucene files
from the page cache, then run the 300,000-ID query with `luc:fields` in place of the
triple pattern. Record time, disk reads and data read, cold and warm, and confirm the row
count and the set of IDs equal the baseline query's. Repeat cold on shared dev.

## Open decisions

1. **Name.** `luc:fields` is proposed. `luc:values` and `luc:stored` were considered;
   `luc:fields` matches the `returnFields` argument.
2. **All stored fields.** Whether `returnFields` should accept `["*"]`. It would load
   every stored field of every hit, which is cheap per hit but unbounded in memory for a
   large window.
3. **The two related defects.** Whether they are fixed before this lands. The negative
   `limit` defect affects any client that follows the documentation.
