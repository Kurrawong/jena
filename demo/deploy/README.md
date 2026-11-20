<!-- Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0 -->

# Deployed demo

The recipes demo in [demo/](../README.md) as a single container: dataset, index and
SPARQL endpoint in one process, with nothing on disk and no arguments to pass.

```bash
docker run --rm -p 3030:3030 ghcr.io/kurrawong/fuseki-lucene-shacl-demo:latest
```

| URL | |
|---|---|
| `/` | Fuseki UI: query editor and dataset browser |
| `/kitchen/query` | SPARQL query, including `luc:query`, `luc:facet` and `luc:config` |

The Fuseki UI has to be at the root: it prefixes every API call with the path it was
served from, so anywhere else it asks for `/somewhere/$/server` and gets a 404.

From the repository root:

```bash
task demo-build
```

```bash
task demo-run DEMO_PORT=3040
```

```bash
demo/check.sh http://localhost:3040
```

`demo-run` stops a previous demo container on that port first. `task demo-stop` matches
the container by the label and name `demo-run` gives it, so it does not stop another
Fuseki on the same port.

## How it holds together

| Piece | Choice | Why |
|---|---|---|
| Configuration | `demo/config.ttl`, the same file `task serve` runs | One configuration to keep correct; `TestDemoQueries` in jena-text runs every demo query against it |
| Data | `ja:MemoryDataset` and `ja:data` | A few hundred triples. There is no volume to mount, back up or leave stale |
| Index | `text:directory "mem"` | The facet taxonomy follows the index into memory |
| Indexing | `text:buildOnStartup true` | Runs the bulk indexer, including the CSV review children, as the dataset is assembled. See [docs/03-configuration.md](../../docs/03-configuration.md#textbuildonstartup) |
| Working directory | `WORKDIR /demo` | `idx:location "data/reviews.csv"` is resolved against the working directory |
| Writes | none | Query endpoint only. The index is rebuilt from the files on every start, so a write would not survive a restart |
| Admin | [shiro.ini](shiro.ini) | `/$/**` denied outright; `/$/ping` and the read-only `/$/server` and `/$/stats` the UI needs are opened by name. Create, delete and backup live under `/$/datasets` and stay shut |

Every start rebuilds from the files in the image, so a redeploy has no state to lose.

## Hosting

The image needs a container host with a JVM's worth of memory, such as Cloud Run, Azure
Container Apps, Fly or App Runner. It reads `PORT` when the host sets one and otherwise
listens on 3030. Cloudflare Workers cannot run it, because there is no JVM there.

Point the deployment at `:latest` to follow `main`, or at `sha-<short>` to pin. CI builds
the image on every push and pull request, runs `demo/check.sh` against it, and pushes it
on `main` only.
