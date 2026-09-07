/* Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0 */

/*
 * Label resolution with the browser's HTTP cache as the cache tier.
 *
 * Modelled on ../label-cdn (packages/label-cache-client), minus the CDN: the backend is
 * SPARQL rather than blob storage. The design point carried over is that the cache is the
 * browser's own HTTP cache, not an in-page Map:
 *
 *   - One GET per IRI, so each IRI is its own cache key. A batched VALUES query would be
 *     one request but a different URL per batch composition, and the hit rate collapses.
 *   - Never sets cache: "no-store", so a returning view can be served with no network
 *     transfer at all. serve_app.py rewrites Fuseki's no-store header to make that legal.
 *   - The in-memory Map below is ONLY an in-flight de-dupe: it collapses concurrent
 *     requests for the same IRI within one page view. It is not the cache and does not
 *     need eviction.
 *
 * Deliberately NOT carried over: language negotiation. label-cache-client keys its cache
 * on (IRI, lang) and walks a fallback chain, issuing another request per miss. This is a
 * demo of Lucene faceting, not of RDF language handling, and the demo's own data is
 * entirely untagged — so there is no lang parameter and no fallback chain.
 */

const LABEL_PREDICATES = [
    'http://www.w3.org/2000/01/rdf-schema#label',
    'http://www.w3.org/2004/02/skos/core#prefLabel',
    'http://purl.org/dc/terms/title',
    'https://schema.org/name',
];

/** Bound on concurrent label requests, as label-cache-client does. */
const MAX_IN_FLIGHT = 100;

function labelQuery(iri) {
    const values = LABEL_PREDICATES.map(p => `<${p}>`).join(' ');
    return `SELECT ?label WHERE {
  VALUES ?p { ${values} }
  <${iri}> ?p ?label .
} LIMIT 1`;
}

class LabelResolver {
    /**
     * @param {string} endpoint  SPARQL endpoint, proxied so the cache header can be rewritten
     * @param {string} version   cache salt — bump to invalidate every cached label at once
     */
    constructor(endpoint, version = '1') {
        this.endpoint = endpoint;
        this.version = String(version);
        this._inFlight = new Map();
        this._queue = [];
        this._active = 0;
    }

    /** Build the request URL for one IRI. GET, so the response is individually cacheable. */
    url(iri) {
        const params = new URLSearchParams({
            query: labelQuery(iri),
            v: this.version,
        });
        return `${this.endpoint}?${params.toString()}`;
    }

    /**
     * Resolve one IRI to a label, or null if it has none. Never throws — a label is
     * decoration, and a failed lookup must not take the surrounding view down with it.
     */
    resolve(iri) {
        if (!iri) return Promise.resolve(null);
        const pending = this._inFlight.get(iri);
        if (pending) return pending;

        const request = this._schedule(async () => {
            try {
                // No cache option is set: the browser cache is the point, so never bust it.
                const resp = await fetch(this.url(iri), {
                    headers: { Accept: 'application/sparql-results+json' },
                });
                if (!resp.ok) return null;
                const data = await resp.json();
                const binding = data.results?.bindings?.[0];
                return binding?.label?.value ?? null;
            } catch {
                return null;
            } finally {
                this._inFlight.delete(iri);
            }
        });

        this._inFlight.set(iri, request);
        return request;
    }

    /**
     * Resolve many IRIs concurrently, bounded. Returns a Map of IRI -> label, omitting
     * any IRI that resolved to nothing.
     */
    async resolveMany(iris) {
        const unique = [...new Set(iris.filter(Boolean))];
        const labels = await Promise.all(unique.map(iri => this.resolve(iri)));
        const out = new Map();
        unique.forEach((iri, i) => {
            if (labels[i]) out.set(iri, labels[i]);
        });
        return out;
    }

    /** Run `task` when a slot frees up, keeping at most MAX_IN_FLIGHT requests open. */
    _schedule(task) {
        return new Promise((resolve) => {
            const run = () => {
                this._active += 1;
                task().then((value) => {
                    this._active -= 1;
                    const next = this._queue.shift();
                    if (next) next();
                    resolve(value);
                });
            };
            if (this._active < MAX_IN_FLIGHT) run();
            else this._queue.push(run);
        });
    }
}

window.LabelResolver = LabelResolver;
