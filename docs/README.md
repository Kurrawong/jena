# Jena Text SHACL Docs

This doc set covers the SHACL/entity-per-document search model in `jena-text`.

## Status

| Feature | Status | Notes |
|---|---|---|
| `luc:query` | Done | Fixed-position query API with explicit `indexSelector` and `fieldSpec` |
| `luc:match` | Done | Sole per-hit match-detail API |
| `luc:nestedMatch` | Done | Projects the `idx:nested` child records a filter selected, grouped by `?record` |
| `luc:facet` | Done | Fixed-position facet API with field IRIs and range facets |
| `luc:config` | Done | The index configuration as triples, through the query endpoint. Opt-in per index with `text:exposeConfig`. See [2026-10-09_luc_config_property_function_plan.md](2026-10-09_luc_config_property_function_plan.md) |
| `luc:stats` | Done | Entity, document, term, token and point counts and index size as triples, through the query endpoint. Not gated. See [02-sparql-api.md](02-sparql-api.md#lucstats) |
| Multi-index selection | Done | Query-time `indexSelector` plus `text:indexes` config |
| Field IRIs | Done | Public SPARQL uses field IRIs only |
| Sort pushdown | Done | Sort specs use field IRIs in the public API |
| Nested sort selector | Done | Order by a child value where a co-located discriminator matches |
| External content (CSV/TSV) | Done | `idx:externalSource` builds nested children from a tabular file; bulk build only |
| External delta endpoint | Designed | NDJSON deltas over HTTP, via opt-in retained rows. Not built — at reduced volume, load the values as RDF instead |
| Graph scoping model | Designed | Reserved synthetic field `urn:jena:lucene:field#sourceGraph`; implementation deferred |
| Source graph indexing | Designed | `idx:storeGraph` flag writing multi-valued `sourceGraph`. Not built — `?graph` was removed from `luc:query` instead, since a union-built document has no single source graph |
| Vector search | Designed | Entity-level dense vectors as a `VECTOR` field, filtered KNN plus facets. Not built — engine choice is Jlama. The loader's missing Vector API flags, a prerequisite, are fixed |
| SHACL class semantics | Designed | `sh:targetClass` and `sh:class` to match subclass instances, with `sh:hasValue` for an exact type. See [2026-10-09_shacl_class_semantics_and_jena_shacl_reuse.md](2026-10-09_shacl_class_semantics_and_jena_shacl_reuse.md) |
| Hit path and `luc:fields` | Built | `luc:query` reads the entity IRI only when bound, from a doc value, and computes matches only for `luc:match`; `luc:fields` returns field values per hit from doc values or stored fields. See [2026-10-12_luc_query_field_projection_design.md](2026-10-12_luc_query_field_projection_design.md) |
| `sh:node` occurrence filters | Built | Feed a field from nodes selected by a node shape, with occurrence constraints evaluated by jena-shacl. See [2026-10-12_shacl_node_occurrence_filters_design.md](2026-10-12_shacl_node_occurrence_filters_design.md) |
| Temporal operators | Built on `TemporalField` | CQL2's fifteen `t_` operators over a value's span (a date is its whole day); see [SPARQL API: temporal operators](02-sparql-api.md#temporal-operators). `idx:PeriodField`, for a start and end read from one period node, is designed, not built. See [2026-10-26_temporal_operators_plan.md](2026-10-26_temporal_operators_plan.md) |
| IRI sort key and cursor paging | Built | `urn:jena:lucene:index#entity` orders ties by entity IRI; `luc:query`'s eighth argument pages by cursor, and its sixth subject element, `?cursor`, gives each hit's cursor. See [SPARQL API: cursor paging](02-sparql-api.md#cursor-paging) |
| Highlight API | Deferred | Reserved for later, not active in the current `luc:query` signature |

## Core Rules

- `luc:query` object arguments are exactly `(indexSelector fieldSpec queryString cqlFilter sortSpec limit offset)`, or those and `after` for cursor paging.
- `luc:facet` object arguments are exactly `(indexSelector fieldSpec queryString facetFields cqlFilter maxValues minCount)`.
- `luc:query`'s subject list is 1 to 6 of `(?hit ?entity ?score ?totalHits ?rank ?cursor)`, the sixth only with `after`; there is no `?graph` slot.
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
    luc:query ("default" "default" "learning" "" "" 20 0) .
}
```

```sparql
PREFIX luc: <urn:jena:lucene:index#>

SELECT ?entity ?field ?value WHERE {
  (?hit ?entity ?score)
    luc:query ("default" '["urn:jena:lucene:field#title"]' "copper" "" "" 10 0) .
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
| [03-configuration.md](03-configuration.md) | Assembler and index configuration — every `idx:` term |
| [04-architecture.md](04-architecture.md) | Internal design and execution flow |
| [05-testing.md](05-testing.md) | Test coverage and commands |
| [06-design-decisions.md](06-design-decisions.md) | Why the model is shaped the way it is |
| [07-future-work.md](07-future-work.md) | Known gaps and candidates |
| [08-use-cases.md](08-use-cases.md) | Worked query scenarios |
| [09-spatial.md](09-spatial.md) | WKT indexing and spatial filters |
| [10-suggested-configuration.md](10-suggested-configuration.md) | Opinionated defaults per data kind — names, identifiers, dates, observations |

Dated files (`2026-*.md`) are design notes: a snapshot of the thinking at that date, not
reference. Where one disagrees with the numbered docs, the numbered docs win.

## Build And Test

```bash
mvn -pl jena-text test
```
