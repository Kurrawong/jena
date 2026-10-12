---
title: "sh:node occurrence filters, evaluated by jena-shacl"
date: "2026-10-12"
status: "Designed; nothing implemented"
---

# sh:node Occurrence Filters, Evaluated by jena-shacl

This design lets a field be fed from nodes selected by a SHACL node shape, and moves every
constraint on a field occurrence onto jena-shacl's constraint evaluation. The motivating
case is an identifier modelled as a blank node:

```turtle
ex:site1 schema:identifier [ schema:propertyID gswa:CollarId ; schema:value "2297211" ] ,
                           [ schema:propertyID gswa:HoleId   ; schema:value "69422ENEA_CP84042" ] .
```

Indexing the CollarId alone as a root field needs "the `schema:value` of the identifier
whose `schema:propertyID` is `gswa:CollarId`". A SHACL path only navigates, so `sh:path`
cannot say it. SHACL says it with `sh:node`:

```turtle
sh:property [
    sh:path schema:identifier ;
    sh:node [
        sh:property [ sh:path schema:propertyID ; sh:hasValue gswa:CollarId ] ;
        sh:property [ sh:path schema:value ; idx:field field:collarId ] ;
    ] ;
] ;
```

Only standard SHACL terms and the existing `idx:field` are used.

Claims about current behaviour were checked against `f01fa1eb3c`.

## What the index supports today

`ShaclIndexAssembler.parseOccurrence` accepts three constraints on a field occurrence:
`sh:class`, `sh:nodeKind` and `sh:datatype`. `ShaclEntityBuilder.satisfiesConstraints`
tests them by hand against the node at the end of the path, and `sh:class` matches
`rdf:type` exactly. Nothing tests a node partway along a path, and `sh:node` and
`sh:hasValue` are not read. A `sh:property` without `idx:field` raises "Field occurrence
… is missing idx:field".

The identifier shape is indexed today as an `idx:nested` block, one child document per
identifier. That supports same-child filtering and `luc:nestedMatch`, but not a root
field, so the identifier cannot be sorted on, read as a doc value, or returned by
`luc:fields`
([2026-10-12_luc_query_field_projection_design.md](2026-10-12_luc_query_field_projection_design.md)).

## Semantics

### A step with `sh:node`

A `sh:property` with `sh:path` and `sh:node`, and no `idx:field`, is a **step**. From
the focus node it follows `sh:path`, and keeps each reached node that conforms to the
`sh:node` shape's **filter**. From each kept node it evaluates the `sh:node` shape's field
occurrences, as if the kept node were the focus node.

Inside the `sh:node` shape:

| Member | Role |
|---|---|
| `sh:property` with `idx:field` | A field occurrence evaluated from the kept node. Its values go into the enclosing document: the entity's at root scope, the child's inside `idx:nested` |
| `sh:property` without `idx:field` | Part of the filter, with standard SHACL meaning: `sh:hasValue` holds when at least one value equals it, `sh:minCount` counts values, and so on |
| A constraint directly on the shape (`sh:class`, `sh:in`, `sh:or`, …) | Part of the filter, applied to the kept node itself |
| A `sh:property` with `sh:path` and `sh:node` and no `idx:field` | A further step, evaluated from the kept node |

A field reached through a step is an ordinary field. In the example, `field:collarId` is a
single-valued root field and can be `idx:sortable`, `idx:facetable` and returned by
`luc:fields`. An entity with two CollarId identifiers gives two values; the field's
`idx:multiValued` setting applies as it does to any path.

### Constraints on a field occurrence

The constraints on a field occurrence filter the values it reaches, one value at a time.
They are evaluated as a node shape applied to each value node, by jena-shacl. Accepted
constraints are those SHACL defines per value node:

`sh:class`, `sh:datatype`, `sh:nodeKind`, `sh:in`, `sh:pattern` (with `sh:flags`),
`sh:minLength`, `sh:maxLength`, `sh:languageIn`, `sh:minInclusive`, `sh:maxInclusive`,
`sh:minExclusive`, `sh:maxExclusive`, `sh:node`, `sh:and`, `sh:or`, `sh:not`, `sh:xone`.

The others are rejected on a field occurrence at configuration time, with an error naming
the constraint: `sh:hasValue`, `sh:minCount`, `sh:maxCount`, `sh:uniqueLang`,
`sh:qualifiedValueShape` and the pair constraints (`sh:equals`, `sh:disjoint`,
`sh:lessThan`, `sh:lessThanOrEquals`) describe the set of values, not one value, so they
have no per-value meaning. `sh:hasValue` on a field occurrence would read as "index only
this value", which is `sh:in` with one member. Inside a filter they are all accepted with
their SHACL meaning.

`sh:sparql` and SPARQL-based constraint components are rejected everywhere: they run
arbitrary queries during indexing.

### Change of behaviour: `sh:class`

jena-shacl's `ClassConstraint` checks types through `G.allTypesOfNodeRDFS`, so an
occurrence's `sh:class ex:Person` now admits an instance of a subclass of `ex:Person`.
That is SHACL's definition, and the one
[2026-10-09_shacl_class_semantics_and_jena_shacl_reuse.md](2026-10-09_shacl_class_semantics_and_jena_shacl_reuse.md)
adopts for `sh:class`. `sh:targetClass` keeps exact matching until that plan's change
lands. SHACL mode carries no backward-compatibility requirement.

## Using jena-shacl

`jena-text/pom.xml` declares `jena-shacl` directly, at `${project.version}`. Today it
arrives only through `jena-cmds`.

| Need | jena-shacl |
|---|---|
| `sh:` vocabulary | `org.apache.jena.shacl.vocabulary.SHACL`, replacing the IRIs built from strings in `ShaclIndexAssembler` |
| Path parsing | `ShaclPaths.parsePath`, replacing `parseShaclPath`, then rejecting the `*`, `+` and `?` path forms the index does not support, as today |
| Path evaluation | `ShaclPaths.valueNodes` |
| Shape parsing | `Shapes.parse` over a graph holding the filter's triples |
| Conformance of one node | `VLib.validateShape(vCxt, data, shape, node)` on a fresh `ValidationContext`, conforming when the context records no violation |

The assembler builds a jena-shacl `Shape` for each filter and each occurrence's
value constraints once, at configuration time. For a filter it copies the `sh:node`
shape's triples, minus the `sh:property` members that carry `idx:field`, into a small
graph and parses it, so a field occurrence's own value constraints never decide whether
its node is kept. For an occurrence's value constraints it copies those constraint
triples onto a fresh node shape.

`VLib` and `ValidationContext` are public classes but not jena-shacl's documented API.
Upstream changes to them arrive with each upstream sync, and this design's tests catch a
break. The index never produces validation reports; it asks only whether a node conforms.

## Change tracking

`ShaclTextDocProducer` rebuilds an entity when a changed triple's predicate is relevant
and the entity can be found by walking back from the changed node. Two additions:

- **Filter predicates.** Every predicate in a filter's paths and constraints (for
  `sh:class`, `rdf:type`) is relevant. For change resolution, each filter property is
  registered like an occurrence whose path is the step's path followed by the filter
  property's path. A change to a blank node's `schema:propertyID` walks back through
  `schema:propertyID`'s subject, then `schema:identifier`, to the site, using the existing
  `findScopeRootsForPredicateChange`.
- **Steps.** A field reached through a step is registered with the joined path, step path
  then field path, so a change to `schema:value` resolves the same way.

A change to `rdfs:subClassOf` is not tracked: it can alter which nodes satisfy `sh:class`
anywhere in the data. Until the class-semantics plan adds that, such a change needs a
reindex, which [03-configuration.md](03-configuration.md) states beside `sh:class`.

## Fingerprint and `luc:config`

The configuration fingerprint includes steps and filters, so changing a filter is
reported as a mismatch at startup like any other field change. `luc:config` copies the
index's configuration subgraph by following objects, so steps and filters appear in its
output as written, with no change to `luc:config`.

## Indexing cost

A hand-coded check is a graph lookup. Validating a node with jena-shacl builds a
validation context and evaluates each constraint, which may be slower. The synthetic
benchmark in the field-projection design measures bulk-indexing throughput for an index
using steps and value constraints, before and after.

## Implementation

| Change | Where |
|---|---|
| Direct `jena-shacl` dependency | `jena-text/pom.xml` |
| `SHACL` vocabulary, `ShaclPaths.parsePath` | `ShaclIndexAssembler` |
| Parse steps and filters, build jena-shacl shapes, reject unsupported constraints | `ShaclIndexAssembler`; a step model in `ShaclIndexMapping` |
| Value constraints through jena-shacl, replacing `satisfiesConstraints` | `ShaclEntityBuilder`; `FieldOccurrence` holds the shape |
| Steps evaluated from each kept node | `ShaclEntityBuilder`, and the bulk indexer if it evaluates occurrences separately |
| Change tracking for filters and steps | `ShaclIndexMapping` predicate and occurrence lookups, `ShaclTextDocProducer` |
| Fingerprint | The fingerprint serialisation |
| Docs | [03-configuration.md](03-configuration.md): steps, filters, accepted constraints, `sh:class` semantics. [04-architecture.md](04-architecture.md) and [06-design-decisions.md](06-design-decisions.md): the jena-shacl dependency |

## Tests

Written first and seen to fail. New classes are JUnit 5 and registered in `TS_Text`.

- The identifier example indexes `field:collarId` with the CollarId only, as a root
  field, sortable, for sites with and without a CollarId.
- Two identifiers of the kept kind on one entity give two values, and the
  `idx:multiValued false` warning when the field is single-valued.
- A filter with `sh:in` over two kinds, with `sh:minCount` on an inner property, and with
  a constraint on the shape itself.
- A step inside a step.
- A step inside an `idx:nested` block feeds the child document.
- Each value constraint in the accepted list filters values; each rejected one raises at
  configuration time naming the constraint; `sh:sparql` raises.
- A field occurrence's own value constraint inside the `sh:node` shape does not decide
  whether the node is kept.
- `sh:class` admits a subclass instance (behaviour change), and still rejects an unrelated
  type.
- Change listener: changing a blank node's `schema:propertyID` from `gswa:CollarId` to
  another kind removes the value; changing it back restores it; changing `schema:value`
  updates it; deleting the blank node's triples removes it.
- The existing `sh:class`, `sh:nodeKind` and `sh:datatype` tests pass unchanged apart from
  the subclass case.
- The fingerprint changes when a filter changes.
- The bulk indexer and the change listener produce the same document for the same data.
