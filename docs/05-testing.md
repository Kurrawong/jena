# Testing

## Running Tests

```bash
# Full jena-text suite (546 tests)
mvn test -pl jena-text

# Only SHACL / faceting tests
mvn test -pl jena-text -Dtest="TestShaclIndexMapping,TestShaclDocumentBuilding,TestShaclTextDocProducer,TestShaclAssembler,TestShaclEntityPerDocument,TestNativeFacetCounts,TestTextFacetPF,TestTextQueryPFFilters,TestSearchExecution,TestHierarchicalFacets,TestHierarchicalFacetsSparql,TestSortSpec"
```

All tests run via JUnit 4 and are aggregated in `TS_Text.java` (Surefire only picks up `**/TS_*.java`).

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

### Hierarchical Facets Tests

| Class | Tests | What it covers |
|-------|-------|---------------|
| `TestHierarchicalFacets` | 9 | Java API: taxonomy indexing, top-level facets, drill-down path building, flat+hierarchy coexistence, multi-valued hierarchies, empty dimensions |
| `TestHierarchicalFacetsSparql` | 3 | SPARQL `luc:facet` with hierarchy: top-level via field IRI, drill-down via CQL filter, flat facets alongside hierarchy |

### Sort Tests

| Class | Tests | What it covers |
|-------|-------|---------------|
| `TestSortSpec` | 9 | Sort JSON parsing, field-IRI sort specs, Lucene sort construction, numeric selector semantics, invalid text-field sorting |
| `TestTextQueryPFFilters` | 13 | End-to-end SPARQL `luc:query` sorting with field IRIs, including descending and filtered ascending order |

### Existing Tests (unchanged, verifying no regressions)

The remaining suite covers text search, multilingual support, graph indexing, deletion, analyzers, property lists, spatial filtering, nested identifiers, and demo mining scenarios. The full `jena-text` module currently passes at 546 tests.

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
- All five field types (TEXT, KEYWORD, INT, LONG, DOUBLE)
- Multi-valued fields
- Entity lifecycle: create, update (add field), delete (remove type)
- Assembler config parsing (valid and error cases)
- Shared execution between PFs
- Facet count accuracy with filters
- minCount and maxValues options
- End-to-end SPARQL sort pushdown using field IRIs
- Hierarchical facets: taxonomy indexing, top-level counts, drill-down via CQL filters, flat+hierarchy coexistence
- Range facets on numeric fields: single-valued, multi-valued, open-ended buckets, mixed flat+range requests, and 5-slot `luc:facet` bindings
- Multi-valued numeric sorting semantics (`MIN` for ascending, `MAX` for descending)

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
