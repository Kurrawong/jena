# 2026-04-08 Graph Filtering Target Model

Status: design only

Refined by [2026-07-31_source_graph_indexing_design.md](2026-07-31_source_graph_indexing_design.md),
which settles the index-time flag, the multi-valued semantics, and where collection has to
happen. The `?graph` slot this note rules out was removed from `luc:query` on 2026-07-31.

## Decision

Do not add graph-specific slots to the SHACL property-function signatures.

Graph scoping should be modeled as a normal doc-level field filter using a reserved synthetic field IRI:

```text
urn:jena:lucene:field#sourceGraph
```

## Rationale

Trying to expose `?graph` as a dedicated binding causes awkward semantics:

- on `luc:query`, it is ambiguous whether the graph is per-hit, per-entity, or per-matched-value
- on `luc:match`, it implies per-field-value provenance, which is more expensive and not needed for the immediate use case
- adding dedicated graph bindings complicates otherwise clean fixed-arity signatures

The graph-filtering use case is really doc-level:

- users want to constrain search to entities that have indexed content from graph X
- that fits naturally as a normal CQL filter over a synthetic keyword field

## Intended Semantics

`sourceGraph` is a multi-valued KEYWORD field.

At index time:

1. build the entity document as normal
2. collect every graph touched while resolving indexed values for that entity
3. add each discovered graph IRI to `sourceGraph`

At query time:

- filtering to graph X means “this entity has at least one indexed value sourced from graph X”

Example target filter:

```json
{"op":"=","args":[{"property":"urn:jena:lucene:field#sourceGraph"},"http://example.org/graph/A"]}
```

## Relationship To Index-Time Graph Restriction

This model does not replace strict graph partitioning at index time.

Recommended split:

- index-time graph restriction:
  - used when users want true graph-isolated indexing
  - only selected graphs contribute to the index
- query-time `sourceGraph` filtering:
  - used for flexible post-filtering within a broader index

If indexing is restricted to one graph, `sourceGraph` naturally reflects only that graph anyway.

## Non-Goals For This Pass

- no dedicated `?graph` result slot on `luc:query`
- no dedicated `?graph` result slot on `luc:match`
- no per-match graph provenance
- no immediate implementation changes required in this design-only pass

## Future Implementation Notes

When implemented, this likely needs:

- reserved-field handling in the SHACL index mapping/runtime
- indexing support to collect source graphs while traversing fields
- documentation examples showing `sourceGraph` filters in `luc:query` and `luc:facet`
