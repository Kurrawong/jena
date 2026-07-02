---
title: "nested sort selector design"
date: "2026-07-02"
---

# 2026-07-02 Nested Sort Selector Design

## Status

Proposed. Not yet implemented. This note records the design for ordering parent
(entity-per-document) search results by a value drawn from a specific nested
child record — e.g. "sort by the identifier value **where** identifierType =
companyID", or "sort by the agent name **where** attributionRole = owner".

## Problem

Results are entity-per-document. A single entity commonly carries repeated,
qualified child records:

- identifiers: `?s sdo:identifier [ sdo:propertyID ?type ; sdo:value ?val ]`
- prov attributions: `?s prov:qualifiedAttribution [ prov:agent ?a ; prov:hadRole ?role ]`

Consumers (Prez) pivot these into columns and want to sort the whole result set
by **one** of those columns — sort by the *companyID* identifier, or by the
*owner* agent — while keeping every entity in the result set and keeping
pagination correct over large result sets (1000+ hits).

The current sort spec (`SortSpec` / `SortSpecParser`) names a single flat field
on the parent doc. It cannot express "the value where type = X", because:

- flat multivalued fields are a decorrelated bag on the parent doc; the
  type↔value correlation is lost at flatten time,
- so the only available control is the MIN/MAX selector across the bag.

Doing the discrimination in SPARQL (`ORDER BY ?val` on a pivoted, `FILTER`ed
var) is not viable: the ordering would run in ARQ *after* `luc:query` has already
applied `limit`/`offset`, so it cannot reorder across the page boundary without
pulling the entire result set out of Lucene first.

## Design

The correlation we need is already preserved in the index: fields inside one
`idx:nested` block are emitted onto the **same child document** (block-join
layout, `_blockKind`, `_nestedScope`, `PARENTS_FILTER`). Lucene provides
`ToParentBlockJoinSortField` (present in `lucene-join-10.3.1`), which sorts
**parent** docs by a docvalues field on their **children**, restricted by a
child filter and collapsed with a MIN/MAX selector.

So "sort by identifierValue where identifierType = companyID" maps to:

> sort parents by the `identifierValue` docvalue, taken only from child docs
> where `identifierType = companyID`, MIN (asc) / MAX (desc) if more than one.

Key semantic distinction: the child predicate is a **sort selector**, not a
result filter. It chooses which child supplies the sort key; it must **not**
drop entities that have no matching child (those get a defined missing-value
placement instead). This is why it lives in the sort spec and not in
`cqlFilter`.

The selector is only meaningful for nested fields — on a flat field there is no
surviving per-value discriminator to test — so `filter` present is both
necessary and sufficient to select the block-join path. This makes the API
rule total and lets the scope be inferred rather than stated.

## SPARQL / API part

The sort spec is the 5th argument of `luc:query`
(`indexSelector fieldSpec queryString cqlFilter sortSpec limit offset`).
Extend the sort **object** (array-of-objects for multi-sort is unchanged):

Flat sort (unchanged, still valid):

```json
{ "field": "urn:jena:lucene:field#year", "order": "desc" }
```

Nested sort selector (new):

```json
{
  "field":  "urn:jena:lucene:field#identifierValue",
  "filter": { "field": "urn:jena:lucene:field#identifierType", "eq": "companyID" },
  "order":  "asc",
  "missing": "last"
}
```

- `field` — the child value field to sort on; must be `idx:sortable`.
- `filter.field` / `filter.eq` — the co-located discriminator on the same child
  doc.
- `order` — `asc` (default) / `desc`, as today.
- `missing` — optional, `first` | `last`; placement of entities with no matching
  child. Default `last`.
- `nested` scope is **inferred** from `field` and is **not** part of the public
  API.

Parser rules (`SortSpecParser`):

- `field` only → flat sort (today's behaviour).
- `field` + `filter` → nested block-join sort; infer scope via
  `findNestedDefForFieldName(field)`, validate `filter.field` resolves to a field
  in the **same** nested block.
- `filter` on a field that resolves to a flat (root) field → hard error:
  "sort filter requires a nested field".

Preset form (recommended for Prez, optional to implement): a profile may
pre-declare a named sort IRI that resolves to
`(valueField, filterField, filterValue)`, so the wire format stays the trivial
flat shape (`{"field":"urn:jena:lucene:sort#byCompanyId","order":"asc"}`) and the
client need not assemble the selector at query time. Inline form remains the
escape hatch for ad-hoc columns.

## Indexer part

No indexer/config code changes. The only requirement is data-modelling, taught by
example in the docs/config:

- the discriminator and the value must be `idx:property` occurrences of the
  **same** `idx:nested` block (so they land on one child doc),
- the value field must be `idx:sortable true` (single-valued per child →
  `SortedDocValues` / `SortedNumericDocValuesField`, already emitted by
  `addFieldToDoc`, which children share),
- the discriminator field must be `idx:indexed true` (queryable for the child
  filter `TermQuery`).

Example shape fragment:

```turtle
:sampleShape idx:nested [
    idx:joinPath sdo:identifier ;
    idx:property field:identifierType ;    # discriminator -> child filter
    idx:property field:identifierValue ;   # value         -> sort key
] .

field:identifierType
    idx:fieldName "identifierType" ; idx:fieldType idx:KeywordField ;
    idx:indexed true ; idx:facetable true ; sh:path sdo:propertyID .

field:identifierValue
    idx:fieldName "identifierValue" ; idx:fieldType idx:KeywordField ;
    idx:sortable true ; sh:path sdo:value .
```

## Proposed code changes

Small, additive, and on existing block-join rails. Detail left to implementer.

- `SortSpec` — carry the optional selector: value field (existing), plus
  `filterField`, `filterValue`, `missing`. Flat specs leave them null.
- `SortSpecParser` — parse `filter` / `missing`; infer scope; validate
  same-block and flat-field-error rules above.
- `ShaclTextIndexLucene.buildLuceneSort` — branch when a spec has a selector:
  emit `ToParentBlockJoinSortField(childValueField, type, reverse,
  PARENTS_FILTER, childFilterBitSet)` with `setMissingValue(...)`, instead of the
  plain `SortField`. Build `childFilterBitSet` from a `BooleanQuery` of
  `NESTED_SCOPE_FIELD = <scope>` MUST `filterField = filterValue` (same pattern
  already used for correlated nested search / `PARENTS_FILTER`).
- (Optional) profile/assembler — parse named sort presets.
- Tests — `TestNativeFacetCounts`-style: index entities with multiple identifier
  types, assert parent order by the companyID value asc/desc, and assert
  entities lacking a companyID land per `missing`. Add the new test class to
  `TS_Text.java`.

## Discussion

**Why block-join sort and not SPARQL `ORDER BY`.** Correct pagination requires
sorting *before* truncation to a page. `luc:query` pushes `limit`/`offset` down
into the Lucene search, so the sort must happen there too. A SPARQL `ORDER BY`
on a pivoted variable runs after the Lucene window is already cut, forcing a
fetch-everything-then-sort in ARQ that degrades with result size and defeats the
pushed-down pagination. It also introduces cartesian rows (one `?val` per child)
and undefined ordering for entities with no matching child.

**Why a sort selector and not a `cqlFilter` clause.** They answer different
questions. `cqlFilter` restricts *which entities appear*; putting
`identifierType = companyID` there would drop every entity lacking a companyID
identifier. The selector restricts *which child supplies the sort key* while
keeping all entities, placing the unmatched ones via the missing-value policy.
A caller that wants both "only companyID docs" and "sorted by it" states them
independently — filter in `cqlFilter`, order in the sort spec — and they compose.

**Why the selector is nested-only.** A flat multivalued field is a decorrelated
bag; flattening destroys the type↔value tuple, so there is no per-value
discriminator to test. The correlation only survives in the block-join child
doc. The one apparent exception (sort by the `@en` label only) is the same
pattern in disguise: a per-value discriminator that would itself require nesting
to filter on. Hence `filter` present ⟺ block-join path, and it is an error on a
flat field.

**Known edges (decide, then document).**

- *Selector on multiple matching children* — if an entity has two companyID
  identifiers, the parent key collapses MIN (asc) / MAX (desc). Normal case is
  one-per-type, so MIN = MAX.
- *Missing values* — entities with no matching child have no key; `missing`
  makes placement explicit rather than incidental.
- *Value cardinality* — a `sortable` keyword must be single-valued per child
  (one record → one value), else the child doc fails on duplicate docvalues.

**Out of scope.** Numeric observation pivots (sort samples by an `Au` grade
where the observable property is a *value*, not a distinct type/predicate) are
deliberately not covered here; that discrimination is by sibling value and is
being handled by SQL pushdown separately. This design targets the qualified
patterns whose discriminator is an explicit co-located property: identifiers and
prov-qualified attributions.
