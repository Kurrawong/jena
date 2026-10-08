---
title: "SHACL class semantics, and reusing jena-shacl"
date: "2026-10-09"
status: "Planned; nothing implemented"
---

# SHACL Class Semantics, and Reusing jena-shacl

`sh:targetClass` and `sh:class` in a SHACL index configuration match `rdf:type` exactly. In
SHACL they also match instances of subclasses. This note plans to adopt the SHACL
meaning, to add `sh:hasValue` so a shape can still ask for an exact type, and to replace
the parts of `ShaclIndexAssembler` that duplicate `jena-shacl`.

Claims about current behaviour were checked against `227a8f29f1`.

## Current behaviour

SHACL defines a *SHACL instance* of a class `C` as a node with `rdf:type C`, or with
`rdf:type` of a class that is `rdfs:subClassOf*` `C`
([SHACL, "SHACL instance"](https://www.w3.org/TR/shacl/#dfn-shacl-instance)). Both
[`sh:targetClass`](https://www.w3.org/TR/shacl/#targetClass) and
[`sh:class`](https://www.w3.org/TR/shacl/#ClassConstraintComponent) are defined in terms of
SHACL instances.

Every place the index decides class membership uses `rdf:type` alone:

| Site | What it does |
|---|---|
| `ShaclBulkIndexer.discoverWorkItems` (`:213`, `:234`) | Finds entities with `find(ANY, rdf:type, targetClass)` in the default graph and across quads |
| `ShaclTextDocProducer.rebuildEntityDocuments` (`:228-236`) | Reads the entity's `rdf:type` values and looks each up in `mapping.getProfilesForClass` |
| `ShaclTextDocProducer.handleTypeChange` (`:98-113`) | Looks the changed type up in `getProfilesForClass` and `getOccurrencesRequiringClass` |
| `ShaclEntityBuilder.satisfiesConstraints` (`:146-153`) | Applies `sh:class` with `find(endpoint, rdf:type, requiredClass)` |
| `ShaclIndexMapping.getProfilesForClass`, `getOccurrencesRequiringClass` (`:825-830`) | Exact-key maps from class to profile and to occurrence |

Given `ex:OpenPitMine rdfs:subClassOf ex:Mine` and a shape with `sh:targetClass ex:Mine`,
an entity typed only `ex:OpenPitMine` is not indexed, and nothing reports that. The same
applies to a value reached through an occurrence with `sh:class ex:Mine`.

No document records this as intended. [03-configuration.md](03-configuration.md) describes
`sh:class` as "only index values whose reached node has this `rdf:type`", which states the
behaviour without saying it departs from SHACL. A reader who knows SHACL expects
subclasses to match.

## Target behaviour

`sh:targetClass C` selects every SHACL instance of `C`, and `sh:class C` keeps every value
that is a SHACL instance of `C`.

### Which graph supplies rdfs:subClassOf

SHACL takes `rdfs:subClassOf` from the data graph. The index has no single data graph, and
its two write paths read different ones:

- `ShaclTextDocProducer.allGraphsView()` reads the default graph plus every named graph.
- `ShaclBulkIndexer.processItems` builds an entity found in the default graph from the
  default graph, and one found in a named graph from `baseDataset.getUnionGraph()`, which
  is the union of the named graphs only (`:260-263`).

The subclass closure is computed over the default graph plus every named graph in both
paths, so an ontology loaded into its own named graph applies to instances in any graph,
and the bulk and live paths agree on class membership. Computing it over the graph an
entity was found in would make membership depend on where the ontology was loaded. The
difference in which graph supplies an entity's *field values* is unchanged by this plan.

### Reused helpers

All in `org.apache.jena.system.G` in `jena-arq`, which `ShaclIndexAssembler` already
imports. `jena-shacl`'s own `TargetOps.focusTargetClass` is a one-line call to
`G.allNodesOfTypeRDFS`.

| Helper | Use |
|---|---|
| `G.subClasses(graph, C)` | `?x rdfs:subClassOf* C`, including `C`. Bulk discovery: compute once per target class, then run the existing streaming `find(ANY, rdf:type, sub)` for each |
| `G.superClasses(graph, T)` | `T rdfs:subClassOf* ?x`, including `T`. Live updates: which profiles and occurrences a changed type affects |
| `G.isOfType(graph, node, C)` | `sh:class` in `satisfiesConstraints` |
| `G.allTypesOfNodeRDFS(graph, node)` | `rebuildEntityDocuments`: an entity's types with their superclasses |

`G.allNodesOfTypeRDFS` is not used for bulk discovery. It returns a `HashSet` of every
instance, which for a large class holds every entity IRI in memory and cannot stop at
`maxEntitiesPerProfile`. Iterating `G.subClasses` keeps discovery streaming.

### Live updates

| Change | Today | Needed |
|---|---|---|
| `rdf:type` added or removed | Rebuild if the type is exactly a target class or an `sh:class` value | Rebuild if any superclass of the type, including itself, is one |
| `rdfs:subClassOf` added or removed | Not watched | Rebuild every instance of the subclass and its own subclasses, for each profile or occurrence the change affects |

An `rdfs:subClassOf` change can affect many entities: adding `ex:OpenPitMine
rdfs:subClassOf ex:Mine` brings every open-pit mine into the `ex:Mine` profile. The
listener rebuilds them synchronously, the same way a type change is handled today, and
logs the count at `INFO`. The alternative, treating ontology edits as rebuild-only like
`idx:externalSource`, leaves the index stale with no indication, and it is not proposed.

### A stale document when one of two types is removed

`rebuildEntityDocuments` deletes an entity only when no profile matches
(`ShaclTextDocProducer.java:238-241`); otherwise it updates the documents for the profiles
that do match. `updateEntityForProfile` deletes only by the profile's own discriminator
(`ShaclTextIndexLucene.java:1918-1929`). Reading the code, an entity typed `ex:A` and
`ex:B` with a shape for each, which then loses `ex:B`, keeps its `ex:B` document. This has
not been confirmed by a test.

The change matters here because both the subclass semantics and the `sh:hasValue` filter
below make a profile stop matching an entity that still matches others. The fix is to
delete the documents of every profile that no longer matches, which needs a per-profile
delete in `ShaclTextIndexLucene`. The first test written should reproduce the case above
on the current code.

### Effect on existing indexes and the fingerprint

An index built under exact matching is missing subclass instances. The configuration is
unchanged, so `ShaclConfigFingerprint` produces the same hash and the startup check reports
`MATCH` against an index that needs rebuilding.

The fingerprint design ([2026-08-13_config_endpoint_and_fingerprint_plan.md](2026-08-13_config_endpoint_and_fingerprint_plan.md))
reserves `FINGERPRINT_VERSION` for serialisation changes. A version this build does not
recognise reports `UNKNOWN`, which logs at `INFO`. Two options:

1. Bump `FINGERPRINT_VERSION` to 2 and record the change in the upgrade notes of
   [03-configuration.md](03-configuration.md) as "rebuild the index after upgrading", as was
   done for the taxonomy-directory default. Existing indexes report `UNKNOWN`.
2. Add the class semantics to the serialised text without bumping the version, so every
   existing index reports `MISMATCH` and logs at `WARN`.

Option 2 states the actual condition. It does depart from the rule that serialisation
changes bump the version, so it needs a decision.

## Exact type with sh:hasValue

Where a shape is meant to match the exact type only, a property shape on the node shape
states it:

```turtle
<#MineShape>
    sh:targetClass ex:Mine ;
    sh:property [ sh:path rdf:type ; sh:hasValue ex:Mine ] ;
    sh:property [ idx:field field:name ; sh:path rdfs:label ] .
```

[`sh:hasValue`](https://www.w3.org/TR/shacl/#HasValueConstraintComponent) holds when at
least one value reached by `sh:path` equals the given node. An entity typed only
`ex:OpenPitMine` has no `rdf:type ex:Mine` and is excluded; one asserting both types is
kept. That reproduces today's matching for this shape.

The index reads a SHACL constraint as a filter rather than a validation, as it already
does for `sh:class`, `sh:nodeKind` and `sh:datatype` on occurrences. A focus node that
fails the constraint is not indexed under that shape, and nothing is reported.

### Scope

- A `sh:property` with `sh:hasValue` and **no** `idx:field` is a focus-node filter. Today a
  `sh:property` without `idx:field` raises `Field occurrence ... is missing idx:field`
  (`ShaclIndexAssembler.java:462-464`), so no configuration that loads now changes meaning.
- Any `sh:path` is allowed, not only `rdf:type`. `sh:path ex:status ; sh:hasValue
  ex:Published` indexes only published entities.
- `sh:hasValue` is the only constraint accepted on a filter in the first version. A filter
  carrying any other constraint is a configuration error, so unsupported SHACL is rejected
  rather than ignored.
- A filter does not combine with `idx:field`. A `sh:hasValue` on an occurrence, restricting
  which values a field receives, is a separate feature and is not part of this plan.

### Evaluation

| Path | Behaviour |
|---|---|
| Bulk discovery | After an entity is found for a profile, evaluate its filters; skip the entity for that profile if any fails |
| `rebuildEntityDocuments` | Evaluate filters per matched profile; delete the document of a profile whose filter now fails (needs the per-profile delete above) |
| Change listener | Each predicate in a filter path is added to the predicate lookup, so a change to it rebuilds the focus entity. `rdf:type` is already watched |

Filter values are read with `ShaclPaths.valueNodes(graph, focus, path)` from
`jena-shacl`, which evaluates a path with `PathEval` as `ShaclEntityBuilder` already does.

## Reusing jena-shacl

`jena-text` reads SHACL through the plain RDF API. `jena-shacl` is already on its compile
classpath, but only transitively: `jena-text/pom.xml` declares `jena-cmds`, which declares
`jena-shacl` at compile scope (`jena-cmds/pom.xml:74-78`). No class in `jena-text` imports
from it.

| Duplicate | Replacement |
|---|---|
| `sh:` IRIs built from strings, `ShaclIndexAssembler.java:49-63` | `org.apache.jena.shacl.vocabulary.SHACL` |
| `ShaclIndexAssembler.parseShaclPath` (`:811`) | `ShaclPaths.parsePath`, then reject what the index cannot handle (below) |
| Hand-written `sh:hasValue` value lookup | `ShaclPaths.valueNodes` |

`parseShaclPath` matches `ShaclPaths.path()` line for line, including the `isList` helper
and the left-deep sequence fold, except that it rejects `sh:zeroOrMorePath`,
`sh:oneOrMorePath` and `sh:zeroOrOnePath`. Those forms are not supported elsewhere in the
index: `collectPredicates` (`:860`), the join-path handling (`:687-721`) and the
precomputed `pathVariants` handle only link, inverse, sequence and alternative. The
replacement keeps that restriction by checking the parsed `Path` for `P_ZeroOrMore1`,
`P_OneOrMore1` and `P_ZeroOrOne` and raising the existing error.

`jena-text/pom.xml` should declare `jena-shacl` directly, with `${project.version}`, so the
dependency does not rest on `jena-cmds` continuing to carry it.

Not reused:

- The validation engine (`Shapes.parse`, `ShaclValidator`, the constraint classes in
  `engine/constraint`) produces validation reports. The index needs filters, and its
  mapping carries `idx:` settings the validator does not model.
- `ShaclPaths.pathToRDF` writes left-deep sequences as nested lists, and writes
  `sh:alternativePath` for a `P_ReverseLink`. The `luc:config` plan
  ([2026-10-09_luc_config_property_function_plan.md](2026-10-09_luc_config_property_function_plan.md))
  copies paths from the configuration instead.

### Documents to change

[06-design-decisions.md](06-design-decisions.md) records "Parse config using standard Jena
RDF API — no jena-shacl dependency", and
[04-architecture.md](04-architecture.md) repeats "No jena-shacl dependency" in the
`ShaclIndexAssembler` row. The reason given, "we're reading config, not running
validation", still holds for the validator. Amend both to say the index reuses
`jena-shacl`'s vocabulary and path handling and still does not validate.

Also update `sh:targetClass` and `sh:class` in [03-configuration.md](03-configuration.md),
line 7 of [01-user-guide.md](01-user-guide.md), and line 21 of
[04-architecture.md](04-architecture.md), which all describe exact `rdf:type` matching.

## Sequencing

Three pull requests, each usable alone:

1. **Reuse, no behaviour change.** Vocabulary, path parser, direct dependency, design-decision
   wording. The existing suite passing unchanged is the test.
2. **Per-profile delete.** The failing test for the two-type case first, then the fix.
3. **Subclass semantics and `sh:hasValue` filters together**, so a deployment that needs
   exact matching has `sh:hasValue` in the same release that changes the default.

## Tests for pull request 3

Written first and seen to fail. New classes are JUnit 5 and must be added to `TS_Text`.

- Bulk: an instance of a subclass, and of a sub-subclass, is indexed under the
  superclass's shape.
- Bulk: the ontology in a named graph and the instances in the default graph, and the
  reverse.
- Live: adding `rdf:type ex:OpenPitMine` to an entity indexes it under the `ex:Mine` shape.
- Live: adding `ex:OpenPitMine rdfs:subClassOf ex:Mine` indexes existing open-pit mines;
  removing it deletes them.
- `sh:class ex:Mine` on an occurrence keeps a value typed `ex:OpenPitMine`.
- An entity matching two shapes, one via a subclass, gets one document per shape.
- `sh:hasValue` on `rdf:type`: an entity typed only with the subclass is excluded; one
  with both types is kept.
- `sh:hasValue` on another path: removing the value deletes the document for that shape and
  leaves the entity's documents under other shapes.
- A filter carrying `sh:minCount`, or carrying `idx:field`, is rejected at configuration
  time.
- The fingerprint reports whichever status the chosen option above produces for an index
  built before the change.

## Out of scope

- SHACL implicit class targets, where a shape is also an `rdfs:Class`.
- `sh:targetNode`, `sh:targetSubjectsOf`, `sh:targetObjectsOf`, and SPARQL-based targets.
- RDFS reasoning beyond `rdfs:subClassOf*`, such as `rdfs:domain` and `rdfs:range`, which
  SHACL does not apply either.
