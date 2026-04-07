---
title: "nested hierarchical faceting design"
date: "2026-04-07"
---

# 2026-04-07 Nested Hierarchical Faceting Design

## Status

This note records the proposed direction for fixing hierarchical facets over correlated repeated child records.

It does not change the existing user docs yet. It is a design note for the follow-up work after the current range-facet branch.

## Problem

The current `idx:facetHierarchy` model is sufficient for direct hierarchies where all levels are read independently from the entity root and no repeated intermediate node is involved.

That is why the current implementation works for examples such as:

- `state -> commodity`
- other direct parent-level hierarchies where the entity itself carries the values

It is not sufficient for correlated repeated child records such as:

- identifiers: `entity sdo:identifier _:id . _:id sdo:propertyID X ; sdo:value Y`
- location assessments: `doc pe:hasLocationAssessment _:a . _:a pe:method M ; pe:status S ; pe:resolvedFeature F`
- observations, qualified attributions, and similar tuple-shaped blank nodes

The core missing concept is not "hierarchy" but "repeating correlated child record".

## Proposed Public Model

Add an explicit nested child-record construct:

- `idx:nested` declares a repeated child collection on a shape
- `idx:joinPath` identifies how to enumerate child nodes from the parent entity
- `idx:property` references field definitions that are evaluated relative to the child node
- `idx:facetHierarchy` inside the nested block defines a hierarchy over those child fields

This gives the assembler and indexer enough information to:

- enumerate child records correctly
- build correlated taxonomy tuples without cartesian products
- support exact drill-down counts now
- preserve a stable public model that can later be backed by block join

## Identifier Example

### Field Definitions

The identifier example uses dual fields for the value:

- one exact `KeywordField` for hierarchy paths and exact filters
- one analyzed `TextField` for prefix search / typeahead

```turtle
@prefix idx:   <urn:jena:lucene:index#> .
@prefix sh:    <http://www.w3.org/ns/shacl#> .
@prefix field: <urn:jena:lucene:field#> .
@prefix sdo:   <https://schema.org/> .

field:identifierType
    idx:fieldName "identifierType" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path sdo:propertyID .

field:identifierValueExact
    idx:fieldName "identifierValueExact" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path sdo:value .

field:identifierValueText
    idx:fieldName "identifierValueText" ;
    idx:fieldType idx:TextField ;
    idx:analyzer <#edgeNgramAnalyzer> ;
    idx:queryAnalyzer <#keywordAnalyzer> ;
    sh:path sdo:value .
```

### Shape Definition

```turtle
<#BoreholeShape>
    sh:targetClass ex:Borehole ;
    idx:nested [
        idx:joinPath sdo:identifier ;
        idx:property field:identifierType ;
        idx:property field:identifierValueExact ;
        idx:property field:identifierValueText ;
        idx:facetHierarchy ( field:identifierType field:identifierValueExact ) ;
    ] .
```

### Intended Semantics

For each `sdo:identifier` child node:

- `field:identifierType` is evaluated relative to that child
- `field:identifierValueExact` is evaluated relative to that child
- `field:identifierValueText` is evaluated relative to that child

For faceting, each child emits one correlated hierarchy tuple:

```text
(identifierType, identifierValueExact)
```

For example:

```turtle
ex:b1 sdo:identifier _:i1, _:i2 .
_:i1 sdo:propertyID ex:Company ;   sdo:value "BHP" .
_:i2 sdo:propertyID ex:HoleNumber ; sdo:value "8412" .
```

produces hierarchy paths equivalent to:

```text
(Company, "BHP")
(HoleNumber, "8412")
```

and not the cartesian product:

```text
(Company, "8412")
(HoleNumber, "BHP")
```

## What This Solves Now

With `idx:nested` and correlated taxonomy population, the system can correctly support:

- top-level counts for identifier types
- drill-down counts for identifier values within a selected type
- exact same-hierarchy filtering when multiple `=` filters on the same hierarchy are compiled into one hierarchy-path query

This is the right fix for qualified-identifier style faceting.

## What This Does Not Solve Yet

Without block join, nested text fields remain parent-flattened at query time.

That means `field:identifierValueText` can still be used for:

- entity search by identifier prefix
- typeahead-like entity retrieval with a small debounce
- broad search over all identifier values on an entity

But it does not yet guarantee same-child correlation for mixed text + sibling-field queries such as:

- `identifierType = HoleNumber`
- and `identifierValueText = 841*`

Those semantics require child-level querying, which is the point where block join becomes necessary.

## Non-Identifier Example: Direct Hierarchies

The current `state -> commodity` example is still valid and does not need `idx:nested`.

There is no repeated intermediate child record to correlate. The entity itself already carries the fields directly.

### Direct Shape-Level Hierarchy

```turtle
field:state
    idx:fieldName "state" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path ex:state .

field:commodity
    idx:fieldName "commodity" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path ex:commodity .

<#SiteShape>
    sh:targetClass ex:Site ;
    sh:property field:state ;
    sh:property field:commodity ;
    idx:facetHierarchy ( field:state field:commodity ) .
```

### Rule

- direct hierarchies stay as ordinary shape-level `idx:facetHierarchy`
- they do not need `idx:joinPath`
- they do not need `idx:nested`

So the new model is additive:

- use ordinary shape-level hierarchy for direct entity fields
- use `idx:nested` only when the hierarchy levels belong to a repeated correlated child record

## Another Nested Example: Location Assessments

```turtle
field:assessmentMethod
    idx:fieldName "assessmentMethod" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path pe:method .

field:assessmentStatus
    idx:fieldName "assessmentStatus" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path pe:status .

field:assessmentFeatureExact
    idx:fieldName "assessmentFeatureExact" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path pe:resolvedFeature .

field:assessmentFeatureText
    idx:fieldName "assessmentFeatureText" ;
    idx:fieldType idx:TextField ;
    idx:analyzer <#edgeNgramAnalyzer> ;
    idx:queryAnalyzer <#keywordAnalyzer> ;
    sh:path pe:resolvedFeature .

<#DocumentShape>
    sh:targetClass ex:Document ;
    idx:nested [
        idx:joinPath pe:hasLocationAssessment ;
        idx:property field:assessmentMethod ;
        idx:property field:assessmentStatus ;
        idx:property field:assessmentFeatureExact ;
        idx:property field:assessmentFeatureText ;
        idx:facetHierarchy (
            field:assessmentMethod
            field:assessmentStatus
            field:assessmentFeatureExact
        ) ;
    ] .
```

This is the same structural pattern as identifiers:

- the hierarchy belongs to a repeated child record
- the exact leaf field is used in the taxonomy
- the text field exists for broader search use cases

## Why `idx:nested` Is Better Than a Hierarchy-Specific Join Hack

An `idx:hierarchyJoinPath` property would be enough to repair the current hierarchical facet bug, but it would make the model hierarchy-specific.

`idx:nested` is broader and cleaner because it says:

- this shape has a repeated child collection
- these fields belong to that child collection
- one or more hierarchies may be defined inside that collection

That is the correct public concept even before block join exists.

## Future Block Join Support

The main reason to prefer `idx:nested` is that it preserves a stable public model while allowing two internal execution strategies over time.

### Phase 1: Correlated Taxonomy on Parent Documents

Initial support can keep the current one-parent-document-per-entity model:

- child records are enumerated via `idx:joinPath`
- hierarchy tuples are emitted correctly into the taxonomy
- nested text fields are still flattened onto the parent Lucene document

This is enough for:

- correct nested hierarchical facets
- correct exact drill-down counts
- correct exact hierarchy-path filtering where the query compiler can fold same-hierarchy equality constraints

### Phase 2: Block Join Using the Same Public Config

Later, the same `idx:nested` model can be backed by Lucene parent/child blocks:

- the entity becomes the parent document
- each nested child record becomes a child document
- child-field text search and sibling-field exact filters can run at child scope
- matching parents are lifted via block join

Under that model:

- `field:identifierValueText` becomes a true child field rather than a parent-flattened approximation
- text search within a chosen identifier type becomes same-child correct
- per-child match attribution becomes possible

The key design requirement is that `idx:nested` must describe the data model, not the temporary storage strategy.

## Proposed Rules

The intended rules for the new model are:

- `idx:nested` is optional and additive
- `idx:joinPath` is required inside `idx:nested`
- fields named by `idx:property` are evaluated relative to the child node reached by `idx:joinPath`
- `idx:facetHierarchy` may appear either:
  - on the shape itself for direct entity fields, or
  - inside `idx:nested` for child-relative fields
- exact hierarchy levels should use `KeywordField`
- analyzed search variants should be modeled as separate `TextField` definitions on the same child property

## Recommendation

Adopt `idx:nested` as the public fix for hierarchical facets over correlated child records.

Keep the existing shape-level `idx:facetHierarchy` for direct root-level hierarchies such as `state -> commodity`.

Do not introduce block join into the public configuration yet. Instead:

- define the nested public model now
- implement correlated hierarchy support first
- leave room for a later block-join execution layer using the same configuration
