# Suggested Configuration

Opinionated defaults for the common kinds of data. Every recipe here is exercised by a test —
the backing class is named so you can read the exact shape that is known to work.

For what each term means, see [03-configuration.md](03-configuration.md). This page is about
*which* to reach for.

## Principles

**The index is a filter, not a store of record.** The graph holds the truth. Lucene exists to
narrow millions of entities to a page of candidates; the values come back from the KG, or from
`luc:match` when you have deliberately stored them. Every stored value is a copy that goes
stale the moment the source changes, and a rebuild you owe.

**Each flag buys exactly one capability, and costs one structure.** `idx:indexed` for
filtering, `idx:stored` for projection, `idx:facetable` for counts, `idx:sortable` for
`ORDER BY`. Turn on what a query needs. The defaults — indexed and stored, not facetable, not
sortable — are right for most fields.

**One field, one job.** When a value needs both exact filtering and free-text search, define
two fields over the same path rather than one field with a compromise analyzer. They are
different Lucene fields; they cost one extra term dictionary and they never fight.

**Fields are path-free and reusable.** Define `field:title` once; bind it to `rdfs:label` on
one shape and `dcterms:title` on another. Occurrences carry paths, fields carry behaviour.

**Prefer a rebuild to a clever incremental trick.** A wrong document is worse than a slow one.

## The matrix

| Data kind | `idx:fieldType` | indexed | stored | facetable | sortable | Analyzer |
|---|---|---|---|---|---|---|
| Title / label, for searching | `TextField` | ✓ default | ✓ if projected | — | — | index default (BM25) |
| Name, for exact match + counts | `KeywordField` | ✓ default | ✓ default | ✓ | ✓ + `idx:normalizer` | — |
| Description / abstract / body | `TextField` | ✓ default | ✗ | — | — | index default |
| Identifier / code, exact | `KeywordField` | ✓ default | ✓ default | ✓ if you count them | — | — |
| Identifier, prefix typeahead | `TextField` | ✓ default | ✗ | — | — | `EdgeNGramAnalyzer` whole-value |
| Controlled vocabulary term | `KeywordField` | ✓ default | ✗ | ✓ | — | — |
| Taxonomy level | `KeywordField` | ✓ default | ✗ | ✓ | — | — (+ `idx:facetHierarchy`) |
| Entity type pivot | `KeywordField` | ✓ default | ✗ | ✓ | — | — |
| Year / count | `IntField` | ✓ default | ✓ default | ✓ | ✓ | — |
| Measurement / grade / score | `DoubleField` | ✓ default | ✗ | ✓ | ✓ | — |
| Date / timestamp | `TemporalField` | ✓ default | ✓ default | ✓ | ✓ | — (+ `idx:storeLiteralMetadata true`) |
| Geometry | `LatLonField` | ✓ default | ✓ default | — | ✗ rejected | — |

`✓ default` means you get it without writing anything. Set `idx:multiValued true` wherever the
predicate can repeat — see [Multi-valued fields](#multi-valued-fields).

## Names and titles

Two fields over one path: BM25 for finding, `KEYWORD` for filtering and counting.

```turtle
field:authorNameText
    idx:fieldName "authorNameText" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true .

field:authorName
    idx:fieldName "authorName" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true ;
    idx:sortable true ;
    idx:normalizer [ a text:LowerCaseKeywordAnalyzer ] .

<#MiningReportShape>
    sh:property [ idx:field field:authorNameText ; sh:path ( ex:authoredBy ex:name ) ] ;
    sh:property [ idx:field field:authorName     ; sh:path ( ex:authoredBy ex:name ) ] .
```

**Why.** `"Sarah Jones"` should reach `"Dr Sarah Jones"` — that is BM25 over a tokenised
field, with no analyzer override. The `KEYWORD` twin keeps the whole string as one term, so
`= "Dr Sarah Jones"` is exact and the facet counts one bucket per person.

`idx:normalizer` makes the indexed term and the sort key case-folded while the stored value
and the facet label stay as authored — so `"de Silva"` sorts next to `"De Silva"` instead of
after `"Zhang"`, and the facet still reads correctly.

**Don't** put an n-gram analyzer on a name. It inflates the term dictionary, wrecks scoring,
and `"Jon"` starts matching `"Jones"`, `"Jonathan"` and `"Jonsson"` with no way to rank them.
Names want BM25.

*Backed by `TestKeywordNormalizer`, `TestKeywordNormalizerTwinField`,
`TestKeywordRawSortAndExactMatch`.*

## Identifiers and codes

```turtle
field:identifier
    idx:fieldName "identifier" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

field:identifierPrefix
    idx:fieldName "identifierPrefix" ;
    idx:fieldType idx:TextField ;
    idx:stored false ;
    idx:analyzer [ a text:EdgeNGramAnalyzer ] .   # whole-value, NOT tokenized
```

**Why.** An identifier is looked up two ways: pasted whole (`= "RPT-MIA-2023-001"`, the
`KEYWORD` field), or typed progressively (`RPT-MIA` → the edge-n-gram field). Whole-value
n-grams mean `"RPT-MIA"` reaches `RPT-MIA-2023-001` while `"2023"` does not — a prefix of the
identifier, not of some fragment inside it.

Add `text:tokenized true` only when users legitimately search from an interior segment, and
know that you are buying `"2023"` matching every 2023 report.

**Don't** store the prefix field. It is a search structure with no display value; the exact
twin already holds the string.

*Backed by `TestTypeaheadFieldConfigurations` (both whole-value and per-word variants).*

## Descriptions and free text

```turtle
field:description
    idx:fieldName "description" ;
    idx:fieldType idx:TextField ;
    idx:stored false ;
    idx:defaultSearch true .
```

**Why.** Long prose is the one case where `idx:stored false` clearly pays: the stored copy is
the biggest thing in the index and the graph can return it by IRI. Keep it indexed — that is
the whole point — and put it in `defaultSearch` so a bare query string reaches it.

Set `text:storeValues true` on the index only if `luc:match` needs to project field values at
all; it is `false` by default.

## Controlled vocabularies, categories, taxonomies

```turtle
field:commodity
    idx:fieldName "commodity" ;
    idx:fieldType idx:KeywordField ;
    idx:stored false ;
    idx:facetable true ;
    idx:multiValued true .

field:state
    idx:fieldName "state" ;
    idx:fieldType idx:KeywordField ;
    idx:stored false ;
    idx:facetable true .

<#MiningReportShape>
    sh:property [ idx:field field:state     ; sh:path ex:state ] ;
    sh:property [ idx:field field:commodity ; sh:path ex:commodity ] ;
    idx:facetHierarchy ( field:state field:commodity ) .
```

**Why.** Vocabulary terms are exactly the `KEYWORD` case: a closed set, filtered with `=` or
`in`, counted as facets. Not stored, because the label is already in the graph and the facet
carries its own value.

`idx:facetHierarchy` adds a drill-down dimension over the same fields — WA → Gold → … — and
each level keeps its own flat dimension, so faceting on `field:commodity` alone still works.
The two are addressed separately and neither shadows the other.

**Don't** index a vocabulary term as `TEXT`. `=` compiles to a raw term query either way, so
against a tokenised field it looks for the single term `"Iron Ore"` — which analysis never
produced, having emitted `iron` and `ore`. The filter matches nothing, with no error. Use
`text_query` when you want analyzer-aware matching, and `KEYWORD` when you want `=`.

*Backed by `TestHierarchicalFacets`, `TestHierarchicalFacetsSparql`, `TestNativeFacetCounts`.*

## Entity type pivots

```turtle
field:entityType
    idx:fieldName "entityType" ;
    idx:fieldType idx:KeywordField ;
    idx:stored false ;
    idx:facetable true .

<#MiningReportShape>
    sh:property [ idx:field field:entityType ; sh:path rdf:type ] .
```

An IRI-valued path indexes as its IRI string, so this gives "23 Reports, 51 Boreholes" across
a mixed index without a second query. Bind it on every shape.

## Numbers and measurements

```turtle
field:grade
    idx:fieldName "grade" ;
    idx:fieldType idx:DoubleField ;
    idx:facetable true ;   # range facet buckets
    idx:sortable true ;    # ORDER BY, nested sort selector
    idx:stored false .     # the assay database is the source of truth
```

**Why.** `DOUBLE` gives range filters (`>=`, `between`) as a points query, and range facets
declared per query rather than per config — you pass the boundaries in the `luc:facet` call,
so re-bucketing needs no reindex.

Note the asymmetry worth knowing: numeric facet/sort docvalues are written from `facetable` or
`sortable` alone, independently of `idx:indexed`. Leaving `idx:indexed false` on a numeric
field therefore produces one that counts and sorts but cannot be filtered — and the filter
does not error, it is silently dropped. Leave `idx:indexed` alone.

**Don't** store a volatile number you can re-fetch. Storing means the index is stale the
moment the value is corrected.

*Backed by `TestRangeFacetCounts` (INT, LONG, DOUBLE and TEMPORAL buckets).*

## Dates

```turtle
field:publishedOn
    idx:fieldName "publishedOn" ;
    idx:fieldType idx:TemporalField ;
    idx:facetable true ;
    idx:sortable true ;
    idx:storeLiteralMetadata true .   # required — config fails without it
```

**Why.** A `TEMPORAL` field parses `xsd:date` / `xsd:dateTime` to epoch millis and indexes
*that* — filtering and sorting are numeric, and date-only values are treated as start-of-day
UTC. Range facets take ISO boundaries (`"2019-01-01"`), not epoch numbers, so a config change
is never needed to re-bucket.

`idx:storeLiteralMetadata true` is **not optional**: a `TEMPORAL` field without it is rejected
at config time. It stores the datatype and language alongside the value so a projected hit
rebuilds as the literal it came from rather than a bare string.

A value that fails to parse is logged and simply does not participate in range queries — the
entity is still indexed on its other fields, so a bad date silently narrows results rather
than failing loudly. Validate dates on the way in.

**Don't** model a date as `KEYWORD` to get "sortable strings". It sorts lexically, which is
correct only for zero-padded ISO dates and wrong the moment a timezone offset appears, and you
lose range filters entirely.

*Backed by `TestDateLiteralRoundTrip` (filter, sort, invalid-value handling) and the temporal
case in `TestRangeFacetCounts`.*

## Observations and measurement records (SOSA-style)

One row per observation, correlated per child. This is the case `idx:nested` exists for.

```turtle
field:observedProperty
    idx:fieldName "observedProperty" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

field:resultValue
    idx:fieldName "resultValue" ;
    idx:fieldType idx:DoubleField ;
    idx:facetable true ;
    idx:sortable true .

field:resultUnits
    idx:fieldName "resultUnits" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

<#SampleShape>
    sh:targetClass sosa:Sample ;
    sh:property [ idx:field field:title ; sh:path rdfs:label ] ;

    idx:nested [
        idx:joinPath [ sh:inversePath sosa:hasFeatureOfInterest ] ;
        idx:property [ idx:field field:observedProperty ; sh:path ( sosa:observedProperty rdfs:label ) ] ;
        idx:property [ idx:field field:resultValue      ; sh:path sosa:hasSimpleResult ] ;
        idx:property [ idx:field field:resultUnits      ; sh:path ex:units ] ;
        idx:facetHierarchy ( field:observedProperty field:resultUnits ) ;
    ] .
```

**Why nested and not flat.** Flatten observations onto the sample and "copper above 1%"
matches a sample with copper at 0.2% and lead at 5% — the values decorrelate. Inside an
`idx:nested` block each observation is its own child document, and AND-ed clauses in the same
scope fold into one block join, so both conditions must hold *on one observation*.

This correlation covers `=`, ranges, `in`, `between`, `like` and `text_query` — not just
equality.

**When the observations are not in the graph**, and there are millions of them, bind an
`idx:externalSource` instead: one CSV row becomes one child document, joined on the entity
IRI, with the values never loaded as triples. Same query semantics, same correlation. See
[03-configuration.md → External Content](03-configuration.md#external-content-csvtsv).

**Don't** reach for nesting when the child has exactly one field. A single-field child is a
multi-valued field on the parent with extra machinery.

**Not covered by a test:** a `TEMPORAL` field *inside* a nested block. Result times on the
parent are well covered; if you need `sosa:resultTime` per observation, add a test with it.

*Backed by `TestNestedJoinPathSupport`, `TestCorrelatedNestedAttribution`,
`TestNestedHierarchicalFacets`, `TestNestedMatchProjection`, `TestNestedSortSelector`,
`TestExternalContentIndexing`, `TestGswaMeasurementCsv`.*

## Geometry

```turtle
field:location
    idx:fieldName "location" ;
    idx:fieldType idx:LatLonField .

<#SiteShape>
    sh:property [ idx:field field:location ; sh:path geo:asWKT ] .
```

`POINT`, `POLYGON` and `MULTIPOLYGON` WKT are indexed for spatial filters. Anything else —
`LINESTRING`, `MULTIPOINT` — is logged and **skipped**, so a geometry column of mixed types
quietly indexes only part of itself. CRS84 (bare WKT) and EPSG:4326 both work with axis order
handled for you; GDA94/GDA2020 are treated as WGS84-equivalent.

Sorting on a `LatLon` field is rejected outright, and equality is meaningless — use the
spatial CQL operators. See [09-spatial.md](09-spatial.md).

*Backed by `TestSpatialFiltering`.*

## Multi-valued fields

Set `idx:multiValued true` whenever the predicate can legitimately repeat.

Without it, a second value is **dropped with a warning** — the document keeps the first value
and the entity silently stops matching on the rest. This is the single most common
misconfiguration, because it works perfectly until one entity has two commodities.

For `KEYWORD` fields it also selects the docvalues shape (`SortedSet` rather than `Sorted`),
which is what makes sorting on a repeated field well-defined.

*Backed by `TestKeywordNormalizerMultiValued`,
`TestShaclLucQueryRawValueOnMultiValuedField`.*

## Anti-patterns

| Pattern | What goes wrong |
|---|---|
| `idx:indexed false` on anything a client filters | The clause is dropped, logged, and the query returns unfiltered results. No error |
| One `TEXT` field doing exact match *and* search | Tokenisation breaks `=`; the analyzer you pick is wrong for one of the two jobs |
| Storing volatile values | Every correction upstream needs a reindex before the index stops lying |
| n-grams on names | Term explosion, broken ranking, `"Jon"` matching three unrelated people |
| Flattening correlated children | "copper above 1%" matches the sample with copper at 0.2% and lead at 5% |
| Forgetting `idx:multiValued` | Values after the first vanish with only a log line |
| Facetable on a high-cardinality field | A facet dimension with a million single-count buckets answers nothing and costs memory |
