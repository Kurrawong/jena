# Testing

## Running Tests

```bash
# Full jena-text suite
mvn test -pl jena-text

# SHACL and faceting tests only
mvn test -pl jena-text -Dtest="TestShaclIndexMapping,TestShaclDocumentBuilding,TestShaclTextDocProducer,TestShaclAssembler,TestShaclEntityPerDocument,TestNativeFacetCounts,TestTextFacetPF,TestTextQueryPFFilters,TestSearchExecution,TestHierarchicalFacets,TestHierarchicalFacetsSparql,TestSortSpec"
```

Surefire runs only `**/TS_*.java`. `TS_Text.java` is the suite: it lists every test class
in a JUnit 5 `@SelectClasses` annotation. Older SHACL and faceting classes use JUnit 4 and
run through `junit-vintage-engine`. Write new tests in JUnit 5.

Surefire does not report a class that is missing from `@SelectClasses`; the class does
not run. After you add a test class, check that its name appears in the `-- in <class>`
lines of the Maven output.

`TestFacetedResults` and `TestUpdateDocumentFacets` are in the test sources and are not
in `TS_Text`, so they do not run.

---

## Test Discipline

- **Back each documented recommendation with a test.** A recommendation in the docs, for
  example the `idx:normalizer` twin-field pattern, needs a test of the exact recommended
  shape, with the real field cardinality (single-valued or multi-valued).
- **Red before green.** For a bug fix or new behaviour, add the test first and confirm
  that it fails for the expected reason. Then apply the fix. Where practical, commit the
  failing test and the fix separately.
- **Test the combinations of the config matrix that production configs use.** When a
  change interacts with existing settings (`sortable × multiValued × normalized × field
  type`), test the combination, in addition to each setting alone.

---

## Test Suite Overview

The tables list the main SHACL-mode test classes by area. `TS_Text.java` is the complete
list. Classes are in `jena-text/src/test/java/org/apache/jena/query/text/`, or in its
`assembler` or `external` subpackage where the table says so.

### Facets and the luc:query hit path

| Class | What it covers |
|-------|---------------|
| `TestNativeFacetCounts` | Java API: open and filtered facets, `maxValues`, `minCount`, all children when `maxValues` is 0, empty and unknown fields, facet specs by IRI and by bare name |
| `TestTextFacetPF` | `luc:facet`: flat and range facets, mixed requests, filters, `maxValues`, `minCount`, wildcard, unbound `?low`/`?high` on flat rows, rejection of the 3-slot subject, temporal range boundaries, cache separation between facet requests |
| `TestFacetSideways` | `luc:facet` count modes: each selected field's sideways counts equal a `"drilldown"` call with that field's clauses removed, across segments with deleted documents; `or` within one field; clauses that stay in the base filter (`or` across fields, `<>`, nested scopes, hierarchy levels, fields not requested); `luc:query` totals in the same query; `countMode` values and errors |
| `TestTextQueryPFFilters` | `luc:query` with CQL2-JSON filters, field-IRI scoping, empty-string placeholders, string limits, `?totalHits`, `luc:match` field binding, queries pinned to entity IRIs, and sort by field IRI (descending, and ascending with a filter) |
| `TestSearchExecution` | Shared execution: key generation, normalisation, reuse per index, and sort in the key |
| `TestLucQueryHitPath` | The `luc:query` hit path: `(?hit)` decodes no stored field and `(?hit ?entity)` only the entity field or its doc value, counted through `HitReadObserver`; entity order under relevance and sort; constant `?entity`; match details only with `luc:match`, only for matched fields and the returned page; two searches in one query each resolving their own `?hit`; constant field slots in `luc:match` and `luc:nestedMatch`; negative and zero `limit`; the searcher held while iterating, until the last of two iterators closes, and released on finish, abandon and cancel; late reads with and without a commit; a window re-run on the held searcher ignoring a later commit, and raising after release and a commit; closing a query after its index closed |
| `TestLucMatchLargeWindow` | `luc:match` and `luc:nestedMatch` over 300,000 hits within 90 seconds |
| `TestFieldValueStorage` | Which fields are read from doc values; an eligible stored field written as a doc value only; original terms from doc values and from stored fields (normalized, facet-only, language-tagged, numeric, temporal); indexes with stored copies of eligible fields; a segment written before a field was sortable; the value `luc:match` selects on a multi-valued doc-value field; `luc:nestedMatch` reading child fields from doc values; `queryByFields`, `queryWithFilters` and `queryWithCql` returning values held only in doc values |
| `TestLucFields` | `luc:fields`: the identifier query reading no stored field, variable and constant field slots, multi-valued fields, two patterns per hit, joins with `luc:match` in both orders, each error, a late read after a commit, one read per field, two indexes in one query |
| `TestEntityDocValue` | The entity IRI doc value: written as binary; the same IRIs under relevance and sort with no stored read; an index without it; an old segment without it beside a new one with it; parent documents only; entity updates and deletes |
| `TestTextIndexDump` | `textindexdump` printing fields held only in doc values |

### Entity-per-document model

| Class | What it covers |
|-------|---------------|
| `TestShaclIndexMapping` | Data model: predicate and class lookup, field resolution, facet field names, defaults, field IRIs (generated and explicit), conflicting field types, two field IRIs sharing a field name, hierarchy metadata |
| `TestShaclDocumentBuilding` | Document building: `TEXT`, `KEYWORD`, `INT`, `LONG`, `DOUBLE`; multi-valued fields; sort doc values for multi-valued `KEYWORD`; a single-valued sortable field indexing its first value only; discriminator; null fields; int from string |
| `TestShaclTextDocProducer` | Change listener: adding a type creates the document, adding a property rebuilds it, deleting the type removes it, irrelevant predicates and blank-node subjects are skipped, several entities |
| `assembler.TestShaclAssembler` | Config parsing: shapes, derived entity definition, inverse and sequence paths, query analyzers, normalizers, temporal fields, nested hierarchies and join paths, shared fields, occurrence constraints, named hierarchies, and the rejected forms |
| `TestShaclEntityPerDocument` | End to end: text search, `luc:query`, facet counts, filtered facets, adding data after load, one document per entity |
| `TestLuceneQuerySyntax` | The `queryString` argument through Lucene's classic parser: phrase, boolean, required and prohibited terms, wildcards, fuzzy, proximity, boost, grouping, term range, and the bare `*` match-all |
| `TestQueryParserSettings` | `text:splitOnWhitespace` and `text:autoGeneratePhraseQueries`: the pieces of a code must be adjacent in a text field through `luc:query`, `luc:facet` and a nested scope; separate words match any word; a code that contains a space matches whole only when escaped; both settings off; splitting only; the rejected combination; a non-boolean value |
| `TestDateLiteralRoundTrip` | `TEMPORAL` fields: `between` and `>=` over the epoch field, typed `xsd:date` and `xsd:dateTime` on read, language-tagged literals, and an unparseable date dropping out of range queries while the build continues |

### Hierarchical facets

| Class | What it covers |
|-------|---------------|
| `TestHierarchicalFacets` | Java API: taxonomy indexing, top-level facets, drill-down paths, blank level values, text search with hierarchies, flat facets beside hierarchies and on level fields, `minCount`, field IRIs for level fields, an error on a field that is not facetable |
| `TestHierarchicalFacetsSparql` | `luc:facet` with a hierarchy: top level by field IRI, drill-down by CQL filter, flat facets beside the hierarchy |
| `TestSelfBoundOccurrences` | `idx:self` in a nested block, where the child node is a hierarchy level: top-level counts, drill-down per child, children not counted above the parent, same-child correlation with a sibling field, reindex after a vocabulary edit and after a child is added |
| `assembler.TestSelfOccurrenceAssembler` | `idx:self` config: accepted in a nested block and at root; rejected with `sh:path`, with neither, as `idx:self false`, on a numeric field, and on an `idx:column` |

### Sort and paging

| Class | What it covers |
|-------|---------------|
| `TestSortSpec` | Sort JSON parsing, nested sort selectors and their errors, missing-value placement, Lucene sort construction, `MIN`/`MAX` selectors for multi-valued fields, rank-ordered scores under a sort, an error on sorting a text field |
| `TestCursorPaging` | The entity IRI sort key: ties in IRI order, before and after re-insertion, ascending and descending, code point order where Jena's `ORDER BY` differs, and an error on an index without the IRI doc value. Unknown, unsortable, `TEXT` and facetable-only numeric sort fields; a failed search raising. Cursor walks over a normalized keyword, a multi-valued numeric, an explicit IRI key and relevance order; ending a walk on `?totalHits` and on looking ahead one hit; continuing from the last hit shown; a cursor surviving re-insertion of every entity; `offset` with a cursor; the empty eighth argument; a cursor used with another query or another sort, or malformed, truncated or extended; the marker each client-facing error starts with; `?cursor` as the sixth subject element without an eighth argument, with an empty one, and as a constant; a seventh element; the token round trip and its checks |

### External content

| Class | What it covers |
|-------|---------------|
| `external.TestCsvRowSource` | CSV and TSV reading: binding by header and by position, `idx:subjectPrefix`, glob expansion in filename order, empty cell read as null, rows with a blank subject skipped, rows emitted in file order without an order check, repeated subjects emitted unchanged, and the config errors on open: missing file, glob matching nothing, missing subject or bound column |
| `assembler.TestExternalSourceAssembler` | Turtle config: narrow source, optional properties, headerless positional binding, bound fields as profile fields, hierarchies over external fields, deltas, and the validation rules: `idx:joinPath` or `idx:externalSource` but not both, required `idx:nestedName`, no `sh:path` on a bound column, exactly one of `idx:columnName` and `idx:columnIndex`, unknown format, unsupported field type, required location and subject column |
| `TestExternalContentIndexing` | End to end: rows become children of the matching entity; entities with no rows are still indexed; unmatched rows are counted and dropped; same-child `=` with a range; AND across two properties is entity-level; sort by an external value; `idx:subjectPrefix`; a wrong join key giving a zero match rate in the counters; buffering of an unsorted source; empty and unparseable cells; `idx:onError "fail"`; hierarchical facets over external children; a live graph change keeping external children; deltas. Wide children (depth from, depth to, analyte, value on one child): all columns correlate, a Cu child and a deep child on the same hole do not satisfy one AND, two analytes in one interval are not one child |

### sh:node steps and value constraints

Both classes build the index from Turtle with `ShaclTurtleFixture`, which also reads back
the written Lucene documents.

| Class | What it covers |
|-------|---------------|
| `TestShaclNodeOccurrenceFilters` | Steps: a filtered identifier value as a sortable root field; two kept values and the single-valued warning; filters with `sh:in`, an inner `sh:minCount`, a constraint on the shape, and a `sh:node` member without a field; a step inside a step; a step inside `idx:nested` feeding the child; an occurrence's own constraint leaving the choice of node to the step; the change listener following a filter predicate, a field value and an `rdf:type`; the fingerprint following a filter; the bulk indexer and the change listener writing the same documents |
| `TestShaclOccurrenceValueConstraints` | Each accepted per-value constraint filtering values; each set constraint, and `sh:sparql` on an occurrence and in a filter, rejected with the constraint named; repeating paths rejected; `sh:class` admitting a subclass instance; the fingerprint following a value constraint; value checks reading no data prefixes |

### Upstream tests

The other classes in `TS_Text` cover text search, multilingual support, graph indexing,
deletion, analyzers, property lists, spatial filtering, nested identifiers, and several
entity types sharing one index (`TestMultiEntityTypeScenarios`).

---

## Test Patterns

### Programmatic setup

Many tests build the mapping in Java. From `TestNativeFacetCounts`:

```java
// FieldDef(name, type, analyzer, stored, indexed, facetable, sortable, multiValued, defaultSearch)
FieldDef title = new FieldDef("title", FieldType.TEXT, null,
    true, true, false, false, false, true);

// occurrence(...) is a test helper:
// new FieldOccurrence(field, path, ShaclIndexAssembler.extractPathVariants(path), predicates, null, null, null, null)
List<FieldOccurrence> occurrences = List.of(
    occurrence(title, PathFactory.pathLink(TITLE_PRED), Set.of(TITLE_PRED)));

IndexProfile profile = new IndexProfile(shapeNode, Set.of(BOOK_CLASS), "uri", "docType",
    List.of(title), occurrences, List.of(), List.of());
ShaclIndexMapping mapping = new ShaclIndexMapping(List.of(profile));

TextIndexConfig config = new TextIndexConfig(ShaclIndexAssembler.deriveEntityDefinition(mapping));
config.setShaclMapping(mapping);
config.setFacetFields(mapping.getFacetFieldNames());
ShaclTextIndexLucene index = new ShaclTextIndexLucene(new ByteBuffersDirectory(), config);

ShaclTextDocProducer producer = new ShaclTextDocProducer(baseDs.asDatasetGraph(), index, mapping);
Dataset dataset = TextDatasetFactory.create(baseDs, index, true, producer);
```

### Assembler setup

`assembler.TestShaclAssembler` builds the configuration with the Jena Model API. A field
is its own resource; a property shape links it to a path with `idx:field`:

```java
Resource labelField = model.createResource(EX + "labelField")
    .addProperty(model.createProperty(IndexVocab.NS, "fieldName"), "label")
    .addProperty(model.createProperty(IndexVocab.NS, "fieldType"), IndexVocab.TextField)
    .addProperty(model.createProperty(IndexVocab.NS, "defaultSearch"), model.createTypedLiteral(true));

Resource bookShape = model.createResource(EX + "BookShape")
    .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
    .addProperty(model.createProperty(SH, "property"), model.createResource()
        .addProperty(model.createProperty(IndexVocab.NS, "field"), labelField)
        .addProperty(model.createProperty(SH, "path"), RDFS.label));

Resource indexSpec = model.createResource(EX + "index")
    .addProperty(RDF.type, TextVocab.textIndexShacl)
    .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
    .addProperty(TextVocab.pShapes, model.createList(new RDFNode[]{ bookShape }));

ShaclTextIndexLucene index = (ShaclTextIndexLucene) Assembler.general().open(indexSpec);
```

### Turtle setup

`ShaclTurtleFixture` builds an index from a Turtle shapes configuration.
`openIndex(shapesTurtle)` returns the index, `liveDataset(index)` wraps it with the change
listener, and `parentDoc`, `childDocs` and `renderAll` read the written documents.

---

## Coverage

### Covered

- All argument forms of `luc:query` and `luc:facet`.
- CQL2-JSON filter parsing and compilation (`cql.TestCqlParser`,
  `cql.TestCqlToLuceneCompiler`).
- All field types: `TEXT`, `KEYWORD`, `INT`, `LONG`, `DOUBLE`, `TEMPORAL`
  (`TestDateLiteralRoundTrip` at root, `TestNestedTemporalField` in `idx:nested`),
  `LATLON` (`TestSpatialFiltering`).
- Multi-valued fields.
- Entity lifecycle: create, update (add a field), delete (remove the type).
- Data in named graphs (`TestShaclStepChangeTracking`).
- Assembler config parsing, valid and invalid, from the Model API and from Turtle
  (`ShaclTurtleFixture`, and `TestReferenceIndex` with
  `src/test/resources/reference-index/config.ttl`).
- Shared execution between property functions.
- Facet counts with filters, `minCount` and `maxValues`.
- Sort by field IRI through `luc:query`.
- Hierarchical facets: taxonomy indexing, top-level counts, drill-down by CQL filter,
  flat facets beside hierarchies.
- Range facets on numeric fields: single-valued, multi-valued, open buckets, mixed flat
  and range requests, 5-slot bindings.
- Range facets on `TEMPORAL` fields: ISO date boundaries counted from the epoch doc
  values, lower bound inclusive, upper bound exclusive except on an open end.
- Multi-valued numeric sort: `MIN` for ascending, `MAX` for descending.
- External CSV and TSV content: the IRI join, the match counters, the order check, and
  the boundary between same-child and entity-level correlation.

### Not covered

- Several shapes with overlapping predicates.
- Update performance at scale: entities with many triples, and frequent updates.
- Concurrent write transactions.
- `sh:alternativePath` in assembler config.
- Very long field values and special characters in filters.

---

## Fuseki Integration Testing

The unit tests run the Java API and the SPARQL property functions in process. To test
through HTTP, build Fuseki and start it with a configuration file:

```bash
mvn clean install -pl jena-fuseki2/jena-fuseki-server -am -DskipTests

java -jar jena-fuseki2/jena-fuseki-server/target/jena-fuseki-server-*.jar \
    --config config.ttl
```

---

## luc:query Benchmark

`BenchLucQuery`, in `jena-text/src/test/java/org/apache/jena/query/text/bench/`, is a JMH
benchmark of `luc:query` over a synthetic index. It is in the test sources and
`TS_Text` does not list it, so Surefire does not run it.

### Running it

```bash
mvn -o -q -pl jena-text test-compile dependency:build-classpath \
    -Dmdep.outputFile=target/bench.classpath -Dmdep.includeScope=test -Drat.skip
cd jena-text
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
    -cp target/test-classes:target/classes:$(cat target/bench.classpath) \
    org.apache.jena.query.text.bench.BenchLucQuery -rf json -rff results.json
```

Arguments after the class name go to JMH. A regex runs a subset: `BenchLucQuery pageText`
runs the three page scenarios. `-l` builds the index and lists the scenarios without
running them.

The first run generates the data, loads it into TDB2 with the parallel loader, and builds
the Lucene index with `shacltextindexer`, as a production bulk load does. On a 12-core
machine this took about two and a half minutes. Later runs reuse the build in
`jena-text/target/bench-luc-query`, which `mvn clean` deletes.

| Property | Default | Meaning |
|---|---|---|
| `bench.dir` | `jena-text/target/bench-luc-query` | Where the dataset and index are built and reused |
| `bench.entities` | `500000` | Entity count; both large windows are three fifths of it |
| `bench.matchWindow` | `20000` | Window of `largeTextWithMatch` |

The benchmark uses JMH's sample-time mode in milliseconds: 3 warmup and 5 measurement
iterations of 10 seconds each, in one forked JVM with an 8 GB heap. JMH reports the mean
and the 50th, 90th, 95th and 99th percentiles. Compare runs at the median, on the same
machine, with the machine otherwise idle.

### Data

- 500,000 entities of one SHACL shape, in a TDB2 store on disk, as in production.
- Each entity has two `TEXT` fields of about 300 characters from a 5,000-word vocabulary
  with a Zipf distribution, a sortable `KEYWORD` identifier, four facetable `KEYWORD`
  fields, an `INT` year, and two `schema:identifier` children (`schema:propertyID`,
  `schema:value`) indexed through `idx:nested`.
- Every field except `entityType` is stored.
- Hit counts depend only on the entity number: the term `survey` matches 300,000
  entities, the term `basalt` 10,638, and the filter `status = active` 300,000.
- The index config is `jena-text/src/test/resources/bench/luc-query-bench.ttl`.

### Scenarios

Each scenario runs one SPARQL query through ARQ in a read transaction and reads every
binding of every row. Before timing, each scenario runs its query once and fails if the
row count is wrong.

| Scenario | Query | Rows |
|---|---|---|
| `pageText` | `basalt`, page of 20, binds `?entity` | 20 |
| `pageTextWithMatch` | As `pageText`, joined to `luc:match` | 20 |
| `pageTextWithFacet` | As `pageText`, in a `UNION` with `luc:facet` over four fields, 10 values each | 51 |
| `largeFilterOnly` | `*` with the `status` filter, limit 300,000, binds `?entity` | 300,000 |
| `largeTextNoMatch` | `survey`, limit 300,000, binds `?entity` | 300,000 |
| `largeTextWithMatch` | `survey` joined to `luc:match`, limit `bench.matchWindow` | 20,000 |
| `largeIdentifierFields` | `*` with the `status` filter, limit 300,000, binds `?hit` only, `recordId` through `luc:fields` | 300,000 |
| `formSideways<Form><Term>` | One `luc:facet` in its default sideways mode over category, region, status and subject (and year, for `Broad`), all values, with the form's full filter | Equal to `formPerField<Form><Term>` |
| `formPerField<Form><Term>` | The same counts as `"drilldown"` calls in a `UNION`: subject under the full filter, and each selected field with its own clauses removed | Equal to `formSideways<Form><Term>` |

| Placeholder | Values |
|---|---|
| `<Form>` | `Selective`: category in 3 of 30 values, region in 2 of 8, status `active`. `Broad`: category in 20 values, region in 6, status `active` or `draft`, year between 1960 and 2015, year as a range facet |
| `<Term>` | `Common` (`survey`) or `Rare` (`basalt`) |

Before timing, each `form` scenario checks that its sideways and per-field queries return
the same rows.

`largeIdentifierFields` reads `recordId`, a sortable `KEYWORD`, from its doc value, and
`largeFilterOnly` reads the entity IRI doc value. An index built before these doc values
existed still has stored copies and is read from them. Build a fresh index with
`-Dbench.dir` to measure the doc-value path.

`largeTextWithMatch` runs at 20,000 hits by default. Set `-Dbench.matchWindow=300000` to
run it at the size of the other large scenarios; `TestLucMatchLargeWindow` checks that
`luc:match` completes at that size.

---

## Adding New Tests

1. Create the test class in `jena-text/src/test/java/org/apache/jena/query/text/`. Put
   assembler tests in the `assembler` subpackage.
2. Add the class to `@SelectClasses` in `TS_Text.java`.
3. Run `mvn test -pl jena-text`.
4. Check that the class name appears in the `-- in <class>` lines of the output.
