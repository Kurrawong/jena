---
title: "Sideways facet counts in luc:facet"
date: "2026-11-13"
status: "Built"
---

# Sideways Facet Counts in luc:facet

`luc:facet` now counts sideways by default: given a search form's full filter, it counts
each selected facet without its own selection, and every other facet under the full
filter. An optional eighth argument, `"drilldown"`, counts every facet under the full
filter, as `luc:facet` did before. The counts come from Lucene's `DrillSideways`, which runs
one search for the full filter and one per selected facet. The reference for callers is
[SPARQL API: count modes](02-sparql-api.md#count-modes); this page records the decisions
behind it and the measurements.

Claims about earlier behaviour were checked against `ff965709ac`.

## The requirement

The `ts-benchmarking` project compares a faceted search form on QLever, Jena with this
index, and a plain SPARQL store. In the form, values within a facet are ORed and facets are
ANDed, and each facet's counts leave out that facet's own selection, so a user who selected
commodity `cu` still sees counts for `au`.

Before this change `luc:facet` counted every requested field under one filter. A client
wanting the form's counts made one call for the unselected fields with the full filter,
plus one call per selected field with that field's clause removed from the CQL2-JSON
filter by the client. A form with three facets selected needed four `luc:facet` calls and
four filters.

## The interface

```sparql
(?field ?value ?low ?high ?count)
  luc:facet (indexSelector fieldSpec queryString facetFields cqlFilter maxValues minCount countMode)
```

`countMode` is `"sideways"` or `"drilldown"`, Lucene's names for the two kinds of count. It
is optional: leaving it out, or `""`, means `"sideways"`, the way `luc:query`'s optional
eighth argument, `after`, is left out or `""` when unused. Any other value is an error.

| Mode | A field the filter selects on | Every other field |
|---|---|---|
| `"sideways"` | its own selection left out | full filter |
| `"drilldown"` | full filter | full filter |

### Why sideways is the default

A search form is the main client of `luc:facet`, and a form's checkboxes need sideways
counts. The policy for `luc:` mode in `CLAUDE.md` allows breaking changes. No existing
jena-text test changed result under the new default: none of them faceted, through
`luc:facet`, a field its filter also selected on.

Drill-down counts stay available because they answer a different question: what the
current results contain. For a selected field they list only the selected values, and they
add up to `luc:query`'s `?totalHits`. Charts and summaries of a result set need that.

A reader of the counts has to know which mode applies. In sideways mode a selected field's
counts do not add up to `?totalHits`, and the rules below decide per field whether it was
counted sideways. The rows carry no marker; the API reference documents the rules.

### Options not taken

| Option | Reason not taken |
|---|---|
| Keep drill-down as the default, sideways on request | The main client would have to ask for the only counts that suit it. |
| A `{"fields": [...], "sideways": true}` object as `facetFields` | Built first, then dropped: it puts a counting option inside the list of facet targets. |
| A key inside `cqlFilter` | The filter is standard CQL2-JSON built from form state. A control key there mixes request options with filter data. |
| `"results"` as the drill-down mode's name | Ambiguous: both modes count results. |
| A boolean eighth argument | `true` or `false` in eighth position does not say what it switches. |
| A separate property function | Duplicates `luc:facet`'s arguments, subject form and result shape for one option. |

Each (field, value) is returned once. A client that wants both counts for a field adds a
second `luc:facet` with `"drilldown"` to the same query. The facet cache in
`SearchExecution` is keyed by the request's canonical form, which includes the mode, so the
two calls do not return each other's counts.

## Splitting the filter

`DrillSideways` takes a base query and one query per dimension, and returns the hits that
match every dimension and, per dimension, the hits that match every dimension except that
one. The filter is split as follows.

The **top-level clauses** are the arguments of a top-level `and`, or the filter itself when
it is a single clause. A **requested** field is one in `facetFields`, with `"*"` expanded to
every facetable `KEYWORD` field and hierarchy dimension. A requested field is a
**dimension** when:

1. at least one top-level clause names it;
2. every top-level clause naming it is eligible: `=` or `in` on a facetable `KEYWORD`
   field; for a field requested as a range facet also `between`, `<`, `<=`, `>` or `>=`;
   or an `or` whose branches are all eligible clauses on that same field;
3. no other part of the filter names it, such as an `or` with another field, or a `not`;
4. it is not in an `idx:nested` scope and is not a level of an `idx:facetHierarchy`.

The dimension's query is its clauses compiled together, ANDed when there are several. All
other top-level clauses, compiled as one `and`, and the text query form the base query.
Fields that are not dimensions are counted over the full-filter hits with the code
`getFacetCountsWithCql` uses, including the hierarchy drill-down paths taken from the full
filter. When the filter yields no dimension, the call is a drill-down call.

The `or` case in condition 2 covers values ORed within one facet that a client writes as
an `or` instead of an `in`. The jena-facet demo app does this when the user selects several
range buckets on one field: it sends an `or` of `between` clauses.

### A field that cannot be a dimension

A requested field that fails a condition is counted under the full filter, as in
drill-down mode. The alternative was to reject the call. Rejection would make one fixed
query text fail for some states of a form, for example once a commodity sits in an `or`
with a region, and the client would need a second query text for those states, which is
the work sideways counts exist to remove.

Condition 3 exists because leaving out only the top-level clauses of a field that is also
named in an `or` with another field would count it with part of its selection still
applied. That count matches neither the form's meaning nor the full filter.

### Equivalence with per-field calls

For each dimension, the sideways count equals the count of a drill-down call whose filter
is the original with that dimension's clauses removed. Removing them does not change how
the remaining clauses compile:

- the compiler folds clauses on one `idx:nested` scope into one block-join query, and turns
  `=` on hierarchy levels into taxonomy drill-downs, but condition 4 keeps every such
  clause in the base query;
- the other clauses compile one by one and are ANDed, so removing one does not change the
  others.

`TestFacetSideways` asserts this equality for every case it covers, including an index with
several segments and deleted documents. The `form` scenarios of `BenchLucQuery` check it
again at 500,000 entities before timing.

## Hierarchies, ranges and nested fields

Range facets take part: a `between`, an order comparison, or an `or` of them on a field
requested as a range facet makes that field a dimension, so a year range facet shows every
bucket under the other filters. The range counters accept any `FacetsCollector`, so this
needed no extra mechanism.

Hierarchy dimensions and their level fields are counted under the full filter. The
filter's `=` clauses on levels do two things today: they become taxonomy drill-down
queries, and they choose the path whose children are counted. A sideways count would have
to decide which levels of a selected path to leave out, which changes what the facet
returns, not only its counts.

Fields in an `idx:nested` scope are counted under the full filter. The compiler folds a
filter's clauses on one scope into one block join so that they match the same child
record. `DrillSideways` ANDs each dimension's query with the base query as a separate
query, so a nested clause made a dimension would be lifted to the parent on its own and
could match a different child from the scope's other clauses.

Both are recorded as future work in [07-future-work.md](07-future-work.md).

## luc:query in the same SPARQL query

`luc:query` and `luc:facet` share one `SearchExecution` when their index, fields, query
string and filter match. `luc:query`'s hits and `?totalHits` come from its own search with
the full filter in either count mode. `TestFacetSideways` checks the total.

## How the counts are computed

Four ways to compute the same counts were measured. All return identical rows; the
benchmark checks this before timing.

| Strategy | Code of ours |
|---|---|
| Per-field calls: what a client sent before, one drill-down `luc:facet` per selected field plus one, in a `UNION` | none |
| A: `DrillSideways` without an executor, one pass with its own `DrillSidewaysScorer` | none |
| B: `DrillSideways` with an executor: one search for the full filter and one per dimension | a same-thread executor |
| C: base query evaluated once into a bitset per segment, each dimension intersected with it once, per-dimension hits as ANDs of bitsets | about 80 lines |

### Measurements

`BenchLucQuery` over the synthetic index of 500,000 entities described in
[05-testing.md](05-testing.md), on a 12-core x86-64 machine limited to 10 cores, JDK 25.
Times are medians in milliseconds per SPARQL query. Two forms:

- **Selective**: category in 3 of 30 values, region in 2 of 8, status `active` (3 in 5
  entities); subject (200 values) counted but not selected.
- **Broad**: category in 20 of 30 values, region in 6 of 8, status `active` or `draft` (4 in
  5 entities), year between 1960 and 2015 with year as a range facet; subject as above.

Each form runs with text query `survey`, matched by 300,000 entities, and `basalt`, matched
by 10,638.

Exploration runs, 2 warmup and 3 measurement iterations of 5 seconds, Lucene's query cache
on:

| Form, text | Per-field calls | A: one pass | B: per dimension | C: bitsets |
|---|---|---|---|---|
| Selective, `survey` | 5.08 | 11.86 | 5.02 | 7.58 |
| Selective, `basalt` | 1.39 | 1.50 | 1.20 | 1.11 |
| Broad, `survey` | 23.5 | 32.6 | 19.4 | 24.4 |
| Broad, `basalt` | 13.8 | 3.27 | 12.9 | 3.97 |

Final run of the built code (B), with `BenchLucQuery`'s standard settings: 3 warmup and 5
measurement iterations of 10 seconds, query cache on. Median, with the 90th and 99th
percentiles in brackets:

| Form, text | Per-field calls | B: `luc:facet`, sideways |
|---|---|---|
| Selective, `survey` | 5.11 (5.69, 7.58) | 4.96 (5.22, 6.97) |
| Selective, `basalt` | 1.41 (1.49, 1.83) | 1.24 (1.32, 1.65) |
| Broad, `survey` | 19.99 (20.44, 22.29) | 17.30 (18.02, 21.61) |
| Broad, `basalt` | 12.98 (13.50, 16.35) | 12.78 (13.45, 15.93) |

One sideways call is faster than the per-field calls it replaces in all four scenarios,
by 2% to 13% at the median. Both run the same number of Lucene searches, one for the full
filter and one per selected facet, so the difference is probably the overhead of evaluating
several `luc:facet` calls and `UNION` branches instead of one. It was not profiled.

### Why A is slow with a large base, and fast with a small one

The following was read from the Lucene 10.3.1 source. `DrillSidewaysScorer.score` picks one
of three loops per segment from cost estimates. When the base query's cost is below a tenth
of the summed dimension costs it uses query-first scoring: it steps through the base hits
and calls `advance()` on each dimension. When the second cheapest dimension costs less
than a tenth of the base it uses drill-down-advance scoring. Otherwise it uses union
scoring, which reads every base hit and every posting of every dimension one document at a
time, in windows of 2,048 documents.

With `survey`, the base (300,000) is not below a tenth of the dimension costs, so A uses
union scoring. B's searches are plain filter conjunctions, which Lucene 10 runs with
`DenseConjunctionBulkScorer` when they are dense enough: each clause is loaded into a
bitset 4,096 documents at a time and the bitsets are ANDed word by word. B reads more
postings than A, since it re-reads the base for each search, but spends much less per
posting, which is the likely reason its four or five searches finish before A's one pass.
This was read from the source, not profiled.

With `basalt` and the broad form, A uses query-first scoring and checks only the 10,638
base hits against each dimension. B's searches each re-evaluate the broad dimensions: a
20-value `TermInSetQuery` on category matches about 333,000 entities. The query cache
probably does not help here: `LRUQueryCache` skips caching a clause that costs more than
ten times the lead clause of its conjunction, and these broad clauses are led by 10,638
base hits. This explanation was not profiled. That is the one case where A was four times
faster than B.

Lucene's maintainers have an open issue proposing to replace union and drill-down-advance
scoring with query-first scoring (LUCENE-10037). An open pull request fixes a correctness
bug in drill-down-advance scoring, where a dimension's two-phase check is skipped in
segments without deletions (apache/lucene#16981). Neither Solr nor Elasticsearch uses a
one-pass sideways scorer: Solr intersects cached filter bitsets per excluded filter, and an
Elasticsearch source comment in `FilterByFilterAggregator` says collecting each filter
separately is about twice as fast as interleaving them.

### Decision

B. It uses Lucene's own `DrillSideways` code, with a same-thread executor of ours that runs
the searches one after another on the query's thread. In every scenario measured it was
faster than the per-field calls a client sent before, so getting these counts in one call
costs no time over getting them in several. A was up to 2.3 times slower than the
per-field calls with a large base. C was faster than B with a small base but slower with a
large one, and is code to maintain.

B gives up A's gain in one case: few base hits with broad selections (12.9 ms against
3.27 ms on this index). If forms of that shape matter, choosing A or B per query from
Lucene's cost estimates, as `DrillSidewaysScorer` already does between its own loops, keeps
the counting in Lucene. That is left for later.

The same-thread executor also keeps the searches on the calling thread. A shared thread
pool would let the searches of one query run in parallel; it was not measured.

### Limits of the measurements

- One index of 500,000 entities, on one machine, one query at a time.
- The same filters repeat every iteration, so Lucene's query cache helps every strategy
  that runs ordinary searches. A run of C and the per-field calls with the cache turned off
  kept their order on three of the four scenarios; on selective `survey`, C went from
  slower to slightly faster (9.81 against 10.1 ms). A and B were not run without the cache.
- Times are for the whole SPARQL query in-process, without HTTP or result serialisation.
