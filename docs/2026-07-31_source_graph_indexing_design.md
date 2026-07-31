---
title: "source graph indexing"
date: "2026-07-31"
---

# 2026-07-31 Source Graph Indexing

## Status

**Designed, not built.** Nothing in this note is implemented.

Refines [2026-04-08-graph-filtering-target-model.md](2026-04-08-graph-filtering-target-model.md),
which chose the shape — graph scoping is a doc-level field, not a result slot — but left
"indexing support to collect source graphs while traversing fields" as a future note. This
note settles the three things that were open: what turns it on, what the field contains
when an entity spans graphs, and where the collection actually has to happen.

It also records why `?graph` was removed from `luc:query` on 2026-07-31, which is the part
already carried out.

## What exists today

Nothing. Graph provenance is inert end to end, in a way that reads as implemented:

| Surface | State |
|---|---|
| `SearchHit.graph` | **Removed 2026-07-31.** Its one construction site passed `null` unconditionally |
| `?graph` slot on `luc:query` | **Removed 2026-07-31.** Subject arity is now 1–5, `?rank` last |
| `graphURI` parameter on `ShaclTextIndexLucene.searchWithHitIds` | Accepted and never read. Threaded down from `SearchExecution` to match the classic signature |
| `EntityDefinition.graphField` | Classic mode only. Populated in `TextIndexLucene`, never in the SHACL path |

The classic (triple-per-document) index does support a graph field, and does filter on it
by appending `graphField:<iri>` to the query string. That mechanism does not carry over,
for the reason in the next section.

### Why `?graph` was removed rather than populated

`ShaclTextDocProducer.allGraphsView()` builds a `GraphUnionRead` over the default graph
plus every named graph, and the entity document is assembled from that union. An entity's
indexed values can therefore originate in several graphs at once — which is not an edge
case but the normal shape whenever description is split across graphs, e.g. a base record
in one graph and a curation or enrichment layer in another.

So a per-hit `?graph` binding has no defensible value. Binding the first graph seen would
be arbitrary; binding one row per contributing graph would multiply hits and quietly break
`?totalHits` and paging. The slot was removed, and this design puts the provenance where it
can be multi-valued without distorting the result set: on the document.

`luc:query`'s subject list is now `(?hit ?entity ?score ?totalHits ?rank)`, and a six-element
list is rejected at build time so a query written against the old shape fails loudly rather
than binding `?graph` to a rank integer.

## Decision

Add an **opt-in index-time flag** that records, per entity document, **every graph that
contributed an indexed value**, as a multi-valued KEYWORD field under the reserved IRI the
2026-04-08 note already named:

```text
urn:jena:lucene:field#sourceGraph
```

Assembler flag, on the index config rather than per shape:

```turtle
<#index> a text:TextIndexLucene ;
    text:shapes <#shapes> ;
    idx:storeGraph true ;      # default false
    .
```

### Multi-valued, not single

An entity spanning graphs A and B gets both. The alternative — a single "the graph",
defined as whichever graph holds the `rdf:type` triple that matched the shape target — is
rejected. It is defensible in isolation and it is exactly wrong in the case that motivates
asking: an entity typed in a base graph but enriched from a curation graph would report
only the base graph, and a filter for the curation graph would not return it. A field that
is accurate until the data gets interesting is worse than no field, because it is trusted.

Multi-valued means the filter semantics are "this entity has at least one indexed value
sourced from graph X", as the 2026-04-08 note specified. That is an honest reading of a
union-built document. It is not "this entity lives in graph X", and the documentation must
not imply that it is, because for a split entity no such statement exists.

### Opt-in, not always on

Three reasons the flag defaults to false:

- It costs index size on every document, and multi-valued means the cost scales with how
  split the data is.
- Collecting it requires the value traversal to track provenance per value (see below),
  which is work the common single-graph deployment has no use for.
- For a single-graph dataset the field is a constant, and a constant field is worse than
  absent: it invites filters that look meaningful and always match everything.

## Where the collection has to happen

This is the part the 2026-04-08 note deferred, and it is the bulk of the work.

`ShaclEntityBuilder.buildEntity` receives the union `Graph` and resolves each field's
values through it. A `GraphUnionRead` does not report which member graph produced a triple,
so provenance cannot be recovered after the fact from the union view. Two options:

1. **Resolve per graph.** Iterate the contributing graphs and run value resolution against
   each, unioning the results and recording which pass produced values. Straightforward and
   obviously correct, but multiplies traversal cost by the graph count, which is the wrong
   trade for a deployment with many small graphs.
2. **Ask the quad store per resolved value.** Resolve as now, then for each resolved value
   look up the quad(s) that produced it via `baseDataset.find(ANY, s, p, o)` and collect
   their graph nodes. One extra lookup per indexed value, and it degrades gracefully — the
   flag being off means none of it runs.

Option 2 is preferred, with the caveat that a value reached through a multi-step join path
has more than one contributing triple; the honest answer is to collect the graphs of every
triple along the path, not just the last hop. That is what makes the field mean "contributed
to", which is what the filter semantics need.

### Cases that need a decision when built

- **`idx:externalSource` children.** Nested records built from a CSV/TSV file have no source
  graph at all. They should contribute nothing to `sourceGraph`, not a placeholder IRI.
- **The default graph.** Needs a stable, documented representation. `urn:x-arq:DefaultGraph`
  is what Jena already uses internally and is the least surprising choice, but it must be
  written down, since users will type it into filters by hand.
- **Nested documents.** Whether the field lands on the parent only, or on child documents
  too. Parent-only is enough for the stated filtering use case and avoids widening the
  block-join surface.
- **Incremental update.** `ShaclTextDocProducer` rebuilds the whole entity document on
  change, so the field is recomputed wholesale and needs no delta logic — but that also
  means a graph deletion only drops out of `sourceGraph` once something triggers a rebuild
  of that entity.

## What this buys

Because it lands as a normal KEYWORD `FieldDef` rather than a special case, the existing
machinery applies unchanged:

- **CQL filtering** — restrict a search to entities with content from a graph:

  ```json
  {"op":"=","args":[{"property":"urn:jena:lucene:field#sourceGraph"},"http://example.org/graph/A"]}
  ```

- **Faceting** — `luc:facet` over `sourceGraph` gives hit counts per contributing graph,
  which is the more useful shape of the original question: not "where did this one hit come
  from" but "how is this result set distributed across graphs".

Neither needs new query-side code, which is the main argument for this shape over reviving
a result slot.

## Non-goals

- No `?graph` result slot on `luc:query`, `luc:match`, or `luc:nestedMatch`. This reaffirms
  the 2026-04-08 decision; the slot that existed until 2026-07-31 was contrary to it.
- No per-match or per-value graph provenance in results. The field is doc-level.
- No index-time graph restriction (indexing only selected graphs). That remains the separate
  mechanism the 2026-04-08 note describes, and the two compose: with indexing restricted to
  one graph, `sourceGraph` reflects only that graph anyway.
- No use of the dead `graphURI` parameter on `searchWithHitIds`. If this is built, that
  parameter should be deleted rather than wired up — filtering goes through CQL like every
  other field.

## Testing notes

Per the repo's test discipline, the shape that must be tested is the one that would be
recommended, at real cardinality:

- An entity whose values come from **two** graphs reports both. This is the case the
  single-valued design would have got wrong, so it is the test that has to exist.
- A filter on graph B returns an entity typed in graph A but enriched from B.
- With `idx:storeGraph` absent or false, no `sourceGraph` field is written and a filter
  naming it fails as an unknown field rather than silently matching nothing.
- Default-graph-only data reports the documented default-graph IRI, not an absent field.
- Faceting on `sourceGraph` counts an entity spanning A and B once under each — and the
  facet counts therefore sum to more than `?totalHits`, which needs to be stated in the
  docs or it will be read as a bug.
