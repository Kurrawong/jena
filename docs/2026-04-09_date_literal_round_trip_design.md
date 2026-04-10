# Date Literal Round-Trip and Literal Metadata Storage

Date: 2026-04-09

## Context

The current SHACL Lucene indexing path does not preserve general RDF literal metadata for indexed values.

Current behavior:

- `KEYWORD` values are stored as strings and, on read, may be re-emitted as IRIs if the stored string "looks like" a URI
- `TEXT` values are re-emitted as plain string literals
- `INT`, `LONG`, and `DOUBLE` values are re-emitted using the configured Lucene field type, not the original RDF datatype
- there is no dedicated `DATE` or `DATETIME` field type
- CQL range filtering and `between` only compile to Lucene queries for numeric field types
- residual CQL expressions are currently logged and ignored, not post-filtered in Java

This means ISO date literals stored as strings do not currently support real Lucene-backed range filtering, and typed literal fidelity is not preserved for round-trip output.

## Problem

We want:

- correct range filtering for RDF date-like literals
- exact round-trip output fidelity for `luc:match` and related value-returning paths
- a design that avoids heuristic reconstruction from stored strings
- a solution that does not require reading values back from the source graph for every hit

The graph-lookup approach has some appeal, but it has important downsides:

- ambiguous reconstruction for multi-valued fields
- poor fit for `luc:match`, where we want the value that matched the Lucene document snapshot
- possible drift between index state and graph state
- extra query-time cost
- nested field reconstruction becomes difficult or ambiguous

So the preferred direction is to keep round-trip data in Lucene, but only for indexed fields and only for the term metadata actually needed.

## Recommended Direction

### 1. Add explicit literal metadata storage

Introduce a field-level option to preserve RDF literal metadata for indexed values.

Preferred public shape:

- `idx:storeLiteralMetadata true`

Semantics:

- if the indexed value is a typed literal, store its datatype URI
- if the indexed value is a language-tagged literal, store its language tag
- if the indexed value is a plain literal, store no extra literal metadata
- if the indexed value is an IRI, store no literal metadata

This is preferable to separate public flags such as `storeDatatype` and `storeLangTag` unless we have a real use case for independent control. One flag is easier to explain and reason about.

### 2. Add dedicated date-like field handling

For proper range behavior, metadata storage alone is not enough. Date-like fields also need a normalized numeric companion representation for Lucene query execution.

Preferred model:

- keep the original lexical form for round-trip output
- keep datatype metadata for reconstruction
- store a numeric companion field for range, sort, and facet operations

For example, for RDF field `eventDate`, Lucene storage could include:

- `eventDate` — stored lexical string
- `eventDate__datatype` — stored datatype URI
- `eventDate__epoch` — numeric companion for query/range/sort/facet

This is intentionally a dual-representation design:

- lexical representation for exact round-trip fidelity
- numeric representation for efficient typed query behavior

## Why Not Pack Everything Into One Stored String

A packed single-field representation such as `lexical|datatype|lang` is possible, but is not preferred.

Downsides:

- escaping and separator handling
- harder debugging
- harder evolution if more metadata is needed later
- awkward query semantics if the same field is also used for matching

If we want a compact format, a better alternative is:

- one opaque stored serialized RDF term payload for round-trip
- separate indexed Lucene companion fields for query behavior

However, separate Lucene fields are the clearest first implementation.

## Equality and Range Semantics

We should avoid split semantics where equality uses lexical matching but range uses numeric matching.

Recommended date-like semantics:

- `=`, `<`, `<=`, `>`, `>=`, and `between` operate on the normalized numeric companion field
- returned values still come from the stored lexical form plus stored datatype metadata
- optional lexical equality can be added later if there is a concrete requirement for strict lexical matching rather than value equality

This keeps query behavior coherent.

## Temporal CQL Operators

If we later add temporal CQL operators such as:

- `t_before`
- `t_after`
- `t_during`
- `t_meets`
- `t_overlaps`

they should be treated as higher-level temporal predicates over normalized temporal values, not as something Lucene understands directly.

Lucene itself only gives us lower-level query primitives such as:

- numeric exact queries
- numeric range queries
- boolean composition (`MUST`, `SHOULD`, `MUST_NOT`)

So the implementation model should be:

1. normalize date-like values into comparable numeric values
2. lower each temporal operator into the corresponding Lucene boolean/range query combination

For example, conceptually:

- `t_before(a, b)` lowers to a comparison equivalent to `a_end < b_start`
- `t_after(a, b)` lowers to a comparison equivalent to `a_start > b_end`
- `t_during(a, b)` lowers to a conjunction equivalent to `a_start >= b_start && a_end <= b_end`

### Do Not Rewrite Temporal Ops Back Into Lower-Level CQL

There is little benefit in rewriting temporal operators into simpler CQL first.

Reasons:

- the current execution model targets Lucene directly
- residual CQL is currently ignored rather than evaluated post-Lucene
- temporal operators are effectively frontend sugar over Lucene boolean/range queries

So the preferred approach is:

- parse temporal operators as dedicated operations
- compile them directly into Lucene query objects
- keep any normalization/lowering logic internal to the Lucene compiler rather than exposing a second rewritten CQL layer

This is the simplest fit for the current codebase.

## Suggested Datatype Scope

First pass:

- `xsd:date`
- `xsd:dateTime`

Possible later extensions:

- `xsd:gYear`
- `xsd:gYearMonth`

The first implementation should stay narrow and explicit.

## Normalization Rules

### `xsd:dateTime`

Parse to an instant and store epoch milliseconds in UTC.

### `xsd:date`

Treat as a calendar date and store **UTC midnight epoch milliseconds**.

Using milliseconds for both `xsd:date` and `xsd:dateTime` allows a single shared numeric representation type and simplifies cross-type comparisons.

### Timezones and Validation

- Literals without a timezone offset are treated as UTC.
- If an indexed value fails date parsing, the numeric `__epoch` companion is omitted (making it un-searchable by range), but the lexical form and metadata are still stored.

### Round-trip

Round-trip output must not be reconstructed from the normalized numeric value alone.

Instead, emit:

- original lexical form
- original datatype URI

This preserves the user's original representation and avoids timezone/formatting loss.

## Multi-valued Fields

For multi-valued date fields, the implementation must ensure consistent alignment between the stored lexical values, the metadata fields (`__datatype`), and the numeric companion fields (`__epoch`). This ensures that the N-th value returned in a result set correctly corresponds to its original datatype and numeric value.

## Proposed Public Configuration Shape

Minimum recommended additions:

- `idx:storeLiteralMetadata true`
- dedicated date-like field types:
  - `idx:DateField`
  - `idx:DateTimeField`

### Configuration Enforcement

To ensure round-trip fidelity, `idx:DateField` and `idx:DateTimeField` **require** `idx:storeLiteralMetadata true` to be enabled for that field (or globally). If a date-like field is configured without literal metadata storage, the configuration should be rejected at startup.

## Internal Representation Proposal

For a date-like configured field:

1. Preserve original lexical form in the stored primary field
2. Preserve datatype metadata in a companion stored field
3. Write a numeric companion field for query/range/sort/facet behavior (UTC epoch millis)

Example companion naming:

- `<field>`
- `<field>__datatype`
- `<field>__lang`
- `<field>__epoch`

## Implementation Outline

### Phase 1: literal metadata support

- extend field definition/configuration with `idx:storeLiteralMetadata`
- preserve datatype URI and language tag alongside stored lexical values when configured
- update result emission paths to reconstruct RDF literals from stored metadata rather than field-type-only heuristics

### Phase 2: date-like field support

- add explicit date-like field types
- parse RDF date-like literals into normalized numeric companion values (UTC epoch millis)
- compile date comparisons and `between` against the numeric companion field
- keep result output sourced from stored lexical value + stored datatype metadata
- enforce `storeLiteralMetadata` for these types

### Phase 3: tests and docs

Add coverage for:

- round-trip of `xsd:date`
- round-trip of `xsd:dateTime`
- date range queries using `between`
- date comparisons using `>=`, `<=`, `<`, `>`
- behavior when date parsing fails
- behavior for language-tagged literals when metadata storage is enabled

## Migration and Compatibility

No backwards compatibility is required for this change. Existing indexes may need to be deleted and recreated to take advantage of the new date-like field types and metadata storage.

## Open Design Questions

1. Should date equality be semantic only, or should we also support explicit lexical equality later?
2. Should we preserve IRI-vs-literal term kind explicitly in metadata, or continue relying on field structure plus literal metadata for now?
3. If temporal CQL operators are added, should all date-like fields be modeled internally as degenerate intervals (`start == end`) to keep instant and interval logic uniform?

## Recommendation

Adopt the following as the baseline design:

- add `idx:storeLiteralMetadata true`
- add explicit date-like field types and **enforce** metadata storage for them
- store date-like values in two representations:
  - lexical form for exact round-trip
  - numeric companion (UTC epoch millis) for query behavior
- if temporal CQL operators are added, compile them directly to Lucene boolean/range queries
- model instants as degenerate intervals for future temporal operator support
- reconstruct result literals from stored lexical form plus stored datatype/lang metadata
