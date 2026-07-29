# Demo UI rework — CONSTRUCT results + cached label resolution

Working note, not documentation. Captures what was investigated on 2026-07-29 and what
the plan is. Everything under **Verified** was tested against the running mining demo;
everything under **Proposed** is not built yet.

## Goal

Move the demo app from SELECT-shaped result handling to an RDF payload:

1. One `CONSTRUCT` per search returning hits **and** facet buckets together, `LIMIT 10`.
2. Page 2+ drops the facet branch when no filter changed.
3. Every IRI in the response gets its label fetched separately and cached by the browser.

The label-fetching pattern is taken from `../label-cdn`
(`packages/label-cache-client`) — **without** introducing a CDN, and with SPARQL rather
than blob storage as the backend.

## Verified

### The UI already re-sorts client-side

`demo/app-static/app.js:2041`:

```js
if (orderedUris && orderedUris.length > 0) {
    const rank = new Map(orderedUris.map((uri, idx) => [uri, idx]));
    return cards.sort((a, b) => (rank.get(a.uri) ?? MAX) - (rank.get(b.uri) ?? MAX));
}
return cards.sort((a, b) => b.score - a.score);
```

It depends on **SELECT preserving row order** to build `orderedUris`. That assumption is
exactly what a CONSTRUCT graph removes.

### The CONSTRUCT query works

`luc:query` and `luc:facet` in separate UNION branches inside `CONSTRUCT` run fine, as do
`SHA256()` for stable result IRIs and `BNODE()` for facet buckets. No engine change is
needed to *execute* the proposed query.

### …the ordering was unrecoverable — fixed

Sorting by a field (`{"field":"…#depth","order":"asc"}`) used to return:

```turtle
<urn:hash:result-a770df84…> a jlsr:SearchResult ;
    jlsr:searchResultURI     ex:report-0004 ;
    jlsr:searchResultWeight  "NaN"^^xsd:float .
```

**`NaN` for every hit.** Lucene skips relevance scoring when sorting by a field, so there
was no score to emit. A CONSTRUCT graph is unordered by definition, so with `?score`
unusable the client had *no* signal to reconstruct the requested order.

Fixed in two steps, because the first turned out not to be enough.

**First**, `NaN` was replaced: a sorted search now binds `1/(1+rank)` as the score
(`ShaclTextIndexLucene.rankScore`), which depends only on rank, so a hit keeps its score
when a later page re-runs the search with a larger window.

**Then testing against the running demo showed score cannot carry order at all.** Sorting
was only the visible half of the problem:

| Search | `?score` | Order recoverable? |
|---|---|---|
| sorted by depth | `1.0, 0.5, 0.33, 0.25` | yes |
| `*` — the demo's default view | `1.0, 1.0, 1.0, 1.0` | **no** |
| `gold exploration` | `3.6702733, 3.6702733, 3.6379592, …` | **no** |

`MatchAllDocsQuery` scores every document identically and real relevance scores tie. A
CONSTRUCT of the default view came back in reverse rank order with every score at `1.0` —
nothing to sort on.

So `luc:query` now binds **`?rank`**, the hit's position in the whole result set, counting
from 0 and continuing across pages: `(?hit ?entity ?score ?rank ?totalHits ?graph)`.
`?score` keeps its true relevance value. `orderedUris` is gone; the client sorts on rank.

Inserting `?rank` before `?totalHits` breaks the 4- and 5-argument subject forms —
permitted for the `luc:` surface by `CLAUDE.md`, and cheaper than shipping a position we
would only have to move again before release. The 68 three-argument call sites are
unaffected; 7 needed updating.

### Labels: the cache tier is the browser HTTP cache

`label-cache-client` does not use localStorage. Its README:

> **Prefers the browser cache.** It never busts it (no `cache: "no-store"`), so a
> returning view can be served from the browser's own HTTP cache without a network
> transfer. In the browser it adds only an in-flight de-dupe.

| Tier | Role | Survives reload |
|---|---|---|
| In-memory `Map` | in-flight de-dupe only — collapses concurrent requests for one IRI | no |
| **HTTP cache** | **the actual cache; zero network on repeat** | **yes** |
| localStorage / IndexedDB | manual, needs eviction and invalidation | yes |

Two things currently block using it:

- **SPARQL POST is not cacheable.** Labels must go over `GET /query?query=…` so each IRI
  is its own cache key. Fuseki serves GET queries fine (verified, HTTP 200).
- **Fuseki actively disables the cache.** It responds
  `Cache-Control: must-revalidate,no-cache,no-store`. `demo/serve_app.py` already proxies
  `/fuseki` → `:3030`, so it can rewrite that header for label GETs. No Fuseki change.

This also settles per-IRI vs batched: a `VALUES ?iri {…}` batch is one request but a
different URL per batch composition, so the hit rate collapses. Per-IRI GETs are
individually cacheable — which is why `label-cache-client` issues N requests bounded at
100 in flight rather than one large one.

### Loose end: `jlsr:facetName` carried the wrong thing — fixed

Faceting on `urn:jena:lucene:field#commodity` returned `jlsr:facetName "state_commodity"`.
Investigating turned up three stacked defects, not a naming nit:

1. `resolveFacetFieldNames` redirected any field IRI belonging to a `facetHierarchy` to
   that hierarchy's dimension, so the request for `#commodity` stopped existing.
2. A taxonomy dimension with no drill-down path returns its **top** level, so the counts
   that came back were for `state`, not `commodity` — right shape, wrong field.
3. `TextFacetPF` could not resolve `"state_commodity"` to a field, so `?field` degraded
   from an IRI to a plain string literal — a type that varies per bucket.

Every facetable field is already indexed as its own flat dimension regardless of hierarchy
membership, so the correct counts were in the index all along. The redirect is gone;
dimensions remain addressable by name and CQL drill-down is untouched. Non-facetable
fields now fail fast instead of returning silently empty.

`?field` is now always an IRI for a field-IRI request — the client can key facet UI off it.

## Built

All four are done and verified against the running mining demo.

1. **`?rank` on `luc:query`** — see above. Needed after all, though for a broader reason
   than the original note gave.
2. **One CONSTRUCT for search + facets**; page 2+ omits the facet UNION when the query and
   filters are unchanged (`facetStateKey`).
3. **`labels.js`** modelled on `label-cache-client`: `resolve` / `resolveMany`, bounded at
   100 in flight, in-flight de-dupe, never sets `cache: "no-store"`, one `GET` per IRI.
4. **`serve_app.py` sets `Cache-Control`** on label GETs only — verified that a label
   request gets `public, max-age=86400` while an ordinary query keeps Fuseki's `no-store`.

### Known-failing demo tests — not caused by this work

The Playwright suite is 56 passed / 2 failed. Both failures reproduce on `main` with a
pre-change engine, verified by building `main` in a worktree and running the identical
queries:

- **French review note** — `?q=réserves` searches default fields, but `field:reviewNote`
  has no `idx:defaultSearch true`, so it can never match. Searching the field explicitly
  finds `report-cad-2023`. Either the config or the test expectation is wrong.
- **Qualified attribution: PI + Sarah Jones** — returns 0 although `report-mia-2023` has
  role "Principal Investigator" and agent "Dr Sarah Jones" on the *same* attribution node.
  The correlated nested filter looks genuinely broken; worth its own issue.

## Decisions

- **Scope:** rework `app.js` in place. No parallel mode, no flag.
- **Cache lifetime:** `max-age` set by `serve_app.py`, plus a version salt on the label URL
  from `app-config.js` so the whole label cache can be busted on demand.
- **Language handling: out of scope.** One `GET` per IRI, no `lang` parameter, no fallback
  chain, no `LANG()` filter; the label query takes `LIMIT 1`. The demo's own data is
  entirely untagged — 54 `rdfs:label`s, none with a language tag — and this is a demo of
  Lucene faceting, not of RDF language negotiation. `label-cache-client` keys its cache on
  `(IRI, lang)` and retries down a fallback chain; we deliberately do not.

## Sequencing

#130 merged to `main` as `0f966ebdca`, so this work moved to `feat/demo-ui-construct-labels`
off `main` rather than continuing on `feat/external-content-indexing`. Everything above has
landed on that branch: engine changes first, then the client.
