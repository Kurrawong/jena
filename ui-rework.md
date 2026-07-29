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

Separate branch from #130 — that PR is green and self-contained, and folding a UI rework
into it would make it much harder to review. The engine change (rank-as-score) has landed;
the CONSTRUCT rewrite and `labels.js` come next.
