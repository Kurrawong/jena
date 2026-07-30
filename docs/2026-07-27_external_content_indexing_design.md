---
title: "external content indexing design"
date: "2026-07-27"
---

# 2026-07-27 External Content Indexing Design

## Status

**Phase 1 implemented** (CSV/TSV, narrow input, sorted streaming merge, rebuild-only,
counters). Phases 2 and 3 remain proposed. Where the config sketch below differs from
what shipped, the shipped form is noted inline; the reference is
[03-configuration.md → External Content](03-configuration.md#external-content-csvtsv).

> **Superseded in part: `idx:sorted` no longer exists.** Everything below that treats
> sortedness as an operator-supplied assertion — the config table, the merge sketch,
> the open question at the end — has been overtaken. The second fallback this document
> lists, *"large file → external merge sort into a temp file at build start"*, was
> promoted from fallback to the only path: `SortingRowSource` sorts every source
> internally, spilling to temp runs only when the input exceeds the buffer. The
> assertion, its verification, and the buffer-everything branch are all gone.
>
> The reason was the trap described under [Sortedness](#sortedness): byte order and the
> obvious `ORDER BY` disagree on integer-like keys, and asking an operator to know that
> was a worse deal than paying for a sort. See
> [03-configuration.md → Sort order](03-configuration.md#sort-order).

This note records the design for populating
**nested child records of an entity document from an external tabular source**
(CSV, TSV, Parquet, JDBC) rather than from the RDF graph, joined to
graph-derived fields on the **same** entity document via the entity IRI.

**Depends on** [2026-07-02_nested_sort_selector_design.md](2026-07-02_nested_sort_selector_design.md)
being implemented — sorting entities by an external child value requires the
block-join sort selector. This design assumes it is present.

That note's "Out of scope" line excludes numeric observation pivots on the
grounds they were "being handled by SQL pushdown separately". **That scoping
decision is reversed here** — the discrimination is by a co-located property
value, mechanically identical to the qualified-identifier pattern it does
cover. The exclusion line should be amended.

## Problem

The SHACL/entity-per-document model derives every field by evaluating `sh:path`
against the graph. Some deployments have entity attributes that are:

- **large** — tens of millions of rows, hundreds of distinct measured
  properties, an order of magnitude bigger than the graph describing the same
  entities;
- **already authoritative elsewhere** — a relational warehouse or a published
  columnar/CSV extract is the source of truth, and loading it into RDF would
  duplicate a volatile dataset for no modelling gain;
- **needed only as search machinery** — range filters, range facets and sort,
  with the *values* retrieved from the source of truth, not from Lucene.

Today the only way to filter or facet on such attributes is to materialise them
as triples. That is the wrong trade: it inflates the store with data nobody
queries by SPARQL, and couples the graph's update cycle to a foreign batch
release cadence.

What is wanted instead: an entity's document carries **graph-derived fields and
external measurements side by side**, so a single Lucene query can constrain
across both (e.g. spatial + type + text from the graph, numeric ranges from the
external table) and return entity IRIs.

## Scope

In scope:

- declarative binding of external table columns to field definitions;
- external rows as **nested child records** of a graph entity;
- join to the graph entity on the entity IRI;
- indexed-but-not-stored external values as the default;
- batch (re)build, with a delta path designed but deferred.

Explicitly **not** in scope — no transformation language. The design deliberately
has no expression syntax, no computed columns, no unit conversion, no
value-conditional logic. A cell either parses as its declared field type or it is
an error. Anything else is the extractor's job, upstream, in whatever tool
produced the file (`SELECT`, `dbt`, a script). This keeps the config declarative
and the ingest loop free of an interpreter.

The single concession to derivation is **string concatenation of an IRI prefix**
(`idx:subjectPrefix`). It never inspects or rewrites a value.

## The model: property/value children, not one field per property

The natural-looking design is **flat**: one Lucene field per measured property
(`Au_PPM`, `Cu_PPM`, …), sparse on the entity document. It is rejected. The
chosen design is **nested EAV**: one child document per measurement, carrying a
`measuredProperty` keyword and a `measuredValue` number.

```
parent  = entity                       (graph fields: label, geometry, type…)
  child = { property: "Au", value: 12.4 }
  child = { property: "Cu", value:  0.7 }
  child = …
```

| | Flat (field per property) | **Nested (property/value children)** |
|---|---|---|
| Field IRIs in the query API | one per property (100s) | **two** |
| New property appears in source | config regeneration | **nothing to do** |
| Property as a facet dimension | awkward — N field probes | **native hierarchy** |
| Child docs | none | entities × populated properties |
| Index size | smaller | ~3–5× larger |
| Sort by one property | flat sort, trivial | block-join sort selector |

The decisive argument is **API surface and evolution**, not size. A flat model
puts hundreds of field IRIs into the public query API and requires a config
change every time the source gains a column; the nested model exposes two field
IRIs and absorbs new properties silently. Disk is cheap and recoverable; a wide
public API surface is neither.

Note that no RDF properties are invented in either model. External values never
enter the graph — a "field" here is an index-internal Lucene name, not a
predicate, and the ontology is untouched.

### The granularity constraint

Child-document count is `entities × populated properties per entity`, and
Lucene's hard ceiling is `IndexWriter.MAX_DOCS` ≈ 2.15 billion documents per
index. That makes the model choice **coupled to the grain of the entity**:

| Entity grain | Entities | Populated props | Children | Verdict |
|---|---|---|---|---|
| Coarse (one entity per real-world object) | ~10⁷ | ~35 | ~5 × 10⁸ | Comfortable |
| Fine (one entity per sub-record/measurement event) | ~10⁸ | ~35 | ~3 × 10⁹ | **Over the ceiling** |

**Nested EAV is viable only at coarse entity grain.** If the deployment needs
per-sub-record entities, the model must change — see
[Same-child limits](#same-child-limits) — and flat becomes the survivor. Confirm
entity grain before committing to this design; it is the one decision that
cannot be reversed cheaply.

### Same-child limits

Block-join correlates within **one** child document. Two clauses on *different*
properties are two different children, and Lucene cannot correlate across
siblings. So:

- `property = "Au" AND value > 0.5` — **one child**, exact.
- `(property="Au" AND value>0.5) AND (property="Cu" AND value>100)` — two folded
  block-joins ANDed at the parent. This means **"the entity has some Au above
  0.5 and some Cu above 100"**, *not* "in the same measurement event".

Entity-level conjunction is the semantic this model provides. If same-event
co-occurrence is required, the child must *be* the event (one child per
measurement event, with properties as flat fields on the child) — a different
design, and one that reintroduces the flat field-per-property problem at child
scope. Establish the requirement before building.

## Query capability

Verified against the current codebase.

| Capability | Status |
|---|---|
| Same-child filter mixing `=` and range (`property="Au" AND value>0.5`) | **Works today.** `CqlToLuceneCompiler.compileSameScopeFold` groups AND-ed leaves by nested scope into one `ToParentBlockJoinQuery`; `inferLeafNestedScope` accepts `CqlComparison`, `CqlIn`, `CqlBetween`, `CqlLike`, `CqlTextQuery` |
| Entity-level AND across properties | Works today (two folded block-joins ANDed) |
| OR across properties | Works today (`SHOULD` fold, `setMinimumNumberShouldMatch(1)`) |
| Range facets on child values | Existing range-facet machinery |
| Hierarchical facet property → value bands | Existing `idx:facetHierarchy` inside a nested block |
| **Sort entities by one property's value** | **Requires the block-join sort selector** — assumed implemented per Status |

`docs/03-configuration.md` stated nested same-child correlation was "via `=` only".
That was **stale** — range predicates fold correctly. Corrected 2026-07-27.

## Input formats and library choices

An `ExternalRowSource` SPI keeps format handling behind one small interface, so
the format list is extensible and heavy dependencies stay optional.

| Format | Library | License | Notes |
|---|---|---|---|
| **CSV / TSV** | **Apache Commons CSV 1.14.1** | ASL 2.0 | **Already in root-pom dependency management** — zero new dependency. TSV is free via `CSVFormat.TDF`. Recommended for Phase 1. |
| CSV / TSV (alt) | OpenCSV 5.12.0 | ASL 2.0 | Also already managed in root pom. |
| CSV / TSV (perf) | univocity-parsers / FastCSV | ASL 2.0 / MIT | Faster than Commons CSV in published benchmarks; swap target if ingest becomes the bottleneck. The SPI makes this a one-class change. |
| **Parquet** | parquet-mr (`parquet-hadoop`, `parquet-column`) | ASL 2.0 | Canonical reader. Historically drags `hadoop-common`; since 1.13 `org.apache.parquet.io.LocalInputFile` avoids the Hadoop FS layer. Budget ~15–30 MB of dependencies and a real CVE-tracking surface. **Keep in an optional module.** |
| Parquet + CSV + SQL (alt) | DuckDB JDBC (`org.duckdb:duckdb_jdbc`) | MIT | One self-contained jar reads Parquet **and** CSV **and** speaks JDBC. Can satisfy the sortedness precondition for free (`ORDER BY subject`). Pragmatic single-dependency answer to the whole matrix. |
| **JDBC** | driver of choice | — | Generic `JdbcRowSource`. Pushes `ORDER BY` and projection into the database, making "query SQL at index time" first-class rather than a workaround. |

Recommendation: **Phase 1 = Commons CSV only.** Add `JdbcRowSource` next — trivial
given the SPI, and it removes the export-a-file step. Treat Parquet as an
optional module, and evaluate DuckDB before taking on parquet-mr's weight.

Note the columnar-projection argument for Parquet is **weaker under the nested
model** than it would be under flat: the natural input is now a narrow
three-column table, so there is little width to project away. Parquet earns its
place on compression and scan speed, not projection.

## Configuration

### Source blocks live inside `idx:nested`

An `idx:externalSource` block sits inside an `idx:nested` block. Each source row
becomes one child document.

```turtle
@prefix idx:   <urn:jena:lucene:index#> .
@prefix field: <urn:jena:lucene:field#> .
@prefix sh:    <http://www.w3.org/ns/shacl#> .

<#SampleShape>
    sh:targetClass ex:Sample ;

    # graph-derived fields, unchanged
    sh:property field:sampleName ;
    sh:property field:geometry ;

    # external child records
    idx:nested [
        idx:nestedName "measurement" ;
        idx:externalSource [
            idx:format        idx:CsvFile ;
            idx:location      "/data/measurements.csv" ;
            idx:subjectColumn "sample_iri" ;
            idx:sorted        true ;
            idx:column [ idx:columnName "property" ; idx:field field:measuredProperty ] ;
            idx:column [ idx:columnName "value"    ; idx:field field:measuredValue ] ;
        ] ;
        idx:facetHierarchy ( field:measuredProperty field:measuredBand ) ;
    ] .
```

> **As shipped.** The column's canonical-field pointer is `idx:field`, not
> `idx:property` as sketched above — `idx:property` already means "a nested field
> occurrence", and reusing it here would collide. `idx:field` is how occurrences
> already reference fields.
>
> A facet hierarchy needs KEYWORD levels, so the example pairs the property with a
> band column rather than the raw numeric value.

Field definitions are ordinary definitions with **no `sh:path`**:

```turtle
field:measuredProperty
    idx:fieldName "measuredProperty" ;
    idx:fieldType idx:KeywordField ;
    idx:indexed   true ;
    idx:facetable true ;
    idx:stored    true .        # small, non-volatile label — see Not-stored semantics

field:measuredValue
    idx:fieldName "measuredValue" ;
    idx:fieldType idx:DoubleField ;
    idx:indexed   true ;        # range filters   -> DoublePoint
    idx:facetable true ;        # range facets
    idx:sortable  true ;        # sort selector   -> docvalues
    idx:stored    false .       # values live in the source of truth
```

**Externality is not a separate flag.** A field is external because it appears in
an `idx:column` binding — exactly as a field is nested because it appears in an
`idx:nested` block. An explicit `idx:external true` would be redundant state that
can contradict the bindings. The assembler must therefore stop requiring
`sh:path`, and must require its *absence* on bound fields.

**A nested block has either `idx:joinPath` or `idx:externalSource`, never both.**
`idx:joinPath` enumerates children from the graph; `idx:externalSource` supplies
them from rows. `idx:nestedName` is required for external blocks, since there is
no join path to derive a scope name from.

### Source properties

| Property | Meaning |
|---|---|
| `idx:format` | `idx:CsvFile`, `idx:TsvFile`, `idx:ParquetFile`, `idx:JdbcQuery` |
| `idx:location` | Path, glob (`/data/meas-*.csv`), or JDBC URL |
| `idx:query` | SQL text, `idx:JdbcQuery` only |
| `idx:subjectColumn` | Column holding the entity IRI (or the key to be prefixed) |
| `idx:subjectColumnIndex` | Zero-based subject column, used instead of the above when `idx:headerless` |
| `idx:subjectPrefix` | Optional string prepended to the subject column value |
| `idx:sorted` | Asserts input is grouped and sorted ascending by `subjectColumn` |
| `idx:delimiter` | Delimiter override for delimited text |
| `idx:headerless` | No header row; bind with `idx:columnIndex` instead of `idx:columnName` |
| `idx:onError` | `skip` (default, counted) or `fail` |

### Input shape

The natural input is narrow, one row per measurement:

```
sample_iri                          	property	value
https://ex.org/sample/A1            	Au      	12.4
https://ex.org/sample/A1            	Cu      	 0.7
https://ex.org/sample/A2            	Au      	 0.3
```

A **wide** source (one row per entity, one column per property) is also
supportable by binding several columns and emitting one child per non-empty
bound cell — useful when the upstream extract is already pivoted and unpivoting
it is inconvenient. The narrow form is primary because it maps 1:1 to child
documents and needs no per-column configuration.

### `idx:subjectPrefix`

The join key is the entity IRI, but external extracts usually carry a bare
business key. Repeating a long IRI prefix across tens of millions of rows is
significant file bloat, so:

```turtle
idx:subjectColumn "sample_id" ;
idx:subjectPrefix "https://ex.org/id/sample/" ;
```

Concatenation only — no mid-string placeholders, no escaping rules, no per-row
logic. If a key needs real work to become an IRI, the extractor does it.

## Merge model

### Why the merge cannot be a second pass

Two independent constraints force the join to happen at document construction.

**1. Lucene has no partial document update.**

| Mechanism | Verdict |
|---|---|
| `IndexWriter.updateDocument(Term, Document)` | Full **replace**. Needs every field in hand at once. |
| `updateNumericDocValue` / `updateBinaryDocValue` | In-place but restricted to **docvalues-only fields**, and updates only docvalues. Cannot create BKD points, so cannot make a field range-*searchable*. |
| Read stored fields, re-add, `updateDocument` | Requires everything `stored`, contradicting the not-stored goal. Rejected. |

**2. Block-join requires the whole block written atomically.** Parent and
children must be written contiguously in one segment via a single
`IndexWriter.addDocuments(Iterable<Document>)` call. Children cannot be streamed
in independently or appended later. **All of an entity's children must be in
hand before anything is written.**

Together these make grouping-by-subject mandatory, not merely an optimisation.

### Streaming merge join

`ShaclBulkIndexer` already materialises all work items in a `List<WorkItem>`
during `discoverWorkItems()` before `processItems()` runs, so the sort-merge
shape is nearly free:

1. Discovery proceeds as today, then **work items are sorted by `entityUri`**
   when any profile has an external source.
2. Each declared source is opened as a stream, asserted `idx:sorted` by
   `subjectColumn`.
3. `processItems` advances both cursors on one ordering. For each entity, all
   **consecutive** rows sharing that subject are consumed — each becomes a child
   document — merged with the graph-derived parent, and the whole block written
   with one `addDocuments` call.

Properties: O(N + M), constant memory, sequential I/O only, no random access —
which is what makes CSV and Parquet viable as sources at all.

Fallbacks when `idx:sorted` is false:

- small sidecar → in-memory `Map<String, List<Object[]>>`;
- large file → external merge sort into a temp file at build start;
- JDBC/DuckDB → push `ORDER BY` into the source and keep the streaming path.

An **external-major** alternative (iterate rows, look up the graph per row) was
considered and rejected: entities with no external row would never get a
document, so it silently drops data.

### Cardinality and match outcomes

| Case | Behaviour |
|---|---|
| Entity has matching rows | One child document per row |
| Entity has no matching row | Parent document only, no children — normal for sparse sources |
| Row's subject matches no entity | Counted and skipped (default). External content **augments** entities; it does not create them |
| Empty / absent value cell | Row skipped — no child emitted. Never coerced to `0` |
| Unparseable cell | Per `idx:onError`: `skip` (counted) or `fail` |
| Duplicate (subject, property) rows | Multiple children with the same property. Legal; affects sort collapse (MIN asc / MAX desc) |

The "row matches no entity" counter is the most valuable diagnostic here: a
subject-prefix or key-format mistake produces a technically successful build with
near-zero matches, and the reported match rate is what makes that visible. The
indexer does not enforce a minimum — the graph and the extract are equally valid
sources being aligned, no threshold could be guessed for a legitimate partial
overlap, and a bad join key is a data fix, not a config one.

## Not-stored semantics

`idx:stored false` is the default for external **values** and has precise
query-time consequences:

| Capability | Works? | Why |
|---|---|---|
| Range / equality filters | Yes | Points are indexed |
| Sort via block-join selector | Yes | Docvalues are written |
| Numeric range facets | Yes | Bucket bounds come from config |
| `luc:match` value binding | **No** | Nothing stored to return |
| Keyword term-facet value binding | Needs `stored` | Facet value binding leans on `text:storeValues` |

Hence the asymmetry in the config example: the **property label** is stored (a
short, non-volatile string like `"Au"`, needed for facet value binding, with no
staleness risk because it is not a measurement), while the **value** is not. The
not-stored discipline targets volatile measurements specifically.

**Freshness, stated plainly:** not storing values removes *display* staleness
only. The **filter is still a snapshot** — if the source moves 90 → 110 and the
index is not rebuilt, the entity is still filtered as 90. Not-stored is a
correctness *hygiene* measure, not a freshness mechanism. **Rebuild cadence must
match the source's update cadence.** Batch-released sources fit well;
continuously mutating tables do not, and should use a property-function pushdown
instead.

## Updates

### Full rebuild (Phase 1)

The supported path. For batch-released sources this is correct and simplest, and
should be the default position.

`ShaclTextDocProducer.change()` → `rebuildEntityDocuments()` reconstructs an
entity's document **from the graph alone**. If it fires for an entity carrying
external children, the rewritten document **silently loses them**. Therefore:

- A profile with any `idx:externalSource` is **rebuild-only**. The doc producer
  must detect this and refuse or log loudly — it must not quietly emit a lossy
  document.
- Indexes may mix profiles; only shapes with external sources are rebuild-only.

### Delta applied at build time (implemented)

**Implemented 2026-07-27**, as `idx:delta`. This is the *delivery* half of the delta
story, not the incremental-rebuild half:

```turtle
idx:externalSource [
    idx:location "data/assays.csv" ;
    idx:sorted   true ;
    idx:delta    ( "data/2026-07-a.csv" "data/2026-07-b.csv" ) ;   # applied in order
    idx:opColumn "op" ;                                            # default
    ...
]
```

The reader merges base and deltas per subject and hands the indexer each entity's
**complete** child set, so no partial update is needed and the block is still written
whole. Deltas must be sorted like the base; only one subject's rows are held at a time.

What it does not do: rebuild *only* affected entities. This is still a full rebuild —
it just removes the need to physically merge the base and its deltas into a new
snapshot first. Selective rebuild remains future work (see below).

#### Operation semantics — corrected

The sketch below said `DELETE` needs no value, reasoning that a row *is* a measurement
keyed by (subject, property). **That is inconsistent with this same note**, which makes
duplicate (subject, property) rows legal. If the pair is not unique, a valueless DELETE
cannot say which child it means. Resolved as:

| | |
|---|---|
| `DELETE` | matches on the bound columns it fills in; an **empty cell is a wildcard**. `DELETE s Cu` removes every Cu child; `DELETE s Cu 0.7` removes only that one |
| Numeric matching | by value, not lexical form — `0.70` deletes `0.7`. A delete that silently matches nothing is the worst outcome available |
| `ADD` | **appends**. Deliberately not an upsert: with duplicates legal there is no key to upsert on. Replacing is DELETE then ADD |
| Ordering | deletes apply before adds within a subject, so row order in the file cannot change the result |
| Unmatched delete | counted and reported, not an error — deltas get replayed and overlap |

This also generalises to wide children, where identity is the whole row rather than
(subject, property): a DELETE naming more columns simply matches more precisely.

### Delta via staged snapshot (designed, deferred)

The remaining piece — rebuilding *only* the affected entities rather than everything:

The constraint above is *reconstruction*, not delivery: rebuilding an entity's
block needs its **complete** external row set. That is the indexer's problem, not
the data producer's — so the delta file carries only what changed, and the
indexer reconstructs from state it owns:

```
snapshot(N)  +  delta  →  snapshot(N+1)  →  rebuild only affected entities
```

Delta format — the same narrow shape plus an operation column:

```
op      sample_iri                     property	value
ADD     https://ex.org/sample/A1       Au      	12.4
DELETE  https://ex.org/sample/A2       Cu
```

~~`ADD` is an upsert of one (subject, property) measurement; `DELETE` removes one,
needing no value.~~ **Superseded** — see
[Operation semantics — corrected](#operation-semantics--corrected) above. A valueless
DELETE cannot identify one of several measurements sharing a property, which this note
elsewhere permits. As implemented, `ADD` appends and `DELETE` matches on the columns it
fills in, with an empty cell as a wildcard.

Per-measurement deletion still falls out naturally, because a row *is* a measurement.

Reconstruction reads the affected subjects back from the updated snapshot, by
binary search if it is sorted by subject, or one sequential filtered pass
otherwise. A sequential pass over a multi-GB snapshot is minutes — acceptable for
any realistic delta cadence, and it needs no random-access index.

**Ownership rule:** graph changes own **document lifecycle** (a document exists
because the entity is in the graph); external deltas own **child content only**
and never create or delete parent documents. Deleting every external row for an
entity rebuilds it with graph fields and no children. A bad delta cannot destroy
documents.

## Sizing expectation

Dominated by child-document count, not by the number of distinct properties. Per
child: one keyword term, one numeric point, one docvalue, plus `_blockKind` and
`_nestedScope`. The distinct-property term dictionary is negligible — hundreds of
terms regardless of row count.

### Measured, 2026-07-27

The full GSWA downhole summary extract, measured with the `demo/geochem-external`
harness (`task reindex-full`, then `task measure-split`), which built the same
collars a second time without the `idx:nested` block and differenced the two
indexes. That harness has since been removed — the demos were consolidated onto
the mining demo alone — so the figures below are a record, not reproducible from
this tree:

| | |
|---|---|
| Collars (parents) | 2,470,212 |
| Measurements (children) | 29,707,584 |
| Lucene documents | ~32.2 M |
| Total index | **1,338 MB** |
| — graph-derived (parents only) | 257 MB (109 bytes/collar) |
| — external content | **1,082 MB (38.2 bytes per child)** |
| Facet taxonomy | 104 KB |
| Build | 6m08 @ 6,730 entities/sec |

**38 bytes per child document**, with the value not stored. That makes the earlier
"tens of GB" projection concrete: 10⁷ entities × ~35 properties = 5 × 10⁸ children
× 38 bytes ≈ **19 GB**. The estimate holds.

Two caveats on the figure. The full index still carried ~662 MB in unmerged
compound segments at measurement time while the graph-only twin had ~3 MB, so a
`forceMerge` would likely move the split somewhat in the external side's favour.
And the ~3–5× flat-vs-nested ratio below remains an **estimate** — no flat index
was built, so it is not backed by this measurement.

Compare: a flat model at the same grain is ~3–5× smaller (estimated, untested).
That is the price paid for the two-field API surface, and it is the right trade
while entity grain stays coarse.

For scale context, the same measurements as RDF would have been ~30 M triples
*in addition to* the graph, against 1 GB of index — which is the "why not load it
as triples" argument in numbers.

## Proposed code changes

Additive, and mostly on existing block-join rails.

- **`ExternalRowSource` SPI** (new) — `open()`, `next()`, `subject()`,
  `value(int)`, `isSorted()`, `close()`.
- **`CsvRowSource`** (new) — Commons CSV, CSV + TDF/TSV, header or positional,
  glob expansion.
- **`JdbcRowSource`** (new, Phase 2) — `ORDER BY` and projection pushed down.
- **`ParquetRowSource`** (new, Phase 2) — optional module.
- **`ShaclIndexMapping`** — add `ExternalSourceDef` (format, location, subject
  column, prefix, sorted, error policy, column→`FieldDef` bindings) attached to
  `NestedDef`; add derived `FieldDef.isExternal()`.
- **`ShaclIndexAssembler`** — parse `idx:externalSource` / `idx:column` /
  `idx:nestedName`. Validate: external fields have **no** `sh:path`; a nested
  block has `idx:joinPath` xor `idx:externalSource`; a field IRI belongs to
  exactly one scope; bound columns exist where the schema is discoverable.
- **`ShaclBulkIndexer`** — sort work items by `entityUri` when external sources
  are present; open sources; merge-join in `processItems`; counters for rows
  read / matched / unmatched / skipped.
- **`ShaclEntityBuilder`** — accept pre-resolved external rows and emit them as
  child documents in the entity's block, reusing `addFieldToDoc` so field typing,
  docvalues and points behave identically regardless of origin.
- **`ShaclTextDocProducer`** — guard against lossy live rebuild of
  external-bearing profiles.
- **Tests** — new `TestExternalContentIndexing`: IRI join; multiple children per
  entity; entities with no rows; unmatched-subject counting; `subjectPrefix`;
  same-child filter mixing `=` and range; entity-level AND across two properties
  (assert it is *not* same-child); block-join sort by one property with
  `missing:last`; hierarchical property→value facets; not-stored value yields no
  `luc:match` binding; unsorted-input fallback. **Register in `TS_Text.java`** or
  Surefire will not run it.
- **Docs** — new section in `03-configuration.md`; fix the stale "same-child via
  `=` only" line; amend the 2026-07-02 out-of-scope line; status row in
  `docs/README.md`.

## Phasing

1. **Phase 1** — CSV/TSV via Commons CSV, narrow input, sorted streaming merge,
   external children, rebuild-only, counters. No new dependencies.
2. **Phase 2** — Parquet (optional module) or DuckDB, `JdbcRowSource`, wide-input
   binding, unsorted-input external sort.
3. **Phase 3** — delta via staged snapshot.

## Discussion

**Why not load the external data as triples?** It duplicates a large, volatile,
externally-owned dataset into the store, couples the graph's update cycle to a
foreign release cadence, and pays RDF's per-value overhead for values only ever
compared numerically. Nothing queries them by SPARQL path.

**Why not a SQL property function instead?** A pushdown evaluates per query and
stays perfectly fresh, which is right for continuously mutating data. But it
cannot participate in Lucene scoring, faceting or sort pushdown, needs a live
connection on the query path, and puts per-query load on the warehouse. Indexing
wins when the source is batch-released and the access pattern is
filter/facet/sort. The choice is a data-cadence question, not a preference.

**Why nested rather than a field per property?** Chiefly public API surface and
evolution: two field IRIs instead of hundreds, and a new property in the source
needs no config change. Property-as-a-facet-dimension also comes free, where a
flat model would need N field probes. The cost is ~3–5× index size, which is
recoverable; a wide API surface is not.

**Why is the narrow input format primary, when it multiplies row count?** Because
under the nested model a document is created per measurement regardless — so the
row-count objection to long-format input disappears. The narrow form maps 1:1 to
child documents, needs no per-column configuration, and makes per-measurement
delete expressible.

**Why the entity IRI as the sole join key?** It is the one identifier both sides
agree on, it is what the document is keyed by
(`getDocDef().getEntityField()`), and it keeps the merge a single-key sort-merge.
Composite keys would immediately require a key-construction expression syntax —
the transformation language this design refuses.

**Why no transformation language?** It is the difference between a config file
and an ETL tool. Every source has an upstream step already that is better placed,
better tested and better versioned for value manipulation. Admitting one
expression evaluates to admitting all of them, and the config becomes a program
with no debugger. The boundary: **the indexer parses, it never computes.**

**Why derive externality from bindings instead of `idx:external true`?** A flag
plus a binding is two sources of truth that can disagree. Presence in an
`idx:column` binding is unambiguous, and it matches how `idx:nested` establishes
nested scope today.

**Why is unmatched-subject a skip rather than an error?** External extracts are
routinely broader than the graph. Failing on the first extra row would make
normal data unusable. But silent skipping hides the catastrophic case, so the
count and the match rate are always reported — loudly, with a warning when
nothing matched at all. Reporting is where it stops: judging the overlap is not
the indexer's job.

## Open questions

1. **Entity grain** — nested EAV is viable only at coarse grain (see
   [The granularity constraint](#the-granularity-constraint)). **Still open, and
   still the one decision that is not cheaply reversible.** Phase 1 does not force
   it either way: the mechanism is identical at both grains, only the child count
   differs. Confirm against the actual deployment before loading at scale.
2. **Same-event co-occurrence** — **partly answered by the implementation.**
   `idx:column` is repeatable without limit and every bound column lands on the
   *same* child, so making the child the event costs nothing but a wider row:

   ```
   hole_iri,depth_from,depth_to,analyte,value
   https://ex.org/hole/A1,0,10,Au,12.4
   ```

   All four fields then correlate in one block join — the same-scope fold groups
   every AND-ed leaf in a scope, with no arity limit — so "Au above 1 g/t in the
   0–10 m interval" is one exact child query.

   What stays true is narrower than the original framing. Widening the child
   **moves** the boundary rather than removing it: two analytes are still two rows,
   so "Au *and* Cu in the same interval" remains unanswerable same-child. That
   needs both analytes as columns of one row — which is the flat-at-child-scope
   design this note describes, and it is available incrementally, per column,
   rather than as an all-or-nothing switch.

   The [Same-child limits](#same-child-limits) section above is therefore slightly
   too pessimistic as written: it treats "the child must *be* the event" as a
   different design, when in practice it is a wider extract and a longer
   `idx:column` list. The cost it names is real but bounded — flat fields appear at
   child scope only for the properties actually co-queried, not for all of them.
3. **Multiple sources per nested block** — allow several `idx:externalSource`
   blocks feeding one child collection (N-way merge on the same ordering)?
   **Deferred.** One source per block in Phase 1. `idx:externalSource` is a
   repeatable property, so admitting more later is additive.
4. **`idx:sorted` verification** — **resolved: verify.** The assertion is checked
   as rows stream past, and a subject that sorts before its predecessor fails the
   build. Trusting it would let an unsorted file merge to mostly-unmatched — a
   successful build with almost nothing in it, which is the worst failure mode
   available here. The check costs one string comparison per row.
5. **Wide input** — **deferred to Phase 2**, as proposed. Narrow input is the
   primary form and no pivoted source has appeared yet.

Two further decisions were settled during implementation:

6. **Unsorted input** — buffered in memory rather than rejected, with a warning.
   The design listed this as a fallback; making it the default for
   `idx:sorted false` means a small sidecar works out of the box while the
   external merge sort stays deferred to Phase 2.
7. **Partial rows** — a row with an empty or unparseable bound cell is dropped
   *whole*, not partially. A child carrying `property = "Au"` and no value would
   match a same-child filter on the property while contributing nothing to the
   range clause, which is precisely the correlation trap the nested model exists
   to avoid.

## Out of scope

- Value transformation of any kind — units, detection-limit markers such as
  `<0.5`, null sentinels, rescaling, derived columns.
- Creating entities from external rows; external content augments existing
  entities only.
- Live/streaming updates from external sources.
- Cross-entity aggregation at index time.
- Same-event co-occurrence across different measured properties (see
  [Same-child limits](#same-child-limits)).
