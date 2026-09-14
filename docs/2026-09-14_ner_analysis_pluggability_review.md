---
title: "Analysis-chain pluggability, reviewed against an NER workload"
date: "2026-09-14"
---

# 2026-09-14 Analysis-chain pluggability for NER

## Status

Review. No code written. Prompted by a user building named-entity recognition on top of
the SHACL index, who reported which analysis capabilities they could and could not reach
from configuration.

Their three claimed gaps were phonetic matching, misspelling tolerance beyond `~1`, and
span finding. Two of the three hold. The reasons are narrower than reported, and one
further gap — the one that unlocks most of the others — was not on their list.

## What is already reachable

`idx:analyzer`, `idx:queryAnalyzer` and `idx:normalizer` are resolved with a bare
`a.open(resource)` ([ShaclIndexAssembler.java:606,615,636](../jena-text/src/main/java/org/apache/jena/query/text/assembler/ShaclIndexAssembler.java)),
so SHACL mode reaches the whole assembler registry, `text:GenericAnalyzer` included.
`GenericAnalyzerAssembler` is reflection over `getDeclaredConstructor`
([GenericAnalyzerAssembler.java:196](../jena-text/src/main/java/org/apache/jena/query/text/assembler/GenericAnalyzerAssembler.java)),
with `text:TypeAnalyzer` allowing analyzers to nest as constructor arguments.

Any `Analyzer` subclass with a matching public constructor therefore works per field:
`ShingleAnalyzerWrapper`, the language analyzers, `English` / `Standard` / `Whitespace` /
`Keyword`. `CustomAnalyzer` does not, because it is builder-only and has no public
constructor to reflect on.

## Corrections to the reported gaps

### Filters are not inherently unreachable

`GenericFilterAssembler`, `GenericTokenizerAssembler` and `DefineFiltersAssembler` exist
and let `text:ConfigurableAnalyzer` use an arbitrary `TokenFilter` class. The catch is
narrower and worse: `text:defineAnalyzers` is parsed only in
[TextIndexLuceneAssembler.java:131](../jena-text/src/main/java/org/apache/jena/query/text/assembler/TextIndexLuceneAssembler.java).
`ShaclTextIndexAssembler` never looks at it.

So in SHACL mode `ConfigurableAnalyzer` is confined to the four tokenizers and two filters
hardcoded in its static block
([ConfigurableAnalyzer.java:57-72](../jena-text/src/main/java/org/apache/jena/query/text/analyzer/ConfigurableAnalyzer.java)),
and `text:DefinedAnalyzer` / `text:addLang` are unusable. This is an assembler wiring
omission, not a design limit, and it is the root of most of what follows.

### The snippet slot is plumbed but never filled

`luc:match` binds `(?hit ?field ?value ?snippet)`
([TextMatchPF.java:52,153](../jena-text/src/main/java/org/apache/jena/query/text/TextMatchPF.java))
and `FieldMatch` carries a snippet node, but every construction site passes `null`
([ShaclTextIndexLucene.java:914,933,1055](../jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java)).
`lucene-highlighter` is already a dependency. "No highlighting" is true in effect; the
socket is already cut.

### Confirmed as reported

No `lucene-analysis-phonetic`, no `lucene-memory`, no `lucene-monitor`, and no
`Similarity` hook anywhere in the module. The query string goes straight to the classic
`QueryParser` with only `setAllowLeadingWildcard(true)` touched
([ShaclTextIndexLucene.java:695-700](../jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java)):
no `setFuzzyPrefixLength`, no `setFuzzyMinSim`, no `setPhraseSlop`.

## Gaps, cost, breakage

| # | Gap | What to do | Effort | Breaking |
|---|---|---|---|---|
| 1 | `defineAnalyzers` dead in SHACL mode | Call `DefineAnalyzersAssembler.open()` in `ShaclTextIndexAssembler` before `parseShapes` | ~0.5 day + tests | No — additive |
| 2 | No interior n-grams | Add `NGramAnalyzer` mirroring `EdgeNGramAnalyzer`, plus assembler and vocab term | ~1 day | No |
| 3 | No phonetic | Add `lucene-analysis-phonetic`; Beider-Morse additionally needs a wrapper analyzer | 0.5–1 day | No, but grows the image |
| 4 | Fuzzy has one knob | Expose `fuzzyPrefixLength`, `phraseSlop`, `defaultOperator` on the parser | ~2 hours index-level | Index-level no; per-query yes |
| 5 | `?snippet` always null | `UnifiedHighlighter` over stored fields, or Lucene's `Matches` API | 1–2 days | Operationally, in offsets mode |
| 6 | No gazetteer / document-side matching | `lucene-monitor` subsystem and a new property function | 2–4 weeks | New surface |
| 7 | `Similarity` not pluggable | `text:similarity` via the same reflection pattern | ~20 lines | No |

### 1 is the unlock

With `text:defineAnalyzers` wired, `text:GenericFilter` reaches every filter in
`lucene-analysis-common` — `NGramTokenFilter`, `SynonymGraphFilter` (partly),
`WordDelimiterGraphFilter`, `PatternReplaceFilter` — plus anything added by 3. Ordering
matters: analyzers must be defined before
[ShaclTextIndexAssembler.java:77](../jena-text/src/main/java/org/apache/jena/query/text/assembler/ShaclTextIndexAssembler.java)
parses the shapes, since the shapes are what reference them.

Two caveats, both of which stop being dormant upstream warts the moment we advertise this
path:

- The registry is static global state (`ConfigurableAnalyzer.defineFilter`), so two
  datasets in one Fuseki share a filter namespace. Last definition wins, silently.
- `ConfigurableAnalyzer.getTokenFilter` assigns `paramValues[0] = source` into a shared
  mutable `FilterSpec` array
  ([ConfigurableAnalyzer.java:151](../jena-text/src/main/java/org/apache/jena/query/text/analyzer/ConfigurableAnalyzer.java),
  [GenericFilterAssembler.java:193-203](../jena-text/src/main/java/org/apache/jena/query/text/assembler/GenericFilterAssembler.java)).
  Two threads analysing concurrently can cross streams. `TestDatasetConcurrencyWithConfigurableAnalyzer`
  exists, so upstream has been here; read it before recommending filter chains under load.

### 2 is the highest-value item for the reported workload

`~2` blowing up on short tokens such as `CO` or `AUS` is not fixable with fuzzy knobs:
Lucene hard-caps `maxEdits` at 2, and short tokens have nothing to prefix-gate on. A
trigram field is the standard answer, and it is cheap here because `EdgeNGramAnalyzer` is
the template.

One design note: `pairedQueryAnalyzer`
([ShaclIndexMapping.java:181](../jena-text/src/main/java/org/apache/jena/query/text/ShaclIndexMapping.java))
special-cases edge n-grams so the query side is *not* n-grammed. Interior n-grams want the
opposite — both sides n-grammed — so that method needs a second branch, not a copy.

### 5 has a fork in it

Character offsets via the `Matches` API need
`IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS`, an index format change and so a
full reindex. `UnifiedHighlighter` in re-analysis mode needs no reindex, only stored values
and CPU per hit. For span finding, offsets are what is actually wanted, and the reindex is
the price. Either way nothing downstream regresses: `?snippet` is unbound today.

Field attribution is already solved — query clauses are wrapped in `NamedMatches`
([ShaclTextIndexLucene.java:buildNamedQuery](../jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java)).

### 6 is worth resisting

Inverting the problem — querying per candidate span rather than running a gazetteer over a
document — is the right architecture, and `lucene-monitor` would not obviously beat it. A
much cheaper 90% is a `luc:analyze` property function that runs a named field's index
analyzer over a supplied string and returns the tokens. A day's work, no index change, and
it removes the whole class of bug where client-side candidate spans tokenize differently
from the index.

## Breaking-change surface

SHACL mode carries no backward-compatibility obligation (see `CLAUDE.md`), so this is about
churn, not policy.

The place to be careful is the `luc:query` object tuple: positionally parsed, exactly seven
arguments, throwing otherwise
([ShaclTextQueryPF.java:276-281](../jena-text/src/main/java/org/apache/jena/query/text/ShaclTextQueryPF.java)).
Per-query fuzzy control means an eighth slot, and every existing query breaks. If per-query
knobs are wanted, take them as a JSON options blob in an existing slot, or fold them into
the CQL layer, which already has structured expressions.

Analyzer changes also interact with the config fingerprint. `validateSameCanonicalField`
compares `analyzerNode` by `Node` identity
([ShaclIndexAssembler.java:582-584](../jena-text/src/main/java/org/apache/jena/query/text/assembler/ShaclIndexAssembler.java)),
and analyzers defined through `text:defineAnalyzers` would sit outside the fingerprint
entirely — so editing a named filter chain would change indexing behaviour without
signalling that a reindex is due. Decide this as part of 1, not after.

## Documentation and test debt found on the way

- No SHACL-mode test uses `text:GenericAnalyzer` through `idx:analyzer`. The capability the
  user exercised in production is untested here, which the repo's own rule — documented
  recommendations must be backed by a test — already forbids, independently of anything
  above.
- [10-suggested-configuration.md:57](10-suggested-configuration.md) says edge n-grams are
  reachable in upstream mode "by assembling a tokenizer with `text:GenericAnalyzer`". No
  stock Lucene analyzer class wraps an n-gram tokenizer, and `CustomAnalyzer` is
  builder-only and so unreachable by reflection. What is reachable is this fork's
  `EdgeNGramAnalyzer` by class name.
- Setting a global `text:queryAnalyzer` discards every per-field `idx:queryAnalyzer`. It
  warns ([TextIndexLucene.java:159-162](../jena-text/src/main/java/org/apache/jena/query/text/TextIndexLucene.java))
  but the warning is easy to miss in a loader log.

## Suggested order

1 (unlock), then 2 (the reported misspelling problem), then 5 in re-analysis mode (spans
without a reindex), then reassess. 3 follows 1 almost for free. 6 should wait for evidence
that the inverted approach is genuinely insufficient.
