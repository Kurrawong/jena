# Test case: `keyword-multivalued-sort`

Live, in-browser check of the **GSWA shape**: a `KEYWORD` field that is `sortable` **and**
`multiValued` **and** carries `idx:normalizer`. This is the intersection of the normalizer
feature (#90) and the multi-valued sort fix (#92/#93) — the field a real deployment uses
(`field:nameSort` fed from a multi-valued `schema:name`).

Multi-valued sortable KEYWORD fields use `SortedSetDocValues` with a MIN/MAX selector; the
normalizer must be applied to those bytes, or the sort silently falls back to raw
(case-sensitive). This case confirms it does not.

## Field under test (`config.ttl`)

```turtle
field:nameSort
    idx:fieldType idx:KeywordField ;
    idx:sortable true ;
    idx:multiValued true ;
    idx:normalizer [ a text:LowerCaseKeywordAnalyzer ] .
```

## Run

From `demo/test-cases/`:

```bash
task run    CASE=keyword-multivalued-sort
task verify CASE=keyword-multivalued-sort
task stop
```

UI: `http://localhost:3032/#/dataset/ds/query`

## Expected results

| Query | Demonstrates | Expected |
|-------|--------------|----------|
| `01-multivalued-sort` | multi-valued + normalized sort is case-insensitive | `apple, Zebra, Äpfel` |
| `02-multivalued-min` | ascending uses the **normalized** MIN per entity | `…/eOne` then `…/eTwo` |

If the normalizer were **not** applied to the multi-valued sort key, query 01 would return
raw-byte order (`Zebra, apple, Äpfel`) and query 02 would return `eTwo` before `eOne`
(raw min `"Yak"` < `"Beta"`). Seeing the expected order confirms #93 + #90 compose correctly.
