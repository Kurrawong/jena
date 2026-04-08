# Configuration Reference

All configuration is done via Jena Assembler TTL files. The text index is configured as part of a `text:TextDataset`.

> **Note:** The upstream Jena classic mode (`text:entityMap` / `text:query`) is unchanged and still available. See the [Apache Jena documentation](https://jena.apache.org/documentation/query/text-query.html) for its configuration. This reference covers only the SHACL-based entity-per-document configuration.

## Dataset wrapper

```turtle
@prefix text: <http://jena.apache.org/text#> .
@prefix tdb2: <http://jena.apache.org/2016/tdb#> .

<#ds> a text:TextDataset ;
    text:dataset <#baseDs> ;
    text:index <#index> ;
    .

<#baseDs> a tdb2:DatasetTDB2 ;
    tdb2:location "/path/to/tdb2" ;
    .
```

---

## Index Configuration

Each entity (identified by `rdf:type` matching `sh:targetClass`) gets one Lucene document containing all its fields. Uses `luc:query` for search with filters and `luc:facet` for facet counts.

```turtle
@prefix text:  <http://jena.apache.org/text#> .
@prefix idx:   <urn:jena:lucene:index#> .
@prefix sh:    <http://www.w3.org/ns/shacl#> .

<#index> a text:TextIndexLucene ;
    text:directory "mem" ;
    text:shapes ( <#BookShape> <#ArticleShape> ) ;
    text:storeValues true ;
    text:maxFacetHits 50000 ;                 # limit facet search (0 = unlimited)
    .
```

### Index properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `text:shapes` | RDF list | required* | List of shape resources |
| `text:maxFacetHits` | integer | 0 | Max docs for facet collection. 0 = unlimited |
| `text:storeValues` | boolean | false | Store literal values for retrieval |

*Mutually exclusive with `text:entityMap` (classic mode).

### Shape definition

Each shape in the list defines a Lucene document profile:

```turtle
<#BookShape>
    sh:targetClass ex:Book ;              # which rdf:type triggers this shape
    idx:docIdField "uri" ;                # entity URI field name (default: "uri")
    idx:discriminatorField "docType" ;    # type discriminator field (default: "docType")
    sh:property [                         # field definitions via sh:property
        idx:fieldName "title" ;
        idx:fieldType idx:TextField ;
        idx:defaultSearch true ;
        idx:stored true ;
        sh:path rdfs:label ;
    ] ;
    sh:property [
        idx:fieldName "category" ;
        idx:fieldType idx:KeywordField ;
        idx:facetable true ;
        idx:multiValued true ;
        sh:path ex:category ;
    ] .
```

Alternatively, fields can be defined as **named resources** with absolute IRIs and referenced from shapes. This is the recommended pattern — named resources are used as field identifiers in SPARQL queries (`luc:query` field specs, `luc:facet` facet field arrays, CQL2-JSON filter properties, and sort specs), enable field reuse across shapes, and support complex paths:

```turtle
@prefix field: <urn:jena:lucene:field#> .

## Named field resources — IRIs used as field identifiers in SPARQL
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

field:authorName
    idx:fieldName "authorName" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path ( ex:writtenBy ex:name ) .  ## sequence path

field:referencedBy
    idx:fieldName "referencedBy" ;
    idx:fieldType idx:KeywordField ;
    idx:multiValued true ;
    sh:path [ sh:inversePath ex:references ] .  ## inverse path

<#BookShape>
    sh:targetClass ex:Book ;
    sh:property field:title ;
    sh:property field:category ;
    sh:property field:authorName ;
    sh:property field:referencedBy .

## Same fields reused on a different shape
<#ArticleShape>
    sh:targetClass ex:Article ;
    sh:property field:title ;
    sh:property field:category .
```

> **Important:** Field resource IRIs must be absolute. Relative IRIs (e.g., `<#field-title>` via `PREFIX : <#>`) resolve to environment-dependent values that differ between Docker, local development, and the browser. Use an absolute prefix like `<urn:jena:lucene:field#>` or any other stable IRI scheme.

### Path expressions

Multiple path forms are supported:

```turtle
# Direct predicate
sh:path rdfs:label ;

# Alternative paths (field indexes multiple predicates)
sh:path [ sh:alternativePath (rdfs:label skos:prefLabel dct:title) ] ;

# Sequence path (traverse relationships — indexes the value at the end of the path)
sh:path ( ex:authoredBy ex:name ) ;

# Inverse path (follow a predicate in reverse)
sh:path [ sh:inversePath ex:authored ] ;

# Convenience shorthand (same as sh:path for single predicates)
idx:path rdfs:label ;
```

Sequence and inverse paths enable cross-entity indexing without forward chaining. For example, a sequence path `( ex:authoredBy ex:name )` on a report shape indexes the author's name directly on the report's Lucene document, without materialising an `ex:authorName` triple in the graph.

### Shape properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `sh:targetClass` | URI | required | RDF class that triggers this shape |
| `idx:docIdField` | string | `"uri"` | Lucene field for entity URI |
| `idx:discriminatorField` | string | `"docType"` | Lucene field for type discrimination |

### Field properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `idx:fieldName` | string | required | Lucene field name |
| `idx:fieldType` | Resource | `idx:TextField` | One of the field types below |
| `idx:stored` | boolean | true | Store value for retrieval |
| `idx:indexed` | boolean | true | Index for searching |
| `idx:facetable` | boolean | false | Enable faceting: KEYWORD flat/taxonomy facets or numeric range facets |
| `idx:sortable` | boolean | false | Enable sorting |
| `idx:multiValued` | boolean | false | Allow multiple values per entity |
| `idx:defaultSearch` | boolean | false | Use as default search field |
| `idx:analyzer` | Resource | index default | Per-field analyzer |
| `sh:path` | URI or blank node | required | RDF predicate(s) to index |

### Field types

| Type | Lucene fields | Stored as | Use case |
|------|--------------|-----------|----------|
| `idx:TextField` | `TextField` | analyzed text | Full-text search. Returns string literals in bindings |
| `idx:KeywordField` | `StringField` | exact string | Facets, filters, exact match. Returns IRIs in `?literal` and `?value` bindings when the stored value looks like a URI |
| `idx:IntField` | `IntPoint` | int | Numeric range queries, numeric range facets, numeric sorting. Range facets return typed `?low` / `?high` literals |
| `idx:LongField` | `LongPoint` | long | Large numeric values, numeric range facets, numeric sorting. Range facets return typed `?low` / `?high` literals |
| `idx:DoubleField` | `DoublePoint` | double | Floating point values, numeric range facets, numeric sorting. Range facets return typed `?low` / `?high` literals |
| `idx:LatLonField` | `LatLonShape` | WKT geometry | Spatial filtering via CQL2-JSON `s_intersects`. See [Spatial Filtering](09-spatial.md) |

When `idx:facetable true` is set on a `KeywordField`, a `SortedSetDocValuesFacetField` is added for flat facets. When `idx:sortable true` is set on a `KeywordField`, a `SortedDocValuesField` is added for sorting.

For numeric fields (`INT`, `LONG`, `DOUBLE`), docvalues are written whenever either `idx:facetable true` or `idx:sortable true` is set. This supports:

- range faceting on facetable numeric fields
- sorting on sortable numeric fields
- single-valued and multi-valued numeric fields via a common docvalues representation

### Range facets on numeric fields

Numeric fields (`INT`, `LONG`, `DOUBLE`) support range faceting via `luc:facet` when the field is marked `idx:facetable true`. Bucket boundaries are specified per query in the `facetFields` JSON array — no index-time bucket configuration is needed.

Key points:

- `idx:facetable true` is the public switch for numeric range faceting
- `idx:sortable true` is only needed if the same numeric field must also be sortable
- numeric range facets support both single-valued and multi-valued fields
- mixed flat + range facet requests use the 5-slot `luc:facet` subject form

For multi-valued numeric sorting, ascending order uses the field's minimum value and descending order uses the field's maximum value.

See [SPARQL API — Range Facets](02-sparql-api.md#range-facets) for query syntax.

**Date fields:** Lucene has no native date type. Store dates as epoch milliseconds using `idx:LongField`. Mark the field `idx:facetable true` for range faceting and `idx:sortable true` if you also need sort pushdown. Range facet boundaries are then epoch millis values. For hierarchical date navigation (year → month → day drill-down), use separate KEYWORD fields per level instead — see [Hierarchical vs Range Facets for Dates](02-sparql-api.md#hierarchical-vs-range-facets-for-dates).

### Field IRIs

Each field definition has an associated IRI that serves as its external identity. This IRI is used in SPARQL queries — as the `fieldSpec` in `luc:query`, in the `facetFields` JSON array for `luc:facet`, as the `property` value in CQL2-JSON filters, and as the `field` value in sort specs. The `idx:fieldName` property is the internal Lucene field name and is not accepted in these contexts.

- **Named resource fields**: If the field is defined as a named resource (URI node) in the configuration, its IRI is used directly. Use an absolute IRI prefix to ensure portability.
- **Blank node fields**: Fields defined on blank nodes (e.g., via `sh:property [ ... ]`) get an auto-generated IRI: `urn:jena:lucene:field#{fieldName}`.

This IRI is deterministic and stable — it depends only on `idx:fieldName`, not on blank node identity.

---

## Multiple shapes

You can define multiple shapes for different entity types in the same index:

```turtle
<#index> a text:TextIndexLucene ;
    text:directory "mem" ;
    text:shapes ( <#BookShape> <#ArticleShape> ) ;
    .

<#BookShape>
    sh:targetClass ex:Book ;
    sh:property [ idx:fieldName "title" ; sh:path rdfs:label ; idx:defaultSearch true ] ;
    sh:property [ idx:fieldName "genre" ; idx:fieldType idx:KeywordField ; idx:facetable true ; sh:path ex:genre ] .

<#ArticleShape>
    sh:targetClass ex:Article ;
    sh:property [ idx:fieldName "title" ; sh:path dct:title ; idx:defaultSearch true ] ;
    sh:property [ idx:fieldName "journal" ; idx:fieldType idx:KeywordField ; idx:facetable true ; sh:path ex:journal ] .
```

Each entity gets a `docType` discriminator field (the local name of its `sh:targetClass`), so Book and Article documents coexist in the same index.

---

## Hierarchical Facets

Hierarchical facets allow drill-down navigation through a tree of related fields. For example, state → commodity, where selecting a state reveals commodity counts within that state.

Define hierarchies on a shape using `idx:facetHierarchy` with an RDF list of field resources. The list order defines the hierarchy levels (parent → child):

```turtle
@prefix field: <urn:jena:lucene:field#> .

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
    sh:property field:title ;
    sh:property field:state ;
    sh:property field:commodity ;
    idx:facetHierarchy ( field:state field:commodity ) .
```

### Rules

- `idx:facetHierarchy` takes an RDF list of field IRIs (named resources)
- At least 2 levels are required
- Each referenced field must exist as a `sh:property` on the same shape (or globally)
- Multiple shapes can share the same hierarchy definition
- The dimension name is auto-generated by joining field names with `_` (e.g., `state_commodity`)

### How it works

Hierarchical fields are indexed using Lucene's taxonomy (`DirectoryTaxonomyWriter`). Each document gets a `FacetField` with path components from parent to child levels. At query time, `FastTaxonomyFacetCounts` provides drill-down counts and `MultiFacets` combines hierarchy and flat facet results.

Drill-down is triggered automatically when a CQL `=` filter targets a hierarchy level field. See [SPARQL API — Hierarchy Drill-Down](02-sparql-api.md#hierarchy-drill-down) for query examples.

---

## Validation rules

- `text:shapes` and `text:entityMap` are **mutually exclusive** — specifying both throws an error
- Each shape must have at least one `sh:targetClass`
- Each field must have at least one path (`sh:path` or `idx:path`)
- Each field must have an `idx:fieldName`
- The shapes list must not be empty
