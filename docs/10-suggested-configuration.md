# Suggested Configuration

Defaults per kind of data. Terms are defined in [03-configuration.md](03-configuration.md);
this page is which to pick. Each recipe names the test that pins it.

## Principles

- The graph holds the values; the index narrows entities. Store a field only when
  `luc:match` / `luc:nestedMatch` must project it.
- Graph-derived documents are rebuilt on any relevant triple change. Shapes with an
  `idx:externalSource` are rebuild-only: the producer refuses live changes and logs that the
  document is stale until `ShaclBulkIndexer` runs.
- The flags are independent: `idx:indexed` writes searchable terms, `idx:stored` a stored
  copy, `idx:facetable` facet docvalues, `idx:sortable` sort docvalues.
- Exact match and free-text search want two fields over one path, not one field with a
  compromise analyzer.
- Fields carry behaviour, occurrences carry paths. One field, many shapes.
- Flags apply at index time only. Changing a type, analyzer or flag leaves existing documents
  as they were — reindex.

## The matrix

What to write, not what the defaults are. `idx:indexed` and `idx:stored` both default to
`true`, so `✗` means set it false explicitly.

| Data kind | Example | `idx:fieldType` | stored | facetable | sortable |
|---|---|---|---|---|---|
| Title / label, searched | `rdfs:label` of a report | `TextField` | ✗ | — | — |
| Name, exact match + counts | author, operator, publisher | `KeywordField` | ✗ | ✓ | ✓ + `idx:normalizer` |
| Description / abstract | `dcterms:description` | `TextField` | ✗ | — | — |
| Identifier / code, exact | `RPT-MIA-2023-001` | `KeywordField` | ✗ | ✓ if counted | — |
| Identifier, prefix typeahead | same value, n-grams | `TextField` | ✗ | — | — |
| Vocabulary term | `Gold`, `Approved`, `WA` | `KeywordField` | ✗ | ✓ | — |
| Level of a facet hierarchy | `state` then `commodity` | `KeywordField` | ✗ | ✓ | — |
| Entity class | `rdf:type` → `Borehole` | `KeywordField` | ✗ | ✓ | — |
| Year or count | `2023`, `42` | `IntField` | ✗ | ✓ | ✓ |
| Measurement / grade / score | `12.4` | `DoubleField` | ✗ | ✓ | ✓ |
| Full date or timestamp | `2023-04-01`, `2023-04-01T09:00:00Z` | `TemporalField` | ✗ | ✓ | ✓ |
| Geometry | `POINT(151.2 -33.9)` | `LatLonField` | ✗ | — | rejected |

Filtering, faceting and sorting read points and docvalues, never the stored copy — so `✗`
costs nothing but projection. Set `idx:multiValued true` wherever the predicate can repeat.

## Query syntax on a TEXT field

`queryString` goes to Lucene's classic
[`QueryParser`](https://lucene.apache.org/core/10_3_1/queryparser/org/apache/lucene/queryparser/classic/package-summary.html#package.description)
(`MultiFieldQueryParser` for several fields), scored by BM25. No configuration needed:

| Form | Example |
|---|---|
| Phrase | `"machine learning"` |
| Boolean | `machine AND learning`, `physics OR translation` |
| Required / prohibited | `+learning -physics` |
| Wildcard | `quan*`, `qu?ntum`, `*tum` (leading wildcards are enabled) |
| Fuzzy | `learnimg~1` |
| Proximity | `"machine networks"~2` |
| Boost | `quantum^4 learning` |
| Grouping | `(machine OR deep) AND learning` |
| Term range | `[a TO m]` |
| Match all | `*` |

So `quan*` needs no n-gram field; add one when prefix matching must be ranked and fast.

The string is analyzed with the field's query analyzer — on a `KEYWORD` field the whole value
is one term. Scope with `fieldSpec`, not an embedded `fieldName:` prefix, which uses internal
field names. Structured predicates belong in `cqlFilter`.

*`TestLuceneQuerySyntax`*

## Titles and names

A label you only search is one field:

```turtle
field:title
    idx:fieldName "title" ;
    idx:fieldType idx:TextField ;
    idx:stored false ;
    idx:defaultSearch true .
```

A name that is also a filter value — author, operator, publisher — is two fields over one
path, because it is searched as prose *and* picked from a facet list:

```turtle
field:authorNameText
    idx:fieldName "authorNameText" ;
    idx:fieldType idx:TextField ;
    idx:stored false ;
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

`idx:normalizer` case-folds the indexed term and sort key; the facet label stays as authored.

**Don't** n-gram a name: term explosion, broken ranking, and `"Jon"` matches `"Jones"`,
`"Jonathan"` and `"Jonsson"` indistinguishably.

*`TestKeywordNormalizer`, `TestKeywordNormalizerTwinField`, `TestKeywordRawSortAndExactMatch`*

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
    idx:analyzer [ a text:EdgeNGramAnalyzer ] .   # whole-value, not tokenized
```

Whole-value n-grams: `"RPT-MIA"` reaches `RPT-MIA-2023-001`, `"2023"` does not. Add
`text:tokenized true` for interior segments, accepting that `"2023"` then matches every 2023
report.

*`TestTypeaheadFieldConfigurations`*

## Descriptions

```turtle
field:description
    idx:fieldName "description" ;
    idx:fieldType idx:TextField ;
    idx:stored false ;
    idx:defaultSearch true .
```

The stored copy would be the largest thing in the index; the graph returns it by IRI.
`text:storeValues` on the index is `false` by default.

## Vocabularies and taxonomies

A vocabulary term is one value from a closed list. A hierarchy level is one field per rung;
`idx:facetHierarchy` declares the nesting.

```turtle
field:state
    idx:fieldName "state" ;
    idx:fieldType idx:KeywordField ;
    idx:stored false ;
    idx:facetable true .

field:commodity
    idx:fieldName "commodity" ;
    idx:fieldType idx:KeywordField ;
    idx:stored false ;
    idx:facetable true ;
    idx:multiValued true .

<#MiningReportShape>
    sh:property [ idx:field field:state     ; sh:path ex:state ] ;
    sh:property [ idx:field field:commodity ; sh:path ex:commodity ] ;
    idx:facetHierarchy ( field:state field:commodity ) .
```

Each level keeps its own flat dimension, so faceting on `field:commodity` alone still works.

**Don't** use `TEXT`. `=` compiles to a term query either way, so against a tokenised field it
looks for the term `"Iron Ore"`, which analysis never produced — matching nothing, silently.

*`TestHierarchicalFacets`, `TestHierarchicalFacetsSparql`, `TestNativeFacetCounts`*

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

Bind on every shape: one search crosses all of them, and this answers "Reports 23, Boreholes
51" in the same request. IRI-valued paths index as their IRI string. Distinct from
`idx:discriminatorField`, which is internal to delete scoping.

## Numbers and measurements

```turtle
field:grade
    idx:fieldName "grade" ;
    idx:fieldType idx:DoubleField ;
    idx:stored false ;
    idx:facetable true ;
    idx:sortable true .
```

Range facet boundaries are per query, so re-bucketing needs no reindex.

Numeric docvalues come from `facetable`/`sortable` independently of `idx:indexed`, so
`idx:indexed false` yields a field that counts and sorts but cannot be filtered — and the
filter is dropped silently, not rejected. Leave `idx:indexed` alone.

*`TestRangeFacetCounts`*

## Dates

`TemporalField` is a full date or timestamp — `xsd:date` or `xsd:dateTime`. A bare year is an
`IntField`.

```turtle
field:publishedOn
    idx:fieldName "publishedOn" ;
    idx:fieldType idx:TemporalField ;
    idx:stored false ;
    idx:facetable true ;
    idx:sortable true ;
    idx:storeLiteralMetadata true .   # required — config fails without it
```

Values are indexed as epoch millis in a companion field, so filtering and sorting are numeric
and date-only values are start-of-day UTC. Range facet boundaries are ISO strings.

`idx:storeLiteralMetadata true` is mandatory on `TEMPORAL`
(`ShaclIndexMapping.validateLiteralMetadataRequirements`), including on an unstored field
where it qualifies nothing.

An unparseable date is logged and drops out of range queries while the entity stays indexed —
so bad dates narrow results silently. Validate on the way in.

**Don't** use `KEYWORD` for sortable dates: lexical order is correct only for zero-padded ISO,
and range filters are gone.

*`TestDateLiteralRoundTrip`, `TestRangeFacetCounts`*

## Observations (SOSA-style)

```turtle
field:observedProperty
    idx:fieldName "observedProperty" ;
    idx:fieldType idx:KeywordField ;
    idx:stored true ;           # projected by luc:nestedMatch
    idx:facetable true .

field:resultValue
    idx:fieldName "resultValue" ;
    idx:fieldType idx:DoubleField ;
    idx:stored true ;
    idx:facetable true ;
    idx:sortable true .

field:resultUnits
    idx:fieldName "resultUnits" ;
    idx:fieldType idx:KeywordField ;
    idx:stored true ;
    idx:facetable true .

<#SampleShape>
    sh:targetClass sosa:Sample ;
    sh:property [ idx:field field:title ; sh:path rdfs:label ] ;
    idx:nested [
        idx:joinPath [ sh:inversePath sosa:hasFeatureOfInterest ] ;
        idx:property [ idx:field field:observedProperty ; sh:path ( sosa:observedProperty rdfs:label ) ] ;
        idx:property [ idx:field field:resultValue      ; sh:path sosa:hasSimpleResult ] ;
        idx:facetHierarchy ( field:observedProperty field:resultUnits ) ;
    ] .
```

Each observation is its own child document, so AND-ed clauses in one scope must hold on a
single observation. Flattened, "copper above 1%" would match a sample with copper at 0.2% and
lead at 5%. Correlation covers `=`, ranges, `in`, `between`, `like` and `text_query`.

Millions of observations that are not in the graph: use `idx:externalSource` instead — one CSV
row per child, same semantics. See
[External Content](03-configuration.md#external-content-csvtsv).

**Don't** nest a single-field child; that is a multi-valued field with extra machinery.

**Untested:** a `TEMPORAL` field inside a nested block.

*`TestNestedJoinPathSupport`, `TestCorrelatedNestedAttribution`, `TestNestedHierarchicalFacets`,
`TestNestedMatchProjection`, `TestNestedSortSelector`, `TestExternalContentIndexing`,
`TestGswaMeasurementCsv`*

## Geometry

```turtle
field:location
    idx:fieldName "location" ;
    idx:fieldType idx:LatLonField ;
    idx:stored false .

<#SiteShape>
    sh:property [ idx:field field:location ; sh:path geo:asWKT ] .
```

`POINT`, `POLYGON` and `MULTIPOLYGON` are indexed; `LINESTRING`, `MULTIPOINT` and the rest are
logged and skipped, so mixed geometry columns index only in part. CRS84 and EPSG:4326 both
work; GDA94/GDA2020 are treated as WGS84. Sorting is rejected and equality is meaningless —
use the spatial CQL operators ([09-spatial.md](09-spatial.md)).

*`TestSpatialFiltering`*

## Multi-valued fields

Without `idx:multiValued true`, values after the first are dropped with a log warning — which
looks fine until one entity has two commodities. On `KEYWORD` it also selects `SortedSet`
docvalues, making sort on a repeated field well-defined.

*`TestKeywordNormalizerMultiValued`, `TestShaclLucQueryRawValueOnMultiValuedField`*

## Anti-patterns

| Pattern | What goes wrong |
|---|---|
| `idx:indexed false` on a filtered field | Clause dropped and logged; results come back unfiltered, no error |
| One `TEXT` field for exact match *and* search | Tokenisation breaks `=` |
| Storing values nothing projects | Index size for no benefit |
| Storing external-source values corrected upstream | Rebuild-only shape: stale until `ShaclBulkIndexer` runs |
| n-grams on names | Term explosion, broken ranking |
| Flattening correlated children | Cross-matching between unrelated child records |
| Forgetting `idx:multiValued` | Values after the first vanish with a log line |
| `facetable` on a high-cardinality field | A dimension of single-count buckets, holding memory |
