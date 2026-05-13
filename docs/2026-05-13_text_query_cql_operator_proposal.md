# Issue: Add `text_query` to CQL for Analyzer-Aware Correlated Text Search

## Problem

PR 80 fixes same-child correlation for nested fields, but only when all relevant
clauses live in the same CQL subtree.

That exposes an awkward split in the current query model:

- text search lives in the separate `luc:query` text input
- structured constraints live in `cqlFilter`

This is acceptable for root-scoped text search, but it breaks down for nested
business cases where text must be correlated with sibling fields on the same
blank node.

Examples:

- `schema:identifier`:
  - `propertyID = "DOI"`
  - text/typeahead on `value`
- `prov:qualifiedAttribution`:
  - `hadRole = "Principal Investigator"`
  - text search on `agent`

If one clause is in `queryString` and the other is in `cqlFilter`, PR 80's
same-scope fold does not apply, so same-child correctness is lost.

## Confirmed Gap in Current Implementation

The current compiler path for CQL `=` on `TEXT` fields is not analyzer-aware.

Code:

- [CqlToLuceneCompiler.java](../jena-text/src/main/java/org/apache/jena/query/text/cql/CqlToLuceneCompiler.java)

Current behavior:

- `=` on `KEYWORD` and `TEXT` both compile to `new TermQuery(new Term(field, value))`
- no query analyzer is applied for `TEXT`
- no case normalization is applied

Live demo verification against `demo/test/Lucene`:

- `identifierValueText` is indexed with `EdgeNGramAnalyzer` and lowercased
- raw Lucene terms include `a-94` and `a-9412`
- `TermQuery(identifierValueText, "a-94")` matches
- `TermQuery(identifierValueText, "A-94")` does not

So today:

- same-child folding in PR 80 is working
- but `=` on `TEXT` does **not** mean analyzer-mediated text matching
- using `=` for correlated nested text is therefore semantically weak and
  operationally brittle

## Why `like` Is Not the Right Fix

OGC CQL2 `like` is wildcard-pattern matching, not full-text search.

It uses:

- `%` for multi-character wildcard
- `_` for single-character wildcard
- `\` as escape

That is closer to SQL wildcard semantics than to Jena/Lucene analyzer semantics.
It is a poor fit for:

- tokenized text fields
- edge-ngram typeahead
- stemming or synonym analyzers
- general Lucene query parsing behavior

## Proposed Direction

Add a Jena-specific CQL text operator:

- preferred name: `text_query`

Reasoning on naming:

- Jena already has `luc:query` and `luc:match`
- `text_query` reads as "use text-query semantics on this property"
- it is clearer than `text_match`, which risks overlap with `luc:match`

## Proposed Semantics

`text_query(property, text)` means:

- run analyzer-aware text matching against exactly one property
- use the field's configured query analyzer
- compile to the same family of Lucene query used by the normal text input path
- allow the clause to participate inside a larger CQL tree

This lets correlated nested searches stay fully inside one CQL subtree.

Example:

```json
{
  "op": "and",
  "args": [
    { "op": "=", "args": [ { "property": "identifierType" }, "anumber" ] },
    { "op": "text_query", "args": [ { "property": "identifierValueText" }, "A-94" ] }
  ]
}
```

And:

```json
{
  "op": "and",
  "args": [
    { "op": "=", "args": [ { "property": "attributionRole" }, "Principal Investigator" ] },
    { "op": "text_query", "args": [ { "property": "attributionAgentText" }, "Sarah Jones" ] }
  ]
}
```

In both cases the text clause can live in the same nested-scope `and` subtree as
its sibling constraint, so PR 80's same-child fold remains applicable.

## Relation to Existing `luc:query`

`text_query(property, text)` should be the property-scoped analogue of the
current "regular" text search input.

That means:

- same analyzer/query-analyzer semantics
- same broad Lucene matching family
- but scoped to one property so it can participate inside CQL

For multi-field search inside CQL, the surface language can stay simple:

```json
{
  "op": "or",
  "args": [
    { "op": "text_query", "args": [ { "property": "title" }, "gold mine" ] },
    { "op": "text_query", "args": [ { "property": "description" }, "gold mine" ] }
  ]
}
```

Compiler optimization can then collapse this into the same Lucene query shape as
today's normal multi-field path:

- root-scoped fields -> one multi-field Lucene query
- same nested scope -> one inner multi-field Lucene query wrapped once in
  `ToParentBlockJoinQuery`

So the surface syntax can remain explicit, while the compiled Lucene query stays
efficient.

## Recommended Implementation Path

### Phase 1: Immediate Bug Fix

Fix the current compiler so `=` on `TEXT` is not a raw `TermQuery`.

Options:

- analyzer-normalize the value before building the term query
- or compile `=` on `TEXT` through the same analyzer-aware query builder used by
  the normal text path

This is the pragmatic fix for the current broken demo/query behavior.

### Phase 2: Add `text_query`

Introduce `text_query` as the explicit analyzer-aware operator for `TEXT` fields.

Suggested policy:

- `=` remains exact for `KEYWORD`, numeric, temporal
- `text_query` becomes the preferred form for analyzer-backed text fields
- nested correlated text examples and UI should use `text_query`

### Phase 3: Compatibility Decision

Decide whether `=` on `TEXT` should:

1. remain analyzer-aware for backward compatibility, or
2. be discouraged/deprecated in favor of `text_query`

The cleaner long-term language design is (2), but (1) may be useful to avoid
breaking existing CQL callers.

## Open Questions

1. Should `text_query` support full Lucene query-string syntax, or just analyzer
   processed plain text?
2. Should it expose phrase/proximity/boosting later, or keep the first version
   intentionally minimal?
3. Should the existing top-level `queryString` path eventually compile through
   the same internal builder as `text_query` for consistency?
4. Should `like` still be supported on `TEXT` fields purely as wildcard matching,
   distinct from analyzer-mediated text search?

## Recommendation

Preferred end state:

- add `text_query`
- keep CQL exact semantics clean
- use `text_query` for nested correlated text
- optimize `or` of `text_query` clauses to the same Lucene form as normal
  multi-field search

Preferred immediate step:

- fix the current `=` on `TEXT` bug so the live behavior at least matches user
  expectations until `text_query` lands
