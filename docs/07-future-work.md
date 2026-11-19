# Known Limitations & Future Work

## Items Needing Attention

### Assembler integration test with TTL file

The `TestShaclAssembler` builds config programmatically using the Model API. There is no test that loads a real TTL file with `text:shapes`. Adding a TTL-file-based assembler test would catch config serialisation issues.

### Large-scale performance validation

All tests use small datasets (5-10 entities). The SHACL mode's "rebuild entire entity on any change" approach reads all triples for the entity from the base dataset. This should be validated with:
- Entities with 100+ triples
- Indexes with 100k+ entities
- High-frequency update scenarios

### Multiple sh:targetClass per shape

The data model supports multiple `sh:targetClass` values per shape. The change listener and document builder handle this correctly. However, this pattern has limited test coverage — most tests use one class per shape.

### Concurrent write transactions

The `ShaclTextDocProducer` uses a `ThreadLocal<Boolean>` for transaction tracking (matching the pattern in `TextDocProducerTriples`). Concurrent write scenarios should be stress-tested, particularly the rebuild-on-change flow where one transaction might read partially-committed state from the base dataset.

---

## Planned Extensions

### Deferrable extensions

The following features are deferred. Most can be added as opt-in extensions or new PFs. Where a feature extends an existing response model, it should do so in a backward-compatible way.

#### Sideways counts for hierarchies and nested fields

`luc:facet` counts flat `KEYWORD` and range facets sideways by default, with Lucene's
`DrillSideways` (see [SPARQL API: count modes](02-sparql-api.md#count-modes)). Two kinds of
field are still counted under the full filter:

- Hierarchy dimensions and their level fields. A sideways count would need to decide which
  levels of a selected path to leave out, and the filter's `=` clauses on levels also set
  the drill-down path the counts are read from.
- Fields in an `idx:nested` scope. The CQL compiler folds a filter's clauses on one scope
  into one block-join query, so that they must match the same child record. `DrillSideways`
  ANDs each dimension's query with the base query as a separate query, so a nested clause
  made a dimension would be lifted to the parent on its own and could match a different
  child from the scope's other clauses.

**API impact:** none. These fields would move from full-filter counts to sideways counts
in the same default mode.

#### Choosing the sideways strategy per query

Sideways counts run Lucene's `DrillSideways` with an executor, which makes it run one search
for the full filter and one per selected facet. Without an executor, `DrillSideways` runs
one pass instead. On the benchmark index the one pass was four times faster when few
entities matched the base query and the selections were broad (3.27 against 12.9 ms), and
up to 2.3 times slower when many matched. Choosing between the two per query from Lucene's
cost estimates would keep the gain without the loss. See
[2026-11-13_luc_facet_sideways_counts_design.md](2026-11-13_luc_facet_sideways_counts_design.md).

**API impact:** none.

#### Hierarchy enhancements

Hierarchical facets are already supported. Follow-on work could extend them with:

- SKOS broader/narrower-derived hierarchies
- label-aware hierarchy path rendering
- richer hierarchy browsing UIs

**API impact:** Extensions on top of the existing `luc:facet` hierarchy model.

#### Range facet enhancements

Base numeric range faceting is designed to live on `luc:facet` using range objects in `facetFields` and the 5-slot subject form `(?field ?value ?low ?high ?count)`.

Follow-on work could extend that baseline with:

- automatic bucket generation
- calendar-aware bucketing for date/time values
- label generation for UI display
- range-on-range aggregation over pre-ranged source data

**API impact:** Extensions on top of the existing `luc:facet` model rather than a separate `luc:facetRange` PF.

#### Result grouping

Group search results by a field value (e.g., group books by author). Lucene's `GroupingSearch` can do this efficiently. The entity-per-document model makes this straightforward since the grouping field is on the same document.

**API impact:** New PF `luc:group`. No changes to existing PFs.

#### Suggest / Autocomplete

Add `luc:suggest` property function using Lucene's `SuggestField` for type-ahead autocomplete:

```sparql
PREFIX luc: <urn:jena:lucene:index#>
(?suggestion ?weight) luc:suggest ("mach" 10) .
```

Would require:
- New `SuggestField` in SHACL field types
- Lucene `Suggester` (e.g., `AnalyzingInfixSuggester`)
- New PF implementation
- Index rebuild to populate suggest data structures

**API impact:** New PF `luc:suggest`. No changes to existing PFs.

#### SHACL-aware bulk reindexing

The existing `jena.textindexer` CLI tool indexes all data using the triple-per-document model. A SHACL-aware variant would:
1. Load the SHACL mapping from the assembler config
2. For each entity matching a shape's `sh:targetClass`, build a complete entity document
3. Bulk-index efficiently (no per-triple rebuild overhead)

**API impact:** CLI tool only. No SPARQL API changes.

---

## Architectural Considerations

### Index rebuild required when switching modes

Switching from `text:entityMap` to `text:shapes` (or vice versa) requires a full reindex. The Lucene document structure is fundamentally different between the two modes. There is no migration path.

### FacetsConfig must match between index and read

The `FacetsConfig` (which fields are multi-valued, etc.) must be consistent between index time and read time. If a field is changed from single-valued to multi-valued, existing documents will have the old structure. A reindex resolves this.

### Memory considerations for faceting

`SortedSetDocValues` faceting stores all unique values in memory during facet collection. For fields with very high cardinality (e.g., URIs), this can consume significant memory. The `text:maxFacetHits` property limits the number of documents searched, which indirectly limits memory usage.

### Thread safety

`TextIndexLucene` assumes single-writer semantics (enforced by Jena's transaction system). The `IndexWriter` is volatile but not locked. The `ShaclTextDocProducer`'s `ThreadLocal<Boolean>` for transaction state follows the same pattern as `TextDocProducerTriples`.

Read operations (queries, facets) open a new `DirectoryReader` per call, which is thread-safe and sees a consistent snapshot.
