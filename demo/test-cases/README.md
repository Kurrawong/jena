# Fuseki test-cases harness

A small, reusable setup for spinning up a Fuseki server against a self-contained
scenario so you can **see the behaviour in the browser** (and in the editor). Use it to
reproduce/validate index behaviour live — the in-browser counterpart of the jena-text
JUnit tests.

Each subdirectory that contains a `config.ttl` is a **test case**. Pick one with
`CASE=<dir>`. Every case is served under the dataset name **`ds`**.

```
test-cases/
  Taskfile.yml            # this harness
  README.md
  keyword-sort-demo/      # a case
    config.ttl            #   Fuseki + SHACL index config (in-memory)
    data.ttl              #   sample data, loaded via GSP
    queries/*.rq          #   one SPARQL file per thing being shown (with an #@ intent line)
    README.md             #   what it shows + expected results
```

## Usage

From this directory (`demo/test-cases/`):

```bash
task build                              # once — builds the Fuseki server jar
task list                               # list available cases
task run    CASE=keyword-sort-demo      # start (background) + load, prints the UI URL
task verify CASE=keyword-sort-demo      # run every queries/*.rq, print results to eyeball
task query  CASE=keyword-sort-demo Q=01-sort-raw-bytes   # run a single query
task serve  CASE=keyword-sort-demo      # foreground instead (Ctrl-C to stop); load separately
task stop                               # stop the server
```

UI for any case: `http://localhost:3032/#/dataset/ds/query`

Override the port with `PORT=3033` on any command (if you change it, also update
`idx:fusekiBase` in the case's `config.ttl` — it's only used for facet links, queries work
regardless).

## How it stays clean

- Cases use an **in-memory** dataset (`ja:DatasetTxnMem`) and an in-memory Lucene
  directory (`text:directory "mem"`), so nothing is written under the repo — data is fresh
  on every `task run`.
- Fuseki's working area (`FUSEKI_BASE`) and the server log are sent to `/tmp`, so they
  never appear in the repo or trip the RAT license check during `task build`.

## Adding a new case

1. `mkdir mycase && cd mycase`
2. Add `config.ttl` — copy `keyword-sort-demo/config.ttl`, keep `fuseki:name "ds"`, change the
   shapes/fields. Keep it in-memory (`ja:DatasetTxnMem` + `text:directory "mem"`).
3. Add `data.ttl` with sample triples.
4. Add `queries/NN-name.rq` files. Start each with the license line and an `#@` line
   describing intent / expected result (shown by `task verify`).
5. Add a `README.md` with an Expected-results table.
6. `task run CASE=mycase` then `task verify CASE=mycase`.

> Turtle/SPARQL files need the first line `## Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0`
> (RAT license check). `README.md` files are exempt.
