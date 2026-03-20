# Configuration Reference

All configuration is done via Jena Assembler TTL files. The text index is configured as part of a `text:TextDataset`.

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

## Classic Mode (text:entityMap)

The original triple-per-document model. Each RDF triple matching the entity map becomes a separate Lucene document. Uses `text:query` for search. No faceting support.

```turtle
<#index> a text:TextIndexLucene ;
    text:directory <file:/path/to/lucene> ;   # or "mem" for in-memory
    text:entityMap <#entMap> ;
    text:storeValues true ;
    .

<#entMap> a text:EntityMap ;
    text:entityField "uri" ;
    text:defaultField "text" ;
    text:langField "lang" ;
    text:uidField "uid" ;
    text:map (
        [ text:field "text" ;     text:predicate rdfs:label ]
        [ text:field "category" ; text:predicate ex:category ]
        [ text:field "author" ;   text:predicate ex:author ]
    ) .
```

### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `text:directory` | URI or `"mem"` | required | Lucene index location |
| `text:entityMap` | Resource | required* | Entity map definition |
| `text:storeValues` | boolean | false | Store literal values for retrieval |
| `text:analyzer` | Resource | StandardAnalyzer | Default analyzer |
| `text:queryAnalyzer` | Resource | same as analyzer | Analyzer for queries |
| `text:multilingualSupport` | boolean | false | Enable multilingual indexing |
| `text:ignoreIndexErrors` | boolean | false | Continue on indexing errors |
| `text:cacheQueries` | boolean | true | Enable query caching |

*Mutually exclusive with `text:shapes`.

---

## SHACL Mode (text:shapes)

Entity-per-document model. Each entity (identified by `rdf:type` matching `sh:targetClass`) gets one Lucene document containing all its fields. Uses `luc:query` for search with filters and `luc:facet` for facet counts.

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

### SHACL-mode properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `text:shapes` | RDF list | required* | List of shape resources |
| `text:maxFacetHits` | integer | 0 | Max docs for facet collection. 0 = unlimited |
| `text:storeValues` | boolean | false | Store literal values for retrieval |

*Mutually exclusive with `text:entityMap`.

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

Alternatively, fields can be defined as **named resources** and referenced from shapes. This is the recommended pattern — named resources preserve their IRI in query results, enable field reuse across shapes, and support complex paths:

```turtle
## Named field resources — IRIs used in ?field bindings
<#field-title>
    idx:fieldName "title" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true ;
    sh:path rdfs:label .

<#field-category>
    idx:fieldName "category" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path ex:category .

<#field-authorName>
    idx:fieldName "authorName" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    sh:path ( ex:writtenBy ex:name ) .  ## sequence path

<#field-referencedBy>
    idx:fieldName "referencedBy" ;
    idx:fieldType idx:KeywordField ;
    idx:multiValued true ;
    sh:path [ sh:inversePath ex:references ] .  ## inverse path

<#BookShape>
    sh:targetClass ex:Book ;
    sh:property <#field-title> ;
    sh:property <#field-category> ;
    sh:property <#field-authorName> ;
    sh:property <#field-referencedBy> .

## Same fields reused on a different shape
<#ArticleShape>
    sh:targetClass ex:Article ;
    sh:property <#field-title> ;
    sh:property <#field-category> .
```

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
| `idx:facetable` | boolean | false | Enable SortedSetDocValues faceting |
| `idx:sortable` | boolean | false | Add DocValues for sorting |
| `idx:multiValued` | boolean | false | Allow multiple values per entity |
| `idx:defaultSearch` | boolean | false | Use as default search field |
| `idx:analyzer` | Resource | index default | Per-field analyzer |
| `sh:path` | URI or blank node | required | RDF predicate(s) to index |

### Field types

| Type | Lucene fields | Stored as | Use case |
|------|--------------|-----------|----------|
| `idx:TextField` | `TextField` | analyzed text | Full-text search. Returns string literals in bindings |
| `idx:KeywordField` | `StringField` | exact string | Facets, filters, exact match. Returns IRIs in `?literal` and `?value` bindings when the stored value looks like a URI |
| `idx:IntField` | `IntPoint` | int | Numeric range queries. Returns `xsd:integer` typed literals |
| `idx:LongField` | `LongPoint` | long | Large numeric values. Returns `xsd:long` typed literals |
| `idx:DoubleField` | `DoublePoint` | double | Floating point values. Returns `xsd:double` typed literals |
| `idx:LatLonField` | `LatLonShape` | WKT geometry | Spatial filtering via CQL2-JSON `s_intersects`. See [Spatial Filtering](09-spatial.md) |

When `idx:facetable true` is set on a KeywordField, a `SortedSetDocValuesFacetField` is automatically added. When `idx:sortable true` is set, a `SortedDocValuesField` (for keywords) or `NumericDocValuesField` (for numerics) is added.

### Field IRIs

Each field definition has an associated IRI used in `?field` bindings from `luc:query` and `luc:facet`:

- **Named resource fields**: If the field is defined as a named resource (URI node) in the configuration, its IRI is used directly.
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

## Validation rules

- `text:shapes` and `text:entityMap` are **mutually exclusive** — specifying both throws an error
- Each shape must have at least one `sh:targetClass`
- Each field must have at least one path (`sh:path` or `idx:path`)
- Each field must have an `idx:fieldName`
- The shapes list must not be empty
