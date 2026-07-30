# Demo: SHACL Entity-per-Document Indexing

This demo shows the SHACL-based full-text indexing mode for Apache Jena Fuseki,
using an Australian mining domain with reports, boreholes, sites, and authors.

## Prerequisites

- Java 21+
- Maven 3.9+ (for building)
- [go-task](https://taskfile.dev/) (for `task` commands)

For Docker workflows:
- Docker Desktop
- Root image tasks live in `../Taskfile.yml`
- **GitHub CR**: `gh` CLI authenticated with `write:packages` scope (only if using the root GHCR push tasks)
- **Azure CR**: `az` CLI authenticated (only if using the root ACR push tasks)

## Quick start

```bash
# 1. Build the Fuseki server JAR (from repo root)
task build

# 2. Load the data and build the index, with the server DOWN
task load-offline
task index

# 3. Start the server
task serve

# 4. Run all queries (in a separate terminal)
task query

# 5. Stop the server
task stop
```

Or in one step: `task refresh` does all of the above and launches the app.

**Why load and index with the server stopped?** `BoreholeShape` draws its assay
children from `data/assays.csv` via `idx:externalSource`, which makes it
**rebuild-only**: a live graph change cannot re-derive those children, so the change
listener refuses to touch those documents rather than silently dropping them. Only
`task index` (the bulk indexer) can build them, and it needs exclusive TDB2 access.

`task load` (push into a running server over GSP) still works for the shapes that have
no external source — reports and sites — but boreholes will be missing their assays
until the next `task index`.

## External assay data

`test/data/assays.csv` is a small CSV of borehole assay results, keyed by **borehole
IRI**, that is never loaded into the graph:

```
borehole,analyte,grade,units,below_detection
http://example.org/mining/bh-bh-001,Ag,12.4,ppm,f
http://example.org/mining/bh-bh-001,Au,0.82,ppm,f
```

Each row becomes a nested child document inside that borehole's Lucene block, so a
single query can constrain across graph fields and assay values at once — "Queensland
boreholes with silver above 10 ppm". All four columns land on **one** child, so they
correlate: "Cu above 1 pct and above detection" must be satisfied by the same assay,
not by two different ones on the same hole.

`grade` is `idx:stored false` — searchable, sortable, facetable, never returned. The
assay database stays the source of truth.

`test/data/assays-delta.csv` demonstrates `idx:delta`, applied over the base at build
time:

```
op,borehole,analyte,grade,units,below_detection
DELETE,http://example.org/mining/bh-mia-001,Ag,44.9,ppm,f
ADD,http://example.org/mining/bh-mia-001,Ag,51.3,ppm,f
DELETE,http://example.org/mining/bh-pil-001,Mn,,,
```

`DELETE` matches on the columns it fills in, so the last row — with empty cells — is a
wildcard removing *every* Mn measurement of that hole. `ADD` appends. Deletes run before
adds, so row order in the file cannot change the outcome. `task index` reports what
happened:

```
Delta source CSV data/assays.csv + 1 delta(s) [data/assays-delta.csv]:
  3 rows added, 3 rows deleted, 0 deletes matched nothing
```

See [docs/03-configuration.md](../docs/03-configuration.md#external-content-csvtsv).

## Quick start (Docker)

```bash
# Build the runtime image from the repo root, then start Fuseki locally from demo/
task -d .. runtime-build
task docker-start

# Load data and run queries (in a separate terminal)
task load
task query

# Stop
task docker-stop         # keep data
task docker-clean        # wipe data volumes
```

To run the same `docker-compose` service using an image published to GitHub Container Registry instead of building locally:

```bash
# Start Fuseki from GHCR
task docker-start GHCR=1

# Stop
task docker-stop
```

## Data model

The demo data (`data/mining.ttl`) contains:

- **6 Sites** — mines and operations across Australian states
- **7 Boreholes** — drill holes linked to sites
- **8 Mining Reports** — geological and production reports
- **4 Authors** — people who authored the reports
- **1 Multi-identifier demo report** — includes `""`, `94130`, and `DAG2011/00113216`

### Relationships

```
MiningReport --ex:authoredBy--> Author --ex:name--> "name string"
Author --ex:authored--> MiningReport
```

Reports link to authors via `ex:authoredBy`. Authors link back via `ex:authored`.
Author names (`ex:name`) are stored on the Author entity, not on the report.

## Index configuration

`config.ttl` defines three SHACL shapes that control what gets indexed:

| Shape | Entity type | Fields |
|-------|------------|--------|
| MiningReportShape | `ex:MiningReport` | title, description, commodity, state, operator, status, year, **authorName**, **authoredByUri** |
| BoreholeShape | `ex:Borehole` | title, commodity, state, depth |
| SiteShape | `ex:Site` | title, commodity, state, status |

### Path types demonstrated

**Direct paths** (most fields): `sh:path rdfs:label` indexes the value directly from the entity.

**Sequence path** (`authorName`):
```turtle
sh:path ( ex:authoredBy ex:name )
```
Traverses from the report to its author, then reads the author's name. The author name
is indexed on the report document even though it's stored on a different entity.

**Inverse path** (`authoredByUri`):
```turtle
sh:path [ sh:inversePath ex:authored ]
```
Finds authors who link to this report via `ex:authored` and indexes their URIs.
This is the reverse direction — instead of following a link from the report,
it finds entities that link *to* the report.

## Queries

| File | What it tests |
|------|--------------|
| `01-basic-search.rq` | Full-text search for "copper" |
| `02-filtered-search.rq` | Search with JSON filter (state=QLD) |
| `03-facet-counts.rq` | Facet counts across commodity, state, operator |
| `04-facet-filtered.rq` | Drill-down: facets for "gold" in WA only |
| `05-combined.rq` | Search + facets in one query (UNION pattern) |
| `06-sequence-path-facet.rq` | Facet counts on `authorName` (sequence path field) |
| `07-filter-by-author.rq` | Filter results by `authorName` = "Dr Sarah Jones" |
| `08-spatial-bbox.rq` | Bounding box spatial search |
| `09-matchraw-multivalue.rq` | Multi-valued identifier search showing `?matchRaw` on dirty identifier data |
| `10-match-field-details.rq` | Access field details like field name and weight |
| `11-hierarchical-facets.rq` | Hierarchical facets using Lucene taxonomy |
| `12-range-facets.rq` | Numeric range facets on `year` and `depth` (INT fields) |
| `13-mixed-facets.rq` | Mixed flat (state) and range (year) facets in one query |
| `14-nested-identifier-hierarchy.rq` | Nested identifier hierarchy facets (`company` / `anumber` / `mnumber`) |
| `15-nested-identifier-prefix-search.rq` | Prefix search over nested identifier text |
| `16-sort-by-year-desc.rq` | Sort reports by `year` descending via field-IRI sort JSON |
| `17-sort-boreholes-by-depth-asc.rq` | Sort boreholes by `depth` ascending via field-IRI sort JSON |
| `18-nested-identifier-company-drilldown.rq` | Drill down identifier values under `identifierType = company` |
| `19-nested-identifier-exact-pair.rq` | Correlated exact filter on `identifierType = company` + `identifierValueExact` |

### Expected results for path queries

**Query 06** should return author facets showing each author wrote 2 reports:
```
authorName  "Dr Priya Patel"   2
authorName  "Dr Sarah Jones"   2
authorName  "James Williams"   2
authorName  "Prof Wei Chen"    2
```

**Query 07** should return exactly 2 reports by Dr Sarah Jones:
```
report-mia-2023  "Mount Isa Copper Resource Estimation 2023"
report-od-2024   "Olympic Dam Expansion Feasibility Study"
```

**Query 09** should return the demo report with `?matchRaw = "94130"` on every row,
even though the entity also carries an empty identifier value:
```
report-identifier-demo  "94130"  ""
report-identifier-demo  "94130"  "94130"
report-identifier-demo  "94130"  "DAG2011/00113216"
```

**Query 12** should return year and depth buckets:
```
year   null  2000  1
year   2000  2020  1
year   2020  null  7
depth  0     200   1
depth  200   500   4
depth  500   1000  2
```

**Query 13** should return state and year facets for "copper":
```
state  state:NSW  null  null  3
state  state:QLD  null  null  3
state  state:SA   null  null  3
state  state:WA   null  null  3
state  state:PNG  null  null  1
year   null       2020  null  0
year   2020       2024  null  3
year   2024       null  null  1
```

## Server image

Image build and publish tasks were moved to the repo root so the `demo/` task file stays focused on demo/test workflows.

Build the runtime image from the repo root:

```bash
task -d .. runtime-build
```

Push from the repo root if needed:

```bash
task -d .. runtime-ghcr-push
task -d .. runtime-acr-push ACR_NAME=myregistry
```

The demo Docker tasks here (`docker-start`, `docker-serve`, `docker-stop`, `docker-clean`, `loader-index`) still work; they now assume the relevant image already exists locally, or in GHCR when passed `GHCR=1`.

## Loader / reindexer image

A separate Docker image for offline bulk data operations: loading N-Quads/Turtle/N-Triples into TDB2 and building the SHACL Lucene index. Useful for large datasets where GSP loading is too slow (e.g. the drillhole dataset — 17.6M entities, 184M triples).

The image runs two steps sequentially:
1. `tdb2.tdbloader` — bulk loads data files into TDB2
2. `shacltextindexer` — builds the SHACL Lucene index from the TDB2 store

### Building

Build the loader image from the repo root:

```bash
task -d .. loader-build
```

This produces `fuseki-lucene-shacl-loader:6.1.0-SNAPSHOT`.

### Running

```bash
docker run --rm \
  -v /path/to/config.ttl:/config/config.ttl:ro \
  -v /path/to/data:/input:ro \
  -v fuseki-db:/data/DB \
  -v fuseki-lucene:/data/Lucene \
  -e JAVA_OPTS="-Xmx8g" \
  fuseki-lucene-shacl-loader:6.1.0-SNAPSHOT
```

| Volume mount | Purpose |
|---|---|
| `/config/config.ttl` | Assembler config (must reference `/data/DB` for TDB2 and `/data/Lucene` for the index) |
| `/input` | Directory containing `.nq`, `.ttl`, or `.nt` data files |
| `/data/DB` | TDB2 database output |
| `/data/Lucene` | Lucene index output |

| Environment variable | Default | Purpose |
|---|---|---|
| `MODE` | `all` | `all` = load + reindex, `load` = TDB2 load only, `index` = SHACL reindex only |
| `CONFIG` | `/config/config.ttl` | Path to assembler config inside the container |
| `DB_DIR` | `/data/DB` | TDB2 output directory |
| `INPUT_DIR` | `/input` | Data files directory |
| `JAVA_OPTS` | (none) | JVM flags, e.g. `-Xmx8g` for large datasets |

If you already have a large TDB2 store and only need to rebuild the SHACL Lucene index, use the safe `MODE=index` task below instead of the loader image's default entrypoint.
For a safe text-only reindex using the existing loader image:

```bash
task loader-index
```

This runs the loader container with `MODE=index`, so it skips `tdb2.tdbloader` and only runs `shacltextindexer`.

### Using with the server image

The loader and server images share volumes, so you can bulk-load data offline then start the server:

```bash
# 1. Load data
docker run --rm \
  -v ./config.ttl:/config/config.ttl:ro \
  -v ./data:/input:ro \
  -v fuseki-db:/data/DB \
  -v fuseki-lucene:/data/Lucene \
  -e JAVA_OPTS="-Xmx8g" \
  ghcr.io/kurrawong/fuseki-lucene-shacl-loader:6.1.0-SNAPSHOT

# 2. Start the server with the same volumes
docker run -d -p 3030:3030 \
  -v ./config.ttl:/fuseki/config.ttl:ro \
  -v fuseki-db:/fuseki/DB \
  -v fuseki-lucene:/fuseki/Lucene \
  ghcr.io/kurrawong/fuseki-lucene-shacl:6.1.0-SNAPSHOT
```

A `docker-compose.yml` example is provided in `loader/`.

### Pushing

Use the root task file for loader publishing:

```bash
task -d .. loader-ghcr-push
task -d .. loader-acr-push ACR_NAME=gswadevacr
```

For safe text-only reindex from GHCR:

```bash
task loader-index GHCR=1
```

## Demo app (FastAPI + Bulma)

A lightweight web UI for interactive faceted search. Built with FastAPI, Jinja2 templates, and Bulma CSS. Provides a search box, sidebar facet checkboxes with counts, and result cards with clickable facet badges.

The app dynamically reads `config.ttl` to discover shapes, fields, and facetability — no hardcoded field names.

```bash
# Install dependencies (once)
task app-setup

# Start the app (Fuseki must be running)
task app
```

Opens at `http://localhost:8000` by default. The app reads its backend base URL from `app-config.js`, which `task app` generates from `FUSEKI_PORT`, then queries `http://localhost:${FUSEKI_PORT}/mining/query` — start the server first with `task serve` or `docker compose up`, then load data with `task load`.

Configure via environment variables:
- `FUSEKI_ENDPOINT` — SPARQL endpoint URL (default: `http://localhost:3030/mining/query`)
- `FUSEKI_CONFIG` — path to assembler config (default: `../config.ttl`)

## Synthetic data generation

Generate larger datasets for performance testing:

```bash
task generate -- --count 1000
```

## Cleanup

```bash
task clean
```
