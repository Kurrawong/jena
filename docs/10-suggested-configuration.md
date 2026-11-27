# Suggested Configuration

This page gives a field configuration for each kind of data. The terms are defined in
[03-configuration.md](03-configuration.md). Each recipe names the tests that cover it.

## Principles

- The graph holds the values, and the index finds entities. Set `idx:stored true` only on
  fields that `luc:match` or `luc:nestedMatch` must return.
- A change to a relevant triple rebuilds the entity's document, so stored values follow
  the graph.
- Each flag controls one structure:

  | Flag | Writes |
  |---|---|
  | `idx:indexed` | Searchable terms or points |
  | `idx:stored` | A stored copy of the value |
  | `idx:facetable` | Facet doc values |
  | `idx:sortable` | Sort doc values |

- For exact match and free-text search on the same values, use two fields over one path.
  One field with one analyzer cannot do both.
- A field definition holds the type and flags. A shape's `sh:property` holds the path. One
  field can be used in many shapes.
- Flags take effect when a document is written. After you change a type, analyzer or flag,
  rebuild the index.

## Matrix

`idx:indexed` and `idx:stored` default to `true`. In the table, `✗` means set the flag to
`false`. `—` means leave it unset.

| Data kind | Example | `idx:fieldType` | stored | facetable | sortable | Classic `text:query` mode |
|---|---|---|---|---|---|---|
| Title or label, searched | `rdfs:label` of a report | `TextField` | ✓ | — | — | ✓ |
| Name, exact match and counts | author, operator, publisher | `KeywordField` | ✗ | ✓ | ✓ with `idx:normalizer` | Exact match only |
| Description or abstract | `dcterms:description` | `TextField` | ✓ | — | — | ✓ |
| Identifier or code, exact | `RPT-MIA-2023-001` | `KeywordField` | ✗ | ✓ if counted | — | Exact match only |
| Identifier, prefix typeahead | `RPT-MIA` | `TextField` | ✗ | — | — | With `text:GenericAnalyzer` |
| Vocabulary term | `Gold`, `Approved`, `WA` | `KeywordField` | ✗ | ✓ | ✓ if sorted on | Exact match only |
| Level of a facet hierarchy | `country`, then `state` | `KeywordField` | ✗ | ✓ | — | ✗ |
| Entity class | `rdf:type` → `Borehole` | `KeywordField` | ✗ | ✓ | — | ✗ |
| Year or count | `2023`, `42` | `IntField` | ✗ | ✓ | ✓ | ✗ |
| Measurement, grade or score | `12.4` | `DoubleField` | ✗ | ✓ | ✓ | ✗ |
| Full date or timestamp | `2023-04-01`, `2023-04-01T09:00:00Z` | `TemporalField` | ✗ | ✓ | ✓ | ✗ |
| Geometry | `POINT(151.2 -33.9)` | `LatLonField` | ✗ | — | Raises | ✗ |
| Repeated correlated record | an assay, an observation, a qualified identifier | Ordinary fields in an [`idx:nested`](#observations-sosa-style) block | ✗ | ✓ | ✓ | ✗ |

Filters, facets and sorts read points and doc values. `idx:stored` affects only what
`luc:match` and `luc:nestedMatch` return.

Store searchable text fields. `luc:match` returns the field that matched. For a
`TextField`, it returns the value only if the field is stored. When an entity has several labels or descriptions,
`luc:match` returns the one that matched.

### Classic `text:query` mode

The last column is upstream Jena's `text:entityMap` and `text:query` mode. It has no field
types. `TextIndexLucene` writes each value as an analyzed text field keyed by predicate.
It writes no points, doc values or facet fields, so it has no facets, sorting, range
filters, nesting or spatial filters.

"Exact match only": pair the predicate with `text:KeywordAnalyzer` or
`text:LowerCaseKeywordAnalyzer`, and `text:query` finds entities by the whole value. It
cannot count or sort by the value. Edge n-grams need a tokenizer assembled with
`text:GenericAnalyzer`. The `text:EdgeNGramAnalyzer` shorthand exists only in SHACL mode.

## Query syntax on a TEXT field

Lucene's classic
[`QueryParser`](https://lucene.apache.org/core/10_3_1/queryparser/org/apache/lucene/queryparser/classic/package-summary.html#package.description)
parses `queryString`. `MultiFieldQueryParser` parses it when `fieldSpec` names several
fields. BM25 scores the hits. These forms need no configuration:

| Form | Example |
|---|---|
| Phrase | `"machine learning"` |
| Boolean | `machine AND learning`, `physics OR translation` |
| Required and prohibited | `+learning -physics` |
| Wildcard | `quan*`, `qu?ntum`, `*tum` (leading wildcards are enabled) |
| Fuzzy | `learnimg~1` |
| Proximity | `"machine networks"~2` |
| Boost | `quantum^4 learning` |
| Grouping | `(machine OR deep) AND learning` |
| Term range | `[a TO m]` |
| Match all | `*` |

`quan*` works without an n-gram field. Add an n-gram field when prefix matches must be
ranked and fast.

The field's query analyzer analyzes the string. On a `KEYWORD` field, the whole value is
one term. Select fields with `fieldSpec`. A `fieldName:` prefix in the query string uses
the internal `idx:fieldName`. Put structured conditions in `cqlFilter`.

*[TestLuceneQuerySyntax](../jena-text/src/test/java/org/apache/jena/query/text/TestLuceneQuerySyntax.java)*

## Titles and names

A label that is only searched needs one field:

```turtle
field:title
    idx:fieldName "title" ;
    idx:fieldType idx:TextField ;
    idx:stored true ;
    idx:defaultSearch true .
```

The field is stored, so `luc:match` can show which label matched.

A name that is also a filter value, such as an author, operator or publisher, needs two
fields over one path. One field is searched as text. The other gives the facet values:

```turtle
field:authorNameText
    idx:fieldName "authorNameText" ;
    idx:fieldType idx:TextField ;
    idx:stored true ;
    idx:defaultSearch true .

field:authorName
    idx:fieldName "authorName" ;
    idx:fieldType idx:KeywordField ;
    idx:stored false ;
    idx:facetable true ;
    idx:sortable true ;
    idx:normalizer [ a text:LowerCaseKeywordAnalyzer ] .

<#MiningReportShape>
    sh:property [ idx:field field:authorNameText ; sh:path ( ex:authoredBy ex:name ) ] ;
    sh:property [ idx:field field:authorName     ; sh:path ( ex:authoredBy ex:name ) ] .
```

`idx:normalizer` lowercases the indexed term and the sort key. The facet label keeps the
original case.

Do not use n-grams on a name. They create many terms, distort ranking, and make `"Jon"`
match `"Jones"`, `"Jonathan"` and `"Jonsson"` equally.

*[TestKeywordNormalizer](../jena-text/src/test/java/org/apache/jena/query/text/TestKeywordNormalizer.java), [TestKeywordNormalizerTwinField](../jena-text/src/test/java/org/apache/jena/query/text/TestKeywordNormalizerTwinField.java), [TestKeywordRawSortAndExactMatch](../jena-text/src/test/java/org/apache/jena/query/text/TestKeywordRawSortAndExactMatch.java)*

## Identifiers and codes

```turtle
field:identifier
    idx:fieldName "identifier" ;
    idx:fieldType idx:KeywordField ;
    idx:stored false ;
    idx:facetable true .

field:identifierPrefix
    idx:fieldName "identifierPrefix" ;
    idx:fieldType idx:TextField ;
    idx:stored false ;
    idx:analyzer [ a text:EdgeNGramAnalyzer ] .   # n-grams of the whole value
```

With whole-value n-grams, `"RPT-MIA"` finds `RPT-MIA-2023-001` and `"2023"` does not.
With `text:tokenized true` on the analyzer, each word gets n-grams, so `"2023"` also
matches. It then matches every 2023 report.

With no `idx:queryAnalyzer`, the field gets the query analyzer that suits the n-gram mode.

*[TestTypeaheadFieldConfigurations](../jena-text/src/test/java/org/apache/jena/query/text/TestTypeaheadFieldConfigurations.java)*

## Descriptions

```turtle
field:description
    idx:fieldName "description" ;
    idx:fieldType idx:TextField ;
    idx:stored true ;
    idx:defaultSearch true .
```

Store descriptions so that a result can show which description matched. Set
`idx:stored false` only when the stored text is a large part of the index and the
application can read the text from the graph by IRI.

*[TestTextMatchPF](../jena-text/src/test/java/org/apache/jena/query/text/TestTextMatchPF.java), [TestShaclLucQueryRawValueOnMultiValuedField](../jena-text/src/test/java/org/apache/jena/query/text/TestShaclLucQueryRawValueOnMultiValuedField.java)*

## Vocabularies and taxonomies

A vocabulary term is one value from a closed list. Add `idx:multiValued true` if the
predicate repeats:

```turtle
field:status
    idx:fieldName "status" ;
    idx:fieldType idx:KeywordField ;
    idx:stored false ;
    idx:facetable true .
```

For a hierarchy, define one field for each level. `idx:facetHierarchy` lists the levels
from parent to child, so a facet can go from a country to its states:

```turtle
field:country
    idx:fieldName "country" ;
    idx:fieldType idx:KeywordField ;
    idx:stored false ;
    idx:facetable true .

field:state
    idx:fieldName "state" ;
    idx:fieldType idx:KeywordField ;
    idx:stored false ;
    idx:facetable true .

<#SiteShape>
    sh:property [ idx:field field:country ; sh:path ex:country ] ;
    sh:property [ idx:field field:state   ; sh:path ex:state ] ;
    idx:facetHierarchy ( field:country field:state ) .
```

Each level also stays a separate facet, so a facet on `field:state` alone works.

Use `KeywordField` for vocabulary terms. On a `TextField`, `=` compiles to a term query for
the whole value, for example `"Iron Ore"`. The analyzer never writes that term, so the
filter matches nothing and reports no error.

*[TestHierarchicalFacets](../jena-text/src/test/java/org/apache/jena/query/text/TestHierarchicalFacets.java), [TestHierarchicalFacetsSparql](../jena-text/src/test/java/org/apache/jena/query/text/TestHierarchicalFacetsSparql.java), [TestNativeFacetCounts](../jena-text/src/test/java/org/apache/jena/query/text/TestNativeFacetCounts.java)*

## Entity class

```turtle
field:entityType
    idx:fieldName "entityType" ;
    idx:fieldType idx:KeywordField ;
    idx:stored false ;
    idx:facetable true .

<#MiningReportShape>
    sh:property [ idx:field field:entityType ; sh:path rdf:type ] .
```

Bind this field in every shape. One search covers all shapes, and a facet on this field
gives counts per class, for example 23 reports and 51 boreholes, in the same request. A
path whose values are IRIs indexes each IRI as a string. `idx:discriminatorField` is a
separate, internal field that scopes deletes.

## Numbers and measurements

```turtle
field:grade
    idx:fieldName "grade" ;
    idx:fieldType idx:DoubleField ;
    idx:stored false ;
    idx:facetable true ;
    idx:sortable true .
```

Each query gives its own range facet boundaries, so new ranges need no rebuild of the
index. A numeric field is counted only in ranges.

`idx:facetable` or `idx:sortable` writes numeric doc values even with `idx:indexed false`.
Such a field can be counted and sorted, and a filter on it raises `TextIndexException`.
Leave `idx:indexed` at its default.

*[TestRangeFacetCounts](../jena-text/src/test/java/org/apache/jena/query/text/TestRangeFacetCounts.java)*

## Dates

Use `TemporalField` for a full date or timestamp, typed `xsd:date` or `xsd:dateTime`. Use
`IntField` for a year alone.

```turtle
field:publishedOn
    idx:fieldName "publishedOn" ;
    idx:fieldType idx:TemporalField ;
    idx:stored false ;
    idx:facetable true ;
    idx:sortable true ;
    idx:storeLiteralMetadata true .   # required on TemporalField
```

The index writes each value as epoch milliseconds in a companion field, so filters and
sorts are numeric. A date without a time is the start of that day in UTC. Range facet
boundaries are ISO strings.

`idx:storeLiteralMetadata true` is required on every `TemporalField`, stored or not. The
index fails to load without it (`ShaclIndexMapping.validateLiteralMetadataRequirements`).

A date that cannot be parsed is logged. The entity stays in the index, but range filters
on that field do not match it, so results are smaller and no error is reported. Validate
dates before loading.

Use `TemporalField` for dates that are sorted or filtered. A `KeywordField` sorts dates
correctly only when they are zero-padded ISO strings, and it supports no range filters.

*[TestDateLiteralRoundTrip](../jena-text/src/test/java/org/apache/jena/query/text/TestDateLiteralRoundTrip.java), [TestRangeFacetCounts](../jena-text/src/test/java/org/apache/jena/query/text/TestRangeFacetCounts.java)*

## Observations (SOSA-style)

```turtle
field:observedProperty
    idx:fieldName "observedProperty" ;
    idx:fieldType idx:KeywordField ;
    idx:stored false ;
    idx:facetable true .

field:resultValue
    idx:fieldName "resultValue" ;
    idx:fieldType idx:DoubleField ;
    idx:stored false ;
    idx:facetable true ;
    idx:sortable true .

field:resultUnits
    idx:fieldName "resultUnits" ;
    idx:fieldType idx:KeywordField ;
    idx:stored false ;
    idx:facetable true .

<#SampleShape>
    sh:targetClass sosa:Sample ;
    sh:property [ idx:field field:title ; sh:path rdfs:label ] ;
    idx:nested [
        idx:joinPath [ sh:inversePath sosa:hasFeatureOfInterest ] ;
        idx:property [ idx:field field:observedProperty ; sh:path ( sosa:observedProperty rdfs:label ) ] ;
        idx:property [ idx:field field:resultValue      ; sh:path sosa:hasSimpleResult ] ;
        idx:property [ idx:field field:resultUnits      ; sh:path ( sosa:hasResult qudt:unit ) ] ;
        idx:facetHierarchy ( field:observedProperty field:resultUnits ) ;
    ] .
```

Each observation is a separate child document. AND-ed clauses on fields of one nested
block must all hold on the same observation. For example, "copper above 1%" does not
match a sample with copper at 0.2% and lead at 5%. A flat, multi-valued field would match
it. Correlation applies to `=`, ranges, `in`, `between`, `like` and `text_query`.

Every level of an `idx:facetHierarchy` in a nested block must be bound by an
`idx:property` in the same block. The index fails to load otherwise.

For millions of observations that are not in the graph, use `idx:externalSource`. Each
CSV row becomes one child document, with the same correlation. See
[External Content](03-configuration.md#external-content-csvtsv).

`luc:nestedMatch` returns stored child fields only. It does not return a child record that
has no stored fields. For children from the graph, read their values from the graph. For
children from an `idx:externalSource`, store the fields, because there are no triples to
read.

Do not use a nested block for a single field. Use a multi-valued field.

*[TestNestedJoinPathSupport](../jena-text/src/test/java/org/apache/jena/query/text/TestNestedJoinPathSupport.java), [TestCorrelatedNestedAttribution](../jena-text/src/test/java/org/apache/jena/query/text/TestCorrelatedNestedAttribution.java), [TestNestedHierarchicalFacets](../jena-text/src/test/java/org/apache/jena/query/text/TestNestedHierarchicalFacets.java),
[TestNestedMatchProjection](../jena-text/src/test/java/org/apache/jena/query/text/TestNestedMatchProjection.java), [TestNestedSortSelector](../jena-text/src/test/java/org/apache/jena/query/text/TestNestedSortSelector.java), [TestExternalContentIndexing](../jena-text/src/test/java/org/apache/jena/query/text/TestExternalContentIndexing.java),
[TestDownholeMeasurementCsv](../jena-text/src/test/java/org/apache/jena/query/text/TestDownholeMeasurementCsv.java)*

## Geometry

```turtle
field:location
    idx:fieldName "location" ;
    idx:fieldType idx:LatLonField ;
    idx:stored false .

<#SiteShape>
    sh:property [ idx:field field:location ; sh:path geo:asWKT ] .
```

All WKT and GeoJSON geometry types are indexed: points, lines, polygons with holes, their
multi forms and geometry collections. CRS84 and EPSG:4326 are supported. GDA94 and
GDA2020 are treated as WGS84. A sort on a `LatLonField` raises, and so does `=`. Use the
spatial CQL2 operators. See [09-spatial.md](09-spatial.md).

*[TestSpatialFiltering](../jena-text/src/test/java/org/apache/jena/query/text/TestSpatialFiltering.java)*

## Multi-valued fields

Without `idx:multiValued true`, the index keeps only the first value and logs a warning
that names the entity and the field. Set it on every field whose predicate can repeat.
On a `KeywordField` with `idx:sortable true`, the flag also selects `SortedSet` doc
values, so a sort on a repeated field is well defined.

*[TestKeywordNormalizerMultiValued](../jena-text/src/test/java/org/apache/jena/query/text/TestKeywordNormalizerMultiValued.java), [TestShaclLucQueryRawValueOnMultiValuedField](../jena-text/src/test/java/org/apache/jena/query/text/TestShaclLucQueryRawValueOnMultiValuedField.java)*

## Configurations to avoid

| Configuration | Result |
|---|---|
| `idx:indexed false` on a filtered field | The filter raises `TextIndexException` |
| One `TextField` for exact match and search | `=` filters match nothing, because the analyzer splits the value |
| `idx:stored true` on a field nothing returns | The index is larger, and no query reads the stored values |
| n-grams on names | Many terms and poor ranking |
| Flat fields for correlated child records | Values from different child records combine in one match |
| No `idx:multiValued` on a repeating predicate | Values after the first are dropped, with a log warning |
| `idx:facetable` on a field with many distinct values | A facet of single-count values that uses memory |
