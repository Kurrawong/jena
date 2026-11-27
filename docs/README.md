# Jena Text SHACL Documentation

These pages describe the SHACL entity-per-document index in `jena-text`: the
`text:TextIndexShacl` configuration and the `luc:` SPARQL property functions.

## Pages

| Page | Answers |
|---|---|
| [01-user-guide.md](01-user-guide.md) | How do I configure an index, run it in Fuseki and run my first queries? |
| [02-sparql-api.md](02-sparql-api.md) | What are the arguments and bindings of `luc:query`, `luc:match`, `luc:nestedMatch`, `luc:fields`, `luc:facet`, `luc:config` and `luc:stats`, and the CQL2-JSON filters? |
| [03-configuration.md](03-configuration.md) | What does each `text:` and `idx:` configuration term do? |
| [04-architecture.md](04-architecture.md) | How are documents built and queries executed? |
| [05-testing.md](05-testing.md) | Which tests cover which behaviour, and how do I run them? |
| [06-design-decisions.md](06-design-decisions.md) | Why is the model shaped as it is? |
| [07-future-work.md](07-future-work.md) | What are the known gaps and planned extensions? |
| [08-use-cases.md](08-use-cases.md) | Which configuration or query do I use for a given search task? |
| [09-spatial.md](09-spatial.md) | How do I index geometry and filter by location, and where do results differ from GeoSPARQL? |
| [10-suggested-configuration.md](10-suggested-configuration.md) | Which field type and flags do I use for each kind of data? |

## Design notes

Files named `2026-*.md` are design notes. Each one records the design at its date. Where a
design note and a numbered page disagree, the numbered page describes the current
behaviour.

Design notes for built features:

| Feature | Design |
|---|---|
| `luc:facet` sideways counts | [2026-11-13_luc_facet_sideways_counts_design.md](2026-11-13_luc_facet_sideways_counts_design.md) |
| `luc:config` | [2026-10-09_luc_config_property_function_plan.md](2026-10-09_luc_config_property_function_plan.md) |
| `luc:fields` and the values `luc:query` reads per hit | [2026-10-12_luc_query_field_projection_design.md](2026-10-12_luc_query_field_projection_design.md) |
| `sh:node` occurrence filters | [2026-10-12_shacl_node_occurrence_filters_design.md](2026-10-12_shacl_node_occurrence_filters_design.md) |

## Designs for features that are not built

| Feature | Design |
|---|---|
| Graph filters with the reserved field `urn:jena:lucene:field#sourceGraph` | [2026-04-08-graph-filtering-target-model.md](2026-04-08-graph-filtering-target-model.md), [2026-07-31_source_graph_indexing_design.md](2026-07-31_source_graph_indexing_design.md) |
| NDJSON deltas for `idx:externalSource` over HTTP | [2026-07-30_external_delta_endpoint_design.md](2026-07-30_external_delta_endpoint_design.md) |
| Dense vector fields with filtered KNN search | [2026-08-05_vector_search_and_embeddings_design.md](2026-08-05_vector_search_and_embeddings_design.md) |
| `sh:targetClass` and `sh:class` matching subclass instances | [2026-10-09_shacl_class_semantics_and_jena_shacl_reuse.md](2026-10-09_shacl_class_semantics_and_jena_shacl_reuse.md) |
| Highlighted snippets in `luc:query` | None |
