---
title: "Correlated hierarchical facets over root fields"
date: "2026-08-03"
status: "Resolved — idx:self binds the focus node, making the nested correlated hierarchy reachable"
---

# Correlated Hierarchical Facets Over Root Fields

## Problem

A shape-level `idx:facetHierarchy` builds its facet paths by taking the **cartesian
product** of each level's values. When every level is single-valued that is correct. When
two levels are multi-valued and their values are *pairwise* related, it invents paths that
are not in the data, and the drill-down counts are wrong.

There is a correlated implementation — `idx:facetHierarchy` inside an `idx:nested` block —
but it is only reachable when the correlating node has each level hanging off it as a
sub-property. It cannot express a hierarchy where one of the levels **is** the child node
itself. That is the case below, so neither construct currently fits.

## Use case

GSWA search facets on the datatype of a document (`dataType`), and wants to add a broader
grouping above it (`dataTypeGrouping`) so users can select several datatypes at once. The
UI should show:

```text
Holes (1,234)
  downhole-assays (812)
  borehole (422)
```

The data:

```turtle
# instance data — an entity may reference several display tables
<borehole/123>  gswa:hasDisplayTable  <display/borehole> , <display/downhole-assays> .

# vocabulary — each display table belongs to one or more groupings
<display/borehole>          gswa:hasGrouping  <datatype/Holes> .
<display/downhole-assays>   gswa:hasGrouping  <datatype/Holes> .
<display/geochem-results>   gswa:hasGrouping  <datatype/Geochemistry> .
```

The current shape indexes the display table IRI itself as `dataType`:

```turtle
sh:property [ idx:field field:dataType          ; sh:path gswa:hasDisplayTable ] ;
sh:property [ idx:field field:dataTypeGrouping  ; sh:path ( gswa:hasDisplayTable gswa:hasGrouping ) ] ;
```

Both fields are `idx:multiValued true`.

## Why the root hierarchy is wrong here

`addDirectHierarchyFacetFields`
(`jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java:1040`)
reads each level's values independently from the entity root:

```java
for (ShaclIndexMapping.FieldDef levelField : hierarchy.getLevels()) {
    levelValues.add(asHierarchyFacetValues(entity.get(levelField.getFieldName()), ...));
}
addFacetPaths(doc, hierarchy.getDimensionName(), levelValues, 0, new ArrayList<>());
```

and `addFacetPaths` (`:1117`) recurses over every value at every level, emitting one
`FacetField` per combination.

So for an entity carrying two display tables in two different groupings:

```text
dataType          = [ display/borehole, display/geochem-results ]
dataTypeGrouping  = [ datatype/Holes,   datatype/Geochemistry   ]
```

it emits four paths, two of them fabricated:

```text
Holes / borehole                  ✓ in the data
Holes / geochem-results           ✗ invented
Geochemistry / borehole           ✗ invented
Geochemistry / geochem-results    ✓ in the data
```

Drilling into `Holes` then offers `geochem-results` as a child, and the child counts sum to
more than the parent count. This is the "no repeated intermediate node" limitation recorded
in [`2026-04-07_nested_hierarchical_faceting_design.md`](2026-04-07_nested_hierarchical_faceting_design.md);
the note assumed such cases would move to `idx:nested`, which is where this one gets stuck.

## Why the nested block does not reach it

The natural correlating node is the display table, so the block would be:

```turtle
idx:nested [
    idx:joinPath gswa:hasDisplayTable ;
    idx:property [ idx:field field:dataType         ; sh:path ??? ] ;
    idx:property [ idx:field field:dataTypeGrouping ; sh:path gswa:hasGrouping ] ;
    idx:facetHierarchy ( field:dataTypeGrouping field:dataType ) ;
] ;
```

`addNestedHierarchyFacetFields` (`:1050`) would then do the right thing — it builds paths
per child record, so no cartesian product across children.

The blocker is the `???`. `dataType`'s value **is** the child node, `<display/borehole>`.
Every `idx:property` inside a nested block is evaluated as a path *from* the child, and
there is no way to express "the child node itself" — no `idx:self`, no empty path, no
`sh:this`. The existing nested patterns never needed one: `schema:identifier` and
`prov:qualifiedAttribution` children are blank-node bundles where every indexed value is
reached by stepping off the child.

## What is actually needed

A way to declare a hierarchy whose levels stay pairwise correlated, where one level is the
correlating node itself. Some directions, none evaluated:

1. **Identity path.** Let an `idx:property` inside a nested block bind the child node
   itself — `idx:self true` on the occurrence, or an empty `sh:path` list. Smallest change;
   makes the existing correlated code path reachable. Worth checking what else an identity
   path would enable, and whether it is coherent for a non-facet field.
2. **Correlated root hierarchy.** Teach `addDirectHierarchyFacetFields` to walk the paths
   rather than the flattened field values, so a hierarchy over `( A B )` where B's path
   extends A's stays correlated without a nested block. Larger change, and it needs a rule
   for when two root occurrences count as correlated.
3. **Nothing** — declare this out of scope and require callers to materialise a correlated
   child structure in the data. Cheapest for us, but pushes a denormalisation onto every
   dataset that has a taxonomy attached to a term rather than to the entity.

## Notes for whoever picks this up

- Both a flat facet on the field and the hierarchy dimension are addressable separately and
  neither shadows the other (`docs/03-configuration.md:472`), so a fix must not change how
  `dataType` behaves as a flat facet today.
- The failure is silent. Nothing warns at index time that a hierarchy's levels are
  multi-valued and uncorrelated; the counts are simply wrong. A config-time warning may be
  worth having regardless of which direction is chosen.
- Hierarchical facet ordinals live in the taxonomy directory. If it is left unset the
  counts vanish entirely when indexing and querying are separate processes
  (`docs/03-configuration.md:64`) — worth confirming in any test that exercises this.
- This shape recurs: commodity groupings, report-type hierarchies and sample-of chains are
  all taxonomies attached to a term rather than to the entity, and all are flat facets
  today.

## Resolution (2026-08-03) — option 1, the identity path

`idx:self true` on an occurrence binds the **focus node** itself instead of a path from it:
the child node inside an `idx:nested` block, the entity at root scope. That is the whole
change. It is the one primitive the model was missing, and with it the existing correlated
nested hierarchy becomes reachable for this shape:

```turtle
idx:nested [
    idx:joinPath ex:hasDisplayTable ;
    idx:property [ idx:field field:displayTable ; idx:self true ] ;
    idx:property [ idx:field field:grouping     ; sh:path ex:hasGrouping ] ;
    idx:facetHierarchy ( field:grouping field:displayTable ) ;
] ;
```

### Why not option 2 (correlate the root hierarchy)

Option 2 was built first, in the form of a "prefix chain": correlate a shape-level
hierarchy when each level's path is found to extend the level below it. It was **discarded
before merge**, for reasons worth keeping:

- **It is a second mechanism for one semantic.** `idx:nested` already models "these values
  belong together". Deriving the same thing a second way, from the shape of paths, means
  two implementations of correlation that must agree forever.
- **It is implicit.** Nothing in the config says the hierarchy is correlated. Two
  configurations that look alike behave differently, and the difference is only visible by
  reasoning about path prefixes. Correctness became a property of how the paths happened
  to be written.
- **Its fallbacks are silent.** Fan-in, alternative paths, or a level with two occurrences
  all drop back to the cartesian product — the wrong answer — with only a log line.

The scope rule that made option 1 look expensive turned out to be the right rule, not an
obstacle. A field's scope determines its behaviour; wanting entity-level flat faceting and
child-correlated hierarchy from one field IRI is wanting two behaviours from one
declaration. Two behaviours, two fields, two names — which is honest, and states in the
config what is actually being asked for.

### What was built

| Piece | Change |
|---|---|
| `IndexVocab` | `idx:self` |
| `ShaclIndexMapping.FieldOccurrence` | `FieldOccurrence.self(...)` factory; null path, one zero-step path variant, no predicates; `isSelf()` |
| `ShaclIndexAssembler` | Parses and validates: exactly one of `sh:path` / `idx:self`, `idx:self false` rejected, KEYWORD/TEXT only, not on `idx:column` |
| `ShaclEntityBuilder` | Self occurrences take the focus node, with the occurrence's constraints applied to it |
| `ShaclTextDocProducer` | Reverse-walking an identity path from a node arrives at that node |

Nothing else moved. The nested hierarchy builder, the facet code and the block-join query
path were already correct — they were simply unreachable for a hierarchy whose level is the
child node.

Semantics worth knowing:

- **Constraints filter the focus node.** `sh:nodeKind sh:IRI` on a self occurrence means
  "index this child only when it is an IRI".
- **Blank nodes yield nothing.** Their labels are not stable across a reload, so there is
  nothing meaningful to index; the field is absent from that document.
- **Field type is restricted to KEYWORD/TEXT.** A focus node is a resource, so the numeric
  and temporal types have nothing to convert — rejected at config time rather than
  producing empty fields.
- **No new change tracking.** A self occurrence contributes no predicates, which is
  correct: its value cannot change unless the entity or the join reaching it changes, and
  both are already tracked. Pinned by two tests (a vocabulary edit, and a child added to an
  entity).
- **It is not facet machinery.** A self-bound field is an ordinary child-scope field: it
  filters same-child with its siblings like any other value. Pinned by a test.

### Tests

`TestSelfBoundOccurrences` (6) — the correlated hierarchy: top-level counts, drill-down
staying correlated per child, children not out-counting the parent, same-child correlation
with a sibling field, vocabulary-edit reindex, child-added reindex.
`TestSelfOccurrenceAssembler` (8) — config surface: parses in a nested block and at root,
and rejects self+path, neither, `idx:self false`, a numeric field, and `idx:column`.

Written red first: with the builder's self branch removed, all six behaviour tests fail on
the null path.

### Still open

- **Deeper taxonomies.** A hierarchy walked transitively (`skos:broader+`, unknown depth)
  is still not expressible. `idx:self` does not help there; that needs a path-walking
  hierarchy declaration, and should wait for a real case.

