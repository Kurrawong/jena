# Configuration Reference

This document covers SHACL-mode configuration for `text:TextDataset`.

Classic `text:entityMap` / `text:query` configuration is unchanged upstream and is not covered here.

## Dataset Wrapper

Use `text:indexes`, even for a single index.

Single index:

```turtle
@prefix text: <http://jena.apache.org/text#> .
@prefix tdb2: <http://jena.apache.org/2016/tdb#> .

<#ds> a text:TextDataset ;
    text:dataset <#baseDs> ;
    text:indexes <#index> .

<#baseDs> a tdb2:DatasetTDB2 ;
    tdb2:location "/path/to/tdb2" .
```

Multiple indexes:

```turtle
<#ds> a text:TextDataset ;
    text:dataset <#baseDs> ;
    text:indexes ( <#objectsIndex> <#ocrIndex> ) .
```

Notes:

- `text:indexes` accepts either a single resource or an RDF list.
- `text:index` is legacy and should be avoided in new configs.
- Duplicate `text:indexId` values are rejected.

## Index Resources

```turtle
@prefix text: <http://jena.apache.org/text#> .
@prefix idx:  <urn:jena:lucene:index#> .
@prefix sh:   <http://www.w3.org/ns/shacl#> .

<#index> a text:TextIndexShacl ;
    text:indexId "default" ;
    text:directory "mem" ;
    text:shapes ( <#BookShape> <#ArticleShape> ) ;
    text:storeValues true ;
    text:maxFacetHits 50000 .
```

SHACL mode is `text:TextIndexShacl`, and it **requires** `text:shapes`. `text:TextIndexLucene`
is the classic triple-per-document index and ignores `text:shapes` entirely — configure that
one with `text:entityMap`.

| Property | Required | Meaning |
|---|---|---|
| `text:indexId` | no | Token id used by `indexSelector`, for example `"default"` or `"objects"` |
| `text:directory` | yes | Lucene storage location. The literal `"mem"` is in-memory; anything else is a path or file IRI |
| `text:shapes` | yes | RDF list of SHACL shapes, one document profile each |
| `text:taxonomyDirectory` | no | Where hierarchical-facet ordinals live. Defaults to a sibling of `text:directory` named `<path>_taxonomy`, or to memory when `text:directory` is `"mem"`. Set it only to put the taxonomy somewhere else |
| `text:storeValues` | no, default `false` | Store values for `luc:match` and facet value binding |
| `text:maxFacetHits` | no | Maximum documents considered during facet collection |
| `text:analyzer` | no | Index-wide default analyzer |
| `text:queryAnalyzer` | no | Index-wide query analyzer |

If the index resource itself is a URI resource, that URI is also accepted as an `indexSelector`.

### Upgrading: the taxonomy directory

A config that declares hierarchies with `text:directory <path>` and no
`text:taxonomyDirectory` now creates `<path>_taxonomy` beside the index on first open.
Previously such a config kept its ordinals in memory, so hierarchical facet counts were
lost whenever the process that built the index was not the process that served it — the
`fuseki-lucene-shacl-loader` / `fuseki-lucene-shacl` split, which is the normal
deployment. Those configs start returning hierarchical counts on upgrade with no edit.

Rebuild the index after upgrading. An index built under the old default carries facet
ordinals that no on-disk taxonomy resolves, and the new empty taxonomy beside it will not
match them.

## Shapes

Each SHACL shape contributes one document profile.

```turtle
@prefix sh:   <http://www.w3.org/ns/shacl#> .
@prefix idx:  <urn:jena:lucene:index#> .
@prefix field: <urn:jena:lucene:field#> .

<#BookShape>
    sh:targetClass ex:Book ;
    sh:property [ idx:field field:title ; sh:path rdfs:label ] ;
    sh:property [ idx:field field:category ; sh:path ex:category ] ;
    sh:property [ idx:field field:authorName ; sh:path ( ex:authoredBy ex:name ) ] .
```

Each `sh:property` is an **occurrence**: it names a canonical field and the path that feeds
it. Pointing `sh:property` straight at a field resource is the old form and is rejected.

| Property | Required | Meaning |
|---|---|---|
| `sh:targetClass` | yes | Entity class this profile indexes. Repeatable |
| `sh:property` | yes¹ | A root field occurrence |
| `idx:nested` | yes¹ | A child collection — see [Nested Child Records](#nested-child-records) |
| `idx:facetHierarchy` | no | Ordered list of fields forming one hierarchical facet dimension |
| `idx:docIdField` | no, default `"uri"` | Lucene field holding the entity IRI. Must be identical across every profile in one index |
| `idx:discriminatorField` | no, default `"docType"` | Lucene field holding the target class's local name, so deletes stay scoped to one profile |

¹ A shape needs at least one of `sh:property` / `idx:nested`.

## Fields

A field is defined **once**, as a named resource, and carries **no path**. The path lives on
the occurrence that references it, which is what lets one field be fed from several paths and
several shapes.

```turtle
@prefix field: <urn:jena:lucene:field#> .
@prefix idx:   <urn:jena:lucene:index#> .

field:title
    idx:fieldName "title" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true .

field:category
    idx:fieldName "category" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

field:year
    idx:fieldName "year" ;
    idx:fieldType idx:IntField ;
    idx:facetable true ;
    idx:sortable true .

field:publishedOn
    idx:fieldName "publishedOn" ;
    idx:fieldType idx:TemporalField ;
    idx:facetable true ;
    idx:sortable true ;
    idx:storeLiteralMetadata true .   # not optional on TEMPORAL
```

Public API rule:

- External SPARQL uses the field IRI, for example `urn:jena:lucene:field#title`.
- Internal Lucene storage uses `idx:fieldName`.

`idx:fieldName` is not a public query-time identifier.

## Vector Fields

A vector field holds a dense embedding of the entity, searched by nearest-neighbour
similarity. It is the one field kind that is **derived rather than extracted**: it has no
`sh:path` of its own, and instead names the fields whose values are verbalised and
embedded.

```turtle
field:embedding
    idx:fieldName "embedding" ;
    idx:fieldType idx:VectorField ;
    idx:dimension 384 ;
    idx:similarity idx:Cosine ;              # default; also DotProduct, Euclidean, MaximumInnerProduct
    idx:embeddingSource ( field:title field:description ) .
```

Because it has no path, it attaches to a shape with `idx:vectorField` rather than through
an `sh:property` occurrence:

```turtle
:MiningReportShape
    sh:targetClass ex:MiningReport ;
    sh:property [ idx:field field:title ; sh:path rdfs:label ] ;
    sh:property [ idx:field field:description ; sh:path dcterms:description ] ;
    idx:vectorField field:embedding .
```

Source values are joined in declaration order, separated by `". "`. Order matters: changing
it changes every vector, and therefore requires a reindex.

An entity with no text in any source field gets **no vector at all** rather than an
embedding of the empty string. Lucene treats a missing KNN field as "not a candidate",
which is the right answer for an entity with nothing to be similar to; embedding the empty
string would instead place every such entity at one arbitrary point in the space, where
they surface as neighbours of each other.

### The embedding engine

One block per index names the provider:

```turtle
:index rdf:type text:TextIndexShacl ;
    # ...
    idx:knnTopK 100 ;                        # optional; neighbours retrieved (default 100)
    idx:embedding [
        idx:provider "jlama" ;
        idx:model "BAAI/bge-small-en-v1.5" ;
        idx:modelPath "/models" ;
        idx:option [ idx:optionName "queryPrefix" ; idx:optionValue "..." ] ;
    ] .
```

| Provider | Ships in | Notes |
|---|---|---|
| `hashing` | `jena-text` | Deterministic, no model, no download. Measures **lexical overlap, not meaning** — for testing the plumbing and for offline demos only |
| `onnx` | `jena-text-embeddings` (optional) | Real embeddings, via ONNX Runtime. **Use this one.** Any model with an ONNX export |
| `jlama` | `jena-text-embeddings` (optional) | **Broken — do not use.** See below |

#### `jlama` returns vectors that are not the model's output

It loads `BAAI/bge-small-en-v1.5` without error and reports the right dimension, but the
vectors are wrong. Against a reference BERT forward pass over the same `model.safetensors`
and the same token ids from Jlama's own (correct) tokenizer, its vector for a given text has
cosine **-0.06** with the correct one under its default `MODEL` pooling — 0.27 at best
across its four pooling modes. Unrelated documents come out closer than related ones, so
retrieval is noise.

This is not a configuration problem: the tokenizer is right, the `F32`/`I8` and `F32`/`F32`
working dtypes are bit-identical, every pooling mode is wrong, and the same weights behave
correctly under both the numpy reference and ONNX Runtime. Nothing throws — it is exactly
the silent "confident garbage" failure described under
[Model identity is a correctness boundary](#model-identity-is-a-correctness-boundary),
arriving through the engine rather than through a model mismatch.

`TestOnnxEmbeddingProvider.embeddingsMatchTheReferenceForwardPass` pins the correct numbers.

#### The `onnx` provider

```turtle
idx:embedding [
    idx:provider "onnx" ;
    idx:model "BAAI/bge-small-en-v1.5" ;
    idx:modelPath "models" ;                      # downloaded here on first run, then reused
    idx:option [ idx:optionName "pooling" ; idx:optionValue "cls" ] ;    # optional
] .
```

ONNX Runtime runs the forward pass; tokenisation reuses Jlama's `BertTokenizer`, which is
pure Java and was verified correct, so no second native dependency is needed. The runtime's
native libraries ship inside its jar for linux/macOS/windows on x86_64 and aarch64, and
**no `--add-modules jdk.incubator.vector` is required** — that was a Jlama constraint.

| Option | Default | Meaning |
|---|---|---|
| `pooling` | `cls` for `bge-*`, else `mean` | How token vectors collapse to one. Wrong value degrades retrieval silently |
| `queryPrefix` / `documentPrefix` | inferred from the model name | The asymmetric instructions BGE and E5 expect |

Model files are fetched from HuggingFace (`onnx/model.onnx` plus the tokenizer and config
JSON) into `idx:modelPath`. A directory that already holds all four is used as-is with no
network call, which is the intended production shape — bake the model into a derived image.
Not every model publishes an ONNX export; a missing one fails at startup with the URL.

Providers are discovered with `ServiceLoader`, so `jena-text` itself carries no ML
dependency and adding an engine is a jar on the classpath, not a class name in RDF. An
unknown provider name fails at startup listing the ones that are available.

**Model choice is constrained by the engine.** Jlama loads encoder-only BERT models, which
rules out the current leaders — Qwen3-Embedding (decoder), EmbeddingGemma (Gemma3 with
bidirectional attention), and ModernBERT-based models such as granite-embedding-r2. Within
BERT the practical options are `BAAI/bge-small-en-v1.5` (384-dim, the documented default),
`Snowflake/snowflake-arctic-embed-s` and `intfloat/e5-small-v2`.

### Model identity is a correctness boundary

Index-time and query-time embeddings must come from the same model. A mismatch does not
error — it returns plausible-looking garbage, which is worse. Two checks guard this:

- `idx:dimension` is compared against what the provider reports, at assembly time. A
  mismatch refuses to start.
- Lucene fixes a KNN field's dimension at index time, so changing model means a **full
  reindex** regardless.

Note that matching dimensions do not prove matching models: two different 384-dim models
will both pass the check and return nonsense. Keep the loader and server images pinned to
the same model artifact.

### Attributes that are refused on a vector field

Each of these fails *silently* if allowed through, so the config is rejected instead:

| Attribute | Why it is refused |
|---|---|
| `idx:facetable` | A vector has no discrete values to count |
| `idx:sortable` | A vector has no total order |
| `idx:multiValued` | One entity carries exactly one embedding |
| `idx:defaultSearch` | `"default"` selects fields for the Lucene query parser, which a vector has no part in. Name the field explicitly in `fieldSpec` |
| `idx:analyzer`, `idx:queryAnalyzer` | Tokenisation is the model's, not Lucene's |
| `idx:storeLiteralMetadata` | There is no literal |

`idx:dimension`, `idx:similarity` and `idx:embeddingSource` are likewise refused on any
field that is *not* an `idx:VectorField` — a config declaring them elsewhere is a config
whose author believes they have configured a vector.

Every `idx:embeddingSource` entry must be a field the shape actually populates. Naming an
unreachable field would otherwise mean every entity embeds the same partial text.

## Field Properties

These go on the **canonical field**, never on an occurrence.

| Property | Default | Meaning |
|---|---|---|
| `idx:fieldName` | required | Internal Lucene field name |
| `idx:fieldType` | `idx:TextField` | `idx:TextField`, `idx:KeywordField`, `idx:IntField`, `idx:LongField`, `idx:DoubleField`, `idx:TemporalField`, `idx:LatLonField` |
| `idx:stored` | `true` | Keep the value so `luc:match` / `luc:nestedMatch` can project it |
| `idx:indexed` | `true` | Write searchable terms/points — this is what makes the field **filterable** |
| `idx:facetable` | `false` | Write facet docvalues — required to appear in `luc:facet` |
| `idx:sortable` | `false` | Write sort docvalues — required for `ORDER BY` pushdown |
| `idx:multiValued` | `false` | Allow more than one value per entity |
| `idx:defaultSearch` | `false` | Included when `fieldSpec` is `"default"` |
| `idx:analyzer` | index-wide default | Index-time analyzer override (`TEXT`) |
| `idx:queryAnalyzer` | implied by `idx:analyzer` | Query-time analyzer override |
| `idx:normalizer` | none | `KEYWORD` only — analyzer driving the indexed term and sort key while the stored value and facet label stay raw |
| `idx:storeLiteralMetadata` | `false` | Store the datatype and language tag so a projected value rebuilds as the original literal. **Required on every `TEMPORAL` field** |

The four write-side flags are independent, and each buys exactly one capability:

| Flag | Structure written | Without it |
|---|---|---|
| `idx:indexed` | inverted terms / points | filters on the field are **silently dropped** — the clause becomes residual, is logged, and never applied |
| `idx:stored` | stored value | `luc:match` has nothing to project |
| `idx:facetable` | facet docvalues | faceting the field is an error |
| `idx:sortable` | sort docvalues | no sort pushdown |

Numeric and temporal fields take their facet/sort docvalues from `idx:facetable` **or** `idx:sortable`, independently of `idx:indexed` — so a numeric field can be facetable and sortable but not filterable. That is rarely what you want; see [10-suggested-configuration.md](10-suggested-configuration.md).

`idx:DateField` and `idx:DateTimeField` are accepted as deprecated aliases for `idx:TemporalField`.

### Occurrence properties

An occurrence binds a canonical field to a path. It carries the path and any value
constraints; it must not repeat the field's own metadata (`idx:fieldName`, `idx:fieldType`,
the flags, the analyzers), and the field must not carry occurrence data (`sh:path`,
`sh:class`, `sh:nodeKind`, `sh:datatype`). Both directions are rejected at config time, so
one field cannot end up with two different definitions.

| Property | Required | Meaning |
|---|---|---|
| `idx:field` | yes | The canonical field this occurrence feeds |
| `sh:path` | yes¹ | Direct, sequence, inverse, or alternative path from the entity (or, inside `idx:nested`, from the child node) |
| `idx:self` | yes¹ | Bind the focus node itself instead of a path from it — see [Indexing the focus node](#indexing-the-focus-node) |
| `sh:class` | no | Only index values whose reached node has this `rdf:type` |
| `sh:nodeKind` | no | Restrict to `sh:IRI`, `sh:Literal`, or `sh:BlankNode` |
| `sh:datatype` | no | Only index literals with this datatype |

¹ Exactly one of `sh:path` / `idx:self`. Neither is an error, and so is both.

Several occurrences may feed one field — that is how a value fans in from more than one
path. They must all resolve to the same canonical definition.

### Indexing the focus node

`idx:self true` feeds a field from the node the occurrence is evaluated against, rather
than from a path leading away from it. That node is the **entity** for a root occurrence
and the **child node** inside an `idx:nested` block.

```turtle
sh:property [ idx:field field:entityIri ; idx:self true ] ;
```

The value constraints still apply — they filter the focus node itself, so
`sh:nodeKind sh:IRI` on a self occurrence means "only index this child when it is an IRI".

A focus node is a resource, so a self-bound field must be `idx:KeywordField` or
`idx:TextField`; the numeric and temporal types have nothing to convert and are rejected at
config time. Blank nodes yield no value at all — their labels are not stable across a
reload, so indexing them would be meaningless — and the field is simply absent from that
document.

A self occurrence contributes no predicates to change tracking, which is correct: its value
cannot change without the entity or the join that reaches it changing, and both are already
tracked.

The main use is a nested block whose child node is itself one of the values you want —
see [Pattern 4](#pattern-4--hierarchy-whose-level-is-the-child-node).

## Choosing an Analyzer for a TEXT Field

A `TEXT` field is only as searchable as its analyzer, and picking the wrong one produces a
field that silently matches nothing rather than an error.

Start here, because most fields do not want an analyzer override at all:

| Goal | Configuration |
|---|---|
| Find entities by words in their text | `TEXT`, **no** `idx:analyzer` — the index-wide `StandardAnalyzer` applies and BM25 ranks the hits |
| Filter to one value of a closed vocabulary (a person, an organisation, a status) | `KEYWORD` + `idx:facetable`, filtered with `=` on a value the UI offers from the facet list |
| Complete a value the user is typing from memory (an identifier, a code) | `TEXT` + `idx:analyzer [ a text:EdgeNGramAnalyzer ]` |

Edge-n-grams are for the third row only. They are the wrong reach for a name: see
[Names want BM25, not n-grams](#names-want-bm25-not-n-grams).

### Edge-n-gram modes

```turtle
## Whole-value prefixes — "RPT-MIA" reaches "RPT-MIA-2023-001", "2023" does not
field:identifierValueText
    idx:fieldName "identifierValueText" ;
    idx:fieldType idx:TextField ;
    idx:analyzer [ a text:EdgeNGramAnalyzer ] .

## fed by an occurrence on the shape:
##   sh:property [ idx:field field:identifierValueText ; sh:path schema:value ]
```

`text:tokenized true` switches to per-word n-grams, so any word of a multi-word value can
be prefix-matched and multi-word input matches as an adjacent phrase. Use it when the
values are segmented and users search by the segment they remember — dashed identifiers,
mostly. `text:minGram` (default 1) and `text:maxGram` (default 20) bound the n-gram
lengths; in per-word mode the whole word is also indexed, so words longer than `maxGram`
remain searchable in full.

### The query analyzer is implied

Do not set `idx:queryAnalyzer` on an edge-n-gram field. Running the n-gram analyzer over
the *query* as well as the index turns every input into a pile of prefixes and matches far
too much, so the field's mode selects its partner automatically:

| `idx:analyzer` | Implied `idx:queryAnalyzer` |
|---|---|
| `text:EdgeNGramAnalyzer` | `text:LowerCaseKeywordAnalyzer` — the input is one lowercased term |
| `text:EdgeNGramAnalyzer` with `text:tokenized true` | `text:StandardAnalyzer` — the input is split into words the same way the index was |
| anything else | none; the analyzer is its own correct query-side partner |

An explicit `idx:queryAnalyzer` still wins, for the cases these defaults do not cover.

### Choosing between whole-value and per-word

For a value with no punctuation or spaces — `"A9412"` — the two n-gram modes behave
identically. They diverge as soon as the value has more than one word or segment:

| Value | Input | whole-value | per-word |
|---|---|---|---|
| `Dr Sarah Jones` | `Dr Sar` | ✅ | ✅ |
| `Dr Sarah Jones` | `Jones` | ❌ | ✅ |
| `Dr Sarah Jones` | `Sarah Jones` | ❌ | ✅ |
| `RPT-MIA-2023-001` | `RPT-MIA` | ✅ | ✅ |
| `RPT-MIA-2023-001` | `2023` | ❌ | ✅ |

Whole-value is right when a value must be matched as an indivisible string and a mid-value
fragment should *not* hit. Per-word is right when the value has internal structure the user
navigates by.

`TestTypeaheadFieldConfigurations` exercises every row of both tables.

### Names want BM25, not n-grams

The tempting configuration for a person's name is a per-word edge-n-gram twin, so that
typing `Jones` completes `Dr Sarah Jones`. Do not do this. It costs:

- **Relevance.** Every value expands to ~10–20 tokens, IDF over prefixes is noise (`"s"`
  occurs everywhere) and length norms inflate. BM25 on the field stops meaning anything,
  which matters the moment it joins `idx:defaultSearch`.
- **Index size**, several times over, for every name in the corpus.
- **Wrong multi-word semantics.** `text_query` builds a phrase query, so `"Jones Sarah"`
  misses. Right for completion, wrong for someone searching a name.

And it still does not buy typo tolerance — `"Jonse"` fails under n-grams exactly as it
fails under BM25.

A plain `TEXT` field with no analyzer override does what a name filter actually needs.
`text_query` tokenises the input the same way the field was indexed, so `"Jones"` and
`"Sarah Jones"` both reach `"Dr Sarah Jones"`, ranked by BM25. Pair it with a `KEYWORD`
twin when you also want exact filtering and facet counts:

```turtle
## Exact side: equality filters and facet counts
field:authorName
    idx:fieldName "authorName" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

## Search side: BM25 over the same name, no analyzer override
field:authorNameText
    idx:fieldName "authorNameText" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true .

## Two occurrences, one path — the twin fields diverge on analysis, not on source:
<#MiningReportShape>
    sh:property [ idx:field field:authorName     ; sh:path ( ex:authoredBy ex:name ) ] ;
    sh:property [ idx:field field:authorNameText ; sh:path ( ex:authoredBy ex:name ) ] .
```

This holds inside an `idx:nested` scope too: a plain `TEXT` field there still folds
same-child with its sibling clauses, so "role = Principal Investigator AND agent matches
Sarah Jones" correlates onto one attribution record with no n-grams involved.
`TestCorrelatedNestedAttribution` covers it.

#### What about completion?

A picklist is worth it only when the vocabulary is genuinely small and fixed — a set of
roles or statuses, not a set of people. Completing a name means either shipping the
vocabulary to the client, which stops being viable somewhere in the low thousands, or a
query per keystroke. Prefer BM25 on whole words until there is a suggestion API worth
using; the natural one here is a prefix filter over facet values, evaluated in the facet
collector, which reuses the dictionary the index already has and respects the current
filter state.

An identifier is the opposite case: an open, near-unique vocabulary with no useful counts,
which the user types verbatim and expects to be completed. That is what edge-n-grams are
for.

## Paths

Direct path:

```turtle
sh:path rdfs:label .
```

Sequence path:

```turtle
sh:path ( ex:authoredBy ex:name ) .
```

Inverse path:

```turtle
sh:path [ sh:inversePath ex:authored ] .
```

## Nested Child Records

`idx:nested` declares a repeated child collection on a shape. Each child becomes its own Lucene doc inside the entity's block; clauses targeting the same nested scope can be combined with same-child correlation at query time.

`idx:joinPath` enumerates child nodes from the parent. Field occurrences inside the `idx:nested` block are evaluated relative to the child node, not the parent.

### Block properties

| Property | Required | Meaning |
|---|---|---|
| `idx:joinPath` | yes¹ | SHACL path enumerating child nodes from the parent |
| `idx:externalSource` | yes¹ | Children come from a tabular source instead — see [External Content](#external-content-csvtsv) |
| `idx:property` | yes² | Repeatable field occurrence, evaluated relative to the child node |
| `idx:nestedName` | see below | The block's **scope name** |
| `idx:facetHierarchy` | no | Hierarchy whose levels are correlated per child record |

¹ Exactly one of `idx:joinPath` / `idx:externalSource`.
² For a `idx:joinPath` block. An external block's fields come from its `idx:column` bindings instead, and `idx:property` there is an error.

**The scope name** identifies the block. It is written to every child document as
`_nestedScope`, and it is what lets the query path tell "these clauses must be satisfied by
*one* child" from "any children will do", recover a child's field definitions when
projecting `luc:nestedMatch`, and restrict a nested sort selector to the right block.

- For an `idx:joinPath` block the name is **derived from the path** — you do not write it,
  and an `idx:nestedName` there is ignored.
- For an `idx:externalSource` block it is **required**: there is no join path to derive it
  from. Omitting it is a config-time error.

It is an opaque label, not an IRI: nothing in the data denotes it, no query names it (the
scope is always inferred from the field), and the derived form is a path string. Pick
something short and descriptive. Because it lands in the index, renaming it invalidates
existing child documents — reindex after a change.

### Pattern 1 — Qualified identifier (both children are KEYWORD)

`schema:identifier` records carrying `(propertyID, value)` pairs:

```turtle
field:identifierType
    idx:fieldName "identifierType" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

field:identifierValueExact
    idx:fieldName "identifierValueExact" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

<#BoreholeShape>
    sh:targetClass ex:Borehole ;
    idx:nested [
        idx:joinPath schema:identifier ;
        idx:property [ idx:field field:identifierType ; sh:path schema:propertyID ] ;
        idx:property [ idx:field field:identifierValueExact ; sh:path schema:value ] ;
        idx:facetHierarchy ( field:identifierType field:identifierValueExact ) ;
    ] .
```

Inside a nested block the occurrence paths are relative to the **child** node, so
`schema:propertyID` here is read from each `schema:identifier` record, not from the borehole.

Query-time same-child correlation is not limited to `=`. AND-ed leaves that target the same nested scope fold into one block join, so `=`, ranges (`<`, `>`, `<=`, `>=`), `in`, `between`, `like` and `text_query` all correlate within a single child — see [02-sparql-api.md → Nested same-child filters](02-sparql-api.md#nested-same-child-filters).

### Pattern 2 — Identifier with text/typeahead on a child field

Add a second occurrence of the value field with an analyzer-backed `TEXT` field. The exact and text fields share the SHACL path but produce different Lucene fields:

```turtle
field:identifierValueText
    idx:fieldName "identifierValueText" ;
    idx:fieldType idx:TextField ;
    idx:analyzer [ a text:EdgeNGramAnalyzer ; text:tokenized true ] .

<#BoreholeShape>
    sh:targetClass ex:Borehole ;
    idx:nested [
        idx:joinPath schema:identifier ;
        idx:property [ idx:field field:identifierType ; sh:path schema:propertyID ] ;
        idx:property [ idx:field field:identifierValueExact ; sh:path schema:value ] ;
        idx:property [ idx:field field:identifierValueText ; sh:path schema:value ] ;
        idx:facetHierarchy ( field:identifierType field:identifierValueExact ) ;
    ] .
```

At query time, combine `=` on `identifierType` with `text_query` on `identifierValueText` in the same CQL subtree (see [02-sparql-api.md](02-sparql-api.md#text_query--analyzer-aware-text-matching)).

The exact and text fields can coexist on the same child path — index-time, each value writes both a raw keyword term and the analyzed tokens to its child doc.

### Pattern 3 — Qualified attribution (prov)

`prov:qualifiedAttribution` records carrying `(hadRole, agent)`:

```turtle
field:attributionRole
    idx:fieldName "attributionRole" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

field:attributionAgentExact
    idx:fieldName "attributionAgentExact" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

<#MiningReportShape>
    sh:targetClass ex:MiningReport ;
    idx:nested [
        idx:joinPath prov:qualifiedAttribution ;
        idx:property [ idx:field field:attributionRole ; sh:path ( prov:hadRole rdfs:label ) ] ;
        idx:property [ idx:field field:attributionAgentExact ; sh:path ( prov:agent rdfs:label ) ] ;
        idx:property [ idx:field field:attributionAgentText ; sh:path ( prov:agent rdfs:label ) ] ;
    ] .
```

Role is a fixed vocabulary, so it filters with `=`. The agent name filters with
`text_query` against the plain `TEXT` twin: `"Sarah Jones"` reaches `"Dr Sarah Jones"`,
and the two clauses still fold same-child, so a report surfaces only when one attribution
record carries both. The `KEYWORD` twin stays for exact filtering and facet counts.

No n-gram twin on the agent, on purpose — see
[Names want BM25, not n-grams](#names-want-bm25-not-n-grams).
`TestCorrelatedNestedAttribution` pins the correlation for `=`, for `text_query` on a
plain field, and for the n-gram configurations that are permitted but not advised.

### Pattern 4 — Hierarchy whose level is the child node

A taxonomy is often attached to a *term* rather than to the entity: the entity references
some term, and the term belongs to a broader grouping. Faceting on both, correlated, needs
the term itself as a hierarchy level.

```turtle
# instance data — an entity may reference several display tables
ex:doc1  ex:hasDisplayTable  ex:borehole , ex:downholeAssays .

# vocabulary — each display table belongs to a grouping
ex:borehole        ex:hasGrouping  ex:Holes .
ex:downholeAssays  ex:hasGrouping  ex:Holes .
ex:geochemResults  ex:hasGrouping  ex:Geochemistry .
```

```turtle
<#DocumentShape>
    sh:targetClass ex:Document ;
    idx:nested [
        idx:joinPath ex:hasDisplayTable ;
        idx:property [ idx:field field:displayTable ; idx:self true ] ;
        idx:property [ idx:field field:grouping     ; sh:path ex:hasGrouping ] ;
        idx:facetHierarchy ( field:grouping field:displayTable ) ;
    ] .
```

The child node **is** the display table, so its occurrence binds the focus node; the
grouping is one step off it. Each child record carries one display table and its grouping,
so the hierarchy is pairwise correct: a document referencing display tables in two
different groupings produces exactly its two real paths.

Declaring the two fields as **root** occurrences instead — `sh:path ex:hasDisplayTable` and
`sh:path ( ex:hasDisplayTable ex:hasGrouping )` — would read each level's values
independently and emit the cartesian product of the two, including
`Holes / geochemResults`, which is not in the data. Drilling into a grouping would then
offer terms that are not in it, and the children would out-count the parent.

Both fields are child-scoped here, which is what makes them correlated, and it is also
what determines how they behave elsewhere: they filter same-child, and they are not
available as entity-level flat facets. If you also want a flat facet over the term across
all entities, declare a **separate field** with its own name fed by a root occurrence on
`ex:hasDisplayTable`. One field cannot be both — see the first rule below.

### Rules

- One field IRI belongs to one scope: either root or one nested collection.
- Scope names are resolved across the whole mapping, so an explicit `idx:nestedName` must be unique among all `idx:nested` blocks in the index, not just within its shape.
- `idx:joinPath` may be a simple predicate, an inverse predicate, or a sequence of predicate steps. It does not support alternative paths.
- Both the exact-keyword and edge-ngram-text variants can sit on the same SHACL path — they are different Lucene fields driven by their own analyzers.
- `idx:facetHierarchy` inside an `idx:nested` block defines a hierarchy whose levels are correlated per child record (no cartesian products). On a shape, the levels are read independently from the entity and cross-produced — correct when they are independent, wrong when they are pairwise related, which is what [Pattern 4](#pattern-4--hierarchy-whose-level-is-the-child-node) is for.
- `idx:self` binds the focus node: the child node inside `idx:nested`, the entity at root. It replaces `sh:path` rather than accompanying it, and is not available on an `idx:column` — an external child is a row, not a node.
- A field named in an `idx:facetHierarchy` keeps its own flat facet dimension. Faceting on the field IRI returns that field's counts across all parents; faceting on the hierarchy's dimension name returns its top level, or the children of a drill-down path. The two are addressed separately and neither shadows the other.
- Faceting on a field that is not `idx:facetable` is an error — there is no dimension to answer from.

## External Content (CSV/TSV)

An `idx:nested` block can draw its children from a **tabular file** instead of the graph, joined to the entity on the entity IRI. Use it when an attribute set is large, authoritative somewhere else, and needed only as search machinery — range filters, range facets and sort — with the values themselves retrieved from the source of truth.

Design note: [2026-07-27_external_content_indexing_design.md](2026-07-27_external_content_indexing_design.md).

A nested block has **either** `idx:joinPath` **or** `idx:externalSource`, never both.

### Configuration

```turtle
field:measuredProperty
    idx:fieldName "measuredProperty" ;
    idx:fieldType idx:KeywordField ;
    idx:indexed   true ;
    idx:facetable true ;
    idx:stored    true .        # short, non-volatile label — safe to store

field:measuredValue
    idx:fieldName "measuredValue" ;
    idx:fieldType idx:DoubleField ;
    idx:indexed   true ;        # range filters -> DoublePoint
    idx:facetable true ;        # range facets
    idx:sortable  true ;        # sort selector -> docvalues
    idx:stored    false .       # values live in the source of truth

<#SampleShape>
    sh:targetClass ex:Sample ;
    sh:property [ idx:field field:sampleName ; sh:path ex:name ] ;

    idx:nested [
        idx:nestedName "measurement" ;
        idx:externalSource [
            idx:format        idx:CsvFile ;
            idx:location      "/data/measurements.csv" ;
            idx:subjectColumn "sample_iri" ;
            idx:column [ idx:columnName "property" ; idx:field field:measuredProperty ] ;
            idx:column [ idx:columnName "value" ;    idx:field field:measuredValue ] ;
        ] ;
        idx:facetHierarchy ( field:measuredProperty field:measuredBand ) ;
    ] .
```

Bound fields carry **no `sh:path`** — their values come from the column. There is no `idx:external` flag: a field is external because a column binds it, exactly as a field is nested because it appears in an `idx:nested` block.

`idx:nestedName` is required here, and only here: with no `idx:joinPath` there is nothing to derive the block's scope name from. See [Block properties](#block-properties).

### Source properties

| Property | Required | Meaning |
|---|---|---|
| `idx:format` | yes | `idx:CsvFile` or `idx:TsvFile` |
| `idx:location` | yes | Path, or a glob such as `/data/meas-*.csv` (read in filename order) |
| `idx:subjectColumn` | yes¹ | Column holding the entity IRI, or the key to be prefixed |
| `idx:subjectColumnIndex` | yes¹ | Zero-based subject column, when `idx:headerless` is true |
| `idx:subjectPrefix` | no | String prepended to the subject column value. Concatenation only |
| `idx:delimiter` | no | Single-character delimiter override |
| `idx:headerless` | no | No header row; bind columns with `idx:columnIndex`. Default `false` |
| `idx:onError` | no | `"skip"` (default, counted) or `"fail"` |
| `idx:column` | yes | Repeatable binding: `idx:columnName` **or** `idx:columnIndex`, plus `idx:field` |
| `idx:delta` | no | Delta file(s) applied over the base at build time. Several must be an ordered list |
| `idx:opColumn` | no | Column holding `ADD`/`DELETE` in a delta. Default `"op"` |

¹ `idx:subjectColumn` with a header, `idx:subjectColumnIndex` when headerless.

Columns may bind `TEXT`, `KEYWORD`, `INT`, `LONG` and `DOUBLE` fields. `TEMPORAL` and `LATLON` are rejected at config time — they need literal metadata or WKT handling a bare cell cannot carry unambiguously.

### Input shape

One row per measurement, joined on the entity IRI:

```
sample_iri,property,value
https://ex.org/sample/A1,Au,12.4
https://ex.org/sample/A1,Cu,0.7
https://ex.org/sample/A2,Au,0.3
```

Each row becomes one child document. Two field IRIs cover any number of measured properties, and a new property in the source needs no config change.

### Wide children — one row is one event

`idx:column` is repeatable without limit, and **every bound column lands on the same child document**. Bind four and the child stops being a property/value pair and becomes the measurement event itself:

```turtle
idx:column [ idx:columnName "depth_from" ; idx:field field:depthFrom ] ;
idx:column [ idx:columnName "depth_to" ;   idx:field field:depthTo ] ;
idx:column [ idx:columnName "analyte" ;    idx:field field:analyte ] ;
idx:column [ idx:columnName "value" ;      idx:field field:value ] ;
```

```
hole_iri,depth_from,depth_to,analyte,value
https://ex.org/hole/A1,0,10,Au,12.4
https://ex.org/hole/A1,0,10,Cu,0.7
https://ex.org/hole/A1,10,20,Au,0.5
```

All four fields then correlate in one block join — the same-scope fold groups every AND-ed leaf in a nested scope with no arity limit:

```json
{"op":"and","args":[
  {"op":"=", "args":[{"property":"urn:jena:lucene:field#analyte"},"Au"]},
  {"op":">=","args":[{"property":"urn:jena:lucene:field#value"},1.0]},
  {"op":">=","args":[{"property":"urn:jena:lucene:field#depthFrom"},0]},
  {"op":"<=","args":[{"property":"urn:jena:lucene:field#depthTo"},10]}
]}
```

"Au above 1 g/t in the 0–10 m interval" — one child, exact. A hole with a Cu result at 0–10 m *and* a separate interval starting at 10 m does **not** match `analyte = "Cu" AND depthFrom >= 10`, because no single child satisfies both.

**The grain of the row sets what correlates.** Widening the child moves the boundary; it does not remove it. Two analytes are still two rows, so "Au and Cu in the *same* interval" remains unanswerable as a same-child query — `analyte = "Au" AND analyte = "Cu"` matches nothing, since one child has one analyte. If that question matters, the row must carry both analytes as separate columns.

**Every bound column must be populated on every row.** A row missing any one bound cell is dropped whole (see the rules below), which is a stricter constraint on a four-column extract than on a two-column one.

### Sort order

There is nothing to configure. The build needs external rows grouped and ascending by
subject — Lucene has no partial document update, so all of an entity's children must be
in hand before its block join is written — and it establishes that order itself.

Rows are read, sorted by subject and, if there are more than fit in memory, spilled to
temp files and merged. An input small enough to buffer never touches disk. Memory is
bounded by the buffer rather than the input, so a source of any size is safe, and the
export order of the source does not affect the result.

This used to be the `idx:sorted` assertion, which required pre-sorting the file with
`LC_ALL=C sort`. The pitfall was that byte order and the obvious `ORDER BY` rarely
agree: exporting with `ORDER BY collar_id` on an integer column yields `1175968` before
`117597`, which is numerically right and lexically wrong. That is no longer something
anyone has to know.

The sort is stable, so rows sharing a subject keep their input order — which matters
because duplicate `(subject, property)` rows are legal.

`jena.text.external.sortBufferRows` (default 200,000) sets how many rows are held before
spilling. It is a tuning knob for memory-constrained hosts, not something a normal
deployment sets.

### Deltas

A delta file carries only what changed. It is applied over the base at build time, so
the indexer still sees each entity's complete child set — which is what it needs, since
a Lucene block is written whole and there is no partial document update.

```turtle
idx:delta ( "data/2026-07-a.csv" "data/2026-07-b.csv" ) ;   # applied in this order
idx:opColumn "op" ;                                          # default
```

Same columns as the base, plus an operation column:

```
op,borehole,analyte,grade,units,below_detection
DELETE,http://ex.org/bh-1,Ag,44.9,ppm,f
ADD,http://ex.org/bh-1,Ag,51.3,ppm,f
DELETE,http://ex.org/bh-2,Mn,,,
```

| | |
|---|---|
| `DELETE` | matches on the columns it fills in; an **empty cell is a wildcard**. The third row above removes *every* Mn measurement of `bh-2` |
| Numeric matching | by value, not lexical form — `0.70` deletes `0.7` |
| `ADD` | **appends**; it is not an upsert, because duplicate rows for the same property are legal and so there is no key to upsert on. Replace = DELETE then ADD |
| Ordering | deletes apply before adds within a subject, so row order in the file cannot change the outcome |
| Unmatched delete | counted and logged, not an error — deltas get replayed and overlap |

Deltas require a header row, because the operation column is bound by name. They do
not require the base or the delta to be in any particular order — see
[Sort order](#sort-order). Several deltas must be given as an RDF **list** —
they apply in order and RDF puts no order on repeated properties.

This is still a **full rebuild**; the delta removes the need to physically merge base
and deltas into a new snapshot first, not the need to rebuild. Rebuilding only the
affected entities is future work.

### Rules and limits

- **Bulk build only.** `ShaclBulkIndexer` is the only path that populates external children. A live graph change to an entity of such a shape is refused with a warning — rebuilding from the graph alone would silently strip its children, and Lucene has no partial document update. Re-run the bulk indexer.
- **Rows augment entities; they never create them.** A row whose subject matches no entity is counted and dropped. The graph and the extract are equally valid sources that we align, and neither is obliged to cover the other, so the indexer never judges how well they overlap. Match and unmatch counts and the resulting match rate are always logged — a wrong `idx:subjectPrefix` shows up as a near-zero rate. Fix it in the data, not with a threshold.
- **An entity with no rows is still indexed**, with its graph fields and no children.
- **A row with an empty or unparseable bound cell is dropped whole**, never coerced to `0` and never emitted as a half-populated child.
- **No transformations.** No units, no `<0.5` detection-limit markers, no null sentinels, no computed columns. A cell either parses as its declared type or it is an error. That work belongs upstream, in whatever produced the file.
- **`idx:stored false` costs the value binding only.** Filters, range facets and sort all still work; `luc:match` has nothing to return. Note this removes *display* staleness, not filter staleness — the index is still a snapshot, so rebuild cadence must match the source's release cadence.
- **Same-child correlation is per row.** Every bound column of a row lands on one child, and all of them correlate. Clauses spanning *two* rows are two block joins ANDed at the entity: "has some Au above 0.5 **and** some Cu above 100", not "in the same measurement event". See [Wide children](#wide-children--one-row-is-one-event).

## Multi-Index Notes

Multiple indexes are useful when corpora differ materially:

- different analyzers
- different field sets
- different update cadence
- operational separation

At query time:

- `indexSelector` picks the index
- `fieldSpec` is resolved against that selected index
- sort and filter field IRIs must exist in that selected index

## Sort Configuration

Sort specs use field IRIs in SPARQL:

```json
{"field":"urn:jena:lucene:field#year","order":"desc"}
```

But the underlying Lucene field key still comes from `idx:fieldName`.

## Example

```turtle
@prefix text: <http://jena.apache.org/text#> .
@prefix idx:  <urn:jena:lucene:index#> .
@prefix sh:   <http://www.w3.org/ns/shacl#> .
@prefix field: <urn:jena:lucene:field#> .

<#dataset> a text:TextDataset ;
    text:dataset <#baseDs> ;
    text:indexes <#index> .

<#index> a text:TextIndexShacl ;
    text:indexId "default" ;
    text:directory "mem" ;
    text:storeValues true ;
    text:shapes ( <#BookShape> ) .

field:title
    idx:fieldName "title" ;
    idx:fieldType idx:TextField ;
    idx:defaultSearch true .

field:category
    idx:fieldName "category" ;
    idx:fieldType idx:KeywordField ;
    idx:facetable true .

<#BookShape>
    sh:targetClass ex:Book ;
    sh:property [ idx:field field:title ; sh:path rdfs:label ] ;
    sh:property [ idx:field field:category ; sh:path ex:category ] .
```

## Vocabulary Index

`idx:` is `urn:jena:lucene:index#` — the same namespace SPARQL prefixes as `luc:`. The
convention is `idx:` for configuration and `luc:` for the property functions; nothing
enforces it.

Every term the assembler reads, and where it is defined above:

| Term | Goes on | Section |
|---|---|---|
| `idx:field` | occurrence, `idx:column` | [Occurrence properties](#occurrence-properties) |
| `idx:self` | occurrence | [Indexing the focus node](#indexing-the-focus-node) |
| `idx:fieldName` `idx:fieldType` | canonical field | [Field Properties](#field-properties) |
| `idx:stored` `idx:indexed` `idx:facetable` `idx:sortable` `idx:multiValued` `idx:defaultSearch` | canonical field | [Field Properties](#field-properties) |
| `idx:analyzer` `idx:queryAnalyzer` `idx:normalizer` | canonical field | [Field Properties](#field-properties) |
| `idx:storeLiteralMetadata` | canonical field | [Field Properties](#field-properties) |
| `idx:docIdField` `idx:discriminatorField` | shape | [Shapes](#shapes) |
| `idx:facetHierarchy` | shape, `idx:nested` | [Shapes](#shapes), [Block properties](#block-properties) |
| `idx:nested` | shape | [Nested Child Records](#nested-child-records) |
| `idx:joinPath` `idx:nestedName` `idx:property` | `idx:nested` | [Block properties](#block-properties) |
| `idx:externalSource` | `idx:nested` | [External Content](#external-content-csvtsv) |
| `idx:format` `idx:location` `idx:subjectColumn` `idx:subjectColumnIndex` `idx:subjectPrefix` `idx:delimiter` `idx:headerless` `idx:onError` `idx:column` `idx:delta` `idx:opColumn` | `idx:externalSource` | [Source properties](#source-properties) |
| `idx:columnName` `idx:columnIndex` | `idx:column` | [Source properties](#source-properties) |
| `idx:vectorField` | shape | [Vector Fields](#vector-fields) |
| `idx:dimension` `idx:similarity` `idx:embeddingSource` | canonical field (VECTOR only) | [Vector Fields](#vector-fields) |
| `idx:embedding` `idx:knnTopK` | index | [The embedding engine](#the-embedding-engine) |
| `idx:provider` `idx:model` `idx:modelPath` `idx:option` | `idx:embedding` | [The embedding engine](#the-embedding-engine) |
| `idx:optionName` `idx:optionValue` | `idx:option` | [The embedding engine](#the-embedding-engine) |

Resources rather than properties:

| Term | Used as |
|---|---|
| `idx:TextField` `idx:KeywordField` `idx:IntField` `idx:LongField` `idx:DoubleField` `idx:TemporalField` `idx:LatLonField` `idx:VectorField` | `idx:fieldType` value |
| `idx:Cosine` `idx:DotProduct` `idx:Euclidean` `idx:MaximumInnerProduct` | `idx:similarity` value |
| `idx:DateField` `idx:DateTimeField` | deprecated aliases for `idx:TemporalField` |
| `idx:CsvFile` `idx:TsvFile` | `idx:format` value |

Property function IRIs in the same namespace: `luc:query`, `luc:facet`, `luc:match`,
`luc:nestedMatch` — see [02-sparql-api.md](02-sparql-api.md). Note that `luc:nestedMatch` is
deliberately **not** named `luc:nested`: that IRI is already the `idx:nested` config
predicate, and a property function registered on it would intercept any query reading a
config graph.

Terms that do **not** exist, despite appearing in older design notes: `idx:external`
(externality follows from an `idx:column` binding), `idx:sorted` (asserting a pre-sorted
source is not implemented; the loader sorts), and `idx:path` (use `sh:path`).
