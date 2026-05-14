# Issue: Text Search in CQL for Correlated Nested Queries

## Problem

PR 80 fixes same-child correlation for nested fields, but only when the relevant
clauses live in the same CQL subtree.

That creates an awkward split in the user model:

- structured constraints live in `cqlFilter`
- free-text search lives in `queryString`

For root-scoped text fields this is acceptable. For nested fields it is not,
because a user may need:

- `identifierType = "DOI"` and text/typeahead on `identifierValueText`
- `attributionRole = "Principal Investigator"` and text search on
  `attributionAgentText`

If one part is in `queryString` and the other is in `cqlFilter`, PR 80's
same-child fold does not apply. The result is that the system can still
over-match across sibling child documents.

## Why This Happens

The correlation model is correct at index time:

- fields inside the same `idx:nested` block are emitted onto the same child
  Lucene document
- `idx:joinPath` defines the nested scope
- query-time same-child correctness depends on building one combined child-scope
  query before lifting to the parent

That is straightforward when all relevant predicates are represented as one CQL
tree. It is not straightforward when text search is represented separately as
`queryString`.

## What OGC CQL2 Actually Provides

Checked against OGC CQL2 1.0.0, document `21-065r2`.

Relevant points:

- `like` is a pattern-matching operator, not a full-text operator.
- Its semantics are wildcard-based: `%` is the multi-character wildcard, `_` is
  the single-character wildcard, and `\` is the escape character.
- The standard also defines `between` and `in` as advanced comparison operators.
- It defines case/accent normalization helpers such as `CASEI`.
- It defines an extension point for custom functions, so implementations may add
  extra semantics beyond the built-in operators.

This means `like` is closer to SQL-style wildcard matching than to
Lucene/analyzer-driven search. It is not a good semantic fit for:

- tokenized text fields
- edge-ngram typeahead fields
- stemming/synonym/analyzer-driven search
- phrase-style or scored full-text search

## Design Tension

Today, Jena text effectively has two distinct query models:

1. Lucene text semantics in `queryString`
2. structured filter semantics in CQL

PR 80 exposes that this split is not just cosmetic. It leaks into correctness
for nested correlation.

Using `=` against analyzed text fields can work operationally in some cases
(notably edge-ngram + keyword-style query analyzer), but the semantics are not
clean:

- `=` reads as exact equality
- the implementation behavior is actually analyzer-mediated matching

That is defensible as a temporary bridge, but weak as a long-term language
design.

## Options

### Option 1: Reuse `like`

Pros:

- standard CQL2 operator
- no extension needed

Cons:

- semantics do not match Lucene text search
- poor fit for analyzer-based search
- likely confusing for users who expect wildcard matching, not token search
- weak fit for edge-ngram fields unless we redefine behavior in a surprising way

## Option 2: Keep text search outside CQL

Pros:

- minimal language change
- preserves current separation

Cons:

- nested correlated text queries remain awkward
- same-child correctness depends on user knowing to avoid `queryString`
- UI and API have to keep inventing special cases

## Option 3: Add a custom CQL text operator or function

Examples:

- operator style: `text_match(property, "term")`
- operator style: `match(property, "term")`
- function style nested inside a predicate if needed by the chosen grammar shape

Pros:

- one unified tree for structured and text constraints
- same-child correlation works naturally for nested text use cases
- semantics can explicitly mean "use the field's text analyzer/query analyzer"
- avoids pretending that analyzer-based matching is equality or SQL-style `like`

Cons:

- implementation-specific extension, not core OGC CQL2
- needs explicit documentation in API and user docs

## Proposed Direction

Prefer Option 3.

Specifically:

- keep `queryString` for simple top-level search UX
- add a Jena-specific CQL text operator/function for analyzer-driven matching
- use that operator whenever a text clause must participate in nested
  correlation

For example, the user intent:

- `identifierType = "DOI"`
- text/typeahead on `identifierValueText`

should become one CQL subtree conceptually like:

```json
{
  "op": "and",
  "args": [
    { "op": "=", "args": [ { "property": "identifierType" }, "DOI" ] },
    { "op": "text_match", "args": [ { "property": "identifierValueText" }, "10.1234" ] }
  ]
}
```

Likewise for qualified attribution:

```json
{
  "op": "and",
  "args": [
    { "op": "=", "args": [ { "property": "attributionRole" }, "Principal Investigator" ] },
    { "op": "text_match", "args": [ { "property": "attributionAgentText" }, "Sarah Jones" ] }
  ]
}
```

The important point is not the exact name `text_match`; it is that the operator
means analyzer-driven text matching and can live inside the same CQL subtree as
other nested clauses.

## Practical Short-Term Rule

Until a dedicated text operator exists:

- root-scoped text search can continue using `queryString`
- nested correlated text search should be expressed through `cqlFilter`
- using `=` on analyzer-driven fields may be acceptable as a temporary
  implementation detail, but should not be treated as ideal language semantics

## Follow-Up Questions

1. Should Jena define a custom CQL text operator, or a custom function?
2. Should that operator support:
   - analyzer-driven token matching
   - prefix/typeahead behavior on edge-ngram fields
   - phrase queries
   - boosting/scoring controls
3. Should `queryString` eventually compile into the same internal representation
   as the new CQL text operator for consistency?
4. Do we want a documented rule that correlated nested text constraints must be
   fully representable in CQL, even if top-level full-text search remains
   separate?

## Sources

- OGC CQL2 standard overview: https://www.ogc.org/standards/cql2/
- OGC CQL2 1.0.0 standard text: https://docs.ogc.org/is/21-065r2/21-065r2.html
  - `like` semantics: lines 1017-1074 in the current HTML rendering
  - case-insensitive comparison notes: lines 1178-1181
  - custom functions extension point: lines 2116-2148
