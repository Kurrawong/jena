---
title: "Temporal: CQL2 temporal operators over spans and periods"
date: "2026-10-26"
status: "Plan; nothing built yet"
issues: ""
---

# Temporal: CQL2 Temporal Operators over Spans and Periods

Implementation plan for CQL2's fifteen temporal operators in `luc:query` and `luc:facet`
CQL filters, over the existing `idx:TemporalField` and a new `idx:PeriodField`. Every claim
about current behaviour was verified against `main` at `3928057dd9` by reading the cited
line.

The first consumer is Prez (Kurrawong/prez, search work in PR #54 and PR #99). Prez sends
CQL2-JSON filters on Lucene fields as `luc:query`'s filter argument, and today rejects any
temporal operator or typed temporal literal with a 400 before sending
(`_check_lucene_filter` in `api/prez/dependencies.py`), because the fork fails them
mid-query with a 500. Prez answers the same operators in SPARQL, and **pushdown must give
the same answers as Prez's SPARQL**. That requirement fixes the semantics below; where
the spec leaves a choice open, Prez's choice is taken.

## Decisions taken

1. **Dates and periods.** `idx:TemporalField` stays a field of single values. A new
   `idx:PeriodField` reads a start and an end from one period node and keeps them
   paired.
2. **All fifteen operators.** Req 15A of the conformance class `/conf/temporal-functions`
   requires all of them; no subset conforms.
3. **A value is the span it names.** An `xsd:date` is the whole day, not its midnight.
   This reverses an earlier choice, because midnight instants would disagree with Prez's
   SPARQL at every day boundary.
4. **One PR.** Both field types, the docs and the demo land in one PR, built in four
   stages. Prez gets the image tag of its merge commit.
5. **Plan first, then test-first.** Each fix or behaviour lands as a red commit and a
   green commit.

## Semantics

Sources: OGC 21-065r2 (CQL2 1.0.0) §6.3, §7.8, Tables 5 and 6; the normative JSON schema
`schemas.opengis.net/cql2/1.0/cql2.json`; W3C OWL-Time, which Table 6 cites for twelve of
the fifteen definitions; and Prez's implementation (`prez/services/query_generation/
temporal.py`, `cql.py` `_Span` and `_related`, `reference_data/cql/temporal_relations.json`).
The spec and Prez agree on every point below unless a point says otherwise.

### A value's span

Every operand is the closed interval it spans, in UTC, as epoch milliseconds.

| Value | Span | Note |
|---|---|---|
| `xsd:dateTime` | `[t, t]` | No timezone → UTC |
| `xsd:dateTimeStamp` | `[t, t]` | Always has a timezone. Prez's SPARQL excludes it; see [Resolved questions](#resolved-questions) |
| `xsd:date` | midnight → next midnight | At its own offset if it has one: `"2021-06-30+10:00"` is `[2021-06-29T14:00Z, 2021-06-30T14:00Z]` |
| `xsd:gYearMonth` | first of the month → first of the next, UTC | Any offset ignored, as Prez does |
| `xsd:gYear` | 1 January → next 1 January, UTC | Any offset ignored, as Prez does |
| anything else | none: never matches | `gMonthDay`, `time`, and a string that looks like a date |

The datatype decides, not the lexical form. A `"2021-06-30"^^xsd:string` has no span.

A period ends when the next one starts, so consecutive days *meet*:
`t_after(p, {"date":"2021-06-30"})` does not match `"2021-07-01"^^xsd:date`, whose start
equals the 30th's end.

### Literals in a filter

| CQL2-JSON | Span |
|---|---|
| `{"timestamp": "2021-06-30T12:00:00Z"}` | `[t, t]` |
| `{"date": "2021-06-30"}` | the whole day, UTC |
| `{"interval": [a, b]}` | from `a`'s start to `b`'s end |

Each interval end is one of:

- a bare date string: the day's start as a start bound, its end (next midnight) as an end
  bound;
- a bare timestamp string: that instant;
- `".."`: open, `-∞` as a start and `+∞` as an end (§7.8.1);
- `{"property": p}`: `p`'s start as a start bound, `p`'s end as an end bound.

The schema's timestamp pattern requires a trailing `Z`. The parser accepts more, as
Prez's does: an offset is converted to UTC and a missing timezone is UTC. The one
non-standard key Prez reads, `{"datetime": ...}`, is not accepted.

### The relations

`s` is a span's start, `e` its end. Every comparison is strict or an equality, so
intervals are closed:

| Operator | Condition |
|---|---|
| `t_before` | `a.e < b.s` |
| `t_after` | `a.s > b.e` |
| `t_meets` | `a.e = b.s` |
| `t_metBy` | `a.s = b.e` |
| `t_overlaps` | `a.s < b.s ∧ a.e > b.s ∧ a.e < b.e` |
| `t_overlappedBy` | `a.s > b.s ∧ a.s < b.e ∧ a.e > b.e` |
| `t_starts` | `a.s = b.s ∧ a.e < b.e` |
| `t_startedBy` | `a.s = b.s ∧ a.e > b.e` |
| `t_during` | `a.s > b.s ∧ a.e < b.e` |
| `t_contains` | `a.s < b.s ∧ a.e > b.e` |
| `t_finishes` | `a.s > b.s ∧ a.e = b.e` |
| `t_finishedBy` | `a.s < b.s ∧ a.e = b.e` |
| `t_equals` | `a.s = b.s ∧ a.e = b.e` |
| `t_disjoint` | `a.e < b.s ∨ a.s > b.e` |
| `t_intersects` | `¬ t_disjoint`, i.e. `a.s ≤ b.e ∧ a.e ≥ b.s` |

Table 6 of the spec has two links swapped: `T_STARTEDBY` points at OWL-Time's
`intervalStarts` and `T_STARTS` at `intervalStartedBy`. The spec's Figure 6 and its test
data both follow standard Allen, as does the table above. The test GeoPackage's Berlin
feature, `[2022-04-16T10:13:19Z, 2024-02-22]`, is counted by
`T_STARTEDBY(interval(start,end), interval('…10:13:19Z','…10:15:10Z'))` and not by
`T_STARTS`.

The spec never says outright that an instant is `[v, v]`, but conformance Test 42 expects
`t_equals("date", interval('2022-04-16','2022-04-16'))` to match, which only holds if it
is.

### Open ends

Every comparison involving an open end is a constant: anything is after `-∞` and before
`+∞`, and `-∞ = -∞`. An open query end compiles to `Long.MIN_VALUE` or `Long.MAX_VALUE`
and is compared like any other bound. A TemporalField value is never open, so every
comparison against one comes out as Prez's `_related` decides it: `p.e < +∞` holds for
every value and `p.e = +∞` for none. A PeriodField value may be open (see
[Missing bounds](#missing-bounds)); there, an open query end and an open indexed end
compare as equal infinities.

### Operand order and operand kinds

Either operand may be a property, a literal or an interval (Prez sends
`t_contains(interval, p)`). Permission 8 lets a server refuse a literal first or a
property second; this plan does not use it.

| First / second | Compiles to |
|---|---|
| property / literal, literal / property | a box query on the property's span field |
| interval with property ends / literal | one box query per property end, ANDed or ORed per the relation |
| literal / literal | `MatchAllDocsQuery` or `MatchNoDocsQuery` |
| property / property | **raises**: a Lucene document cannot compare two of its own values |

### Multi-valued fields, and NULL

A positive filter on a multi-valued field is existential, as for every other operator
(03-configuration.md, "What a filter means on a multi-valued field").

- A plain `{"property": p}` operand is **one value**: its start and end come from the same
  value. This is what the 2-D point makes possible.
- Each property end of an interval is **bound on its own**. Prez's SPARQL binds the start
  and end of `{"interval": [p, q]}` to separate variables (`_interval_end`, one variable
  per side), so an entity with two values of `p` pairs either start with either end. On a
  single-valued field the two readings agree. A period whose start and end must stay
  paired is what `idx:PeriodField` is for.
- `t_disjoint` is a positive predicate, "before or after", and is existential like the
  others: an entity matches when **some** value is disjoint. It is **not** compiled as
  `MUST_NOT t_intersects`.
- An entity with no value for the property does not match any `t_` operator, including
  `t_disjoint`. Req 14C: if "either temporal expression … is NULL, then the predicate
  SHALL evaluate to the value NULL". The spec's own test data excludes the 240 NULL rows
  from `t_disjoint`. Prez's SPARQL agrees, through its `is_temporal` guard.
- `{"op":"not","args":[t_x]}` keeps the compiler's existing NOT
  (`CqlToLuceneCompiler.java:506`): `MatchAll MUST_NOT`, so an entity with no value does
  match it. Prez's `FILTER NOT EXISTS` behaves the same.

The difference from `<>` is deliberate. `<>` is defined here as the negation of `=`;
`t_disjoint` is defined by the spec as its own positive predicate.

### What is not decided by the spec

- **Date vs timestamp.** Permission 2 lets a server reject the comparison or cast;
  §7.8.3 offers casting to dates or to midnight. Prez does neither: it compares spans.
  Spans give the answer a reader expects (10:00 on a day is *during* that day), so this
  plan follows Prez.
- **Granularity.** "Both instants have the same granularity" (§7.8.1) is not enforced by
  the schema, and spans make it unnecessary.

## Current state (verified)

### Index side

- `addTemporalLiteralField` (`ShaclTextIndexLucene.java:1874`) writes one `LongPoint` on
  `<field>__epoch` when `idx:indexed`, and a `SortedNumericDocValuesField` on the same
  name when facetable or sortable. An unparseable value is logged and dropped from both.
- The epoch is `LiteralFieldSupport.parseTemporalEpochMillis` (`LiteralFieldSupport.java:109`),
  which reads the **lexical form only**: it sniffs a `T` to choose dateTime or date, and
  never looks at the datatype. A `"2021-06-30"^^xsd:string` is indexed as a date today.
- **An offset on a date is dropped.** The date branch (`:119-121`) parses with
  `ISO_DATE`, keeps the `LocalDate` and takes its start of day in UTC, so
  `"2021-06-30+10:00"^^xsd:date` indexes as `2021-06-30T00:00Z`, ten hours later than the
  day starts. Checked with `jshell`; the same midnight as an `xsd:dateTime` with `+10:00`
  gives `2021-06-29T14:00Z`.
- `xsd:gYear` and `xsd:gYearMonth` fail to parse, so they are stored but have no epoch.
- Values reach the indexer as `Object`. `addFieldToDoc` (`:1635`) dispatches on
  `instanceof Node`, and `ShaclEntityBuilder.extractOccurrenceValues`
  (`ShaclEntityBuilder.java:129`) yields **one value per node reached**. Nothing today
  can carry two nodes as one value.

### Query side

- `CqlParser` knows `COMPARISON_OPS` (`CqlParser.java:48`) and `SPATIAL_OPS` (`:49`).
  Any `t_` operator throws `Unknown CQL2 operator` (`:92`). A temporal operator fails
  loudly today, so unlike the spatial plan there is no silent drop to close first.
- `extractValue` (`:209`) takes only JSON strings, numbers and booleans. A
  `{"date": …}`, `{"timestamp": …}` or `{"interval": …}` object throws `Cannot extract
  CQL value` (`:228`) wherever a value is expected, comparisons included.
- On a TEMPORAL field, `=` is `LongPoint.newExactQuery` on the epoch twin
  (`CqlToLuceneCompiler.java:977`). `<`, `<=`, `>`, `>=` and `between` are
  `LongPoint.newRangeQuery` (`:1008-1016`), with strictness as ±1 ms. The epoch is a
  date's midnight, so these compare a value's **start**.
- Nested scopes: `maybeLiftToParent` (`:681`) lifts a clause on a child field. The
  same-scope fold recognises Comparison, In, Between, Like and TextQuery only
  (`inferLeafNestedScope` `:400`, `buildInnerForLeaf` `:435`). Spatial is neither lifted
  nor folded: `compileSpatial` returns the bare shape query (`:730`). Temporal must not
  copy that, because TEMPORAL inside `idx:nested` is supported and tested
  (`TestNestedTemporalField`, #142).

### Elsewhere a new field type is felt

- **Three exhaustive switches fail to compile** with a new `FieldType` constant:
  `sortTypeFor` (`ShaclTextIndexLucene.java:3402`), the sort-selector discriminator
  switch (`:3498-3512`), and `TextFacetPF.facetValueNode` (`TextFacetPF.java:190`).
- **One switch statement silently does nothing**: `ShaclIndexStats` (`:84-92`) has no
  default, so a PERIOD field would report no figures. `addPoints` reads only
  `PointValues.getDocCount` and `size`, which are dimension-agnostic, so a 2-D point
  works unchanged.
- `idx:stored` defaults to `true` (`assembler/ShaclIndexAssembler.java:740`), and nothing
  checks `facetable` or `sortable` per type. A facetable field of a type with no facet
  path is accepted and fails quietly: `collectFacetResults` swallows the error and returns
  empty buckets.
- `SELF_BINDABLE_TYPES` (`ShaclIndexAssembler.java:49`, checked at `:581`) allows only
  KEYWORD and TEXT on `idx:self`.
- `parseFieldType` (`:1032`) is an if-chain over vocabulary IRIs that throws on anything
  unknown.
- External `idx:column`s check `ExternalSourceDef.SUPPORTED_COLUMN_TYPES`, an allowlist,
  so a new type is rejected there with no change.
- **The config fingerprint covers configuration, not index layout.**
  - `renderField` (`ShaclConfigFingerprint.java:149-165`) writes the type's enum name, and
    `renderOccurrence` (`:168-181`) writes the path, sorted variants and predicates.
  - Adding a span field to TEMPORAL without changing the configuration leaves an existing
    index reporting MATCH while it lacks the field, and every `t_` operator returns
    nothing.
  - A changed fingerprint reports MISMATCH, which is a startup warning
    (`ShaclTextIndexLucene.java:486`).
  - A bumped `FINGERPRINT_VERSION` (`:91`) reports UNKNOWN (`ShaclIndexStamp.java:150-159`),
    which is meant for format changes and is logged at info.
- `luc:config` copies the configuration triples verbatim, so new vocabulary needs no
  change there.

## Design

### The span field

Every **indexed** temporal value also gets a 2-D `LongPoint` on `<field>__span`, holding
`(start, end)` in epoch milliseconds. A TemporalField writes one per value; a PeriodField
writes one per period node. Neither is stored or given doc values. A relation against a
constant span is a box in `(start, end)` space, which is one `LongPoint.newRangeQuery`
with `long[]` bounds. Lucene 10.3.1 has the 2-D constructor and query; verified with
`javap`.

`<field>__epoch` stays as it is for `=`, `<`, `between`, sort and range facets, with one
change: it takes the span's start, from the same datatype-aware parser. That fixes dates
with offsets, gives `gYear` and `gYearMonth` a sortable start, and leaves the lexical
fallback for untyped literals in place for `__epoch` only, so a string that looks like a
date keeps the comparisons it has today. The span field never uses the fallback.

The layout changes for every indexed TEMPORAL field, so `renderField` adds a layout tag
for TEMPORAL and PERIOD (`type=TEMPORAL/span`). An index built before this then reports
MISMATCH at startup, rather than MATCH while answering every `t_` query with nothing. A
reindex is required, as for any SHACL-mode layout change.

### Compiling an operator

1. **Resolve each operand to two ends.** An end is a constant (epoch ms, or `±∞`) or a
   *bound end*: a binding plus a side, start or end. A plain property operand is one
   binding, so both its ends share it. Each property end of an interval is a binding of
   its own.
2. **Expand the relation** into its comparisons, with AND or OR logic. `t_intersects` is
   negated, and De Morgan turns it into `a.s ≤ b.e ∧ a.e ≥ b.s` *per value*. No relation
   negates an `=`, so a negated comparison is always a half-range.
3. **Classify each comparison.** Constant against constant folds to true or false.
   Bound against constant is a range on one dimension of one binding's span. Bound
   against bound raises.
4. **Build the query.**
   - Under AND, intersect each binding's ranges into one box.
   - Under OR, build a box per comparison.
   - Every binding must have a value, which is the NULL rule, so a binding with no
     constraint left still contributes an exists-box: the full `[MIN, MAX]²` range.
   - An empty box is `MatchNoDocsQuery`.
5. **Saturate the strict bounds.** `< MIN` and `> MAX` are empty, and `< x` is `≤ x-1`
   without overflow. The existing `Math.addExact` (`:1010-1013`) would throw on a
   sentinel.
6. **Lift and fold.** Each binding's box is lifted with `maybeLiftToParent`. A temporal
   clause with exactly one binding joins the same-scope fold through
   `inferLeafNestedScope` and `buildInnerForLeaf`. A clause with two bindings lifts each
   on its own, matching the independent-variables reading.

### Parser and AST

- `TEMPORAL_OPS`: the fifteen names with the schema's exact casing (`t_finishedBy`,
  `t_metBy`, `t_overlappedBy`, `t_startedBy`).
- `CqlTemporal(String op, TemporalOperand a, TemporalOperand b)`. `TemporalOperand` is
  sealed: `Property(String)`, `Instant(Kind, String)` for date or timestamp, and
  `Interval(End, End)`. `End` is `Open`, `Literal(String)` or `Property(String)`.
- `toCanonical()` keeps operand order, which changes the meaning, so `SearchExecution`
  keys stay distinct.
- `extractValue` also unwraps `{"date": …}` and `{"timestamp": …}` to their string, so
  `>`, `between` and `in` accept typed literals as CQL2 writes them. Their meaning is
  unchanged: they compare the start instant on `__epoch`.

### idx:PeriodField

```turtle
field:validity
    idx:fieldName "validity" ;
    idx:fieldType idx:PeriodField .

ex:TenementShape
    sh:property [
        idx:field field:validity ;
        sh:path ex:validity ;                                   # reaches the period node
        idx:periodStart ( time:hasBeginning time:inXSDDateTimeStamp ) ;
        idx:periodEnd   ( time:hasEnd time:inXSDDateTimeStamp ) ;
    ] .

# Start and end directly on the entity
ex:ReportShape
    sh:property [
        idx:field field:activePeriod ;
        idx:self true ;
        idx:periodStart schema:startDate ;
        idx:periodEnd schema:endDate ;
    ] .
```

- `idx:periodStart` and `idx:periodEnd` sit on the **occurrence**, beside `sh:path`. That
  way one field can be fed from OWL-Time on one shape and from `schema:startDate` on
  another, as fan-in already allows.
  - Both are required on a PeriodField occurrence.
  - They are rejected on any other occurrence, on a canonical field
    (`rejectOccurrencePropertiesOnCanonicalField`, `:757`) and on a step (`parseStep`,
    `:503`).
  - They are parsed with `parseShaclPath` (`:917`), so `*`, `+` and `?` paths are
    rejected as everywhere else.
- `idx:self` is allowed: `SELF_BINDABLE_TYPES` gains PERIOD.
- Value constraints on the occurrence filter the **period node**, not the start and end
  literals. The docs must say so.
- **The span of a period** runs from its start value's start to its end value's end. A
  period from the date 2020-01-01 to the date 2020-12-31 therefore ends at
  2021-01-01T00:00Z, the same reading a query interval's date end gets.
- **Indexing.** `ShaclEntityBuilder` yields a `PeriodValue(Node start, Node end)` per
  period node, and `addFieldToDoc` gets a branch for it. The period is skipped with a
  warning when either side has more than one value, when a value has no span, or when its
  start is after its end.
- **Change tracking through `ConditionPath`.**
  - `addConditionPaths` (`ShaclIndexMapping.java:1389`) already registers "extra relative
    paths from the node an occurrence reaches", for value constraints.
  - Registering `concatVariants(occurrence.getPathVariants(), startVariants ∪ endVariants)`
    there makes the listener follow an edit to `time:inXSDDateTimeStamp` two hops below
    the entity, and leaves the occurrence's own variants untouched.
- **Fingerprint.** `renderOccurrence` renders `periodStart` and `periodEnd` **explicitly**.
  If they were only folded into the sorted variant and predicate lists, swapping start and
  end would produce the same fingerprint.
- **v1 limits.**
  - Unstored: `idx:stored` defaults to false for PERIOD, and an explicit `true` raises.
    There is no RDF literal for a period to return.
  - Not facetable or sortable: an explicit `true` raises.
  - Not a hierarchy level, not `idx:defaultSearch`, and no `idx:storeLiteralMetadata`.
  - `sortTypeFor` and the discriminator switch raise for PERIOD.
  - `facetValueNode` raises an unreachable-state error.
  - `ShaclIndexStats` reads `__span`.
- **Field-name collision.** A user field literally named `x__span` would collide with the
  generated name. `validateFieldNameUniqueness` does not check this today for `__epoch`
  either. Out of scope, noted.

### Missing bounds

A period node with a start and no end is **open-ended**, stored as `Long.MAX_VALUE`, and
symmetrically for a missing start. An ongoing tenement then intersects every interval
after its start and never matches `t_before`. It matches
`t_finishes(p, {"interval": ["2010-01-01", ".."]})` when it started after 2010, because
both ends are `+∞`. A node with neither bound is skipped.

## Delivery: one PR, four stages

The stages are commit groups within one PR, in this order. Each follows the test
discipline in `CLAUDE.md`:

- Red commit, then green.
- New test classes in JUnit 5, registered in `TS_Text` and confirmed in the
  `-- in <class>` output.
- The docs a stage changes are backed by a test of the exact recommended shape.

### Stage 1 — Span field for TemporalField

- A `TemporalSpans` parser: from datatype plus lexical form to `(start, end)`, for the
  four datatypes. `__epoch` uses its start, with the lexical fallback for untyped
  literals.
- Write `<field>__span` for indexed TEMPORAL values. Add the fingerprint layout tag.
- Tests:
  - Parser units on Prez's fixture values: `datetime-utc`, `datetime-offset`,
    `datetime-no-timezone`, `date`, `date-offset`, `year-month`, `year`, plus `--06-30`
    and `12:00:00Z` with no span. Also December rollover for `gYearMonth`.
  - The offset-date fix through `=` and `between`.
  - `luc:stats` point count on `__span`.
  - A pre-change index reporting MISMATCH.
- Docs: `03-configuration.md` (TemporalField semantics), `05-testing.md`.

### Stage 2 — Temporal operators over TemporalField

- Parser, AST and compiler as designed; typed literals in comparisons; nested lift and
  fold; filter errors rethrown as `QueryParseException`.
- Tests:
  - **Prez's eleven cases, verbatim**, end to end through `luc:query` on a mirror of
    `api/tests/integration/jena-lucene-cql/data/temporal.ttl`.
  - **An oracle test.** Generate random spans and random query spans, including open
    ends, then compare each operator's Lucene hits with the table above evaluated
    directly in Java. This is what proves the box per operator.
  - Boundary fixtures on every operator: touching ends, ±1 ms, and an open end against a
    closed one.
  - Multi-valued: existential per operator. `t_disjoint` with one disjoint value and one
    intersecting value matches.
  - NULL: an entity with no value matches no `t_` operator, and does match `not(...)`.
  - Interval property ends bound independently: two values cross-pair.
  - Operand order: `t_contains(interval, p)`.
  - Raises: property against property, an unknown or non-temporal field, a malformed
    literal, an interval of the wrong length.
  - Over HTTP, through Fuseki: a filter that cannot be compiled is a 400, not a 500.
  - Nested: a lone clause lifts; two same-scope clauses fold to one child.
  - `CqlParser`: all fifteen names with exact casing, and each operand form.
- Docs: `02-sparql-api.md`, a "Temporal operators" section under CQL2-JSON Filters, with
  the span table, the relations, operand rules and NULL rule.

### Stage 3 — idx:PeriodField

- Vocabulary, assembler validation, `PeriodValue`, indexing, change tracking through
  `ConditionPath`, fingerprint rendering, stats, and the three switch arms. The compiler
  accepts PERIOD operands with no further change, since it already compiles to
  `__span`.
- Tests:
  - Each config rejection, with its message.
  - Indexing: an open end, an open start, start after end, two starts, an unparseable
    bound.
  - **Correlation**: an entity with periods `[2010, 2012]` and `[2018, 2020]` does not
    match `t_during(p, interval [2011, 2019])`.
  - `idx:self`, nested, and the oracle test over periods with open ends.
  - The closed-periods-only opt-out: an occurrence with
    `sh:node [ sh:property [ sh:path time:hasEnd ; sh:minCount 1 ] ]` skips an
    open-ended period.
  - Change tracking: an edit to the end literal two hops down reindexes the entity, and
    so does removing the `time:hasEnd` link.
  - Fingerprint: swapping start and end changes it.
- Docs: `03-configuration.md` (field type, occurrence properties, v1 limits),
  `10-suggested-configuration.md` (OWL-Time and `schema:startDate` recipes), and the
  `docs/README.md` status row.

### Stage 4 — Demo

Period data, some open-ended, and one query per operator in the demo, as spatial PR 6
did. After merge, hand Prez the `sha-<short>` tag. CI pushes images only on a push to
`main` (`.github/workflows/docker.yml:78`), so the tag is the merge commit's.

## Deliberately out of scope

- **Property against property.** It raises. Lucene has no per-document comparison of two
  values without a custom doc-values query.
- **Day-granular comparisons.** `=`, `<` and `between` keep comparing the start instant.
  Moving them to span semantics would be a separate decision.
- **Sorting, faceting and projecting a PeriodField.** Sort by start is the obvious
  follow-up.
- **Numeric intervals** such as `depthFrom`/`depthTo` in the drillhole examples. The same
  2-D layout would serve them, but CQL2's `t_` operators are temporal only.

## Resolved questions

1. **A missing period bound is open-ended.**
   - In validity data, a start with no end almost always means "still current".
     Skipping those periods would drop every live one from every query, silently.
   - Data where a missing end means "unknown" already has an opt-out with no new config.
     A value constraint on the occurrence filters the period node, so
     `sh:node [ sh:property [ sh:path time:hasEnd ; sh:minCount 1 ] ]` indexes only
     closed periods. Stage 3 tests this exact shape.
2. **`xsd:dateTimeStamp` is a dateTime.**
   - It is XSD 1.1's dateTime with a required timezone.
   - OWL-Time's `time:inXSDDateTimeStamp` uses it, so without it a PeriodField over
     OWL-Time data would index nothing.
   - Pushdown and Prez's SPARQL differ only on values of this type, until Prez adds it to
     `is_temporal`. The handback to Prez says so.
3. **The name is `idx:PeriodField`.**
   - This repo already uses "interval" for depth intervals.
   - A numeric interval field would want that name.
   - CQL2's `{"interval": …}` is query syntax, so naming the field type after it buys
     nothing.
4. **A filter the index cannot run is a 400** (stage 2).
   - Fuseki already answers a `QueryParseException` thrown during execution with a 400
     (`SPARQLQueryProcessor.java:308`, "bad fixed Lucene query string"). The 500 Prez
     sees today is because `TextIndexException` extends `QueryExecException`.
   - `luc:query` and `luc:facet` catch a `TextIndexException` from parsing and compiling
     their CQL filter argument, and only from that step, then rethrow it as a
     `QueryParseException`.
   - The throw sites in `CqlParser` and `CqlToLuceneCompiler`, and their unit tests,
     are unchanged. Errors during execution, such as I/O, stay 500s.
   - It covers spatial and comparison errors too. Without it, a malformed temporal filter
     that Prez answers with a 400 today would become a 500 once Prez removes its stopgap.
5. **No tracking issue.** The PR description links this plan, and the Prez session gets
   the PR link. The spatial plan's issues existed because its problems were reported
   before there was a plan.
