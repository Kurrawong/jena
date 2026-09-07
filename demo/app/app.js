/* Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0 */

/*
 * demo-mini — a deliberately small faceted-search client.
 *
 * Two things are worth knowing before reading on:
 *
 * 1. The app discovers the index shape from GET /$/config/effective. It does NOT parse
 *    config.ttl. Field types, facetability, the hierarchy dimension name and the
 *    index-fingerprint status all come from the server, which is the only party that
 *    knows what Jena actually resolved.
 *
 * 2. Labels are never joined into the search query. Every IRI is resolved by its own
 *    cacheable GET through labels.js, so the browser's HTTP cache serves repeat views
 *    with no network at all. See labels.js for why that beats one batched VALUES query.
 */

const BASE = '/fuseki';
const ENDPOINT = `${BASE}/kitchen/query`;
const F = n => `urn:jena:lucene:field#${n}`;
const KT = 'http://example.org/kitchen/';
const PAGE_SIZE = 5;

const labels = new LabelResolver(ENDPOINT, '1');

/** Years covered by the data, discovered at startup — see loadDateAxis(). */
const dateAxis = { years: [] };

/** Every sh:targetClass in the index, from /$/config/effective. */
const kinds = { classes: [] };

/**
 * The hierarchical dimensions, each an ordered list of levels.
 *
 * `key` is filled in from /$/config/effective at startup: a dimension is named by joining
 * its levels' idx:fieldName values with "_", which is not something this client should be
 * asserting for itself.
 *
 * Nothing here is limited to two levels. Drilling is a PATH, and the server returns the
 * children of whatever path it is given — so a three-level dimension is the same code with
 * one more entry in `fields`.
 */
const DIMS = {
    region: {
        key: 'region_country',
        title: 'Region / country',
        fields: [F('region'), F('country')],
        label: 'iri',
    },
    ingredient: {
        key: 'ingredient_ingredientGrams',
        title: 'Ingredients',
        note: 'children from the graph, not a file — drill an ingredient to see its quantities',
        fields: [F('ingredient'), F('ingredientGrams')],
        label: 'iri',
        // grams is an IntField; "=" on the string the facet returns would match nothing.
        cast: [null, Number],
    },
    review: {
        key: 'reviewer_stars_reviewMonth',
        title: null,                      // rendered in its own group in index.html
        fields: [F('reviewer'), F('stars'), F('reviewMonth')],
        label: 'plain',
        // stars is an IntField, so its "=" needs a number and not the string the facet
        // hands back. Getting this wrong filters nothing and reports no error.
        cast: [null, Number, null],
    },
};

/** Flat facet panels, in display order. */
const FACETS = [
    { key: F('course'), title: 'Course' },
    { key: F('diet'), title: 'Diet' },
    { key: F('contributor'), title: 'People (author or tester)', note: 'one field, two SHACL paths' },
];

const PREP_RANGES = [null, 20, 60, 240, null];

const TESTS = [
    '01-search-bm25', '02-facets-flat', '03-facets-filtered', '04-hierarchy-top',
    '05-hierarchy-drill', '06-date-range', '07-range-facets', '08-typeahead-ngram',
    '09-fanin-contributor', '10-keyword-iri-filter', '11-match', '12-nested-match-reviews',
    '13-nested-correlation', '14-sort-and-page', '15-vector-search', '16-vector-filtered',
    '17-nested-hierarchy-correlated', '18-one-field-many-paths-many-shapes',
    '19-three-level-drilldown', '20-ingredient-quantity-correlated',
];

const state = {
    q: '', mode: 'bm25', sort: '', page: 0,
    sel: {},            // fieldIRI -> Set of values (multi-select)
    /*
     * One entry per hierarchical dimension.
     *   path  — the values currently expanded, outermost first. Expanding is free: it asks
     *           luc:facet for that path's children and narrows nothing.
     *   depth — how many of those path values are ALSO filters. Ticking a box at level i
     *           sets depth to i+1; the twisty leaves depth alone.
     * Keeping them apart is what lets a node be opened without being filtered by.
     */
    drill: { region: { path: [], depth: 0 }, ingredient: { path: [], depth: 0 },
             review: { path: [], depth: 0 } },
    // null = both kinds. Only two shapes exist, so "both" is genuinely the absence of a
    // filter rather than an "in" of every class.
    kind: null,
    // null means "the whole span", which is not a filter at all. Otherwise [lo, hi] are
    // indices into dateAxis.years, so the slider and the filter cannot disagree.
    yearIdx: null,
    minStars: '',
    prepBucket: null,   // [low, high]
    config: null,
};

/* ── tiny helpers ─────────────────────────────────────────────────────── */

const el = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const isIri = v => typeof v === 'string' && /^https?:|^urn:/.test(v);
const shortIri = v => isIri(v) ? v.replace(KT, 'kt:').replace('urn:jena:lucene:field#', 'field:') : v;

const prop = f => ({ property: f });
const eq = (f, v) => ({ op: '=', args: [prop(f), v] });
const anyOf = (f, vals) => vals.length === 1 ? eq(f, vals[0]) : { op: 'or', args: vals.map(v => eq(f, v)) };

/** Same endpoint, but asking for Turtle so the raw payload can be shown verbatim. */
async function sparqlTurtle(query) {
    const resp = await fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sparql-query', Accept: 'text/turtle' },
        body: query,
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${(await resp.text()).slice(0, 400)}`);
    return resp.text();
}

async function sparql(query) {
    const resp = await fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sparql-query', Accept: 'application/sparql-results+json' },
        body: query,
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${(await resp.text()).slice(0, 400)}`);
    return resp.json();
}

/* ── the CQL2-JSON filter, built from the whole UI state ───────────────── */

/**
 * @param skip  clause groups to leave out: a dimension id ("region", "review"), or
 *              "kind" / "date".
 * @param pin   dimension id -> path to force, replacing whatever is selected.
 *
 * A facet must not be counted under its own filter, or drilling into a value hides every
 * sibling and the value itself. Asking for the region level with the region clause omitted
 * is what keeps all three regions on screen, counted correctly under the OTHER filters,
 * with the chosen one ticked. Standard faceted-search behaviour; Solr and Elasticsearch
 * spell it as excluding a tagged filter.
 */
function buildFilter(skip = {}, pin = {}) {
    const clauses = [];

    for (const [field, values] of Object.entries(state.sel)) {
        if (values.size) clauses.push(anyOf(field, [...values]));
    }

    if (state.kind && !skip.kind) clauses.push(eq(F('entityType'), state.kind));

    // Each dimension contributes the leading `depth` values of its path as "=" clauses.
    // `pin` overrides that for one dimension, which is how a level's children are requested
    // without the path becoming a filter on the results.
    for (const [id, dim] of Object.entries(DIMS)) {
        const values = pin[id] ?? (skip[id] ? [] : state.drill[id].path.slice(0, state.drill[id].depth));
        values.forEach((v, i) => {
            const cast = dim.cast?.[i];
            clauses.push(eq(dim.fields[i], cast ? cast(v) : v));
        });
    }

    if (state.yearIdx && !skip.date) {
        const [lo, hi] = state.yearIdx;
        clauses.push({ op: 'between', args: [prop(F('publishedOn')),
            `${dateAxis.years[lo]}-01-01`, `${dateAxis.years[hi]}-12-31`] });
    }

    if (state.prepBucket) {
        const [lo, hi] = state.prepBucket;
        if (lo !== null) clauses.push({ op: '>=', args: [prop(F('prepMinutes')), lo] });
        if (hi !== null) clauses.push({ op: '<', args: [prop(F('prepMinutes')), hi] });
    }

    // These are the same-child pair. They sit as siblings in the top-level "and", so the
    // compiler folds them into one block join: ONE review must satisfy both. Selecting
    // "Priya" and "5" finds recipes Priya rated 5 — not recipes Priya reviewed that someone
    // else rated 5. Selecting a reviewer also drives the reviewer_stars drill-down.
    if (state.minStars && !skip.review) {
        clauses.push({ op: '>=', args: [prop(F('stars')), Number(state.minStars)] });
    }

    // The typeahead field is analyzer-backed, so it needs text_query. "=" would compare a
    // raw term against edge n-grams and match nothing.
    if (state.mode === 'code' && state.q.trim()) {
        clauses.push({ op: 'text_query', args: [prop(F('codeText')), state.q.trim()] });
    }

    if (!clauses.length) return '';
    return JSON.stringify(clauses.length === 1 ? clauses[0] : { op: 'and', args: clauses });
}

function fieldSpec() {
    return state.mode === 'semantic' ? JSON.stringify([F('embedding')]) : 'default';
}

function queryString() {
    if (state.mode === 'semantic') return state.q.trim();
    if (state.mode === 'code') return '*';
    return state.q.trim() || '*';
}

function sortSpec() {
    if (!state.sort) return '';
    const [field, order] = state.sort.split(':');
    // Every sortable field belongs to the recipe shape, so a reviewer has no value for it.
    // Without "missing", Lucene's own default puts those first and a sort by prep time
    // opens with six things that do not have one.
    return JSON.stringify({ field: F(field), order, missing: 'last' });
}

const lit = s => `"${String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;

/** The 7 luc:query / luc:facet arguments shared by every request in this view. */
function argsFor(kind, skip = {}, fields = null, pin = {}) {
    const fs = fieldSpec();
    const spec = fs === 'default' ? '"default"' : `'${fs}'`;
    const q = lit(queryString());
    const filter = buildFilter(skip, pin);
    const f = filter ? `'${filter}'` : '""';
    if (kind === 'facet') {
        const list = fields ? JSON.stringify(fields) : facetFields();
        return `"default" ${spec} ${q} '${list}' ${f} 50 0`;
    }
    const sort = sortSpec();
    const s = sort ? `'${sort}'` : '""';
    return `"default" ${spec} ${q} ${f} ${s} ${PAGE_SIZE} ${state.page * PAGE_SIZE}`;
}

/**
 * The facet-field list the sidebar asks for. One definition, used by the query and by the
 * URL, so the ?facet= a link carries is the list that was actually requested.
 */
function facetFields() {
    return JSON.stringify([
        ...Object.values(DIMS).map(d => d.key),
        ...FACETS.map(x => x.key),
        { field: F('prepMinutes'), ranges: PREP_RANGES },
    ]);
}

/* ── queries ───────────────────────────────────────────────────────────── */

/**
 * Everything each hit says about itself, one row per triple.
 *
 * This is the DESCRIBE-shaped projection rather than a column per property, which is what
 * removes the pile of OPTIONALs: a reviewer is not a recipe with null columns, it simply
 * has fewer triples. Adding a third shape needs no change here at all.
 *
 * Not a literal DESCRIBE only because that returns a graph, and turning a graph into
 * something renderable needs an RDF parser in the browser — for no gain, since the card's
 * RDF column already shows the real thing (see recordQuery). Grouping ?p/?o by subject is
 * the same information in a form the client already handles.
 */
function searchQuery() {
    return `PREFIX luc: <urn:jena:lucene:index#>
PREFIX kt:  <${KT}>

SELECT ?entity ?rank ?totalHits ?score ?p ?o
WHERE {
    (?hit ?entity ?score ?totalHits ?rank) luc:query (${argsFor('query')}) .
    {
        ?entity ?p ?o
    }
    UNION
    {
        # The one value on a card that is not a triple on the entity. A recipe's region is
        # one hop off its country — exactly how the index reaches it, with the sequence
        # path ( kt:country kt:inRegion ) — so it is fetched the same way and labelled with
        # the predicate that derives it.
        ?entity kt:country/kt:inRegion ?o .
        BIND(kt:inRegion AS ?p)
    }
}
ORDER BY ?rank`;
}

function facetQuery(skip = {}, facetFields = null, pin = {}) {
    return `PREFIX luc: <urn:jena:lucene:index#>

SELECT ?field ?value ?low ?high ?count
WHERE {
    (?field ?value ?low ?high ?count) luc:facet (${argsFor('facet', skip, facetFields, pin)})
}`;
}

/**
 * One hierarchy level.
 *
 * The server returns a hierarchy one level at a time: no drill filter means the top level,
 * an "=" on a level means that level's children. So a two-level tree is two requests —
 * each omitting the filter belonging to the level it is asking about, so the level's own
 * values survive being chosen.
 */
function levelQuery(dimension, skip, pin = {}) {
    return facetQuery(skip, [dimension], pin);
}

/**
 * The right-hand Turtle panel.
 *
 * Field IRIs are used directly as predicates, which is what makes the output legible:
 * a hit reads as `field:summary "..."` — the indexed field that matched and the value it
 * matched on — rather than as a reified match object. `luc:match` supplies those; the
 * `luc:matchedRecord` blank node is a child document that the CQL filter selected, so a
 * CSV-backed review shows up as the actual row.
 *
 * The luc:query arguments are identical to the results query, so this shares one Lucene
 * execution with the results and the facets rather than searching again.
 */
function constructQuery() {
    return `PREFIX luc:   <urn:jena:lucene:index#>
PREFIX field: <urn:jena:lucene:field#>
PREFIX rdfs:  <http://www.w3.org/2000/01/rdf-schema#>
PREFIX kt:    <${KT}>

CONSTRUCT {
    ?entity rdfs:label ?label ;
            luc:rank ?rank ;
            luc:score ?score ;
            ?field ?value ;
            luc:matchedRecord ?record .
    ?record ?nfield ?nvalue .
}
WHERE {
    (?hit ?entity ?score ?totalHits ?rank) luc:query (${argsFor('query')}) .
    ?entity rdfs:label ?label .
    OPTIONAL { (?hit ?field ?value) luc:match () }
    OPTIONAL { (?hit ?record ?nfield ?nvalue) luc:nestedMatch () }
}`;
}

/**
 * The record itself, as it sits in the graph — its own triples and nothing else.
 *
 * Deliberately separate from constructQuery(): that one is the *index* view (rank, score,
 * which field matched, which child records the filter selected), and it belongs in the
 * right-hand panel. This is the RDF the index was built FROM.
 *
 * The contrast is worth noticing on any card: reviews never appear here, because they live
 * in data/reviews.csv and were never loaded into the graph. They exist only in the index,
 * which is exactly where the right-hand panel shows them.
 */
function recordQuery() {
    return `PREFIX luc: <urn:jena:lucene:index#>

CONSTRUCT {
    ?entity ?p ?o .
    ?o ?bp ?bo .
}
WHERE {
    (?hit ?entity) luc:query (${argsFor('query')}) .
    ?entity ?p ?o .
    # One hop further into blank nodes — a concise bounded description rather than just the
    # entity's own triples. Without it an ingredient record is an empty "[]", because its
    # contents hang off a blank node and the entity only points at it. This is exactly the
    # structure idx:joinPath walks to build the nested children.
    OPTIONAL {
        FILTER(isBlank(?o))
        ?o ?bp ?bo
    }
}`;
}

/** Which indexed field each hit matched on, for the "matched on" line of a card. */
function matchQuery() {
    return `PREFIX luc: <urn:jena:lucene:index#>

SELECT ?entity ?field ?value
WHERE {
    (?hit ?entity) luc:query (${argsFor('query')}) .
    (?hit ?field ?value) luc:match () .
}`;
}

function nestedQuery() {
    return `PREFIX luc: <urn:jena:lucene:index#>

SELECT ?entity ?record ?field ?value
WHERE {
    (?hit ?entity) luc:query (${argsFor('query')}) .
    (?hit ?record ?field ?value) luc:nestedMatch () .
}`;
}

/* ── reviews: indexed for filtering, read from the source for display ──── */

/**
 * The review fields are `idx:stored false`, so the index can filter, correlate, facet and
 * sort on them but has no values to give back — `luc:nestedMatch` returns nothing. That is
 * the point of `idx:externalSource`: the graph and the index are the FILTER, and the values
 * stay in the source of truth.
 *
 * So the app reads data/reviews.csv itself, bundled and served beside it. A browser cannot
 * reach an arbitrary path on disk, which is the real constraint here — in a deployment this
 * would be whatever API owns the reviews, and the shape of the code would not change.
 *
 * Fetched once and cached: it is a static file, and every card on every page reads from the
 * same copy.
 */
let reviewsPromise = null;

function loadReviews() {
    reviewsPromise ??= fetch('data/reviews.csv')
        .then(r => r.ok ? r.text() : Promise.reject(new Error(`HTTP ${r.status}`)))
        .then(parseReviews)
        .catch(() => new Map());
    return reviewsPromise;
}

/**
 * Deliberately naive: split on commas, no quoting, no escapes. That is honest about this
 * file, which has none — a real source would use a CSV parser, and would more likely be
 * JSON from an API anyway.
 */
function parseReviews(text) {
    const [header, ...rows] = text.trim().split(/\r?\n/);
    const cols = header.split(',');
    const byRecipe = new Map();
    for (const row of rows) {
        const cells = row.split(',');
        const rec = Object.fromEntries(cols.map((c, i) => [c.trim(), cells[i]?.trim()]));
        if (!rec.recipe) continue;
        if (!byRecipe.has(rec.recipe)) byRecipe.set(rec.recipe, []);
        byRecipe.get(rec.recipe).push({ reviewer: rec.reviewer, stars: Number(rec.stars), month: rec.month });
    }
    for (const list of byRecipe.values()) list.sort((a, b) => a.month.localeCompare(b.month));
    return byRecipe;
}

/* ── the kind toggle: recipes, reviewers, or both ──────────────────────── */

/**
 * Two shapes share this index, so a hit may be a recipe or a reviewer. The toggle filters
 * on field:entityType, which both shapes populate from rdf:type.
 *
 * "Both" is the ABSENCE of a filter, not an `in` of every class — with only two shapes the
 * two are equivalent, and no filter is the cheaper and more honest of the pair. It would
 * have to become an explicit `in` the moment a third shape existed that should be excluded.
 */
async function renderKinds(res) {
    const node = el('kinds');
    if (!node || kinds.classes.length < 2) { if (node) node.innerHTML = ''; return; }

    // Counts come from the facet, but the LIST comes from the index's target classes. A
    // kind with no matches must still be offered, or filtering to one kind removes the
    // control you would use to get back to the other.
    const counts = new Map(bindings(res || { results: { bindings: [] } })
        .map(b => [val(b, 'value'), Number(val(b, 'count'))]));
    const lab = await labels.resolveMany(kinds.classes);
    const total = [...counts.values()].reduce((n, c) => n + c, 0);

    const button = (value, text, n) =>
        `<button class="kind ${state.kind === value ? 'on' : ''}" data-value="${esc(value ?? '')}"
                 ${n === 0 && value ? 'disabled' : ''}>
           ${esc(text)} <span class="kind-n">${n}</span></button>`;

    node.innerHTML = `<span class="kind-label">Search</span>`
        + kinds.classes.map(c => button(c, (lab.get(c) || shortIri(c)) + 's', counts.get(c) || 0)).join('')
        + button(null, 'Both', total);

    node.querySelectorAll('.kind').forEach(b => {
        b.onclick = () => {
            state.kind = b.dataset.value || null;
            state.page = 0;
            run();
        };
    });
}

/* ── the published-date histogram ──────────────────────────────────────── */

/** Bucket edges, one per year plus a closing edge. */
function yearBoundaries() {
    return [...dateAxis.years.map(y => `${y}-01-01`), `${dateAxis.years.at(-1) + 1}-01-01`];
}

/**
 * Learn which years the data covers, so the axis is the data's rather than a guess.
 * Read from the graph, once, at startup — the range facet needs its bucket edges before
 * it can be asked for anything.
 */
async function loadDateAxis() {
    try {
        const res = await sparql(`PREFIX kt: <${KT}>
SELECT (MIN(?d) AS ?from) (MAX(?d) AS ?to) WHERE { ?s kt:publishedOn ?d }`);
        const b = res.results.bindings[0];
        const from = Number(b?.from?.value?.slice(0, 4));
        const to = Number(b?.to?.value?.slice(0, 4));
        if (!from || !to || to < from) return;
        dateAxis.years = Array.from({ length: to - from + 1 }, (_, i) => from + i);
    } catch {
        // A missing histogram is a missing control, not a broken page.
    }
}

/**
 * A range facet drawn as bars, with a two-handle slider over it.
 *
 * The point of the bars is that you can see where the data is before choosing a range —
 * a plain pair of date inputs lets you pick an empty window and gives no clue why.
 */
function renderDateHistogram(res) {
    const node = el('datehist');
    if (!node) return;
    if (!dateAxis.years.length) { node.innerHTML = ''; return; }


    const counts = new Map();
    for (const b of bindings(res || { results: { bindings: [] } })) {
        const year = Number(val(b, 'low')?.slice(0, 4));
        if (!Number.isNaN(year)) counts.set(year, Number(val(b, 'count')));
    }
    const group = el('group-published');
    const anyData = [...counts.values()].some(n => n > 0);
    if (group) group.style.display = anyData || state.yearIdx ? '' : 'none';

    const peak = Math.max(1, ...counts.values());
    const last = dateAxis.years.length - 1;
    const [lo, hi] = state.yearIdx || [0, last];

    const bars = dateAxis.years.map((y, i) => {
        const n = counts.get(y) || 0;
        const inRange = i >= lo && i <= hi;
        // A zero-count year still gets a sliver, so the axis stays evenly spaced and a gap
        // in the data reads as a gap rather than as a missing bar.
        const h = n ? Math.max(12, Math.round((n / peak) * 100)) : 3;
        return `<div class="bar ${inRange ? 'on' : 'off'}" style="height:${h}%"
                     title="${y}: ${n} recipe${n === 1 ? '' : 's'}"><span>${n || ''}</span></div>`;
    }).join('');

    node.innerHTML = `
      <div class="bars">${bars}</div>
      <div class="rangewrap">
        <input type="range" id="year-lo" min="0" max="${last}" value="${lo}" step="1" aria-label="earliest year">
        <input type="range" id="year-hi" min="0" max="${last}" value="${hi}" step="1" aria-label="latest year">
      </div>
      <div class="axis"><span>${dateAxis.years[lo]}</span>
        ${state.yearIdx ? `<button class="linkish" id="year-clear">clear</button>` : `<span class="muted">all years</span>`}
        <span>${dateAxis.years[hi]}</span></div>`;

    let timer;
    const onSlide = () => {
        let a = Number(el('year-lo').value);
        let b2 = Number(el('year-hi').value);
        if (a > b2) [a, b2] = [b2, a];      // handles crossed: treat it as a span, not an error
        state.yearIdx = (a === 0 && b2 === last) ? null : [a, b2];
        state.page = 0;
        // Repaint the bars immediately so dragging feels attached to something, but debounce
        // the six requests a redraw costs.
        renderDateHistogram(res);
        clearTimeout(timer);
        timer = setTimeout(run, 250);
    };
    el('year-lo').oninput = onSlide;
    el('year-hi').oninput = onSlide;
    if (el('year-clear')) el('year-clear').onclick = () => { state.yearIdx = null; state.page = 0; run(); };
}

/* ── the sources overlay ───────────────────────────────────────────────── */

/*
 * The three inputs the index is built from, so what is being searched is never a black box:
 * the RDF that gets indexed, the configuration that decides how, and the CSV that is joined
 * in at build time but never enters the graph.
 *
 * The config comes from the server's own /$/config endpoint rather than a copy served
 * beside the app — it is the file Fuseki actually read, which is the only version worth
 * showing. The other two are static files served next to the app.
 */
const SOURCES = {
    rdf: {
        title: 'data/kitchen.ttl',
        note: 'Everything the index is built from. Ten recipes, six reviewers, and the '
            + 'vocabularies their IRIs point at. Note the recipe names: some are rdfs:label, '
            + 'some schema:name, some dcterms:title — all three feed one index field.',
        url: 'data/kitchen.ttl',
    },
    config: {
        title: 'config.ttl, as the server read it',
        note: 'Served by Fuseki from GET /$/config — the actual file it started with, not a '
            + 'copy. Fields are declared once and path-free; the sh:property occurrences on '
            + 'each shape decide what feeds them.',
        url: `${BASE}/$/config`,
    },
    reviews: {
        title: 'data/reviews.csv',
        note: 'Never loaded into the graph, and its values are not in the index either — '
            + 'the review fields are idx:stored false. The index can filter and count on '
            + 'these rows; the app reads the file itself to display them.',
        url: 'data/reviews.csv',
    },
};

const sourceCache = new Map();

async function showSource(id) {
    const src = SOURCES[id];
    if (!src) return;
    el('overlay').hidden = false;
    el('ov-title').textContent = src.title;
    el('ov-note').textContent = src.note;
    document.querySelectorAll('.ov-tabs button').forEach(b =>
        b.classList.toggle('on', b.dataset.src === id));

    if (!sourceCache.has(id)) {
        el('ov-body').textContent = 'loading…';
        try {
            const r = await fetch(src.url);
            if (!r.ok) throw new Error(`HTTP ${r.status}`);
            sourceCache.set(id, await r.text());
        } catch (err) {
            el('ov-body').textContent = `could not load ${src.url}\n${err.message}`;
            return;
        }
    }
    el('ov-body').textContent = sourceCache.get(id);
    el('ov-body').scrollTop = 0;
}

function wireOverlay() {
    document.querySelectorAll('.sources button, .ov-tabs button').forEach(b => {
        b.onclick = () => showSource(b.dataset.src);
    });
    const close = () => { el('overlay').hidden = true; };
    el('ov-close').onclick = close;
    // Click the backdrop, not the sheet.
    el('overlay').onclick = ev => { if (ev.target === el('overlay')) close(); };
    document.addEventListener('keydown', ev => {
        if (ev.key === 'Escape' && !el('overlay').hidden) close();
    });
}

/* ── the URL is the query ──────────────────────────────────────────────── */

/*
 * The address bar carries the compiled arguments, not the widget states:
 *
 *   ?q= &mode= &filter= &sort= &facet= &page=
 *
 * `filter`, `sort` and `facet` are the exact CQL2-JSON, sort spec and facet-field list
 * that go to luc:query and luc:facet — so a shared link is also a readable statement of
 * what was asked, and the three can be pasted straight into a SPARQL call.
 *
 * `filter` is parsed back into widget state on load. That works because this app generates
 * the filter and knows its shape; it is not a general CQL2 reader. Anything it does not
 * recognise is ignored rather than guessed at, so a hand-edited filter still runs — the
 * results will be right even where the sidebar cannot show why.
 *
 * `facet` is written but not read back: which facets to request follows from the index
 * configuration rather than from anything the user chose, so it is recomputed at load. It
 * is in the URL because it is part of the question being asked.
 */
function syncUrl() {
    const params = new URLSearchParams();
    if (state.q.trim()) params.set('q', state.q.trim());
    if (state.mode !== 'bm25') params.set('mode', state.mode);
    const filter = buildFilter();
    if (filter) params.set('filter', filter);
    const sort = sortSpec();
    if (sort) params.set('sort', sort);
    if (state.page) params.set('page', String(state.page));
    // Only once there is something to share. The facet list is the same on every view, so
    // emitting it on the bare landing page is 300 characters of noise in the address bar.
    if ([...params.keys()].length) params.set('facet', facetFields());
    // replaceState, not pushState: this fires on every keystroke of a debounced search, and
    // filling the back button with them would make it useless for leaving the page.
    const query = readableQuery(params);
    history.replaceState(null, '', query ? `?${query}` : location.pathname);
}

/*
 * URLSearchParams serialises as application/x-www-form-urlencoded, which escapes far more
 * than a URL query component actually requires — every ":" in an IRI and every brace and
 * bracket of the JSON. RFC 3986 allows all of these unencoded in a query, so putting them
 * back turns
 *
 *   filter=%7B"op"%3A"%3D"%2C"args"%3A%5B%7B"property"%3A"urn%3Ajena%3A…
 * into
 *   filter={"op":"=","args":[{"property":"urn:jena:lucene:field%23reviewer"…
 *
 * Deliberately NOT restored:
 *   &   the pair separator
 *   +   form decoding reads it as a space, so an encoded plus must stay encoded
 *   #   starts the fragment
 *   "   the URL standard's query percent-encode set includes it, so a browser re-encodes
 *       it anyway; leaving it to them keeps what we write and what is displayed the same
 */
const URL_SAFE = { '%3A': ':', '%2C': ',', '%2F': '/', '%5B': '[', '%5D': ']',
                   '%7B': '{', '%7D': '}', '%3D': '=', '%40': '@', '%24': '$',
                   '%21': '!', '%28': '(', '%29': ')', '%2A': '*' };

function readableQuery(params) {
    return params.toString().replace(/%[0-9A-F]{2}/g, m => URL_SAFE[m] ?? m);
}

function readUrl() {
    const params = new URLSearchParams(location.search);
    state.q = params.get('q') || '';
    const mode = params.get('mode');
    if (['bm25', 'semantic', 'code'].includes(mode)) state.mode = mode;
    state.sort = sortFromSpec(params.get('sort'));
    state.page = Math.max(0, Number(params.get('page')) || 0);
    try {
        if (params.get('filter')) applyFilter(JSON.parse(params.get('filter')));
    } catch {
        // A malformed filter must not blank the page; the unfiltered view is a fine fallback.
    }
    el('q').value = state.q;
    el('mode').value = state.mode;
    el('sort').value = state.sort;
}

/** The sort control's value from a compiled sort spec, or "" if it is not one of ours. */
function sortFromSpec(json) {
    try {
        const spec = JSON.parse(json);
        const field = spec?.field?.split('#')[1];
        const options = [...el('sort').options].map(o => o.value);
        const candidate = `${field}:${spec.order || 'asc'}`;
        return options.includes(candidate) ? candidate : '';
    } catch {
        return '';
    }
}

/** Walk our own filter shape back into widget state. */
function applyFilter(root) {
    const flat = [];
    (function walk(c) {
        if (!c) return;
        if (c.op === 'and') c.args.forEach(walk);
        else flat.push(c);
    })(root);

    const eq = new Map();     // field -> [values]
    const add = (f, v) => { if (!eq.has(f)) eq.set(f, []); eq.get(f).push(v); };

    for (const c of flat) {
        const field = c.args?.[0]?.property;
        const value = c.args?.[1];
        if (c.op === '=') add(field, value);
        else if (c.op === 'or' && c.args.every(a => a.op === '=')) {
            c.args.forEach(a => add(a.args[0].property, a.args[1]));
        } else if (c.op === 'between' && field === F('publishedOn')) {
            const lo = dateAxis.years.indexOf(Number(String(value).slice(0, 4)));
            const hi = dateAxis.years.indexOf(Number(String(c.args[2]).slice(0, 4)));
            if (lo >= 0 && hi >= 0) state.yearIdx = [lo, hi];
        } else if (c.op === '>=' && field === F('prepMinutes')) {
            state.prepBucket = [value, state.prepBucket?.[1] ?? null];
        } else if (c.op === '<' && field === F('prepMinutes')) {
            state.prepBucket = [state.prepBucket?.[0] ?? null, value];
        } else if (c.op === '>=' && field === F('stars')) {
            state.minStars = String(value);
            el('min-stars').value = state.minStars;
        }
    }

    if (eq.has(F('entityType'))) state.kind = eq.get(F('entityType'))[0];

    // A dimension's levels are consumed in order: the run of leading levels present is the
    // drill path, and all of it is filtered, which is what the URL recorded.
    for (const [id, dim] of Object.entries(DIMS)) {
        const path = [];
        for (const field of dim.fields) {
            if (!eq.has(field)) break;
            path.push(String(eq.get(field)[0]));
        }
        state.drill[id] = { path, depth: path.length };
    }

    // Whatever is left and is a facet field we render is a flat multi-select.
    for (const f of FACETS) {
        if (eq.has(f.key)) state.sel[f.key] = new Set(eq.get(f.key));
    }
}

/* ── run and render ────────────────────────────────────────────────────── */

let seq = 0;

async function run() {
    const mine = ++seq;
    el('debug-sparql').textContent = searchQuery() + '\n\n/* facets */\n' + facetQuery();
    syncUrl();
    renderModeNote();


    // Each hierarchy level is its own request, omitting the filter for the level being
    // asked about, plus one per OPEN node for its children. That is what costs the extra
    // round trips, and what lets a node be opened without filtering and stops a chosen
    // value vanishing from the list that offers it. Irrelevant at ten documents; on a real
    // corpus it is the ordinary price of exclude-own-filter faceting.
    const jobs = {
        results: sparql(searchQuery()),
        facets: sparql(facetQuery()),
        matched: sparql(matchQuery()),
        kinds: sparql(facetQuery({ kind: true }, [F('entityType')])),
        // Counted with the date filter itself skipped, so narrowing the range never
        // flattens the bars you are dragging over. Same rule as the hierarchy levels.
        dateHist: dateAxis.years.length
            ? sparql(facetQuery({ date: true }, [{ field: F('publishedOn'), ranges: yearBoundaries() }]))
            : null,
        reviews: loadReviews(),
        // One Turtle fetch serves both the right-hand panel and the per-card columns, and
        // never rejects: the explanation must not be able to take the results down.
        turtle: sparqlTurtle(constructQuery()).catch(err => ({ error: err.message })),
        records: sparqlTurtle(recordQuery()).catch(err => ({ error: err.message })),
        // For a dimension whose open path is [a, b], that is three requests: the top level,
        // a's children, and b's children. Each pins the path it is asking about and is
        // therefore counted with that dimension's own filter excluded, so a chosen value
        // never disappears from the list that offered it.
        levels: Promise.all(Object.entries(DIMS).flatMap(([id, dim]) =>
            Array.from({ length: state.drill[id].path.length + 1 }, (_, L) =>
                sparql(levelQuery(dim.key, {}, { [id]: state.drill[id].path.slice(0, L) }))
                    .then(r => [id, L, r])))),
    };

    let done;
    try {
        const keys = Object.keys(jobs);
        const settled = await Promise.all(keys.map(k => jobs[k]));
        done = Object.fromEntries(keys.map((k, i) => [k, settled[i]]));
    } catch (err) {
        if (mine !== seq) return;
        el('results').innerHTML = `<div class="error">${esc(err.message)}</div>`;
        el('summary').textContent = '';
        return;
    }
    if (mine !== seq) return;

    renderTurtlePanel(done.turtle);
    const blocks = turtleBlocks(done.records);
    await renderResults(done.results, done.reviews, done.matched, blocks);
    await renderFacets(done);
    renderDateHistogram(done.dateHist);
    await renderKinds(done.kinds);

    // Say why the sidebar is empty rather than just leaving it empty.
    const anyFacet = [...document.querySelectorAll('#panel-facets .facet-group')]
        .some(n => n.offsetParent !== null);
    el('facets-empty').hidden = anyFacet;
    await renderChips();
}

/**
 * Split one Turtle document into its per-subject blocks, so each result card can show its
 * own. Jena writes a subject block starting at column 0 with its predicates indented, so
 * a split before every unindented line is exactly a split between subjects.
 */
function turtleBlocks(text) {
    const blocks = new Map();
    if (typeof text !== 'string') return blocks;
    for (const part of text.split(/\n(?=\S)/)) {
        const block = part.trim();
        if (!block || block.startsWith('PREFIX') || block.startsWith('@prefix')) continue;
        const subject = block.split(/\s/)[0];
        const iri = subject.startsWith('<') ? subject.slice(1, -1)
            : subject.startsWith('kt:') ? KT + subject.slice(3)
            : null;
        if (iri) blocks.set(iri, block);
    }
    return blocks;
}

/**
 * Fetched and rendered independently of the results: this panel is an explanation, and a
 * failure to produce it must not take the search view down with it.
 */
function renderTurtlePanel(text) {
    const node = el('turtle');
    if (!node) return;
    if (typeof text !== 'string') {
        node.textContent = '# could not build the index view\n# ' + (text?.error || 'unknown error');
        return;
    }
    // Strip the prefix block: it is the same seven lines on every query and it pushes the
    // part worth reading below the fold.
    const body = text.split(/\n(?=\S)/).filter(b => !b.startsWith('PREFIX')).join('\n');
    // Under a vector query luc:match projects nothing, and the absence is the point: a KNN
    // hit is near the query in embedding space, not a term match on some field. Say so,
    // rather than leaving an unexplained gap where the field: predicates usually are.
    const note = state.mode === 'semantic'
        ? '# No field: predicates below — a KNN hit matches a vector, not a term.\n'
          + '# Similarity is the whole explanation; luc:match has nothing to project.\n\n'
        : '';
    node.textContent = note + (body.trim() || '# no hits');
}

function bindings(res) { return res.results.bindings; }
const val = (b, k) => b[k]?.value;

const RDFS = 'http://www.w3.org/2000/01/rdf-schema#';
const DCT = 'http://purl.org/dc/terms/';
const SDO = 'https://schema.org/';
const RDF = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#';

/** Which predicates a card reads, and in what preference order for the name. */
const NAME_PREDICATES = [RDFS + 'label', SDO + 'name', DCT + 'title'];

/**
 * Fold the one-row-per-triple result into one object per hit.
 *
 * `props` is predicate -> array of values, so a card asks for what it wants and gets
 * nothing when the entity has nothing — which is how a reviewer and a recipe go through
 * the same renderer without either knowing about the other.
 */
function foldHits(rows) {
    const hits = new Map();
    for (const b of rows) {
        const iri = val(b, 'entity');
        let hit = hits.get(iri);
        if (!hit) {
            hit = { iri, rank: Number(val(b, 'rank')), score: Number(val(b, 'score')),
                    totalHits: Number(val(b, 'totalHits')), props: new Map() };
            hits.set(iri, hit);
        }
        const p = val(b, 'p');
        if (!p) continue;
        if (!hit.props.has(p)) hit.props.set(p, []);
        hit.props.get(p).push(val(b, 'o'));
    }
    return [...hits.values()].sort((a, b) => a.rank - b.rank);
}

async function renderResults(res, reviews, matched, blocks) {
    const hits = foldHits(bindings(res));
    const total = hits.length ? hits[0].totalHits : 0;

    const one = (hit, p) => hit.props.get(p)?.[0];
    const all = (hit, p) => hit.props.get(p) || [];
    const nameOf = hit => NAME_PREDICATES.map(p => one(hit, p)).find(Boolean) || hit.iri;
    const peopleOf = hit => [...all(hit, KT + 'author'), ...all(hit, KT + 'testedBy')];

    // Every IRI on screen gets a label, resolved through the browser-cached label endpoint.
    const iris = new Set();
    for (const hit of hits) {
        [RDF + 'type', KT + 'course', KT + 'country', KT + 'inRegion'].forEach(p => {
            all(hit, p).forEach(v => iris.add(v));
        });
        all(hit, KT + 'diet').forEach(v => iris.add(v));
        peopleOf(hit).forEach(v => iris.add(v));
    }

    // Field IRIs get labels too, so "matched on" reads "Summary" rather than a CURIE.
    const byMatch = new Map();
    for (const b of bindings(matched || { results: { bindings: [] } })) {
        const e = val(b, 'entity');
        if (!byMatch.has(e)) byMatch.set(e, []);
        byMatch.get(e).push({ field: val(b, 'field'), value: val(b, 'value') });
        iris.add(val(b, 'field'));
    }

    const lab = await labels.resolveMany([...iris]);
    const L = iri => lab.get(iri) || shortIri(iri);

    // Which review rows the current filter picked out, so the ones that made the recipe
    // match can be marked. The index decided WHICH recipes; this decides which to highlight.
    const { path, depth } = state.drill.review;
    const wantsReviewer = depth > 0 ? path[0] : null;
    const wantsStars = depth > 1 ? Number(path[1]) : null;
    const wantsMonth = depth > 2 ? path[2] : null;
    const minStars = state.minStars ? Number(state.minStars) : null;
    const isMatch = r =>
        (wantsReviewer || wantsStars !== null || wantsMonth || minStars !== null)
        && (!wantsReviewer || r.reviewer === wantsReviewer)
        && (wantsStars === null || r.stars === wantsStars)
        && (!wantsMonth || r.month === wantsMonth)
        && (minStars === null || r.stars >= minStars);

    const pages = Math.ceil(total / PAGE_SIZE);
    el('summary').textContent = total
        ? `${total} result${total === 1 ? '' : 's'}${pages > 1 ? ` · page ${state.page + 1} of ${pages}` : ''}`
        : '';

    if (!hits.length) {
        el('results').innerHTML = `<div class="empty">Nothing matched.</div>`;
        el('pager').innerHTML = '';
        return;
    }

    el('results').innerHTML = hits.map(hit => {
        const recs = reviews.get(hit.iri) || [];
        const found = byMatch.get(hit.iri) || [];
        const why = state.mode === 'semantic'
            ? `<span class="why-badge vec">nearest neighbour · cosine ${hit.score.toFixed(3)}</span>`
            : found.length
                ? found.map(h => `<span class="why-badge" title="${esc(h.value)}">${esc(L(h.field))}</span>`).join('')
                : `<span class="why-badge none">filter only — no text match</span>`;
        const country = one(hit, KT + 'country');
        const region = one(hit, KT + 'inRegion');
        const facts = [
            one(hit, KT + 'course') && `<span class="badge k">${esc(L(one(hit, KT + 'course')))}</span>`,
            country && `<span class="badge k">${region ? esc(L(region)) + ' › ' : ''}${esc(L(country))}</span>`,
            ...all(hit, KT + 'diet').map(d => `<span class="badge">${esc(L(d))}</span>`),
            ...peopleOf(hit).map(x => `<span class="badge people">${esc(L(x))}</span>`),
            one(hit, KT + 'prepMinutes') && `<span class="badge num">${esc(one(hit, KT + 'prepMinutes'))} min</span>`,
            one(hit, KT + 'publishedOn') && `<span class="badge num">${esc(one(hit, KT + 'publishedOn'))}</span>`,
        ].filter(Boolean).join('');
        const code = one(hit, KT + 'code');
        return `<article class="card">
    <div class="card-main">
      <h2>${esc(nameOf(hit))} <span class="kind-tag">${esc(L(one(hit, RDF + 'type')))}</span></h2>
      <div class="code">${code ? esc(code) + ' · ' : ''}rank ${hit.rank} · score ${hit.score.toFixed(4)}</div>
      <p class="desc">${esc(one(hit, DCT + 'description') || '')}</p>
      <div class="badges">${facts}</div>
      ${recs.length ? `<div class="reviews">
        <h4>Reviews <span class="tag csv">read from reviews.csv, not the index</span></h4>
        ${recs.map(r => `<div class="review ${isMatch(r) ? 'hit' : ''}"><span class="stars">${'★'.repeat(r.stars || 0)}</span>
          ${esc(r.reviewer || '')} · ${esc(r.month || '')}</div>`).join('')}</div>` : ''}
      <div class="why"><span class="why-label">Matched on</span>${why}</div>
    </div>
    <div class="card-ttl">
      <h4>the record in the graph</h4>
      <pre class="ttl">${esc(blocks?.get(hit.iri) || '# no triples')}</pre>
    </div>
  </article>`;
    }).join('');

    el('pager').innerHTML = pages > 1
        ? `<button id="prev" ${state.page === 0 ? 'disabled' : ''}>← previous</button>
       <button id="next" ${state.page >= pages - 1 ? 'disabled' : ''}>next →</button>`
        : '';
    if (pages > 1) {
        el('prev').onclick = () => { state.page--; run(); };
        el('next').onclick = () => { state.page++; run(); };
    }
}

/** @param res the bundle from run(): base facets plus one response per hierarchy level. */
async function renderFacets(res) {
    const rows = bindings(res.facets);
    const groups = new Map();   // facet key -> [{value, count}]
    const ranges = [];
    for (const b of rows) {
        const field = val(b, 'field');
        const count = Number(val(b, 'count'));
        if (b.low || b.high) {
            ranges.push({ low: b.low ? Number(val(b, 'low')) : null, high: b.high ? Number(val(b, 'high')) : null, count });
            continue;
        }
        if (!groups.has(field)) groups.set(field, []);
        groups.get(field).push({ value: val(b, 'value'), count });
    }

    const valuesOf = (response, dim) => response
        ? bindings(response).filter(b => val(b, 'field') === dim)
            .map(b => ({ value: val(b, 'value'), count: Number(val(b, 'count')) }))
        : [];

    // [dimId, level, response] -> levels[dimId][level]
    const levels = {};
    for (const [id, L, r] of res.levels || []) {
        (levels[id] ||= [])[L] = valuesOf(r, DIMS[id].key);
    }

    const iris = [];
    for (const list of groups.values()) list.forEach(x => { if (isIri(x.value)) iris.push(x.value); });
    for (const list of Object.values(levels).flat()) {
        (list || []).forEach(x => { if (isIri(x.value)) iris.push(x.value); });
    }
    const lab = await labels.resolveMany(iris);
    const L = v => lab.get(v) || shortIri(v);

    const reviewsGroup = el('group-reviews');
    const anyReviews = (levels.review?.[0] || []).length > 0;
    if (reviewsGroup) reviewsGroup.style.display = anyReviews ? '' : 'none';
    el('facet-reviewer').innerHTML = renderDrill('review', levels.review || [], L);

    let html = '';
    for (const [id, dim] of Object.entries(DIMS)) {
        // `review` has its own group in index.html, next to the min-stars control.
        if (!dim.title || !(levels[id]?.[0] || []).length) continue;
        html += `<div class="facet-group"><h3>${esc(dim.title)}</h3>`
            + (dim.note ? `<p class="hint">${esc(dim.note)}</p>` : '')
            + renderDrill(id, levels[id], L) + `</div>`;
    }
    for (const f of FACETS) {
        const list = groups.get(f.key) || [];
        if (!list.length) continue;
        html += `<div class="facet-group"><h3>${esc(f.title)}</h3>`;
        if (f.note) html += `<p class="hint">${esc(f.note)}</p>`;
        html += renderOptions(f.key, list, L, 'multi');
        html += `</div>`;
    }

    if (ranges.length && ranges.some(r => r.count > 0)) {
        html += `<div class="facet-group"><h3>Prep time</h3><ul>` + ranges.map(r => {
            const label = r.low === null ? `under ${r.high} min`
                : r.high === null ? `${r.low} min and over` : `${r.low}–${r.high} min`;
            const on = state.prepBucket && state.prepBucket[0] === r.low && state.prepBucket[1] === r.high;
            return `<li><label class="opt"><input type="checkbox" data-kind="prep"
              data-low="${r.low ?? ''}" data-high="${r.high ?? ''}" ${on ? 'checked' : ''}>
              <span class="name">${esc(label)}</span><span class="count">${r.count}</span></label></li>`;
        }).join('') + `</ul></div>`;
    }

    el('facets').innerHTML = html;
    wireFacetInputs();
}

/**
 * A hierarchical dimension, drawn as a tree of arbitrary depth.
 *
 * Level L's list is rendered; beneath the value that the open path chose at that level,
 * level L+1 is rendered inside it. So a three-level dimension nests three deep, from the
 * same code and the same three requests.
 *
 * Two controls per row, doing different things:
 *   twisty   — expand: ask for this value's children. Changes nothing about the results.
 *   checkbox — filter: narrow the results to this value (and expand, since that is
 *              invariably the next thing wanted).
 */
function renderDrill(id, levels, L, level = 0) {
    const dim = DIMS[id];
    const list = levels[level] || [];
    if (!list.length) return '';
    const { path, depth } = state.drill[id];
    const text = v => dim.fields[level] === F('stars')
            ? `<span class="stars">${'★'.repeat(Number(v) || 0)}</span>`
        : dim.fields[level] === F('ingredientGrams') ? `${esc(v)} g`
        : dim.label === 'iri' ? esc(L(v))
        : esc(v);

    return `<ul${level ? ' class="hier-children"' : ''}>` + list.map(x => {
        const open = path[level] === x.value;
        const filtered = open && depth > level;
        const deeper = open ? renderDrill(id, levels, L, level + 1) : '';
        const canOpen = level + 1 < dim.fields.length;
        return `<li><div class="hier-row">
              ${canOpen
                ? `<button class="twisty" data-kind="open" data-dim="${id}" data-level="${level}"
                           data-value="${esc(x.value)}" aria-expanded="${open}"
                           title="${open ? 'collapse' : 'show what is inside'}">${open ? '▾' : '▸'}</button>`
                : `<span class="twisty"></span>`}
              <label class="opt"><input type="checkbox" data-kind="pick" data-dim="${id}"
                data-level="${level}" data-value="${esc(x.value)}" ${filtered ? 'checked' : ''}>
                <span class="name">${text(x.value)}</span>
                <span class="count">${x.count}</span></label>
            </div>${deeper}</li>`;
    }).join('') + `</ul>`;
}

function renderOptions(field, list, L, kind) {
    if (!list.length) return '';
    return `<ul>` + list.map(x => {
        const on = state.sel[field]?.has(x.value);
        return `<li><label class="opt"><input type="checkbox" data-kind="${kind}" data-field="${esc(field)}"
      data-value="${esc(x.value)}" ${on ? 'checked' : ''}>
      <span class="name">${esc(L(x.value))}</span><span class="count">${x.count}</span></label></li>`;
    }).join('') + `</ul>`;
}

function wireFacetInputs() {
    document.querySelectorAll('#facets [data-kind], #facet-reviewer [data-kind]').forEach(node => {
        node.onclick = ev => {
            const d = ev.currentTarget.dataset;
            state.page = 0;
            const drill = d.dim && state.drill[d.dim];
            const level = Number(d.level);
            switch (d.kind) {
                case 'open': {
                    // Expanding replaces the path from this level down, and never deepens
                    // the filter: depth is clamped to what is still on the path.
                    const closing = drill.path[level] === d.value;
                    drill.path = closing ? drill.path.slice(0, level) : [...drill.path.slice(0, level), d.value];
                    drill.depth = Math.min(drill.depth, drill.path.length);
                    break;
                }
                case 'pick': {
                    const already = drill.path[level] === d.value && drill.depth > level;
                    drill.path = [...drill.path.slice(0, level), d.value];
                    // Unticking drops the filter but leaves the value open, so the list you
                    // were looking at stays on screen.
                    drill.depth = already ? level : level + 1;
                    break;
                }
                case 'multi': {
                    const set = state.sel[d.field] || (state.sel[d.field] = new Set());
                    set.has(d.value) ? set.delete(d.value) : set.add(d.value);
                    break;
                }
                case 'prep': {
                    const lo = d.low === '' ? null : Number(d.low);
                    const hi = d.high === '' ? null : Number(d.high);
                    const same = state.prepBucket && state.prepBucket[0] === lo && state.prepBucket[1] === hi;
                    state.prepBucket = same ? null : [lo, hi];
                    break;
                }
            }
            run();
        };
    });
}

/**
 * Chips carry labels, not CURIEs — for the value AND for the field it belongs to. Field
 * IRIs have rdfs:label in the data exactly so this can go through the same label cache as
 * everything else, instead of title-casing idx:fieldName in JavaScript.
 */
async function renderChips() {
    const chips = [];
    const add = (fieldIri, value, text, clear) => chips.push({ fieldIri, value, text, clear });

    for (const [field, values] of Object.entries(state.sel)) {
        for (const v of values) add(field, v, null, () => state.sel[field].delete(v));
    }
    // One chip per filtered level of each dimension. Clearing a level clears the ones
    // below it too — a drill path with a hole in it is not a path.
    for (const [id, dim] of Object.entries(DIMS)) {
        const { path, depth } = state.drill[id];
        for (let i = 0; i < depth; i++) {
            const raw = path[i];
            const shown = dim.fields[i] === F('stars') ? '★'.repeat(Number(raw) || 0) : null;
            add(dim.fields[i], isIri(raw) ? raw : null, shown ?? (isIri(raw) ? null : raw),
                () => { state.drill[id].depth = i; state.drill[id].path = path.slice(0, i + 1); });
        }
    }
    if (state.kind) add(F('entityType'), state.kind, null, () => { state.kind = null; });
    if (state.yearIdx) {
        const [lo, hi] = state.yearIdx;
        add(F('publishedOn'), null,
            lo === hi ? `${dateAxis.years[lo]}` : `${dateAxis.years[lo]}–${dateAxis.years[hi]}`,
            () => { state.yearIdx = null; });
    }
    if (state.minStars) add(F('stars'), null, `${state.minStars} or more`, () => { state.minStars = ''; el('min-stars').value = ''; });
    if (state.prepBucket) {
        const [lo, hi] = state.prepBucket;
        add(F('prepMinutes'), null, lo === null ? `under ${hi} min` : hi === null ? `${lo} min and over` : `${lo}–${hi} min`,
            () => { state.prepBucket = null; });
    }

    const wanted = [];
    chips.forEach(c => { wanted.push(c.fieldIri); if (isIri(c.value)) wanted.push(c.value); });
    const lab = await labels.resolveMany(wanted);
    const L = v => lab.get(v) || shortIri(v);

    el('chips').innerHTML = chips.map((c, i) =>
        `<span class="chip"><span class="chip-field">${esc(L(c.fieldIri))}</span>${esc(c.text ?? L(c.value))}` +
        `<button data-i="${i}" title="remove">×</button></span>`).join('');
    el('chips').querySelectorAll('button').forEach(b => {
        b.onclick = () => { chips[Number(b.dataset.i)].clear(); state.page = 0; run(); };
    });
}

function renderModeNote() {
    const note = el('mode-note');
    if (state.mode === 'semantic') {
        note.className = 'note';
        note.innerHTML = '<strong>Semantic mode.</strong> The only change is the <code>fieldSpec</code> argument — '
            + 'naming the vector field makes the search box text to embed rather than a Lucene expression. '
            + 'Filters below are pushed <em>into</em> the KNN traversal, and facet counts become top-k scoped. '
            + 'Try <em>a coffee flavoured Italian pudding</em>, <em>a savoury broth with bean curd</em>, or '
            + '<em>meat cooked very slowly in wine until tender</em> — none share a word with the recipe they '
            + 'find. Switch back to Keyword to see BM25 miss them. '
            + 'Note that KNN always returns the k nearest neighbours, so with ten recipes everything comes '
            + 'back, ranked — there is no relevance cutoff.';
        note.classList.remove('hidden');
    } else if (state.mode === 'code') {
        note.className = 'note';
        note.textContent = 'Typeahead mode. The box drives a text_query on an edge-n-gram field — try "2019", "0042" or "RCP-2021".';
        note.classList.remove('hidden');
    } else {
        note.classList.add('hidden');
    }
}

/* ── the feature-test panel ────────────────────────────────────────────── */

function renderTestList() {
    el('test-list').innerHTML = TESTS.map(name => {
        const [num, ...rest] = name.split('-');
        return `<li><button data-file="${name}.rq"><span class="num">${num}</span>${esc(rest.join(' '))}</button></li>`;
    }).join('');
    el('test-list').querySelectorAll('button').forEach(b => { b.onclick = () => runTest(b.dataset.file); });
}

async function runTest(file) {
    const main = el('results');
    el('summary').textContent = '';
    el('pager').innerHTML = '';
    el('chips').innerHTML = '';
    main.innerHTML = `<div class="empty">running ${esc(file)}…</div>`;
    let text;
    try {
        const r = await fetch(`queries/${file}`);
        if (!r.ok) throw new Error(`could not read queries/${file} (HTTP ${r.status})`);
        text = await r.text();
    } catch (err) {
        main.innerHTML = `<div class="error">${esc(err.message)}</div>`;
        return;
    }
    let res;
    try {
        res = await sparql(text);
    } catch (err) {
        main.innerHTML = `<div class="testview"><pre class="rq">${esc(text)}</pre>
      <div class="error">${esc(err.message)}</div></div>`;
        return;
    }
    const vars = res.head.vars;
    const rows = res.results.bindings;
    main.innerHTML = `<div class="testview">
    <h2>${esc(file)}</h2>
    <pre class="rq">${esc(text)}</pre>
    ${rows.length ? `<table class="res"><thead><tr>${vars.map(v => `<th>${esc(v)}</th>`).join('')}</tr></thead>
      <tbody>${rows.map(b => `<tr>${vars.map(v => `<td>${esc(shortIri(b[v]?.value ?? ''))}</td>`).join('')}</tr>`).join('')}</tbody>
    </table><p class="hint">${rows.length} row${rows.length === 1 ? '' : 's'}</p>`
            : `<div class="empty">no rows</div>`}
  </div>`;
}

/* ── startup ───────────────────────────────────────────────────────────── */

async function loadConfig() {
    const badge = el('index-status');
    try {
        const r = await fetch(`${BASE}/$/config/effective`, { headers: { Accept: 'application/json' } });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        const cfg = await r.json();
        state.config = cfg;
        const ds = (cfg.datasets || []).find(d => d.shaclIndex);
        if (!ds) { badge.textContent = 'no SHACL index'; badge.className = 'status bad'; return; }
        const status = ds.index?.status || 'UNKNOWN';
        const nFields = ds.profiles?.[0]?.fields?.length ?? 0;
        // Trust the server's names for the hierarchy dimensions rather than assuming
        // "region_country" / "reviewer_stars": both are derived by joining idx:fieldName
        // values, which this client does not get to decide.
        kinds.classes = [...new Set((ds.profiles || []).flatMap(p => p.targetClasses || []))];

        // NOTE: /$/config/effective reports only ROOT hierarchies — it walks
        // profile.getHierarchies(), and a hierarchy declared inside an idx:nested block
        // hangs off the nested def instead. So region_country is discovered here and the
        // two nested dimensions fall back to the names hardcoded in DIMS. Those names are
        // derived by joining level fieldNames with "_", so they are stable, but this is the
        // one place the client is asserting something the server could tell it.
        const hiers = ds.profiles?.flatMap(pr => pr.hierarchies || []) || [];
        const byLevel = name => hiers.find(h => (h.levels || []).includes(name))?.dimension;
        const regionDim = byLevel('region');
        if (regionDim) DIMS.region.key = regionDim;
        const reviewDim = byLevel('reviewer');
        if (reviewDim) DIMS.review.key = reviewDim;
        badge.textContent = `index ${status} · ${nFields} fields`;
        badge.className = 'status ' + (status === 'MATCH' ? 'ok' : status === 'MISMATCH' ? 'bad' : 'warn');
        badge.title = `GET /$/config/effective\nfingerprint ${status}\nbuilt ${ds.index?.builtAt || '?'}\n`
            + `root hierarchies: ${hiers.map(h => h.dimension).join(', ') || 'none'}`;
    } catch (err) {
        badge.textContent = 'config endpoint unavailable';
        badge.className = 'status warn';
    }
}

function wireChrome() {
    document.querySelectorAll('.tab').forEach(t => {
        t.onclick = () => {
            document.querySelectorAll('.tab').forEach(x => x.classList.toggle('active', x === t));
            el('panel-facets').classList.toggle('hidden', t.dataset.tab !== 'facets');
            el('panel-tests').classList.toggle('hidden', t.dataset.tab !== 'tests');
        };
    });

    let timer;
    el('q').oninput = ev => {
        state.q = ev.target.value; state.page = 0;
        clearTimeout(timer);
        timer = setTimeout(run, 200);
    };
    el('mode').onchange = ev => { state.mode = ev.target.value; state.page = 0; run(); };
    el('sort').onchange = ev => { state.sort = ev.target.value; state.page = 0; run(); };
    el('min-stars').onchange = ev => { state.minStars = ev.target.value; state.page = 0; run(); };
    el('reset').onclick = () => {
        Object.assign(state, {
            q: '', mode: 'bm25', sort: '', page: 0,
            sel: {}, kind: null, yearIdx: null, minStars: '', prepBucket: null,
            drill: { region: { path: [], depth: 0 }, ingredient: { path: [], depth: 0 },
                     review: { path: [], depth: 0 } },
        });
        el('q').value = ''; el('mode').value = 'bm25'; el('sort').value = '';
        el('min-stars').value = '';
        run();
    };
}

(async function main() {
    wireChrome();
    wireOverlay();
    renderTestList();
    // The axis must exist before a ?filter= date range can be read back into slider indices,
    // and the dimension names before a drill path can be matched to its levels.
    await Promise.all([loadConfig(), loadDateAxis()]);
    readUrl();
    run();
})();
