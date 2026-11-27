# Design Decisions

Each section gives a decision, its reason, and its consequence.

## Decision: Keep upstream code paths, add SHACL code beside them

**Problem:** An early version changed upstream Jena files (`TextQueryPF`, core methods of
`TextIndexLucene`, the PF registrations in `TextQuery`). Each change is a possible
conflict when the fork merges `apache/jena`.

**Decision:** New behaviour goes into new classes. Changes to upstream classes are kept
small: registrations, hooks for the subclass, and the few changes listed below.

**Implementation:**

- `TextQueryPF` is the upstream version. `text:query` has no filter support.
- `ShaclTextQueryPF` implements `luc:query`, and `TextFacetPF` implements `luc:facet`.
- `ShaclTextIndexLucene` extends `TextIndexLucene` and holds all facet infrastructure
  (`FacetsConfig`, sorted-set doc values, the taxonomy).
- The new property functions are registered under `urn:jena:lucene:index#`. `text:`
  (`http://jena.apache.org/text#`) keeps the upstream property functions.

**Consequence:** Upstream merges touch few fork lines. The changes to upstream classes
are listed in
[Architecture: Upstream classes changed by the fork](04-architecture.md#upstream-classes-changed-by-the-fork).
One of them changes classic mode: `TextIndexLucene.parseQuery` treats the query string
`*` as match-all, also for `text:query`.

---

## Decision: One namespace for configuration and property functions

**Problem:** The new property functions need a namespace. `text:` belongs to upstream.

**Decision:** Use `urn:jena:lucene:index#` for both the configuration vocabulary and the
property functions. Configuration examples bind it to the prefix `idx:`
(`idx:fieldName`, `idx:facetable`); SPARQL examples bind it to `luc:` (`luc:query`,
`luc:facet`). Field IRIs use `urn:jena:lucene:field#`.

**Reason:** SHACL-mode configuration and queries use one vocabulary. `text:` stays with
classic mode.

---

## Decision: Separate property functions for hits and facets

**Problem:** An early combined property function, `text:queryWithFacets`, returned hits
and facet counts in one binding stream. Each hit was joined with each facet value, so N
hits and M values gave N×M rows.

**Decision:** Two property functions:

- `luc:query` returns hits, one row per hit.
- `luc:facet` returns facet counts, one row per value.

When their search parameters match, they share one `SearchExecution`.

**Reason:** A SPARQL property function returns flat bindings. Hits and facet counts have
different cardinalities and different row shapes.

**Consequence:** The hit search runs once. Facet counts run their own facet search on
first request, and `SearchExecution` caches them per facet request. See
[Architecture: Shared Execution Flow](04-architecture.md#shared-execution-flow).

---

## Decision: Separate queries for hits and facets

**Problem:** `luc:query` and `luc:facet` in one basic graph pattern share no variables, so
SPARQL joins every hit with every facet value: N×M rows.

**Alternatives evaluated:**

| Alternative | Result |
|---|---|
| Handle-based API: `luc:search` creates a handle, `luc:hit` and `luc:facet` read from it | The handle variable `?r` has the same value in every row, so the cross join stays. Nested blank-node patterns such as `[ luc:entity ?s ; luc:score ?score ]` cannot be implemented with Jena property functions, which bind subject and object variables and produce no triples for later patterns. Needs three property functions |
| Each property function in its own `OPTIONAL` | `OPTIONAL` is a left outer join. Each row of the first is extended by all M rows of the second: N×M |
| `CONSTRUCT` output | The output graph removes duplicates, but evaluation still computes N×M bindings. The client reads a graph |

**Decision:** Recommend separate SPARQL queries. Document `UNION` for a single request.
Document the combined pattern with a warning about the cross product.

**Reason:** The cross product comes from SPARQL join semantics, so no property function
design removes it when two unrelated binding streams meet in one pattern. Elasticsearch,
Solr and other search engines also use one request for results and one for facets.
`UNION` gives N+M rows with sparse columns. `SearchExecution` shares the search between
the two property functions in any query structure where their parameters match.

---

## Decision: JSON for filters and facet field lists

**Problem:** A property function argument list needs to carry structured filter criteria,
for example `category = Technology AND author = Smith`.

**Alternatives considered:**

| Alternative | Drawback |
|---|---|
| RDF lists of pairs | Verbose; hard to build in application code |
| A custom string language | Needs a new parser; unfamiliar to clients |
| JSON | — |

**Decision:** A CQL2-JSON object for filters and a JSON array for facet field lists, each
in a string literal at a fixed argument position. The public contract names fields by
IRI. The Lucene field name from `idx:fieldName` is internal. The only other plain strings
are the Lucene query string and the `"default"` shorthand for the index and the field
spec.

```sparql
(?hit ?entity ?score) luc:query ("default" "default" "learning"
    '{"op":"=","args":[{"property":"urn:jena:lucene:field#category"},"Technology"]}'
    "" 20 0) .
(?field ?value ?low ?high ?count) luc:facet ("default" "default" "learning"
    '["urn:jena:lucene:field#category", "urn:jena:lucene:field#author"]'
    "" 10 0) .
```

**Reason:** Web applications build JSON from UI state, for example the selected facet
checkboxes. Field IRIs keep the public API stable when internal Lucene field names change.

---

## Decision: Entity-per-document through SHACL shapes

**Problem:** The triple-per-document model has these limits:

- Facet values are on other documents than text values, so a facet count needs a join
  on the entity IRI.
- An entity with several triples can be counted more than once.
- Lucene's `DrillSideways` counts facet values on the document that matched the query.
  With one triple per document, the facet values are on other documents. The
  entity-per-document index supports it, and `luc:facet` uses it for
  [sideways counts](02-sparql-api.md#count-modes).
- There are no numeric field types.

**Alternatives considered:**

| Alternative | Drawback |
|---|---|
| Field type annotations on `text:entityMap` | Adds to a config format that is already complex |
| A new custom vocabulary | No existing standard |
| SHACL node shapes and property shapes | — |

**Decision:** Use the SHACL vocabulary (`sh:targetClass`, `sh:path`,
`sh:alternativePath`) with extension properties in the `idx:` namespace. Read the
configuration with the Jena RDF API, and reuse jena-shacl's vocabulary, path parser and
constraint evaluation. The index runs no validation and produces no validation report.

**Reason:**

- A SHACL shape describes an entity type with typed properties.
- `sh:targetClass` states which class the entities have, for example `ex:Book`.
- `sh:path` and `sh:alternativePath` state which predicates to index.
- Extension properties (`idx:fieldType`, `idx:facetable` and others) add the index
  settings.
- The index reads configuration and filters values. It reuses jena-shacl where the two
  tasks are the same: the `sh:` vocabulary, `ShaclPaths.parsePath`, and the constraint
  components. A field occurrence's value constraints and a `sh:node` step's filter are
  jena-shacl node shapes, and the index asks only whether one node conforms to one shape
  ([2026-10-12_shacl_node_occurrence_filters_design.md](2026-10-12_shacl_node_occurrence_filters_design.md)).
  The `idx:` settings stay in the index's own mapping; jena-shacl does not read them.

---

## Decision: Keep entityMap beside shapes

**Decision:** The code for `text:entityMap` and `text:shapes` is present together. One
index uses one of the two: `text:TextIndexLucene` with `text:entityMap`, or
`text:TextIndexShacl` with `text:shapes`.

**Reason:** Existing deployments use `text:entityMap`. They continue to work unchanged.
New deployments, and deployments that need facets or numeric fields, can use SHACL mode.

---

## Decision: Rebuild the whole entity document on any change

**Problem:** When a triple of an entity changes, the index can update the affected field
or rebuild the whole document.

**Decision:** Rebuild. Read all triples of the entity, build the complete `Entity`, and
replace the document block.

**Reason:** Lucene has no partial update of an indexed document: a change to one field
deletes and adds the whole document. With nested fields, the parent and its children are
one block, which is deleted and added as a unit. A full rebuild needs no partial-update
logic, and each document always matches the current graph.

**Consequence:** The cost of a change grows with the number of triples and child records
of the entity. A profile with an `idx:externalSource` cannot be rebuilt from the graph,
so the change listener skips it and `ShaclBulkIndexer` rebuilds it.

---

## Decision: Discriminator field for indexes with several shapes

**Problem:** When several shapes target different classes (for example `Book` and
`Article`) in one index, the index must tell their documents apart.

**Decision:** A `StringField` holds the local name of the first `sh:targetClass` of the
shape, for example `Book`. The field name is `docType` by default and is set with
`idx:discriminatorField`.

**Reason:** The value is short and readable. The delete query of a rebuild matches the
entity IRI and the discriminator, so it removes the document block of one shape only.

---

## Decision: TermInSetQuery for many values on one field

**Problem:** A `BooleanQuery` with one clause per value fails with `TooManyClauses` when
the clause count exceeds Lucene's limit (1024 by default). Filters can carry many values,
for example an `in` list of entity IRIs.

**Decision:** Compile a set of values on one field to one `TermInSetQuery`. This applies
to `in` on a field, to `in` on the entity IRI, and to several values for one field in a
facet filter.

**Reason:** `TermInSetQuery` takes any number of terms as one query, from sorted byte
arrays.

---

## Decision: Count every matching document

**Problem:** The cost of a facet count grows with the number of documents the query
matches. An earlier `text:maxFacetHits` index property was meant to cap the documents a
facet count reads. The assembler read it, but no facet search applied it.

**Decision:** `luc:facet` counts every document the query matches. `text:maxFacetHits`
was removed. The assembler ignores it, as it ignores any property it does not read.

**Reason:** A cap counts a subset of the matches, taken in index order. Three things
follow from that:

- The counts no longer add up to the `?totalHits` that `luc:query` returns for the same
  query, which counts every match.
- In the default [sideways count mode](02-sparql-api.md#count-modes) each field is
  counted over its own set of documents. A cap would apply to each set separately, so
  the counts of different fields would come from different subsets, and a value's count
  would no longer be the number of hits its selection gives.
- It would not bound memory. `SortedSetDocValuesFacetCounts` keeps one counter per
  unique value of the field, whatever the number of documents read.

**Consequence:** Counting time grows with the number of matching documents. Use
`maxValues` and `minCount` to limit what is returned; neither reduces the documents read.
