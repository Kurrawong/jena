# Testing

## Running Tests

```bash
# Full jena-text suite (809 tests)
mvn test -pl jena-text

# Only SHACL / faceting tests
mvn test -pl jena-text -Dtest="TestShaclIndexMapping,TestShaclDocumentBuilding,TestShaclTextDocProducer,TestShaclAssembler,TestShaclEntityPerDocument,TestNativeFacetCounts,TestTextFacetPF,TestTextQueryPFFilters,TestSearchExecution,TestHierarchicalFacets,TestHierarchicalFacetsSparql,TestSortSpec"
```

Tests are aggregated in `TS_Text.java` (Surefire only picks up `**/TS_*.java`). The suite class uses the JUnit 5 `@Suite` / `@SelectClasses` annotations; the fork's older SHACL/faceting classes are still JUnit 4 and run through `junit-vintage-engine`. New tests should be written in JUnit 5.

A class missing from `@SelectClasses` is **silently never run** — not reported as skipped. After adding a test, confirm it appears in the `-- in <class>` lines.

---

## Test discipline

- **Documented recommendations must be backed by a test.** Anything we recommend in docs (e.g. the `idx:normalizer` twin-field pattern) needs a test exercising the *exact* recommended shape, including the real field cardinality (single- vs multi-valued). Advice must not outrun coverage.
- **Red before green.** For a bug fix or new behaviour, add the failing test first, confirm it fails for the expected reason, then apply the fix. Keep red and green as separate commits where practical so the TDD step is visible.
- **Cover the corners of the config matrix.** When a change interacts with existing flags (`sortable × multiValued × normalized × field type`), test the intersection production configs actually use — not each axis alone. (This is why multi-valued + normalized KEYWORD sorting had a gap: single-valued and multi-valued were each tested, but never together.)

---

## Test Suite Overview

### SHACL Faceting Tests

| Class | Tests | What it covers |
|-------|-------|---------------|
| `TestNativeFacetCounts` | 10 | Java API: open facets, filtered facets, maxValues, minCount, getAllChildren, empty/nonexistent fields |
| `TestTextFacetPF` | 16 | SPARQL `luc:facet` PF: flat/range facets, mixed requests, filters, maxValues, minCount, subject arity checks, empty-string placeholders |
| `TestTextQueryPFFilters` | 13 | SPARQL `luc:query` with JSON filters, field-IRI scoping, empty-string placeholders, string limits, and end-to-end sort pushdown |
| `TestSearchExecution` | 10 | Shared execution: key generation, normalisation, index-aware reuse, and sort-sensitive cache keys |
| `TestLucQueryHitPath` | 25 | The `luc:query` hit path: `(?hit)` decodes no stored field and `(?hit ?entity)` only the entity field or its doc value, counted through `HitReadObserver`; entity order under relevance and sort; constant `?entity`; no match details without `luc:match`, only matched fields decoded with it, and only for the returned page; two searches in one query each resolving their own `?hit`; constant field slots in `luc:match` and `luc:nestedMatch`; negative and zero `limit`; the searcher held while iterating, until the last of two iterators closes, and released on finish, abandon and cancel; late reads with and without a commit; a window re-run on the held searcher ignoring a later commit, and raising after release and a commit; closing a query after its index closed |
| `TestLucMatchLargeWindow` | 2 | `luc:match` and `luc:nestedMatch` over 300,000 hits within 90 s, which the earlier per-call scan of every cached hit does not meet |
| `TestFieldValueStorage` | 10 | Which fields are read from doc values; an eligible stored field written as a doc value only; original terms from doc values and from stored fields (normalized, facet-only, language-tagged, numeric, temporal); indexes with stored copies of eligible fields; a segment written before a field was sortable; the value `luc:match` selects on a multi-valued doc-value field; `luc:nestedMatch` reading child fields from doc values; `queryByFields`, `queryWithFilters` and `queryWithCql` returning doc-value-only values |
| `TestLucFields` | 16 | `luc:fields`: the identifier query reading no stored field, variable and constant field slots, multi-valued fields, two patterns per hit, joins with `luc:match` in both orders, each error, a late read after a commit, one read per field, two indexes in one query |
| `TestEntityDocValue` | 5 | The entity IRI doc value: written as binary and giving the same IRIs under relevance and sort with no stored read, an index without it, an old segment without it beside a new one with it, parent documents only, and entity updates and deletes |
| `TestTextIndexDump` | 1 | `textindexdump` printing fields kept in doc values only |

### SHACL Entity-Per-Document Tests

| Class | Tests | What it covers |
|-------|-------|---------------|
| `TestShaclIndexMapping` | 13 | Data model: predicate lookup, class lookup, field resolution, facet field names, defaults, hierarchy metadata |
| `TestShaclDocumentBuilding` | 11 | Lucene doc building: TEXT/KEYWORD/INT/LONG/DOUBLE field types, multi-valued, discriminator, null fields, int-from-string |
| `TestShaclTextDocProducer` | 5 | Change listener: add type creates doc, add property rebuilds, delete type removes, irrelevant predicate ignored, multiple entities |
| `TestShaclAssembler` | 9 | Config parsing: valid shapes, SHACL/entity-map exclusivity, hierarchy config, and assembler validation paths |
| `TestShaclEntityPerDocument` | 7 | End-to-end: text search, SPARQL `luc:query`, facet counts, filtered facets, add after load, entity-per-doc model verification |
| `TestLuceneQuerySyntax` | 12 | The `queryString` argument reaching Lucene's classic parser: phrase, boolean, required/prohibited, trailing/leading/single-char wildcard, fuzzy, proximity slop, boost, grouping, lexical term range, and the bare `*` match-all short-circuit |
| `TestQueryParserSettings` | 12 | `text:splitOnWhitespace` and `text:autoGeneratePhraseQueries`: a code's pieces must be adjacent in a text field through `luc:query`, `luc:facet` and a nested scope; separate words still match any word; a code containing a space is not found whole unless escaped; both settings off, splitting only, the rejected pairing and a non-boolean value |
| `TestDateLiteralRoundTrip` | 4 | TEMPORAL fields: `between` and `>=` over the epoch twin field, typed `xsd:date`/`xsd:dateTime` reconstruction on read, language-tagged literal round trip, and an unparseable date dropping out of range queries without failing the build |
| `TestTemporalSpan` | 6 | The interval a temporal literal spans, in UTC, on Prez's fixture values: a dateTime and a dateTimeStamp are one instant, with an offset converted and no timezone taken as UTC; a date is its whole day at its own offset; a gYearMonth and a gYear are their whole month and year, December rolling over and any offset ignored; gMonthDay, time, a date-shaped string, an IRI and an invalid date have no span |
| `TestTemporalComparisonsByStart` | 4 | `=` and `between` on a TEMPORAL field compare a value's start: an offset date by midnight at its offset, a gYear and a gYearMonth by their first day, a date-shaped string still by its lexical form, and `{"date"}`/`{"timestamp"}` literals as their lexical form |
| `TestTemporalOperators` | 15 | Prez's temporal cases verbatim, on a mirror of its fixture: every dateTime, date, offset date, year-month and year against timestamps, dates, intervals with dates, timestamps, open ends and property ends, the property first or second, an `and` of clauses on two fields, years before 1000 and a December |
| `TestTemporalOperatorOracle` | 1 | All fifteen operators, against 120 generated timestamps, dates and intervals in both operand orders (3,600 checks), agree with OGC 21-065r2's definitions evaluated directly, on a six-hour grid where ends coincide often |
| `TestTemporalOperatorSemantics` | 6 | A property operand keeps each value's start with its own end; an interval's property ends are bound separately; a multi-valued field matches when any value does, `t_disjoint` included; an entity with no value matches no operator, only a `not`; two literals are a constant; and the filters that raise |
| `TestNestedTemporalOperators` | 2 | A temporal clause on a child field surfaces the parent, and an `and` of it with a keyword clause on the same scope must hold of one child |
| `TestPeriodField` | 8 | `idx:PeriodField`: each period keeps its start with its own end (two periods do not match on one's start and the other's end); OWL-Time timestamps through `time:inXSDDateTimeStamp` are instants; a missing bound is open, and an open end equals an open query end; a period with two starts, an unreadable bound or an end before its start is skipped; `idx:self` makes the entity the period; a nested period correlates with its child's other fields; an `sh:minCount` constraint on the period node keeps only closed periods; editing the end date two hops down, or removing the end, reindexes the entity |
| `TestPeriodFieldOracle` | 1 | All fifteen operators on a PeriodField, against generated periods open at either end and 120 operands in both orders (3,600 checks), agree with OGC 21-065r2's definitions, and the checks are not vacuous |
| `TestPeriodFieldConfig` | 13 | A PeriodField is unstored by default; rejected at config time: a missing `idx:periodStart` or `idx:periodEnd`, either on another field type, on the canonical field or on a step, `idx:stored`, `idx:facetable`, `idx:sortable`, `idx:defaultSearch` or `idx:storeLiteralMetadata` true, a hierarchy level, and a path the listener cannot follow |
| `TestCqlTemporalParsing` | 3 | The `t_` operator names are cased as the CQL2-JSON schema writes them (`t_finishedby` is unknown); every operand form parses; and the canonical form keeps operand order, so `luc:query` and `luc:facet` do not share a search across swapped operands |
| `TestCqlFilterErrors` | 3 | `luc:query` and `luc:facet` raise a filter they cannot run, or malformed filter JSON, as a `QueryParseException`, which Fuseki answers with a 400; `luc:facet` no longer answers it with no rows |

### Hierarchical Facets Tests

| Class | Tests | What it covers |
|-------|-------|---------------|
| `TestHierarchicalFacets` | 9 | Java API: taxonomy indexing, top-level facets, drill-down path building, flat+hierarchy coexistence, multi-valued hierarchies, empty dimensions |
| `TestHierarchicalFacetsSparql` | 3 | SPARQL `luc:facet` with hierarchy: top-level via field IRI, drill-down via CQL filter, flat facets alongside hierarchy |
| `TestSelfBoundOccurrences` | 6 | `idx:self` in a nested block, where the child node is itself a hierarchy level: top-level counts, drill-down correlated per child, children not out-counting the parent, same-child correlation with a sibling field, vocabulary-edit reindex, child-added reindex |
| `TestSelfOccurrenceAssembler` | 8 | `idx:self` config surface: parses in a nested block and at root; rejects self+`sh:path`, neither, `idx:self false`, a numeric field, and use on an `idx:column` |

### Sort Tests

| Class | Tests | What it covers |
|-------|-------|---------------|
| `TestSortSpec` | 9 | Sort JSON parsing, field-IRI sort specs, Lucene sort construction, numeric selector semantics, invalid text-field sorting |
| `TestTextQueryPFFilters` | 13 | End-to-end SPARQL `luc:query` sorting with field IRIs, including descending and filtered ascending order |
| `TestCursorPaging` | 35 | The entity IRI sort key: ties in IRI order, before and after re-insertion, ascending and descending, code point order where Jena's `ORDER BY` differs, and an error on an index without the IRI doc value. Unknown, unsortable, `TEXT` and facetable-only numeric sort fields; a failed search raising. Cursor walks over a normalized keyword, a multi-valued numeric, an explicit IRI key and relevance order; ending a walk on `?totalHits` and on looking ahead one hit, continuing from the last hit shown; a cursor surviving re-insertion of every entity; `offset` from a cursor; the empty eighth argument; a cursor used with another query, another sort, or malformed, including truncated and extended; the marker each client-facing error starts with; the sixth subject element, `?cursor`, without an eighth argument, with an empty one, and as a constant, and a seventh element; the token round trip and its checks |

### External Content Tests

| Class | Tests | What it covers |
|-------|-------|---------------|
| `TestCsvRowSource` | 15 | CSV/TSV reading: header and positional binding, `idx:subjectPrefix`, glob expansion in filename order, empty cell → null, blank join key skipped-but-counted, rows emitted in file order without an ordering check, and the config errors on open — missing file, empty glob, missing subject/bound column |
| `TestExternalSourceAssembler` | 13 | Turtle config: narrow source, optional properties, headerless positional binding, bound fields becoming profile fields, hierarchy over external fields, and the validation rules — `idx:joinPath` xor `idx:externalSource`, required `idx:nestedName`, no `sh:path` on a bound column, exactly one of `idx:columnName`/`idx:columnIndex`, unknown format, unsupported field type |
| `TestExternalContentIndexing` | 19 | End-to-end: rows become children of the matching entity; entities with no rows still indexed; unmatched rows counted and dropped; same-child `=` + range correlation; entity-level AND across two properties is *not* same-child; sort by a not-stored external value with `missing` placement; `idx:subjectPrefix`; a bad join key building successfully with a zero match rate in the counters; unsorted buffering; empty and unparseable cells; `idx:onError "fail"`; hierarchical facets over external children; live graph change does not strip external children. Plus wide children (depth-from/depth-to/analyte/value on one child): four-way same-child correlation, the decorrelation check that a Cu child and a deep child on the same hole do not satisfy one AND, and two analytes still not being same-child |

### sh:node Steps and Value Constraints

Both classes configure the index from Turtle through `ShaclTurtleFixture`, which also reads
the written Lucene documents back.

| Class | Tests | What it covers |
|-------|-------|---------------|
| `TestShaclNodeOccurrenceFilters` | 14 | Steps: a filtered identifier value as a sortable root field; two kept values, and the single-valued warning; filters with `sh:in`, an inner `sh:minCount`, a constraint on the shape, and a field-less `sh:node` member; a step inside a step; a step inside `idx:nested` feeding the child; an occurrence's own constraint not deciding whether its node is kept; the change listener following a filter predicate, a field value and an `rdf:type`; the fingerprint following a filter; bulk indexer and change listener writing the same documents |
| `TestShaclOccurrenceValueConstraints` | 33 | Each accepted per-value constraint filtering values; each set constraint, and `sh:sparql` on an occurrence and in a filter, rejected naming the constraint; repeating paths rejected; `sh:class` admitting a subclass instance |

### Existing Tests (unchanged, verifying no regressions)

The remaining suite covers text search, multilingual support, graph indexing, deletion, analyzers, property lists, spatial filtering, nested identifiers, and demo mining scenarios.

`TestDemoExamples` is the one test that uses the demo's real files rather than a fixture
built in Java. It parses `demo/test/config.ttl` with the production assembler, loads
`demo/test/data/*.ttl`, indexes through `ShaclBulkIndexer` (the path `task index` runs, so
external-source shapes are covered), then runs every saved search in `demo/test/tests.json`
as a dynamic test.

It asserts two things per example, and the second is the one that matters: the example
clears its `minResults`, and a declared filter **actually changes the result set**. A
filter that quietly does nothing passes a row-count floor, because an unfiltered query
returns more rows rather than fewer. Several demo bugs presented exactly that way, so a
floor alone is not enough.

What it cannot reach is the browser's own pipeline: URL encoding, and the
`parseCqlFilter`/`buildCqlFilter` round trip. `demo/testing/check-examples.mjs` covers
those against a running server, via `task check-examples`.

---

## Test Patterns

### Programmatic setup (no assembler)

Most tests create the index programmatically:

```java
// Define fields
FieldDef titleField = new FieldDef("title", FieldType.TEXT, null,
    true, true, false, false, false, true,
    Collections.singleton(TITLE_PRED));

// Build profile and mapping
IndexProfile profile = new IndexProfile(shapeNode, targetClasses, "uri", "docType", fields);
ShaclIndexMapping mapping = new ShaclIndexMapping(Collections.singletonList(profile));
EntityDefinition defn = ShaclIndexAssembler.deriveEntityDefinition(mapping);

// Build config and index
TextIndexConfig config = new TextIndexConfig(defn);
config.setShaclMapping(mapping);
config.setFacetFields(mapping.getFacetFieldNames());

TextIndexLucene textIndex = new TextIndexLucene(new ByteBuffersDirectory(), config);

// Wire dataset with SHACL producer
ShaclTextDocProducer producer = new ShaclTextDocProducer(baseDs.asDatasetGraph(), textIndex, mapping);
Dataset dataset = TextDatasetFactory.create(baseDs, textIndex, true, producer);
```

### Assembler-based setup

`TestShaclAssembler` builds config in-memory using the Jena Model API:

```java
Resource bookShape = model.createResource(EX + "BookShape")
    .addProperty(model.createProperty(SH, "targetClass"), model.createResource(EX + "Book"))
    .addProperty(model.createProperty(SH, "property"),
        model.createResource()
            .addProperty(model.createProperty(IDX, "fieldName"), "label")
            .addProperty(model.createProperty(IDX, "fieldType"), IndexVocab.TextField)
            .addProperty(model.createProperty(IDX, "defaultSearch"), model.createTypedLiteral(true))
            .addProperty(model.createProperty(SH, "path"), RDFS.label));

RDFNode shapesList = model.createList(new RDFNode[]{ bookShape });

Resource indexSpec = model.createResource(EX + "index")
    .addProperty(RDF.type, TextVocab.textIndexLucene)
    .addProperty(TextVocab.pDirectory, model.createLiteral("mem"))
    .addProperty(TextVocab.pShapes, shapesList);

TextIndexLucene index = (TextIndexLucene) Assembler.general().open(indexSpec);
```

---

## What's Tested vs Not Tested

### Covered

- All SPARQL argument forms for `luc:query` and `luc:facet`
- JSON filter parsing and semantics (OR within field, AND across fields)
- All field types: TEXT, KEYWORD, INT, LONG, DOUBLE, TEMPORAL (`TestDateLiteralRoundTrip` at root scope, `TestNestedTemporalField` inside `idx:nested`), LATLON (`TestSpatialFiltering`)
- Multi-valued fields
- Entity lifecycle: create, update (add field), delete (remove type)
- Assembler config parsing (valid and error cases)
- Shared execution between PFs
- Facet count accuracy with filters
- minCount and maxValues options
- End-to-end SPARQL sort pushdown using field IRIs
- Hierarchical facets: taxonomy indexing, top-level counts, drill-down via CQL filters, flat+hierarchy coexistence
- Range facets on numeric fields: single-valued, multi-valued, open-ended buckets, mixed flat+range requests, and 5-slot `luc:facet` bindings
- Range facets on TEMPORAL fields: ISO date boundaries counted from the epoch docvalues, lower bound inclusive and upper exclusive except on the open end
- Multi-valued numeric sorting semantics (`MIN` for ascending, `MAX` for descending)
- External content from CSV/TSV: the IRI join, match/unmatch counters, sortedness verification, and the same-child vs entity-level correlation boundary

### Not yet covered (candidates for future tests)

- Named graph support in SHACL mode
- Multiple shapes with overlapping predicates
- Large-scale performance (10k+ entities)
- Concurrent write transactions
- TTL-file-based assembler integration test (currently programmatic only)
- `sh:alternativePath` in assembler config
- Edge cases: empty string values, very long field values, special characters in filters

---

## Fuseki Integration Testing

The unit tests above cover the Java API and SPARQL property functions programmatically. For end-to-end testing with a running Fuseki server (HTTP endpoint, data loading, curl queries), see the [Deploying with Fuseki](01-user-guide.md#deploying-with-fuseki) section of the User Guide.

```bash
# Build Fuseki
mvn clean install -pl jena-fuseki2/jena-fuseki-server -am -DskipTests

# Start with a config file
java -jar jena-fuseki2/jena-fuseki-server/target/jena-fuseki-server-*.jar \
    --config config.ttl
```

---

## luc:query benchmark

`BenchLucQuery`, in `jena-text/src/test/java/org/apache/jena/query/text/bench/`, is a JMH
benchmark of `luc:query` over a synthetic index. It is in the test sources but is not a
test: surefire runs only `TS_*` classes and `TS_Text` does not list it.

### Running it

```bash
mvn -o -q -pl jena-text test-compile dependency:build-classpath \
    -Dmdep.outputFile=target/bench.classpath -Dmdep.includeScope=test -Drat.skip
cd jena-text
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
    -cp target/test-classes:target/classes:$(cat target/bench.classpath) \
    org.apache.jena.query.text.bench.BenchLucQuery -rf json -rff results.json
```

Arguments after the class name are JMH's. A regex runs a subset: `BenchLucQuery pageText`
runs the three page scenarios. `-l` builds the index and lists the scenarios without
running them.

The first run generates the data, loads it into TDB2 with the parallel loader and builds the
Lucene index with `shacltextindexer`, the same path as a production bulk load. On a 12-core
machine this took about two and a half minutes. Later runs reuse the build, which lives in
`jena-text/target/bench-luc-query` and is therefore deleted by `mvn clean`. System
properties:

| Property | Default | Meaning |
|---|---|---|
| `bench.dir` | `jena-text/target/bench-luc-query` | Where the dataset and index are built and reused |
| `bench.entities` | `500000` | Entity count; both large windows are three fifths of it |
| `bench.matchWindow` | `20000` | Window of `largeTextWithMatch` |

The benchmark uses JMH's sample-time mode in milliseconds, with 3 warmup and 5 measurement
iterations of 10 seconds each, in one forked JVM with an 8 GB heap. JMH reports the mean
and the 50th, 90th, 95th and 99th percentiles. Compare runs at the median, on the same
machine, with the machine otherwise idle.

### Data

500,000 entities of one SHACL shape, in a TDB2 store on disk. Each entity has two `TEXT`
fields of about 300 characters drawn from a 5,000-word vocabulary with a Zipf
distribution, a sortable `KEYWORD` identifier, four facetable `KEYWORD` fields, an `INT`
year, and two `schema:identifier` children (`schema:propertyID`, `schema:value`) indexed
through `idx:nested`. Every field except `entityType` is stored. Hit counts are fixed by
the entity number: the term `survey` matches 300,000 entities, the term `basalt` 10,638,
and the filter `status = active` 300,000. The index config is
`jena-text/src/test/resources/bench/luc-query-bench.ttl`.

The store is TDB2 rather than in-memory because production runs on TDB2.

### Scenarios

Each scenario runs one SPARQL query through ARQ in a read transaction and reads every
binding of every row. Before timing, each scenario runs its query once and fails if the
row count is wrong.

| Scenario | Query | Rows |
|---|---|---|
| `pageText` | `basalt`, page of 20, binds `?entity` | 20 |
| `pageTextWithMatch` | as `pageText`, joined to `luc:match` | 20 |
| `pageTextWithFacet` | as `pageText`, in a `UNION` with `luc:facet` over four fields, 10 values each | 51 |
| `largeFilterOnly` | `*` with the `status` filter, limit 300,000, binds `?entity` | 300,000 |
| `largeTextNoMatch` | `survey`, limit 300,000, binds `?entity` | 300,000 |
| `largeTextWithMatch` | `survey` joined to `luc:match`, limit `bench.matchWindow` | 20,000 |
| `largeIdentifierFields` | `*` with the `status` filter, limit 300,000, binds `?hit` only, `recordId` through `luc:fields` | 300,000 |

`largeIdentifierFields` reads `recordId`, a sortable `KEYWORD`, from its doc value. An
index built before field values were read from doc values still has a stored copy, so
build a fresh one with `-Dbench.dir`. The same holds for the entity IRI doc value that
`largeFilterOnly` reads; an older index falls back to the stored entity field.

`largeTextWithMatch` runs at 20,000 hits rather than 300,000. `luc:match` finds each
incoming hit by scanning every cached hit, so the query time grows with the square of the
window: on the machine above it took 3.5 seconds at 20,000 hits and 15 seconds at 40,000,
which projects to more than ten minutes per query at 300,000. Run it at 300,000 with
`-Dbench.matchWindow=300000` once that lookup is linear.

---

## Adding New Tests

1. Create your test class in `jena-text/src/test/java/org/apache/jena/query/text/`
2. Add it to `TS_Text.java` suite class (Surefire won't find it otherwise)
3. Run: `mvn test -pl jena-text`

For assembler tests, put them in the `assembler` subpackage and import into `TS_Text.java`.
