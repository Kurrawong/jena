# User Guide

This guide builds a SHACL-mode text index over a small set of books, runs it in Fuseki,
and queries it. It then shows the common query forms. The full signatures are in
[02-sparql-api.md](02-sparql-api.md), and every configuration term is in
[03-configuration.md](03-configuration.md).

In SHACL mode, each resource that matches a shape's `sh:targetClass` becomes one Lucene
document. Each `sh:property` in the shape adds the values at its `sh:path` to a typed
field of that document.

## Deploying with Fuseki

Prerequisites: Java 21 or later and Maven 3.9 or later.

### 1. Build the server

Run this from the repository root:

```bash
mvn clean install -pl jena-fuseki2/jena-fuseki-server -am -DskipTests
```

The server jar is in `jena-fuseki2/jena-fuseki-server/target/`.

### 2. Write the data

Save this as `books.ttl`:

```turtle
PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

ex:book1 a ex:Book ;
    rdfs:label "Machine learning for geologists" ;
    ex:category "Technology" ;
    ex:year 2021 .

ex:book2 a ex:Book ;
    rdfs:label "A history of copper mining" ;
    ex:category "History" ;
    ex:year 2015 .
```

### 3. Write the configuration

Save this as `config.ttl` in the same directory as `books.ttl`:

```turtle
PREFIX :       <#>
PREFIX ex:     <http://example.org/>
PREFIX field:  <urn:jena:lucene:field#>
PREFIX fuseki: <http://jena.apache.org/fuseki#>
PREFIX idx:    <urn:jena:lucene:index#>
PREFIX ja:     <http://jena.hpl.hp.com/2005/11/Assembler#>
PREFIX rdfs:   <http://www.w3.org/2000/01/rdf-schema#>
PREFIX sh:     <http://www.w3.org/ns/shacl#>
PREFIX text:   <http://jena.apache.org/text#>

:service a fuseki:Service ;
    fuseki:name "books" ;
    fuseki:endpoint [ fuseki:operation fuseki:query  ; fuseki:name "query" ] ;
    fuseki:endpoint [ fuseki:operation fuseki:update ; fuseki:name "update" ] ;
    fuseki:dataset :ds .

:ds a text:TextDataset ;
    text:dataset :base ;
    text:indexes :index ;
    text:buildOnStartup true .

:base a ja:MemoryDataset ;
    ja:data <books.ttl> .

:index a text:TextIndexShacl ;
    text:indexId "default" ;
    text:directory "mem" ;
    text:shapes ( :BookShape ) .

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

:BookShape
    sh:targetClass ex:Book ;
    sh:property [ idx:field field:title    ; sh:path rdfs:label ] ;
    sh:property [ idx:field field:category ; sh:path ex:category ] ;
    sh:property [ idx:field field:year     ; sh:path ex:year ] .
```

| Part | Purpose |
|---|---|
| `text:TextDataset` | Wraps the base dataset and updates the index when the data changes |
| `text:buildOnStartup true` | Indexes `books.ttl` when the server starts |
| `text:indexId "default"` | The name that queries use to select this index |
| `text:directory "mem"` | Keeps the index in memory |
| `field:title`, `field:category`, `field:year` | Field definitions: a name, a type and flags. They have no path |
| `:BookShape` | Selects `ex:Book` resources and binds each field to a path |

One field can be used in several shapes. For the type and flags to use for each kind of
data, see [10-suggested-configuration.md](10-suggested-configuration.md).

### 4. Start the server

```bash
java -jar jena-fuseki2/jena-fuseki-server/target/jena-fuseki-server-*.jar --config config.ttl
```

Give the path to `config.ttl` if you start the server from another directory.

### 5. Run a query

```bash
curl -s http://localhost:3030/books/query \
  -H 'Accept: text/csv' \
  --data-urlencode 'query=
PREFIX luc: <urn:jena:lucene:index#>
SELECT ?entity ?score WHERE {
  (?hit ?entity ?score) luc:query ("default" "default" "learning" "" "" 10 0) .
}'
```

The result has one row: `http://example.org/book1` and its score.

### 6. Change the data

```bash
curl -s http://localhost:3030/books/update \
  --data-urlencode 'update=
PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
INSERT DATA { ex:book3 a ex:Book ; rdfs:label "Deep learning" ; ex:category "Technology" . }'
```

Run the query from step 5 again. It now returns `book1` and `book3`. The text dataset
built the document for `ex:book3` when the triples were added.

### Persistent data

For data on disk, use a TDB2 base dataset and a directory path in `text:directory`.
Load the data with `tdb2.tdbloader`, build the index with `shacltextindexer`, and then
start the server. See [03-configuration.md](03-configuration.md#textbuildonstartup).

## Queries

The examples use the index and fields from the configuration above. Each query needs this
prefix:

```sparql
PREFIX luc: <urn:jena:lucene:index#>
```

### Search

```sparql
(?hit ?entity ?score ?totalHits)
  luc:query (indexSelector fieldSpec queryString cqlFilter sortSpec limit offset)
```

| Argument | Value |
|---|---|
| `indexSelector` | The `text:indexId` of the index, or the IRI of the index resource |
| `fieldSpec` | `"default"` for the fields with `idx:defaultSearch true`, or a JSON array of field IRIs |
| `queryString` | Lucene query syntax. `"*"` matches all documents |
| `cqlFilter` | A CQL2-JSON object, or `""` for no filter |
| `sortSpec` | A JSON sort object or array, or `""` for relevance order |
| `limit` | Page size. A negative value means no limit. `0` gives 10 hits |
| `offset` | Number of hits to skip. It must be 0 or more |

`?totalHits` is the number of matches. The `offset` does not change it.

Search only the title field:

```sparql
(?hit ?entity ?score)
  luc:query ("default" '["urn:jena:lucene:field#title"]' "copper" "" "" 10 0) .
```

Filter on a field:

```sparql
(?hit ?entity ?score ?totalHits)
  luc:query (
    "default"
    "default"
    "learning"
    '{"op":"=","args":[{"property":"urn:jena:lucene:field#category"},"Technology"]}'
    ""
    20
    0
  ) .
```

Sort by a field. A `KeywordField` needs `idx:sortable true`. A numeric field needs
`idx:sortable true` or `idx:facetable true`.

```sparql
(?hit ?entity ?score)
  luc:query (
    "default"
    "default"
    "learning"
    ""
    '{"field":"urn:jena:lucene:field#year","order":"desc"}'
    10
    0
  ) .
```

Get the second page of 10 hits:

```sparql
(?hit ?entity ?score ?totalHits)
  luc:query ("default" "default" "learning" "" "" 10 10) .
```

### Which field matched

`luc:match` gives one row for each field that matched a hit. It joins to `luc:query`
through `?hit`:

```sparql
SELECT ?entity ?field ?value WHERE {
  (?hit ?entity ?score)
    luc:query ("default" '["urn:jena:lucene:field#title"]' "copper" "" "" 10 0) .
  (?hit ?field ?value) luc:match () .
}
```

The full subject list is `(?hit ?field ?value ?snippet)`. `?value` is read from the stored
value or the doc values of the field. A `TextField` has no doc values, so its `?value` is
bound only with `idx:stored true`, which is the default. `luc:query` has no `?match` slot.

### Facet counts

```sparql
(?field ?value ?low ?high ?count)
  luc:facet (indexSelector fieldSpec queryString facetFields cqlFilter maxValues minCount)
```

The subject list always has these five variables. A count for one value binds `?value`
and leaves `?low` and `?high` unbound.

Count the categories of the books that match "learning", up to 10 values:

```sparql
(?field ?value ?low ?high ?count)
  luc:facet (
    "default"
    "default"
    "learning"
    '["urn:jena:lucene:field#category"]'
    ""
    10
    0
  ) .
```

Count a numeric field in ranges. A range row binds `?low` and `?high` and leaves `?value`
unbound. A `null` boundary makes an open-ended range. A numeric field can be counted only
in ranges.

```sparql
(?field ?value ?low ?high ?count)
  luc:facet (
    "default"
    "default"
    "*"
    '[{"field":"urn:jena:lucene:field#year","ranges":[null,2000,2010,2020,null]}]'
    ""
    20
    0
  ) .
```

### Argument count

`luc:query` takes 7 object arguments, or 8 with the cursor `after`. `luc:facet` takes 7,
or 8 with `countMode`. Any other number fails when the query is built. Use `""` for an
unused `cqlFilter` or `sortSpec`.

### Field IRIs

Name fields by their IRI, for example `urn:jena:lucene:field#title`, in `fieldSpec`,
`facetFields`, CQL `property` and sort `field`. `luc:match` and `luc:facet` return field
IRIs in `?field`. The `idx:fieldName` is the name of the Lucene field inside the index.

### Several indexes

Give each index its own `text:indexId`. Select an index by that id in the first
argument:

```sparql
(?hit ?entity ?score)
  luc:query ("objects" "default" "gold" "" "" 20 0) .
```

An index without `text:indexId` takes the local name of its resource IRI. If the index
resource is an IRI, that IRI also selects it.

### Named graphs

`luc:query` has no graph argument and no `?graph` slot. The index builds each document
from the union of all graphs. A reserved field, `urn:jena:lucene:field#sourceGraph`, is
designed for graph filters but is not built, so a filter on it fails as an unknown field.

## Troubleshooting

| Symptom | Cause |
|---|---|
| The query fails when it is built | The number of object arguments is wrong |
| "No text index registered with selector" | The `indexSelector` matches no `text:indexId` or index IRI |
| A filter fails with "names unknown field" | The `property` is not a field of the selected index |
| "Unknown sort field" | The sort `field` is not a field IRI of the selected index |
| "is not idx:sortable" | The sort field has no sort doc values. Set the flag and rebuild the index |
| A query returns no hits after the server starts | The index is empty. Set `text:buildOnStartup true`, or build the index with `shacltextindexer` |
