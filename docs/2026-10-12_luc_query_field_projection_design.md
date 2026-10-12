---
title: "Reading less per hit, and luc:fields for identifier queries"
date: "2026-10-12"
status: "Built"
---

# Reading Less per Hit, and luc:fields for Identifier Queries

This design reduces what `luc:query` reads for each hit, and adds `luc:fields`, a property
function that returns field values for every hit straight from the Lucene index.

The usual query binds `?entity`, sometimes uses `luc:match`, and joins to the graph for
everything else. That path should read as little as possible, and most of this design
serves it: `luc:query` reads the entity IRI only when the query binds `?entity`, match
details are computed only when `luc:match` is used, and `luc:match` finds a hit with one
lookup instead of a scan.

A query that needs only an identifier for every hit is the exception. `luc:fields` serves
it with no graph join, reading doc values where they hold the original value.

Claims about current behaviour were checked against `f01fa1eb3c`.

## The identifier requirement

The Discovery Portal needs about 300,000 identifiers from one search with filters, which
it passes to a stored procedure (requested 2026-10-09; 10,000 is not enough and a bulk
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
latency.

The identifiers are modelled as `schema:identifier` blank nodes with `schema:propertyID`
naming the kind of identifier. Indexing one kind as its own field needs `sh:node`
occurrence filters, designed separately in
[2026-10-12_shacl_node_occurrence_filters_design.md](2026-10-12_shacl_node_occurrence_filters_design.md).

## What happens per hit today

| Step | Today | Cost |
|---|---|---|
| Entity IRI | `searchWithHitIds` calls `storedFields.document(sd.doc)` and takes the entity field | Decompresses the stored-field block and decodes every stored field of the hit (labels, titles, identifiers) to read one. A warm measurement on 2026-10-12 put `luc:query` at about 13 µs per hit, most of it here |
| Match details | For any query string other than `*`, `computeFieldMatches` runs on every hit, whether or not the query uses `luc:match` | A second whole-document decode per hit, plus `Weight.matches` |
| `luc:match` and `luc:nestedMatch` join | Each incoming `?hit` scans every cached hit for an equal ID | Quadratic: 400 comparisons for a page of 20, 9 × 10¹⁰ for 300,000 hits |
| Which search a `?hit` belongs to | `findSearchExecution` returns the first `SearchExecution` in the context, and every search labels its hits `hit0`, `hit1`, … | With two `luc:query` calls in one query, `?hit` can resolve against the wrong search |
| Negative `limit` | `parseLimit` keeps `-1`, then `ShaclTextQueryPF.exec` applies `args.limit > 0 ? args.limit : 10` | [02-sparql-api.md](02-sparql-api.md#paging) says negative means unlimited; the client gets 10 hits |

## Part 1: the hit path, for every query

### Held searcher

A Lucene doc ID is valid only against the reader it came from. Today `searchWithHitIds`
releases its `IndexSearcher` before returning, which forces everything to be read inside
that call.

`SearchExecution` instead keeps the searcher it searched with, holding one reference from
the `SearcherManager`, and releases it when `luc:query`'s iterator closes. Work that
depends on the hits (entity IRIs, match details, field values) can then run later, and
only when a query asks for it.

A plan can evaluate a dependent property function after `luc:query`'s iterator has
closed, for example when `luc:query` is drained into one side of a hash join first. The
function then acquires a searcher and compares its reader with the one the search used.
The `SearcherManager` hands back the same reader until a commit refreshes it
(`ShaclTextIndexLucene.commit` calls `maybeRefresh`), so with no write in between the doc
IDs are still valid. If the reader has changed, the function raises a query error rather
than attach values from a different index state.

While a searcher is held, segments merged away in the meantime keep their files until it
is released, which is bounded by one query's duration. When a `SearchExecution` re-runs
its search for a larger window, it releases the old searcher, takes the new one, and
discards everything read against the old one.

### Entity IRI only when bound

`luc:query` reads the entity IRI only when its subject list has an `?entity` slot, or a
constant there to filter on. `(?hit) luc:query (…)` reads none. `SearchHit.getEntityNode`
returns null for a hit read without it. Its one other caller, the over-limit warning in
`computeNestedMatches`, logs the Lucene doc ID instead.

When the IRI is needed, it is read in doc-ID order: hits grouped by segment, sorted by doc
ID, read, and the values put back by hit. Two ways to read it:

1. **A one-field `StoredFieldVisitor`** replacing `storedFields.document`. It decodes only
   the entity field but still decompresses the block. No reindex.
2. **A doc value for the entity IRI**, written by `docFromMapping` beside the stored copy.
   A column read with no stored-field access. Needs a reindex and writes every IRI a
   second time; IRIs are unique per document, so the column gets no deduplication.
   `BinaryDocValues` suits unique values better than `SortedDocValues`, whose term
   dictionary gains nothing from them; the benchmark compares the two.

The visitor ships. The doc value ships if the benchmark shows it clearly faster than the
visitor for large windows. A segment written before the doc value existed has no such
field, so the reader checks `FieldInfos` per segment and falls back to the visitor there.

### Match details only when `luc:match` is used

`luc:query` no longer calls `computeFieldMatches`. `SearchExecution` keeps the text query,
and the first `luc:match` call computes match details for every cached hit in one
doc-ID-ordered pass against the held searcher, then answers later calls from the cache.
A query without `luc:match` pays nothing for matches.

The values `luc:match` binds come from the value reader in
[Part 2](#part-2-one-value-reader-and-the-storage-rule), which decodes only the matched
fields.

### Hit lookup

`SearchExecution` holds a `Map<Node, SearchHit>` built with the hit list, so each incoming
`?hit` costs one lookup. Hit labels carry a per-search prefix, and `luc:match`,
`luc:nestedMatch` and `luc:fields` find the search by hit ID through a map shared across
the query, not by taking the first `SearchExecution` found.

`luc:match` and `luc:nestedMatch` currently reject a constant in their field slot
(`TextMatchPF` raises "Field must be a variable"). Both accept one after this change,
behaving as a filter on that field, as `luc:fields` does.

### Negative `limit`

A negative `limit` means unlimited, as documented. `ShaclTextQueryPF.exec` stops mapping
it to 10.

### Part 1 as implemented

Where the implementation differs from the text above, and why:

| Topic | Design | Implemented | Reason |
|---|---|---|---|
| Hit lookup | A `Map<Node, SearchHit>` per search | No map. The label is `s<search>h<rank>`; the rank indexes the cached window | Same single lookup with no per-hit map entry, which is 300,000 entries at the identifier window |
| Window re-run | Release the old searcher, take the new one | Re-run against the held searcher if one is held. Otherwise acquire the current one, and raise if its reader is not the one the earlier search used | Pages of one query then see one index state, and a hit label already returned never names a different document. Values read for the old hits are discarded either way, because the re-run creates new `SearchHit` objects |
| Child records | Not changed; `computeNestedMatches` stays in the search | Computed on the first `luc:nestedMatch` call, like match details | It runs one block-join query per hit, which a query with a nested filter and no `luc:nestedMatch` paid for nothing |
| Batch for lazy reads | Every cached hit | The requested hit plus every hit `luc:query` has returned; every cached hit when `?hit` is unbound | Page *n* caches `offset + limit` hits but returns `limit`; the skipped hits are never joined |
| `limit` 0 | Not covered | Still the default page of 10, now documented | 0 was undocumented and mapped to 10; changing it was not part of this design |
| Unlimited window | Not covered | When the window is at least the reader's `maxDoc`, the hit queue is sized by `IndexSearcher.count` | Lucene sizes its queue at the requested count capped at `maxDoc`, which counts child documents, so an unlimited search would otherwise allocate a queue the size of the index |
| Entity IRI doc value | Ships if the benchmark favours it | Ships as `BinaryDocValues`, implemented with Part 2; see [Parts 2 and 3 as implemented](#parts-2-and-3-as-implemented) | Faster than the visitor at large windows; see [Entity IRI doc value](#entity-iri-doc-value) |
| Read counting | A hook on the value reader | `HitReadObserver` on `ShaclTextIndexLucene` | The value reader is Part 2. The hook reports stored-field decodes and match computations |

`HitReader.inDocOrder` is the doc-ID-ordered primitive the value reader and `luc:fields`
can build on: it calls `startLeaf` once per segment (where doc-values iterators are
opened) and `read` per hit in doc-ID order (where `advanceExact` and a restricted
`StoredFieldVisitor` run). `SearchExecution.readAgainstSearch` runs such a read against
the held searcher, or against a newly acquired one after the reader check, so a cache
keyed by field on `SearchExecution` and filled through it gets the late-read rule for
free.

A SPARQL query can make a dependent read after `luc:query` has closed. One form that
does is `luc:match` after a sorted subquery over `luc:query`; the sort drains the
subquery first. A separate group (`{ luc:query } { luc:match }`) is evaluated with the
`luc:query` iterator still open.

## Part 2: one value reader, and the storage rule

### `idx:stored` means returnable

`idx:stored true` means the field's value can be returned by `luc:match`,
`luc:nestedMatch` and `luc:fields`. Where the value is kept is decided by the indexer, not
the configuration. `idx:stored false` means it cannot be returned, whether or not a doc
value exists. No new configuration term is needed.

### Where values are kept

A field's doc value holds its original value when all of these hold:

- `idx:fieldType` is `KEYWORD`
- `idx:sortable true`, so a `SortedDocValuesField` (single-valued) or
  `SortedSetDocValuesField` (multi-valued) is written under the field's own name
- no `idx:normalizer`, so the doc value is the raw lexical form rather than a sort key
- `idx:storeLiteralMetadata false`, so there is no stored datatype or language to pair
  with the value

For such a field with `idx:stored true`, the indexer writes the doc value only, so the
value is on disk once. Every other field with `idx:stored true` gets a stored copy,
because its doc value, if any, does not hold the original value:

| Field | Doc values written | Holds the original value? |
|---|---|---|
| `TEXT` | none | — |
| `KEYWORD`, `idx:sortable`, `idx:normalizer` set | sort doc value | No: the normalized sort key |
| `KEYWORD`, `idx:facetable` only | `SortedSetDocValuesFacetField` | Encoded in the shared facet field, not under the field's name |
| `KEYWORD` with `idx:storeLiteralMetadata` | as above | The language tag and datatype live in stored twins |
| `INT`, `LONG`, `DOUBLE`, `idx:facetable` or `idx:sortable` | `SortedNumericDocValuesField` | The number, not the lexical form or datatype: `"007"^^xsd:integer` comes back as `7` |
| `TEMPORAL`, `idx:facetable` or `idx:sortable` | `SortedNumericDocValuesField` on an epoch twin | No: precision and timezone are not kept |

The rule applies to root and nested fields alike. An index written before this change
keeps stored copies of eligible fields; the reader prefers the doc value, so the copy is
unused until the next reindex.

### The value reader

One class reads values for all three property functions. For each field it knows, from
the mapping, whether the field is read from doc values or stored fields. It reads a set of
fields for a set of doc IDs in doc-ID order, using one restricted `StoredFieldVisitor` for
the stored fields and forward-only `advanceExact` for the doc values.

Stored values go through the existing `fieldValueToNode`, which applies
`idx:storeLiteralMetadata` through `LiteralFieldSupport.reconstructNode` and aligns each
value with its datatype and language twin by index. A doc value goes through the same
`reconstructNode` with no datatype or language, which is what `fieldValueToNode` passes
for an eligible field, so both sources give the same term. For `KEYWORD` that is an IRI
when `looksLikeUri` accepts the value and a plain string otherwise.

A multi-valued field read from `SortedSetDocValues` returns each distinct value once, in
byte order. Stored fields return values in indexing order, duplicates included. RDF
triples form a set, so the doc-value result matches the source data.

## Part 3: `luc:fields`

```sparql
(?hit ?field ?value) luc:fields ()
```

| Slot | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Join key from `luc:query` |
| `?field` | variable or field IRI | A variable returns every returnable root field; an IRI returns that field only |
| `?value` | variable | One value |

The object is always `()`. `luc:query` is unchanged: it keeps its seven arguments, and
`luc:fields` alone decides which fields are read. On its first call for a field it reads
that field for every cached hit in one pass and caches the values by hit; later calls are
map lookups. A variable field slot reads every returnable root field in one pass.

`luc:fields` behaves like a triple pattern with `?hit` as subject: one row per value, a
variable in the field slot ranges over fields, a constant selects one. One pattern per
field gives one column per field:

```sparql
PREFIX luc: <urn:jena:lucene:index#>
PREFIX field: <https://example.org/field#>

SELECT ?id WHERE {
  (?hit) luc:query ("default" "default" "<text>" '<CQL2-JSON>' "" 300000 0) .
  (?hit field:collarId ?id) luc:fields () .
}
```

Binding only `?hit` lets `luc:query` skip the entity IRI. With `field:collarId` a
sortable `KEYWORD`, the query reads no stored field at all.

### With `luc:match`

Both join on `?hit`, so a hit's rows are every combination of its `luc:match` rows and its
`luc:fields` rows, as with two triple patterns on one subject. Neither changes what the
other returns. Use different variable names: `(?hit ?field ?value)` in both also joins on
`?field` and `?value`, keeping only pairs both produce.

### Errors

Each raises a query error naming the field:

- a field IRI that is not configured (as for `fieldSpec`)
- a field with `idx:stored false`
- a field inside an `idx:nested` block, whose values live on child documents;
  `luc:nestedMatch` returns those
- a read after the index was refreshed under the search (see [Held searcher](#held-searcher))

`luc:fields` learns which index it reads only when `luc:query` has resolved its selector,
so these are raised on the first call, not when the query is parsed.

### Alternatives rejected

| Alternative | Why rejected |
|---|---|
| An eighth `luc:query` argument listing the fields to read, which an earlier draft proposed | It existed only so values could be read before `luc:query` released its searcher. The held searcher removes that, and the argument named every field twice |
| Values in extra `luc:query` subject slots, e.g. `(?hit ?entity ?score ?totalHits ?rank ?collarId)` | Positional: `?collarId` needs the four slots before it filled, its variables must line up with a field list elsewhere, and a multi-valued field has no single value to bind |
| The field IRI in the object, `(?hit ?value) luc:fields (field:collarId)` | "Every field" then needs a second form, and the object invites `(?hit ?id ?name) luc:fields (f1 f2)`, which brings back positional alignment and cross-multiplies multi-valued fields inside one call. `luc:config`, `luc:match` and `luc:nestedMatch` put the field in the subject |
| A separate `idx:returnable` flag | It would describe the same thing as `idx:stored`, and its combinations with `idx:stored` would need rejecting |
| Extend `luc:match` | It means "fields the text query matched" |

## Parts 2 and 3 as implemented

| Topic | Design | Implemented | Reason |
|---|---|---|---|
| Value reader | One class | `FieldValueReader`, used through `HitReader.inDocOrder` by `computeFieldMatches`, `computeNestedMatches` and `readFieldValues` | — |
| Doc value or stored copy | Prefer the doc value | Decided per segment from `FieldInfo`: an eligible field with `SORTED` or `SORTED_SET` doc values is read from them, otherwise from stored fields; a segment with no `FieldInfo` for the field is not read at all | A segment written before the field was sortable has a stored copy and no doc value; a segment where no document has the field costs nothing |
| `luc:match` value on a multi-valued doc-value field | Not covered | `selectStoredValue` runs over the doc values; with no matching value it returns the first in byte order rather than the first indexed | Doc values do not keep indexing order. Tested with the matched value last in byte order |
| `luc:fields` batch | Every cached hit | The requested hit plus every hit `luc:query` has returned; every cached hit when `?hit` is unbound | As Part 1's lazy reads: page *n* caches `offset + limit` hits but returns `limit` |
| `luc:fields` cache | By hit | Per field, a list indexed by rank, cleared when the search re-runs | No per-hit map at 300,000 hits |
| Value slot | Variable | Variable or constant, in `luc:fields` and now `luc:match` | ARQ substitutes `?field` and `?value` from the earlier pattern, so shared variables reach the later one as constants. `luc:match` used to reject a constant value |
| Root field | Not inside `idx:nested` | Has a root occurrence in some shape (`ShaclIndexMapping.getRootFields`); a field bound both at root and in a nested block is returnable | Its root values are on the entity's document |
| `LATLON` | Not covered | Left out of a variable field slot; a constant gives no rows | A stored geometry has no term `reconstructNode` can build |
| Entity IRI doc value | A doc value of the entity field, `BinaryDocValues` or `SortedDocValues` chosen by benchmark | A `BinaryDocValuesField` under a separate field, `<entity field>__docValue`, on parent documents. The reader falls back to the stored entity field per segment and per document where it is absent | Lucene 10 requires every document with a field to give it the same doc-value type, and child documents carry the entity field without one. The separate field also lets an old index take new documents with the doc value, so old and new segments mix. `BinaryDocValues` won the [benchmark](#entity-iri-doc-value) |
| Read counting | — | `HitReadObserver.docValuesRead` beside `storedFieldsRead` | Tests check that a doc-value field causes no stored read |

Indexes written before Part 2 work unchanged: eligible fields have both a stored copy and
the doc value, and the reader uses the doc value. Their old segments have no entity IRI
doc value and are read through the visitor; documents written after the upgrade get it.

An `INT`, `LONG` or `DOUBLE` field without `idx:storeLiteralMetadata` is stored as a
number, so `"007"^^xsd:integer` comes back as `"7"^^xsd:integer` from stored fields as
well; this was already so. With `idx:storeLiteralMetadata` the lexical form is stored and
returned, which is what the Part 2 test checks.

## Shared execution

`SearchExecution.buildKey` is unchanged, so `luc:facet` still shares the search with
`luc:query`. A `SearchExecution` records whether entity IRIs were read. A later
`luc:query` with the same key that binds `?entity` after one that did not reads them in a
further pass over the held searcher, without re-running the search.

## Memory

The hit list is already materialised in full: `SearchExecution` caches up to
`offset + limit` `SearchHit` objects. A field read by `luc:fields` adds one cached `Node`
per value; for one short identifier on 300,000 hits that is an estimated 30–60 MB, not
measured. The hit-ID map adds one entry per hit.

## Out of scope

- Returning nested fields through `luc:fields`. `luc:nestedMatch` returns the children a
  filter selected.
- `sh:targetClass` subclass semantics, which remain in
  [2026-10-09_shacl_class_semantics_and_jena_shacl_reuse.md](2026-10-09_shacl_class_semantics_and_jena_shacl_reuse.md).
- TDB2-side changes and anything on the client side.

## Implementation

| Change | Where |
|---|---|
| Held searcher, release on iterator close, reader check for late reads | `SearchExecution`, `ShaclTextQueryPF`, `ShaclTextIndexLucene` |
| Entity IRI only when the subject has an `?entity` slot; doc-ID-ordered visitor read; nullable `getEntityNode` | `ShaclTextQueryPF`, `ShaclTextIndexLucene.searchWithHitIds`, `SearchHit`, `computeNestedMatches` |
| Entity IRI doc value with per-segment fallback | `docFromMapping`, `readEntityNodes` |
| Lazy match computation | `SearchExecution`, `TextMatchPF`, `ShaclTextIndexLucene.computeFieldMatches` |
| Hit-ID map, per-search labels, query-wide lookup; constant field slot | `SearchExecution`, `SearchHit`, `TextMatchPF`, `TextNestedMatchPF` |
| Negative `limit` | `ShaclTextQueryPF.exec` |
| Doc-value eligibility, decided once per field | `ShaclIndexMapping.FieldDef` |
| Doc value only for eligible stored fields | `ShaclTextIndexLucene.addFieldToDoc`, `addNodeFieldToDoc` |
| Value reader | New class in `org.apache.jena.query.text`, used by `luc:match`, `luc:nestedMatch`, `luc:fields` |
| `luc:fields` | New `TextFieldsPF`; `IndexVocab.pfFields`; registered in `TextQuery` |
| Docs | [02-sparql-api.md](02-sparql-api.md): `luc:fields`, constant field slots, negative `limit`, binding only `?hit`. [03-configuration.md](03-configuration.md#field-properties): `idx:stored` as returnable, the storage rule. [04-architecture.md](04-architecture.md): held searcher, value reader. [README.md](README.md) row |

## Tests

Written first and seen to fail. New classes are JUnit 5 and must be added to `TS_Text`'s
`@SelectClasses`; a class missing from that list never runs.

Part 1:

- `(?hit) luc:query` reads no stored field; `(?hit ?entity)` reads the entity field only.
  Counted through a test hook on the value reader, since a skipped read has no other
  observable result.
- `?entity` binds the same IRI as before, under relevance and under a sort, and a constant
  `?entity` still filters.
- With the entity doc value: the same IRIs, and a pre-existing segment without the doc
  value falls back to stored fields.
- A query without `luc:match` computes no matches (counted); with it, the same rows as
  before for the existing `luc:match` tests.
- `luc:match` and `luc:nestedMatch` at 300,000 hits finish in time linear in hits: a
  synthetic index sized so the quadratic scan fails a generous timeout.
- Two `luc:query` calls with different searches in one query: each `?hit` resolves to its
  own search.
- A constant field slot in `luc:match` filters to that field.
- A negative `limit` returns every hit.
- The searcher is released when a query finishes, is cancelled, or is abandoned part-way:
  the `SearcherManager` reference count returns to its starting value.
- A commit between the search and a late dependent read raises; with no commit, the late
  read succeeds.
- A window re-run discards values read against the old searcher.

Part 2:

- An eligible field with `idx:stored true` writes a doc value and no stored copy;
  `idx:stored false` writes the doc value and is not returnable.
- For each eligible shape (single- and multi-valued, IRI and string values), the terms
  equal those the old stored-copy path returned, compared as sets.
- Each non-eligible row of the table above returns its original term from stored fields:
  normalized `KEYWORD`, facet-only `KEYWORD`, language-tagged `KEYWORD`, numeric with a
  non-canonical lexical form, `TEMPORAL` with a timezone.
- An index with stored copies of eligible fields (written by the old rule) still returns
  them.

Part 3:

- The identifier shape: a single-valued sortable `KEYWORD` with a filter-only search
  returns one row per hit, in rank order, reading no stored field.
- A variable field slot returns every returnable root field; a constant selects one; two
  constant patterns give one row per hit with both columns.
- With `luc:match`: distinct variables give the per-hit product; shared `?field ?value`
  give only common pairs.
- Each error in [Errors](#errors).

## Performance

Part 1 changes the per-hit path of every `luc:query`, so it is measured for regressions in
the usual queries as well as for the identifier gain.

### Synthetic benchmark

A JMH benchmark in `jena-text`'s test sources, run on demand and not by `TS_Text`. It is in
`jena-text`, not `jena-benchmarks`, because `jena-benchmarks` is upstream's and its module
lists conflict on every upstream sync.

It builds a synthetic index of 500,000 entities, each with about ten stored fields
including two `TEXT` fields of a few hundred characters, and nested identifier children in
the `schema:identifier` shape. Scenarios:

| Scenario | Guards |
|---|---|
| Page of 20, text query, binds `?entity` | The usual UI query |
| Page of 20, text query, with `luc:match` | Lazy matches, hit lookup |
| `luc:query` with `luc:facet`, same search | Shared execution |
| 300,000 hits, filter only, binds `?entity` | Large windows needing the IRI; visitor versus entity doc value |
| 300,000 hits, text query, no `luc:match` | Matches no longer computed |
| 300,000 hits, with `luc:match` | Linear hit lookup |
| 300,000 hits, `(?hit)`, identifier through `luc:fields` | The identifier query |

The benchmark is committed first and run at that commit for the baseline. The first five
scenarios exist at the baseline and must be no more than 5% slower at the median, warm, on
the same machine. The others are reported.

#### Results

Both runs used the same machine, pinned to 8 of 12 cores, each with an index built by its
own code. The original was benchmark commit `ae310d3792`; the final run was the built
design, Parts 1 to 3 with the entity IRI doc value. JMH ran in sample-time mode, with
3 × 10 s warmup, 5 × 10 s measurement and one fork. Times are in ms per query.

| Scenario | Original p50 | Final p50 | Change | Original p90 | Final p90 | Change |
|---|---|---|---|---|---|---|
| pageText | 0.656 | 0.250 | -62% | 0.702 | 0.260 | -63% |
| pageTextWithMatch | 0.685 | 0.503 | -27% | 0.726 | 0.543 | -25% |
| pageTextWithFacet | 1.090 | 0.764 | -30% | 1.149 | 0.818 | -29% |
| largeFilterOnly | 2,429 | 89.4 | -96% | 2,509 | 92.0 | -96% |
| largeTextNoMatch | 6,539 | 94.1 | -99% | 6,675 | 96.3 | -99% |
| largeTextWithMatch, 20,000 hits | 4,035 | 249 | -94% | 4,080 | 263 | -94% |
| largeIdentifierFields | — | 173 | — | — | 177 | — |

No scenario is slower. The large windows that bind `?entity` read it from the doc value
rather than stored fields, and `largeTextNoMatch` no longer computes match details.
`largeTextWithMatch` no longer scans every cached hit on each `luc:match` call; with
Part 1 alone it took 5.6 s at p50 across 300,000 hits, where probes of the original up to
40,000 hits grew quadratically, projecting to about 14 minutes. `luc:fields` is new, so
the identifier scenario has no original.

The index grew from 323.5 MB to 342.0 MB (6%) and the index step from 91.3 s to 94.0 s.
Eligible fields lose their stored copy, but the entity IRI doc value, one unique IRI per
entity, adds more than that saves: an index built without it was 320.5 MB.

#### Entity IRI doc value

The three ways to read the entity IRI were compared on the same synthetic data, each in an
index built with only that option, pinned to 8 cores. Times are p50 in ms per query.

| Read | largeFilterOnly | pageText | largeIdentifierFields |
|---|---|---|---|
| Stored entity field, one-field visitor | 2,378 | 0.397 | 175 |
| `SortedDocValues` | 93.6 | 0.251 | 165.7 |
| `BinaryDocValues` | 85.7 | 0.249 | 160.7 |

The doc value cuts a 300,000-hit window needing the IRI from 2.4 s to under 0.1 s, and
`BinaryDocValues` is slightly ahead of `SortedDocValues`, so it ships. The switch used to
choose between them for the benchmark is removed.

### Production-scale check

Part 1 does not change the index format, so it is measured against a copy of an existing
production-scale index (a 210 GB TDB2 store and its 20 GB Lucene index), old code against
new, warm and with the page cache evicted. Parts 2 and 3 change what is written, so they
are measured on a subset of the same data reindexed into a separate directory. The
identifier query is compared with the triple-pattern baseline above for row count, the set
of identifiers, time and bytes read.

This check was not run for the built design; the figures above are synthetic only.

## Open decisions

The entity IRI doc value was decided by the benchmark: `BinaryDocValues`, see
[Entity IRI doc value](#entity-iri-doc-value).

1. **Regression threshold**: 5% at the median is proposed; noise on the machine used may
   call for a wider band or more iterations.
2. **Numeric doc values**: `INT` and `LONG` could be eligible when the lexical form is
   canonical, but nothing records whether it was. Stored-only until a numeric identifier
   needs it.
