# Use Cases

Each section shows the configuration or query for one search task. The examples use a
SHACL index selected as `"default"` and fields in the `urn:jena:lucene:field#` namespace.
Each query needs this prefix:

```sparql
PREFIX luc: <urn:jena:lucene:index#>
```

The argument lists are described in [02-sparql-api.md](02-sparql-api.md).

## Search with filters

Search the default fields for "climate change" and keep only one publisher:

```sparql
SELECT ?entity ?score WHERE {
  (?hit ?entity ?score) luc:query (
    "default" "default" "climate change"
    '{"op":"=","args":[{"property":"urn:jena:lucene:field#publisher"},"Halden Institute"]}'
    "" 20 0) .
}
```

Combine conditions on several fields with `and`:

```sparql
(?hit ?entity ?score) luc:query (
  "default" "default" "climate"
  '{"op":"and","args":[{"op":"=","args":[{"property":"urn:jena:lucene:field#publisher"},"Halden Institute"]},{"op":"=","args":[{"property":"urn:jena:lucene:field#category"},"Environment"]}]}'
  "" 20 0) .
```

## Facet counts

Count the top 10 categories and publishers of the hits:

```sparql
SELECT ?field ?value ?count WHERE {
  (?field ?value ?low ?high ?count) luc:facet (
    "default" "default" "climate change"
    '["urn:jena:lucene:field#category", "urn:jena:lucene:field#publisher"]'
    "" 10 0) .
}
```

The last argument, `minCount`, removes values with fewer hits. With `5`, a value needs at
least 5 hits.

## Facets for a filtered search

When a user selects `category = Environment`, put the selection in the `cqlFilter` of
`luc:facet`:

```sparql
(?field ?value ?low ?high ?count) luc:facet (
  "default" "default" "climate change"
  '["urn:jena:lucene:field#category", "urn:jena:lucene:field#publisher"]'
  '{"op":"=","args":[{"property":"urn:jena:lucene:field#category"},"Environment"]}'
  10 0) .
```

The default count mode is `"sideways"`. The category facet is counted without the
category selection, so it still lists the other categories and their counts. The
publisher facet is counted under the full filter. To count every facet under the full
filter, add `"drilldown"` as the eighth argument. See
[Count modes](02-sparql-api.md#count-modes).

## Hits and facets for one page

Hits and facet counts have different rows: hits are entities with scores, and facets are
field, value and count. A query that joins the two patterns returns every hit combined
with every facet row.

Send two queries:

```sparql
# Hits
SELECT ?entity ?score WHERE {
  (?hit ?entity ?score) luc:query ("default" "default" "climate change" "" "" 20 0) .
}
```

```sparql
# Facets
SELECT ?field ?value ?count WHERE {
  (?field ?value ?low ?high ?count) luc:facet (
    "default" "default" "climate change"
    '["urn:jena:lucene:field#category", "urn:jena:lucene:field#publisher"]'
    "" 10 0) .
}
```

To send one request, put the two patterns in a `UNION`. The result has one row for each
hit and one row for each facet value:

```sparql
SELECT ?entity ?score ?field ?value ?count WHERE {
  { (?hit ?entity ?score) luc:query ("default" "default" "climate change" "" "" 20 0) . }
  UNION
  { (?field ?value ?low ?high ?count) luc:facet (
      "default" "default" "climate change"
      '["urn:jena:lucene:field#category", "urn:jena:lucene:field#publisher"]'
      "" 10 0) . }
}
```

In one query, `luc:query` and `luc:facet` with the same index, fields, query string and
filter share one Lucene search (`SearchExecution`).

## Counts by range

Count hits by year in ranges. `null` makes an open-ended range:

```sparql
(?field ?value ?low ?high ?count) luc:facet (
  "default" "default" "*"
  '[{"field":"urn:jena:lucene:field#year","ranges":[2020,2022,2024,2026]}]'
  "" 10 0) .
```

Each row binds `?low` and `?high` and leaves `?value` unbound. The field must be numeric or
temporal and have `idx:facetable true`. The boundaries are set per query, so other ranges
need no rebuild of the index.

## Taxonomy facets

Declare the levels of a hierarchy with `idx:facetHierarchy` on the shape. A facet can then
go from a parent value to its children. The configuration is in
[Vocabularies and taxonomies](10-suggested-configuration.md#vocabularies-and-taxonomies).

## Values from related resources

A shape can index values that are on other resources. A sequence path follows links
forward, and an inverse path follows them backward:

```turtle
PREFIX field: <urn:jena:lucene:field#>

field:authorName
    idx:fieldName "authorName" ;
    idx:fieldType idx:KeywordField .

field:referencedBy
    idx:fieldName "referencedBy" ;
    idx:fieldType idx:KeywordField .

<#BookShape>
    sh:targetClass ex:Book ;
    ## Sequence path: the name of the book's author
    sh:property [ idx:field field:authorName ; sh:path ( ex:writtenBy ex:name ) ] ;
    ## Inverse path: the resources that reference the book
    sh:property [ idx:field field:referencedBy ; sh:path [ sh:inversePath ex:references ] ] .
```

The index follows the path when it builds the document. Other uses are the label of a
dataset's theme, the name of a product's manufacturer, or an organisation found by the
names of its members.

### Paths and materialised triples

A path in the index configuration can give search the same values that forward chaining
would add to the graph:

| | Forward chaining | Path in the index configuration |
|---|---|---|
| Source graph | Gets extra triples | Stays the same |
| After a data change | The rules must run again | The change listener rebuilds the affected documents |
| Configuration | Code or rule files | SHACL paths in Turtle |
| Several hops | Shortcut triples | Sequence paths |
| Reverse links | Inverse triples | Inverse paths |
| Facet counts | None | `luc:facet` |

The indexed values exist only in the Lucene index. SPARQL queries that do not use the
index, OWL or RDFS entailment, and other systems that read the graph do not see them.
Those uses still need materialised triples.

## Identifier prefix search

Users often type the start of an identifier, such as `BH12` for `BH123456`. Index the
identifier with edge n-grams and query it with a keyword analyzer, so the input is one
prefix term:

```turtle
PREFIX field: <urn:jena:lucene:field#>
PREFIX text:  <http://jena.apache.org/text#>
PREFIX idx:   <urn:jena:lucene:index#>

field:identifier
    idx:fieldName "identifier" ;
    idx:fieldType idx:TextField ;
    idx:analyzer [ a text:EdgeNGramAnalyzer ] ;
    idx:queryAnalyzer [ a text:LowerCaseKeywordAnalyzer ] .

## In the shape:
##   sh:property [ idx:field field:identifier ; sh:path ex:identifier ]
```

```sparql
SELECT ?entity ?score WHERE {
  (?hit ?entity ?score) luc:query (
    "default" '["urn:jena:lucene:field#identifier"]' "BH12" "" "" 10 0) .
}
```

The query matches identifiers that start with `BH12`. For other n-gram options, see
[Identifiers and codes](10-suggested-configuration.md#identifiers-and-codes).

## Search on a map

Filter hits by area with a spatial CQL2 operator:

```sparql
(?hit ?entity ?score) luc:query (
  "default" "default" "gold mine"
  '{"op":"s_intersects","args":[{"property":"urn:jena:lucene:field#location"},{"bbox":[115,-35,120,-30]}]}'
  "" 20 0) .
```

Configuration, coordinate systems and the other operators are in
[09-spatial.md](09-spatial.md).

## Index updates

`text:TextDataset` passes each added or deleted triple to `ShaclTextDocProducer`. When the
triple has a predicate that a shape indexes, or is an `rdf:type` triple, the producer
rebuilds the documents of the affected entities from the base dataset. A SPARQL update
through the text dataset is searchable when the update commits.

The listener sees only changes made through the text dataset. Data loaded before the
dataset is wrapped, for example with `tdb2.tdbloader`, needs `shacltextindexer` or
`text:buildOnStartup`. See [03-configuration.md](03-configuration.md#textbuildonstartup).

## Not built

Result grouping (`luc:group`) and type-ahead suggestions (`luc:suggest`) are not built.
See [07-future-work.md](07-future-work.md).
