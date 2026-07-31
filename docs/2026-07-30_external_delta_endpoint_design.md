---
title: "external delta endpoint design"
date: "2026-07-30"
---

# 2026-07-30 External Delta Endpoint Design

## Status

**Designed, not built — and probably should not be built yet.** See
[Do not build this until the volume demands it](#do-not-build-this-until-the-volume-demands-it)
first. At the volumes now in play the same outcome is available by loading the values as
RDF, which needs no new code at all. This note exists so the decision is recorded with
its numbers, and so the design is ready if volume returns.

Extends [2026-07-27_external_content_indexing_design.md](2026-07-27_external_content_indexing_design.md),
whose "Delta via staged snapshot (designed, deferred)" section this supersedes, and whose
"Live/streaming updates from external sources" out-of-scope line this reverses.

## Problem

External content is **rebuild-only**. `ShaclTextDocProducer.rebuildEntityDocuments()`
reconstructs a document from the graph alone, so for a shape declaring an
`idx:externalSource` it would write the entity back stripped of every external child.
The producer therefore refuses outright and logs that the document is now stale
(`ShaclTextDocProducer.java:249`).

Two consequences, and the second is the one that hurts:

1. There is no way to apply an external delta without rebuilding the whole index.
2. **A graph change to an external-bearing entity is also refused.** An RDF Patch that
   corrects a collar's name leaves the document stale, because the machinery cannot
   rewrite the block without losing the assays.

So this is not only about a new endpoint. The same missing capability blocks the
already-working graph path.

## What already works

Worth stating precisely, because it sets the shape of what is missing.

`TextDatasetAssembler` builds a `DatasetGraphText extends DatasetGraphTextMonitor`, and
that wrapper is the dataset Fuseki hands to **every** write path. `PatchApply` does
`new RDFChangesApply(action.getDataset())`
(`jena-fuseki-core/.../servlets/PatchApply.java:143`), so a patched quad goes

```
POST /ds/patch → RDFChangesApply → DatasetGraphTextMonitor.add()
               → ShaclTextDocProducer.change() → rebuildEntityDocuments()
```

exactly as a SPARQL Update does. **Graph-derived SHACL fields are already live over the
RDF Patch endpoint.** Nothing in this note changes that; it extends the same path to
cover external children.

## The constraint everything follows from

Lucene has no partial document update, and a block join must be written whole. Touching
one child means rewriting the entity's entire block — parent and every child.
`ShaclTextIndexLucene.updateEntityForProfile` (line 1379) already does exactly this: a
delete-by-`(docIdField, discriminator)` term pair, then one `addDocuments(block)`.

A live delta is therefore trivial **if** the indexer can answer "what are entity E's
current external children?" cheaply. Today it cannot: the values are deliberately
`idx:stored false`, and the base extract is a multi-GB sequential file. Answering that
question is the entire design.

## Design

### 1. Retained rows — let Lucene own the external state

Opt in per source:

```turtle
idx:externalSource [
    idx:location   "/data/assays.csv" ;
    idx:retainRows true ;        # default false — rebuild-only, exactly as today
    ...
]
```

When set, the **parent** document carries one stored, non-indexed field per nested scope
— `_extRows$<nestedName>` — holding the raw cell text of that entity's children for that
scope, deflated. It is the same `String[]` rows `ExternalChildMerger.toRecord()` consumes;
nothing is transformed on the way in or out.

This makes a block **self-reconstructing**: read retained rows → apply operations →
re-emit via the existing `toRecord`/`addFieldToDoc` path, so a rebuilt block is identical
to a bulk-built one. The rewrite stays atomic with the index, because it is still one
`deleteDocuments` plus one `addDocuments`.

> **This is internal storage, not a wire format.** Producers send text (see
> [The wire format](#3-the-wire-format--ndjson)). Nothing outside the indexer ever sees
> the retained-rows field. "Deflated" is a size optimisation, not an interface.

**Cost.** On the measured GSWA index (2,470,212 collars, 29,707,584 children, 1,338 MB)
retained rows are roughly 25 bytes/child raw and perhaps 8–12 compressed — about
**+250–350 MB, or 20–25%**. That is the price of live updates, which is why it is opt-in
per source: a batch-released source keeps today's behaviour and today's size.

The alternative — a sidecar KV store (RocksDB, MapDB, LMDB) keyed by `(subject, scope)` —
avoids the growth but adds a dependency, a crash-consistency problem between two stores
that can now disagree, and a backup story. Take it only if the retained-rows field
measures materially worse than projected.

### 2. The graph path stops being broken

With retained rows present, `rebuildEntityDocuments()` no longer needs the
`hasExternalSource` refusal: it rebuilds from **graph + retained rows**, and
external-bearing shapes cease to be rebuild-only. This is worth having on its own,
independent of any endpoint.

It is also what makes the two delta paths compose. Both converge on one routine:

```
rebuildBlock(entity) = graph fields (ShaclEntityBuilder)
                     + retained external rows (optionally mutated by a delta)
                     → updateEntityForProfile
```

An RDF patch and an external delta touching the same entity in the same transaction then
produce one correct block, rather than two racing half-rebuilds.

### 3. The wire format — NDJSON

Do not invent new semantics. The operation contract is already settled and implemented in
`DeltaRowSource` (see its class javadoc, and `docs/03-configuration.md` → Deltas):

| | |
|---|---|
| `DELETE` | matches on the columns it fills in; an **empty cell is a wildcard**. Numeric columns compare by value, so `0.70` deletes `0.7` |
| `ADD` | **appends**; not an upsert, because duplicate `(subject, property)` rows are legal and there is no key to upsert on. Replace = DELETE then ADD |
| Ordering | deletes apply before adds within a subject, so row order cannot change the outcome |
| Unmatched delete | counted and reported, not an error — deltas get replayed and overlap |

NDJSON, one row per line, scope-qualified, with a leading header line:

```json
{"h":{"id":"urn:uuid:8f3c…","prev":"urn:uuid:1a90…"}}
{"op":"D","s":"http://ex.org/bh-1","scope":"measurement","v":{"analyte":"Ag"}}
{"op":"A","s":"http://ex.org/bh-1","scope":"measurement","v":{"analyte":"Ag","grade":51.3,"units":"ppm"}}
```

NDJSON because it streams, appends, and survives queues and blob storage without a
container format — the same reasons the CSV form works.

`v` keys are **column names, not field IRIs**. The binding stays in the config, so the
producer keeps emitting whatever its extract already calls those columns and a config
change does not become a producer change.

`text/csv` remains accepted for the existing shape (`op,subject,cols…`, with `?scope=`
naming the nested block), so one file drives both the CLI and the endpoint.

### 4. Idempotency is load-bearing, not a nicety

`ADD` appends. **Replaying a delta duplicates children silently**, and HTTP clients
retry. RDF Patch escapes this because adding a quad twice is naturally idempotent; this
format is not.

So the header line is mandatory:

- Applied ids go into Lucene commit user data (`IndexWriter.setLiveCommitData`) as a
  bounded ring of the last N.
- A re-POST of a seen id returns `200` with `{"applied":false,"reason":"duplicate"}`.
- `prev` chains the deltas, so a **gap** is detectable — a missed delta is otherwise
  invisible and permanently corrupting. An out-of-order or gapped id is a `409`, not a
  silent apply.

If the producer cannot guarantee stable ids and ordering, prefer a **replace-subject**
operation instead: `{"op":"R","s":…,"scope":…}` clears the scope and the following adds
repopulate it. It is trivially idempotent, and it fits a producer that can only say "here
is the current full set for these subjects". Worth adding regardless of the id scheme.

### 5. The endpoint

A `fuseki:externalDelta` operation modelled directly on `PatchApply`: same `ActionREST`
shape, same POST/PATCH, same `beginWrite`/`commit`/`abort` envelope, registered in
`OperationRegistry`.

```
POST /ds/external
Content-Type: application/external-delta+ndjson
```

Body → group by subject → per subject: locate the block, read retained rows, apply
operations, rewrite the block. The matching logic is lifted verbatim from
`DeltaRowSource.matches()` / `valuesEqual()` into a shared `DeltaOps`, so the HTTP path
and the build-time path cannot drift apart.

The response mirrors `ExternalChildMerger.SourceStats`: rows read and applied, adds,
deletes, deletes matching nothing, subjects unmatched.

Because it runs inside the Fuseki write transaction, and `ShaclTextDocProducer` already
defers `indexer.commit()` to `finish()`, a delta and a patch in flight interleave
correctly.

### 6. Ownership and ordering

Unchanged from the 2026-07-27 note: **graph changes own document lifecycle; external
deltas own child content only.** A delta for an entity with no document is counted and
dropped — it never creates one. A bad delta cannot destroy documents.

The operational consequence, and the main sharp edge to document: **patch first, then
delta.** Rows arriving before their entity are lost and must be replayed.

Parking orphan rows for an entity that has not arrived yet would need state outside the
index, which drags the sidecar store back in. Defer it. If co-arrival turns out to be
common, solve it with a bundle request — graph part then external part, one transaction —
rather than an orphan store.

### 7. Selective bulk rebuild falls out

Phase 3 of the 2026-07-27 note becomes the same code driven from a file rather than a
socket: the delta names its affected subjects, and each subject's current rows come from
its retained-rows field. No sequential pass over the base snapshot — which was the thing
that made that phase unattractive.

## Do not build this until the volume demands it

The 2026-07-27 design justifies external content on volume: attributes that are "tens of
millions of rows, an order of magnitude bigger than the graph describing the same
entities". **Remove the volume and most of the argument goes with it.**

If the extract is reduced to a max (or otherwise summarised) value per
`(entity, property)`, loading those values as RDF is the better answer:

| | External CSV + this design | **Load as RDF** |
|---|---|---|
| Live updates | retained rows, new endpoint, new format, idempotency ring | **RDF Patch endpoint, working today** |
| Index overhead | +20–25% for retained rows | none |
| New code | ~5 components across `jena-text` and `jena-fuseki-core` | **none** |
| Values readable | not stored; source of truth only | SPARQL-visible, `DESCRIBE`-able |
| Lucene model | `idx:externalSource` nested block | `idx:joinPath` nested block — the primary path |
| Store growth | none | ~4 triples per `(entity, property)` |
| Update cadence | decoupled from the graph | coupled to the graph |

The Lucene side barely changes. Nested EAV children from the graph via `idx:joinPath`
is the *original* design and supports everything the external variant does —
`idx:facetHierarchy` per child, same-child correlation, the block-join sort selector
(`docs/03-configuration.md` → Nested). External content was the bolt-on, not the
foundation. Swapping the source of the children is a config change, not a code change.

### The crossover, measured 2026-07-30

Counted from the narrow (already-summarised) extracts in
`gswa-prez-config/raw_data`, which are the unpivoted form of
`DHAssayFlatSummary/dh_assay_flat_summary.csv` and `ssAssayflat/ss_assay_flat.csv`
(~2.5 GB of wide CSV between them):

| Source | Entities | Measurements | Per entity |
|---|---|---|---|
| `drillhole-measurements.csv` (224 MB) | 224,184 | 2,551,572 | 11.4 |
| `surface-measurements.csv` (1.1 GB) | ~869,000 | 13,145,243 | 15.1 |
| **Total** | **~1.09 M** | **15,696,815** | 14.4 |

That is roughly **half** the 29.7 M children measured on 2026-07-27, which is the
reduction that reopens this question.

Analytes are a **closed set of 83 columns** — effectively the periodic table, stable for
decades. This matters: the 2026-07-27 argument for nested EAV over flat leant on
"a new property appears in the source → config regeneration", and that objection is much
weaker against a fixed column set than against an open-ended one.

Three modellings, and their cost:

| Modelling | Triples/measurement | Total triples | TDB2 growth¹ | Lucene model |
|---|---|---|---|---|
| Qualified node, minimal (link, analyte, value) | 3 | 47.1 M | ~3–5 GB | nested EAV, 2 field IRIs |
| **Qualified node, typed** (+ `rdf:type`) | **4** | **62.8 M** | **~4–7 GB** | nested EAV, 2 field IRIs |
| Full `sosa:Observation` (+ unit, observed property) | 6 | 94 M | ~6–10 GB | nested EAV, 2 field IRIs |
| Flat direct predicate (`<site> gswa:Au_PPM 12.4`) | 1 | 15.7 M | ~1–2 GB | **flat, 83 field IRIs** |

¹ At 60–110 bytes/triple on disk including node table and indexes. The upper end applies
here: measurement nodes are mostly *new* URIs and the values are distinct doubles, so
neither the node table nor the term dictionary gets the URI reuse that keeps TDB2 compact.

For scale, the loaded store is currently **15 GB**
(`gswa-prez-config/fuseki/external_drillhole/volume`), against 47.6 GB of raw N-Quads at
~208 bytes/line ≈ **~245 M quads** across all sources. So the typed-qualified-node option
is **+4–7 GB on a 15 GB store (~+30–45%), and ~25% of the graph's quad count** — for data
nothing queries by SPARQL path.

That is a very different proposition from the same exercise at the original volume, which
would have been ~119 M triples.

**The Lucene index size is unchanged either way.** 15.7 M nested children index
identically whether their source is a CSV row or a graph node — at the measured
38.2 bytes/child, about **600 MB** of external content in both worlds. What actually
differs is only: TDB2 growth, the retained-rows overhead (~160 MB, RDF path pays none),
and whether the delta endpoint has to exist.

| | External CSV + this design | **Load as RDF (typed qualified node)** |
|---|---|---|
| TDB2 growth | none | **+4–7 GB** |
| Lucene index (children) | ~600 MB | ~600 MB — identical |
| Retained rows | +~160 MB | none |
| New code | ~7 components across two modules | **none** |
| Live updates | build all of the above | **RDF Patch, working today** |
| Extra load time | CSV merge during build | ~62.8 M triples through `tdb2.xloader` |

**Verdict: load it as RDF.** +4–7 GB on a 15 GB store is a far easier trade than
building and then maintaining a bespoke delta protocol, a wire format, an idempotency
scheme and a retained-rows storage mode. The volume reduction is what makes it so; at
29.7 M measurements the answer was the other way.

The honest counter, which does not disappear: 62.8 M triples is a quarter of the graph's
quad count carrying data nobody dereferences or traverses. That was objection #1 in the
2026-07-27 note. It has become survivable, not wrong.

**Keep the external content machinery either way.** It is built, tested and documented,
and it is the right answer the moment the full measurement set — rather than a summary —
needs to be searchable. Reverting to it is a config change.

## Proposed code changes

- **`ShaclIndexMapping.ExternalSourceDef`** — add `retainRows`.
- **`ShaclIndexAssembler`** — parse `idx:retainRows`.
- **`ShaclEntityBuilder` / `ShaclTextIndexLucene`** — write the `_extRows$<scope>` stored
  field on the parent when retention is on; read it back when rebuilding.
- **`ShaclTextDocProducer`** — replace the `hasExternalSource` refusal with a rebuild from
  graph + retained rows, keeping the refusal only when retention is off.
- **`DeltaOps`** (new) — `matches()` / `valuesEqual()` lifted out of `DeltaRowSource`,
  used by both the file and HTTP paths.
- **`ExternalDeltaReader`** (new) — NDJSON and CSV to a stream of operations.
- **`ExternalDeltaApply`** (new, `jena-fuseki-core`) — the servlet, plus `Operation` and
  `OperationRegistry` entries.
- **Idempotency ring** — applied delta ids in Lucene commit user data.

## Tests

Per the repo's test discipline, red first, and **registered in `TS_Text.java`** or
Surefire will not run them.

- Retained rows round-trip: bulk build, reopen, rebuild one entity, children identical.
- A graph patch to an external-bearing entity **keeps** its children (this is the
  currently-failing behaviour — write it first).
- Retention off: the refusal still fires and is still logged.
- `ADD` appends rather than upserts; duplicate `(subject, property)` survives.
- `DELETE` wildcard: an empty cell removes every matching child.
- Numeric delete by value — `0.70` deletes `0.7`.
- Deletes before adds within a subject, irrespective of row order.
- Replaying a delta id is a no-op; a gapped `prev` is rejected.
- A delta for an unknown subject is counted and creates no document.
- Endpoint-level: patch and delta in one transaction produce one correct block.

## Open questions

1. **The reduced pair count.** Fill it in; it decides whether any of this gets built.
2. **Does the producer emit stable ids and ordering?** If not, adopt the
   replace-subject operation and drop the chain check.
3. **Retained-rows size in practice** — measure before committing publicly to the 20–25%
   figure; it is projected, not observed.
4. **Are patch and delta co-arriving for the same entity?** Only that determines whether
   the bundle request is needed.
5. **Crash consistency between TDB2 and the Lucene index** is not addressed here, and is
   not made worse — the existing commit path already has this property.
