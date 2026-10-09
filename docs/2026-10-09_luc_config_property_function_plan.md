---
title: "luc:config — the index configuration as triples"
date: "2026-10-09"
status: "Built; replaces the /$/config endpoint, which is removed"
---

# luc:config — the Index Configuration as Triples

`luc:config` is a read-only property function that returns a SHACL index's configuration
as triples, through the dataset's ordinary query endpoint. A client uses it to find out
which fields exist and what each supports without a copy of the configuration file.

Reference documentation: [02-sparql-api.md](02-sparql-api.md#lucconfig) for the call and
[03-configuration.md](03-configuration.md#textexposeconfig) for the `text:exposeConfig`
flag. This note records why it is built the way it is.

## Why a property function, and why the endpoint went

The `/$/config` endpoint added by #154 (`jena-fuseki2/jena-fuseki-mod-config`) served the
configuration file and a JSON "effective" view. It had two problems:

- It was under `/$/`, so Fuseki's `LocalhostFilter` or Shiro gated it. A client that
  could reach `/{dataset}/query` and nothing else could not read it. The demo app reached
  it only through an allowlist in `demo/serve_app.py`, and the deployed demo opened it in
  `demo/deploy/shiro.ini`.
- Its JSON omitted `sh:path`, so the demo app parsed Turtle anyway.

A property function is answered by the query endpoint the client already uses, is scoped
to the dataset being queried, works in embedded ARQ with no Fuseki module, and its output
joins and `CONSTRUCT`s like any SPARQL result. With it in place the endpoint had no
remaining user, so the module, its `/$/config` Shiro and proxy allowances, and the CI
check for it were removed in the same change.

## What is returned

Fuseki parses the configuration file into a `Model` and hands each assembler a `Resource`
in it. The model is discarded once the server is built, so `ShaclTextIndexAssembler`
copies the index's subgraph while it still has it: every triple reachable from the index
resource, following objects, with three exceptions:

- `rdf:type` is not followed. Following it would pull in descriptions of the classes the
  index is typed with, which are not this index's configuration.
- `rdf:nil` is not entered. The assembler's model carries inferred types for it
  (`rdf:nil rdf:type rdf:List`, `rdfs:Resource`); a first version returned them, and
  `TestLucConfig.triplesMatchTheConfigurationExactly` caught it.
- `rdf:type rdf:List` and `rdf:type rdfs:Resource` are dropped. Fuseki assembles from
  `AssemblerHelp.fullModel`, whose `ModelExpansion.withSchema` adds both to every list
  cell. Against `demo/deploy/config.ttl` that was 42 triples the file does not contain,
  found by diffing a running server's `luc:config` output against the file; the unit
  tests had assembled without expansion. `typesInferredByTheAssemblerAreNotReturned`
  now assembles the way Fuseki does.

With those rules, every triple `luc:config` returned from the running demo server was in
`demo/deploy/config.ttl`.

The result is the configuration as written. Defaults are not added: a field with no
`idx:facetable` triple is returned without one.

### Considered and not built: a generated, filtered view

The plan first proposed generating the triples from `ShaclIndexMapping`, which would have
resolved defaults and left out directories, CSV locations, analyzers and shape IRIs that
disclose the configuration file's location. It also proposed copying `sh:path` subgraphs
separately, because `jena-shacl`'s `ShaclPaths.pathToRDF` writes a left-deep
`( ex:a ex:b ex:c )` back as `( ( ex:a ex:b ) ex:c )`.

That was dropped for the simpler design above. Copying the subgraph needs no generator, no
exclusion list to keep in step with new configuration properties, and returns paths
exactly as written by construction. The cost is that anything in the index's subgraph is
readable, file paths included. That is handled by making the feature opt-in rather than
by filtering.

## Opt-in

`text:exposeConfig` defaults to `false`, and is per index. An index that does not set it
answers `luc:config` with a query error naming the flag. The subgraph is copied only when
the flag is set. The flag is not part of the configuration fingerprint, so turning it on
or off does not make the startup check report a mismatch.

The demo configurations, `demo/test/config.ttl` and `demo/deploy/config.ttl`, set it: the
demo app reads its fields and facets with `luc:config`, and both files are public in the
repository.

## Querying the output

Each subject slot is a variable or a constant, and the property function evaluates
`graph.find(s, p, o)` over the copied graph, so a call behaves like one triple pattern.
A variable repeated across slots must bind the same node. Every call for one index in one
query reads the same graph, so blank nodes join across calls.

A SPARQL property path in the query runs against the dataset, not against `luc:config`'s
output, so a client cannot walk an RDF list with `rdf:rest*/rdf:first`. For a sequence or
alternative path, use one call per list cell, or `CONSTRUCT` the configuration and query
the result.

## Implementation

| Change | Where |
|---|---|
| Copy the index subgraph when `text:exposeConfig true` | `ShaclTextIndexAssembler.configOf`, held by `ShaclTextIndexLucene.getExposedConfig` |
| The property function | `ShaclConfigPF`; `IndexVocab.pfConfig`; registered in `TextQuery` |
| Index selection shared by `luc:query`, `luc:facet` and `luc:config` | `ShaclIndexSelection`, extracted from two identical copies |
| The flag | `TextVocab.pExposeConfig` |
| Removed | `jena-fuseki2/jena-fuseki-mod-config` and its entries in `jena-fuseki2/pom.xml` and `jena-fuseki-server/pom.xml` |
| Demo app | `fetchConfigText` in `demo/app-static/app.js` runs the `CONSTRUCT` above against `{dataset}/query`; the dataset name comes from `APP_CONFIG.dataset`, default `mining`, since the index's subgraph does not contain the service |

Tests: `TestLucConfig`, 20 cases, registered in `TS_Text`.
