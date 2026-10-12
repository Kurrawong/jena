---
title: "Config endpoint and index-shape fingerprint"
date: "2026-08-13"
status: "Fingerprint built and in use. Config endpoint built, then removed on 2026-10-09. TDB2 stamping deliberately not done"
---

# Config Endpoint and Index-Shape Fingerprint

> **The config endpoint described here has been removed.** The
> `jena-fuseki-mod-config` module and its `/$/config` paths were deleted on 2026-10-09 in
> favour of the `luc:config` property function, which answers through the dataset's
> query endpoint instead of the admin space. See
> [2026-10-09_luc_config_property_function_plan.md](2026-10-09_luc_config_property_function_plan.md).
> The fingerprint, the index stamp and the dataset pairing are unaffected and still in
> use.

Two features that share one idea: the server should be able to tell you what
configuration it is running, and whether the on-disk index was built from the same
configuration.

1. **Config endpoint** — a read-only, admin-gated HTTP endpoint serving the Fuseki
   configuration file, so a client can browse it without a copy on its own filesystem.
2. **Index-shape fingerprint** — a hash of the index-determining part of the config,
   written into the Lucene index when it is built and compared at startup.

Every claim about current behaviour was verified against the tree at `a39127fb04`, by
reading the cited line.

## Motivation

### Nothing tells you what config is running

`/$/server` and `/$/datasets` return the runtime view only — dataset name, accepting
state, operation types, endpoint names (`JsonDescription.java:45-67`). No dataset type,
no storage location, no index configuration, no timeouts, and no indication of which
file a dataset came from. The server retains only `configFilename`
(`FusekiServer.java:225,374`); the parsed `Model` is a `Builder` field (`:529,:947`),
handed to modules and then dropped.

The concrete cost is in `demo/`. `demo/app-static/config.ttl` is a **symlink** to
`demo/test/config.ttl`, created by `ln -sfn` at `demo/Taskfile.yml:79`. The app fetches
it over HTTP and parses 364 lines of Turtle with N3.js in the browser
(`demo/app-static/app.js:675-700`) to discover the endpoint, facet fields, field types
and hierarchies. That works only because the app and the server share a filesystem, and
it re-implements Jena's config semantics in JavaScript — so it drifts. The
`text:taxonomyDirectory` defaulting added in `a39127fb04` is exactly the kind of rule the
browser parser does not know.

A symptom already present: `idx:fusekiBase "http://localhost:3030"` in that config is
consumed by nobody. Jena has no such property, and the app takes its base from
`APP_CONFIG.fusekiBase` in `app-config.js`.

### Nothing tells you the index matches the config

The index records nothing about how it was built. There is no `setLiveCommitData`
anywhere in the repo, no version marker, and no metadata document in
`ShaclTextIndexLucene`.

The drift is structural in our deployment. Two container invocations read `/config.ttl`
independently:

- loader: `CONFIG="${CONFIG:-/config.ttl}"` → `shacltextindexer --desc="$CONFIG"`
  (`build-files/docker/loader-entrypoint.sh:5,332`)
- runtime: `--config /config.ttl` (`build-files/docker/Dockerfile:105`)

Two separately mounted files, possibly months apart, with nothing checking they agree.
Add `idx:facetable true` to a field, redeploy the runtime without re-running the loader,
and the facet returns zero buckets forever with no error anywhere.

## What is being compared

Not the config file. Two textually different files must count as the same when they
differ only by `tdb2:location` — building on a large indexing VM and serving from
somewhere else is a normal workflow, and the resulting index is entirely valid. So what
is hashed is a *projection* of the config: the parts that determine the built artifact.

**That projection is mostly the parsed object.** `ShaclIndexMapping` holds `FieldDef`
(name, type, analyzers, flags — `:69-83`), `HierarchyDef` (`:227-228`), `FieldOccurrence`
(`sh:path`, predicates, required class, datatype — `:275-282`) and `IndexProfile`.
`text:directory` and `text:taxonomyDirectory` live on the assembler and never reach the
mapping, so they are excluded for free.

Two things in the mapping *are* location-dependent and had to be excluded by hand. An
earlier draft of this note claimed the mapping held no location at all; that was wrong,
and both exceptions were found by writing the code and running it:

- **`ExternalSourceDef.getLocation()` and its delta locations** are CSV paths in the
  mapping. A path to data is not a description of index shape, and it is expected to
  differ between an indexing machine and a serving one, so it is excluded. Whether deltas
  are in use at all is structural, so the *count* is kept.
- **`IndexProfile.getShapeNode()`** — the shape's own IRI. This one is a trap. The
  `@prefix : <#>` idiom, which `demo/app-static/config.ttl` uses, resolves shape names
  against the configuration file's own URI, so the same shape is
  `file:///build/config.ttl#ReportShape` on one machine and
  `file:///srv/config.ttl#ReportShape` on another. Including it made an end-to-end run
  report MISMATCH purely because the directory had changed — exactly the false positive
  the design set out to avoid. A shape's *name* determines nothing about what is written
  to the index; profiles are distinguished by their target classes and content. Excluded,
  and pinned by `configFileLocationIsNotSignificant`.

Everything else follows by construction: endpoint names, timeouts, prefixes, comments and
triple order never reach the mapping.

### Why not hash the RDF graph

Because blank node canonicalisation would have to be written first. Jena has no
RDFC-1.0 / URDNA2015 implementation in this tree — only `NodeFormatter_C14N`, which is
per-term escaping, not graph canonicalisation. Our configs are dense with blank nodes:
every `fuseki:endpoint`, every `sh:` shape, every RDF list. Hand-rolling blank node
labelling to compare two files that differ by a directory string is the wrong trade.

### Scope of the hash

| In | Why |
|---|---|
| `ShaclIndexMapping` — profiles, fields, flags, paths, hierarchies | determines index content and layout |
| `text:storeValues` | determines whether values are written (`ShaclTextIndexAssembler.java:109-117`) |
| `facetFields` | determines which SSDV dims exist (`:125`) |

| Out | Why |
|---|---|
| `text:directory`, `text:taxonomyDirectory`, `tdb2:location` | not in the mapping; the whole point |
| `ExternalSourceDef` location and delta locations | paths to data, expected to differ per machine |
| Shape node IRI | document-relative under `@prefix : <#>`; names nothing on disk |
| `text:maxFacetHits` | applied at query time (`:127-134`) — must not trigger a reindex warning |
| Fuseki service and endpoint names, `ja:context` timeouts | do not affect the artifact |
| Analyzer *parameters* | see below |

### Known gap: analyzer parameters

`FieldDef` stores `Analyzer` **instances** (`:72-83`), not the RDF that configured them.
The hash therefore covers the analyzer's class name but not its parameters — a changed
stopword list on the same analyzer class hashes identically and stays invisible.

This is accepted, not overlooked. A stopword list is closer to data than to schema: if it
changes, the index differs for the same reason it differs when the underlying triples
change, and no config check was ever going to catch that. Closing the gap would mean
capturing each analyzer's config subgraph into `FieldDef` at assembly time; it is
deferred until something demands it. **Document it in the endpoint output**, so nobody
reads a green fingerprint as a stronger guarantee than it is.

## Where the hash is computed

`ShaclTextIndexAssembler.java:120-136` is a single convergence point:

```java
TextIndexConfig config = new TextIndexConfig(docDef);
config.setAnalyzer(analyzer);
config.setQueryAnalyzer(queryAnalyzer);
config.setValueStored(storeValues);
config.setShaclMapping(shaclMapping);
config.setFacetFields(shaclMapping.getFacetFieldNames());
// ... maxFacetHits ...
return new ShaclTextIndexLucene(directory, taxonomyDirectory, config);
```

Both the Fuseki runtime and `shacltextindexer` assemble from the same Turtle through this
same code, so **one hook covers both processes**. Computing the fingerprint here and
carrying it on `TextIndexConfig` means the writer and the reader derive it identically by
construction — there is no second implementation to drift.

## Where the hash is stored

`IndexWriter.setLiveCommitData`, hooked at `TextIndexLucene.openIndexWriter()`
(`:178-192`), with `getIndexWriter()` already public at `:208`. Commit user data is
persisted in `segments_N`, survives merges, and is unused in this repo. Read back through
`DirectoryReader.getIndexCommit().getUserData()`; `DirectoryReader.open(directory)`
already exists at `:390`.

Live commit data persists across commits unless changed, so the cost is a few hundred
bytes per `segments_N` — irrelevant for the loader (one commit) and acceptable for live
updates through `ShaclTextDocProducer`.

Keys:

```
jena.shacl.configFingerprint   sha256 hex of the canonical serialisation
jena.shacl.fingerprintVersion  serialisation format version, an integer
jena.shacl.builtAt             ISO-8601 instant
jena.shacl.jenaVersion         the pom version that wrote it
```

`fingerprintVersion` matters: when the serialiser changes, old indexes must report
"unknown", not "mismatch". Without it, the first change to the format makes every
deployed index look broken.

## Deliverables

### Phase 1 — Fingerprint computation — **BUILT**

`ShaclConfigFingerprint` in `org.apache.jena.query.text`:

- `String serialise(ShaclIndexMapping mapping, boolean storeValues, List<String> facetFields)`
  — deterministic text. Profiles sorted by target; fields sorted by field name; flags
  written explicitly rather than by omission; hierarchies in configured level order (this
  is significant, not incidental); `Node` and `sh:path` in their stable string forms;
  analyzers as `getClass().getName()`.
- `String fingerprint(...)` — `sha256` hex of that.
- A `FINGERPRINT_VERSION` constant.

Explicit flags matter: if `sortable=false` is written as absence, adding a field whose
flags are all false produces the same string as not having the field.

Tests (JUnit 5; new classes must be added to `TS_Text`'s `@SelectClasses` or they
silently never run):

- identical mappings → identical fingerprint
- field order in the config Turtle does not change the fingerprint
- prefix choice, comments and formatting do not change the fingerprint
- **two configs differing only in `text:directory` / `text:taxonomyDirectory` produce the
  same fingerprint** — the load-bearing case
- each of `facetable`, `sortable`, `multiValued`, `stored`, `indexed`, `defaultSearch`
  flipped on one field → fingerprint changes
- field type changed → changes
- a field added or removed → changes
- hierarchy level order reversed → changes
- a hierarchy added → changes
- `sh:path` changed → changes
- `storeValues` flipped → changes
- **`maxFacetHits` changed → does NOT change** — pins the query-time exclusion
- analyzer class changed → changes
- documented: analyzer *parameters* changed → does not change (pins the known gap so a
  future fix has a test to flip)

Per repo test discipline, write the assertion for each behaviour before implementing it
and confirm it fails for the expected reason.

### Phase 2 — Write and read the stamp — **BUILT**

- Compute the fingerprint in `ShaclTextIndexAssembler` (`:120-136`); carry it on
  `TextIndexConfig`.
- Write commit data in `TextIndexLucene.openIndexWriter()` (`:178-192`).
- Add a read accessor returning a small record, or `null` for an index with no stamp.
- Add a comparison returning `MATCH` / `MISMATCH` / `UNKNOWN` (no stamp, or a
  `fingerprintVersion` this build does not understand).

Tests: build an index and read the stamp back; reopen with a changed config and get
`MISMATCH`; an index written without a stamp reads `UNKNOWN` and does not throw; the
stamp survives a merge (`forceMerge`) and a reopen.

The `UNKNOWN` path deserves care — every index built before this ships is in it, so it
must be quiet and non-fatal.

### Phase 3 — Startup check and logging — **BUILT**

The check runs where the index is opened — in the `ShaclTextIndexLucene`
constructor — rather than in a Fuseki module. An earlier draft put it on
`FusekiModule.serverAfterStarting`; opening the index is the better place because it is
the moment the two artifacts actually meet, and it covers the `shacltextindexer`,
embedded use and tests as well as Fuseki, with no new module and no Fuseki dependency
from `jena-text`. In a Fuseki deployment the assembler runs at startup, so this is
startup logging.

```
INFO  Index config fingerprint matches: /mining (sha256:a1b2c3d4…)
WARN  Index config fingerprint MISMATCH: /mining
WARN    index built:   2026-08-01T09:14:22Z  sha256:9f3c…
WARN    running config:                      sha256:a1b2…
WARN    The Lucene index was built from a different index configuration.
WARN    Facets, fields or hierarchies added since may return no results.
WARN    Rebuild with: shacltextindexer --desc=<config>
INFO  Index config fingerprint: unknown for /mining (index predates fingerprinting)
```

Log the match, not only the mismatch. A check that is silent when healthy is a check
nobody trusts when it speaks.

Warn, do not fail startup. A mismatched index still serves correct results for every
field that was present when it was built; refusing to start would turn a degraded service
into an outage. If a strict mode is wanted later it should be an explicit opt-in flag.

### Phase 4 — Config endpoint — not started

A new module `jena-fuseki2/jena-fuseki-mod-config`, following
`jena-fuseki-mod-geosparql` exactly: a `FMod_*` class plus
`META-INF/services/org.apache.jena.fuseki.main.sys.FusekiAutoModule`. That module is
already a dependency of `jena-fuseki-server` (`jena-fuseki-server/pom.xml:76`), so the
pattern is proven and the shaded jar picks ours up the same way.

A new module rather than files added to `jena-fuseki-main`: the fork has added **no**
Fuseki-side code so far (every fuseki commit in history is an upstream `GH-nnnn`), and
keeping it that way keeps the monthly upstream merge to its existing conflict set.

```
GET /$/config                   → the server configuration file     (Turtle)
GET /$/config/effective         → what the server actually resolved (JSON)
GET /$/config/datasets          → dataset configuration files       (JSON)
GET /$/config/datasets/{name}   → one dataset's configuration file  (Turtle)
```

**The paths name the two things Fuseki calls "config".** The `--config` file is the
*server* configuration: at most one, and the only place a server-wide setting such as a
timeout can live. The files in `FUSEKI_BASE/configuration/` are *dataset* configurations:
one per dataset, each parsed into its own graph, never merged, and unable to carry a
`fuseki:Server` at all. Collapsing both behind one path inherits Jena's own ambiguity, so
the root is the server configuration — singular, because Fuseki allows only one — and
dataset configurations get their own collection.

They are keyed by dataset name, not an opaque handle:
`FusekiServerCtl.generateConfigurationFilename` writes `<dsName>.ttl`, so the name is the
real key. Two earlier revisions got this wrong. The first made a caller read a listing,
pick a base64 id and come back for the content — but the id is derived from the path the
same response prints, so it gated nothing, and once the bytes were captured at startup the
second call fetched something already in memory. The second switched the root between
Turtle and JSON on `Accept`, which makes the response type depend on something a reader of
the URL cannot see. A path per resource needs neither.

Serve the **captured bytes**, not `builder.configModel()`Serve the **captured bytes**, not `builder.configModel()` — `readAssemblerFile` injects
`modelExtras` (`AssemblerUtils.java:141`), assembler-registration `rdfs:subClassOf`
triples the user never wrote.

Do not redact. Behind the admin gate, reading the config grants nothing the caller does
not already have — `/$/datasets` POST already **writes** config files
(`ActionDatasets.java:252-253`). There is no reliable rule for what is sensitive in an
arbitrary assembler graph, and a silently holed file defeats the purpose of a browse
view. Document the endpoint as being exactly as sensitive as the file.

Read-only. No write path, which means no `ja:loadClass` remote-configuration-execution
concern (`FusekiConfig.processLoadClass:275`) and no reload semantics to get right. Note
that `ActionReload` is registered only in `TestFusekiReload.java:154` — there is no
`/$/reload` on a real server, so there is nothing to apply an edit with anyway. The GUI
"edit" is client-side with a download; the browser holds the original bytes, so comments
and prefixes survive by construction.

**Authentication must be decided, not inherited.** Our image does load the admin module:
`jena-fuseki-server.jar` Main-Class is `FusekiServerCmd` → `FusekiServerUICmd` →
`FusekiRunner.fmodsServerUI()` (`FusekiRunner.java:206-217`) = `FMod_Admin` + `FMod_UI` +
`FMod_Shiro`. Shiro is active, because `FusekiServerCtl.ensureBaseArea` copies the bundled
default `shiro.ini` in if missing (`:197-200`) and hands it to Shiro
(`FMod_Admin.java:145-148`). But that default is:

```ini
/$/server  = anon
/$/ping    = anon
/$/metrics = anon
/$/**      = localhostFilter
```

`authcBasic` is commented out and the sample user is `user1=passwd1`. So the default gate
is network position, not authentication, and `LocalhostFilter.isAccessAllowed` compares
`request.getRemoteAddr()` to a literal loopback set. A request reaching the container via
`-p 3030:3030` or a reverse proxy arrives with the bridge gateway address and gets a 403.

Two consequences: `/$/config` is safe by default, and it will block the demo app. The
deployment must enable real auth (`/$/** = authcBasic,user[admin]` with a real password).
Do **not** add a fourth `anon` line next to `/$/server`.

### Phase 5 — Effective view and demo app — not started

`GET /$/config/{id}?view=effective` — JSON built from the live objects, not by re-parsing
Turtle. This is the part a browser genuinely cannot compute, because it includes Jena's
defaulting (such as `text:taxonomyDirectory` from `text:directory`, `a39127fb04`).

```json
{
  "configFingerprint": "sha256:a1b2…",
  "fingerprintVersion": 1,
  "caveats": ["Analyzer parameters are not covered by the fingerprint."],
  "datasets": [{
    "name": "/mining",
    "index": {
      "location": "/data/Lucene",
      "fingerprint": "sha256:9f3c…",
      "status": "MISMATCH",
      "builtAt": "2026-08-01T09:14:22Z"
    },
    "shapes": [ ... ],
    "fields": [ { "name": "commodity", "type": "KEYWORD", "facetable": true, ... } ]
  }]
}
```

`location` is displayed and never compared, so building on one machine and serving from
another reports `MATCH` with two different paths.

Then delete the `ln -sfn` at `demo/Taskfile.yml:79`, drop the N3.js config parse from
`demo/app-static/app.js`, and remove the dead `idx:fusekiBase` triple from the demo
config.

## TDB2

Deliberately last, and separable.

The fingerprint is derived from `ShaclIndexMapping`, and **nothing in it influences the
TDB2 store**. A TDB2 database loaded under one index shape is perfectly valid under
another. So stamping TDB2 with this hash answers "was the same config file present when
this store was loaded" — a deployment-provenance signal, not a validity one.

That is still worth something, but it must be reported at a different severity:
informational, never a warning, and never phrased as "invalid".

Mechanically it is also more work than the Lucene side. TDB2 has no user-metadata slot —
`Names.TDB_CONFIG_FILE = "tdb.cfg"` is TDB2's own `StoreParams` and must not be co-opted.
The load is done by upstream `tdb2.tdbloader` / `tdb2.xloader`
(`loader-entrypoint.sh:36-37`), which we should not modify. So the stamp would be a
sidecar file written by the entrypoint after the load, e.g. `<location>/jena-config.json`.

A sidecar in the container directory is safe: `cleanDatabaseDirectory`
(`DatabaseOps.java:177-202`) removes only `-tmp` compaction directories and files listed
in `jena-tdb-temp-files`, so an unknown file survives — including across compaction, which
creates a new `Data-NNNN` inside the same stable container directory. It does **not**
survive backup → restore.

Recommendation: ship Phases 1–5 first and decide on TDB2 with the endpoint in hand. The
Lucene check is the one that catches a real, silent failure.

## Explicitly out of scope

- **Writing config through the API.** Read-only plus client-side download.
- **Data currency** — "was this index built from the current TDB2 content". A separate
  question needing a persisted generation counter, which TDB2 does not have:
  `TransactionCoordinator.dataVersion` (`:116`) is an in-memory `AtomicLong` reset to 0
  every JVM run.
- **Dataset instance identity** — detecting a mis-mounted index whose config happens to
  match. Would need a UUID minted at build time.
- **Field-level diff of a mismatch.** The same deterministic serialisation makes it cheap
  later; the boolean is what is being asked for now.

## Sequencing

Phases 1–3 are one coherent piece of work in `jena-text` and deliver the check on their
own — the startup log is useful with no endpoint at all. Phase 4 is independent and can
land in parallel. Phase 5 depends on both.

## What was built

All five phases. 53 new tests in `jena-text` (857, from an 809 baseline) and 8 in the new
Fuseki module.

| File | Role |
|---|---|
| `ShaclConfigFingerprint` | canonical serialisation + SHA-256, `FINGERPRINT_VERSION = 1` |
| `ShaclIndexStamp` | commit-data keys, read/write, `compare`, `comparePairing`, `Status` |
| `DatasetInstanceId` | the minted-once sidecar, `jena-dataset-id.properties` |
| `DatasetLocations` | unwraps wrapper layers to find the TDB2 container directory |
| `ShaclTextIndexLucene` | computes, stamps, compares, pairs, logs |
| `TextDatasetAssembler` | calls `checkOrCompletePairing` on both single- and multi-index paths |
| `shacltextindexer` | re-stamps after a rebuild, with the dataset pairing id |
| `jena-fuseki-mod-config` | `/$/config`, raw view, effective view |

Verified against a running Fuseki on a two-triple dataset: the indexer mints the sidecar
and writes the stamp; startup logs both `Index configuration matches` and `Index is paired
with the dataset it was built from`; flipping one `idx:sortable` logs MISMATCH; copying
the tree elsewhere still logs MATCH with an identical fingerprint; and `/$/config` lists
the source, serves the file byte-identical to disk, and reports the effective view with
status, fingerprints and pairing.

### Pairing rules

A new index adopts the dataset it is first attached to. An existing one never rewrites its
pairing — that would erase the evidence. An identity is minted for the attached dataset
only when the index carries a pairing to judge it against; this covers the case where the
database was loaded with plain `tdbloader` and so is anonymous, which would otherwise be
reported as unknown rather than as the crossed mount it is. An unpaired index writes
nothing.

### Two upstream behaviours worth knowing

**`FusekiServer.getConfigFilename()` is null for any command-line server.** `FusekiArgs`
reads the `--config` file itself and calls `builder.parseConfig(Model)`; only
`parseConfigFile(String)` records the name. `FMod_Config` therefore captures the name from
`serverArgsPrepare`. The same gap is why `ActionReload` could not have worked for a
command-line server even if it were registered. Pinned by
`commandLineServersReportNoConfigFilename`.

**`mvn apache-rat:check` from the command line is not the build's RAT check.** Invoked
directly it uses the `default-cli` execution and reports three long-standing unapproved
files; the execution bound into the lifecycle is configured differently and passes. Judge
licence headers by `mvn install`, not by the ad-hoc goal.

### The demo app

`loadConfig` now fetches from `/$/config` through the app's same-origin proxy, so the app
no longer needs a filesystem shared with the server. The `ln -sfn ../test/config.ttl`
symlink is gone from `demo/Taskfile.yml`, and the dead `idx:fusekiBase` triple is gone
from the demo config. Verified with the symlink absent: the app's fetch path returns bytes
identical to the file on disk.

**The demo proxy was already an admin bypass, and that is what got fixed.**

An earlier attempt opened `/$/config` in a demo-only `shiro.ini`. That was solving the
wrong problem, and testing showed why. From a non-loopback address, with the stock
`shiro.ini`:

| Request | Direct to Fuseki | Through `serve_app.py` |
|---|---|---|
| `/$/datasets` | 403 | **200** |
| `/$/config` | 403 | **200** |

`_proxy_request` forwarded anything under `/fuseki/` verbatim, and the proxy binds
`0.0.0.0` and connects to Fuseki from localhost. `LocalhostFilter` compares the socket's
remote address, so **every** admin endpoint was laundered through the proxy — including
`POST`/`DELETE /$/datasets`, backup and compact. Anyone on the same network could delete
the dataset. `/$/config` needed no Shiro change because it was already reachable; the
Shiro edit cut a careful one-path hole beside an open door.

So the proxy carries the allowlist instead, and Fuseki keeps its stock, stricter
configuration. `ADMIN_ALLOWED` in `serve_app.py` permits `GET /$/config` and refuses
everything else under `/$/` before it reaches Fuseki. The app needs exactly three paths —
`{dataset}/query`, `/$/config`, `/$/config/{id}` — so nothing is lost.

Verified from a non-loopback address through the proxy: `/$/config` 200; `/$/datasets`,
`/$/backups-list`, `/$/server`, `/$/compact` and `POST /$/datasets` all 403; the SPARQL
endpoint still 200 and returning results. The demo-only `shiro.ini` is gone.

The general lesson is worth keeping: a same-origin proxy in front of Fuseki defeats
`LocalhostFilter` completely. Any deployment fronting Fuseki with a reverse proxy needs
its admin paths restricted at the proxy, or real authentication in Shiro — the shipped
default protects nothing once a proxy is in the path.

The N3.js parse remains: `extractConfig` derives `predicateToFacet`, `fieldInfo`,
`sortableFields` and `hierarchyDimensions`, and the effective view does not expose the
predicate mapping. Moving the app onto the JSON view means extending that view and
reworking a 3000-line file with no automated browser coverage here — worth doing, not
worth doing blind. The local-file fallback is kept for a static deployment, and now
reports both failed attempts rather than a bare 404.

### Not done: TDB2 stamping

Unchanged from the reasoning above. Nothing in the fingerprint influences the TDB2 store,
so a stamp there records deployment provenance rather than validity and would have to be
reported at a lower severity; and in a multi-dataset config it is unclear which index's
fingerprint belongs in which store's sidecar. The dataset *identity* sidecar is in place,
which is the part that carries real information.
