---
title: "Correlated hierarchical facets over root fields"
date: "2026-08-03"
status: "Resolved — root hierarchies correlate when their levels' paths form a prefix chain"
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

## Resolution (2026-08-03) — option 2, correlated root hierarchy

Direction 2 was taken, in the form of a **prefix chain**: when each hierarchy level's
occurrence path strictly extends the path of the level below it, the levels are correlated
by walking those paths through the graph instead of cross-producting the flattened field
values. The config in the use case above is already such a chain, so it needs **no new
vocabulary and no config change** beyond declaring the hierarchy:

```turtle
sh:property [ idx:field field:dataType         ; sh:path gswa:hasDisplayTable ] ;
sh:property [ idx:field field:dataTypeGrouping ; sh:path ( gswa:hasDisplayTable gswa:hasGrouping ) ] ;
idx:facetHierarchy ( field:dataTypeGrouping field:dataType ) ;
```

### Why not the identity path (option 1)

`idx:self` was the first recommendation and was dropped once the flat-facet constraint was
tested rather than assumed. It fails on its own terms:

- Making it reachable requires the hierarchy leaf to live in the `idx:nested` block, and
  "one field IRI belongs to one scope" then forces `field:dataType` to be either a root
  field or the nested leaf — not both. Keeping the working flat facet therefore needs a
  **twin field** carrying identical values in the child scope.
- Moving `dataType` wholly into the nested block instead does not relocate the flat facet,
  it removes it. Facet collection filters to parent docs (`filterToParents`), and nested
  values live only on child docs, so the flat `dataType` facet would return nothing under
  an active query — precisely the case a search UI is always in. Open (no-query) requests
  count child docs instead. The client's `dataType` facet is in daily use, so this is not
  a viable break.
- It also materialises one child document per display table purely to carry a facet.

The prefix chain has none of these costs: both fields stay root-scoped, both flat facets
are untouched, no child documents are created, and there is no twin.

### What was built

| Piece | Change |
|---|---|
| `ShaclIndexMapping.CorrelatedHierarchy` | Derives the plan: one root occurrence per level, single path variant, each level's steps strictly extending the next's. Holds the innermost path and the per-level ascent paths |
| `ShaclIndexMapping.IndexProfile` | Derives plans once at config time; warns when a hierarchy stays cartesian **and** has more than one multi-valued level |
| `ShaclEntityBuilder` | Walks the chain from the innermost level outwards, emitting one facet path per real edge chain |
| `Entity` | Carries the walked paths — the correlation is a property of the graph and cannot be recovered from the flattened values |
| `ShaclTextIndexLucene.addDirectHierarchyFacetFields` | Emits the walked paths for a correlated dimension; unchanged cartesian behaviour otherwise |

Notes on the semantics chosen:

- **No partial paths.** A display table with no grouping contributes nothing to the
  dimension, rather than a one-component path, since an entity must not be counted under a
  parent it does not have. It remains a value of the flat `dataType` facet.
- **Branching is genuine.** A display table in two groupings yields two paths — those are
  real edges, unlike the cartesian combinations.
- **Change tracking needed nothing.** Correlation is derived from occurrences that are
  already declared, so their predicates are already registered; a vocabulary edit reverse-
  evaluates to the affected entities as before. Pinned by a test.
- **Fan-in stays cartesian.** A level fed by two occurrences has no single meeting node,
  so no plan is derived. Same for alternative paths.

### Tests

`TestCorrelatedRootHierarchy` (integration, 7 tests) — the exact GSWA shape with both
fields multi-valued: top-level counts, drill-down that does not invent paths, children not
out-counting the parent, flat facets unaffected, vocabulary-edit reindex, a display table
with no grouping, and a three-level chain. `TestCorrelatedHierarchyDerivation` (assembler,
6 tests) — which configurations produce a plan: prefix-chained, independent, reversed,
fan-in, inverse ascent step, three levels. Both registered in `TS_Text`.

The first two assertions were written red first and failed on the invented
`Holes / geochem-results` path before the fix (commit "Add failing test for correlated
hierarchy over prefix-chained root fields").

### Still open

- **Value-anchored sugar** (`idx:leaf` / `idx:ancestorPath`) remains unbuilt and is now
  much less pressing: the prefix chain already expresses the taxonomy-attached-to-a-term
  case with the config authors were writing anyway. It would only add value for a
  taxonomy walked transitively (`skos:broader+`), where the number of levels is not known
  in advance.
- **Deep chains are walked per entity at index time.** Each ascent step is a `PathEval`
  from the node reached. For the two- and three-step vocabularies here that is
  negligible, but a long chain over a large vocabulary would be worth measuring.

