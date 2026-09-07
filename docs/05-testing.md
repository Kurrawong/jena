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

### SHACL Entity-Per-Document Tests

| Class | Tests | What it covers |
|-------|-------|---------------|
| `TestShaclIndexMapping` | 13 | Data model: predicate lookup, class lookup, field resolution, facet field names, defaults, hierarchy metadata |
| `TestShaclDocumentBuilding` | 11 | Lucene doc building: TEXT/KEYWORD/INT/LONG/DOUBLE field types, multi-valued, discriminator, null fields, int-from-string |
| `TestShaclTextDocProducer` | 5 | Change listener: add type creates doc, add property rebuilds, delete type removes, irrelevant predicate ignored, multiple entities |
| `TestShaclAssembler` | 9 | Config parsing: valid shapes, SHACL/entity-map exclusivity, hierarchy config, and assembler validation paths |
| `TestShaclEntityPerDocument` | 7 | End-to-end: text search, SPARQL `luc:query`, facet counts, filtered facets, add after load, entity-per-doc model verification |
| `TestLuceneQuerySyntax` | 12 | The `queryString` argument reaching Lucene's classic parser: phrase, boolean, required/prohibited, trailing/leading/single-char wildcard, fuzzy, proximity slop, boost, grouping, lexical term range, and the bare `*` match-all short-circuit |
| `TestDateLiteralRoundTrip` | 4 | TEMPORAL fields: `between` and `>=` over the epoch twin field, typed `xsd:date`/`xsd:dateTime` reconstruction on read, language-tagged literal round trip, and an unparseable date dropping out of range queries without failing the build |

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

### External Content Tests

| Class | Tests | What it covers |
|-------|-------|---------------|
| `TestCsvRowSource` | 15 | CSV/TSV reading: header and positional binding, `idx:subjectPrefix`, glob expansion in filename order, empty cell → null, blank join key skipped-but-counted, rows emitted in file order without an ordering check, and the config errors on open — missing file, empty glob, missing subject/bound column |
| `TestExternalSourceAssembler` | 13 | Turtle config: narrow source, optional properties, headerless positional binding, bound fields becoming profile fields, hierarchy over external fields, and the validation rules — `idx:joinPath` xor `idx:externalSource`, required `idx:nestedName`, no `sh:path` on a bound column, exactly one of `idx:columnName`/`idx:columnIndex`, unknown format, unsupported field type |
| `TestExternalContentIndexing` | 19 | End-to-end: rows become children of the matching entity; entities with no rows still indexed; unmatched rows counted and dropped; same-child `=` + range correlation; entity-level AND across two properties is *not* same-child; sort by a not-stored external value with `missing` placement; `idx:subjectPrefix`; a bad join key building successfully with a zero match rate in the counters; unsorted buffering; empty and unparseable cells; `idx:onError "fail"`; hierarchical facets over external children; live graph change does not strip external children. Plus wide children (depth-from/depth-to/analyte/value on one child): four-way same-child correlation, the decorrelation check that a Cu child and a deep child on the same hole do not satisfy one AND, and two analytes still not being same-child |

### Existing Tests (unchanged, verifying no regressions)

The remaining suite covers text search, multilingual support, graph indexing, deletion, analyzers, property lists, spatial filtering, nested identifiers, and demo mining scenarios. The full `jena-text` module currently passes at 809 tests.

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

## Adding New Tests

1. Create your test class in `jena-text/src/test/java/org/apache/jena/query/text/`
2. Add it to `TS_Text.java` suite class (Surefire won't find it otherwise)
3. Run: `mvn test -pl jena-text`

For assembler tests, put them in the `assembler` subpackage and import into `TS_Text.java`.
