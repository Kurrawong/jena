# Jena Text SHACL Docs

This doc set covers the SHACL/entity-per-document search model in `jena-text`.

## Status

| Feature | Status | Notes |
|---|---|---|
| `luc:query` | Done | Fixed-position query API with explicit `indexSelector` and `fieldSpec` |
| `luc:match` | Done | Sole per-hit match-detail API |
| `luc:facet` | Done | Fixed-position facet API with field IRIs and range facets |
| Multi-index selection | Done | Query-time `indexSelector` plus `text:indexes` config |
| Field IRIs | Done | Public SPARQL uses field IRIs only |
| Sort pushdown | Done | Sort specs use field IRIs in the public API |
| Graph scoping model | Designed | Reserved synthetic field `urn:jena:lucene:field#sourceGraph`; implementation deferred |
| Highlight API | Deferred | Reserved for later, not active in the current `luc:query` signature |

## Core Rules

- `luc:query` object arguments are exactly `(indexSelector fieldSpec queryString cqlFilter sortSpec limit)`.
- `luc:facet` object arguments are exactly `(indexSelector fieldSpec queryString facetFields cqlFilter maxValues minCount)`.
- `luc:query` does not expose `?match`.
- `luc:match` is the only match-detail API.
- Use `""` placeholders for unused `cqlFilter` and `sortSpec`.
- Query-time field references are always field IRIs.
- Graph filtering is intended to use a reserved synthetic field, not a dedicated result slot.
- `luc:facet` always uses the 5-slot subject form; flat facet rows leave `?low` and `?high` unbound.

## Example

```sparql
PREFIX luc: <urn:jena:lucene:index#>

SELECT ?entity ?score WHERE {
  (?hit ?entity ?score)
    luc:query ("default" "default" "learning" "" "" 20) .
}
```

```sparql
PREFIX luc: <urn:jena:lucene:index#>

SELECT ?entity ?field ?value WHERE {
  (?hit ?entity ?score)
    luc:query ("default" '["urn:jena:lucene:field#title"]' "copper" "" "" 10) .
  (?hit ?field ?value) luc:match () .
}
```

```sparql
PREFIX luc: <urn:jena:lucene:index#>

SELECT ?field ?value ?low ?high ?count WHERE {
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
}
```

## Documents

| Document | Purpose |
|---|---|
| [01-user-guide.md](01-user-guide.md) | Practical setup and query usage |
| [02-sparql-api.md](02-sparql-api.md) | Full SHACL SPARQL signatures and examples |
| [03-configuration.md](03-configuration.md) | Assembler and index configuration |
| [04-architecture.md](04-architecture.md) | Internal design and execution flow |
| [05-testing.md](05-testing.md) | Test coverage and commands |

## Build And Test

```bash
mvn -pl jena-text test
```
