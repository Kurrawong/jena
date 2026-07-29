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

Fixed without adding an output position: a sorted search now binds `1/(1+rank)` as the
score (`ShaclTextIndexLucene.rankScore`). Rank and score are equivalent for the client's
purpose — both mean "sort descending to get the requested order" — so `b.score - a.score`
alone recovers the order and `orderedUris` is no longer needed. The value depends only on
rank, so a hit keeps its score when a later page re-runs the search with a larger window.
`luc:query` still binds `(?hit ?entity ?score ?totalHits ?graph)`.

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

### Loose end: `jlsr:facetName` carries the wrong thing

Faceting on `urn:jena:lucene:field#commodity` returned `jlsr:facetName "state_commodity"`
— the *hierarchy* name, not the requested field IRI. Decide what that predicate should
carry before any client depends on it.

## Proposed

1. ~~Add a rank output to `luc:query`.~~ **Done differently** — scores now carry the rank
   for sorted searches (see above). No signature change, nothing breaking.
2. **One CONSTRUCT for search + facets**, as sketched; page 2+ omits the facet UNION when
   filters are unchanged.
3. **A thin `labels.js`** modelled on `label-cache-client`: `resolve` / `resolveMany`,
   parallel and bounded, in-flight de-dupe, never sets `cache: "no-store"`, one `GET` per
   IRI.
4. **`serve_app.py` sets a sane `Cache-Control`** on label GETs so the browser cache is
   actually usable.

## Open decisions

- **Scope:** rework `app.js` in place, or build the CONSTRUCT + label path as a parallel
  mode that can be compared against the current one?
- **Cache lifetime:** labels are mutable in principle. `max-age` plus an app-level version
  or query-string salt to bust it on demand?
- **Language handling:** `label-cache-client` does client-side language fallback because
  the server has none. Do we want the same, or push `LANG()` filtering into the query?

## Sequencing

Separate branch from #130 — that PR is green and self-contained, and folding a UI rework
into it would make it much harder to review. The engine change (rank-as-score) has landed;
the CONSTRUCT rewrite and `labels.js` come next.
