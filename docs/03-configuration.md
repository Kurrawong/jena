# Configuration Reference

This document covers SHACL-mode configuration for `text:TextDataset`.

Classic `text:entityMap` / `text:query` configuration is unchanged upstream and is not covered here.

## Dataset Wrapper

Use `text:indexes`, even for a single index.

Single index:

```turtle
@prefix text: <http://jena.apache.org/text#> .
@prefix tdb2: <http://jena.apache.org/2016/tdb#> .

<#ds> a text:TextDataset ;
    text:dataset <#baseDs> ;
    text:indexes <#index> .

<#baseDs> a tdb2:DatasetTDB2 ;
    tdb2:location "/path/to/tdb2" .
```

Multiple indexes:

```turtle
<#ds> a text:TextDataset ;
    text:dataset <#baseDs> ;
    text:indexes ( <#objectsIndex> <#ocrIndex> ) .
```

Notes:

- `text:indexes` accepts either a single resource or an RDF list.
- `text:index` is legacy and should be avoided in new configs.
- Duplicate `text:indexId` values are rejected.

## Index Resources

```turtle
@prefix text: <http://jena.apache.org/text#> .
@prefix idx:  <urn:jena:lucene:index#> .
@prefix sh:   <http://www.w3.org/ns/shacl#> .

<#index> a text:TextIndexLucene ;
    text:indexId "default" ;
    text:directory "mem" ;
    text:shapes ( <#BookShape> <#ArticleShape> ) ;
    text:storeValues true ;
    text:maxFacetHits 50000 .
```

Important properties:

| Property | Meaning |
|---|---|
| `text:indexId` | Token id used by `indexSelector`, for example `"default"` or `"objects"` |
| `text:directory` | Lucene storage location |
| `text:shapes` | RDF list of SHACL shapes |
| `text:storeValues` | Store values for `luc:match` and facet value binding |
| `text:maxFacetHits` | Maximum documents considered during facet collection |

If the index resource itself is a URI resource, that URI is also accepted as an `indexSelector`.

## Shapes

Each SHACL shape contributes one document profile.

```turtle
@prefix sh:   <http://www.w3.org/ns/shacl#> .
@prefix idx:  <urn:jena:lucene:index#> .
@prefix field: <urn:jena:lucene:field#> .

<#BookShape>
    sh:targetClass ex:Book ;
    sh:property field:title ;
    sh:property field:category ;
    sh:property field:authorName .
```

## Fields

Named field resources are the recommended pattern.

```turtle
@prefix field: <urn:jena:lucene:field#> .
@prefix idx:   <urn:jena:lucene:index#> .
@prefix sh:    <http://www.w3.org/ns/shacl#> .

field:title
    idx:fieldName "title" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true ;
    sh:path rdfs:label .

field:category
    idx:fieldName "category" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path ex:category .

field:year
    idx:fieldName "year" ;
    idx:fieldType idx:IntField ;
    idx:facetable true ;
    idx:sortable true ;
    sh:path ex:year .

field:publishedOn
    idx:fieldName "publishedOn" ;
    idx:fieldType idx:TemporalField ;
    idx:facetable true ;
    idx:sortable true ;
    sh:path ex:publishedOn .
```

Public API rule:

- External SPARQL uses the field IRI, for example `urn:jena:lucene:field#title`.
- Internal Lucene storage uses `idx:fieldName`.

`idx:fieldName` is not a public query-time identifier.

## Field Properties

| Property | Meaning |
|---|---|
| `idx:fieldName` | Internal Lucene field name |
| `idx:fieldType` | `idx:TextField`, `idx:KeywordField`, `idx:IntField`, `idx:LongField`, `idx:DoubleField`, `idx:TemporalField`, `idx:LatLonField` |
| `idx:facetable` | Enables faceting |
| `idx:sortable` | Enables sort pushdown |
| `idx:multiValued` | Allows multiple values |
| `idx:defaultSearch` | Included when `fieldSpec` is `"default"` |
| `idx:analyzer` | Index-time analyzer override |
| `idx:queryAnalyzer` | Query-time analyzer override |
| `sh:path` | Direct, sequence, inverse, or nested path |

`idx:DateField` and `idx:DateTimeField` are accepted as deprecated aliases for `idx:TemporalField`.

## Paths

Direct path:

```turtle
sh:path rdfs:label .
```

Sequence path:

```turtle
sh:path ( ex:authoredBy ex:name ) .
```

Inverse path:

```turtle
sh:path [ sh:inversePath ex:authored ] .
```

## Nested Child Records

`idx:nested` declares a repeated child collection on a shape. Each child becomes its own Lucene doc inside the entity's block; clauses targeting the same nested scope can be combined with same-child correlation at query time.

`idx:joinPath` enumerates child nodes from the parent. Field occurrences inside the `idx:nested` block are evaluated relative to the child node, not the parent.

### Pattern 1 — Qualified identifier (both children are KEYWORD)

`schema:identifier` records carrying `(propertyID, value)` pairs:

```turtle
field:identifierType
    idx:fieldName "identifierType" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path schema:propertyID .

field:identifierValueExact
    idx:fieldName "identifierValueExact" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path schema:value .

<#BoreholeShape>
    sh:targetClass ex:Borehole ;
    idx:nested [
        idx:joinPath schema:identifier ;
        idx:property field:identifierType ;
        idx:property field:identifierValueExact ;
        idx:facetHierarchy ( field:identifierType field:identifierValueExact ) ;
    ] .
```

Query-time same-child via `=` only — see [02-sparql-api.md → Nested same-child filters](02-sparql-api.md#nested-same-child-filters).

### Pattern 2 — Identifier with text/typeahead on a child field

Add a second occurrence of the value field with an analyzer-backed `TEXT` field. The exact and text fields share the SHACL path but produce different Lucene fields:

```turtle
field:identifierValueText
    idx:fieldName "identifierValueText" ;
    idx:fieldType idx:TextField ;
    idx:analyzer <#edgeNgramAnalyzer> ;
    idx:queryAnalyzer <#lowercaseKeywordAnalyzer> ;
    sh:path schema:value .

<#BoreholeShape>
    sh:targetClass ex:Borehole ;
    idx:nested [
        idx:joinPath schema:identifier ;
        idx:property field:identifierType ;
        idx:property field:identifierValueExact ;
        idx:property field:identifierValueText ;
        idx:facetHierarchy ( field:identifierType field:identifierValueExact ) ;
    ] .
```

At query time, combine `=` on `identifierType` with `text_query` on `identifierValueText` in the same CQL subtree (see [02-sparql-api.md](02-sparql-api.md#text_query--analyzer-aware-text-matching)).

The exact and text fields can coexist on the same child path — index-time, each value writes both a raw keyword term and the analyzed tokens to its child doc.

### Pattern 3 — Qualified attribution (prov)

`prov:qualifiedAttribution` records carrying `(hadRole, agent)`:

```turtle
field:attributionRole
    idx:fieldName "attributionRole" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path prov:hadRole .

field:attributionAgent
    idx:fieldName "attributionAgent" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path prov:agent .

field:attributionAgentText
    idx:fieldName "attributionAgentText" ;
    idx:fieldType idx:TextField ;
    idx:analyzer <#edgeNgramAnalyzer> ;
    idx:queryAnalyzer <#lowercaseKeywordAnalyzer> ;
    sh:path prov:agent .

<#MiningReportShape>
    sh:targetClass ex:MiningReport ;
    idx:nested [
        idx:joinPath prov:qualifiedAttribution ;
        idx:property field:attributionRole ;
        idx:property field:attributionAgent ;
        idx:property field:attributionAgentText ;
    ] .
```

Then at query time:

- exact role + exact agent → both `=` clauses fold same-child
- exact role + text/typeahead on agent → `=` + `text_query` fold same-child

### Rules

- One field IRI belongs to one scope: either root or one nested collection.
- `idx:joinPath` may be a simple predicate, an inverse predicate, or a sequence of predicate steps. It does not support alternative paths.
- Both the exact-keyword and edge-ngram-text variants can sit on the same SHACL path — they are different Lucene fields driven by their own analyzers.
- `idx:facetHierarchy` inside an `idx:nested` block defines a hierarchy whose levels are correlated per child record (no cartesian products).

## Multi-Index Notes

Multiple indexes are useful when corpora differ materially:

- different analyzers
- different field sets
- different update cadence
- operational separation

At query time:

- `indexSelector` picks the index
- `fieldSpec` is resolved against that selected index
- sort and filter field IRIs must exist in that selected index

## Sort Configuration

Sort specs use field IRIs in SPARQL:

```json
{"field":"urn:jena:lucene:field#year","order":"desc"}
```

But the underlying Lucene field key still comes from `idx:fieldName`.

## Example

```turtle
@prefix text: <http://jena.apache.org/text#> .
@prefix idx:  <urn:jena:lucene:index#> .
@prefix sh:   <http://www.w3.org/ns/shacl#> .
@prefix field: <urn:jena:lucene:field#> .

<#dataset> a text:TextDataset ;
    text:dataset <#baseDs> ;
    text:indexes <#index> .

<#index> a text:TextIndexLucene ;
    text:indexId "default" ;
    text:directory "mem" ;
    text:storeValues true ;
    text:shapes ( <#BookShape> ) .

field:title
    idx:fieldName "title" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true ;
    sh:path rdfs:label .

field:category
    idx:fieldName "category" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path ex:category .

<#BookShape>
    sh:targetClass ex:Book ;
    sh:property field:title ;
    sh:property field:category .
```
