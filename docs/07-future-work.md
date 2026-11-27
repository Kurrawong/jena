# Known Limitations & Future Work

## Items Needing Attention

### Update performance at scale

The SHACL change listener rebuilds the whole entity document on each relevant change and
reads all triples of the entity from the base dataset. The query path is measured at
500,000 entities by `BenchLucQuery` (see [Testing](05-testing.md)). The update path is
not measured. It needs tests with:

- entities with 100 or more triples;
- indexes with 100,000 or more entities;
- frequent updates.

### Several sh:targetClass values per shape

The data model accepts several `sh:targetClass` values per shape, and the change listener
and document builder handle them. Most tests use one class per shape, so this case has
little coverage. The discriminator field holds the local name of the first class only.

### Concurrent write transactions

`ShaclTextDocProducer` tracks transactions with a `ThreadLocal<Boolean>`, as
`TextDocProducerTriples` does. Concurrent writes need a stress test, in particular the
rebuild on change, where one transaction can read state from the base dataset that
another has only partly committed.

---

## Planned Extensions

Each extension can be added as an opt-in setting or a new property function. An extension
to an existing response keeps the existing bindings.

| Extension | API impact |
|---|---|
| [Sideways counts for hierarchies and nested fields](#sideways-counts-for-hierarchies-and-nested-fields) | None: these fields move from full-filter counts to sideways counts in the same default mode |
| [Sideways strategy per query](#choosing-the-sideways-strategy-per-query) | None |
| [Hierarchy enhancements](#hierarchy-enhancements) | Extensions of the `luc:facet` hierarchy model |
| [Range facet enhancements](#range-facet-enhancements) | Extensions of `luc:facet`; no separate range property function |
| [Result grouping](#result-grouping) | New property function `luc:group` |
| [Suggest and autocomplete](#suggest--autocomplete) | New property function `luc:suggest` |

### Sideways counts for hierarchies and nested fields

`luc:facet` counts flat `KEYWORD` facets and range facets sideways by default, with
Lucene's `DrillSideways` (see [SPARQL API: count modes](02-sparql-api.md#count-modes)).
Two kinds of field are still counted under the full filter:

- **Hierarchy dimensions and their level fields.** A sideways count must decide which
  levels of a selected path to leave out. The filter's `=` clauses on levels also set the
  drill-down path that the counts are read from.
- **Fields in an `idx:nested` scope.** The CQL compiler folds the clauses on one scope
  into one block-join query, so they must match the same child record. `DrillSideways`
  combines each dimension's query with the base query as a separate query. A nested
  clause made into a dimension would therefore be lifted to the parent alone, and could
  match a different child from the other clauses of its scope.

### Choosing the sideways strategy per query

Sideways counts run `DrillSideways` with an executor, so it runs one search for the full
filter and one per selected facet. Without an executor, `DrillSideways` runs one pass.
On the benchmark index, the one pass was four times faster when few entities matched the
base query and the selections were broad (3.27 ms against 12.9 ms). It was up to 2.3
times slower when many entities matched. A choice per query, from Lucene's cost
estimates, would keep the gain and avoid the loss. See
[2026-11-13_luc_facet_sideways_counts_design.md](2026-11-13_luc_facet_sideways_counts_design.md).

### Hierarchy enhancements

- Hierarchies derived from SKOS `broader` and `narrower`.
- Hierarchy paths rendered with labels.
- Hierarchy browsing in the UI.

### Range facet enhancements

Range facets use range objects in `facetFields` and the 5-slot subject
`(?field ?value ?low ?high ?count)`. Possible additions:

- automatic bucket boundaries;
- calendar buckets for date and time values;
- bucket labels for display;
- range-on-range counts over source data that is already in ranges.

### Result grouping

Group search results by a field value, for example books by author, with Lucene's
`GroupingSearch`. The grouping field is on the entity document.

### Suggest / Autocomplete

A `luc:suggest` property function for type-ahead, with Lucene's `SuggestField`:

```sparql
PREFIX luc: <urn:jena:lucene:index#>
(?suggestion ?weight) luc:suggest ("mach" 10) .
```

It needs:

1. a suggest field type in the SHACL field types;
2. a Lucene suggester, for example `AnalyzingInfixSuggester`;
3. the property function;
4. an index rebuild to fill the suggest structures.

---

## Architectural Considerations

### Switching modes needs a reindex

`text:entityMap` and `text:shapes` write different Lucene document structures. A switch
in either direction needs a full reindex. There is no migration.

### FacetsConfig must match between writing and reading

`FacetsConfig` records which fields are multi-valued and hierarchical. It must be the same
when documents are written and when they are read. After a field changes from
single-valued to multi-valued, existing documents keep the old structure until a
reindex.

### Memory for faceting

`SortedSetDocValues` faceting keeps the unique values of a field in memory during facet
collection. A field with very many unique values, for example IRIs, can use a large amount
of memory.

### Thread safety

`TextIndexLucene` assumes one writer at a time, which Jena's transactions enforce. Its
`IndexWriter` field is `volatile` and has no lock. `ShaclTextDocProducer` tracks
transaction state with a `ThreadLocal<Boolean>`, as `TextDocProducerTriples` does.

Reads differ by index type:

| Index | Reader |
|---|---|
| `TextIndexLucene` (classic) | Opens a new `DirectoryReader` for each query |
| `ShaclTextIndexLucene` | Acquires an `IndexSearcher` from a `SearcherManager`, which a commit refreshes. Each searcher sees one consistent snapshot |
