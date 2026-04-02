---
title: "luc:match design for field-level search matches and highlighting"
date: "2026-04-02"
---

# 2026-04-02 `luc:match` Design

## Purpose

This note defines the proposed architecture for adding a new `luc:match` SPARQL property function alongside the existing `luc:query` and `luc:facet` functions.

The goal is to support:

- field-level match attribution for entity-per-document search hits
- optional field-level highlighting/snippets
- a clean mapping from RDF/SHACL field definitions to Lucene fields
- a result model that does not collapse document hits and per-field match details into one ambiguous flat row shape

This design is motivated primarily by issue `#48` (`?field binding unbound for multi-field luc:query`) and by the requirement to preserve a sensible architecture rather than patching around Lucene behavior heuristically.

## Problem

In the current SHACL-mode design:

- one RDF entity becomes one Lucene document
- one SHACL field definition becomes one Lucene field name plus one external field IRI
- `luc:query` returns document hits
- the optional `?field` binding in `luc:query` is only meaningful when exactly one search field was queried

This is not an accident. Lucene search returns top documents, not "document x field" pairs. In Lucene 10.3.1:

- `TopDocs` returns top-ranked documents
- `ScoreDoc` exposes `doc`, `score`, and `shardIndex`
- neither `TopDocs` nor `ScoreDoc` directly exposes which field matched

This means the current behavior is correct: multi-field search cannot derive a reliable scalar `?field` binding from `TopDocs` alone.

## Design Constraints

The design must preserve these architectural rules:

- external search field identity is the field IRI, not the internal Lucene field name
- the search field is an indexed field definition, not necessarily an RDF predicate
- a field may correspond to a direct predicate, an inverse path, a sequence path, or other SHACL path-derived value
- `luc:query` remains document-hit-oriented
- `luc:facet` remains aggregation-oriented
- field match details must not be modeled as though Lucene inherently returns a single matching field per hit

This means the correct conceptual model is:

- one search hit maps to zero, one, or many field matches

## Why A New PF Is Needed

Trying to force field matches into `luc:query` creates several problems:

- `luc:query` is currently one row per hit; field matches are a child relation of a hit
- multi-field matches can produce multiple valid field bindings for a single hit
- optional highlighting is naturally field-scoped, not hit-scoped
- row expansion inside `luc:query` would make limit, pagination, and ranking semantics harder to reason about

Therefore the API should be split:

- `luc:query` returns hits
- `luc:match` returns per-hit field match details

This mirrors the earlier decision to keep `luc:query` and `luc:facet` separate instead of flattening hits and aggregations into one shape.

## Motivation For The Split

The existing `luc:query` + `luc:facet` split avoids a cartesian product between two unrelated result sets:

- search hits
- facet aggregations

Those two shapes have no natural join key, so `UNION` is the right way to combine them in a single SPARQL response.

`luc:query` + `luc:match` is different. `luc:match` is not an unrelated aggregation. It is a child relation of a specific hit.

That means a join key is natural and correct here. The row count grows with actual field match multiplicity, not with an accidental cross join between unrelated shapes.

## Proposed Surface API

### `luc:query`

Keep `luc:query` document-oriented.

Current conceptual shape:

```sparql
(?hit ?s ?score ?totalHits) luc:query (fieldSpec queryString filter? sort? limit?)
```

Notes:

- `?hit` is a query-scoped hit identifier
- `?s` is the entity URI
- `?score` and `?totalHits` remain hit-level values
- optional scalar `?field` on `luc:query` should remain conservative:
  - bound when exactly one matched field is known
  - unbound when there are zero or multiple field matches

The existing subject shape can be extended carefully or a new result ordering can be introduced, but the core requirement is that `luc:query` exposes a hit id if `luc:match` is to join to it.

### `luc:match`

Add a new PF returning field-level matches for a hit:

```sparql
(?hit ?field ?value ?snippet) luc:match ()
```

Alternative forms are possible, but the intended semantics are:

- one row per matched field for a specific hit
- `?field` is the field IRI
- `?value` is the matched stored value when one can be sensibly selected
- `?snippet` is an optional highlight/snippet for that field

This PF should operate against the current shared `SearchExecution`, not run a second independent Lucene search.

### Example Shape

```sparql
PREFIX luc: <urn:jena:lucene:index#>

SELECT ?s ?score ?field ?snippet WHERE {
  (?hit ?s ?score ?totalHits) luc:query (
    '["urn:jena:lucene:field#title","urn:jena:lucene:field#description"]'
    "machine learning"
    10
  ) .

  OPTIONAL {
    (?hit ?field ?value ?snippet) luc:match () .
  }
}
```

This produces:

- one row for each hit-field match pair
- one row with unbound `?field` / `?value` / `?snippet` for hits with zero field matches when the `OPTIONAL` branch is used

It does not produce the bad `N x M` cartesian product that occurs when joining unrelated hit and facet result sets without a shared variable.

## Zero, One, Or Many Field Matches

### Zero

A hit may legitimately have zero field matches. Main cases:

- `MatchAllDocsQuery`
- a filter-only search with no meaningful text clause
- any query shape where a document is admitted without a field-specific text match suitable for attribution or highlighting

This is acceptable and should not be patched over with a fake field IRI.

### One

This is likely the most common case in ordinary search UX:

- a user query happens to match only one indexed field on a hit
- that hit gets one `luc:match` row
- scalar `?field` in `luc:query` may also be bound if we choose to preserve that shortcut

### Many

This is also legitimate:

- the same hit may match `field:title` and `field:description`
- a future UI may wish to show two snippets
- a downstream flat model may wish to emit both field IRIs as predicates on a search-hit node

The architecture must preserve that multiplicity rather than collapsing it prematurely.

## Public Semantics

### Field identity

The public field identifier remains the field IRI from the SHACL configuration.

This is essential because:

- the internal Lucene field name is an implementation detail
- the field may come from a complex SHACL path, not a single RDF predicate
- customer-facing flat models can use field IRIs directly as predicates if desired

### `?value`

`?value` should be interpreted as:

- the representative stored value for that field on that hit that corresponds to the match, when such a value can be identified

This is already approximated for single-field queries via `extractValueNode()` and `selectStoredValue()` in the current SHACL query path. That logic can be reused, but only after the field match set is known.

### `?snippet`

`?snippet` is optional. If highlighting is not requested, it can be left unbound.

If highlighting is requested:

- `?snippet` should be field-specific
- one snippet per field match is the cleanest first version
- later work can add multiple passages per field if needed

## Lucene Architecture

### Key Lucene APIs

The important Lucene 10.3.1 APIs for this design are:

- `TopDocs`
- `ScoreDoc`
- `IndexSearcher.createWeight(Query, ScoreMode, float)`
- `Weight.matches(LeafReaderContext, int)`
- `Matches`
- `NamedMatches`
- `NamedMatches.wrapQuery(String, Query)`
- `NamedMatches.findNamedMatches(Matches)`
- `UnifiedHighlighter`
- `UnifiedHighlighter.highlightFields(String[], Query, TopDocs, int[])`

The relevant confirmed signatures are:

- `Weight.matches(LeafReaderContext, int)`
- `NamedMatches.wrapQuery(String, Query)`
- `NamedMatches.findNamedMatches(Matches)`

That gives us a clean field attribution path:

1. Build one Lucene subquery per search field.
2. Wrap each field-specific subquery with a name.
3. Combine them into a single Lucene query.
4. Run one top-doc search.
5. For each hit, inspect `Matches` and extract named subquery hits.
6. Map those names back to field IRIs.

## Why Not Rely On `TopDocs` Alone

`TopDocs` is only the ranked document result set. It does not answer:

- which field clause matched
- how many field clauses matched
- where the match occurred inside the field text

That information lives lower in Lucene's query/match model, not in the top-hit container.

## Why `NamedMatches` Is The Right Fit

`NamedMatches` is consistent with the intended design because the field attribution model is:

- one named Lucene subquery per field

That is exactly the structure we need.

The field name used in `NamedMatches.wrapQuery(name, query)` should be a stable public identifier. The preferred choice is the field IRI string.

This yields:

- Lucene internal execution over field-specific clauses
- clean extraction of matched field identifiers
- no need to infer field identity heuristically from `Explanation` strings or top-doc metadata

## Highlighting And Its Relationship To `luc:match`

Highlighting does not replace field attribution. It complements it.

Recommended division of responsibility:

- `NamedMatches` determines which field clauses matched
- highlighting generates display snippets for those fields

For highlighting, Lucene's `UnifiedHighlighter` is the main API to investigate first because it supports:

- field-aware highlighting
- multi-field highlighting
- use of `TopDocs`
- `setWeightMatches(true)` for match-aware highlighting behavior

The suggested order of operations is:

1. determine matched fields using `NamedMatches`
2. if highlighting was requested, ask `UnifiedHighlighter` for snippets only for those matched fields

This keeps highlighting as an optional display concern rather than the primary truth source for match attribution.

## Query Construction Strategy

### Current behavior

Current SHACL-mode query construction uses:

- `QueryParser` for one field
- `MultiFieldQueryParser` for multiple fields

This is implemented in `ShaclTextIndexLucene.parseQueryForFields(...)`.

That is fine for plain search, but for field attribution it is too opaque. `MultiFieldQueryParser` hides the per-field subquery structure that we need to name explicitly.

### Proposed behavior

Introduce a new multi-field query builder for SHACL mode:

- if one field is searched:
  - keep the single-field parser path
- if multiple fields are searched:
  - parse the user query once per field using a field-specific `QueryParser`
  - wrap each field query with `NamedMatches.wrapQuery(fieldIri, fieldQuery)`
  - combine wrapped field queries in a `BooleanQuery` using `SHOULD`

This preserves document-level ranking while making field-level matches inspectable.

Conceptually:

```java
BooleanQuery.Builder bq = new BooleanQuery.Builder();
for (ResolvedField f : fields) {
    QueryParser qp = new QueryParser(f.luceneFieldName(), analyzer);
    Query fieldQuery = qp.parse(queryString);
    Query named = NamedMatches.wrapQuery(f.fieldIri(), fieldQuery);
    bq.add(named, BooleanClause.Occur.SHOULD);
}
Query query = bq.build();
```

If future requirements need more exact parity with `MultiFieldQueryParser`, this builder can be expanded. The important architectural point is that multi-field search must retain explicit field clause identity.

## Shared Execution Design

`luc:match` should reuse the same shared `SearchExecution` mechanism already used by `luc:query` and `luc:facet`.

Current `SearchExecution` stores:

- hits
- facet counts
- total hit count

It should be extended to store:

- a query-scoped list of hit records with stable hit ids
- per-hit field match details
- optional per-hit, per-field snippets when highlighting is requested

Recommended internal model:

```text
SearchHit
  hitId
  entityNode
  score
  totalHits
  graph?

FieldMatch
  hitId
  fieldIri
  valueNode?
  snippet?
```

This remains query-scoped, not globally materialized RDF.

## Join Semantics

Joining `luc:query` and `luc:match` on `?hit` is expected and correct.

This is not the same problem as joining `luc:query` and `luc:facet` without a shared variable.

Row growth is proportional to actual field-match multiplicity:

- roughly `sum(fieldMatchesPerHit)`

This may expand rows, but it is not a cartesian explosion between unrelated relations.

## Repo Classes To Change

### Likely core classes

- `jena-text/src/main/java/org/apache/jena/query/text/ShaclTextQueryPF.java`
- `jena-text/src/main/java/org/apache/jena/query/text/SearchExecution.java`
- `jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java`
- `jena-text/src/main/java/org/apache/jena/query/text/TextHit.java`
- `jena-text/src/main/java/org/apache/jena/query/text/TextQuery.java`

### New classes likely needed

- `LucMatchPF.java` or `TextMatchPF.java`
- `SearchHit.java`
- `FieldMatch.java`
- possibly a small helper class for resolved search fields:
  - field IRI
  - Lucene field name
  - field definition

### Tests likely needed

- `TestLucMatchPF`
- `TestShaclLucMatchSingleField`
- `TestShaclLucMatchMultiField`
- `TestShaclLucMatchHighlighting`
- `TestShaclLucMatchMatchAllDocs`
- `TestSearchExecution` updates for hit id and match caching

## Detailed Implementation Plan

### 1. Introduce a resolved field abstraction

Current code often resolves directly to Lucene field names. For `luc:match`, we need both:

- field IRI
- Lucene field name

Add a helper in `ShaclTextIndexLucene` or `ShaclIndexMapping` that resolves a requested field spec into a richer object rather than just a string list.

### 2. Replace opaque multi-field parsing in the SHACL query path

Keep the current single-field path for the simple case.

For multi-field:

- build one field-specific query per field
- wrap it with `NamedMatches.wrapQuery(fieldIri, q)`
- OR them together with `BooleanQuery`

This query object should become the canonical text query used by:

- top-doc search
- `Weight.matches(...)`
- optional highlighting

### 3. Extend `SearchExecution`

`SearchExecution` should own:

- the shared top-doc search result
- a stable hit list with hit ids
- optional lazy field-match computation
- optional lazy highlighting computation

Possible new methods:

- `List<SearchHit> getSearchHits(int limit, String highlight)`
- `List<FieldMatch> getFieldMatches()`
- `List<FieldMatch> getFieldMatchesForHit(String hitId)`

### 4. Compute field matches lazily from Lucene `Matches`

For each returned `ScoreDoc`:

- determine the correct `LeafReaderContext`
- convert the global doc id in `ScoreDoc.doc` to the segment-local doc id for that leaf
- build a `Weight` from the rewritten query using `IndexSearcher.createWeight(query, ScoreMode.COMPLETE, 1.0f)`
- call `weight.matches(leaf, segmentDocId)`
- call `NamedMatches.findNamedMatches(matches)`
- convert named match results to field IRIs

This match computation should happen only when `luc:match` or scalar `?field` actually requires it.

### 5. Rework scalar `?field` in `luc:query`

Scalar `?field` should remain a convenience binding only.

Suggested rule:

- if exactly one field match exists for a hit, bind it
- otherwise leave `?field` unbound

This preserves simple use while avoiding incorrect collapse of many-to-one match data.

### 6. Add `luc:match`

Implement a new PF that:

- reads the shared `SearchExecution`
- joins by `?hit`
- emits one row per field match
- optionally emits `?value` and `?snippet`

The PF must not trigger an independent Lucene search when a matching `SearchExecution` already exists.

### 7. Add optional highlighting

When a highlight option is present:

- compute matched fields first
- request snippets only for matched fields
- store snippets as part of `FieldMatch`

`UnifiedHighlighter` should be the primary API investigated first.

## Open Questions

### Hit id shape

The hit id should be query-scoped and stable within one execution. Options:

- a blank-node-like synthetic node
- a generated URI in an internal namespace
- a literal key

A generated internal URI or stable blank-node-like abstraction is sufficient. It does not need to be persistent across requests.

### Should `luc:match` require a search term?

Architecturally no, but product-wise top docs for bare `MatchAllDocsQuery` are weak. It is reasonable to document that UIs should avoid presenting ranked top docs until the user has entered a query or otherwise meaningfully narrowed the result set.

### Should field IRIs be emitted as predicates directly?

That can be supported as a projection layer over `FieldMatch`, but the search core should not pretend those triples are part of the source RDF graph. The clean semantic model is:

- hit node
- field match rows on the hit node

## Recommendation

Proceed with a new `luc:match` PF rather than trying to overload `luc:query`.

The recommended architecture is:

- `luc:query` returns hits
- `luc:facet` returns aggregations
- `luc:match` returns per-hit field matches
- shared Lucene execution is held in `SearchExecution`
- field attribution is computed with Lucene `Matches` / `NamedMatches`
- highlighting is optional and field-scoped
- field IRIs remain the public identifier

This is the cleanest way to support:

- field-level attribution
- optional snippets
- correct zero/one/many field multiplicity
- customer-facing flat models based on field IRIs

without sacrificing the entity-per-document architecture or relying on heuristics built on top of `TopDocs`.
