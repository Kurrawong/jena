---
title: "shared field occurrence design"
date: "2026-04-09"
---

# 2026-04-09 Shared Field Occurrence Design

## Status

This note proposes a simplification of the current SHACL-driven field model.

The core change is to separate:

- the canonical field identity used by queries and UI
- the per-shape occurrence that describes how a value is reached

## Problem

The current model makes one resource do too many jobs at once.

Today a field resource effectively serves as all of the following:

- the public field IRI
- the Lucene field definition
- the path definition
- the shape-local occurrence

That works for simple cases, but it becomes awkward when many different shapes should contribute values to the same logical field through different paths.

Example intent:

- a `Survey` child reaches its parent borehole via `^schema:about`
- a `Well` child reaches its parent borehole via `dcterms:hasPart`
- both should populate one shared field such as `field:hasParent`

In the current model, reusing one field IRI for multiple path definitions is not natural because the parser and mapping assume the field definition itself owns the path.

## Proposed Model

Split the model into two layers.

### 1. Canonical field resource

The canonical field resource defines the shared identity and shared Lucene/index behaviour.

Example:

```turtle
@prefix idx:   <urn:jena:lucene:index#> .
@prefix field: <urn:jena:lucene:field#> .

field:hasParent
    idx:fieldName "hasParent" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    idx:stored true ;
    idx:multiValued true .
```

This resource is the thing the query layer refers to.

### 2. Property occurrence node

Each `sh:property` is a shape-local occurrence that says how that shape populates the canonical field.

Example:

```turtle
@prefix sh:     <http://www.w3.org/ns/shacl#> .
@prefix ex:     <http://example.org/> .
@prefix schema: <https://schema.org/> .
@prefix dcterms: <http://purl.org/dc/terms/> .

shape:SurveyShape
    sh:targetClass ex:Survey ;
    sh:property [
        idx:field field:hasParent ;
        sh:path [ sh:inversePath schema:about ] ;
        sh:class ex:Borehole ;
    ] .

shape:WellShape
    sh:targetClass ex:Well ;
    sh:property [
        idx:field field:hasParent ;
        sh:path dcterms:hasPart ;
        sh:class ex:Borehole ;
    ] .
```

This means:

- `field:hasParent` is the shared field identity
- each blank node is one field occurrence
- the occurrence supplies the path
- optional occurrence-level constraints restrict which reached nodes are indexed

## What The New Design Supports

This model supports:

- one canonical field being populated from many different shapes
- different `sh:path` values per contributing shape
- reuse of shared field metadata such as type, analyzers, faceting, and storage flags
- occurrence-level filtering of path endpoints through simple supported constraints
- a stable public field IRI even when the underlying paths differ

This model makes it natural to describe a field as a reusable concept instead of as a single path-bound definition.

## Example: Fan-in to a Shared `hasRelated` Field

This example shows two shapes with different target classes and different paths, each contributing to one shared keyword field.

The field concept is "related objects" — a loose grouping that means something different per shape but should be queryable and facetable as a single dimension.

### Canonical field

```turtle
@prefix idx:   <urn:jena:lucene:index#> .
@prefix field: <urn:jena:lucene:field#> .

field:hasRelated
    idx:fieldName "hasRelated" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable  true ;
    idx:stored     true ;
    idx:multiValued true .
```

The field stores the IRI of each related resource. It is declared once and is shared across all shapes that reference it.

### Shapes

```turtle
@prefix sh:    <http://www.w3.org/ns/shacl#> .
@prefix idx:   <urn:jena:lucene:index#> .
@prefix field: <urn:jena:lucene:field#> .
@prefix ex:    <http://example.org/> .

shape:ArticleShape
    sh:targetClass ex:Article ;
    sh:property [
        idx:field field:hasRelated ;
        sh:path   ex:cites ;
        sh:class  ex:Report ;
    ] .

shape:ProjectShape
    sh:targetClass ex:Project ;
    sh:property [
        idx:field field:hasRelated ;
        sh:path   ex:involves ;
        sh:class  ex:Organisation ;
    ] .
```

`ex:Article` entities reach related resources via `ex:cites` and keep only those of class `ex:Report`.

`ex:Project` entities reach related resources via `ex:involves` and keep only those of class `ex:Organisation`.

Both occurrences write surviving IRIs into the same `hasRelated` Lucene field.

### Example data

```turtle
ex:article1  a ex:Article ;
    ex:cites ex:report1, ex:dataset1 .

ex:report1   a ex:Report .
ex:dataset1  a ex:Dataset .

ex:project1  a ex:Project ;
    ex:involves ex:org1, ex:person1 .

ex:org1      a ex:Organisation .
ex:person1   a ex:Person .
```

### What gets indexed

| Document | Field | Indexed values | Notes |
|---|---|---|---|
| `ex:article1` | `hasRelated` | `ex:report1` | `ex:dataset1` dropped — not `sh:class ex:Report` |
| `ex:project1` | `hasRelated` | `ex:org1` | `ex:person1` dropped — not `sh:class ex:Organisation` |

`ex:dataset1` and `ex:person1` are reachable via the configured paths but do not satisfy the `sh:class` constraint on their respective occurrences, so they are not indexed.

### Querying

A filter query finds all entities related to a specific resource, regardless of which shape produced the relationship:

```sparql
PREFIX luc: <urn:jena:lucene:>

SELECT ?hit ?entity WHERE {
    (?hit ?entity ?score ?totalHits)
        luc:query (
            "default"
            "default"
            ""
            '{"op":"=","args":[{"property":"urn:jena:lucene:field#hasRelated"},
                               "http://example.org/report1"]}'
            ""
            -1
        ) .
}
```

This returns `ex:article1`. Filtering by `ex:org1` would return `ex:project1`. In both cases the query is identical in shape — only the filter value changes. The query has no knowledge of which shape or path produced the value.

A facet query over the same field returns counts across all contributing shapes at once:

```sparql
PREFIX luc: <urn:jena:lucene:>

SELECT ?value ?count WHERE {
    luc:facet (
        "default"
        "default"
        ""
        ""
        "urn:jena:lucene:field#hasRelated"
        -1
    ) ?value ?count .
}
```

This would return `ex:report1` and `ex:org1` as facet values with a count of 1 each, even though they come from entirely different shapes and paths.

## Relationship To Nested Child Scopes

This model does not replace the nested child-record design.

The two designs address different concerns:

- the shared-field model separates canonical field identity from occurrence/path
- the nested model separates root scope from correlated child scope

Those concerns compose cleanly.

Use the shared-field model to define what a field is.

Use `idx:nested` when a shape needs to index a repeated correlated child record such as:

- identifiers
- qualified attributions
- observations
- location assessments

In other words:

- canonical fields define Lucene behaviour and public field identity
- occurrences define how a scope populates a canonical field
- `idx:nested` defines an additional child scope relative to the parent document

## Relationship To Hierarchies

Hierarchies should remain derived scope-level definitions, not separate extracted fields.

That means the merged model is:

- canonical fields are the reusable field definitions
- occurrences populate those canonical fields in either root scope or nested scope
- `idx:facetHierarchy` defines a hierarchy over canonical fields that already exist in that scope

This is important because a hierarchy is not just another field occurrence.

It does not introduce a new extracted value. Instead it defines how correlated field values should be emitted into the taxonomy index for drill-down and counting.

### Recommendation

Do not model the hierarchy as a fourth peer field under `sh:property`.

For the identifier case, keep:

- one keyword field for the top-level identifier kind, such as company/anumber/mnumber
- one keyword field for the identifier value used for drill-down and exact filtering
- one text field for the identifier value used for prefix search / typeahead
- one hierarchy definition over the two keyword fields

So the identifier pattern is best understood as:

- three canonical fields
- one derived hierarchy dimension

and not:

- four separate fields

## Why `idx:joinPath` Should Remain Distinct

`idx:joinPath` and `sh:path` may look similar, but they do different jobs.

- `idx:joinPath` establishes a correlated child scope
- `sh:path` extracts field values within the current scope

That distinction should remain explicit.

For example, in the identifier case:

- `idx:joinPath schema:identifier` means "enumerate identifier child records"
- `sh:path schema:propertyID` means "within one identifier child, extract the type"
- `sh:path schema:value` means "within one identifier child, extract the value"

Using `sh:path` on `idx:nested` would blur the difference between:

- entering a repeated correlated child-record context
- extracting one field value from the current context

Keeping `idx:joinPath` distinct also matches the current implementation model:

- nested indexing iterates child records first
- change propagation treats join paths specially
- future block-join execution will also need that distinction

So the recommended split is:

- `idx:joinPath` for scope creation
- `sh:path` for field occurrences

## Why Move To This Model

The proposed split improves both the public RDF model and the implementation model.

Benefits:

- the canonical field resource becomes reusable
- path definitions become local to the shape that needs them
- the public field identity stops being tied to one specific path
- the parser no longer has to overload one RDF node with both reusable metadata and local traversal rules
- the design aligns more closely with SHACL property-shape structure

This also avoids the awkward current situation where reusing one field identity across several path variants feels like trying to reuse the wrong abstraction.

## Occurrence Constraints

The occurrence node is the right place to attach path-endpoint restrictions such as:

- `sh:class`
- `sh:nodeKind`
- `sh:datatype`

These should be interpreted as index-time filtering constraints, not as full SHACL validation.

These three constraints should be supported in the initial implementation.

Reason:

- they are local to the reached node or value
- they are simple to evaluate during indexing
- they greatly improve practical expressiveness for fan-in fields
- they avoid the much larger complexity jump that comes with nested shape evaluation

Example:

```turtle
sh:property [
    idx:field field:hasParent ;
    sh:path [ sh:inversePath schema:about ] ;
    sh:class ex:Borehole ;
] .
```

This means:

- evaluate the path
- keep only reached nodes that satisfy the supported constraints
- index the surviving values into the shared field

### Recommendation On `sh:node`

Full `sh:node` support should not be part of the first implementation.

Reason:

- `sh:class` and similar simple constraints are local and cheap
- `sh:node` implies nested shape evaluation
- that significantly complicates both indexing and incremental change propagation

Initial support should include:

- `sh:class`
- `sh:nodeKind`
- `sh:datatype`

and should remain limited to constraints that can be checked locally.

## Current Model Versus Proposed Model

### Current model

A field resource owns:

- `idx:fieldName`
- `idx:fieldType`
- analyzers and flags
- `sh:path`
- implicit field IRI

That coupling is reflected in the parser:

- [`ShaclIndexAssembler.java#L351`](/home/david/PycharmProjects/jena-facet/jena-text/src/main/java/org/apache/jena/query/text/assembler/ShaclIndexAssembler.java#L351) parses one resource as one field definition
- [`ShaclIndexAssembler.java#L386`](/home/david/PycharmProjects/jena-facet/jena-text/src/main/java/org/apache/jena/query/text/assembler/ShaclIndexAssembler.java#L386) requires the path on that same resource
- [`ShaclIndexAssembler.java#L395`](/home/david/PycharmProjects/jena-facet/jena-text/src/main/java/org/apache/jena/query/text/assembler/ShaclIndexAssembler.java#L395) treats the field resource URI as the field IRI

### Proposed model

Canonical field resource owns:

- field IRI
- field name
- type
- analyzers
- faceting/search/sort/store metadata

Property occurrence owns:

- `idx:field` reference to the canonical field
- `sh:path`
- optional supported constraints such as `sh:class`

This is a better separation of concerns:

- field identity is stable
- path is local to a specific shape occurrence
- one canonical field can be populated from many occurrences

## Lucene Naming

Lucene currently uses `idx:fieldName`, not the field IRI, as the actual field key:

- [`ShaclTextIndexLucene.java#L622`](/home/david/PycharmProjects/jena-facet/jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java#L622)
- [`ShaclTextIndexLucene.java#L717`](/home/david/PycharmProjects/jena-facet/jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java#L717)

The query layer already treats field IRIs as the public contract and resolves them to field names:

- [`ShaclTextIndexLucene.java#L217`](/home/david/PycharmProjects/jena-facet/jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java#L217)
- [`ShaclTextIndexLucene.java#L265`](/home/david/PycharmProjects/jena-facet/jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java#L265)

So the proposed model fits the current public direction well.

The design does not require Lucene field names to become IRIs. That remains an independent choice.

## Required Internal Refactor

The current implementation needs an explicit distinction between canonical fields and occurrences.

### New conceptual model

- `FieldDef`: canonical field metadata
- `FieldOccurrence`: one shape-local use of a field, with path and supported constraints

Each profile should then hold:

- a list of canonical fields reachable in that profile
- a list of root occurrences
- nested definitions containing nested occurrences

### Why this is required

Today indexing walks `FieldDef` directly and extracts values from `fieldDef.getPath()`:

- [`ShaclEntityBuilder.java#L47`](/home/david/PycharmProjects/jena-facet/jena-text/src/main/java/org/apache/jena/query/text/ShaclEntityBuilder.java#L47)
- [`ShaclEntityBuilder.java#L77`](/home/david/PycharmProjects/jena-facet/jena-text/src/main/java/org/apache/jena/query/text/ShaclEntityBuilder.java#L77)

That is incompatible with a design where the canonical field is path-free and the occurrence owns the path.

## Incremental Change Propagation Risk

This is the main technical complication introduced by occurrence-level constraints.

The current change listener mainly tracks:

- root `rdf:type`
- predicates that appear in field paths
- nested join predicates

See:

- [`ShaclTextDocProducer.java#L80`](/home/david/PycharmProjects/jena-facet/jena-text/src/main/java/org/apache/jena/query/text/ShaclTextDocProducer.java#L80)
- [`ShaclTextDocProducer.java#L175`](/home/david/PycharmProjects/jena-facet/jena-text/src/main/java/org/apache/jena/query/text/ShaclTextDocProducer.java#L175)

If an occurrence uses `sh:class ex:Borehole`, then a related node changing type may affect whether the parent value should be indexed, even if the path triples themselves did not change.

Example:

1. child document reaches node `X` via the configured path
2. `X` gains or loses `rdf:type ex:Borehole`
3. the child document should be rebuilt because `field:hasParent` membership changed

The current listener does not have a general model for that dependency.

### Consequence

Occurrence-level constraints require expanding the dependency graph beyond path predicates alone.

At minimum:

- `sh:class` implies dependency on `rdf:type` of the reached node
- simple node-kind or datatype constraints imply dependency on the endpoint value kind

This is manageable, but it must be designed explicitly.

## Per-Document Dedupe

The fan-in model does not imply deduping result rows across documents.

Many child documents may legitimately share the same parent value. That is the intended behaviour.

The dedupe concern is only within a single document:

- if multiple occurrences on the same child document yield the same parent ID
- the document should ideally index that parent value once for that field

Why:

- stored values stay cleaner
- facet counts are less likely to be distorted
- explanations and match reporting are less noisy

So the design should use set semantics per document per field, not global dedupe across documents.

## Gaps This Design Introduces

Compared to the current code, the proposed model creates the following implementation gaps:

- the assembler must parse property occurrences separately from canonical field resources
- field lookup by IRI must resolve to the canonical field, not to one arbitrary occurrence
- indexing must evaluate occurrences and emit values into the canonical field name
- change propagation must understand occurrence constraints
- hierarchy definitions must choose whether they target canonical fields or occurrences

## Recommendation For Hierarchies

For the first version of this model:

- use canonical field IRIs in ordinary field references and query APIs
- keep hierarchy definitions referring to canonical fields unless a concrete ambiguity appears
- keep hierarchies as scope-level derived definitions rather than separate field occurrences

If a future case requires multiple distinct occurrences of the same canonical field inside one correlated hierarchy context, then occurrence-level hierarchy references can be added later.

The current relationship-tab use case does not require that complexity.

## Merged Identifier Example

The following example shows how the shared-field design composes with `idx:nested`.

Canonical fields:

```turtle
@prefix idx:    <urn:jena:lucene:index#> .
@prefix field:  <urn:jena:lucene:field#> .
@prefix text:   <http://jena.apache.org/text#> .

field:identifierType
    idx:fieldName "identifierType" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

field:identifierValueExact
    idx:fieldName "identifierValueExact" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

field:identifierValueText
    idx:fieldName "identifierValueText" ;
    idx:fieldType idx:TextField ;
    idx:analyzer [ a text:EdgeNGramAnalyzer ] ;
    idx:queryAnalyzer [ a text:LowerCaseKeywordAnalyzer ] .
```

Shape with nested identifier scope:

```turtle
@prefix sh:     <http://www.w3.org/ns/shacl#> .
@prefix ex:     <http://example.org/> .
@prefix schema: <https://schema.org/> .

shape:BoreholeShape
    sh:targetClass ex:Borehole ;
    idx:nested [
        idx:joinPath schema:identifier ;

        idx:property [
            idx:field field:identifierType ;
            sh:path schema:propertyID ;
        ] ;

        idx:property [
            idx:field field:identifierValueExact ;
            sh:path schema:value ;
        ] ;

        idx:property [
            idx:field field:identifierValueText ;
            sh:path schema:value ;
        ] ;

        idx:facetHierarchy ( field:identifierType field:identifierValueExact ) ;
    ] .
```

This says:

- one child scope is created for each `schema:identifier` node
- the two keyword fields are used for correlated hierarchy tuples
- the text field is available for flattened prefix/typeahead search
- the hierarchy remains a derived definition over the keyword fields, not a separate fourth field

## Proposed Parser Rules

### Canonical field resource

Supported properties:

- `idx:fieldName`
- `idx:fieldType`
- `idx:analyzer`
- `idx:queryAnalyzer`
- `idx:stored`
- `idx:indexed`
- `idx:facetable`
- `idx:sortable`
- `idx:multiValued`
- `idx:defaultSearch`
- `idx:storeLiteralMetadata`

Not supported on canonical field resource:

- `sh:path`

### Property occurrence node

Required:

- `idx:field`
- `sh:path`

Optional initially:

- `sh:class`
- `sh:nodeKind`
- `sh:datatype`

Not supported initially:

- full `sh:node`
- general SHACL validation semantics

### Nested scope block

Required:

- `idx:joinPath`

Optional:

- `idx:property` for child-scope occurrences
- `idx:facetHierarchy` for hierarchies over child-scope canonical fields

`idx:joinPath` should remain distinct from `sh:path`.

It establishes the child scope itself rather than extracting one field value.

## Suggested Internal API Shape

Illustrative only:

```java
record FieldDef(
    Node fieldIri,
    String fieldName,
    FieldType fieldType,
    ...
) {}

record FieldOccurrence(
    FieldDef field,
    Path path,
    Node requiredClass,
    NodeKind nodeKind,
    Node datatype,
    String nestedName
) {}
```

The entity builder then:

1. iterates occurrences
2. evaluates the occurrence path
3. applies supported constraints
4. converts surviving nodes to values using the canonical field type
5. inserts those values under the canonical field name

## Migration Direction

This note intentionally ignores backward compatibility.

The clean direction is:

- canonical field resources are reusable definitions
- `sh:property` blank nodes are occurrences
- `idx:property` blank nodes inside `idx:nested` are also occurrences
- `idx:nested` continues to define correlated child scope boundaries
- field resources no longer carry `sh:path`

That is simpler for users and cleaner internally than trying to preserve the current overloaded field-resource model.

## Decision

Adopt the shared canonical field plus property occurrence model.

Specifically:

- use one canonical field IRI for the shared field concept
- allow many property occurrences to contribute to that field through different paths
- support `sh:class`, `sh:nodeKind`, and `sh:datatype` in the first implementation
- keep `idx:nested` for repeated correlated child records
- keep `idx:joinPath` as the scope-forming path for nested child records
- keep hierarchies as derived scope-level definitions over canonical fields rather than separate field occurrences
- treat per-document values as sets for that field
- defer full `sh:node` support until there is a strong need

This is a better fit than the current field-resource-equals-path-definition design.
