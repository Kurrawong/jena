<!-- Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0 -->

# Deployed demo

The mining demo as a single container: dataset, index, SPARQL endpoint and browser app
in one process, with nothing on disk and no arguments to pass.

```bash
docker run --rm -p 3030:3030 ghcr.io/kurrawong/fuseki-lucene-shacl-demo:latest
```

| URL | |
|---|---|
| `/` | Fuseki UI — query editor, dataset browser |
| `/demo` | the faceted search app |
| `/mining/query` | SPARQL |

The Fuseki UI has to be the one at the root: it prefixes every API call with the path it
was served from, so anywhere else it asks for `/somewhere/$/server` and gets a 404. The
search app has no such constraint — relative assets, and its API base comes from
`window.location.origin` — so it is the one that moves.

From the repository, with rebuild-and-rerun as the loop:

```bash
task demo-build
task demo-run DEMO_PORT=3040   # stops a previous demo container on that port first
task demo-stop DEMO_PORT=3040
```

`demo-stop` matches on image *and* port, so pointing `DEMO_PORT` at a port some other
Fuseki already holds will not stop it.

Locally, from a built jar and without Docker — the search app only, at the root, since
assembling the two-front-end tree is the image build's job:

```bash
task build            # from the repository root
task serve-deployed   # from demo/
```

## How it holds together

| Piece | Choice | Why |
|---|---|---|
| Data | `ja:MemoryDataset` + `ja:data` | 260 KB of Turtle. Loading it costs less than opening a database, and there is no volume to mount, back up or leave stale |
| Index | `text:directory "mem"` | The taxonomy follows the index into memory, so hierarchical facets need no separate declaration |
| Indexing | `text:buildOnStartup true` | Runs the bulk indexer as the dataset is assembled — 524 entities in about a second. See [docs/03-configuration.md](../../docs/03-configuration.md#textbuildonstartup) |
| App | Fuseki's `--base` | Jetty serves `demo/app-static` itself, so the app is same-origin: no proxy, no CORS, no second process, one port |
| Writes | none | Query endpoint only. The index is rebuilt from `ja:data` on every start, so a write would not survive a restart anyway |
| Admin | `shiro.ini` | `/$/**` denied outright; `/$/ping`, `/$/config`, and the read-only `/$/server` and `/$/stats` the UI needs are opened by name. Create, delete and backup all live under `/$/datasets` and stay shut, so those UI pages report an error |

Every start rebuilds from the Turtle baked into the image, which is what makes it safe to
redeploy on each push and to run at scale-to-zero: there is no state a redeploy could
lose, and a cold start is a JVM start plus about a second of indexing.

## Files

| File | |
|---|---|
| `config.ttl` | Derived from `../test/config.ttl` — same fields and shapes, three deliberate differences listed in its header. `TestDemoDeployConfig` fails if the field sets drift apart |
| `shiro.ini` | Admin lockdown, copied into the image at `FUSEKI_BASE` |
| `app-config.js` | Same-origin app configuration (`fusekiBase: ''`), replacing the checked-in one that points at the local `serve_app.py` proxy |

The assay CSV (`idx:externalSource`) is left out. It is rebuild-only: the bulk indexer has
to outlive its own process to hand the result over, which an in-memory index cannot do.
Everything else the demo shows is here.

## Hosting

The image needs a container host with a JVM's worth of memory — Cloud Run, Azure
Container Apps, Fly, App Runner. It reads `PORT` when the host sets one and otherwise
listens on 3030, so the usual scale-to-zero setups work unmodified. Cloudflare Workers
cannot run it; there is no JVM there.

Point the deployment at `:latest` to follow `main`, or at `sha-<short>` to pin. CI builds
and smoke-tests the image on every push and pull request, and pushes it on `main` only.
