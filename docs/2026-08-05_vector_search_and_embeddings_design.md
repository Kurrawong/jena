---
title: "vector search and embeddings"
date: "2026-08-05"
---

# 2026-08-05 Vector Search and Embeddings

## Status

**Partly built, 2026-08-10.** The text-embedding path described here is implemented; the
external-source (CSV) path and every structural-embedding idea remain design-only.

| Part of this note | State |
|---|---|
| `FieldType.VECTOR`, `KnnFloatVectorField` on the entity doc | **Built** |
| Filtered KNN via `luc:query`'s `fieldSpec` | **Built** |
| Facets and `?totalHits` scoped to top-k | **Built**, see [Facet semantics](#facet-semantics-change-under-knn) |
| Tier 1 — query-time embedding in process | **Built** |
| Tier 2 — document embedding at index time | **Built**, synchronously, not deferred as this note proposed |
| `EmbeddingProvider` SPI + `jena-text-embeddings` module | **Built** |
| Jlama provider | **Built, unverified against a real model** |
| Tier 0 — vectors supplied via CSV / external row sources | Not built |
| graph2vec, RDF2Vec, node2vec and friends | Rejected, see below |
| Hybrid retrieval / RRF | Not built; mixed vector+text `fieldSpec` is refused rather than answered badly |

Three decisions changed in the building:

**Tier 2 was not deferred.** This note argued for the loader owning document embedding,
on the pgvector precedent that nobody does synchronous in-transaction embedding. In
practice the change listener already rebuilds the whole entity document on any relevant
triple change, and embedding is one more field construction in that path. The async queue
and stale-vector marker are still the right answer at scale, and are still unbuilt — the
cost is currently paid synchronously on commit.

**Verbalisation is explicit config, not convention.** The open question below asked which
properties feed the embedded text. The answer is `idx:embeddingSource`, an ordered list of
field IRIs on the vector field. Order is part of the contract because changing it changes
every vector.

**No `luc:knn`, and no new argument.** The other open question. Naming a vector field in
the existing `fieldSpec` was chosen: it leaves the arity alone and every existing feature —
CQL filters, sort, paging, facets — keeps working with no per-feature plumbing.

### The engine choice needs revisiting

**Jlama is implemented but has not been run against a real model** — the environment the
work was done in has no route to HuggingFace. Everything downstream of the provider is
tested against a deterministic hashing provider; the Jlama forward pass is not.

More importantly, the constraint that drove this note's comparison table turns out to bite
harder than expected: **Jlama's embedding path loads encoder-only BERT models**. That
excludes every 2025–26 leader — Qwen3-Embedding (decoder), EmbeddingGemma (Gemma3 with
bidirectional attention), granite-embedding-r2 (ModernBERT). The best available choice is
therefore a 2023–24 BERT model (`bge-small-en-v1.5`), not because it is the best embedding
model available in 2026 but because it is the best one this engine can load.

That is a real argument for the ONNX/LangChain4j fallback, and the reason the
`EmbeddingProvider` SPI exists: `jena-text` knows only the interface and a `ServiceLoader`
name, so a second engine is a new module and a changed config string, with no change to
the index or query code.

## Summary

Add entity-level dense vectors to the SHACL entity-per-document index, so a Lucene KNN
query can be combined with the existing SHACL-typed filters and facet counts in a single
SPARQL query.

The differentiating claim is not "we have embeddings". It is:

> semantic search, filtered by typed structured constraints, with facet counts, in one
> SPARQL query

Standalone vector databases are weak at the filtering and facet half of that. pgvector is
weak at the filtering half specifically (see [Prior art](#prior-art-what-the-pgvector-ecosystem-settled-on)).
Lucene pushes the filter into the HNSW traversal, and this repo already has the SHACL
typing and the facet engine. That combination is the reason to build this here rather
than bolt a vector store alongside.

## What the vector represents

**An entity, not a triple.**

SHACL mode is entity-per-document, so there is exactly one natural place for a
`KnnFloatVectorField`: alongside the existing TEXT/KEYWORD/INT/LONG/DOUBLE/TEMPORAL/LATLON
fields on the entity document. A triple-level vector has nowhere to live in that model,
and in classic triple-per-document mode it would be both unwieldy and useless for
faceting.

### Rejected: graph2vec

graph2vec (Narayanan et al., 2017) is a doc2vec analogue where the *document* is an entire
graph and the *words* are rooted Weisfeiler–Lehman subtree patterns. It was built for graph
*classification* over datasets of many small graphs — molecules, program dependency graphs.
Applied to an RDF store it produces **one vector for the whole dataset**. That is not a
search feature. The name misleads; people reaching for it usually mean node embeddings.

### Rejected: structural embeddings generally

RDF2Vec, node2vec, TransE/ComplEx/RotatE, R-GCN/CompGCN. These embed entities by graph
position, and fail here for two independent reasons:

**No query-side encoder.** A user types a search string. That string is not an entity in
the graph, so nothing maps it into the embedding space. Structural embeddings support
*"more like this entity"* — item-to-item navigation — and never free-text search.

**No incremental update story.** They are globally trained. One added triple in principle
perturbs the entire space, and new entities are out-of-vocabulary until retraining. That
is an offline batch artifact, incompatible with a live change listener.

They remain plausible later as a reranking signal or a related-entities feature. Not v1.

### Chosen: text embeddings of verbalised entities

Verbalise the entity from its SHACL-declared properties, embed the text. Query and
document land in the same space, so free-text semantic search works, and per-entity
re-embedding on change is tractable.

## What exists today

| Surface | State |
|---|---|
| Lucene version | **10.3.1** (`ver.lucene`, root pom). Filtered KNN, HNSW, scalar quantization all available |
| `FieldType` enum | `TEXT, KEYWORD, INT, LONG, DOUBLE, TEMPORAL, LATLON` — no `VECTOR` (`ShaclIndexMapping.java:42`) |
| Field construction | `addNodeFieldToDoc` / `addFieldToDoc` switch on `FieldType` (`ShaclTextIndexLucene.java:1152`, `:1264`) — the single insertion point |
| External row sources | `ExternalRowSource` / `CsvRowSource` already key rows by subject IRI with an optional prefix (`CsvRowSource.java:215-220`) |
| Embedding code | None |
| Vector field | None |

The external row source finding matters: a CSV of *document IRI → embedding* is already
the exact shape `CsvRowSource` produces. The only missing piece on the document side is a
field type for the binding to land in.

### Vector API state

Verified 2026-08-05 by running the JDK launcher directly.

| Context | `jdk.incubator.vector` | Where |
|---|---|---|
| Runtime Docker image | **Enabled** | `build-files/docker/Dockerfile:105` ENTRYPOINT |
| `jena-text` tests | **Enabled** | auto-activating profile, JDK 21–25 (`jena-text/pom.xml:36-43`) |
| Loader Docker image | **Enabled 2026-08-05** for the text indexer only | `loader-entrypoint.sh` — was off; see [Prerequisites](#prerequisites) for why it is not blanket |
| Documented local run | **Not enabled** | CLAUDE.md's `java -jar ...` has no flags |

Two notes on reading this:

The startup warning `WARNING: Using incubator modules: jdk.incubator.vector` means the
module **is** enabled. It is the opposite of Lucene's `Java vector incubator module is not
readable...` warning. They are easy to confuse and mean opposite things.

The runtime ENTRYPOINT places `--add-modules` *after* `-jar`, which looks wrong. It was
tested and works — the launcher accepts VM options there and takes the first non-option
argument as the jar. Do not "fix" it.

## Prerequisites

**The loader must get the Vector API flags.** `loader-entrypoint.sh` builds every command
from `JAVA_OPTS`, which defaults to empty — including `shacltextindexer` (line 21), the
actual index builder. Today the cost is modest. With KNN it is large, because HNSW *graph
construction* is dominated by distance computations and happens in the indexer, not the
server. For Jlama it is disqualifying: Jlama's entire performance model is the Vector API.

**Fixed 2026-08-05**, but not the obvious way. Defaulting `JAVA_OPTS` to carry the flags —
the first thing tried — breaks the loader.

Enabling an incubator module makes the JVM print `WARNING: Using incubator modules:
jdk.incubator.vector` to **stderr at startup**. The location-discovery commands merge
stderr into stdout and parse the result positionally:

```sh
tdb2_result_and_status="$($SPARQL_CMD --query="$TDB2_QUERY" ... --results=tsv 2>&1)"
TDB2_LOCATION=$(echo "$tdb2_result_and_status" | awk 'NR==2 {print $1}')
```

The warning lands on line 1, so `NR==2` returns the TSV *header* rather than the path.
`TDB2_LOCATION`, `TEXT_INDEX_LOCATION`, `SPATIAL_INDEX_LOCATION` and `SRS_URI` (lines 94,
104, 114, 124) would all silently become `?tdb2Location`-style garbage.

The applied fix instead introduces a separate `JAVA_VECTOR_OPTS`, applied **only** to
`TEXT_INDEXER_CMD`. Keeping it out of `JAVA_OPTS` has a second benefit: a caller
overriding `JAVA_OPTS` for heap cannot accidentally drop the flags.

The general rule this establishes: **any future JVM flag that emits a startup warning must
not be applied to the SPARQL discovery commands in this script.**

**The documented local run command should carry the same flags.** Otherwise local dev has
the Vector API off while production has it on, and KNN benchmarks taken locally will be
much worse for reasons unrelated to the code.

## Delivery tiers

Deliberately separable. Each is useful alone.

### Tier 0 — bring your own vectors

Embeddings produced entirely outside Jena; Jena stores and searches them.

- `FieldType` gains `VECTOR`
- one `case VECTOR ->` in `addNodeFieldToDoc` writing `KnnFloatVectorField`, parsing
  base64 float32 or comma-separated floats
- query side accepts a vector parameter and builds
  `KnnFloatVectorQuery(field, target, k, existingFilterQuery)`, reusing the filter already
  constructed
- one assembler term declaring the field, its dimension, and its similarity function

Vectors arrive either as triples in the graph (free ride on the change listener, but ~3KB
raw / ~4KB base64 per entity of opaque machine noise in TDB2) or via the existing
`external/` row sources (better fit, already built).

**The entire differentiated pitch lands here.** Filtered KNN + SHACL constraints + facets
needs no embedding model in-process. Two features are fully covered at Tier 0 with no
encoder at all:

- **"More like this"** — the query is an entity IRI, which is a key in the map. Look up its
  vector, KNN against the rest, filter, facet.
- **Fixed canonical query sets** — saved searches, category landing pages. Precompute and
  ship in the same CSV keyed by a pseudo-IRI.

### Tier 1 — query-time embedding

So a user can type words instead of 384 floats. This is the only thing a CSV cannot cover,
by construction: a CSV maps *known IRIs* to vectors, and a search string is neither known
nor an IRI.

Three places the encoder can live: the client (Jena makes no call, holds no credentials —
but a bare SPARQL client can no longer do semantic search unaided), an in-process engine
(chosen — see below), or an HTTP provider (rejected as a default; network on the query
path and credentials in assembler config).

### Tier 2 — document embedding inside Fuseki

Verbalise and embed on change. This puts inference inside `ShaclTextDocProducer`'s commit
path and needs an async queue plus a stale-vector marker.

**Deferred.** The loader should own document embedding. See
[Prior art](#prior-art-what-the-pgvector-ecosystem-settled-on) — everyone who built
in-database embedding either made it async via a background worker or required a custom
build, and Postgres had a mature background-worker ecosystem to hang it off. Fuseki does
not, so this is more expensive here than there.

## Engine choice

**Jlama**, with ONNX Runtime via LangChain4j as the fallback.

| | Jlama | LangChain4j bundled ONNX | DJL | HTTP provider |
|---|---|---|---|---|
| Native libs | **None** — pure Java, Vector API | ONNX Runtime natives per platform | Heavy (PyTorch engine) | None |
| Model delivery | Fetched/converted, mounted or baked | **Inside the jar** (~25MB) | Downloaded at runtime | Remote |
| arm64 | Free | Needs the right classifier | Varies | Free |
| Offline | Yes, once the model is present | Yes | After first fetch | No |
| Maturity | Younger; embeddings secondary to LLM inference | Mature | Mature | Mature |
| Vector API | **Required** | Optional | Optional | N/A |

Jlama wins on the property that matters most for this repo's deployment story: **no native
libraries**, which keeps the UBI image clean and makes arm64 free. The incubator flag it
requires is already being passed in the runtime image, so that friction is largely
pre-paid — once the loader gap above is closed.

What it costs, honestly:

- **Vector API is not optional for it.** Hard dependency, hence the prerequisite.
- **Models are not in the jar.** Jlama fetches from HuggingFace and uses its own quantized
  format, so there is a download-and-convert step. This is what LangChain4j's bundled ONNX
  models buy that Jlama does not — and it is why the deployment model below exists.
- **Younger project.** Verify current embedding-model coverage and Maven coordinates
  (`com.github.tjake:jlama-*`) before committing. Confirm whether the target version needs
  any flags beyond `--add-modules jdk.incubator.vector`.

`langchain4j-jlama` exists, so both engines can sit behind one interface and the fallback
stays a dependency swap.

### Module layout

```
jena-text              → EmbeddingProvider interface only, zero new dependencies
jena-text-embeddings   → Jlama (and/or ONNX) implementations, opt-in dependency
```

Keeping implementations out of `jena-text` matters beyond tidiness. This fork intends
eventual contribution back to Apache, and a model binary or an ML runtime as a default
dependency is exactly what Apache legal scrutinises. An opt-in module is a footnote; a
default dependency is an argument.

Note the module list appears in **both** the `-Pdev` and `-Pcomplete` profiles
(`pom.xml:219` and `:277`). CLAUDE.md flags module lists as a recurring upstream-merge
conflict site — add to both.

## Deployment model

The intended pattern is a derived image. Users pick their model, bake it into a layer on
top of the published Jena image, and configure the path.

**Publish the model as its own image**, so the fetch-and-convert step runs once and is
shared:

```dockerfile
# built once, per model
FROM scratch
COPY bge-small-en-v1.5-q4/ /models/bge-small-en-v1.5-q4/
```

**Derive both Jena images from it.** This is the part that is easy to get wrong: the
`loader` target is `FROM runtime`, and *both* need the model — the loader to embed
documents, the runtime to embed queries. Two derived images, one model artifact:

```dockerfile
FROM ghcr.io/kurrawong/fuseki-lucene-shacl:sha-abc1234
COPY --from=myorg/embed-model:bge-small-q4 /models /models
ENV JENA_EMBEDDING_MODEL_PATH=/models/bge-small-en-v1.5-q4
```

```dockerfile
FROM ghcr.io/kurrawong/fuseki-lucene-shacl-loader:sha-abc1234
COPY --from=myorg/embed-model:bge-small-q4 /models /models
ENV JENA_EMBEDDING_MODEL_PATH=/models/bge-small-en-v1.5-q4
```

Pin the base by `sha-<short>`, per the existing immutable-tag policy in CLAUDE.md.

**Mounting is the dev alternative** — `-v ./models:/models`, or a PVC / initContainer under
Kubernetes. More flexible, less reproducible. Support both by reading a path from config;
document the baked derived image as the default because it matches the repo's existing
immutable-provenance philosophy.

**We should eventually publish a default model image ourselves** so the common case is one
`FROM` line. Note that shipping a model in a *Docker image* rather than a *Maven artifact*
neatly sidesteps the Apache bundling concern — the source release contains no model
binary. That is a further point in favour of this deployment shape over the bundled-jar
approach.

## Model identity is a correctness boundary

Index-time and query-time models must match. A mismatch does not error — it returns
plausible-looking garbage. With embeddings generated in two separately-owned places (a
loader image and a runtime image, each independently rebuildable) this *will* eventually
happen.

Record the model identifier and dimension in the index metadata at build time, and fail
loudly at startup on mismatch. Lucene fixes dimension and similarity function per field at
index time anyway, so changing model means a full reindex regardless — the check just makes
that explicit rather than silent.

## Facet semantics change under KNN

`luc:facet` today counts over the **full** matching set. A KNN query returns top-k by
construction; there is no "all matching documents". So facet counts over a vector query
become counts within top-k.

This is a different contract from the one the current documentation promises, and needs an
explicit decision plus explicit documentation. The likely resolution: facets are exact for
boolean/filter queries, top-k-scoped for KNN, and hybrid queries inherit the KNN semantics.

## Hybrid retrieval

Worth noting separately because it needs **no** embedding work at all beyond Tier 0: fusing
the existing BM25 scoring with dense KNN via reciprocal rank fusion is roughly thirty lines
(Lucene provides no RRF out of the box). That is likely the largest single retrieval-quality
win available, and it is independent of every decision above.

## Resource notes

- 384-dim float32 is ~1.5KB/doc — comfortably under Lucene's 1024 default dimension cap
  (raising the cap needs a custom `KnnVectorsFormat`; confirm the exact constant on 10.3.1
  before choosing a larger model). Scalar/binary quantization is available and wanted.
- HNSW wants the graph resident for good latency; segment merges rebuild it.
- Query-side inference on a short string is single-digit milliseconds — a non-issue. Bulk
  indexing is where cost lands: thousands of entities × forward pass, competing with Lucene
  for CPU. Bound inference thread counts or it will fight the indexer for cores.
- Cold start: first embed initialises the model. Warm it at assembler init, not on the
  first user query.

## Prior art: what the pgvector ecosystem settled on

Useful because it is the largest comparable ecosystem and it resolved the same questions.

**pgvector core holds exactly Lucene's position** — storage, index, distance operators, no
embedding function, ever. The overwhelming majority of deployments have the application
embed and pass vectors in. That is Tier 0, and it is the default by weight of practice.

**The in-database layer that grew on top is instructive in its shape.** pgai (Timescale)
uses a *background worker* keeping an embedding table in sync — explicitly not a
synchronous trigger. pg_vectorize runs local sentence-transformers via a *sidecar*.
PostgresML runs models in-process and consequently requires a *custom Postgres build* you
cannot install on managed RDS. Aurora's `aws_ml` and AlloyDB's `embedding()` are HTTP calls
wearing a SQL costume.

Nobody does synchronous in-transaction embedding. That is the direct support for deferring
Tier 2.

**Where the comparison favours this design:** filtered vector search is pgvector's known
sore spot — HNSW plus a `WHERE` clause forces a choice between pre-filtering and
post-filtering, the latter able to return fewer than k rows. Iterative index scans (0.8)
mitigate rather than solve it. And Postgres has no facet engine at all; you hand-roll
`GROUP BY` per query with no shared machinery.

## Open questions

Resolved in the build:

- ~~Which verbalisation?~~ `idx:embeddingSource`, an ordered list of field IRIs.
- ~~`luc:knn` or an argument on `luc:query`?~~ Neither: the existing `fieldSpec`.
- ~~Confirm Jlama's coordinates and licence.~~ `com.github.tjake:jlama-core:0.8.4`,
  Apache 2.0, needs `--add-modules jdk.incubator.vector`. Embedding API is
  `ModelSupport.loadEmbeddingModel(...)` then `model.embed(text, PoolingType)`.

Still open:

- Does the CSV/external-source path carry vectors as base64 or as comma-separated floats?
  Base64 float32 is roughly half the bytes and parses faster; 768 comma-separated floats is
  ~8KB of text per row, which matters at millions of entities.
- **Is Jlama the right engine at all**, given it cannot load any model newer than the BERT
  generation? See [The engine choice needs revisiting](#the-engine-choice-needs-revisiting).
- Should document embedding move off the commit path onto a queue, as this note originally
  proposed? It is synchronous today.
- Should `idx:knnTopK` be per-query rather than per-index? One value per index is what makes
  hits, facets and `?totalHits` describe the same set; a per-query override would need those
  three to agree some other way.

## Related

- [2026-07-27_external_content_indexing_design.md](2026-07-27_external_content_indexing_design.md)
  — the external row source machinery a vector sidecar would reuse
- [04-architecture.md](04-architecture.md) — entity-per-document model
