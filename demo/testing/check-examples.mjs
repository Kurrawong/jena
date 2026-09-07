// Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0

/**
 * Replay every example in tests.json against a running server.
 *
 * The failure this exists to catch is an example that appears to work. A filter the app
 * cannot apply is not an error anywhere: the search runs without it, results come back,
 * and the page reads "Showing all entities — 10 of 524 results", which is a sentence
 * about a working search. Two ways that has actually happened:
 *
 *   - The URL loses it. Example parameters are pushed into the address bar and read back
 *     out; a filter names its field by IRI, and the '#' in urn:jena:lucene:field#location
 *     starts the URL fragment. Everything after it is gone before any script runs.
 *   - The server drops it. A filter naming a field that does not resolve — a bare name
 *     where an IRI is required, a typo — is ignored rather than refused, and the query
 *     matches everything.
 *
 * So each example is checked three ways: its parameters survive the URL round trip
 * unchanged, its query returns at least the results it claims, and a filter actually
 * removes something. The last is what distinguishes "filtered to 3" from "ignored, and
 * here are the first 3 of everything".
 *
 * Usage:  FUSEKI_PORT=3030 node --test check-examples.mjs
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const PORT = process.env.FUSEKI_PORT || '3030';
const DATASET = process.env.FUSEKI_DATASET || 'mining';
const ENDPOINT = `http://localhost:${PORT}/${DATASET}/query`;

/** Field IRIs the index knows values by. A bare name here matches nothing and is ignored. */
const FIELD_NS = 'urn:jena:lucene:field#';

/** Page size the app requests; offset for ?page=N follows from it. */
const PAGE_SIZE = 10;

const examples = JSON.parse(
    readFileSync(new URL('../test/tests.json', import.meta.url), 'utf8'));

/**
 * What the app does to an example's parameters: parse the stored string, re-encode it
 * into the address bar, then read it back out. Mirrors applyExample + loadFromUrl, and
 * is the whole of the URL-integrity check — no server involved.
 */
function throughTheUrl(paramString) {
    const raw = (paramString || '').replace(/^\?/, '');
    const pushed = new URLSearchParams(raw).toString();
    return new URLSearchParams(new URL(`http://localhost/?${pushed}`).search);
}

/**
 * Expand bare field names in a filter to their IRIs, as buildCqlFilter does when the app
 * rebuilds the filter from parsed state. Without this the server silently matches
 * everything, and every example carrying a bare name would look inert.
 */
function expandFieldNames(node) {
    if (Array.isArray(node)) return node.map(expandFieldNames);
    if (node && typeof node === 'object') {
        const out = {};
        for (const [k, v] of Object.entries(node)) {
            out[k] = (k === 'property' && typeof v === 'string' && !v.includes(':'))
                ? FIELD_NS + v
                : expandFieldNames(v);
        }
        return out;
    }
    return node;
}

/** ?sort=year:desc — the app's parseSortParam then buildSortSpec, for the plain case. */
function sortSpecFrom(sortParam) {
    if (!sortParam) return '';
    const idx = sortParam.lastIndexOf(':');
    const field = idx >= 0 ? sortParam.substring(0, idx) : sortParam;
    const order = (idx >= 0 ? sortParam.substring(idx + 1) : 'asc') === 'desc' ? 'desc' : 'asc';
    // A nested sort (field@selector) needs a discriminator table this checker does not
    // carry; the field alone is a valid sort and still exercises the query.
    const plainField = field.split('@')[0];
    return JSON.stringify({ field: FIELD_NS + plainField, order });
}

function sparqlQuote(s) {
    return `'${String(s).replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, '\\n')}'`;
}

/** Number of hits for one set of parameters, via the same luc:query the app builds. */
async function countHits({ q = '', filter = '', sort = '', page = 1 }) {
    const term = (q || '').trim() || '*';
    const offset = (Math.max(1, parseInt(page, 10) || 1) - 1) * PAGE_SIZE;
    const query = `PREFIX luc: <urn:jena:lucene:index#>
SELECT (COUNT(*) AS ?n) WHERE {
    (?hit ?entity ?score) luc:query ('default' 'default' ${sparqlQuote(term)} ${sparqlQuote(filter)} ${sparqlQuote(sort)} 10000 ${offset})
}`;
    const resp = await fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sparql-query', Accept: 'application/sparql-results+json' },
        body: query,
    });
    // Read the body once, and only for the branch that needs it: an assertion message
    // built with `await resp.text()` inline is evaluated eagerly and consumes it.
    if (!resp.ok) {
        assert.fail(`${resp.status} ${resp.statusText} from ${ENDPOINT}\n${await resp.text()}`);
    }
    const json = await resp.json();
    return Number(json.results.bindings[0].n.value);
}

/** Entities in the index, as the yardstick for "this filter removed nothing". */
const corpusSize = await countHits({});

test('the server under test is up and holds a populated index', () => {
    assert.ok(corpusSize > 0,
        `no entities at ${ENDPOINT} — an empty index answers every example with nothing`);
});

for (const [i, ex] of examples.entries()) {
    const label = `${String(i + 1).padStart(2, '0')} ${ex.label}`;

    test(`${label} — parameters survive the URL`, () => {
        const raw = new URLSearchParams((ex.params || '').replace(/^\?/, ''));
        const back = throughTheUrl(ex.params);
        for (const [key, value] of raw) {
            assert.equal(back.get(key), value,
                `?${key} does not survive the address bar. A '#' in the value starts the `
                + `URL fragment, so it arrives truncated and the app ignores it.`);
        }
    });

    test(`${label} — returns results`, async () => {
        const p = throughTheUrl(ex.params);
        const filter = p.get('filter')
            ? JSON.stringify(expandFieldNames(JSON.parse(p.get('filter'))))
            : '';
        const hits = await countHits({
            q: p.get('q') || '',
            filter,
            sort: sortSpecFrom(p.get('sort')),
            page: p.get('page') || 1,
        });

        if (typeof ex.minResults === 'number') {
            assert.ok(hits >= ex.minResults,
                `expected at least ${ex.minResults} results, got ${hits}`);
        }

        // A filter that changes nothing is the shape of every silent failure here, so it
        // is a failure whether or not the example declares a minimum.
        //
        // Measured against the whole corpus, not against the same search term: a term
        // narrow enough to already satisfy the filter — q=réserves matches one document,
        // which is a MiningReport — leaves an entityType filter nothing to remove, and
        // that is a working example, not an ignored filter. Applying the filter alone
        // asks the question the term cannot confound.
        //
        // drillDown is excluded: the app applies it on top of the URL filter, so the
        // filter alone is legitimately wider than what the page shows.
        if (filter && !p.get('drillDown')) {
            const matched = await countHits({ filter });
            assert.ok(matched < corpusSize,
                `filter matched all ${corpusSize} entities, so it did nothing. Either the `
                + `field does not resolve — the server ignores an unknown field rather `
                + `than refusing it — or the filter is genuinely vacuous.`);
        }
    });
}
