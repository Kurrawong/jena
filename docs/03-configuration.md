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

Bulk-index the base dataset while the dataset is being assembled, before the server
accepts a request. Defaults to `false`.

An index is normally filled one of two ways, and neither reaches data that is already in
place when the dataset is wrapped:

- the **change listener** sees triples as they are added, so it never sees a
  `ja:data` load or a TDB2 store filled earlier by `tdb2.tdbloader`;
- **`shacltextindexer`** is a separate process, so it cannot hand a
  `text:directory "mem"` index to the server that needs it.

`text:buildOnStartup` covers the gap between them. With it, an in-memory dataset and an
in-memory index are a working configuration — the self-contained demo image is exactly
that, and before this flag a `"mem"` SHACL index had no way to hold any content at all.

The build cost is paid on every start and grows with the data, so this is for datasets
small enough to index in the time you are willing to wait for a restart. For anything
larger keep the loader/server split: `tdb2.tdbloader`, then `shacltextindexer`, then
start the server on the finished index.

The change listener stays attached — a startup build is not a read-only mode — and the
index is not stamped with the dataset identity that `shacltextindexer` records. That
stamp asserts "this persisted index was built from that database", which only a build
outliving its own process can claim.

Requires a SHACL index; with `text:TextIndexLucene` it is an error, since the classic
mode has `textindexer` for the same job. In a multi-index configuration every SHACL
index is built and any classic one is skipped with a warning.

## Index Resources

```turtle
@prefix text: <http://jena.apache.org/text#> .
@prefix idx:  <urn:jena:lucene:index#> .
@prefix sh:   <http://www.w3.org/ns/shacl#> .

<#index> a text:TextIndexShacl ;
    text:indexId "default" ;
    text:directory "mem" ;
    text:shapes ( <#BookShape> <#ArticleShape> ) ;
    text:storeValues true ;
    text:maxFacetHits 50000 .
```

SHACL mode is `text:TextIndexShacl`, and it **requires** `text:shapes`. `text:TextIndexLucene`
is the classic triple-per-document index and ignores `text:shapes` entirely — configure that
one with `text:entityMap`.

| Property | Required | Meaning |
|---|---|---|
| `text:indexId` | no | Token id used by `indexSelector`, for example `"default"` or `"objects"` |
| `text:directory` | yes | Lucene storage location. The literal `"mem"` is in-memory; anything else is a path or file IRI |
| `text:shapes` | yes | RDF list of SHACL shapes, one document profile each |
| `text:taxonomyDirectory` | no | Where hierarchical-facet ordinals live. Defaults to a sibling of `text:directory` named `<path>_taxonomy`, or to memory when `text:directory` is `"mem"`. Set it only to put the taxonomy somewhere else |
| `text:storeValues` | no, default `false` | Store values for `luc:match` and facet value binding |
| `text:maxFacetHits` | no | Maximum documents considered during facet collection |
| `text:analyzer` | no | Index-wide default analyzer |
| `text:queryAnalyzer` | no | Index-wide query analyzer |
| `text:defineAnalyzers` | no | Named filters, tokenizers and analyzers (`text:defineFilter`, `text:defineTokenizer`, `text:defineAnalyzer`) that an `idx:analyzer` uses through `text:ConfigurableAnalyzer` or `text:DefinedAnalyzer`, as in [Jena's text index](https://jena.apache.org/documentation/query/text-query.html). `text:addLang` is refused: a SHACL index has no per-language analyzers. Names defined here are registered for the whole JVM, as in Jena's text index, so a `text:DefinedAnalyzer` also resolves a name another index defined; one whose name no index loaded so far has defined fails the index at load. Define every name an index uses in its own list, so it does not depend on which index loads first. See [Upgrading an index that lists `text:defineAnalyzers`](#upgrading-an-index-that-lists-textdefineanalyzers) |
| `text:splitOnWhitespace` | no, default `true` | Cut the query string at whitespace before each field's analyzer runs. With `false`, a keyword-analyzed field receives the whole string as one term, spaces included |
| `text:autoGeneratePhraseQueries` | no, default `true` | Where an analyzer splits one word into pieces, the pieces must appear together and in order: `E45/5326` matches `e45` followed by `5326`, not either alone. Needs `text:splitOnWhitespace true`; the index fails to load otherwise |
| `text:exposeConfig` | no, default `false` | Let [`luc:config`](02-sparql-api.md#lucconfig) return this index's configuration. See below |

If the index resource itself is a URI resource, that URI is also accepted as an `indexSelector`.

### Upgrading an index that lists `text:defineAnalyzers`

Earlier builds ignored `text:defineAnalyzers` on a SHACL index, so a `text:DefinedAnalyzer`
resolved to nothing, without a warning:

- **On a field** (`idx:analyzer`), the field was analysed with the index-wide analyzer
  instead.
- **Index-wide** (`text:analyzer`), every field with no `idx:analyzer` of its own was
  analysed with StandardAnalyzer.

Both now get the analyzer they name, so an index built before is analysed one way on disk
and another at query time, and searches can miss documents. Rebuild it. Startup reports
the index's configuration fingerprint as a mismatch in both cases: the fingerprint records
a field's analyzer class, which was none before, and now also the index-wide analyzer's.

The index-wide analyzer is recorded only when it is not StandardAnalyzer, so a
configuration with no `text:analyzer` keeps its fingerprint. One whose `text:analyzer` is
any other analyzer reports a mismatch once against an index built before this change, even
if that analyzer always resolved: startup cannot tell the two apart. Rebuilding clears it
and is safe.

A field that used a defined tokenizer or filter through `text:ConfigurableAnalyzer` failed
on its first write before, so no index of that kind exists to rebuild. `idx:boost` and
`idx:copyOf` are read at query time and need no rebuild.

*[TestShaclDefineAnalyzers](../jena-text/src/test/java/org/apache/jena/query/text/TestShaclDefineAnalyzers.java)
reopens an index built under the old fallback and expects the mismatch.*

### `text:exposeConfig`

With `text:exposeConfig true`, anyone who can query the dataset can read this index's
configuration through `luc:config`. What they can read is every triple reachable from the
index resource, as written in the file:

- `text:directory` and `text:taxonomyDirectory`, including file paths;
- the analyzers, with every setting they carry, including any file path in them;
- the shapes, fields, occurrences, paths and hierarchies;
- an `idx:externalSource` block, including its `idx:location` and delta locations.

Not readable: the dataset, services and endpoints that declare the index, and anything
reached only through `rdf:type`. Only the index resource's own subgraph is copied, and
only when this flag is set; the setting does not affect the index on disk or its
configuration fingerprint.

The flag is per index. In a `text:indexes` configuration, each index answers
`luc:config` only if it sets the flag itself.

### Upgrading: the taxonomy directory

A config that declares hierarchies with `text:directory <path>` and no
`text:taxonomyDirectory` now creates `<path>_taxonomy` beside the index on first open.
Previously such a config kept its ordinals in memory, so hierarchical facet counts were
lost whenever the process that built the index was not the process that served it — the
`fuseki-lucene-shacl-loader` / `fuseki-lucene-shacl` split, which is the normal
deployment. Those configs start returning hierarchical counts on upgrade with no edit.

Rebuild the index after upgrading. An index built under the old default carries facet
ordinals that no on-disk taxonomy resolves, and the new empty taxonomy beside it will not
match them.

## Shapes

Each SHACL shape contributes one document profile.

```turtle
@prefix sh:   <http://www.w3.org/ns/shacl#> .
@prefix idx:  <urn:jena:lucene:index#> .
@prefix field: <urn:jena:lucene:field#> .

<#BookShape>
    sh:targetClass ex:Book ;
    sh:property [ idx:field field:title ; sh:path rdfs:label ] ;
    sh:property [ idx:field field:category ; sh:path ex:category ] ;
    sh:property [ idx:field field:authorName ; sh:path ( ex:authoredBy ex:name ) ] .
```

Each `sh:property` is an **occurrence**: it names a canonical field and the path that feeds
it. Pointing `sh:property` straight at a field resource is the old form and is rejected.

| Property | Required | Meaning |
|---|---|---|
| `sh:targetClass` | yes | Entity class this profile indexes. Repeatable |
| `sh:property` | yes¹ | A root field occurrence |
| `idx:nested` | yes¹ | A child collection — see [Nested Child Records](#nested-child-records) |
| `idx:facetHierarchy` | no | Ordered list of fields forming one hierarchical facet dimension, or a resource carrying `idx:levels` (see below) |
| `idx:docIdField` | no, default `"uri"` | Lucene field holding the entity IRI. Must be identical across every profile in one index |
| `idx:discriminatorField` | no, default `"docType"` | Lucene field holding the target class's local name, so deletes stay scoped to one profile |

¹ A shape needs at least one of `sh:property` / `idx:nested`.

## Fields

A field is defined **once**, as a named resource, and carries **no path**. The path lives on
the occurrence that references it, which is what lets one field be fed from several paths and
several shapes.

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
    idx:storeLiteralMetadata true .   # not optional on TEMPORAL
```

Public API rule:

- External SPARQL uses the field IRI, for example `urn:jena:lucene:field#title`.
- Internal Lucene storage uses `idx:fieldName`.

`idx:fieldName` is not a public query-time identifier.

### One idx:fieldName, one field

`idx:fieldName` is the Lucene field. The same field may be bound in as many shapes as you
like, but two different field IRIs may not share a name:

```turtle
## Rejected: one Lucene field, two definitions
field:authorName   idx:fieldName "author" ; idx:fieldType idx:KeywordField .
field:reviewerName idx:fieldName "author" ; idx:fieldType idx:KeywordField .
```

Both would write to `author`, and every lookup that goes from name to definition returns
whichever shape parsed first. The second definition is then ignored entirely: its
analyzer, its facetability, its whole configuration, with nothing to say so. Give them
distinct names, or bind the one field in both shapes.

Conflicting types for one name were already rejected. This closes the commoner case,
where the types agree and everything else differs.

### Naming a facet hierarchy

A hierarchy may be written as a bare RDF list, or as a resource carrying its levels under
`idx:levels`. Giving that resource an IRI lets queries address the dimension by IRI, like
every other part of the query API:

```turtle
## Bare list: addressable only by the derived dimension name.
idx:facetHierarchy ( field:identifierType field:identifierValueExact ) ;

## Named: addressable by its IRI as well.
idx:facetHierarchy [
    a dim:identifierPath ;
    idx:levels ( field:identifierType field:identifierValueExact )
] ;
```

or, more usefully, with the IRI on the hierarchy itself:

```turtle
idx:facetHierarchy dim:identifierPath .

dim:identifierPath idx:levels ( field:identifierType field:identifierValueExact ) .
```

A named hierarchy is addressed by its IRI, and that IRI **is** the taxonomy dimension. It
has one identifier, not two, so the derived name no longer reaches it.

A bare list has no IRI, so its dimension is the name derived by joining the level field
names with `_`, here `identifierType_identifierValueExact`. That name appears in no
configuration file, and a client has to reconstruct it from the level order to address the
hierarchy at all. This is the one place the move to field IRIs had not reached, and naming
the hierarchy is how you close it.

> **Naming a hierarchy changes its on-disk key, so reindex after doing so.** The dimension
> is written into the Lucene taxonomy, and a query against an index built under the old
> derived name will not find it.

## Field Properties

These go on the **canonical field**, never on an occurrence.

| Property | Default | Meaning |
|---|---|---|
| `idx:fieldName` | required | Internal Lucene field name |
| `idx:fieldType` | `idx:TextField` | `idx:TextField`, `idx:KeywordField`, `idx:IntField`, `idx:LongField`, `idx:DoubleField`, `idx:TemporalField`, `idx:LatLonField`, `idx:PeriodField` (see [Periods](#periods)) |
| `idx:stored` | `true` | The value can be returned by `luc:match`, `luc:nestedMatch` and `luc:fields`. Where it is kept is decided by the indexer; see [Where values are kept](#where-values-are-kept) |
| `idx:indexed` | `true` | Write searchable terms/points — this is what makes the field **filterable** |
| `idx:facetable` | `false` | Write facet docvalues — required to appear in `luc:facet` |
| `idx:sortable` | `false` | Write sort docvalues — required for `ORDER BY` pushdown |
| `idx:multiValued` | `false` | Allow more than one value per entity |
| `idx:defaultSearch` | `false` | Included when `fieldSpec` is `"default"` |
| `idx:analyzer` | index-wide default | Index-time analyzer override (`TEXT`) |
| `idx:queryAnalyzer` | implied by `idx:analyzer` | Query-time analyzer override |
| `idx:normalizer` | none | `KEYWORD` only — analyzer driving the indexed term and sort key while the stored value and facet label stay raw |
| `idx:storeLiteralMetadata` | `false` | Store the datatype and language tag so a projected value rebuilds as the original literal. **Required on every `TEMPORAL` field** |
| `idx:boost` | `1` | Weight of the field's clause when `luc:query` searches the query string across several fields; a positive number. Query-time only, so changing it needs no reindex. See [Search as you type](#search-as-you-type-an-n-gram-copy-field) |
| `idx:copyOf` | none | `TEXT` only — makes this field a copy field and names its source field: the `TEXT` field that indexes the same values, analysed differently. `luc:match` and `luc:nestedMatch` report this field's matches under its source field. Refused at load unless the source field holds every value this one does; changes nothing written, so no reindex. See [Declaring a copy field](#declaring-a-copy-field) |

The four write-side flags are independent, and each buys exactly one capability:

| Flag | Structure written | Without it |
|---|---|---|
| `idx:indexed` | inverted terms / points | filters on the field are **silently dropped** — the clause becomes residual, is logged, and never applied |
| `idx:stored` | stored value, or the sort doc value alone for an eligible field | `luc:match`, `luc:nestedMatch` and `luc:fields` have nothing to return |
| `idx:facetable` | facet docvalues | faceting the field is an error |
| `idx:sortable` | sort docvalues | no sort pushdown |

Numeric and temporal fields take their facet/sort docvalues from `idx:facetable` **or** `idx:sortable`, independently of `idx:indexed` — so a numeric field can be facetable and sortable but not filterable. That is rarely what you want; see [10-suggested-configuration.md](10-suggested-configuration.md).

`idx:DateField` and `idx:DateTimeField` are accepted as deprecated aliases for `idx:TemporalField`.

`idx:TemporalField` reads `xsd:dateTime`, `xsd:dateTimeStamp`, `xsd:date`,
`xsd:gYearMonth` and `xsd:gYear` values, and filters, sorts and facets them by when they
start, in UTC. A date with an offset starts at midnight at that offset. See
[10-suggested-configuration.md → Dates](10-suggested-configuration.md#dates).

### Where values are kept

A field's sort doc value holds its original value when all of these hold:

- `idx:fieldType` is `idx:KeywordField`
- `idx:sortable true`
- no `idx:normalizer`, so the doc value is the raw lexical form rather than a sort key
- `idx:storeLiteralMetadata false`, so there is no datatype or language to pair with it

Such a field with `idx:stored true` is written as the doc value only, with no stored
copy, and `luc:match`, `luc:nestedMatch` and `luc:fields` read it from the doc value. A
doc-value read costs no stored-field decompression, which matters when values are read
for many hits. Every other field with `idx:stored true` gets a stored copy, because its
doc value, if any, does not hold the original value:

| Field | Doc values written | Holds the original value? |
|---|---|---|
| `TEXT` | none | — |
| `KEYWORD`, `idx:sortable`, `idx:normalizer` set | sort doc value | No: the normalized sort key |
| `KEYWORD`, `idx:facetable` only | `SortedSetDocValuesFacetField` | Encoded in the shared facet field |
| `KEYWORD` with `idx:storeLiteralMetadata` | as above | The language tag and datatype are stored separately |
| `INT`, `LONG`, `DOUBLE` | `SortedNumericDocValuesField` | The number, not the lexical form: `"007"^^xsd:integer` would come back as `7` |
| `TEMPORAL` | `SortedNumericDocValuesField` on an epoch twin | No: precision and timezone are lost |

The rule applies to root and nested fields. Both sources give the same term: an IRI
when the value looks like one (`://` or a `urn:` prefix), a plain string otherwise. A
multi-valued field read from its doc value returns each distinct value once, in byte
order; stored values come back in indexing order.

An index written before this rule keeps stored copies of eligible fields. The reader
prefers the doc value and decides per segment: a segment with no doc value for the
field, for example one written before the field was made sortable, is read from its
stored copy. Reindex to drop the unused copies.

#### What a filter means on a multi-valued field

A positive filter on a multi-valued field is **existential**: the entity matches if *any*
one of its values matches. An entity with `commodity` of both Gold and Copper matches
`commodity = "Gold"` and `commodity = "Copper"`, and one with depths 50 and 900 matches
both a shallow and a deep range. The same holds for `in` and for a text query.

Negation inverts the quantifier, which is also the reading people expect: `commodity <>
"Gold"` means *no* value is Gold, so it excludes that entity rather than returning it on
the strength of its Copper. `<>` compiles to `MatchAllDocs MUST_NOT <positive>`, so this
follows from the positive form rather than being implemented separately.

This is a property of the inverted index, not a choice, and it is why these fields need
none of the work that `idx:LatLonField` does. A shape field is not an inverted index over
values: Lucene evaluates `WITHIN`, `CONTAINS` and `DISJOINT` across every shape on the
document at once, so those three are all-of on a multi-valued geometry, which diverges
from GeoSPARQL. See [09-spatial.md](09-spatial.md) and issue #179. `s_intersects` is
existential and unaffected.

Pinned by `TestMultiValuedFieldSemantics`, with the contrasting geometry behaviour pinned
by `testWithinOnMultiValuedFieldRequiresEveryShape` in `TestSpatialFiltering`.

### Occurrence properties

An occurrence binds a canonical field to a path. It carries the path and any value
constraints; it must not repeat the field's own metadata (`idx:fieldName`, `idx:fieldType`,
the flags, the analyzers), and the field must not carry occurrence data (`sh:path`,
`sh:class`, `sh:nodeKind`, `sh:datatype`). Both directions are rejected at config time, so
one field cannot end up with two different definitions.

| Property | Required | Meaning |
|---|---|---|
| `idx:field` | yes | The canonical field this occurrence feeds |
| `sh:path` | yes¹ | Direct, sequence, inverse, or alternative path from the entity (or, inside `idx:nested`, from the child node) |
| `idx:self` | yes¹ | Bind the focus node itself instead of a path from it — see [Indexing the focus node](#indexing-the-focus-node) |
| `sh:class`, `sh:datatype`, `sh:nodeKind`, … | no | Value constraints: only index the values that satisfy them — see [Value constraints](#value-constraints) |
| `idx:periodStart`, `idx:periodEnd` | on a `PeriodField` only, and then both | Paths from the node the occurrence reaches to the period's start and to its end — see [Periods](#periods) |

¹ Exactly one of `sh:path` / `idx:self`. Neither is an error, and so is both.

Several occurrences may feed one field — that is how a value fans in from more than one
path. They must all resolve to the same canonical definition.

### Indexing the focus node

`idx:self true` feeds a field from the node the occurrence is evaluated against, rather
than from a path leading away from it. That node is the **entity** for a root occurrence
and the **child node** inside an `idx:nested` block.

```turtle
sh:property [ idx:field field:entityIri ; idx:self true ] ;
```

The value constraints still apply — they filter the focus node itself, so
`sh:nodeKind sh:IRI` on a self occurrence means "only index this child when it is an IRI".

A focus node is a resource, so a self-bound field must be `idx:KeywordField` or
`idx:TextField`; the numeric and temporal types have nothing to convert and are rejected at
config time. The exception is `idx:PeriodField`, which reads its start and end *from* the
focus node: see [Periods](#periods). Blank nodes yield no value at all — their labels are not stable across a
reload, so indexing them would be meaningless — and the field is simply absent from that
document.

A self occurrence contributes no predicates to change tracking, which is correct: its value
cannot change without the entity or the join that reaches it changing, and both are already
tracked.

The main use is a nested block whose child node is itself one of the values you want —
see [Pattern 4](#pattern-4--hierarchy-whose-level-is-the-child-node).

### Value constraints

The SHACL constraints on an occurrence filter the values it reaches, one value at a time.
They are evaluated by jena-shacl as a node shape applied to each value node; a value that
does not conform is not indexed, and nothing is reported.

```turtle
sh:property [ idx:field field:author ; sh:path ex:author ; sh:class ex:Person ] ;
sh:property [ idx:field field:code ; sh:path ex:code ; sh:pattern "^[A-Z]{3}-" ] ;
```

Accepted on an occurrence, with their SHACL meaning:

`sh:class`, `sh:datatype`, `sh:nodeKind`, `sh:in`, `sh:pattern` (with `sh:flags`),
`sh:minLength`, `sh:maxLength`, `sh:languageIn`, `sh:minInclusive`, `sh:maxInclusive`,
`sh:minExclusive`, `sh:maxExclusive`, `sh:node`, `sh:and`, `sh:or`, `sh:not`, `sh:xone`.

Rejected on an occurrence at config time, with an error naming the constraint:
`sh:hasValue`, `sh:minCount`, `sh:maxCount`, `sh:uniqueLang`, `sh:qualifiedValueShape`,
`sh:equals`, `sh:disjoint`, `sh:lessThan` and `sh:lessThanOrEquals`. Each describes the
set of values a path reaches, or compares it with another property's values, so it has no
meaning for one value. To index only one value, use `sh:in` with one member. These
constraints are accepted inside a [step's filter](#steps-following-a-path-through-shnode).

`sh:sparql`, SPARQL-based constraint components and `sh:closed` are rejected everywhere.

A `sh:property` on an occurrence is rejected. To index only the values whose own
properties conform, write `sh:node [ sh:property [ … ] ]` on the occurrence.

**`sh:class` follows `rdfs:subClassOf`.** `sh:class ex:Person` keeps a value typed
`ex:Student` when the data holds `ex:Student rdfs:subClassOf ex:Person`, as SHACL defines
it. The subclass triples are read from the graph the entity is built from. A change to an
`rdfs:subClassOf` triple is not tracked by the change listener: it can change which values
satisfy `sh:class` anywhere in the data, so after changing the class hierarchy, rebuild the
index. `sh:targetClass` still matches `rdf:type` exactly.

`sh:datatype` also requires the literal to be well formed for its datatype:
`"abc"^^xsd:integer` does not satisfy `sh:datatype xsd:integer`.

### Steps: following a path through sh:node

A `sh:property` with `sh:path` and `sh:node`, and no `idx:field`, is a **step**. From the
focus node it follows `sh:path`, keeps each reached node that conforms to the `sh:node`
shape's **filter**, and evaluates the field occurrences of the `sh:node` shape from each
kept node.

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

`field:collarId` receives `"2297211"` only. It is an ordinary field of the scope the step
is declared in: a root field at shape level, so it can be sortable, facetable and returned
by `luc:fields`, and a child field when the step is an `idx:property` inside
`idx:nested`. An entity with two kept nodes gives two values, and the field's
`idx:multiValued` setting applies as it does to any path.

Inside the `sh:node` shape:

| Member | Role |
|---|---|
| `sh:property` with `idx:field` | A field occurrence, evaluated from the kept node. Its own value constraints filter its values and do not decide whether the node is kept |
| `sh:property` with `sh:path` and `sh:node` whose shape declares a field occurrence | A further step, evaluated from the kept node |
| Any other `sh:property` | Part of the filter, with its SHACL meaning: `sh:hasValue` holds when at least one value equals it, `sh:minCount` counts values, and so on |
| A constraint on the shape itself (`sh:class`, `sh:in`, `sh:or`, …) | Part of the filter, applied to the kept node |

A `sh:property` whose `sh:node` shape declares no field occurrence is part of the filter:
`sh:property [ sh:path ex:org ; sh:node ex:ActiveShape ]` keeps a node whose every
`ex:org` conforms to `ex:ActiveShape`. At shape level and directly inside `idx:nested`, a
`sh:property` without `idx:field` that is not a step is an error, as before.

A step may not lead back to a `sh:node` shape that encloses it; that is a config-time
error. A step carries only `sh:path` and `sh:node`. Constraints on the reached nodes go inside the
`sh:node` shape. Paths inside a filter are limited to the forms listed under
[Paths](#paths), so that the change listener can follow them.

The change listener follows a step: a change to a triple on any path the filter reads, or
to an `rdf:type` checked by `sh:class` in it, rebuilds the entities whose step reaches the
changed node. Changing an identifier's `schema:propertyID` from `ex:CollarId` to another
kind removes its value from `field:collarId`.

### Periods

An `idx:PeriodField` holds periods: a start and an end read from **one** node, kept
together. The temporal operators query it as the span from the start's start to the end's
end; see [02-sparql-api.md → Temporal operators](02-sparql-api.md#temporal-operators).

Two date fields cannot do this. An entity with two periods has two starts and two ends,
and nothing in a pair of fields says which start goes with which end, so a tenement held
2010–2012 and again 2018–2020 would match "during 2011–2019" on the 2018 start and the 2012
end. A PeriodField indexes each period as one point, so it does not.

```turtle
field:validity
    idx:fieldName "validity" ;
    idx:fieldType idx:PeriodField ;
    idx:multiValued true .

ex:TenementShape
    sh:targetClass ex:Tenement ;
    sh:property [
        idx:field field:validity ;
        sh:path ex:validity ;                                # reaches the period node
        idx:periodStart ( time:hasBeginning time:inXSDDate ) ;
        idx:periodEnd   ( time:hasEnd time:inXSDDate ) ;
    ] .
```

`sh:path` (or `idx:self`) reaches the **period node**; `idx:periodStart` and
`idx:periodEnd` are paths from it, in any of the [path forms](#paths). Both go on the
occurrence, so one field can be fed from OWL-Time on one shape and from
`schema:startDate`/`schema:endDate` on another. When the start and end sit on the entity
itself, bind the entity with `idx:self`:

```turtle
sh:property [
    idx:field field:active ;
    idx:self true ;
    idx:periodStart schema:startDate ;
    idx:periodEnd   schema:endDate ;
] .
```

Reading a period node:

- **Spans.** The start and the end are temporal values with the spans of a
  [`TemporalField`](10-suggested-configuration.md#dates). A period from the date
  2020-01-01 to the date 2020-12-31 runs to 2021-01-01T00:00Z, the end of its last day.
- **A missing bound is open.** A period with a start and no end is ongoing, and one with
  an end and no start has always held. Open ends compare as infinities, and an open end
  equals an open end of a query interval. A node with neither bound gives no period.
- **Skipped and logged.** A period with two starts or two ends (nothing says which pairs
  with which), a bound that is not a date or a time, or an end before its start. The
  entity is still indexed, with its other periods.
- **Value constraints filter the period node**, not the start and end literals. To
  index only closed periods, where a missing end means "unknown" rather than "ongoing":
  `sh:node [ sh:property [ sh:path time:hasEnd ; sh:minCount 1 ] ]` on the occurrence.
- **Live updates** follow the start and end paths: changing the date two hops below the
  entity, or removing the link to the end, rebuilds the entity.

A PeriodField is for the temporal operators only. It is not stored, facetable, sortable,
default-searched or a level of a hierarchy, and it has no literal metadata; each of those
set `true` is an error at config time, as are `idx:periodStart` and `idx:periodEnd` on
any other field type, on a canonical field or on a step. `=`, `<` and `between` on it are
refused, since a period has two ends to compare; `t_equals` and `t_during` say which.

*[TestPeriodField](../jena-text/src/test/java/org/apache/jena/query/text/TestPeriodField.java), [TestPeriodFieldConfig](../jena-text/src/test/java/org/apache/jena/query/text/assembler/TestPeriodFieldConfig.java), [TestPeriodFieldOracle](../jena-text/src/test/java/org/apache/jena/query/text/TestPeriodFieldOracle.java)*

## Choosing an Analyzer for a TEXT Field

A `TEXT` field is only as searchable as its analyzer, and picking the wrong one produces a
field that silently matches nothing rather than an error.

Start here, because most fields do not want an analyzer override at all:

| Goal | Configuration |
|---|---|
| Find entities by words in their text | `TEXT`, **no** `idx:analyzer` — the index-wide `StandardAnalyzer` applies and BM25 ranks the hits |
| Filter to one value of a closed vocabulary (a person, an organisation, a status) | `KEYWORD` + `idx:facetable`, filtered with `=` on a value the UI offers from the facet list |
| Complete a value the user is typing from memory (an identifier, a code) | `TEXT` + `idx:analyzer [ a text:EdgeNGramAnalyzer ]` |
| Rank results while the user is still typing a word (a search box over titles) | The plain `TEXT` field plus a per-word edge-n-gram copy field — see [Search as you type](#search-as-you-type-an-n-gram-copy-field) |

Edge-n-grams are for the last two rows only. In place of a plain field they are the wrong
reach for a name: see [Names want BM25, not n-grams](#names-want-bm25-not-n-grams).

### Edge-n-gram modes

```turtle
## Whole-value prefixes — "RPT-MIA" reaches "RPT-MIA-2023-001", "2023" does not
field:identifierValueText
    idx:fieldName "identifierValueText" ;
    idx:fieldType idx:TextField ;
    idx:analyzer [ a text:EdgeNGramAnalyzer ] .

## fed by an occurrence on the shape:
##   sh:property [ idx:field field:identifierValueText ; sh:path schema:value ]
```

`text:tokenized true` switches to per-word n-grams, so any word of a multi-word value can
be prefix-matched and multi-word input matches as an adjacent phrase. Use it when the
values are segmented and users search by the segment they remember — dashed identifiers,
mostly. `text:minGram` (default 1) and `text:maxGram` (default 20) bound the n-gram
lengths; in per-word mode the whole word is also indexed, so words longer than `maxGram`
remain searchable in full.

### The query analyzer is implied

Do not set `idx:queryAnalyzer` on an edge-n-gram field. Running the n-gram analyzer over
the *query* as well as the index turns every input into a pile of prefixes and matches far
too much, so the field's mode selects its partner automatically:

| `idx:analyzer` | Implied `idx:queryAnalyzer` |
|---|---|
| `text:EdgeNGramAnalyzer` | `text:LowerCaseKeywordAnalyzer` — the input is one lowercased term |
| `text:EdgeNGramAnalyzer` with `text:tokenized true` | `text:StandardAnalyzer` — the input is split into words the same way the index was |
| anything else | none; the analyzer is its own correct query-side partner |

An explicit `idx:queryAnalyzer` still wins, for the cases these defaults do not cover.

### Choosing between whole-value and per-word

For a value with no punctuation or spaces — `"A9412"` — the two n-gram modes behave
identically. They diverge as soon as the value has more than one word or segment:

| Value | Input | whole-value | per-word |
|---|---|---|---|
| `Dr Sarah Jones` | `Dr Sar` | ✅ | ✅ |
| `Dr Sarah Jones` | `Jones` | ❌ | ✅ |
| `Dr Sarah Jones` | `Sarah Jones` | ❌ | ✅ |
| `RPT-MIA-2023-001` | `RPT-MIA` | ✅ | ✅ |
| `RPT-MIA-2023-001` | `2023` | ❌ | ✅ |

Whole-value is right when a value must be matched as an indivisible string and a mid-value
fragment should *not* hit. Per-word is right when the value has internal structure the user
navigates by.

`TestTypeaheadFieldConfigurations` exercises every row of both tables.

### Search as you type: an n-gram copy field

A search box that ranks results while the user is still typing a word needs prefix
matches, and a wildcard cannot rank them: `bore*` rewrites to a constant-score query, so
every hit scores the same. Keep the plain field and add a per-word edge-n-gram *copy field*
that indexes the same values, over the same path, both in `idx:defaultSearch`. The plain
field is the copy's *source field*:

```turtle
field:title
    idx:fieldName "title" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true .

field:titlePrefix
    idx:fieldName "titlePrefix" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true ;
    idx:boost 0.5 ;
    idx:copyOf field:title ;
    idx:stored false ;
    idx:analyzer [ a text:EdgeNGramAnalyzer ;
                   text:minGram 2 ; text:maxGram 20 ; text:tokenized true ] .

ex:ReportShape
    sh:property [ idx:field field:title       ; sh:path ex:title ] ;
    sh:property [ idx:field field:titlePrefix ; sh:path ex:title ] .
```

A whole word matches both fields and a prefix matches only the copy field, so a whole-word
hit scores on two clauses and a prefix-only hit on one. In the test fixture, `bore` ranks
every title holding the word, a twenty-word one included, above `Bores`, `Borehole` and
`Boredom`, and still grades those three rather than tying them; `water bor` ranks
`Water bore` first. That ordering is what BM25 gave on that data, not a guarantee: a long
field with one whole-word match can score below a short prefix-only hit. Check it on your
own data, and lower the copy field's `idx:boost` if prefixes rank too high.

- **`idx:boost 0.5`** halves the weight of the copy field's clause. The source field's BM25
  ranking is intact either way; the boost widens the margin by which a whole word beats a
  prefix. It is read at query time, so it can be tuned without a reindex.
- **`text:minGram 2`**, not the default 1. At 1 every word is also indexed by its first
  letter, so a single letter matches nearly every title. At 2 it matches only a one-letter
  word, such as `a`, as a whole word. This limits prefixes, not queries: if a search box
  should wait for two characters, it has to enforce that itself.
- **`idx:copyOf field:title`** makes `luc:match` report the copy field's matches under the
  title. A whole-word hit matches both fields and comes back as one `field:title` row; a
  prefix-only hit comes back as a `field:title` row too, with the title it is a prefix of.
- **`idx:stored false`**, because the value `luc:match` returns is read from the title.

The cost is index size: each word is indexed as up to `maxGram − minGram + 1` prefixes as
well as itself.

#### Declaring a copy field

`idx:copyOf` on the copy field names its source field: the field that indexes the same
values, analysed another way. Unlike Solr's `copyField`, nothing is copied at index time;
each field is fed by its own SHACL path, and the declaration states that the two hold the
same values.

`luc:match` reports a match on `field:titlePrefix` as one on `field:title`, once, with the
title's value as the title keeps it, language tag included. When both matched, the value is
the one the title's own words picked. If the title keeps no value and its copy does, the
copy's stands in. `luc:nestedMatch` projects a nested copy field under
its source field in the same way. Asking `luc:match` for `field:titlePrefix` by name returns
nothing; asking for `field:title` includes the prefix-only hits. Without `idx:copyOf`, each
field gets its own row and the copy field needs its own stored value.

Reporting a copy field's match as its source field's is only right if the source field holds
every value the copy indexes. The index checks that when it loads, and refuses the
configuration, naming the difference, unless:

- **Both are `TEXT` fields**, and not the same one.
- **They are fed alike.** In every shape that feeds the copy field, the source field is fed by
  equal occurrences: the same paths, the same steps with the same filters, the same value
  constraints, the same `idx:self` and the same `idx:nested` block. Two blocks with one join
  path are not the same block: each writes its own child documents. The order of an
  `sh:alternativePath`'s members does not matter here. The source field may also be fed by
  shapes that do not feed the copy field.
- **The source field keeps every value.** Either it is `idx:multiValued`, or neither field
  is and, in each scope, each is fed by one occurrence whose alternatives come in the same
  order. A field that is not `idx:multiValued` keeps only its first value, and the order two
  occurrences are read in is not fixed by the configuration.
- **The source field is not itself a copy field**, so a match is reported one hop away.

Fed alike is stricter than the same `sh:path`. A title read from `rdfs:label` is not the
source field of a prefix field read from `ex:author / rdfs:label`, nor of one with another
`sh:languageIn`. And inside a step's `sh:node` shape, a property shape without `idx:field`
filters the nodes the step reaches, while the same property shape with an `idx:field` filters
nothing, so two such steps reach different authors.

The analyzer is not checked, so the declaration suits any text copy of the same values, a
stemmed or folded one as well as an n-gram one. It changes nothing written to the index, so
adding or removing it needs no reindex. `luc:config` returns it as written:

```sparql
SELECT ?copy ?source WHERE {
  (?copy idx:copyOf ?source) luc:config ("default") .
}
```

This is not the n-gram field warned against below. That one *replaces* the plain field, so
nothing ranks by whole words any more; here the plain field still does, and the copy field
only adds the hits it cannot reach.

*[TestFieldBoost](../jena-text/src/test/java/org/apache/jena/query/text/TestFieldBoost.java),
[TestCopyFields](../jena-text/src/test/java/org/apache/jena/query/text/TestCopyFields.java),
[TestCopyFieldMatches](../jena-text/src/test/java/org/apache/jena/query/text/TestCopyFieldMatches.java)*

### Names want BM25, not n-grams

The tempting configuration for a person's name is a per-word edge-n-gram field in place of
the plain one, so that typing `Jones` completes `Dr Sarah Jones`. Do not do this. It costs:

- **Relevance.** Every value expands to ~10–20 tokens, IDF over prefixes is noise (`"s"`
  occurs everywhere) and length norms inflate. BM25 on the field stops meaning anything,
  which matters the moment it joins `idx:defaultSearch`.
- **Index size**, several times over, for every name in the corpus.
- **Wrong multi-word semantics.** `text_query` builds a phrase query, so `"Jones Sarah"`
  misses. Right for completion, wrong for someone searching a name.

And it still does not buy typo tolerance — `"Jonse"` fails under n-grams exactly as it
fails under BM25.

Beside the plain field rather than in its place, an n-gram copy field costs the index size but
not the ranking. That is [search as you type](#search-as-you-type-an-n-gram-copy-field), for a
search box that must rank while a word is half-typed. A name filter does not need it.

A plain `TEXT` field with no analyzer override does what a name filter actually needs.
`text_query` tokenises the input the same way the field was indexed, so `"Jones"` and
`"Sarah Jones"` both reach `"Dr Sarah Jones"`, ranked by BM25. Pair it with a `KEYWORD`
twin when you also want exact filtering and facet counts:

```turtle
## Exact side: equality filters and facet counts
field:authorName
    idx:fieldName "authorName" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

## Search side: BM25 over the same name, no analyzer override
field:authorNameText
    idx:fieldName "authorNameText" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true .

## Two occurrences, one path — the twin fields diverge on analysis, not on source:
<#MiningReportShape>
    sh:property [ idx:field field:authorName     ; sh:path ( ex:authoredBy ex:name ) ] ;
    sh:property [ idx:field field:authorNameText ; sh:path ( ex:authoredBy ex:name ) ] .
```

This holds inside an `idx:nested` scope too: a plain `TEXT` field there still folds
same-child with its sibling clauses, so "role = Principal Investigator AND agent matches
Sarah Jones" correlates onto one attribution record with no n-grams involved.
`TestCorrelatedNestedAttribution` covers it.

#### What about completion?

A picklist is worth it only when the vocabulary is genuinely small and fixed — a set of
roles or statuses, not a set of people. Completing a name means either shipping the
vocabulary to the client, which stops being viable somewhere in the low thousands, or a
query per keystroke. Prefer BM25 on whole words until there is a suggestion API worth
using; the natural one here is a prefix filter over facet values, evaluated in the facet
collector, which reuses the dictionary the index already has and respects the current
filter state.

An identifier is the opposite case: an open, near-unique vocabulary with no useful counts,
which the user types verbatim and expects to be completed. That is what edge-n-grams are
for.

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

Alternative path:

```turtle
sh:path [ sh:alternativePath ( ex:title ex:subtitle ) ] .
```

`sh:zeroOrMorePath`, `sh:oneOrMorePath` and `sh:zeroOrOnePath` are rejected at config
time, in occurrences, steps and filters alike: the change listener cannot walk them back to
the entity.

## Nested Child Records

`idx:nested` declares a repeated child collection on a shape. Each child becomes its own Lucene doc inside the entity's block; clauses targeting the same nested scope can be combined with same-child correlation at query time.

`idx:joinPath` enumerates child nodes from the parent. Field occurrences inside the `idx:nested` block are evaluated relative to the child node, not the parent.

### Block properties

| Property | Required | Meaning |
|---|---|---|
| `idx:joinPath` | yes¹ | SHACL path enumerating child nodes from the parent |
| `idx:externalSource` | yes¹ | Children come from a tabular source instead — see [External Content](#external-content-csvtsv) |
| `idx:property` | yes² | Repeatable field occurrence, evaluated relative to the child node |
| `idx:nestedName` | see below | The block's **scope name** |
| `idx:facetHierarchy` | no | Hierarchy whose levels are correlated per child record |

¹ Exactly one of `idx:joinPath` / `idx:externalSource`.
² For a `idx:joinPath` block. An external block's fields come from its `idx:column` bindings instead, and `idx:property` there is an error.

**The scope name** identifies the block. It is written to every child document as
`_nestedScope`, and it is what lets the query path tell "these clauses must be satisfied by
*one* child" from "any children will do", recover a child's field definitions when
projecting `luc:nestedMatch`, and restrict a nested sort selector to the right block.

- For an `idx:joinPath` block the name is **derived from the path** — you do not write it,
  and an `idx:nestedName` there is ignored.
- For an `idx:externalSource` block it is **required**: there is no join path to derive it
  from. Omitting it is a config-time error.

It is an opaque label, not an IRI: nothing in the data denotes it, no query names it (the
scope is always inferred from the field), and the derived form is a path string. Pick
something short and descriptive. Because it lands in the index, renaming it invalidates
existing child documents — reindex after a change.

### Pattern 1 — Qualified identifier (both children are KEYWORD)

`schema:identifier` records carrying `(propertyID, value)` pairs:

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

Inside a nested block the occurrence paths are relative to the **child** node, so
`schema:propertyID` here is read from each `schema:identifier` record, not from the borehole.

Query-time same-child correlation is not limited to `=`. AND-ed leaves that target the same nested scope fold into one block join, so `=`, ranges (`<`, `>`, `<=`, `>=`), `in`, `between`, `like` and `text_query` all correlate within a single child — see [02-sparql-api.md → Nested same-child filters](02-sparql-api.md#nested-same-child-filters).

### Pattern 2 — Identifier with text/typeahead on a child field

Add a second occurrence of the value field with an analyzer-backed `TEXT` field. The exact and text fields share the SHACL path but produce different Lucene fields:

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

At query time, combine `=` on `identifierType` with `text_query` on `identifierValueText` in the same CQL subtree (see [02-sparql-api.md](02-sparql-api.md#text_query--analyzer-aware-text-matching)).

The exact and text fields can coexist on the same child path — index-time, each value writes both a raw keyword term and the analyzed tokens to its child doc.

### Pattern 3 — Qualified attribution (prov)

`prov:qualifiedAttribution` records carrying `(hadRole, agent)`:

```turtle
field:attributionRole
    idx:fieldName "attributionRole" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

field:attributionAgentExact
    idx:fieldName "attributionAgentExact" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

<#MiningReportShape>
    sh:targetClass ex:MiningReport ;
    idx:nested [
        idx:joinPath prov:qualifiedAttribution ;
        idx:property [ idx:field field:attributionRole ; sh:path ( prov:hadRole rdfs:label ) ] ;
        idx:property [ idx:field field:attributionAgentExact ; sh:path ( prov:agent rdfs:label ) ] ;
        idx:property [ idx:field field:attributionAgentText ; sh:path ( prov:agent rdfs:label ) ] ;
    ] .
```

Role is a fixed vocabulary, so it filters with `=`. The agent name filters with
`text_query` against the plain `TEXT` twin: `"Sarah Jones"` reaches `"Dr Sarah Jones"`,
and the two clauses still fold same-child, so a report surfaces only when one attribution
record carries both. The `KEYWORD` twin stays for exact filtering and facet counts.

No n-gram field on the agent, on purpose — see
[Names want BM25, not n-grams](#names-want-bm25-not-n-grams).
`TestCorrelatedNestedAttribution` pins the correlation for `=`, for `text_query` on a
plain field, and for the n-gram configurations that are permitted but not advised.

### Pattern 4 — Hierarchy whose level is the child node

A taxonomy is often attached to a *term* rather than to the entity: the entity references
some term, and the term belongs to a broader grouping. Faceting on both, correlated, needs
the term itself as a hierarchy level.

```turtle
# instance data — a report may be tagged with several commodities
ex:report1  dcterms:subject  ex:copper , ex:gold .

# vocabulary — each commodity has a broader group
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

The child node **is** the commodity concept, so its occurrence binds the focus node; the
group is one `skos:broader` step off it. Each child record carries one commodity and its
group, so the hierarchy is pairwise correct: `ex:report1` produces exactly its two real
paths, `BaseMetals / copper` and `PreciousMetals / gold`.

Declaring the two fields as **root** occurrences instead — `sh:path dcterms:subject` and
`sh:path ( dcterms:subject skos:broader )` — would read each level's values independently
and emit the cartesian product of the two, including `BaseMetals / gold`, which is not in
the data. Drilling into a group would then offer commodities that are not in it, and the
children would out-count the parent.

`sh:path skos:broader` is one step, so each level of the hierarchy is a fixed hop from the
concept. A broader chain of unknown depth (`skos:broader+`) cannot be declared as a
hierarchy.

Both fields are child-scoped here, which is what makes them correlated, and it is also
what determines how they behave elsewhere: they filter same-child, and they are not
available as entity-level flat facets. If you also want a flat facet over the term across
all entities, declare a **separate field** with its own name fed by a root occurrence on
`dcterms:subject`. One field cannot be both — see the first rule below.

### Rules

- One field IRI belongs to one scope: either root or one nested collection.
- Scope names are resolved across the whole mapping, so an explicit `idx:nestedName` must be unique among all `idx:nested` blocks in the index, not just within its shape.
- `idx:joinPath` may be a simple predicate, an inverse predicate, or a sequence of predicate steps. It does not support alternative paths.
- Both the exact-keyword and edge-ngram-text variants can sit on the same SHACL path — they are different Lucene fields driven by their own analyzers.
- `idx:facetHierarchy` inside an `idx:nested` block defines a hierarchy whose levels are correlated per child record (no cartesian products). On a shape, the levels are read independently from the entity and cross-produced — correct when they are independent, wrong when they are pairwise related, which is what [Pattern 4](#pattern-4--hierarchy-whose-level-is-the-child-node) is for.
- `idx:self` binds the focus node: the child node inside `idx:nested`, the entity at root. It replaces `sh:path` rather than accompanying it, and is not available on an `idx:column` — an external child is a row, not a node.
- A field named in an `idx:facetHierarchy` keeps its own flat facet dimension. Faceting on the field IRI returns that field's counts across all parents; faceting on the hierarchy's dimension name returns its top level, or the children of a drill-down path. The two are addressed separately and neither shadows the other.
- Faceting on a field that is not `idx:facetable` is an error — there is no dimension to answer from.

## External Content (CSV/TSV)

An `idx:nested` block can draw its children from a **tabular file** instead of the graph, joined to the entity on the entity IRI. Use it when an attribute set is large, authoritative somewhere else, and needed only as search machinery — range filters, range facets and sort — with the values themselves retrieved from the source of truth.

Design note: [2026-07-27_external_content_indexing_design.md](2026-07-27_external_content_indexing_design.md).

A nested block has **either** `idx:joinPath` **or** `idx:externalSource`, never both.

### Configuration

```turtle
field:measuredProperty
    idx:fieldName "measuredProperty" ;
    idx:fieldType idx:KeywordField ;
    idx:indexed   true ;
    idx:facetable true ;
    idx:stored    true .        # short, non-volatile label — safe to store

field:measuredValue
    idx:fieldName "measuredValue" ;
    idx:fieldType idx:DoubleField ;
    idx:indexed   true ;        # range filters -> DoublePoint
    idx:facetable true ;        # range facets
    idx:sortable  true ;        # sort selector -> docvalues
    idx:stored    false .       # values live in the source of truth

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
        idx:facetHierarchy ( field:measuredProperty field:measuredBand ) ;
    ] .
```

Bound fields carry **no `sh:path`** — their values come from the column. There is no `idx:external` flag: a field is external because a column binds it, exactly as a field is nested because it appears in an `idx:nested` block.

`idx:nestedName` is required here, and only here: with no `idx:joinPath` there is nothing to derive the block's scope name from. See [Block properties](#block-properties).

### Source properties

| Property | Required | Meaning |
|---|---|---|
| `idx:format` | yes | `idx:CsvFile` or `idx:TsvFile` |
| `idx:location` | yes | Path, or a glob such as `/data/meas-*.csv` (read in filename order) |
| `idx:subjectColumn` | yes¹ | Column holding the entity IRI, or the key to be prefixed |
| `idx:subjectColumnIndex` | yes¹ | Zero-based subject column, when `idx:headerless` is true |
| `idx:subjectPrefix` | no | String prepended to the subject column value. Concatenation only |
| `idx:delimiter` | no | Single-character delimiter override |
| `idx:headerless` | no | No header row; bind columns with `idx:columnIndex`. Default `false` |
| `idx:onError` | no | `"skip"` (default, counted) or `"fail"` |
| `idx:column` | yes | Repeatable binding: `idx:columnName` **or** `idx:columnIndex`, plus `idx:field` |
| `idx:delta` | no | Delta file(s) applied over the base at build time. Several must be an ordered list |
| `idx:opColumn` | no | Column holding `ADD`/`DELETE` in a delta. Default `"op"` |

¹ `idx:subjectColumn` with a header, `idx:subjectColumnIndex` when headerless.

Columns may bind `TEXT`, `KEYWORD`, `INT`, `LONG` and `DOUBLE` fields. `TEMPORAL` and `LATLON` are rejected at config time — they need literal metadata or WKT handling a bare cell cannot carry unambiguously.

### Input shape

One row per measurement, joined on the entity IRI:

```
sample_iri,property,value
https://ex.org/sample/A1,Au,12.4
https://ex.org/sample/A1,Cu,0.7
https://ex.org/sample/A2,Au,0.3
```

Each row becomes one child document. Two field IRIs cover any number of measured properties, and a new property in the source needs no config change.

### Wide children — one row is one event

`idx:column` is repeatable without limit, and **every bound column lands on the same child document**. Bind four and the child stops being a property/value pair and becomes the measurement event itself:

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

All four fields then correlate in one block join — the same-scope fold groups every AND-ed leaf in a nested scope with no arity limit:

```json
{"op":"and","args":[
  {"op":"=", "args":[{"property":"urn:jena:lucene:field#analyte"},"Au"]},
  {"op":">=","args":[{"property":"urn:jena:lucene:field#value"},1.0]},
  {"op":">=","args":[{"property":"urn:jena:lucene:field#depthFrom"},0]},
  {"op":"<=","args":[{"property":"urn:jena:lucene:field#depthTo"},10]}
]}
```

"Au above 1 g/t in the 0–10 m interval" — one child, exact. A hole with a Cu result at 0–10 m *and* a separate interval starting at 10 m does **not** match `analyte = "Cu" AND depthFrom >= 10`, because no single child satisfies both.

**The grain of the row sets what correlates.** Widening the child moves the boundary; it does not remove it. Two analytes are still two rows, so "Au and Cu in the *same* interval" remains unanswerable as a same-child query — `analyte = "Au" AND analyte = "Cu"` matches nothing, since one child has one analyte. If that question matters, the row must carry both analytes as separate columns.

**Every bound column must be populated on every row.** A row missing any one bound cell is dropped whole (see the rules below), which is a stricter constraint on a four-column extract than on a two-column one.

### Sort order

There is nothing to configure. The build needs external rows grouped and ascending by
subject — Lucene has no partial document update, so all of an entity's children must be
in hand before its block join is written — and it establishes that order itself.

Rows are read, sorted by subject and, if there are more than fit in memory, spilled to
temp files and merged. An input small enough to buffer never touches disk. Memory is
bounded by the buffer rather than the input, so a source of any size is safe, and the
export order of the source does not affect the result.

This used to be the `idx:sorted` assertion, which required pre-sorting the file with
`LC_ALL=C sort`. The pitfall was that byte order and the obvious `ORDER BY` rarely
agree: exporting with `ORDER BY collar_id` on an integer column yields `1175968` before
`117597`, which is numerically right and lexically wrong. That is no longer something
anyone has to know.

The sort is stable, so rows sharing a subject keep their input order — which matters
because duplicate `(subject, property)` rows are legal.

`jena.text.external.sortBufferRows` (default 200,000) sets how many rows are held before
spilling. It is a tuning knob for memory-constrained hosts, not something a normal
deployment sets.

### Deltas

A delta file carries only what changed. It is applied over the base at build time, so
the indexer still sees each entity's complete child set — which is what it needs, since
a Lucene block is written whole and there is no partial document update.

```turtle
idx:delta ( "data/2026-07-a.csv" "data/2026-07-b.csv" ) ;   # applied in this order
idx:opColumn "op" ;                                          # default
```

Same columns as the base, plus an operation column:

```
op,borehole,analyte,grade,units,below_detection
DELETE,http://ex.org/bh-1,Ag,44.9,ppm,f
ADD,http://ex.org/bh-1,Ag,51.3,ppm,f
DELETE,http://ex.org/bh-2,Mn,,,
```

| | |
|---|---|
| `DELETE` | matches on the columns it fills in; an **empty cell is a wildcard**. The third row above removes *every* Mn measurement of `bh-2` |
| Numeric matching | by value, not lexical form — `0.70` deletes `0.7` |
| `ADD` | **appends**; it is not an upsert, because duplicate rows for the same property are legal and so there is no key to upsert on. Replace = DELETE then ADD |
| Ordering | deletes apply before adds within a subject, so row order in the file cannot change the outcome |
| Unmatched delete | counted and logged, not an error — deltas get replayed and overlap |

Deltas require a header row, because the operation column is bound by name. They do
not require the base or the delta to be in any particular order — see
[Sort order](#sort-order). Several deltas must be given as an RDF **list** —
they apply in order and RDF puts no order on repeated properties.

This is still a **full rebuild**; the delta removes the need to physically merge base
and deltas into a new snapshot first, not the need to rebuild. Rebuilding only the
affected entities is future work.

### Rules and limits

- **Bulk build only.** `ShaclBulkIndexer` is the only path that populates external children. A live graph change to an entity of such a shape is refused with a warning — rebuilding from the graph alone would silently strip its children, and Lucene has no partial document update. Re-run the bulk indexer.
- **Rows augment entities; they never create them.** A row whose subject matches no entity is counted and dropped. The graph and the extract are equally valid sources that we align, and neither is obliged to cover the other, so the indexer never judges how well they overlap. Match and unmatch counts and the resulting match rate are always logged — a wrong `idx:subjectPrefix` shows up as a near-zero rate. Fix it in the data, not with a threshold.
- **An entity with no rows is still indexed**, with its graph fields and no children.
- **A row with an empty or unparseable bound cell is dropped whole**, never coerced to `0` and never emitted as a half-populated child.
- **No transformations.** No units, no `<0.5` detection-limit markers, no null sentinels, no computed columns. A cell either parses as its declared type or it is an error. That work belongs upstream, in whatever produced the file.
- **`idx:stored false` costs the value binding only.** Filters, range facets and sort all still work; `luc:match` has nothing to return. Note this removes *display* staleness, not filter staleness — the index is still a snapshot, so rebuild cadence must match the source's release cadence.
- **Same-child correlation is per row.** Every bound column of a row lands on one child, and all of them correlate. Clauses spanning *two* rows are two block joins ANDed at the entity: "has some Au above 0.5 **and** some Cu above 100", not "in the same measurement event". See [Wide children](#wide-children--one-row-is-one-event).

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

<#index> a text:TextIndexShacl ;
    text:indexId "default" ;
    text:directory "mem" ;
    text:storeValues true ;
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

`idx:` is `urn:jena:lucene:index#` — the same namespace SPARQL prefixes as `luc:`. The
convention is `idx:` for configuration and `luc:` for the property functions; nothing
enforces it.

Every term the assembler reads, and where it is defined above:

| Term | Goes on | Section |
|---|---|---|
| `idx:field` | occurrence, `idx:column` | [Occurrence properties](#occurrence-properties) |
| `idx:self` | occurrence | [Indexing the focus node](#indexing-the-focus-node) |
| `idx:periodStart` `idx:periodEnd` | `PeriodField` occurrence | [Periods](#periods) |
| `idx:fieldName` `idx:fieldType` | canonical field | [Field Properties](#field-properties) |
| `idx:stored` `idx:indexed` `idx:facetable` `idx:sortable` `idx:multiValued` `idx:defaultSearch` | canonical field | [Field Properties](#field-properties) |
| `idx:analyzer` `idx:queryAnalyzer` `idx:normalizer` | canonical field | [Field Properties](#field-properties) |
| `idx:storeLiteralMetadata` | canonical field | [Field Properties](#field-properties) |
| `idx:docIdField` `idx:discriminatorField` | shape | [Shapes](#shapes) |
| `idx:facetHierarchy` | shape, `idx:nested` | [Shapes](#shapes), [Block properties](#block-properties) |
| `idx:nested` | shape | [Nested Child Records](#nested-child-records) |
| `idx:joinPath` `idx:nestedName` `idx:property` | `idx:nested` | [Block properties](#block-properties) |
| `idx:externalSource` | `idx:nested` | [External Content](#external-content-csvtsv) |
| `idx:format` `idx:location` `idx:subjectColumn` `idx:subjectColumnIndex` `idx:subjectPrefix` `idx:delimiter` `idx:headerless` `idx:onError` `idx:column` `idx:delta` `idx:opColumn` | `idx:externalSource` | [Source properties](#source-properties) |
| `idx:columnName` `idx:columnIndex` | `idx:column` | [Source properties](#source-properties) |

Resources rather than properties:

| Term | Used as |
|---|---|
| `idx:TextField` `idx:KeywordField` `idx:IntField` `idx:LongField` `idx:DoubleField` `idx:TemporalField` `idx:LatLonField` `idx:PeriodField` | `idx:fieldType` value |
| `idx:DateField` `idx:DateTimeField` | deprecated aliases for `idx:TemporalField` |
| `idx:CsvFile` `idx:TsvFile` | `idx:format` value |

Property function IRIs in the same namespace: `luc:query`, `luc:facet`, `luc:match`,
`luc:nestedMatch` — see [02-sparql-api.md](02-sparql-api.md). Note that `luc:nestedMatch` is
deliberately **not** named `luc:nested`: that IRI is already the `idx:nested` config
predicate, and a property function registered on it would intercept any query reading a
config graph.

Terms that do **not** exist, despite appearing in older design notes: `idx:external`
(externality follows from an `idx:column` binding), `idx:sorted` (asserting a pre-sorted
source is not implemented; the loader sorts), and `idx:path` (use `sh:path`).
