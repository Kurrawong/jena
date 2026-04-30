# Docker Images

This directory contains two Docker images for building and running Fuseki with
SHACL-based text indexing, spatial indexing, and TDB2 datasets.

## Images

**Dockerfile.loader** — Bulk data loading and index building. Builds the Fuseki
server jar from source, then runs `loader-entrypoint.sh` to load RDF data into
TDB2 and optionally build text and spatial indexes.

**Dockerfile.runtime** — Runs a Fuseki server with a mounted config and
pre-built databases.

## Loader Usage

Load RDF files into a TDB2 dataset:

```bash
docker run \
  -v "./data:/rdf" \
  -v "./databases:/databases" \
  -v "./config.ttl:/config.ttl" \
  --rm \
  ghcr.io/aiworkerjohns/jena-loader:latest
```

The loader reads the assembler config at `/config.ttl` to discover the TDB2
location, text index directory, and spatial index file path via SPARQL queries
against the config itself.

Load with all indexes:

```bash
docker run \
  -e "MODE=all" \
  -v "./data:/rdf" \
  -v "./databases:/databases" \
  -v "./config.ttl:/config.ttl" \
  --rm \
  ghcr.io/aiworkerjohns/jena-loader:latest
```

## Environment Variables

| Variable         | Purpose                                                        | Default                          |
|------------------|----------------------------------------------------------------|----------------------------------|
| `CONFIG`         | Path to the assembler config file inside the container         | `/config.ttl`                    |
| `INPUT_DIR`      | Directory containing RDF files to load                         | `/rdf`                           |
| `MODE`           | Processing mode (see below)                                    | `all`                            |
| `TARGET_GRAPH`   | Named graph URI for triples (see TARGET_GRAPH section below)   | unset                            |
| `NO_VALIDATION`  | If set, skip RDF validation with riot                          | unset (validation enabled)       |
| `TDB2_MODE`      | Loader mode for tdb2.tdbloader: `phased`, `basic`, `sequential`, `parallel`, `light` | `phased` |
| `NO_STATS`       | If set, skip tdb2.tdbstats generation after loading            | unset (stats generated)          |
| `THREADS`        | Number of threads for xloader sort                             | `nproc - 1`                      |
| `JAVA_OPTS`      | JVM arguments passed to the text and spatial indexer commands   | unset                            |

## Modes

| Mode             | What it does                                           |
|------------------|--------------------------------------------------------|
| `all`            | TDB2 load + text index + spatial index + stats         |
| `tdb2`           | TDB2 load with tdb2.tdbloader + stats                 |
| `tdb2.xloader`   | TDB2 load with tdb2.xloader + stats                   |
| `index`          | Text index + spatial index (no TDB2 load)              |
| `text`           | Text index only                                        |
| `spatial`        | Spatial index only                                     |

## Supported RDF Formats

The loader discovers files in `INPUT_DIR` with extensions: `.trig`, `.nq`,
`.ttl`, `.nt`, `.rdf`, `.xml`.

## TARGET_GRAPH and Named Graphs

`TARGET_GRAPH` sets `--graph` on the underlying Jena loader command, directing
triples into a specific named graph. Its behavior depends on the loader and data
format:

| Scenario                   | tdb2.tdbloader                                       | tdb2.xloader        |
|----------------------------|------------------------------------------------------|---------------------|
| Triples + `TARGET_GRAPH`   | Loads into the named graph                           | **Not supported**   |
| Quads + `TARGET_GRAPH`     | Only default graph triples go to named graph (warns) | **Not supported**   |
| Quads, no `TARGET_GRAPH`   | Respects graph URIs in the data                      | Respects graph URIs in the data |
| Triples, no `TARGET_GRAPH` | Loads into the default graph                         | Loads into the default graph    |

**tdb2.tdbloader**: When `TARGET_GRAPH` is set and quad files (`.nq`, `.trig`)
are provided, only the default graph portion is loaded into the target graph.
Named graph data in the file is silently dropped, with a warning to stderr.

**tdb2.xloader**: Does not accept `--graph` at all. It only recognizes `--loc`,
`--tmpdir`, and `--threads`. Setting `TARGET_GRAPH` with `MODE=tdb2.xloader`
will cause an error. To load triples into a named graph with xloader, convert
the data to a quad format (`.nq` or `.trig`) with the desired graph URI before
loading.

**Quad files without TARGET_GRAPH**: Both loaders respect the graph URIs
embedded in the data. This is the expected way to load data into named graphs.

**xloader volume mounts**: xloader requires `--loc` to point to a directory that
does not yet exist (it creates it). When using Docker, mount the volume at the
**parent** of the `tdb2:location` path, not the location itself. For example, if
the config has `tdb2:location "/data/DB/ds"`, mount the volume at `/data/DB`:

```yaml
volumes:
  - my-volume:/data/DB    # parent of tdb2:location
```

If the volume is mounted directly at the `tdb2:location` path, xloader will fail
because Docker pre-creates the mount point directory.

## Runtime Usage

Run Fuseki with pre-built databases:

```bash
docker run \
  -v "./databases:/databases" \
  -v "./config.ttl:/config/config.ttl" \
  -p 3030:3030 \
  ghcr.io/aiworkerjohns/jena-runtime:latest
```

The runtime image starts Fuseki with `--config /config/config.ttl` and enables
the `jdk.incubator.vector` module for native access.

## Building Locally

From the repository root:

```bash
# Loader image
docker build -f build-files/docker/Dockerfile.loader -t jena-loader:dev .

# Runtime image
docker build -f build-files/docker/Dockerfile.runtime -t jena-runtime:dev .
```

Both Dockerfiles use a multi-stage build: Maven builds `jena-fuseki-server.jar`
from source (skipping tests and javadoc), then copies the jar into an
`eclipse-temurin:21-jre` runtime image.

## Testing xloader

A test harness in `test-xloader/` verifies xloader behavior with multiple input
files:

```bash
cd build-files/docker/test-xloader
task test
```

This loads two TTL files (4 subjects total) via xloader and queries the result
to confirm all data was loaded.