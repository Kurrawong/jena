# Configuration Reference

This document covers the SHACL-mode configuration of `text:TextDataset`. The classic
`text:entityMap` / `text:query` configuration comes from upstream Apache Jena and is
documented there.

## Dataset Wrapper

One index:

```turtle
@prefix text: <http://jena.apache.org/text#> .
@prefix tdb2: <http://jena.apache.org/2016/tdb#> .

<#ds> a text:TextDataset ;
    text:dataset <#baseDs> ;
    text:indexes <#index> .

<#baseDs> a tdb2:DatasetTDB2 ;
    tdb2:location "/path/to/tdb2" .
```

Several indexes:

```turtle
<#ds> a text:TextDataset ;
    text:dataset <#baseDs> ;
    text:indexes ( <#objectsIndex> <#ocrIndex> ) .
```

| Property | Value | Effect |
|---|---|---|
| `text:dataset` | resource | The base dataset |
| `text:indexes` | one index resource, or an RDF list of them | The indexes. Use it for one index too |
| `text:index` | one index resource | Legacy single-index form. Use `text:indexes` in new configurations. Setting both is an error |
| `text:buildOnStartup` | boolean, default `false` | Build every SHACL index from the base dataset at startup. See below |

Each index in `text:indexes` has an id, used by `indexSelector` at query time. The id is
`text:indexId` when set. Without it, the id is the local name of a URI index resource, or
`index_<n>` for a blank node. Two indexes with the same id are an error.

### `text:buildOnStartup`

```turtle
<#ds> a text:TextDataset ;
    text:dataset <#baseDs> ;
    text:indexes <#index> ;
    text:buildOnStartup true .

<#baseDs> a ja:MemoryDataset ;
    ja:data <data/books.ttl> .

<#index> a text:TextIndexShacl ;
    text:directory "mem" ;
    text:shapes ( <#BookShape> ) .
```

`text:buildOnStartup true` bulk-indexes the base dataset before the server accepts a
request. It fills an index that the other two methods cannot reach:

- The change listener sees only triples added while it runs. It misses a `ja:data` load
  and a TDB2 store filled earlier by `tdb2.tdbloader`.
- `shacltextindexer` runs as a separate process, so it cannot fill a `"mem"` index.

The build runs on every start, and its time grows with the data. For a large dataset:

1. Load the data with `tdb2.tdbloader`.
2. Build the index with `shacltextindexer`.
3. Start the server on the built index.

The change listener stays attached after the startup build. The startup build writes no
dataset identity stamp, because it repeats on every start.

| Index configuration | Result |
|---|---|
| One `text:TextIndexLucene` index | Error. Use `textindexer` for classic indexes |
| Several indexes, some SHACL | Each SHACL index is built. Each classic index is skipped with a warning |
| Several indexes, none SHACL | Error |
| A value that is not a boolean | Error |

## Index Resources

```turtle
@prefix text: <http://jena.apache.org/text#> .
@prefix idx:  <urn:jena:lucene:index#> .
@prefix sh:   <http://www.w3.org/ns/shacl#> .

<#index> a text:TextIndexShacl ;
    text:indexId "default" ;
    text:directory "mem" ;
    text:shapes ( <#BookShape> <#ArticleShape> ) .
```

The SHACL index class is `text:TextIndexShacl`, and it requires `text:shapes`.
`text:TextIndexLucene` is the classic triple-per-document index. It ignores `text:shapes`
and uses `text:entityMap`.

| Property | Required | Default | Effect |
|---|---|---|---|
| `text:indexId` | no | see [Dataset Wrapper](#dataset-wrapper) | Id used by `indexSelector`, for example `"default"` or `"objects"`. A URI index resource is also accepted as an `indexSelector` |
| `text:directory` | yes, exactly one | — | Lucene storage. The literal `"mem"` is in memory. Any other literal is a path, and a resource is a file IRI |
| `text:shapes` | yes | — | RDF list of SHACL shapes, one document profile each. An empty list is an error |
| `text:taxonomyDirectory` | no | see [Taxonomy directory](#taxonomy-directory) | Storage for hierarchical facet ordinals. Same value forms as `text:directory` |
| `text:storeValues` | no | `false` | Does not change what SHACL documents store: `idx:stored` sets that per field. The value is part of the index's configuration fingerprint |
| `text:analyzer` | no | `StandardAnalyzer` | Index-wide default analyzer |
| `text:queryAnalyzer` | no | per field | Index-wide query analyzer. When set, it replaces every per-field query analyzer, and a warning is logged if any field has its own |
| `text:splitOnWhitespace` | no | `true` | Split the query string at whitespace before each field's analyzer runs. With `false`, a keyword-analyzed field receives the whole string as one term, spaces included |
| `text:autoGeneratePhraseQueries` | no | `true` | When an analyzer splits one word into pieces, the pieces must occur together and in order. `E45/5326` matches only `e45` followed by `5326`. Requires `text:splitOnWhitespace true`; the index fails to load otherwise |
| `text:exposeConfig` | no | `false` | Lets [`luc:config`](02-sparql-api.md#lucconfig) return this index's configuration. See below |

A boolean or integer setting with a value of another type is an error.

### `text:exposeConfig`

With `text:exposeConfig true`, anyone who can query the dataset can read this index's
configuration through `luc:config`. The configuration is every triple reachable from the
index resource, as written in the file. That includes:

- `text:directory` and `text:taxonomyDirectory`, with file paths;
- the analyzers and all their settings, with any file paths in them;
- the shapes, fields, occurrences, paths and hierarchies;
- an `idx:externalSource` block, with its `idx:location` and delta locations.

`luc:config` does not return the dataset, services and endpoints that declare the index,
or anything reachable only through `rdf:type`. The flag has no effect on the index on disk
or on its configuration fingerprint.

The flag is per index. In a `text:indexes` configuration, an index answers `luc:config`
only when it sets the flag.

### Taxonomy directory

`text:taxonomyDirectory` holds the ordinals of hierarchical facets. When it is absent and
some shape declares an `idx:facetHierarchy`:

- with `text:directory "mem"`, the taxonomy is in memory;
- with a path, the taxonomy is the sibling directory `<path>_taxonomy`, created on first
  open.

Set `text:taxonomyDirectory` only to put the taxonomy somewhere else.

An index built when the default taxonomy was in memory has facet ordinals that no on-disk
taxonomy resolves. Rebuild such an index.

## Shapes

Each SHACL shape in `text:shapes` is one document profile.

```turtle
@prefix sh:    <http://www.w3.org/ns/shacl#> .
@prefix idx:   <urn:jena:lucene:index#> .
@prefix field: <urn:jena:lucene:field#> .

<#BookShape>
    sh:targetClass ex:Book ;
    sh:property [ idx:field field:title ; sh:path rdfs:label ] ;
    sh:property [ idx:field field:category ; sh:path ex:category ] ;
    sh:property [ idx:field field:authorName ; sh:path ( ex:authoredBy ex:name ) ] .
```

Each `sh:property` is an **occurrence**. It names a field and the path that feeds the field.

| Property | Required | Default | Effect |
|---|---|---|---|
| `sh:targetClass` | yes | — | Entity class the profile indexes, matched on `rdf:type` exactly. Repeatable |
| `sh:property` | yes¹ | — | A root field occurrence, or a [step](#steps-following-a-path-through-shnode) |
| `idx:nested` | yes¹ | — | A child collection. See [Nested Child Records](#nested-child-records) |
| `idx:facetHierarchy` | no | — | One hierarchical facet dimension. See [Facet hierarchies](#facet-hierarchies) |
| `idx:docIdField` | no | `"uri"` | Lucene field that holds the entity IRI. All profiles in one index must use the same value |
| `idx:discriminatorField` | no | `"docType"` | Lucene field that holds the target class's local name. Deletes use it to stay inside one profile |

¹ A shape needs at least one `sh:property` or `idx:nested`.

`idx:field` directly on a shape is an error. Declare each root field as a `sh:property`
occurrence. A `sh:property` that points at a field definition (with `idx:fieldName`) is
also an error.

## Fields

A field is a named resource that defines one Lucene field. It has no path. Occurrences
supply the paths, so one field can take values from several paths and shapes. Error
messages call the field resource the *canonical field*.

```turtle
@prefix field: <urn:jena:lucene:field#> .
@prefix idx:   <urn:jena:lucene:index#> .

field:title
    idx:fieldName "title" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true .

field:category
    idx:fieldName "category" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

field:year
    idx:fieldName "year" ;
    idx:fieldType idx:IntField ;
    idx:facetable true ;
    idx:sortable true .

field:publishedOn
    idx:fieldName "publishedOn" ;
    idx:fieldType idx:TemporalField ;
    idx:facetable true ;
    idx:sortable true ;
    idx:storeLiteralMetadata true .   # required on TEMPORAL
```

SPARQL queries name a field by its IRI, for example `urn:jena:lucene:field#title`, in
filters, sorts, facets and field lists. `idx:fieldName` is the Lucene field name and is
used only inside the index.

### One idx:fieldName per field

The same field may occur in any number of shapes. Two field IRIs with the same
`idx:fieldName` are an error:

```turtle
## Rejected: one Lucene field, two definitions
field:authorName   idx:fieldName "author" ; idx:fieldType idx:KeywordField .
field:reviewerName idx:fieldName "author" ; idx:fieldType idx:KeywordField .
```

Both would write to the Lucene field `author`, and lookups by name would find only the
definition parsed first. Give the fields different names, or use one field in both shapes.

### Facet hierarchies

`idx:facetHierarchy` declares one hierarchical facet dimension. Its levels are fields, in
order from the top. Write it as a bare RDF list, or as a resource that holds the list
under `idx:levels`:

```turtle
## Bare list: addressed by the derived dimension name
idx:facetHierarchy ( field:identifierType field:identifierValueExact ) ;

## Named: addressed by the IRI dim:identifierPath
idx:facetHierarchy dim:identifierPath .
dim:identifierPath idx:levels ( field:identifierType field:identifierValueExact ) .
```

| Form | Dimension name |
|---|---|
| Bare list, or a blank node with `idx:levels` | The level field names joined with `_`, here `identifierType_identifierValueExact` |
| URI resource with `idx:levels` | The resource's IRI. The derived name does not address it |

Constraints, each checked when the configuration loads:

- A hierarchy has at least two levels.
- Each level is a field IRI.
- Each level is a field populated in the same scope as the hierarchy: a root occurrence of
  the shape for a shape-level hierarchy, or an occurrence of the block for a hierarchy
  inside `idx:nested`.

> **Reindex after you name a hierarchy or rename it.** The dimension name is stored in the
> Lucene taxonomy. A query by the new name does not find an index built under the old name.

## Field Properties

These properties go on the field, never on an occurrence.

| Property | Default | Effect |
|---|---|---|
| `idx:fieldName` | required | Lucene field name |
| `idx:fieldType` | `idx:TextField` | `idx:TextField`, `idx:KeywordField`, `idx:IntField`, `idx:LongField`, `idx:DoubleField`, `idx:TemporalField` or `idx:LatLonField`. `idx:DateField` and `idx:DateTimeField` are deprecated aliases for `idx:TemporalField` |
| `idx:stored` | `true` | `luc:match`, `luc:nestedMatch` and `luc:fields` can return the value. See [Where values are kept](#where-values-are-kept) |
| `idx:indexed` | `true` | Writes searchable terms or points, so filters on the field work |
| `idx:facetable` | `false` | Writes facet doc values, so `luc:facet` can count the field |
| `idx:sortable` | `false` | Writes sort doc values, so sorts on the field run in Lucene |
| `idx:multiValued` | `false` | Indexes every value of an entity. When `false` and an entity has several values, only the first is indexed and a warning names the entity and field |
| `idx:defaultSearch` | `false` | Included when `fieldSpec` is `"default"` |
| `idx:analyzer` | `text:analyzer`, else `StandardAnalyzer` | Index-time analyzer of a `TEXT` field |
| `idx:queryAnalyzer` | implied by `idx:analyzer` | Query-time analyzer. See [Query analyzer of an edge-n-gram field](#query-analyzer-of-an-edge-n-gram-field) |
| `idx:normalizer` | none | `KEYWORD` only; an error on any other type. An analyzer that produces the indexed term and the sort key. The stored value and the facet label keep the raw value |
| `idx:storeLiteralMetadata` | `false` | Stores the datatype and language tag, so a returned value rebuilds as the original literal. **Required on every `TEMPORAL` field**; without it, loading the configuration fails |

Each write-side flag adds one structure to the index and enables one capability:

| Flag | Structure written | When the flag is `false` |
|---|---|---|
| `idx:indexed` | inverted terms or points | A filter on the field cannot run in Lucene. It is logged as a warning and ignored, so the filter has no effect on the results |
| `idx:stored` | a stored value, or only the sort doc value for an eligible field | `luc:match`, `luc:nestedMatch` and `luc:fields` return nothing for the field |
| `idx:facetable` | facet doc values | A facet request on the field is an error |
| `idx:sortable` | sort doc values | A sort on the field does not run in Lucene |

A numeric or temporal field gets its doc values from `idx:facetable` or `idx:sortable`,
independently of `idx:indexed`. So a numeric field can be facetable and sortable and still
not filterable. See [10-suggested-configuration.md](10-suggested-configuration.md) for
recommended combinations.

### Where values are kept

A field's sort doc value holds its original value when all of these are true:

- `idx:fieldType` is `idx:KeywordField`;
- `idx:sortable` is `true`;
- there is no `idx:normalizer`, so the doc value is the raw lexical form;
- `idx:storeLiteralMetadata` is `false`, so there is no datatype or language to store.

With `idx:stored true`, such a field is written only as the doc value, and
`luc:match`, `luc:nestedMatch` and `luc:fields` read it from there. A doc-value read needs
no stored-field decompression, so it is faster when many hits are read.

Every other field with `idx:stored true` gets a stored copy, because its doc value does
not hold the original value:

| Field | Doc values written | Doc value holds the original value |
|---|---|---|
| `TEXT` | none | — |
| `KEYWORD`, `idx:sortable`, `idx:normalizer` set | sort doc value | No: it holds the normalized sort key |
| `KEYWORD`, `idx:facetable` only | `SortedSetDocValuesFacetField` | It is encoded in the shared facet field |
| `KEYWORD` with `idx:storeLiteralMetadata` | as above | The language tag and datatype are stored separately |
| `INT`, `LONG`, `DOUBLE` | `SortedNumericDocValuesField` | It holds the number. `"007"^^xsd:integer` returns as `7` |
| `TEMPORAL` | `SortedNumericDocValuesField` on an epoch twin | No: precision and timezone are lost |

The rule applies to root fields and to nested fields. Both sources return the same term:
an IRI when the value contains `://` or starts with `urn:`, a plain string otherwise. A
multi-valued field read from its doc value returns each distinct value once, in byte
order. Stored values return in indexing order.

An index written before this rule has stored copies of eligible fields. The reader uses
the doc value where a segment has one, and the stored copy where it does not, for example
in a segment written before the field was sortable. Reindex to remove the unused copies.

### Filters on multi-valued fields

A positive filter on a multi-valued field matches an entity when **any** of its values
matches. An entity with `commodity` values Gold and Copper matches `commodity = "Gold"`
and also `commodity = "Copper"`. An entity with depths 50 and 900 matches a shallow range
and a deep range. The same applies to `in` and to a text query.

A negated filter matches an entity when **no** value matches. `commodity <> "Gold"`
excludes the entity with Gold and Copper. Tests: `TestMultiValuedFieldSemantics`.

Spatial fields differ. Lucene evaluates `WITHIN`, `CONTAINS` and `DISJOINT` against all
shapes of a document together, so on a multi-valued `idx:LatLonField` these three require
every value to match. This differs from GeoSPARQL. `s_intersects` matches when any value
matches. See [09-spatial.md](09-spatial.md) and issue #179. Test:
`testWithinOnMultiValuedFieldRequiresEveryShape` in `TestSpatialFiltering`.

### Occurrence properties

An occurrence connects a field to a path. It carries the path and any value constraints.

| Property | Required | Effect |
|---|---|---|
| `idx:field` | yes | The field the occurrence feeds |
| `sh:path` | yes¹ | Predicate, sequence, inverse or alternative path from the entity, or from the child node inside `idx:nested`. See [Paths](#paths) |
| `idx:self` | yes¹ | `true` feeds the field from the focus node itself. See [Indexing the focus node](#indexing-the-focus-node) |
| `sh:class`, `sh:datatype`, `sh:nodeKind`, … | no | Index only the values that satisfy them. See [Value constraints](#value-constraints) |

¹ Exactly one of `sh:path` and `idx:self`. Neither, or both, is an error.

These errors are reported when the configuration loads:

- An occurrence carries field metadata: `idx:fieldName`, `idx:fieldType`, a write-side
  flag, `idx:multiValued`, `idx:defaultSearch`, `idx:storeLiteralMetadata`, `idx:analyzer`
  or `idx:queryAnalyzer`. `idx:joinPath` and `idx:nested` are also errors on an occurrence.
- A field carries occurrence data: `sh:path`, `sh:class`, `sh:nodeKind`, `sh:datatype` or
  `idx:field`.
- An occurrence uses `idx:path`. Use `sh:path`.

### Indexing the focus node

`idx:self true` feeds a field from the node the occurrence is evaluated against. That node
is the entity for a root occurrence, and the child node inside an `idx:nested` block.

```turtle
sh:property [ idx:field field:entityIri ; idx:self true ] ;
```

- Value constraints apply to the focus node. `sh:nodeKind sh:IRI` on a self occurrence
  indexes the child only when the child is an IRI.
- A self-bound field must be `idx:KeywordField` or `idx:TextField`. The other types are an
  error when the configuration loads.
- A blank-node focus node gives no value, because blank-node labels change between loads.
  The field is absent from that document.
- `idx:self false` is an error. Omit `idx:self` and give the occurrence a `sh:path`.
- `idx:self` on a step or on an `idx:column` is an error.
- A self occurrence adds no predicates to change tracking. Its value changes only when the
  entity or the join that reaches it changes, and the change listener tracks both.

The main use is a nested block whose child node is itself a value to index. See
[Pattern 4](#pattern-4--hierarchy-whose-level-is-the-child-node).

### Value constraints

The SHACL constraints on an occurrence filter the values it reaches, one value at a time.
jena-shacl evaluates them as a node shape on each value. A value that does not conform is
not indexed, and nothing is reported.

```turtle
sh:property [ idx:field field:author ; sh:path ex:author ; sh:class ex:Person ] ;
sh:property [ idx:field field:code ; sh:path ex:code ; sh:pattern "^[A-Z]{3}-" ] ;
```

| Constraint | On an occurrence |
|---|---|
| `sh:class`, `sh:datatype`, `sh:nodeKind`, `sh:in`, `sh:pattern` (with `sh:flags`), `sh:minLength`, `sh:maxLength`, `sh:languageIn`, `sh:minInclusive`, `sh:maxInclusive`, `sh:minExclusive`, `sh:maxExclusive`, `sh:node`, `sh:and`, `sh:or`, `sh:not`, `sh:xone` | Accepted, with their SHACL meaning |
| `sh:hasValue`, `sh:minCount`, `sh:maxCount`, `sh:uniqueLang`, `sh:qualifiedValueShape`, `sh:equals`, `sh:disjoint`, `sh:lessThan`, `sh:lessThanOrEquals` | Error naming the constraint. Accepted inside a [step filter](#steps-following-a-path-through-shnode) |
| `sh:property` | Error. To index only values whose own properties conform, write `sh:node [ sh:property [ … ] ]` |
| `sh:sparql`, SPARQL-based constraint components, `sh:closed` | Error everywhere in `text:shapes` |

The rejected constraints describe or compare the whole set of values a path reaches, so
they have no meaning for one value. To index one specific value, use `sh:in` with one
member.

`sh:class` follows `rdfs:subClassOf`, as SHACL defines. `sh:class ex:Person` keeps a value
typed `ex:Student` when the data holds `ex:Student rdfs:subClassOf ex:Person`. The
subclass triples are read from the graph the entity is built from. The change listener
does not track `rdfs:subClassOf` triples, so rebuild the index after you change the class
hierarchy.

`sh:datatype` also requires the literal to be valid for its datatype.
`"abc"^^xsd:integer` does not satisfy `sh:datatype xsd:integer`.

### Steps: following a path through sh:node

A **step** is a `sh:property` with `sh:path` and `sh:node`, no `idx:field`, whose
`sh:node` shape declares at least one field occurrence. The step:

1. follows `sh:path` from the focus node;
2. keeps each reached node that conforms to the **step filter**, which is the rest of the
   `sh:node` shape;
3. evaluates the field occurrences of the `sh:node` shape from each kept node.

```turtle
# ex:site1 schema:identifier [ schema:propertyID ex:CollarId ; schema:value "2297211" ] ,
#                            [ schema:propertyID ex:HoleId   ; schema:value "H-1" ] .
sh:property [
    sh:path schema:identifier ;
    sh:node [
        sh:property [ sh:path schema:propertyID ; sh:hasValue ex:CollarId ] ;
        sh:property [ sh:path schema:value ; idx:field field:collarId ] ;
    ] ;
] ;
```

`field:collarId` receives only `"2297211"`. It is an ordinary field of the scope that
declares the step. At shape level it is a root field, so it can be sortable, facetable and
returned by `luc:fields`. In an `idx:property` of an `idx:nested` block it is a child
field. Two kept nodes give two values, and `idx:multiValued` applies as for any path.

Members of the `sh:node` shape:

| Member | Role |
|---|---|
| `sh:property` with `idx:field` | A field occurrence, evaluated from the kept node. Its value constraints filter only its own values |
| `sh:property` with `sh:path` and `sh:node` whose shape declares a field occurrence | A further step, evaluated from the kept node |
| Any other `sh:property` | Part of the step filter, with its SHACL meaning: `sh:hasValue` holds when at least one value equals it, `sh:minCount` counts values, and so on |
| A constraint on the shape itself (`sh:class`, `sh:in`, `sh:or`, …) | Part of the step filter, applied to the kept node |

A `sh:property` whose `sh:node` shape declares no field occurrence is part of the step
filter. `sh:property [ sh:path ex:org ; sh:node ex:ActiveShape ]` keeps a node when every
`ex:org` value conforms to `ex:ActiveShape`. At shape level and directly inside
`idx:nested`, a `sh:property` without `idx:field` that is not a step is an error.

These are errors when the configuration loads:

- a step with more than one `sh:node`;
- a step that carries anything other than `sh:path` and `sh:node`, such as a constraint or
  `idx:self`. Put constraints on the reached nodes inside the `sh:node` shape;
- a step that leads back to a `sh:node` shape that encloses it;
- an `idx:` property inside a step filter;
- a path in a step filter of a form not listed under [Paths](#paths).

The change listener follows steps. A change to a triple on any path that the step filter
reads, or to an `rdf:type` that a `sh:class` in it checks, rebuilds the entities whose step
reaches the changed node. If an identifier's `schema:propertyID` changes from
`ex:CollarId` to another value, its value is removed from `field:collarId`.

## Choosing an Analyzer for a TEXT Field

A `TEXT` field with the wrong analyzer matches nothing, and no error is reported. Most
fields need no analyzer override:

| Goal | Configuration |
|---|---|
| Find entities by words in their text | `TEXT` with no `idx:analyzer`. The index-wide analyzer (`StandardAnalyzer` by default) applies, and BM25 ranks the hits |
| Filter to one value of a closed vocabulary (a person, an organisation, a status) | `KEYWORD` with `idx:facetable true`, filtered with `=` on a value taken from the facet list |
| Complete a value that the user types from memory (an identifier, a code) | `TEXT` with `idx:analyzer [ a text:EdgeNGramAnalyzer ]` |

Use edge-n-grams only for the third row. For names, see [Person names](#person-names).

### Edge-n-gram modes

```turtle
## Whole-value prefixes
field:identifierValueText
    idx:fieldName "identifierValueText" ;
    idx:fieldType idx:TextField ;
    idx:analyzer [ a text:EdgeNGramAnalyzer ] .

## fed by an occurrence on the shape:
##   sh:property [ idx:field field:identifierValueText ; sh:path schema:value ]
```

| Analyzer property | Default | Effect |
|---|---|---|
| `text:tokenized` | `false` | `false`: n-grams of the whole value, so only prefixes of the whole value match. `true`: n-grams of each word, so a prefix of any word matches, and multi-word input matches as an adjacent phrase. In this mode each whole word is also indexed, so a word longer than `text:maxGram` still matches in full |
| `text:minGram` | `1` | Shortest n-gram |
| `text:maxGram` | `20` | Longest n-gram |

Use `text:tokenized true` for values that have segments, such as dashed identifiers, when
users search by the segment they remember.

### Query analyzer of an edge-n-gram field

Do not set `idx:queryAnalyzer` on an edge-n-gram field. The n-gram analyzer applied to the
query turns the input into many prefixes, which match far too many documents. When
`idx:queryAnalyzer` is absent, the field's mode selects the query analyzer:

| `idx:analyzer` | Implied `idx:queryAnalyzer` |
|---|---|
| `text:EdgeNGramAnalyzer` | `text:LowerCaseKeywordAnalyzer`: the input is one lowercased term |
| `text:EdgeNGramAnalyzer` with `text:tokenized true` | `text:StandardAnalyzer`: the input is split into words as the index was |
| any other analyzer | the same analyzer |

An explicit `idx:queryAnalyzer` overrides the implied one. An index-wide
`text:queryAnalyzer` overrides both.

### Whole-value or per-word

For a value with no punctuation or spaces, such as `"A9412"`, the two modes give the same
results. They differ when the value has more than one word or segment:

| Value | Input | Whole-value matches | Per-word matches |
|---|---|---|---|
| `Dr Sarah Jones` | `Dr Sar` | yes | yes |
| `Dr Sarah Jones` | `Jones` | no | yes |
| `Dr Sarah Jones` | `Sarah Jones` | no | yes |
| `RPT-MIA-2023-001` | `RPT-MIA` | yes | yes |
| `RPT-MIA-2023-001` | `2023` | no | yes |

Use whole-value when only the start of the value may match. Use per-word when users
search by the parts of the value.
Tests: `TestTypeaheadFieldConfigurations` covers every row of this table and the previous
one.

### Person names

For a person's name, use a plain `TEXT` field with no analyzer override. `text_query`
analyzes the input in the same way as the field, so `"Jones"` and `"Sarah Jones"` both
match `"Dr Sarah Jones"`, ranked by BM25. Add a `KEYWORD` twin for exact filters and facet
counts:

```turtle
## Exact filters and facet counts
field:authorName
    idx:fieldName "authorName" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

## BM25 search over the same name, no analyzer override
field:authorNameText
    idx:fieldName "authorNameText" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true .

## Two occurrences on one path; the two fields differ only in analysis
<#MiningReportShape>
    sh:property [ idx:field field:authorName     ; sh:path ( ex:authoredBy ex:name ) ] ;
    sh:property [ idx:field field:authorNameText ; sh:path ( ex:authoredBy ex:name ) ] .
```

A per-word edge-n-gram twin on a name has these costs:

- **Relevance.** Each value becomes about 10–20 tokens. IDF over prefixes is meaningless
  (`"s"` occurs everywhere), and length norms grow. BM25 scores on the field lose their
  meaning, which affects every query once the field is in `idx:defaultSearch`.
- **Index size.** The field is several times larger.
- **Word order.** `text_query` builds a phrase query, so `"Jones Sarah"` does not match.

Edge-n-grams also give no typo tolerance: `"Jonse"` fails with n-grams and with BM25.

A plain `TEXT` field inside an `idx:nested` scope also combines with its sibling clauses on
one child. "role = Principal Investigator AND agent matches Sarah Jones" matches only when
one attribution record has both. Tests: `TestCorrelatedNestedAttribution`.

A picklist that completes a value suits a small, fixed vocabulary, such as roles or
statuses. For a large vocabulary such as people, the client must either download the
whole vocabulary, which becomes impractical at a few thousand values, or run a query on
each keystroke. An identifier is an open, near-unique vocabulary with no useful counts,
which the user types exactly; use edge-n-grams for identifiers.

## Paths

`sh:path` on an occurrence or a step, and paths inside a step filter, accept these forms:

| Form | Example |
|---|---|
| Predicate | `sh:path rdfs:label` |
| Sequence | `sh:path ( ex:authoredBy ex:name )` |
| Inverse | `sh:path [ sh:inversePath ex:authored ]` |
| Alternative | `sh:path [ sh:alternativePath ( ex:title ex:subtitle ) ]` |

The forms may be combined. `sh:zeroOrMorePath`, `sh:oneOrMorePath` and `sh:zeroOrOnePath`
are errors when the configuration loads, because the change listener cannot follow them
back to the entity. `idx:joinPath` accepts fewer forms; see [Block properties](#block-properties).

## Nested Child Records

`idx:nested` declares a repeated child collection on a shape. Each child becomes its own
Lucene document in the entity's block. At query time, AND-ed clauses on fields of one
nested scope must match the same child.

`idx:joinPath` reaches the child nodes from the entity. The occurrences inside the block
are evaluated from each child node.

### Block properties

| Property | Required | Effect |
|---|---|---|
| `idx:joinPath` | yes¹ | Path from the entity to the child nodes. A predicate, an inverse predicate, or a sequence of these. Alternative paths are an error |
| `idx:externalSource` | yes¹ | The children come from a tabular file. See [External Content](#external-content-csvtsv) |
| `idx:property` | yes² | A field occurrence or a step, evaluated from the child node. Repeatable |
| `idx:nestedName` | see below | The block's scope name |
| `idx:facetHierarchy` | no | A hierarchy whose levels are taken from the same child record |

¹ Exactly one of `idx:joinPath` and `idx:externalSource`.
² Required, at least one, in an `idx:joinPath` block. In an `idx:externalSource` block,
the fields come from `idx:column` bindings, and `idx:property` is an error.

The **scope name** identifies the block. It is written to every child document as
`_nestedScope`. The query engine uses it to:

- decide which clauses must match the same child;
- find a child's field definitions when it returns `luc:nestedMatch` results;
- restrict a nested sort to the right block.

| Block | Scope name |
|---|---|
| `idx:joinPath` | Derived from the path. `idx:nestedName` is ignored |
| `idx:externalSource` | `idx:nestedName`, required. Without it, loading the configuration fails |

The scope name is a label that no query names, because the query engine infers the scope
from the field. Use a short, descriptive name. The name is stored in the index, so
reindex after you change it.

### Pattern 1 — Qualified identifier (both children are KEYWORD)

`schema:identifier` records that carry `(propertyID, value)` pairs:

```turtle
field:identifierType
    idx:fieldName "identifierType" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

field:identifierValueExact
    idx:fieldName "identifierValueExact" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

<#BoreholeShape>
    sh:targetClass ex:Borehole ;
    idx:nested [
        idx:joinPath schema:identifier ;
        idx:property [ idx:field field:identifierType ; sh:path schema:propertyID ] ;
        idx:property [ idx:field field:identifierValueExact ; sh:path schema:value ] ;
        idx:facetHierarchy ( field:identifierType field:identifierValueExact ) ;
    ] .
```

The occurrence paths start at the child node, so `schema:propertyID` is read from each
`schema:identifier` record.

All AND-ed clauses on one nested scope match the same child: `=`, `<`, `>`, `<=`, `>=`,
`in`, `between`, `like` and `text_query`. See
[02-sparql-api.md → Nested same-child filters](02-sparql-api.md#nested-same-child-filters).

### Pattern 2 — Identifier with text/typeahead on a child field

Add a second occurrence of the value path, feeding an analyzed `TEXT` field. Each value
then writes a raw keyword term and analyzed tokens to its child document:

```turtle
field:identifierValueText
    idx:fieldName "identifierValueText" ;
    idx:fieldType idx:TextField ;
    idx:analyzer [ a text:EdgeNGramAnalyzer ; text:tokenized true ] .

<#BoreholeShape>
    sh:targetClass ex:Borehole ;
    idx:nested [
        idx:joinPath schema:identifier ;
        idx:property [ idx:field field:identifierType ; sh:path schema:propertyID ] ;
        idx:property [ idx:field field:identifierValueExact ; sh:path schema:value ] ;
        idx:property [ idx:field field:identifierValueText ; sh:path schema:value ] ;
        idx:facetHierarchy ( field:identifierType field:identifierValueExact ) ;
    ] .
```

At query time, combine `=` on `identifierType` with `text_query` on `identifierValueText`
in the same CQL subtree. See
[02-sparql-api.md](02-sparql-api.md#text_query--analyzer-aware-text-matching).

### Pattern 3 — Qualified attribution (prov)

`prov:qualifiedAttribution` records that carry `(hadRole, agent)`:

```turtle
field:attributionRole
    idx:fieldName "attributionRole" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

field:attributionAgentExact
    idx:fieldName "attributionAgentExact" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

field:attributionAgentText
    idx:fieldName "attributionAgentText" ;
    idx:fieldType idx:TextField .

<#MiningReportShape>
    sh:targetClass ex:MiningReport ;
    idx:nested [
        idx:joinPath prov:qualifiedAttribution ;
        idx:property [ idx:field field:attributionRole ; sh:path ( prov:hadRole rdfs:label ) ] ;
        idx:property [ idx:field field:attributionAgentExact ; sh:path ( prov:agent rdfs:label ) ] ;
        idx:property [ idx:field field:attributionAgentText ; sh:path ( prov:agent rdfs:label ) ] ;
    ] .
```

- The role is a fixed vocabulary. Filter it with `=`.
- Filter the agent name with `text_query` on the plain `TEXT` field. `"Sarah Jones"`
  matches `"Dr Sarah Jones"`. A report matches only when one attribution record has both
  the role and the agent.
- The `KEYWORD` field gives exact filters and facet counts.

The agent has no n-gram field; see [Person names](#person-names).
`TestCorrelatedNestedAttribution` tests same-child matching for `=`, for `text_query` on a
plain field, and for the n-gram configurations that work but are not recommended.

### Pattern 4 — Hierarchy whose level is the child node

The entity references a term, and the term belongs to a broader group. A correlated facet
over group and term needs the term itself as a hierarchy level.

```turtle
# instance data: a report can have several commodities
ex:report1  dcterms:subject  ex:copper , ex:gold .

# vocabulary: each commodity has a broader group
ex:copper  skos:broader  ex:BaseMetals .
ex:zinc    skos:broader  ex:BaseMetals .
ex:gold    skos:broader  ex:PreciousMetals .
```

```turtle
<#ReportShape>
    sh:targetClass ex:Report ;
    idx:nested [
        idx:joinPath dcterms:subject ;
        idx:property [ idx:field field:commodity      ; idx:self true ] ;
        idx:property [ idx:field field:commodityGroup ; sh:path skos:broader ] ;
        idx:facetHierarchy ( field:commodityGroup field:commodity ) ;
    ] .
```

The child node is the commodity concept, so its occurrence binds the focus node. The
group is one `skos:broader` step from it. Each child record holds one commodity and its
group, so `ex:report1` gives exactly its two paths: `BaseMetals / copper` and
`PreciousMetals / gold`.

With two root occurrences (`sh:path dcterms:subject` and
`sh:path ( dcterms:subject skos:broader )`), each level is read separately and the
hierarchy is their cartesian product. That product includes `BaseMetals / gold`, which is
not in the data. A drill-down into a group then offers commodities from other groups, and
the child counts exceed the parent count.

Each level must be a fixed number of steps from the concept. A `skos:broader` chain of
unknown depth (`skos:broader+`) cannot be a hierarchy.

Both fields here are child fields. They filter on the same child, and they are not
available as entity-level flat facets. For a flat facet over the term across all entities,
declare a separate field, with its own name, fed by a root occurrence on
`dcterms:subject`.

### Rules

- A field IRI belongs to one scope: the root, or one nested block.
- An explicit `idx:nestedName` must be unique among all `idx:nested` blocks of the index.
- The assembler does not check these two rules. When a field or a scope name is in more
  than one block, the query engine uses the first block that declares it.
- Several fields can read the same path in one block, for example a `KEYWORD` field and an
  edge-n-gram `TEXT` field. Each has its own Lucene field and analyzer.
- A hierarchy inside `idx:nested` takes all its levels from one child record. A hierarchy
  on a shape reads each level separately from the entity and combines every value of one
  level with every value of the next. That is correct only when the levels are
  independent. For related levels, use
  [Pattern 4](#pattern-4--hierarchy-whose-level-is-the-child-node).
- A field in an `idx:facetHierarchy` keeps its own flat facet dimension. A facet on the
  field IRI counts that field across all parents. A facet on the hierarchy dimension counts
  its top level, or the children of a drill-down path.
- A facet request on a field without `idx:facetable true` is an error.

## External Content (CSV/TSV)

An `idx:nested` block can take its children from a CSV or TSV file. Rows join to entities
on the entity IRI. Use it for a large attribute set that has its authoritative copy
elsewhere and is needed only for range filters, range facets and sorts. Applications read
the values themselves from the authoritative copy.

Design note: [2026-07-27_external_content_indexing_design.md](2026-07-27_external_content_indexing_design.md).

### Configuration

```turtle
field:measuredProperty
    idx:fieldName "measuredProperty" ;
    idx:fieldType idx:KeywordField ;
    idx:indexed   true ;
    idx:facetable true ;
    idx:stored    true .        # short label that rarely changes

field:measuredValue
    idx:fieldName "measuredValue" ;
    idx:fieldType idx:DoubleField ;
    idx:indexed   true ;        # range filters
    idx:facetable true ;        # range facets
    idx:sortable  true ;        # sorts
    idx:stored    false .       # read values from the authoritative copy

<#SampleShape>
    sh:targetClass ex:Sample ;
    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;

    idx:nested [
        idx:nestedName "measurement" ;
        idx:externalSource [
            idx:format        idx:CsvFile ;
            idx:location      "/data/measurements.csv" ;
            idx:subjectColumn "sample_iri" ;
            idx:column [ idx:columnName "property" ; idx:field field:measuredProperty ] ;
            idx:column [ idx:columnName "value" ;    idx:field field:measuredValue ] ;
        ] ;
    ] .
```

A field bound by `idx:column` takes its values from the column. An `idx:column` with
`sh:path` or `idx:self` is an error. A field is external because a column binds it; there
is no separate flag.

`idx:nestedName` is required in an external block. See [Block properties](#block-properties).

### Source properties

| Property | Required | Default | Effect |
|---|---|---|---|
| `idx:format` | yes | — | `idx:CsvFile` or `idx:TsvFile` |
| `idx:location` | yes | — | A path, or a glob such as `/data/meas-*.csv`. Glob matches are read in filename order |
| `idx:subjectColumn` | yes¹ | — | Header name of the column that holds the entity IRI, or the key that `idx:subjectPrefix` extends |
| `idx:subjectColumnIndex` | yes¹ | — | Zero-based index of the subject column |
| `idx:subjectPrefix` | no | none | String put in front of each subject value. No other transformation |
| `idx:delimiter` | no | by format | Delimiter, exactly one character |
| `idx:headerless` | no | `false` | The file has no header row. Columns are then bound with `idx:columnIndex` |
| `idx:onError` | no | `"skip"` | `"skip"` counts and skips a bad row. `"fail"` stops the build |
| `idx:column` | yes | — | One column binding: exactly one of `idx:columnName` or `idx:columnIndex`, plus `idx:field`. Repeatable |
| `idx:delta` | no | none | A delta file, or an RDF list of delta files. See [Deltas](#deltas) |
| `idx:opColumn` | no | `"op"` | Column in a delta file that holds `ADD` or `DELETE` |

¹ `idx:subjectColumn` when the file has a header; `idx:subjectColumnIndex` when
`idx:headerless true`.

These are errors when the configuration loads:

- no `idx:column`;
- a column bound by name in a headerless source, or by index in a source with a header;
- one field bound to more than one column of a source;
- a column bound to a `TEMPORAL` or `LATLON` field. A cell has no place for the literal
  metadata or WKT these types need. Columns accept `TEXT`, `KEYWORD`, `INT`, `LONG` and
  `DOUBLE`.

### Input shape

One row per measurement, joined on the entity IRI:

```
sample_iri,property,value
https://ex.org/sample/A1,Au,12.4
https://ex.org/sample/A1,Cu,0.7
https://ex.org/sample/A2,Au,0.3
```

Each row becomes one child document. Two fields cover any number of measured properties,
and a new property in the file needs no configuration change.

### Wide children — one row is one event

All bound columns of a row go into the same child document. There is no limit on the
number of columns. With four columns, the child is a whole measurement event:

```turtle
idx:column [ idx:columnName "depth_from" ; idx:field field:depthFrom ] ;
idx:column [ idx:columnName "depth_to" ;   idx:field field:depthTo ] ;
idx:column [ idx:columnName "analyte" ;    idx:field field:analyte ] ;
idx:column [ idx:columnName "value" ;      idx:field field:value ] ;
```

```
hole_iri,depth_from,depth_to,analyte,value
https://ex.org/hole/A1,0,10,Au,12.4
https://ex.org/hole/A1,0,10,Cu,0.7
https://ex.org/hole/A1,10,20,Au,0.5
```

All AND-ed clauses on these four fields must match the same child:

```json
{"op":"and","args":[
  {"op":"=", "args":[{"property":"urn:jena:lucene:field#analyte"},"Au"]},
  {"op":">=","args":[{"property":"urn:jena:lucene:field#value"},1.0]},
  {"op":">=","args":[{"property":"urn:jena:lucene:field#depthFrom"},0]},
  {"op":"<=","args":[{"property":"urn:jena:lucene:field#depthTo"},10]}
]}
```

This filter finds Au above 1 g/t in the 0–10 m interval of one row. A hole with a Cu
result at 0–10 m and a separate interval from 10 m does not match
`analyte = "Cu" AND depthFrom >= 10`, because no single row satisfies both.

The row is the unit of correlation. Two analytes are two rows, so
`analyte = "Au" AND analyte = "Cu"` matches nothing as a same-child query. To ask for Au
and Cu in the same interval, each row must have one column per analyte.

Every bound column must have a value in every row. A row with an empty bound cell is
dropped. The more columns a source binds, the more rows this can drop.

### Sort order

No configuration is needed. The build must write all of an entity's children together,
because Lucene has no partial document update. It therefore sorts the rows by subject
itself:

- Rows are read into a buffer and sorted by subject.
- When the rows do not fit in the buffer, sorted runs are written to temporary files and
  merged. An input that fits in the buffer uses no disk.
- Memory use depends on the buffer size only, so the input can be of any size, in any
  order.
- The sort is stable: rows with the same subject keep their input order. This matters
  because duplicate `(subject, property)` rows are valid.

The system property `jena.text.external.sortBufferRows` (default 200,000) sets the number
of rows held in memory before a run is written to disk. Lower it on hosts with little
memory.

### Deltas

A delta file holds only the changed rows. The build applies it over the base file, so the
indexer still receives the complete child set of each entity.

```turtle
idx:delta ( "data/2026-07-a.csv" "data/2026-07-b.csv" ) ;   # applied in this order
idx:opColumn "op" ;                                          # default
```

A delta file has the same columns as the base file, plus the operation column:

```
op,borehole,analyte,grade,units,below_detection
DELETE,http://ex.org/bh-1,Ag,44.9,ppm,f
ADD,http://ex.org/bh-1,Ag,51.3,ppm,f
DELETE,http://ex.org/bh-2,Mn,,,
```

| Case | Behaviour |
|---|---|
| `DELETE` | Removes the rows that match every cell it fills in. An empty cell matches any value: the third row above removes every Mn measurement of `bh-2` |
| Numeric cells | Compared by value: `0.70` deletes `0.7` |
| `ADD` | Appends a row. Duplicate rows for one property are valid, so there is no key to update. To replace a row, `DELETE` it and `ADD` the new one |
| Order | Within one subject, deletes apply before adds, so row order in the file does not change the result |
| A `DELETE` that matches nothing | Counted and logged. Deltas can be replayed and can overlap |

Constraints:

- A delta file must have a header row, because the operation column is bound by name. A
  delta on a headerless source is an error.
- Neither the base file nor the delta needs any order. See [Sort order](#sort-order).
- Give several deltas as one RDF list. They apply in list order. Repeated `idx:delta`
  statements are an error, because RDF does not order them.

A delta build is a full rebuild. Deltas remove the need to merge the base file and the
deltas into a new file first.

### Rules and limits

- **Bulk build only.** Only `ShaclBulkIndexer` writes external children. The change
  listener ignores a live change to an entity of a shape with `idx:externalSource` and logs
  a warning, because a rebuild from the graph alone would remove the entity's children.
  The entity's document is then out of date until the bulk indexer runs again.
- **Rows add children to existing entities only.** A row whose subject matches no entity
  is counted and dropped. The build always logs the matched and unmatched counts and the
  match rate. A wrong `idx:subjectPrefix` shows as a match rate near zero. There is no
  threshold setting; correct the data or the prefix.
- **An entity with no rows is still indexed**, with its graph fields and no children.
- **A row with an empty or unparseable bound cell is dropped whole.** The build does not
  convert it to `0` or write a partly filled child.
- **No transformations.** The build does not convert units, read detection-limit markers
  such as `<0.5`, interpret null markers, or compute columns. A cell parses as its field's
  type, or it is an error. Do this work in the process that produces the file.
- **`idx:stored false` removes only the returned value.** Filters, range facets and sorts
  still work, and `luc:match` returns nothing for the field. The index is still a
  snapshot, so filters can be out of date until the next rebuild. Rebuild each time the
  source publishes a new release.
- **Same-child matching is per row.** Clauses that two different rows satisfy are two
  block joins AND-ed at the entity. "Au above 0.5 AND Cu above 100" matches when each
  clause matches some row of the entity. See
  [Wide children](#wide-children--one-row-is-one-event).

## Multiple Indexes

Use several indexes when the data sets need different:

- analyzers;
- field sets;
- update schedules;
- operational handling.

At query time:

- `indexSelector` selects the index;
- `fieldSpec` is resolved against the selected index;
- sort and filter field IRIs must exist in the selected index.

Sort specifications name the field IRI:

```json
{"field":"urn:jena:lucene:field#year","order":"desc"}
```

## Example

```turtle
@prefix text:  <http://jena.apache.org/text#> .
@prefix idx:   <urn:jena:lucene:index#> .
@prefix sh:    <http://www.w3.org/ns/shacl#> .
@prefix field: <urn:jena:lucene:field#> .
@prefix ja:    <http://jena.hpl.hp.com/2005/11/Assembler#> .
@prefix rdfs:  <http://www.w3.org/2000/01/rdf-schema#> .
@prefix ex:    <http://example.org/> .

<#dataset> a text:TextDataset ;
    text:dataset <#baseDs> ;
    text:indexes <#index> .

<#baseDs> a ja:MemoryDataset .

<#index> a text:TextIndexShacl ;
    text:indexId "default" ;
    text:directory "mem" ;
    text:shapes ( <#BookShape> ) .

field:title
    idx:fieldName "title" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true .

field:category
    idx:fieldName "category" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

<#BookShape>
    sh:targetClass ex:Book ;
    sh:property [ idx:field field:title ; sh:path rdfs:label ] ;
    sh:property [ idx:field field:category ; sh:path ex:category ] .
```

## Vocabulary Index

`idx:` is `urn:jena:lucene:index#`. SPARQL queries use the same namespace with the prefix
`luc:`. By convention, `idx:` is used for configuration and `luc:` for property functions;
the software accepts either prefix.

Properties the assembler reads:

| Term | Goes on | Section |
|---|---|---|
| `idx:field` | occurrence, `idx:column` | [Occurrence properties](#occurrence-properties) |
| `idx:self` | occurrence | [Indexing the focus node](#indexing-the-focus-node) |
| `idx:fieldName` `idx:fieldType` | field | [Field Properties](#field-properties) |
| `idx:stored` `idx:indexed` `idx:facetable` `idx:sortable` `idx:multiValued` `idx:defaultSearch` | field | [Field Properties](#field-properties) |
| `idx:analyzer` `idx:queryAnalyzer` `idx:normalizer` | field | [Field Properties](#field-properties) |
| `idx:storeLiteralMetadata` | field | [Field Properties](#field-properties) |
| `idx:docIdField` `idx:discriminatorField` | shape | [Shapes](#shapes) |
| `idx:facetHierarchy` | shape, `idx:nested` | [Facet hierarchies](#facet-hierarchies) |
| `idx:levels` | hierarchy resource | [Facet hierarchies](#facet-hierarchies) |
| `idx:nested` | shape | [Nested Child Records](#nested-child-records) |
| `idx:joinPath` `idx:nestedName` `idx:property` | `idx:nested` | [Block properties](#block-properties) |
| `idx:externalSource` | `idx:nested` | [External Content](#external-content-csvtsv) |
| `idx:format` `idx:location` `idx:subjectColumn` `idx:subjectColumnIndex` `idx:subjectPrefix` `idx:delimiter` `idx:headerless` `idx:onError` `idx:column` `idx:delta` `idx:opColumn` | `idx:externalSource` | [Source properties](#source-properties) |
| `idx:columnName` `idx:columnIndex` | `idx:column` | [Source properties](#source-properties) |

Resources used as values:

| Term | Used as |
|---|---|
| `idx:TextField` `idx:KeywordField` `idx:IntField` `idx:LongField` `idx:DoubleField` `idx:TemporalField` `idx:LatLonField` | `idx:fieldType` value |
| `idx:DateField` `idx:DateTimeField` | deprecated aliases for `idx:TemporalField` |
| `idx:CsvFile` `idx:TsvFile` | `idx:format` value |

`idx:path` is an error; use `sh:path`.

Property functions in the same namespace: `luc:query`, `luc:facet`, `luc:match`,
`luc:nestedMatch`, `luc:fields`, `luc:config` and `luc:stats`. See
[02-sparql-api.md](02-sparql-api.md).
