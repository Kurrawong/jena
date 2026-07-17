# Proposal: Normalizer support for KEYWORD fields (case-/locale-aware sort & exact match)

## Problem

A very common requirement — sort search results by a **name or label** — is awkward
today, and the only available answer produces surprising ordering.

### Why you can't just sort a TEXT field

Sorting in Lucene is driven entirely by **DocValues** (a per-document columnar value),
not by the inverted index. A `TEXT` field is analyzed/tokenized (`"Napoleon Bonaparte"`
→ `napoleon`, `bonaparte`), so there is no single value to order by, and Lucene refuses
to attach `SortedDocValues` to a tokenized field. Our code makes this explicit:

- `ShaclTextIndexLucene.buildLuceneSort()` throws
  `"Cannot sort on TEXT field '…'. Use KEYWORD for sortable fields."`
  ([ShaclTextIndexLucene.java:2362](../jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java)).

This is **not** a fork limitation — it is how every Lucene-based system works
(Elasticsearch makes you sort on `name.keyword`, not `name`). The correct model is the
"multi-field" pattern: index the label once as `TEXT` (for full-text search via
`luc:query`) and once as `KEYWORD` (for sorting / exact `in` filters).

### The remaining gap: KEYWORD sort is raw byte order

Today a sortable `KEYWORD` field writes the **raw lexical value** into DocValues:

```java
// ShaclTextIndexLucene.java:1000-1002 (and the node path at :1097-1099)
if (fieldDef.isSortable()) {
    doc.add(new SortedDocValuesField(fieldName, new BytesRef(strVal)));
}
```

`SortedDocValuesField` compares by **raw UTF-8 bytes**, so:

- `"Zebra"` sorts **before** `"apple"` (uppercase `Z` = 0x5A < lowercase `a` = 0x61)
- accents / locale are ignored (`"Zoë"` does not collate where a reader expects)

So sorting names "works" but is case-sensitive and not locale-aware — rarely what a user
wants for a person/organisation/title field.

The same problem affects exact match: `in` / `=` on a KEYWORD field compiles to a
`TermInSetQuery` / `TermQuery` over the **verbatim** indexed term
([CqlToLuceneCompiler.java:542-547](../jena-text/src/main/java/org/apache/jena/query/text/cql/CqlToLuceneCompiler.java)),
so a filter for `"Smith"` will not match an indexed `"smith"`.

## What the fix is — and what it is *not*

### Rejected: magic sidecar on TEXT (`sortable true` on a TEXT field)

An earlier idea was: when a `TEXT` field is marked `sortable`, silently emit a normalized
`SortedDocValuesField` under a derived sidecar name and have `buildLuceneSort` redirect to
it. **We are not doing this.** It bundles three hidden decisions into one boolean:

1. a field that exists in the index but appears in no config (mangled name),
2. an implicit normalization policy (lowercase? fold? locale?) buried in code,
3. an implicit multi-value tiebreak (which token becomes the sort key).

One flag, three invisible policies — too clever, and hard to debug.

### Chosen: an explicit **normalizer** on KEYWORD fields

This is exactly Elasticsearch's `keyword` + `normalizer` model. A *normalizer* is just an
`Analyzer` constrained to emit **one token, no tokenization** — e.g. a "lowercase keyword
analyzer":

```text
KeywordTokenizer  →  LowerCaseFilter  →  (optional ASCIIFoldingFilter / ICU folding)
```

The normalizer is a real, declared property. The field it applies to stays explicit; the
IRI you sort on is the field you declared. No sidecar, no redirect.

### The key Lucene fact: we must apply it ourselves

A normalizer **is** an analyzer, but Lucene will **not** apply it automatically on our
paths, for two independent reasons:

1. **DocValues are never analyzed.** `SortedDocValuesField` takes the `BytesRef` we hand
   it; the `IndexWriter` analyzer is never consulted for DocValues.
2. **`StringField` is `tokenized=false`,** so even the *indexed term* bypasses the writer
   analyzer. Our KEYWORD path uses `StringField` + `SortedDocValuesField`, and the
   `analyzer` slot on a KEYWORD `FieldDef` is currently **inert**
   (only `TextField` paths consult the per-field analyzer; the writer is built with a
   single `getAnalyzer()` at [ShaclTextIndexLucene.java:1942](../jena-text/src/main/java/org/apache/jena/query/text/ShaclTextIndexLucene.java)).

The right primitive is **`Analyzer.normalize(field, text)`** — purpose-built to turn a
string into a single normalized `BytesRef` by running char filters + multi-term-aware
token filters (lowercase, folding, ICU collation) **without** tokenizing. It is the same
call Lucene uses to normalize wildcard/range query terms.

## Proposed configuration surface

A new optional field-level property, `idx:normalizer`, pointing at an `Analyzer` resource.
**Only valid on `KEYWORD` fields — a hard build failure on any other field type** (it
signals a config mistake; see touch point #3). It reuses the **existing** analyzer-assembler
machinery (`a.open(resource)`, exactly as `idx:analyzer` / `idx:queryAnalyzer` already do at
[ShaclIndexAssembler.java:354,363](../jena-text/src/main/java/org/apache/jena/query/text/assembler/ShaclIndexAssembler.java)),
so it inherits everything that machinery already supports.

### What you can do today vs what this adds

To sort on a name today you already use the twin pattern — a `TEXT` field for search plus a
`KEYWORD` field with `idx:sortable true` for the sort:

```turtle
@prefix field: <urn:jena:lucene:field#> .
@prefix idx:   <urn:jena:lucene:index#> .
@prefix sh:    <http://www.w3.org/ns/shacl#> .

## Searchable name — TEXT, analyzed, used by luc:query
field:personName
    idx:fieldName "personName" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true ;
    sh:path schema:name .

## Sortable twin — KEYWORD, used by the sortSpec and exact `in` filters
field:personNameSort
    idx:fieldName "personNameSort" ;
    idx:fieldType idx:KeywordField ;
    idx:sortable true ;
    sh:path schema:name .          # SAME predicate populates both fields
```

The catch: this sort is **raw UTF-8 byte order** — case-sensitive (`"Zebra"` before
`"apple"`), no locale. There is no config knob to change that today. **Putting an analyzer
on the KEYWORD field does nothing**, because the KEYWORD index path uses `StringField`
(analyzer bypassed) and DocValues are never analyzed.

### What `idx:normalizer` adds

`idx:normalizer` is the new wiring that makes an analyzer actually take effect on a KEYWORD
field (applied via `Analyzer.normalize()` to the term + sort key). For the case-insensitive
common case you reuse the **upstream** built-in `text:LowerCaseKeywordAnalyzer`
(`LowerCaseKeywordAnalyzerAssembler`, stock Apache Jena, registered at
[TextAssembler.java:40](../jena-text/src/main/java/org/apache/jena/query/text/assembler/TextAssembler.java)) —
a keyword tokenizer + lowercase. So you don't have to *define* the analyzer, only point at
it:

```turtle
field:personNameSort
    idx:fieldName "personNameSort" ;
    idx:fieldType idx:KeywordField ;
    idx:sortable true ;
    idx:normalizer [ a text:LowerCaseKeywordAnalyzer ] ;
    sh:path schema:name .
```

To be explicit: the analyzer *class* is upstream, but it is **inert on KEYWORD today** — the
new code in this proposal is what applies it.

### Reuse: point `idx:normalizer` at an IRI

`a.open()` resolves **any** resource — named IRI or blank node — so a normalizer can be
defined once and referenced from many fields by IRI. Two idioms, both already supported
with **no extra code**:

**(a) Named analyzer resource** — define once, reference by IRI:

```turtle
<#nameNormalizer> a text:LowerCaseKeywordAnalyzer .

field:personNameSort
    idx:fieldName "personNameSort" ; idx:fieldType idx:KeywordField ;
    idx:sortable true ; idx:normalizer <#nameNormalizer> ; sh:path schema:name .

field:orgNameSort
    idx:fieldName "orgNameSort" ; idx:fieldType idx:KeywordField ;
    idx:sortable true ; idx:normalizer <#nameNormalizer> ; sh:path schema:legalName .
```

**(b) The existing `text:defineAnalyzers` registry** — jena-text already has a global
named-analyzer registry (`text:defineAnalyzers (...)` + `text:DefinedAnalyzer` /
`text:useAnalyzer`, backed by `Util.getDefinedAnalyzer(key)` in
[DefinedAnalyzerAssembler.java:48](../jena-text/src/main/java/org/apache/jena/query/text/assembler/DefinedAnalyzerAssembler.java)).
Define an analyzer once at the top of config, then reference it by key:

```turtle
text:defineAnalyzers (
  [ text:defineAnalyzer <#ciNorm> ; text:analyzer [ a text:LowerCaseKeywordAnalyzer ] ]
) .

field:personNameSort
    idx:fieldName "personNameSort" ; idx:fieldType idx:KeywordField ; idx:sortable true ;
    idx:normalizer [ a text:DefinedAnalyzer ; text:useAnalyzer <#ciNorm> ] ;
    sh:path schema:name .
```

For a fully custom normalizer (e.g. keyword tokenizer + lowercase + ASCII folding), use
`text:configurableAnalyzer` / `text:genericAnalyzer` as the referenced resource — same as
custom `idx:analyzer` definitions today. The only new expectation is that the analyzer is
single-token; we apply it via `Analyzer.normalize()` regardless, which ignores any
tokenizer split.

Naming alternatives considered: `idx:caseInsensitive true` (a shorthand mapping to
`text:LowerCaseKeywordAnalyzer`) — **deferred** (see open questions).

## Touch points (implementation map)

| # | File | Change |
|---|------|--------|
| 1 | `assembler/IndexVocab.java:67-75` | Add `public static final Property pNormalizer = Vocab.property(NS, "normalizer");` |
| 2 | `ShaclIndexMapping.FieldDef` (`ShaclIndexMapping.java:65-188`) | Add a `normalizer` (`Analyzer`) field + `getNormalizer()`. Thread through the canonical constructor. (Many overloaded ctors exist — add to the widest one and default the rest to `null`.) |
| 3 | `assembler/ShaclIndexAssembler.parseCanonicalField` (`:339-383`) | Parse `idx:normalizer` like `idx:analyzer` (resolve via `a.open(...)`). Reject it on non-KEYWORD types. Add the resolved node to `CanonicalFieldSpec` so `validateSameCanonicalField` (`:319-337`) checks it for consistency across occurrences. |
| 4 | `ShaclTextIndexLucene.addFieldToDoc` KEYWORD branch (`:990-1003`) **and** `addNodeFieldToDoc` KEYWORD branch (`:1087-1100`) | When a normalizer is present, compute `BytesRef key = normalizer.normalize(fieldName, value)` once and use it for **both** the `StringField` term and the `SortedDocValuesField`. When absent, behaviour is unchanged (raw value). |
| 5 | `cql/CqlToLuceneCompiler.compileIn` (`:542-547`) and `buildEqualQuery` KEYWORD case (`:721-723`) | When the field has a normalizer, normalize each comparison value with `normalizer.normalize(...)` before building the `TermInSetQuery` / `TermQuery`, so index-time and query-time terms match. (Mirror of how range/term queries already normalize.) |
| 6 | `ShaclTextIndexLucene.buildLuceneSort` (`:2342-2379`) | No change needed for KEYWORD — it already targets `queryFieldName(fd)` (= `fieldName`) and the normalized DocValues live there. Just remove/keep the TEXT guard (TEXT still throws; users use the KEYWORD twin). |

Note the facet interaction: `SortedSetDocValuesFacetField` is written under the raw value
today (`:997-999`, `:1094-1096`). If a normalized KEYWORD field is also `facetable`, decide
whether facet values should be normalized too (probably **no** — facet labels should stay
human-readable). Keep facet values raw and only normalize the term + sort DocValues. This
means a single field can show raw facet labels while sorting case-insensitively — which is
the desired behaviour, but worth a test.

## Backward compatibility

- Within SHACL mode no backward compatibility is required (per CLAUDE.md), but this change
  is additive: fields **without** `idx:normalizer` behave exactly as today (raw bytes).
- Adding or changing a normalizer changes the indexed term and sort key, so it requires a
  **reindex** of affected entities. Document this.

## Testing

- Sort: index `["Zebra","apple","Äpfel"]` on a normalized KEYWORD field; assert
  case-insensitive (and, with folding, accent-insensitive) order, vs raw byte order without
  the normalizer.
- Exact match: index `"Smith"`, filter `in ["smith"]` → matches with normalizer, does not
  without.
- Facet labels remain raw while sort is normalized (combined `facetable + sortable +
  normalizer` field).
- `validateSameCanonicalField` rejects two occurrences declaring the same field IRI with
  different normalizers.
- Add the new test class to `TS_Text.java` (Surefire only discovers `TS_*` suites).

## Decisions

1. **`idx:caseInsensitive true` sugar — deferred.** Ship only the generic `idx:normalizer`
   for now; users point it at the upstream `text:LowerCaseKeywordAnalyzer` for the common
   case. A `caseInsensitive` shorthand can be layered on later if the boilerplate proves
   annoying.
2. **Locale collation (`ICUCollationKeyAnalyzer`) — out of scope for v1.** Lowercase
   (+ optional ASCII folding) only, via the existing analyzer machinery. Revisit if true
   linguistic ordering is needed; it would add the `lucene-analysis-icu` dependency.
3. **`idx:normalizer` on a non-KEYWORD field — hard build failure.** It is a config error,
   so fail fast in the assembler rather than silently ignoring it.
4. **Multi-valued normalized KEYWORD — accepted as-is.** Each value is normalized
   independently into `SortedSetDocValues`; sort uses the min/max selector exactly as today.
   There is no better option, and it matches existing multi-valued sort behaviour.

## Recommendation

- Add `idx:normalizer` on KEYWORD fields, applied via `Analyzer.normalize()` to the term +
  sort DocValues at index time and to comparison values at query time.
- Keep the twin-field pattern (TEXT for search, KEYWORD for sort/exact) as the documented
  approach; this proposal just makes the KEYWORD side collate correctly.
- Do **not** add magic sortable-TEXT sidecar fields.
- Immediately (independent of code): document the twin-field pattern in `01-user-guide.md`
  so users can sort on names *today* (case-sensitively), with normalizer support landing as
  the follow-up that fixes collation.
