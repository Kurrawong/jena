# Test case: `keyword-sort-demo`

Demonstrates the **current (pre-normalizer) behaviour** of sortable / exact-match
`KEYWORD` fields, as described in
[`docs/2026-06-25_keyword_normalizer_for_sortable_fields.md`](../../../docs/2026-06-25_keyword_normalizer_for_sortable_fields.md).
It is the live, in-browser counterpart of the JUnit test
`jena-text/.../TestKeywordRawSortAndExactMatch.java`.

## Files

| File | Purpose |
|------|---------|
| `config.ttl` | Fuseki server + SHACL Lucene index, **fully in-memory** (`ja:DatasetTxnMem` + `text:directory "mem"`). Service name `ds`. |
| `data.ttl` | 6 `ex:Thing` entities, all `ex:label "thing"`, with `ex:name` values chosen to expose raw-byte sort. |
| `queries/*.rq` | One SPARQL file per finding (each has an `#@` line describing intent + expected result). |

## The index (`config.ttl`)

- `field:label` — `TextField`, `defaultSearch` → lets the single query string `"thing"` match every entity.
- `field:name` — `KeywordField`, `sortable` → the field under test (raw bytes, verbatim match).
- `field:nameLC` — `KeywordField`, `sortable`, **with** `idx:analyzer [ a text:LowerCaseKeywordAnalyzer ]` → proves the analyzer slot is inert on KEYWORD.

`field:name` and `field:nameLC` are populated from the **same** predicate (`ex:name`).

## Run it

From `demo/test-cases/`:

```bash
task run CASE=keyword-sort-demo          # build-if-needed not included; run `task build` once first
# then open the printed UI URL, or:
task verify CASE=keyword-sort-demo       # runs all queries, prints results to compare below
task stop
```

UI: `http://localhost:3032/#/dataset/ds/query`

## Expected results

Queries 01–04 are **controls** — always raw/case-sensitive regardless of the feature.
Queries 05–06 exercise `idx:normalizer` (`field:nameCI`) and **change** once the feature is
implemented. Captured snapshots live in [`results/`](results/) (`pre-work.md`, `post-work.md`).

| Query | Field | Demonstrates | Pre-work `?name` | Post-work `?name` |
|-------|-------|--------------|------------------|-------------------|
| `01-sort-raw-bytes`   | `name` (raw) | KEYWORD sort = raw UTF-8 bytes | `Apple, Smith, Zebra, apple, banana, Äpfel` | *(same)* |
| `02-exact-match-miss` | `name` | `= "smith"` ≠ `"Smith"` | *(0 rows)* | *(same)* |
| `03-exact-match-hit`  | `name` | `= "Smith"` matches | `Smith` | *(same)* |
| `04-analyzer-inert`   | `nameLC` (`idx:analyzer`) | analyzer slot is inert on KEYWORD | same as 01 | *(same — still inert)* |
| `05-normalizer-sort`  | `nameCI` (`idx:normalizer`) | normalizer → case-insensitive sort | same as 01 (feature off) | `apple, Apple, banana, Smith, Zebra, Äpfel` |
| `06-normalizer-match` | `nameCI` | normalizer → case-insensitive `=` | *(0 rows)* | `Smith` |

Raw-byte ordering (01): `A`=0x41 < `S`=0x53 < `Z`=0x5A < `a`=0x61 < `b`=0x62 < `Ä`=0xC3.
Normalized (lower-cased) ordering (05): the sort key is the lower-cased value, so `Apple`/`apple`
collate together and `Smith`/`Zebra` fall in alphabetical position; the stored/returned value
stays the original casing.

The controls staying raw while only `nameCI` changes proves the feature is **additive and
opt-in** — plain KEYWORD fields (and the inert `idx:analyzer`) are untouched.
