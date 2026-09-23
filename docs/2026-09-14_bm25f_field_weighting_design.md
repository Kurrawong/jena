---
title: "BM25F field weighting"
date: "2026-09-14"
---

# 2026-09-14 BM25F field weighting

## Status

Proposal. No code written.

## Problem

Every field in a SHACL-mode search contributes equally, and there is no way to say
otherwise.

The canonical case is SKOS labels. A concept has `skos:prefLabel`, `skos:altLabel` and
`skos:hiddenLabel`. All three should be searchable; they are not equally authoritative. A
search for `carbon` should rank a concept whose *preferred* label is "Carbon" above one
that merely carries "carbon" as a hidden spelling variant. Today those are indistinguishable
in the ranking.

The same shape recurs for label-vs-body weighting: `rdfs:label` /
`dcterms:title` against `dcterms:description` / `skos:definition` / `skos:scopeNote`. A term
in a three-word title is a much stronger signal than the same term buried in a 400-word
definition, and users expect a title match first.

### What happens now

`ShaclTextIndexLucene.resolveSearchFields()` expands `"default"` to every field marked
`idx:defaultSearch true`, and `parseQueryForFields()` hands that list to a
`MultiFieldQueryParser`
([ShaclTextIndexLucene.java:672](../jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java)).
That produces a flat `BooleanQuery` of `SHOULD` clauses, one per field, unweighted, with
scores **summed** across clauses.

Three things go wrong with that, and they are the standard reasons BM25F exists:

1. **Summing double-counts.** A concept carrying "carbon" in `prefLabel`, `altLabel` *and*
   `hiddenLabel` scores roughly three times a concept carrying it once — so the concept
   with the sloppiest label set wins. BM25's term-frequency saturation is supposed to stop
   exactly this, and summing per-field scores defeats it: each field saturates separately
   and the sum does not saturate at all.
2. **IDF is computed per field.** "Carbon" may be common in `prefLabel` and rare in
   `hiddenLabel`, so the same term gets a *higher* IDF from the field we trust least, and a
   hidden-label hit can outscore a preferred-label one. The term's rarity is a property of
   the vocabulary, not of which field it landed in.
3. **Length normalisation is per field.** A match in a short `altLabel` gets a large
   length-norm boost relative to a match in a long `definition`. That is sometimes what we
   want — but it arrives as an accident of field length rather than as a stated editorial
   preference, and it cannot be tuned.

Multiplying each clause by a boost — the obvious first fix — does not address any of the
three. It rescales already-broken numbers.

## What BM25F actually does

BM25F treats a document as **one virtual field**. Term frequencies from the constituent
fields are pooled with per-field weights *before* saturation, and field lengths are pooled
the same way:

```text
tf̃(t)   = Σ_f  w_f · tf_f(t) / B_f          where B_f is the per-field length norm
leñ     = Σ_f  w_f · len_f
score(t) = idf(t) · tf̃ / (tf̃ + k1 · (1 - b + b · leñ/avg_leñ))
```

One saturation, one IDF (computed over the pooled field set), one length normalisation.
Weighting a field to 3.0 then means "an occurrence here counts as three occurrences",
which is a statement an ontology editor can reason about — unlike "multiply this clause's
final score by 3".

## Options considered

### A. Per-clause boosts on the existing disjunction

Wrap each field's parsed sub-query in a `BoostQuery`. Trivial (about ten lines in
`parseQueryForFields`), no reindex, works for every query syntax the parser accepts.

Rejected as the primary mechanism for the three reasons above, but **kept as the fallback
path** — see "Query shapes BM25F cannot express".

### B. `DisjunctionMaxQuery` (dismax)

Take the maximum per-field score instead of the sum, plus a small tie-breaker. Fixes the
double-counting (1) but leaves per-field IDF (2) and per-field length norms (3) untouched.
It is a decent cheap approximation and is what a lot of systems ship, but given that Lucene
has real BM25F available, it is not worth standardising on.

### C. Lucene's `CombinedFieldQuery` — recommended

`org.apache.lucene.sandbox.search.CombinedFieldQuery` is a genuine BM25F implementation:
you add fields with weights and terms, and it scores as if the weighted fields were a
single field. It is exactly the model above, and it is maintained by the Lucene project —
Elasticsearch's `combined_fields` query is a thin wrapper over it.

```java
CombinedFieldQuery q = new CombinedFieldQuery.Builder()
    .addField("prefLabel",   3.0f)
    .addField("altLabel",    1.0f)
    .addField("hiddenLabel", 0.3f)
    .addTerm(new BytesRef("carbon"))
    .build();
```

Costs and constraints, all of which we have to design around:

- **New dependency.** `lucene-sandbox`, version-matched to `ver.lucene` (10.3.1). Sandbox
  carries no back-compatibility guarantee — the class can change or move between minor
  Lucene releases. This matters here because the fork syncs `apache/jena@main` monthly and
  inherits its Lucene bumps. Mitigation: keep the call site behind one small builder class
  (`Bm25fQueryBuilder`) so a sandbox API change is a one-file fix.
- **Term queries only.** No phrases, wildcards, prefixes, fuzzy or ranges. The builder takes
  `BytesRef` terms, not a parsed query.
- **Norms required.** Every combined field must have norms. Our `TEXT` fields use
  `TextField.TYPE_STORED` / `TYPE_NOT_STORED`
  ([ShaclTextIndexLucene.java:1337](../jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java)),
  which keeps norms on, so this holds today — but it must be asserted, because a future
  `omitNorms` option would silently break scoring.
- **Same index options across the combined fields**, and `KEYWORD` fields cannot join the
  pool (they are not tokenized and their "length" is meaningless).
- **One block-join scope at a time.** A `CombinedFieldQuery` cannot span root fields and
  nested child-doc fields, since those live in different documents. Nested scopes are
  already handled separately in `parseQueryForFields` and stay separate: each scope gets
  its own combined query, wrapped by `wrapAsParent` as now.

### D. Index-time copy-field with repetition

Concatenate the weighted fields into one `_all` field, repeating high-weight content N
times. This is the pre-BM25F folk remedy. Rejected: it forces a reindex for every weight
change, bloats the index, restricts weights to small integers, and destroys field
attribution for `luc:match`.

## Design

### Configuration: `idx:weight` on the canonical field

Weight belongs on the canonical field resource, beside the other index metadata, because it
is a property of the field's identity and not of a particular occurrence or shape.

```turtle
@prefix idx:   <urn:jena:lucene:index#> .
@prefix field: <urn:jena:lucene:field#> .

field:prefLabel
    idx:fieldName    "prefLabel" ;
    idx:fieldType    idx:TextField ;
    idx:defaultSearch true ;
    idx:multiValued  true ;
    idx:weight       3.0 .

field:altLabel
    idx:fieldName    "altLabel" ;
    idx:fieldType    idx:TextField ;
    idx:defaultSearch true ;
    idx:multiValued  true ;
    idx:weight       1.0 .

field:hiddenLabel
    idx:fieldName    "hiddenLabel" ;
    idx:fieldType    idx:TextField ;
    idx:defaultSearch true ;
    idx:multiValued  true ;
    idx:weight       0.3 .

field:definition
    idx:fieldName    "definition" ;
    idx:fieldType    idx:TextField ;
    idx:defaultSearch true ;
    idx:weight       0.5 .
```

Rules:

- Default `1.0`. Absent `idx:weight` behaves exactly as today.
- A `xsd:double`/`xsd:decimal`; must be `> 0`. Zero or negative is a config error, not a
  silently-dropped field — "never match this" is `idx:defaultSearch false`, which already
  exists.
- Only meaningful on `TEXT` fields. On any other type it is a config error at parse time,
  following the precedent set by `idx:normalizer`, which rejects non-`KEYWORD` fields
  ([ShaclIndexAssembler.java:629](../jena-text/src/main/java/org/apache/jena/query/text/assembler/ShaclIndexAssembler.java)).
- Carried on `ShaclIndexMapping.FieldDef` as a `float`, with `getWeight()`.

**It does not go in the fingerprint.** `ShaclConfigFingerprint` hashes only what determines
what is *written to disk*, and explicitly excludes query-time settings such as
`text:maxFacetHits`. Weight is query-time: changing it must not report the index as stale.
The class Javadoc should gain `idx:weight` to the "deliberately not hashed" list, since a
reader will otherwise wonder.

### Query-time override in `fieldSpec`

`luc:query`'s `fieldSpec` argument today is `"default"` or a JSON array of field IRIs. Extend
the array to admit objects, so an application can tune weights per query without touching
server config:

```sparql
(?hit ?entity ?score) luc:query (
    "default"
    '[{"field":"urn:jena:lucene:field#prefLabel","weight":5.0},
      {"field":"urn:jena:lucene:field#altLabel"}]'
    "carbon" "" "" 10 0 )
```

- A bare string element keeps its current meaning and takes the field's configured weight.
- An object without `"weight"` likewise falls back to configured weight.
- With `"default"`, all `defaultSearch` fields participate at their configured weights.

`parseFieldSpec` currently returns a `List<String>`
([ShaclTextQueryPF.java:309](../jena-text/src/main/java/org/apache/jena/query/text/ShaclTextQueryPF.java));
it becomes a `List<WeightedField>` — record of `(String fieldIRI, Float weightOverride)` —
threaded through `resolveSearchFields`. Per the fork's policy, no compatibility shim for the
old internal signature is needed.

### Query planning: when BM25F applies

`parseQueryForFields` gains a pre-pass. Within each scope (root, and each nested scope
independently):

1. Partition the scope's fields into `TEXT` and non-`TEXT`. Only `TEXT` fields can be pooled.
2. Parse the query string once with the existing `QueryParser` against a sentinel field, and
   inspect the result. If it is a single `TermQuery`, or a `BooleanQuery` whose clauses are
   all `TermQuery`/`BooleanQuery` of terms — i.e. the plain keyword query that covers the
   overwhelming majority of real traffic — rewrite each term into one `CombinedFieldQuery`
   over the weighted `TEXT` fields, preserving the original clause occurs.
3. Anything else — phrase, wildcard, prefix, fuzzy, range, or a mix with `KEYWORD` fields —
   falls back to today's `MultiFieldQueryParser` disjunction, with each field's sub-query
   wrapped in a `BoostQuery(weight)` (option A). Weights still take effect; they are just
   applied the crude way.

The fallback is not a corner case to be embarrassed about — `luc:query` supports leading
wildcards deliberately (`setAllowLeadingWildcard(true)`), and prefix search is a documented
use case. Two scoring regimes for one configuration is a real cost, and the mitigation is
to say so plainly rather than hide it:

- The chosen regime is logged at debug with the reason.
- The `?score` documentation in `docs/02-sparql-api.md` gains a sentence: scores are
  comparable *within* one result set, never between queries. That is already true of BM25;
  this just makes it load-bearing.

### Field attribution and `luc:match`

`buildNamedQuery` wraps each field's sub-query in `NamedMatches` so `luc:match` can report
which field matched
([ShaclTextIndexLucene.java:752](../jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java)).
A `CombinedFieldQuery` deliberately erases that distinction — pooling is the whole point —
so it cannot carry per-field names.

Resolution: when field matches are requested, issue a `BooleanQuery` with

- the `CombinedFieldQuery` as the single scoring clause, and
- the existing per-field `NamedMatches` disjunction wrapped in `BoostQuery(0f)` as a
  `SHOULD`.

A zero boost contributes nothing to the score but still produces `Matches`, so attribution
survives unchanged and ranking comes entirely from BM25F. This costs a second pass over the
postings for hits where matches are actually extracted, which is already the expensive path.

Verify against Lucene 10.3.1 that a `BoostQuery(0f)` is not rewritten away before the
`Matches` API sees it — if it is, the named disjunction goes in a `FILTER`-style
non-scoring clause instead, or matches are extracted from a separately constructed query.
This is the one point in the design that must be proven with a test before the rest is
built.

### Shared execution and facet keys

`SearchExecution.getOrCreate()` keys shared state on normalised query parameters so that
`luc:query` and `luc:facet` in the same SPARQL query reuse one Lucene search. Weights change
the result *ordering* (and, with a limit, the *membership*), so **the effective weight
vector must be part of that key** — otherwise a second `luc:query` with different weights
silently receives the first one's results. Same for `FacetRequestKey`. Normalise by sorting
on field name and rendering the weight to a fixed number of decimal places, so that `3.0`
and `3.00` agree.

### Interactions to get right

- **`multiValued`.** BM25F length pooling sums all values of a multi-valued field, so a
  concept with twenty alt labels is penalised on length relative to one with two. That is
  the correct behaviour and worth documenting, since it is the mechanism that stops
  keyword-stuffed concepts from winning.
- **Nested fields.** Each nested scope keeps its own combined query and its existing
  `ToParentBlockJoinQuery` with `ScoreMode.Avg`. Weighting is within-scope; cross-scope
  scores still combine as they do today.
- **CQL filters.** Compiled as non-scoring `MUST`/filter clauses, so they are unaffected.
- **Facet counts.** Unaffected — counting is independent of scoring.

## Worked example: SKOS

With the config above, `luc:query ("default" "carbon" ...)`:

| Concept | prefLabel | altLabel | hiddenLabel | definition | Outcome |
|---|---|---|---|---|---|
| A | Carbon | — | — | (long) | ranks first: weight 3.0 on a short field |
| B | Graphite | carbon | — | (long) | second |
| C | Diamond | — | carbon | "…carbon…" | third: 0.3 + 0.5, pooled and saturated once |
| D | Carbon | carbon | carbon | — | ranks with A, not 3× A — pooling saturates |

Row D is the one that is broken today, and the reason to prefer C over A.

## Testing

Following the fork's test discipline — red first, corners of the matrix, and registered in
`TS_Text.java` or it will not run at all.

1. **Red baseline.** A test asserting D-above-A ordering under the current summing
   behaviour, then inverted to the BM25F expectation. It documents the bug before it
   documents the fix.
2. **Weight ordering.** prefLabel > altLabel > hiddenLabel for the same term.
3. **Saturation.** A term in three weighted fields does not outrank the same term in the
   single highest-weighted field by the ratio a sum would give.
4. **Pooled IDF.** A term common in `prefLabel` and rare in `hiddenLabel` does not rank the
   hidden-label document higher.
5. **Label vs body.** A title hit outranks a long-description hit at equal weight, and
   weight 0.5 on the description widens the gap.
6. **Fallback path.** A wildcard query over the same weighted fields returns the boosted
   disjunction, still weight-ordered, and does not throw.
7. **Config matrix corners.** weight × `multiValued` × nested scope × `KEYWORD` in the same
   `defaultSearch` set — the combination a real SKOS config produces, not one axis at a
   time.
8. **Config errors.** `idx:weight` on a `KEYWORD` field, and `idx:weight 0`, both rejected at
   assembler parse time with a message naming the field.
9. **Attribution.** `luc:match` still reports the matching field under BM25F scoring.
10. **Fingerprint.** Changing only `idx:weight` leaves the fingerprint unchanged and does
    not report the index as stale.
11. **Shared execution.** Two `luc:query` clauses differing only in query-time weights do
    not share a cached search.

## Rollout

| Step | Content |
|---|---|
| 1 | `lucene-sandbox` dependency; `Bm25fQueryBuilder` isolating the sandbox API; unit test proving `BoostQuery(0f)` preserves `Matches` |
| 2 | `idx:weight` through `IndexVocab`, `ShaclIndexAssembler`, `FieldDef`; validation and errors; fingerprint Javadoc |
| 3 | Query planning in `parseQueryForFields` / `buildNamedQuery`; boosted-disjunction fallback |
| 4 | `fieldSpec` object form in `ShaclTextQueryPF`; `SearchExecution` / `FacetRequestKey` key normalisation |
| 5 | Docs: `01-user-guide.md` (SKOS config), `02-sparql-api.md` (`fieldSpec`, `?score` caveat), `03-configuration.md` (`idx:weight`), `04-architecture.md` (two scoring regimes) |

Steps 2 and 3 are independently useful: step 2 alone with the boosted-disjunction path gives
weighting immediately, and is a reasonable place to stop if `CombinedFieldQuery` turns out to
cost more than it returns.

## Open questions

1. **Does `CombinedFieldQuery` survive the monthly upstream sync?** Sandbox APIs move. Worth
   a note in `CLAUDE.md`'s list of predictable conflict sites if this lands.
2. **Should `k1`/`b` be configurable?** BM25F's tuning constants are currently Lucene
   defaults. Exposing them invites tuning nobody can evaluate without relevance judgements.
   Recommend: not now.
3. **Language tags.** SKOS labels are typically language-tagged, and the natural next
   question is per-language fields (`prefLabel@en` weighted above `prefLabel@fr` for an
   English query). That is a separate design and should not be smuggled in here.
4. **Should `defaultSearch` become derivable from weight?** A field with a weight is
   presumably searchable. Keeping them independent is clearer: weight says how much,
   `defaultSearch` says whether — recommend leaving them separate.
