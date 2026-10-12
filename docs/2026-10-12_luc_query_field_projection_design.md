---
title: "Field projection for luc:query hits"
date: "2026-10-12"
status: "Designed; nothing implemented"
---

# Field Projection for luc:query Hits

This design lets a SPARQL query return field values for every `luc:query` hit straight
from the Lucene index, so that an identifier for each of 300,000 hits needs no join back to
the RDF store. It adds a `luc:fields` property function that joins to `luc:query` through
`?hit`; `luc:query`'s arguments are unchanged. Values are read from doc values where the
doc value is the original value, and from stored fields otherwise. `luc:query` stops
loading each hit's whole stored document, and reads the entity IRI only when the query
binds `?entity`.

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

`StoredFields.document` decompresses and decodes every stored field of the hit (labels,
titles, identifiers and so on) to read one of them. A warm measurement on 2026-10-12 put
`luc:query` at about 13 µs per hit, most of it in this load; at 300,000 hits that is
about 4 s of the 9.4 s warm baseline.

Two things follow:

- A stored CollarId costs almost nothing extra with the current code, because its value
  is already in the document `luc:query` has just decoded.
- Doc values are cheaper again, but only if `luc:query` also stops loading the whole
  document. Otherwise the stored-field read stays and the doc-value read is added to it.

This design takes the second route.

## API

### `luc:fields`

```sparql
(?hit ?field ?value) luc:fields ()
```

| Slot | Type | Meaning |
|---|---|---|
| `?hit` | blank node | Join key from `luc:query` |
| `?field` | variable or field IRI | A variable returns every readable field; an IRI returns that field only |
| `?value` | variable | One value |

The object is always `()`. `luc:query` is unchanged: it keeps its seven arguments, and
the fields to read are decided by `luc:fields` alone.

`luc:fields` returns one row per value, and behaves like a triple pattern with `?hit` as
subject: a variable in the field slot ranges over fields, a constant selects one. One
pattern per field gives one column per field:

```sparql
PREFIX luc: <urn:jena:lucene:index#>
PREFIX field: <https://example.org/field#>

SELECT ?id ?name WHERE {
  (?hit) luc:query ("default" "default" "<text>" '<CQL2-JSON>' "" 300000 0) .
  (?hit field:collarId ?id) luc:fields () .
  (?hit field:name ?name) luc:fields () .
}
```

A multi-valued field gives one row per value, as a triple pattern over a multi-valued
predicate would. Rows come in `luc:query` order, because ARQ evaluates `luc:fields` once
per incoming binding.

Binding only `?hit` in `luc:query`, as above, matters for cost: it is what lets
`luc:query` skip the entity IRI (see [Read plan](#read-plan)).

### Using it with `luc:match`

Both join on `?hit`, so a hit's rows are every combination of its `luc:match` rows and its
`luc:fields` rows, as with two triple patterns on one subject. Two matched fields and one
constant single-valued `luc:fields` pattern give two rows per hit. Neither function
changes what the other returns.

Give the two different variable names. `(?hit ?field ?value) luc:match ()` with
`(?hit ?field ?value) luc:fields ()` also joins on `?field` and `?value`, and keeps only
rows where both produce the same pair.

### Errors

Each of the following raises a query error naming the field, rather than returning no
rows:

- an IRI in the field slot that is not a configured field (as for `fieldSpec`)
- a field that is neither stored nor doc-value eligible (see
  [Where values come from](#where-values-come-from)), which has no value to return
- a field inside an `idx:nested` block (see [Out of scope](#out-of-scope))
- `luc:fields` reading after the index has been refreshed under the search (see
  [Searcher lifetime](#searcher-lifetime))

This follows the existing rule in [02-sparql-api.md](02-sparql-api.md#a-filter-is-applied-or-the-query-is-refused):
a request the index cannot honour is refused. With a variable field slot, fields that have
no readable value are skipped rather than raised, since none was named.

### Alternatives rejected

| Alternative | Why rejected |
|---|---|
| An eighth `luc:query` argument listing the fields to read, which an earlier draft of this design proposed | It only existed so values could be read before `luc:query` released its searcher. Holding the searcher removes that reason, and the argument made every field IRI appear twice. It would save a second decompression of stored blocks in one case (see [Cost of reading separately](#cost-of-reading-separately)) |
| Bind values in extra `luc:query` subject slots, e.g. `(?hit ?entity ?score ?totalHits ?rank ?collarId)` | Subject slots are positional, so `?collarId` needs the four slots before it filled. The variables must line up with a field list elsewhere. A multi-valued field has no single value to bind |
| The field IRI in the object, `(?hit ?value) luc:fields (field:collarId)` | Correlation works either way, since `?hit` stays in the subject. But "every field" then needs a second form, and the object invites `(?hit ?id ?name) luc:fields (field:collarId field:name)`, which brings back positional alignment and cross-multiplies multi-valued fields inside one call. `luc:config`, `luc:match` and `luc:nestedMatch` already put the field in the subject |
| Extend `luc:match` | It means "fields the text query matched". Returning unmatched fields from it changes that meaning for existing queries |

## Where values come from

Each field is read from one of two sources, chosen once per field from its configuration.

### Doc values, where they hold the original value

A field is doc-value eligible when all of these hold:

- `idx:fieldType` is `KEYWORD`
- `idx:sortable true`, so a `SortedDocValuesField` (single-valued) or
  `SortedSetDocValuesField` (multi-valued) is written under the field's own name
- no `idx:normalizer`, so the doc value is the raw lexical form rather than a sort key
- `idx:storeLiteralMetadata false`, so there is no stored datatype or language to pair
  with the value

A scalar identifier such as CollarId fits this shape. It needs `idx:sortable true` in the
shape, and a reindex if it is not sortable today. The value is then read from a column in
doc-ID order, and no stored field is touched. `idx:stored` can be `false` for such a
field: the doc value is the only copy needed, though `luc:match` reads stored fields only
and then has nothing to show for it.

A multi-valued field read from `SortedSetDocValues` returns each distinct value once, in
byte order. Stored fields return values in indexing order, duplicates included. RDF
triples form a set, so the doc-value result matches the source data, but the two sources
can return the same values in a different row order, and the tests pin that.

### Stored fields, otherwise

Every other field is read from stored fields. Doc values elsewhere in the index do not
hold the original value:

| Field | Doc values written | Holds the original value? |
|---|---|---|
| `KEYWORD`, `idx:sortable`, `idx:normalizer` set | `SortedDocValuesField` or `SortedSetDocValuesField` | No: the normalized sort key |
| `KEYWORD`, `idx:facetable` only | `SortedSetDocValuesFacetField` | Encoded in the shared facet field, not under the field's name |
| `INT`, `LONG`, `DOUBLE`, `idx:facetable` or `idx:sortable` | `SortedNumericDocValuesField` | The number, but not the lexical form or datatype: `"007"^^xsd:integer` comes back as `7` |
| `TEMPORAL`, `idx:facetable` or `idx:sortable` | `SortedNumericDocValuesField` on an epoch twin | No: precision and timezone are not kept |
| Any type, neither flag | none | — |

`idx:stored` defaults to `true` ([03-configuration.md](03-configuration.md#field-properties)),
so most configured fields can be projected from stored fields without reindexing.

### Turning values into terms

Stored values go through the existing `fieldValueToNode`, which applies
`idx:storeLiteralMetadata` through `LiteralFieldSupport.reconstructNode` and aligns each
value with its datatype and language twin by index. A doc value goes through the same
`reconstructNode` with no datatype or language, which is what `fieldValueToNode` passes
for an eligible field, so both sources give the same term. For `KEYWORD` that is an IRI
when `looksLikeUri` accepts the value and a plain string otherwise. Whether either equals
the source triple's object is governed by
[2026-04-09_date_literal_round_trip_design.md](2026-04-09_date_literal_round_trip_design.md).

## Read plan

`luc:query` reads the entity IRI only when its subject list has an `?entity` slot, and
reads it with a `StoredFieldVisitor` that accepts the entity field alone, replacing
`storedFields.document(sd.doc)`. The visitor still decompresses the block the document
sits in, so the saving is in decoding the other fields, not in I/O.

Under that rule, `(?hit) luc:query (…)` reads no stored fields. `SearchHit.getEntityNode`
returns null for a hit read without it. Its one other caller, the over-limit warning in
`computeNestedMatches`, logs the Lucene doc ID instead.

`luc:fields`, on its first call for a field, reads that field for every cached hit of the
search in one pass and caches the values by hit. Later calls for the same field are map
lookups. A variable field slot reads every readable field in one pass: the eligible ones
from doc values, the rest with one visitor over stored fields.

Every pass runs in doc-ID order. The hits are grouped by segment, sorted by doc ID within
each one, read in that order, and the values put back by hit. Doc values need
forward-only access for `advanceExact`. For stored fields, doc-ID order means each
compressed block is decompressed once for all the hits in it, instead of once per visit
in rank order. How much that saves cold has not been measured.

### The entity IRI as a doc value

The entity IRI is written stored and indexed, with no doc value (`docFromMapping`), so
any query that binds `?entity` still pays one stored-field read per hit. Adding a doc
value for it would make that a column read like CollarId's.

This step ships only if it measures well. It needs a reindex, and it writes every IRI a
second time. IRIs are unique per document, so the column gets no deduplication. It helps
large result sets that bind `?entity`, such as exports and `CONSTRUCT` over many hits. A
page of 20 hits will not notice it, and the Discovery Portal query does not bind
`?entity` at all. The [performance plan](#performance) measures it against the visitor
read; if it is not clearly faster for large windows, it is left out.

### Cost of reading separately

Reading fields in `luc:fields` rather than inside `luc:query`'s search costs extra in one
case only. When a query binds `?entity` and also reads a stored-only field, the entity
pass and the field pass each decompress the same stored blocks. The second pass hits the
page cache, so it costs CPU, not I/O; it has not been measured. Doc-value fields, and
queries that do not bind `?entity`, read exactly what a combined pass would. The entity
doc value, if it ships, removes the case.

## Searcher lifetime

A Lucene doc ID is valid only against the reader it came from. Today `searchWithHitIds`
releases its `IndexSearcher` before returning, which is why the comment on
`computeNestedMatches` insists everything be read inside that call.

`SearchExecution` instead keeps the searcher it searched with, holding one reference from
the `SearcherManager`, and releases it when `luc:query`'s iterator closes. In a
nested-loop plan, `luc:fields` runs its pass on the first incoming hit, while
`luc:query`'s iterator is still open.

A plan can evaluate `luc:fields` after that iterator has closed: for example when
`luc:query` sits in one group of a hash join and is fully drained first. `luc:fields` then
acquires a searcher and compares its reader with the one the search used. The
`SearcherManager` hands back the same reader until a commit refreshes it
(`ShaclTextIndexLucene.commit` calls `maybeRefresh`), so with no write in between the doc
IDs are still valid and the read goes ahead. If the reader has changed, `luc:fields`
raises a query error rather than attach values from a different index state to the hits.

While a searcher is held, segments merged away in the meantime keep their files until it
is released. For one query that is the usual cost of a Lucene reader and is bounded by the
query's duration.

When a `SearchExecution` re-runs its search for a larger window, it releases the old
searcher, takes the new one, and discards cached field values, since the doc IDs they were
read against may no longer hold.

## Shared execution

`SearchExecution.buildKey` is unchanged, so `luc:facet` still shares the search with
`luc:query`. A `SearchExecution` records whether the entity IRI was read. If a later
`luc:query` with the same key binds `?entity` and the first did not, the IRIs are read in a
further pass over the held searcher, rather than by re-running the search.

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
`luc:nestedMatch` onto them is a small follow-up and removes the same defects there. Until
then, a 300,000-hit query that also uses `luc:match` is slow because of `luc:match`.

## Memory

The hit list is already materialised in full: `SearchExecution` caches up to
`offset + limit` `SearchHit` objects. Projection adds one cached `Node` per value read.
For one short identifier field on 300,000 hits that is an estimated 30–60 MB of heap, not
measured. Streaming hits instead of caching them would change how `luc:facet`,
`luc:match` and `?totalHits` share state, and this requirement does not need it.

## Related defects found while designing

These are outside this design and need their own failing test and fix:

1. **A negative `limit` returns 10 hits.** [02-sparql-api.md](02-sparql-api.md#paging)
   says a negative `limit` means unlimited. `parseLimit` keeps `-1`, but
   `ShaclTextQueryPF.exec` then applies `args.limit > 0 ? args.limit : 10`. A client
   asking for every hit with `-1` gets ten, so a query that needs every hit has to pass
   an explicit limit.
2. **Match computation runs whether or not `luc:match` is used.** For any query string
   other than `*`, `searchWithHitIds` calls `computeFieldMatches`, which loads each hit's
   whole stored document a second time and evaluates `Weight.matches` on it. A
   300,000-hit portal search with a text term pays that for every hit even when no
   `luc:match` appears in the query. A visitor limited to the matched fields applies
   there too.
3. **`luc:match` rejects a constant field.** `TextMatchPF` raises "Field must be a
   variable" for a field IRI in its field slot. `luc:fields` accepts one; relaxing
   `luc:match` the same way would make the two consistent.

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
| Entity IRI read only when the subject list has an `?entity` slot, with a one-field visitor; nullable `getEntityNode` | `ShaclTextQueryPF`, `ShaclTextIndexLucene.searchWithHitIds`, `SearchHit`, the warning in `computeNestedMatches` |
| Held searcher and its release on iterator close; reader check for late reads; per-field value cache; hit-ID map | `SearchExecution`, `ShaclTextQueryPF` |
| Doc-value eligibility, decided once per field | `ShaclIndexMapping.FieldDef` |
| Doc-ID-ordered doc-value and stored-field passes | `ShaclTextIndexLucene` |
| Per-search hit labels | `SearchHit` constructor |
| The property function | New `TextFieldsPF`; `IndexVocab.pfFields = NS + "fields"`; registered in `TextQuery` beside `pfNestedMatch` |
| Entity IRI doc value, if it measures well | `docFromMapping`; a reindex |
| Docs | A `luc:fields` section in [02-sparql-api.md](02-sparql-api.md), including that binding only `?hit` avoids the entity read; the `idx:stored` and `idx:sortable` rows in [03-configuration.md](03-configuration.md#field-properties) name `luc:fields`; a row in [README.md](README.md) |

## Tests

Written first and seen to fail, per the repository's test discipline. New classes are
JUnit 5 and must be added to `TS_Text`'s `@SelectClasses`; a class missing from that list
never runs.

- A single-valued `KEYWORD` field with a filter-only search (`*`) returns one row per
  hit, in rank order. This is the Discovery Portal shape, tested with the field read from
  stored fields and, declared sortable, from doc values.
- The same with a text query string and with a sort spec, so rank order is checked under
  relevance and under a sort.
- A multi-valued field returns one row per value.
- `INT`, `LONG`, `DOUBLE` and `TEMPORAL` fields return the same term `luc:match` returns
  for the same value, with and without `idx:storeLiteralMetadata`.
- A sortable `KEYWORD` field with `idx:normalizer` returns the raw value, not the
  normalized key: it is read from stored fields.
- For each eligible field shape (single- and multi-valued, IRI and string values), doc
  values and stored fields give the same terms, compared as sets.
- A sortable `KEYWORD` field with `idx:stored false` and no normalizer is returned; the
  same field without `idx:sortable` raises.
- A variable field slot returns every readable field and skips unreadable ones; a
  constant selects one.
- Two constant-field patterns give one row per hit with both columns.
- `luc:fields` and `luc:match` in one query: distinct variables give the per-hit product;
  shared `?field ?value` variables give only the common pairs.
- `(?hit) luc:query` reads no stored field: tested by counting visitor calls through a
  test hook, since a skipped read has no other observable result. `(?hit ?entity)` reads
  the entity field only.
- `?entity` binds the same IRI as before when its slot is present, and a constant
  `?entity` still filters hits.
- A second `luc:query` with the same key that binds `?entity`, after one that did not,
  gets the IRIs without re-running the search.
- A commit between the search and a late `luc:fields` read raises; with no commit, the
  late read succeeds.
- A window re-run discards cached values and reads them again.
- The searcher is released when the query finishes, including when the query is
  cancelled or abandoned part-way: the `SearcherManager` reference count returns to its
  starting value.
- Each error in [Errors](#errors) raises and names the field.
- Two `luc:query` calls with different searches in one query: each `?hit` resolves to its
  own search's values.
- 300,000 hits complete in time linear in the hit count. Use a synthetic index sized to
  make a quadratic join fail a generous timeout, not a wall-clock benchmark.

## Performance

The change touches the per-hit path of every `luc:query`, so it is measured for
regressions in queries that do not use `luc:fields`, as well as for the gain in the
Discovery Portal query.

### Benchmark

A JMH benchmark in `jena-text`'s test sources, run on demand and not by `TS_Text`. It sits
in `jena-text` rather than `jena-benchmarks`, because `jena-benchmarks` is upstream's and
its module lists already conflict on every upstream sync.

It builds a synthetic SHACL index of 500,000 entities, each with about ten stored fields
including two `TEXT` fields of a few hundred characters, so that whole-document loads cost
what they do on real data. Scenarios:

| Scenario | What it guards |
|---|---|
| Page of 20, text query, binds `?entity` | The common UI query |
| Page of 20, text query, with `luc:match` | Match computation and the `?hit` join |
| `luc:query` with `luc:facet`, same search | Shared execution |
| 300,000 hits, filter only, binds `?entity` | Large windows that need the IRI; the entity doc-value step |
| 300,000 hits, `(?hit)`, CollarId from stored fields | The Discovery Portal shape before reindexing |
| 300,000 hits, `(?hit)`, CollarId from doc values | The Discovery Portal shape after |
| 300,000 hits, binds `?entity`, stored-only field | The double-decompression case |

The benchmark is committed on its own first, and its numbers recorded at that commit, so
the baseline is the current code. The first three scenarios and the fourth run on both
the baseline and the change. The proposed threshold is that none is more than 5% slower
at the median, warm, on the same machine. The others have no baseline and are reported.

### End-to-end measurement

On the same local dataset and NVMe as the baseline table, evict the TDB2 and Lucene files
from the page cache, then run the 300,000-ID query with `luc:fields` in place of the
triple pattern: once with CollarId read from stored fields, and once with it declared
sortable and read from doc values. Record time, disk reads and data read, cold and warm,
and confirm the row count and the set of IDs equal the baseline query's. Repeat cold on
shared dev.

Separately, measure warm per-hit `luc:query` time for an ordinary query that binds
`?entity`, against the 13 µs baseline, to confirm the one-field visitor helps queries that
do not use `luc:fields`.

## Open decisions

1. **Name.** `luc:fields` is proposed. `luc:values` and `luc:stored` were considered.
2. **Variable field slot.** Whether a variable should be allowed at all. It reads every
   readable field of every hit, which is cheap per hit but unbounded in memory for a large
   window. Requiring a constant is the conservative choice.
3. **Numeric doc values.** `INT` and `LONG` doc values could be eligible when the
   original lexical form is canonical, but nothing records whether it was. Left as
   stored-only until a numeric identifier needs it.
4. **Regression threshold.** 5% at the median is proposed. Benchmark noise on the
   machine used may call for a wider band or more iterations.
5. **The related defects.** Whether they are fixed before this lands. The negative
   `limit` defect affects any client that follows the documentation.
