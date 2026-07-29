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

Query-time same-child correlation is not limited to `=`. AND-ed leaves that target the same nested scope fold into one block join, so `=`, ranges (`<`, `>`, `<=`, `>=`), `in`, `between`, `like` and `text_query` all correlate within a single child — see [02-sparql-api.md → Nested same-child filters](02-sparql-api.md#nested-same-child-filters).

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
            idx:minMatchRate  "0.5"^^xsd:double ;
            idx:column [ idx:columnName "property" ; idx:field field:measuredProperty ] ;
            idx:column [ idx:columnName "value" ;    idx:field field:measuredValue ] ;
        ] ;
        idx:facetHierarchy ( field:measuredProperty field:measuredBand ) ;
    ] .
```

Bound fields carry **no `sh:path`** — their values come from the column. There is no `idx:external` flag: a field is external because a column binds it, exactly as a field is nested because it appears in an `idx:nested` block.

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
| `idx:minMatchRate` | no | Build fails if a smaller fraction of entities matched. Default `0.0` (off) |
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
- **Rows augment entities; they never create them.** A row whose subject matches no entity is counted and dropped. The count is always logged; `idx:minMatchRate` turns the catastrophic case — usually a wrong `idx:subjectPrefix` — into a build failure.
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
