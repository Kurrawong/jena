---
title: "luc:config — the effective index configuration as triples"
date: "2026-10-09"
status: "Planned; nothing implemented"
---

# luc:config — the Effective Index Configuration as Triples

`luc:config` is a proposed read-only property function that returns the configuration of a
SHACL index as triples, in the same `sh:` and `idx:` vocabulary the configuration is
written in. A client uses it to discover which fields exist and what each one supports —
faceting, sorting, filtering, range — over the ordinary SPARQL query endpoint.

Claims about current behaviour were checked against `227a8f29f1`.

## Why a property function

The server already describes its configuration at `GET /$/config/effective`
(`jena-fuseki2/jena-fuseki-mod-config/.../EffectiveConfig.java`, from #154). That endpoint
has two limits:

- It is under `/$/`, so Fuseki's `LocalhostFilter` or Shiro gates it. A client that can
  reach `/{dataset}/query` and nothing else cannot read it. The demo app reaches it only
  through an allowlist in `serve_app.py`.
- Its JSON omits `sh:path`, so the demo app still parses the configuration Turtle with
  N3.js to learn which predicate feeds which field (`extractConfig` in
  `demo/app-static/app.js`).

A property function is answered by the query endpoint the client already uses. It is scoped
to the dataset being queried, it works in embedded ARQ with no Fuseki module, and its
output can be joined, filtered and `CONSTRUCT`ed like any other SPARQL result.

## Where the triples come from

Fuseki parses the configuration file into a Jena `Model` at startup and hands each
assembler a `Resource` inside it; `ShaclTextIndexAssembler.open(Assembler, Resource, Mode)`
is the one for this index. The assembler reads the shapes into a `ShaclIndexMapping`, and
the `Model` is discarded once the server is built. The running index keeps only the
mapping.

The triples are therefore **generated from `ShaclIndexMapping`**, not copied from the
file. That choice decides most of what is and is not exposed:

- Defaults are resolved. A field with no `idx:facetable` triple in the file is reported
  as `idx:facetable false`, so a client never has to know the defaults.
- Only what reached the mapping can appear. Fuseki services, endpoints, `ja:context`
  timeouts, `tdb2:location`, `text:directory` and `text:taxonomyDirectory` never reach it.

`sh:path` is the exception, covered below.

## Output

The output reproduces the configuration's own structure: a node shape with
`sh:targetClass`, one `sh:property` occurrence per field binding, and each canonical field
as a named resource carrying its flags. Every flag in the field-properties table of
[03-configuration.md](03-configuration.md#field-properties) is written explicitly, including
those left at their default. No term outside the existing `sh:` and `idx:` vocabularies is
introduced.

For a shape configured as

```turtle
<#ReportShape>
    sh:targetClass ex:MiningReport ;
    sh:property [ idx:field field:commodity ; sh:path ex:commodity ] ;
    sh:property [ idx:field field:operator ;
                  sh:path ( ex:operatedBy [ sh:inversePath ex:hasOperator ] ) ] ;
    idx:facetHierarchy dim:region .

field:commodity idx:fieldName "commodity" ; idx:fieldType idx:KeywordField ;
                idx:facetable true ; idx:multiValued true .
dim:region idx:levels ( field:state field:district ) .
```

`CONSTRUCT { ?s ?p ?o } WHERE { (?s ?p ?o) luc:config ("default") }` returns

```turtle
[] a sh:NodeShape ;
   sh:targetClass ex:MiningReport ;
   sh:property [ idx:field field:commodity ; sh:path ex:commodity ] ;
   sh:property [ idx:field field:operator ;
                 sh:path ( ex:operatedBy [ sh:inversePath ex:hasOperator ] ) ] ;
   idx:facetHierarchy dim:region .

field:commodity
    idx:fieldName "commodity" ;
    idx:fieldType idx:KeywordField ;
    idx:stored true ;
    idx:indexed true ;
    idx:facetable true ;
    idx:sortable false ;
    idx:multiValued true ;
    idx:defaultSearch false ;
    idx:storeLiteralMetadata false .

dim:region idx:levels ( field:state field:district ) .
```

Nested blocks are written the same way: `idx:nested` with `idx:nestedName`,
`idx:joinPath` and their own `sh:property` occurrences. A hierarchy configured as a bare
list is written as a bare list.

### sh:path is copied as written

`FieldOccurrence` holds an ARQ `Path`, not the RDF it was parsed from. Writing it back
with `jena-shacl`'s `ShaclPaths.pathToRDF` was considered and rejected for two reasons
found in `jena-shacl/.../engine/ShaclPaths.java`:

- `visit(P_Seq)` writes a two-element list for each `P_Seq` node. The parser builds
  sequences left-deep, so the configured `( ex:a ex:b ex:c )` comes back as
  `( ( ex:a ex:b ) ex:c )`. That is a valid SHACL path with the same meaning, but it is
  not what the configuration says. `visit(P_Alt)` nests the same way.
- `visit(P_ReverseLink)` writes `sh:alternativePath` where `sh:inversePath` is meant.
  `ShaclIndexAssembler` produces `P_Inverse`, not `P_ReverseLink`, so this is not reached
  by our paths, but it rules the writer out as a dependable inverse.

Instead, `ShaclIndexAssembler` copies the `sh:path` subgraph out of the configuration model
while the model is still available: the path's head node plus every triple reachable
through blank nodes and list cells. A SHACL path contains only IRIs, blank nodes and list
cells, so that closure is exact. The copy is held beside the mapping and emitted
unchanged. It is not part of the fingerprint serialisation, so it cannot change the
fingerprint.

`idx:joinPath` on a nested block is handled the same way.

### Querying the output

Each subject-list slot is either a variable or a constant, and the property function
evaluates as `graph.find(s, p, o)` over the generated graph, so a call behaves like one
triple pattern:

```sparql
PREFIX luc: <urn:jena:lucene:index#>
PREFIX idx: <urn:jena:lucene:index#>

SELECT ?field ?type WHERE {
  (?field idx:facetable true) luc:config ("default") .
  (?field idx:fieldType ?type) luc:config ("default") .
}
```

Every call for one index in one query reads the same in-memory graph, so blank nodes join
across calls.

Property paths in the query run against the dataset, not against `luc:config`'s output. A
client therefore cannot walk an RDF list such as a sequence path with `rdf:rest*/rdf:first`
inside the query. A single-predicate path is one triple and matches in one pattern; for a
sequence or alternative path, `CONSTRUCT` the graph and query the result.

## What is left out

| Item | Why |
|---|---|
| `text:directory`, `text:taxonomyDirectory`, `tdb2:location`, Fuseki services, `ja:context` | Not in the mapping |
| `idx:externalSource` | Its `idx:location` and delta locations are file paths. The whole block is left out in the first version rather than emitted with holes |
| Shape IRIs | Under the `@prefix : <#>` idiom a shape IRI resolves against the configuration file, e.g. `file:///srv/config.ttl#ReportShape`, which discloses the file's location. Shapes are emitted as blank nodes, identified by `sh:targetClass` |
| Analyzers | `FieldDef` holds `Analyzer` instances, not the RDF that configured them. See open decisions |
| Fingerprint and index status | No term for them exists in `idx:`. `/$/config/effective` already reports them |

Field IRIs are emitted. Under the same `<#>` idiom a field IRI is also a `file:` IRI, but
`luc:query`, `luc:match` and `luc:facet` already return field IRIs, so `luc:config`
discloses nothing new there. [03-configuration.md](03-configuration.md) should recommend a
real namespace for field IRIs.

## Turning it off

Some deployments will not want the index shape readable by everyone who can query. A
boolean on the index resource, proposed as `text:exposeConfig` with default `true`,
disables it. With it set to `false`, `luc:config` raises a query error for that index, as
the other property functions do for a selector they cannot use.

The default is `true` because most of the shape is already discoverable: `luc:facet` with
`["*"]` lists every facetable field.

## Implementation

| Change | Where |
|---|---|
| Copy each `sh:path` and `idx:joinPath` subgraph at assembly | `ShaclIndexAssembler`, held on `ShaclIndexMapping` |
| Build the output graph from the mapping, once per index | New class in `org.apache.jena.query.text`, held by `ShaclTextIndexLucene` |
| The property function | New `ShaclConfigPF`; `IndexVocab.pfConfig = NS + "config"`; registered in `TextQuery` beside `pfFacet` |
| Index resolution | `ShaclTextQueryPF` (`:122`) and `TextFacetPF` (`:103`) each carry a copy of the selector-to-index logic. Extract it rather than add a third copy |
| `text:exposeConfig` | `ShaclTextIndexAssembler`, `TextVocab` |
| Docs | `luc:config` section in [02-sparql-api.md](02-sparql-api.md), the flag in [03-configuration.md](03-configuration.md), a row in [README.md](README.md) |

Moving `/$/config/effective` onto the same graph, serialised as Turtle or JSON-LD, is a
follow-up. Until then the two describe the mapping separately.

## Tests

Written first and seen to fail, per the repository's test discipline. New classes are
JUnit 5 and must be added to `TS_Text`'s `@SelectClasses`.

- Each flag in the field-properties table appears, with defaults written as explicit
  `false` or `true`.
- `idx:fieldType` defaults to `idx:TextField` when not configured.
- Paths round-trip as written: a single predicate, a three-element sequence (asserting the
  flat list, not a nested one), an inverse, an alternative, and an inverse inside a
  sequence.
- A nested block's `idx:joinPath` and occurrences appear under `idx:nested`.
- A named hierarchy and a bare-list hierarchy each appear in their configured form, with
  levels in order.
- A constant in a slot filters: `(?f idx:facetable true)` returns only facetable fields.
- Two calls join on a blank-node occurrence.
- No `file:` IRI and no configured directory or CSV path string appears anywhere in the
  output, using a configuration that sets all of them and uses `@prefix : <#>`.
- The multi-index selector returns each index's own configuration; an unknown selector
  raises.
- `text:exposeConfig false` raises.
- The fingerprint of a configuration is unchanged by the path-subgraph copy.

## Open decisions

1. **Analyzers.** Omit them, or keep each configured analyzer's RDF subgraph at assembly
   the way paths are kept and emit it as written. The second is faithful; the analyzer's
   RDF can name a Java class and parameters such as a stopword file path, so it needs the
   same scrutiny as `idx:externalSource`.
2. **Index-level settings.** Whether `text:storeValues` and `text:maxFacetHits` belong in
   the output. `text:maxFacetHits` caps what `luc:facet` returns, so a client may want it.
3. **Default of `text:exposeConfig`.** Proposed `true`; `false` is the conservative choice.
