/* Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0 */

// ---------------------------------------------------------------------------
// Configuration — adjust these if your Fuseki setup differs
// ---------------------------------------------------------------------------

const CONFIG_PATH = 'config.ttl';
const APP_CONFIG = window.APP_CONFIG || {};
const FUSEKI_BASE = APP_CONFIG.fusekiBase || 'http://localhost:3030';
const RESULT_LIMITS = [10, 100, 1000, 5000, 9999];
const DEFAULT_LIMIT = 10;
const FACET_LIMITS = [10, 25, 50, 100, 500];
const DEFAULT_FACET_LIMIT = 10;
const IDENTIFIER_SUGGESTION_LIMIT = 10;
const IDENTIFIER_SUGGESTION_DEBOUNCE_MS = 150;
const DEFAULT_SORT_DIRECTION = 'asc';
const DAY_MS = 24 * 60 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;

// ---------------------------------------------------------------------------
// RDF namespace constants
// ---------------------------------------------------------------------------

const XSD_STRING = 'http://www.w3.org/2001/XMLSchema#string';
const RDF_LANGSTRING = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#langString';
const RDFS_RESOURCE = 'http://www.w3.org/2000/01/rdf-schema#Resource';
const RDF = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#';
const SH = 'http://www.w3.org/ns/shacl#';
const TEXT = 'http://jena.apache.org/text#';
const IDX = 'urn:jena:lucene:index#';
const FUSEKI = 'http://jena.apache.org/fuseki#';
// Vocabulary for the search CONSTRUCT's payload. Private to the demo — the app both
// writes the template and reads the graph back, so nothing else depends on these terms.
const RES = 'urn:jena:lucene:result#';
const DEMO_FIELD_IRIS = {
    identifierType: 'urn:jena:lucene:field#identifierType',
    identifierValueText: 'urn:jena:lucene:field#identifierValueText',
    attributionRole: 'urn:jena:lucene:field#attributionRole',
    attributionAgentText: 'urn:jena:lucene:field#attributionAgentText',
};

const SPARQL_PREFIXES = `\
PREFIX luc:  <urn:jena:lucene:index#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX dct:  <http://purl.org/dc/terms/>
PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX ex:   <http://example.org/mining/>
PREFIX prov: <http://www.w3.org/ns/prov#>
PREFIX res:  <urn:jena:lucene:result#>
`;

const TURTLE_PREFIXES = {
    rdf: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
    rdfs: 'http://www.w3.org/2000/01/rdf-schema#',
    xsd: 'http://www.w3.org/2001/XMLSchema#',
    dct: 'http://purl.org/dc/terms/',
    ex: 'http://example.org/mining/',
    luc: 'urn:jena:lucene:index#',
    idx: 'urn:jena:lucene:index#',
    sh: 'http://www.w3.org/ns/shacl#',
    schema: 'https://schema.org/',
    geo: 'http://www.opengis.net/ont/geosparql#',
};

// ---------------------------------------------------------------------------
// Utility functions
// ---------------------------------------------------------------------------

function shortName(uri) {
    const h = uri.lastIndexOf('#');
    if (h >= 0) return uri.substring(h + 1);
    const s = uri.lastIndexOf('/');
    if (s >= 0) return uri.substring(s + 1);
    return uri;
}

function escapeSparql(text) {
    return String(text)
        .replace(/\\/g, '\\\\')
        .replace(/'/g, "\\'")
        .replace(/\r/g, '\\r')
        .replace(/\n/g, '\\n');
}

function sparqlQuote(text) {
    return `'${escapeSparql(text)}'`;
}

async function parseSparqlJsonResponse(resp) {
    const body = await resp.text();
    if (!resp.ok) {
        if (resp.status === 405 && resp.url && resp.url.includes('/fuseki/')) {
            throw new Error("SPARQL proxy rejected POST at /fuseki. The demo app is probably being served by a plain static server instead of demo/serve_app.py. Start it with `task app`.");
        }
        const detail = body.trim().slice(0, 400);
        throw new Error(`SPARQL error: ${resp.status} ${resp.statusText}${detail ? ` - ${detail}` : ''}`);
    }
    try {
        return JSON.parse(body);
    } catch (err) {
        const msg = String(err && err.message ? err.message : err);
        const match = msg.match(/position (\d+)/);
        if (match) {
            const pos = Number(match[1]);
            const start = Math.max(0, pos - 160);
            const end = Math.min(body.length, pos + 160);
            const context = body.slice(start, end)
                .replace(/\r/g, '\\r')
                .replace(/\n/g, '\\n');
            throw new Error(`Invalid JSON from Fuseki at position ${pos}: ${context}`);
        }
        const preview = body.trim().slice(0, 400) || '(empty response)';
        throw new Error(`Non-JSON response from Fuseki: ${preview}`);
    }
}

async function parseSparqlTextResponse(resp) {
    const body = await resp.text();
    if (!resp.ok) {
        if (resp.status === 405 && resp.url && resp.url.includes('/fuseki/')) {
            throw new Error("SPARQL proxy rejected POST at /fuseki. The demo app is probably being served by a plain static server instead of demo/serve_app.py. Start it with `task app`.");
        }
        const detail = body.trim().slice(0, 400);
        throw new Error(`SPARQL error: ${resp.status} ${resp.statusText}${detail ? ` - ${detail}` : ''}`);
    }
    return body;
}

function escapeHtml(text) {
    const el = document.createElement('span');
    el.textContent = text;
    return el.innerHTML;
}

function timeStamp() {
    return new Date().toLocaleTimeString('en-GB', { hour12: false });
}

function resolveFieldName(fieldUri, fieldIRIs) {
    const fragment = shortName(fieldUri);
    for (const [name, iri] of Object.entries(fieldIRIs || {})) {
        if (shortName(iri) === fragment) return name;
    }
    return fragment;
}

function buildSortSpec(sortField, sortDirection, fieldIRIs) {
    if (!sortField) return '';
    const fieldIri = (fieldIRIs && fieldIRIs[sortField]) || sortField;
    return JSON.stringify({
        field: fieldIri,
        order: sortDirection || DEFAULT_SORT_DIRECTION,
    });
}

function parseSortParam(sortParam, fieldIRIs) {
    if (!sortParam) {
        return { field: '', direction: DEFAULT_SORT_DIRECTION };
    }
    const idx = sortParam.lastIndexOf(':');
    const rawField = idx >= 0 ? sortParam.substring(0, idx) : sortParam;
    const rawDirection = idx >= 0 ? sortParam.substring(idx + 1) : DEFAULT_SORT_DIRECTION;
    return {
        field: resolveFieldName(rawField, fieldIRIs),
        direction: rawDirection === 'desc' ? 'desc' : DEFAULT_SORT_DIRECTION,
    };
}

function isTemporalFieldType(fieldType) {
    const t = String(fieldType || '').toLowerCase();
    return t.includes('datefield') || t.includes('datetimefield');
}

function isNumericFieldType(fieldType) {
    const t = String(fieldType || '').toLowerCase();
    return t.includes('int') || t.includes('long') || t.includes('double');
}

function facetRangeSpec(fieldName, fieldInfo) {
    if (fieldName === 'year') return [null, 2000, 2010, 2020, null];
    if (fieldName === 'depth') return [0, 100, 250, 500, 1000];
    if (fieldName === 'confidenceScore') return [0.5, 0.7, 0.85, 0.95, null];
    if (fieldName === 'publishedOn') return [null, '2020-01-01', '2022-01-01', '2024-01-01', null];
    if (fieldName === 'indexedAt') return [null, '2024-01-01T00:00:00Z', '2024-02-01T00:00:00Z', '2024-03-01T00:00:00Z', null];
    if (fieldInfo?.isTemporal) return [null, '2020-01-01', '2022-01-01', '2024-01-01', null];
    return null;
}

function formatSelectedValue(field, value) {
    if (typeof value === 'string' && value.startsWith('__RANGE__')) {
        const [low, high] = value.substring(9).split('|');
        return `${escapeHtml(field)} in “${escapeHtml(low || '*')} to ${escapeHtml(high || '*')}”`;
    }
    return `${escapeHtml(field)} = “${escapeHtml(shortName(value))}”`;
}

function fieldIri(fieldIRIs, name, fallback) {
    return (fieldIRIs && fieldIRIs[name]) || fallback || DEMO_FIELD_IRIS[name] || name;
}

function isPropertyArg(arg) {
    return !!(arg && typeof arg === 'object' && typeof arg.property === 'string');
}

function isPropertyLeaf(clause, op) {
    return !!(clause && (!op || clause.op === op) && Array.isArray(clause.args) && clause.args.length >= 2 && isPropertyArg(clause.args[0]));
}

function isEqualsLeaf(clause) {
    return isPropertyLeaf(clause, '=');
}

function isTextQueryLeaf(clause) {
    return isPropertyLeaf(clause, 'text_query');
}

function resolveClauseProperty(clause, fieldIRIs) {
    return isPropertyLeaf(clause) ? resolveFieldName(clause.args[0].property, fieldIRIs) : null;
}

function sanitizeDomId(text) {
    return String(text || '').replace(/[^a-zA-Z0-9_-]+/g, '-');
}

function emptyCorrelatedFilterState() {
    return {
        identifierTerms: {},
        attributionRole: '',
        attributionAgent: '',
    };
}

function temporalFieldPresetBounds(fieldName, fieldInfo) {
    if (fieldName === 'publishedOn') return { min: '1985-01-01', max: '2025-12-31' };
    if (fieldName === 'indexedAt') return { min: '2024-01-01T00:00', max: '2024-03-31T23:00' };
    const ranges = facetRangeSpec(fieldName, fieldInfo) || [];
    const concrete = ranges.filter(v => v != null);
    if (concrete.length >= 2) {
        return { min: String(concrete[0]), max: String(concrete[concrete.length - 1]) };
    }
    return fieldInfo?.isTemporal && String(fieldInfo.fieldType || '').toLowerCase().includes('datetime')
        ? { min: '2024-01-01T00:00', max: '2024-12-31T23:00' }
        : { min: '2000-01-01', max: '2025-12-31' };
}

function normalizeDateInput(value) {
    return value ? value.slice(0, 10) : '';
}

function normalizeDateTimeInput(value) {
    if (!value) return '';
    if (/[+-]\d\d:\d\d$/.test(value) || value.endsWith('Z')) return value;
    if (value.length === 16) return `${value}:00Z`;
    if (value.length === 19) return `${value}Z`;
    return value;
}

function formatTemporalInputValue(fieldInfo, value) {
    if (!value) return '';
    if (fieldInfo?.isTemporal && String(fieldInfo.fieldType || '').toLowerCase().includes('datetime')) {
        return value.replace(/Z$/, '').slice(0, 16);
    }
    return value.slice(0, 10);
}

function temporalToMillis(fieldInfo, value) {
    if (!value) return NaN;
    if (fieldInfo?.isTemporal && String(fieldInfo.fieldType || '').toLowerCase().includes('datetime')) {
        return Date.parse(normalizeDateTimeInput(value));
    }
    return Date.parse(`${normalizeDateInput(value)}T00:00:00Z`);
}

function millisToTemporal(fieldInfo, value) {
    if (!Number.isFinite(value)) return '';
    const iso = new Date(value).toISOString();
    if (fieldInfo?.isTemporal && String(fieldInfo.fieldType || '').toLowerCase().includes('datetime')) {
        return iso.slice(0, 19) + 'Z';
    }
    return iso.slice(0, 10);
}

/**
 * Convert one RDF object term into the shape the card renderer uses.
 */
function termToProperty(object) {
    const raw = object.value;
    const isUri = object.termType === 'NamedNode';
    const lang = object.language || null;
    // RDF 1.1 gives every plain literal an explicit xsd:string, and every
    // language-tagged one rdf:langString. Neither is worth showing as a
    // datatype — the SPARQL JSON results this replaced simply omitted them.
    const rawDatatype = (!isUri && object.datatype) ? object.datatype.value : null;
    const datatype = (rawDatatype === XSD_STRING || rawDatatype === RDF_LANGSTRING)
        ? null : rawDatatype;
    // IRI objects start as their short name and are upgraded in place once
    // labels.js resolves them; literals never need a lookup.
    const display = isUri ? shortName(raw) : decorateLiteralDisplay(raw, lang, datatype);
    return { display, raw, isUri, lang, datatype };
}

/**
 * One rendered property value on a card. Values are plain labels, not controls: the
 * tooltip carries the IRI behind a label, or the literal's language and datatype when
 * there is one.
 */
function propValue(pv) {
    const tooltip = pv.isUri
        ? pv.raw
        : [pv.lang ? `lang: ${pv.lang}` : null,
           pv.datatype ? `datatype: ${shortName(pv.datatype)}` : null].filter(Boolean).join(' | ');
    return {
        value: pv.raw,
        displayValue: pv.display,
        isUri: pv.isUri,
        tooltip,
    };
}

function decorateLiteralDisplay(raw, lang, datatype) {
    if (lang) return `${raw} @${lang}`;
    if (datatype) return `${raw} ^^${shortName(datatype)}`;
    return raw;
}

function renderJsonTree(obj, indent) {
    indent = indent || 0;
    if (obj === null) return '<span class="jt-null">null</span>';
    if (typeof obj === 'boolean') return `<span class="jt-bool">${obj}</span>`;
    if (typeof obj === 'number') return `<span class="jt-num">${obj}</span>`;
    if (typeof obj === 'string') return `<span class="jt-str">"${escapeHtml(obj)}"</span>`;
    if (Array.isArray(obj)) {
        if (obj.length === 0) return '<span class="jt-brace">[]</span>';
        const items = obj.map((v, i) => {
            const comma = i < obj.length - 1 ? ',' : '';
            return `<div class="jt-item">${renderJsonTree(v, indent + 1)}${comma}</div>`;
        }).join('');
        return `<details open><summary class="jt-brace">[<span class="jt-count">${obj.length}</span>]</summary><div class="jt-indent">${items}</div><span class="jt-brace">]</span></details>`;
    }
    if (typeof obj === 'object') {
        const keys = Object.keys(obj);
        if (keys.length === 0) return '<span class="jt-brace">{}</span>';
        const items = keys.map((k, i) => {
            const comma = i < keys.length - 1 ? ',' : '';
            return `<div class="jt-item"><span class="jt-key">"${escapeHtml(k)}"</span>: ${renderJsonTree(obj[k], indent + 1)}${comma}</div>`;
        }).join('');
        return `<details open><summary class="jt-brace">{<span class="jt-count">${keys.length}</span>}</summary><div class="jt-indent">${items}</div><span class="jt-brace">}</span></details>`;
    }
    return escapeHtml(String(obj));
}

function formatTurtle(quads, prefixes = TURTLE_PREFIXES) {
    return new Promise((resolve, reject) => {
        const writer = new N3.Writer({ prefixes });
        writer.addQuads(quads);
        writer.end((error, result) => {
            if (error) {
                reject(error);
                return;
            }
            resolve(result);
        });
    });
}

/**
 * Convert selected facet filters + optional spatial bbox to CQL2-JSON string.
 * Input: {field: [val1, val2], ...}, bbox: [swLon, swLat, neLon, neLat] | null
 * Returns null if no filters are active.
 */
/**
 * Convert selected facet filters + optional spatial geometry to CQL2-JSON string.
 * Input: {field: [val1, val2], ...}
 *   bbox: [swLon, swLat, neLon, neLat] | null
 *   polygon: [[lon, lat], ...] | null  (closed ring, CRS84 order)
 * Returns null if no filters are active.
 */
function buildCqlFilter(selected, bbox, polygon, fieldIRIs, extraClauses) {
    const clauses = [];
    for (const [field, values] of Object.entries(selected)) {
        if (!values || values.length === 0) continue;
        const prop = (fieldIRIs && fieldIRIs[field]) || field;

        const rangeVals = values.filter(v => typeof v === 'string' && v.startsWith('__RANGE__'));
        const exactVals = values.filter(v => !(typeof v === 'string' && v.startsWith('__RANGE__')));

        const fieldClauses = [];

        if (exactVals.length > 0) {
            if (exactVals.length === 1) {
                fieldClauses.push({op: '=', args: [{property: prop}, exactVals[0]]});
            } else {
                fieldClauses.push({op: 'in', args: [{property: prop}, exactVals]});
            }
        }

        for (const rv of rangeVals) {
            const parts = rv.substring(9).split('|');
            const rawLow = parts[0] !== '' ? parts[0] : null;
            const rawHigh = parts[1] !== '' ? parts[1] : null;
            const temporal = (rawLow && isNaN(Number(rawLow))) || (rawHigh && isNaN(Number(rawHigh)));
            const low = rawLow === null ? null : (temporal ? rawLow : Number(rawLow));
            const high = rawHigh === null ? null : (temporal ? rawHigh : Number(rawHigh));

            if (low !== null && high !== null) {
                fieldClauses.push({ op: 'between', args: [{property: prop}, low, high] });
            } else if (low !== null) {
                fieldClauses.push({op: '>=', args: [{property: prop}, low]});
            } else if (high !== null) {
                fieldClauses.push({op: '<', args: [{property: prop}, high]});
            }
        }

        if (fieldClauses.length === 1) {
            clauses.push(fieldClauses[0]);
        } else if (fieldClauses.length > 1) {
            clauses.push({op: 'or', args: fieldClauses});
        }
    }
    if (bbox && bbox.length === 4) {
        const locProp = (fieldIRIs && fieldIRIs['location']) || 'location';
        clauses.push({
            op: 's_intersects',
            args: [{property: locProp}, {bbox: bbox}],
        });
    }
    if (polygon && polygon.length >= 4) {
        const locProp = (fieldIRIs && fieldIRIs['location']) || 'location';
        clauses.push({
            op: 's_intersects',
            args: [{property: locProp}, {type: 'Polygon', coordinates: [polygon]}],
        });
    }
    if (extraClauses && extraClauses.length > 0) {
        clauses.push(...extraClauses);
    }
    if (clauses.length === 0) return null;
    if (clauses.length === 1) return JSON.stringify(clauses[0]);
    return JSON.stringify({op: 'and', args: clauses});
}

function extractCorrelatedFilterState(clause, fieldIRIs, correlated) {
    if (!clause || !clause.op || !Array.isArray(clause.args)) return false;

    if (clause.op === 'and') {
        let identifierType = '';
        let identifierText = '';
        let attrRole = '';
        let attrAgent = '';
        let supported = true;

        for (const child of clause.args) {
            if (!isPropertyLeaf(child)) {
                supported = false;
                break;
            }
            const prop = resolveClauseProperty(child, fieldIRIs);
            if (prop === 'identifierType') {
                if (!isEqualsLeaf(child)) {
                    supported = false;
                    break;
                }
                identifierType = String(child.args[1] || '');
            } else if (prop === 'identifierValueText') {
                if (!isEqualsLeaf(child) && !isTextQueryLeaf(child)) {
                    supported = false;
                    break;
                }
                identifierText = String(child.args[1] || '');
            } else if (prop === 'attributionRole') {
                if (!isEqualsLeaf(child)) {
                    supported = false;
                    break;
                }
                attrRole = String(child.args[1] || '');
            } else if (prop === 'attributionAgentText') {
                if (!isEqualsLeaf(child) && !isTextQueryLeaf(child)) {
                    supported = false;
                    break;
                }
                attrAgent = String(child.args[1] || '');
            } else {
                supported = false;
                break;
            }
        }

        if (supported && identifierType && identifierText) {
            correlated.identifierTerms[identifierType] = identifierText;
            return true;
        }
        if (supported && (attrRole || attrAgent)) {
            if (attrRole) correlated.attributionRole = attrRole;
            if (attrAgent) correlated.attributionAgent = attrAgent;
            return true;
        }
    }

    if (isPropertyLeaf(clause)) {
        const prop = resolveClauseProperty(clause, fieldIRIs);
        if (prop === 'attributionRole' && isEqualsLeaf(clause)) {
            correlated.attributionRole = String(clause.args[1] || '');
            return true;
        }
        if (prop === 'attributionAgentText' && (isEqualsLeaf(clause) || isTextQueryLeaf(clause))) {
            correlated.attributionAgent = String(clause.args[1] || '');
            return true;
        }
    }

    return false;
}

/**
 * Parse a CQL2-JSON filter string back into app state.
 * Returns { selected: {field: [values]}, bbox, polygon }.
 */
function parseCqlFilter(cqlString, fieldIRIs) {
    const selected = {};
    let bbox = null;
    let polygon = null;
    const correlated = emptyCorrelatedFilterState();
    if (!cqlString) return { selected, bbox, polygon, correlated };

    // Build reverse map: IRI → field name
    const iriToName = {};
    if (fieldIRIs) {
        for (const [name, iri] of Object.entries(fieldIRIs)) {
            iriToName[iri] = name;
            // Also map by local name for cross-base matching
            iriToName[shortName(iri)] = name;
        }
    }
    const resolve = (prop) => iriToName[prop] || iriToName[shortName(prop)] || prop;

    let cql;
    try { cql = JSON.parse(cqlString); } catch { return { selected, bbox, polygon, correlated }; }

    function addSelected(field, value) {
        if (!selected[field]) selected[field] = [];
        if (!selected[field].includes(value)) selected[field].push(value);
    }

    function visit(clause) {
        if (!clause || !clause.op || !clause.args) return;
        if (extractCorrelatedFilterState(clause, fieldIRIs, correlated)) return;
        if (clause.op === 'and' || clause.op === 'or') {
            for (const child of clause.args) visit(child);
            return;
        }
        if (clause.op === '=' && clause.args[0]?.property) {
            addSelected(resolve(clause.args[0].property), clause.args[1]);
            return;
        }
        if (clause.op === 'in' && clause.args[0]?.property) {
            const field = resolve(clause.args[0].property);
            for (const value of clause.args[1] || []) addSelected(field, value);
            return;
        }
        if (clause.op === 'between' && clause.args[0]?.property) {
            const field = resolve(clause.args[0].property);
            const bounds = Array.isArray(clause.args[1]) ? clause.args[1] : [clause.args[1], clause.args[2]];
            addSelected(field, `__RANGE__${bounds[0] ?? ''}|${bounds[1] ?? ''}`);
            return;
        }
        if ((clause.op === '>=' || clause.op === '>' || clause.op === '<=' || clause.op === '<') && clause.args[0]?.property) {
            const field = resolve(clause.args[0].property);
            const current = (selected[field] || []).find(v => typeof v === 'string' && v.startsWith('__RANGE__'));
            let low = '';
            let high = '';
            if (current) {
                [low, high] = current.substring(9).split('|');
                selected[field] = selected[field].filter(v => v !== current);
            }
            if (clause.op === '>=' || clause.op === '>') low = String(clause.args[1]);
            if (clause.op === '<=' || clause.op === '<') high = String(clause.args[1]);
            addSelected(field, `__RANGE__${low}|${high}`);
            return;
        }
        if (clause.op === 's_intersects') {
            const geom = clause.args[1];
            if (geom?.bbox) {
                bbox = geom.bbox;
            } else if (geom?.type === 'Polygon' && geom.coordinates) {
                polygon = geom.coordinates[0];
            }
        }
    }

    visit(cql);
    return { selected, bbox, polygon, correlated };
}

// ---------------------------------------------------------------------------
// N3 store helpers
// ---------------------------------------------------------------------------

const nn = (iri) => N3.DataFactory.namedNode(iri);

function getObject(store, subject, predicateIri) {
    const objs = store.getObjects(subject, nn(predicateIri), null);
    return objs.length > 0 ? objs[0] : null;
}

function getObjects(store, subject, predicateIri) {
    return store.getObjects(subject, nn(predicateIri), null);
}

function getSubjects(store, predicateIri, objectIri) {
    return store.getSubjects(nn(predicateIri), nn(objectIri), null);
}

function getLiteral(store, subject, predicateIri) {
    const obj = getObject(store, subject, predicateIri);
    return obj ? obj.value : null;
}

function walkList(store, head) {
    const items = [];
    let current = head;
    while (current && current.value !== RDF + 'nil') {
        const first = getObject(store, current, RDF + 'first');
        if (first) items.push(first);
        current = getObject(store, current, RDF + 'rest');
    }
    return items;
}

function pathToString(store, pathNode) {
    if (pathNode.termType === 'NamedNode') {
        return shortName(pathNode.value);
    }
    const inv = getObject(store, pathNode, SH + 'inversePath');
    if (inv) {
        return '^' + shortName(inv.value);
    }
    const first = getObject(store, pathNode, RDF + 'first');
    if (first) {
        const items = walkList(store, pathNode);
        return items.map(item => shortName(item.value)).join(' / ');
    }
    return pathNode.value;
}

// ---------------------------------------------------------------------------
// Config parser — reads config.ttl via N3.js
// ---------------------------------------------------------------------------

function parseTurtle(text) {
    return new Promise((resolve, reject) => {
        const store = new N3.Store();
        const parser = new N3.Parser();
        parser.parse(text, (error, quad) => {
            if (error) { reject(error); return; }
            if (quad) { store.addQuad(quad); return; }
            resolve(store);
        });
    });
}

function extractConfig(store) {
    let indexNodes = getSubjects(store, RDF + 'type', TEXT + 'TextIndexShacl');
    if (indexNodes.length === 0) indexNodes = getSubjects(store, RDF + 'type', TEXT + 'TextIndexLucene');
    if (indexNodes.length === 0) throw new Error('No text:TextIndexShacl or text:TextIndexLucene found in config');
    const indexNode = indexNodes[0];

    const storeValues = getLiteral(store, indexNode, TEXT + 'storeValues') === 'true';
    const maxFacetHits = parseInt(getLiteral(store, indexNode, TEXT + 'maxFacetHits') || '0', 10);

    const serviceNodes = getSubjects(store, RDF + 'type', FUSEKI + 'Service');
    let datasetName = 'dataset';
    if (serviceNodes.length > 0) {
        const name = getLiteral(store, serviceNodes[0], FUSEKI + 'name');
        if (name) datasetName = name;
    }

    const shapesHead = getObject(store, indexNode, TEXT + 'shapes');
    const shapeNodes = shapesHead ? walkList(store, shapesHead) : [];

    const shapes = [];
    const facetFields = [];
    const sortableFields = [];
    const fieldIRIs = {};
    const fieldInfo = {};
    const predicateToFacet = {};
    const seenFacets = new Set();
    const seenSortable = new Set();
    const hierarchyDimensions = new Map();

    function registerField(shape, propNode, { includeFacetField = true, includePredicateMapping = true } = {}) {
        // Support new occurrence model: blank node with idx:field reference to canonical field resource.
        // Fall back to reading metadata directly from propNode for the old inline model.
        const fieldRef = getObject(store, propNode, IDX + 'field');
        const fieldNode = fieldRef || propNode;

        const fieldName = getLiteral(store, fieldNode, IDX + 'fieldName');
        const fieldType = getObject(store, fieldNode, IDX + 'fieldType');
        const pathNode = getObject(store, propNode, SH + 'path');
        if (!fieldName || !fieldType) return;

        const facetable = getLiteral(store, fieldNode, IDX + 'facetable') === 'true';
        const multiValued = getLiteral(store, fieldNode, IDX + 'multiValued') === 'true';
        const defaultSearch = getLiteral(store, fieldNode, IDX + 'defaultSearch') === 'true';
        const sortable = getLiteral(store, fieldNode, IDX + 'sortable') === 'true';
        const storedLiteral = getLiteral(store, fieldNode, IDX + 'stored');
        const stored = storedLiteral == null ? true : storedLiteral === 'true';
        const pathStr = pathNode ? pathToString(store, pathNode) : '?';

        const fieldIRI = fieldNode.termType === 'NamedNode' ? fieldNode.value : null;

        const fieldTypeShort = shortName(fieldType.value);
        const isNumeric = isNumericFieldType(fieldType.value);
        const isTemporal = isTemporalFieldType(fieldType.value);

        shape.fields.push({
            name: fieldName,
            iri: fieldIRI,
            path: pathStr,
            fieldType: fieldTypeShort,
            stored,
            facetable,
            multiValued,
            defaultSearch,
            sortable,
            isNumeric,
            isTemporal,
        });

        if (fieldIRI) fieldIRIs[fieldName] = fieldIRI;
        fieldInfo[fieldName] = {
            iri: fieldIRI || fieldName,
            fieldType: fieldTypeShort,
            stored,
            isNumeric,
            isTemporal,
            facetable,
            sortable,
        };

        if (sortable && !seenSortable.has(fieldName)) {
            seenSortable.add(fieldName);
            sortableFields.push({
                name: fieldName,
                iri: fieldIRI || fieldName,
                fieldType: fieldTypeShort,
                isNumeric,
                isTemporal,
            });
        }

        if (includeFacetField && facetable && !seenFacets.has(fieldName)) {
            seenFacets.add(fieldName);
            facetFields.push(fieldName);
        }

        if (includePredicateMapping && facetable && pathNode) {
            if (pathNode.termType === 'NamedNode') {
                predicateToFacet[shortName(pathNode.value)] = fieldName;
            } else {
                // Sequence path: map first predicate to this facet field
                const first = getObject(store, pathNode, RDF + 'first');
                if (first && first.termType === 'NamedNode') {
                    predicateToFacet[shortName(first.value)] = fieldName;
                }
                // Inverse path: map the inverted predicate
                const inv = getObject(store, pathNode, SH + 'inversePath');
                if (inv && inv.termType === 'NamedNode') {
                    predicateToFacet[shortName(inv.value)] = fieldName;
                }
            }
        }
    }

    function registerHierarchies(hierNodes, label = null) {
        for (const hierNode of hierNodes) {
            const levelNodes = walkList(store, hierNode);
            const levels = levelNodes
                .filter(n => n.termType === 'NamedNode')
                .map(n => ({
                    name: getLiteral(store, n, IDX + 'fieldName'),
                    iri: n.value,
                }))
                .filter(l => l.name);
            if (levels.length >= 2) {
                const dimName = levels.map(l => l.name).join('_');
                if (label) levels.label = label;
                if (!seenFacets.has(dimName)) {
                    seenFacets.add(dimName);
                    facetFields.push(dimName);
                    hierarchyDimensions.set(dimName, levels);
                }
            }
        }
    }

    for (const shapeNode of shapeNodes) {
        const targetClass = getObject(store, shapeNode, SH + 'targetClass');
        const shape = {
            name: shortName(shapeNode.value),
            targetClass: targetClass ? shortName(targetClass.value) : '?',
            fields: [],
        };

        const propNodes = getObjects(store, shapeNode, SH + 'property');
        for (const propNode of propNodes) {
            registerField(shape, propNode);
        }

        registerHierarchies(getObjects(store, shapeNode, IDX + 'facetHierarchy'));

        const nestedNodes = getObjects(store, shapeNode, IDX + 'nested');
        for (const nestedNode of nestedNodes) {
            const joinPathNode = getObject(store, nestedNode, IDX + 'joinPath');
            let nestedLabel = null;
            if (joinPathNode && joinPathNode.termType === 'NamedNode') {
                nestedLabel = shortName(joinPathNode.value);
                if (nestedLabel === 'identifier') nestedLabel = 'identifiers';
            }
            for (const nestedPropNode of getObjects(store, nestedNode, IDX + 'property')) {
                // Track nested field IRIs for filtering and drill-down, but keep the sidebar
                // focused on the hierarchy dimension rather than duplicating flat child fields.
                registerField(shape, nestedPropNode, { includeFacetField: false, includePredicateMapping: false });
            }
            registerHierarchies(getObjects(store, nestedNode, IDX + 'facetHierarchy'), nestedLabel);
        }

        shapes.push(shape);
    }

    return {
        endpoint: `${FUSEKI_BASE}/${datasetName}/query`,
        storeValues,
        maxFacetHits,
        shapes,
        facetFields,
        sortableFields,
        fieldIRIs,
        fieldInfo,
        predicateToFacet,
        hierarchyDimensions,
    };
}

async function loadConfig() {
    const resp = await fetch(`${CONFIG_PATH}?t=${Date.now()}`);
    if (!resp.ok) throw new Error(`Failed to fetch ${CONFIG_PATH}: ${resp.status}`);
    const text = await resp.text();
    const store = await parseTurtle(text);
    return extractConfig(store);
}

// ---------------------------------------------------------------------------
// Test cases — loaded from tests.json (symlinked per demo scenario)
// ---------------------------------------------------------------------------

async function loadTestCases() {
    try {
        const resp = await fetch(`tests.json?t=${Date.now()}`);
        if (!resp.ok) return [];
        return resp.json();
    } catch {
        return [];
    }
}

// ---------------------------------------------------------------------------
// WKT parser — extracts Leaflet-compatible coordinates from WKT literals
// ---------------------------------------------------------------------------

function parseWktForLeaflet(wktString) {
    let wkt = wktString.trim();
    let isLatLon = false;

    // Strip CRS prefix if present
    if (wkt.startsWith('<')) {
        const close = wkt.indexOf('>');
        const crs = wkt.substring(1, close);
        wkt = wkt.substring(close + 1).trim();
        // EPSG:4326/4283/7844 use lat/lon axis order
        if (crs.includes('4326') || crs.includes('4283') || crs.includes('7844')) {
            isLatLon = true;
        }
    }
    // Bare WKT (no CRS prefix) defaults to CRS84 = lon/lat

    if (wkt.startsWith('POINT')) {
        const m = wkt.match(/POINT\s*\(\s*([-\d.]+)\s+([-\d.]+)\s*\)/);
        if (!m) return null;
        const c1 = parseFloat(m[1]), c2 = parseFloat(m[2]);
        return { type: 'point', lat: isLatLon ? c1 : c2, lon: isLatLon ? c2 : c1 };
    }

    if (wkt.startsWith('POLYGON')) {
        const m = wkt.match(/POLYGON\s*\(\((.*?)\)\)/);
        if (!m) return null;
        const coords = m[1].split(',').map(pair => {
            const [c1, c2] = pair.trim().split(/\s+/).map(Number);
            return isLatLon ? [c1, c2] : [c2, c1]; // [lat, lon] for Leaflet
        });
        return { type: 'polygon', coords };
    }

    return null;
}

// ---------------------------------------------------------------------------
// Alpine.js component: Search page
// ---------------------------------------------------------------------------

function searchApp() {
    return {
        q: '',
        limit: DEFAULT_LIMIT,
        currentPage: 1,
        resultLimits: RESULT_LIMITS,
        maxFacetValues: DEFAULT_FACET_LIMIT,
        facetLimits: FACET_LIMITS,
        sortableFields: [],
        temporalFields: [],
        sortField: '',
        sortDirection: DEFAULT_SORT_DIRECTION,
        selected: {},
        facetFields: [],
        fieldIRIs: {},
        fieldInfo: {},
        predicateToFacet: {},
        hierarchyDimensions: new Map(),
        hierarchyChildren: {},  // dim → { parentValue: [{value, label, count}] }
        hierarchyOpen: {},      // dim → { parentValue: true/false }
        hierarchyLoading: {},   // dim → { parentValue: true/false }
        hierarchySelected: {},  // dim → { parentValue: [childValue, ...] }
        facets: {},
        facetExpanded: {},
        cards: [],
        error: null,
        loading: false,
        showLoading: false,
        _loadingTimer: null,
        description: '',
        _lastTotalHits: 0,
        _facetKey: null,
        _labels: null,
        endpoint: '',
        queryLog: [],
        correlatedFilters: emptyCorrelatedFilterState(),
        suggestKey: null,            // which input's suggestion list is open
        suggestIndex: -1,            // keyboard-highlighted row, -1 for none
        examplesOpen: false,
        exampleGroups: [],           // [{name, examples: [{id, label, params}]}]
        expandedExampleGroups: {},
        activeExampleId: null,
        identifierKindList: [],      // fixed vocabulary: anumber, company, mnumber
        identifierKindSuggestionsByKind: {},   // kind → [{value, label, count}]
        _identifierSuggestTimers: {},
        attributionRoleOptions: [],
        attributionAgentOptions: [],
        attributionAgentsByRole: {},
        spatialBbox: null,
        spatialPolygon: null,
        drawingBbox: false,
        _drawRect: null,
        _drawStart: null,
        _bboxOverlay: null,
        drawingPolygon: false,
        polyPoints: [],
        _polyMarkers: null,
        _polyLine: null,
        _polyOverlay: null,
        mapMarkerCount: 0,
        _map: null,
        _mapLayers: null,
        _mapMarkersByUri: {},
        _highlightTimer: null,
        _abortController: null,
        editorOpen: false,
        editorQuery: '',
        editorResults: '',
        editorRunning: false,
        editorError: null,
        editorEndpoint: '',
        editorView: 'table',
        editorData: null,
        cqlOpen: false,
        cqlJson: null,
        cqlRaw: '',
        cqlView: 'object',
        _cqlRight: 0,
        _cqlTop: 60,
        _cqlWidth: 0,
        _editorRight: 0,
        _editorTop: 60,
        _editorWidth: 0,

        async init() {
            let config;
            try {
                config = await loadConfig();
            } catch (e) {
                this.error = `Failed to load config: ${e.message}`;
                return;
            }

            this.endpoint = config.endpoint;
            this.facetFields = config.facetFields;
            this.sortableFields = config.sortableFields || [];
            this.fieldIRIs = config.fieldIRIs;
            this.fieldInfo = config.fieldInfo || {};
            this.temporalFields = Object.keys(this.fieldInfo).filter(name => this.fieldInfo[name]?.isTemporal);
            this.predicateToFacet = config.predicateToFacet;
            this.hierarchyDimensions = config.hierarchyDimensions || new Map();
            this._labels = new LabelResolver(this.endpoint, APP_CONFIG.labelCacheVersion || '1');

            this.loadFromUrl();
            await this.loadExamples();
            await this.loadIdentifierKinds();
            await this.executeSearch();
            await this.loadAttributionOptions();

            // Auto-expand hierarchy drill-down if specified in URL
            const drillParam = new URLSearchParams(window.location.search).get('drillDown');
            if (drillParam) {
                const sep = drillParam.indexOf(':');
                if (sep > 0) {
                    const dim = drillParam.substring(0, sep);
                    const value = drillParam.substring(sep + 1);
                    if (this.hierarchyDimensions.has(dim)) {
                        await this.toggleHierarchy(dim, value);
                    }
                }
            }

            window.addEventListener('popstate', async () => {
                this.loadFromUrl();
                await this.executeSearch();
            });

            // Initialize map when visible, invalidateSize on toggle
            const self = this;
            Alpine.effect(() => {
                const show = Alpine.store('app').showMap;
                if (show) {
                    setTimeout(() => {
                        if (self._map) self._map.invalidateSize();
                        else self.initMap();
                    }, 50);
                }
            });
        },

        async loadAttributionOptions() {
            try {
                const data = await this.runSparql(`${SPARQL_PREFIXES}
SELECT DISTINCT ?roleLabel ?agentLabel
WHERE {
    ?entity a ex:MiningReport ;
        prov:qualifiedAttribution ?qa .
    OPTIONAL { ?qa prov:hadRole/rdfs:label ?roleLabel }
    OPTIONAL { ?qa prov:agent/rdfs:label ?agentLabel }
}
ORDER BY LCASE(STR(?roleLabel)) LCASE(STR(?agentLabel))`);
                const roles = new Set();
                const agents = new Set();
                const byRole = {};
                for (const row of (data.results?.bindings || [])) {
                    const role = row.roleLabel?.value || '';
                    const agent = row.agentLabel?.value || '';
                    if (role) roles.add(role);
                    if (agent) agents.add(agent);
                    if (role && agent) {
                        if (!byRole[role]) byRole[role] = [];
                        if (!byRole[role].includes(agent)) byRole[role].push(agent);
                    }
                }
                this.attributionRoleOptions = [...roles];
                this.attributionAgentOptions = [...agents];
                this.attributionAgentsByRole = byRole;
            } catch (e) {
                console.warn('Failed to load attribution options:', e);
                this.attributionRoleOptions = [];
                this.attributionAgentOptions = [];
                this.attributionAgentsByRole = {};
            }
        },

        // --- Query log ---

        /**
         * Load the saved searches from tests.json.
         *
         * That file is a flat list where a `{"group": "..."}` entry marks the start of a
         * section rather than being an entry itself — the shape the old navbar dropdown
         * rendered directly. Fold it into real groups so the panel can collapse them.
         */
        async loadExamples() {
            const cases = await loadTestCases();
            const groups = [];
            let current = null;
            cases.forEach((tc, i) => {
                if (tc.group) {
                    current = { name: tc.group, examples: [] };
                    groups.push(current);
                    return;
                }
                if (!tc.label) return;
                if (!current) {
                    current = { name: 'Examples', examples: [] };
                    groups.push(current);
                }
                current.examples.push({ id: `ex-${i}`, label: tc.label, params: tc.params || '' });
            });
            this.exampleGroups = groups.filter(g => g.examples.length > 0);
            // Open the first group so the panel is not a wall of collapsed headers.
            if (this.exampleGroups.length > 0) {
                this.expandedExampleGroups[this.exampleGroups[0].name] = true;
            }
        },

        exampleCount() {
            return this.exampleGroups.reduce((n, g) => n + g.examples.length, 0);
        },

        toggleExampleGroup(name) {
            this.expandedExampleGroups[name] = !this.expandedExampleGroups[name];
        },

        /** One-line hint at what an example actually sets, so the labels are scannable. */
        exampleMeta(ex) {
            const params = new URLSearchParams((ex.params || '').replace(/^\?/, ''));
            const bits = [];
            const q = params.get('q');
            if (q) bits.push(`q=${q}`);
            if (params.get('filter')) bits.push('filter');
            if (params.get('sort')) bits.push('sort');
            if (params.get('page')) bits.push(`page ${params.get('page')}`);
            return bits.join(' · ') || 'wildcard';
        },

        /**
         * Apply an example by pushing its parameters into the URL and re-reading state
         * from there — the same path a shared link or the back button takes, so an
         * example lands the app in a state the user could have reached themselves.
         */
        async applyExample(ex) {
            this.activeExampleId = ex.id;
            const qs = (ex.params || '').replace(/^\?/, '');
            window.history.pushState({}, '', qs ? `?${qs}` : window.location.pathname);
            this.loadFromUrl();
            await this.executeSearch();
        },

        logQuery(label, query, durationMs, isSparql) {
            const dur = durationMs != null ? ` (${(durationMs / 1000).toFixed(2)}s)` : '';
            const trimmed = query.trim();
            const sparql = isSparql !== false && trimmed.toUpperCase().startsWith('PREFIX');
            let isCql = false;
            if (!sparql) {
                try { const p = JSON.parse(trimmed); isCql = p && typeof p.op === 'string'; } catch {}
            }
            this.queryLog.unshift({
                time: timeStamp(),
                label: label + dur,
                query: trimmed,
                isSparql: sparql,
                isCql,
            });
        },

        // --- SPARQL editor ---

        openEditor(query) {
            this.editorQuery = query;
            this.editorEndpoint = this.endpoint;
            this.editorResults = '';
            this.editorData = null;
            this.editorError = null;
            this._editorWidth = Math.max(320, window.innerWidth * 0.5);
            this._editorRight = 0;
            this._editorTop = 60;
            this.editorOpen = true;
            this.$nextTick(() => this.runEditorQuery());
        },

        closeEditor() {
            this.editorOpen = false;
        },

        /**
         * Escape closes one popup at a time, innermost first — the CQL viewer can be
         * opened from the editor, so closing both at once would be surprising.
         */
        closeTopPopup() {
            if (this.cqlOpen) this.closeCql();
            else if (this.editorOpen) this.closeEditor();
        },

        async runEditorQuery() {
            this.editorRunning = true;
            this.editorError = null;
            this.editorResults = '';
            this.editorData = null;
            try {
                const resp = await fetch(this.editorEndpoint, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/sparql-query',
                        'Accept': 'application/sparql-results+json',
                    },
                    body: this.editorQuery,
                });
                if (!resp.ok) {
                    this.editorError = `HTTP ${resp.status}: ${resp.statusText}`;
                    return;
                }
                const text = await resp.text();
                try {
                    this.editorData = JSON.parse(text);
                    this.editorResults = JSON.stringify(this.editorData, null, 2);
                } catch {
                    this.editorData = null;
                    this.editorResults = text;
                }
            } catch (e) {
                this.editorError = e.message;
            } finally {
                this.editorRunning = false;
            }
        },

        editorHasTable() {
            return this.editorData?.head?.vars && this.editorData?.results?.bindings;
        },

        editorTableVars() {
            return this.editorData?.head?.vars || [];
        },

        editorTableRows() {
            return (this.editorData?.results?.bindings || []).map(row => {
                return this.editorTableVars().map(v => {
                    const cell = row[v];
                    if (!cell) return '';
                    if (cell.type === 'uri') return shortName(cell.value);
                    return cell.value;
                });
            });
        },

        // --- CQL viewer ---

        openCql(jsonString) {
            this.cqlRaw = jsonString;
            try {
                this.cqlJson = JSON.parse(jsonString);
            } catch {
                this.cqlJson = null;
            }
            this._cqlWidth = Math.max(320, window.innerWidth * 0.4);
            this._cqlRight = 0;
            this._cqlTop = 60;
            this.cqlOpen = true;
        },

        closeCql() {
            this.cqlOpen = false;
        },

        // --- URL management ---

        pushUrl() {
            const params = new URLSearchParams();
            if (this.q.trim()) params.set('q', this.q.trim());
            if (this.sortField) {
                const sortIri = this.fieldIRIs[this.sortField] || this.sortField;
                params.set('sort', `${sortIri}:${this.sortDirection}`);
            }
            const cql = buildCqlFilter(
                this.selected,
                this.spatialBbox,
                this.spatialPolygon,
                this.fieldIRIs,
                this.extraFilterClauses()
            );
            if (cql) params.set('filter', cql);
            if (this.currentPage > 1) params.set('page', this.currentPage);
            const qs = params.toString();
            const url = qs ? '?' + qs : window.location.pathname;
            history.pushState(null, '', url);
        },

        loadFromUrl() {
            const params = new URLSearchParams(window.location.search);
            this.q = params.get('q') || '';
            const sort = parseSortParam(params.get('sort'), this.fieldIRIs);
            this.sortField = sort.field;
            this.sortDirection = sort.direction;
            const { selected, bbox, polygon, correlated } = parseCqlFilter(params.get('filter'), this.fieldIRIs);
            const fieldNames = new Set([...this.facetFields, ...Object.keys(selected)]);
            this.selected = {};
            for (const f of fieldNames) {
                this.selected[f] = selected[f] || [];
            }
            this.correlatedFilters = correlated || emptyCorrelatedFilterState();
            this.hierarchySelected = this.inferHierarchySelections(this.selected);
            this.spatialBbox = bbox;
            this.spatialPolygon = polygon;
            this.currentPage = Math.max(1, parseInt(params.get('page'), 10) || 1);
        },

        // --- Actions ---

        async search() {
            this.currentPage = 1;
            this.pushUrl();
            await this.executeSearch();
        },

        async goToPage(page) {
            this.currentPage = page;
            this.pushUrl();
            await this.executeSearch();
        },

        totalPages() {
            if (!this._lastTotalHits || this.limit <= 0) return 1;
            return Math.ceil(this._lastTotalHits / this.limit);
        },

        paginationRange() {
            const tp = this.totalPages();
            const cur = this.currentPage;
            if (tp <= 7) return Array.from({ length: tp }, (_, i) => i + 1);
            const pages = [1];
            if (cur > 3) pages.push('...');
            for (let p = Math.max(2, cur - 1); p <= Math.min(tp - 1, cur + 1); p++) {
                pages.push(p);
            }
            if (cur < tp - 2) pages.push('...');
            pages.push(tp);
            return pages;
        },

        async toggleFacet(field, value) {
            if (!this.selected[field]) this.selected[field] = [];
            const idx = this.selected[field].indexOf(value);
            if (idx >= 0) {
                this.selected[field].splice(idx, 1);
            } else {
                this.selected[field].push(value);
            }
            this.pushUrl();
            await this.executeSearch();
        },

        async clearFilters() {
            for (const f of Object.keys(this.selected)) {
                this.selected[f] = [];
            }
            this.hierarchySelected = {};
            this.clearCorrelatedFilters();
            this.clearBbox();
            this.clearPolygon();
            this.pushUrl();
            await this.executeSearch();
        },

        hasActiveFilters() {
            return this.spatialBbox != null || this.spatialPolygon != null ||
                this.correlatedFiltersActive() ||
                Object.values(this.selected).some(values => (values || []).length > 0) ||
                Object.values(this.hierarchySelected || {}).some(parents =>
                    Object.values(parents || {}).some(values => (values || []).length > 0));
        },

        temporalFieldLabel(fieldName) {
            return this.facetLabel(fieldName);
        },

        temporalInputType(fieldName) {
            const info = this.fieldInfo[fieldName];
            return info && String(info.fieldType || '').toLowerCase().includes('datetime') ? 'datetime-local' : 'date';
        },

        temporalSliderStep(fieldName) {
            const info = this.fieldInfo[fieldName];
            return info && String(info.fieldType || '').toLowerCase().includes('datetime') ? HOUR_MS : DAY_MS;
        },

        temporalBounds(fieldName) {
            return temporalFieldPresetBounds(fieldName, this.fieldInfo[fieldName]);
        },

        temporalRangeSelection(fieldName) {
            const values = this.selected[fieldName] || [];
            const current = values.find(v => typeof v === 'string' && v.startsWith('__RANGE__'));
            if (!current) return { low: '', high: '' };
            const [low, high] = current.substring(9).split('|');
            return { low: low || '', high: high || '' };
        },

        temporalInputValue(fieldName, side) {
            const value = this.temporalRangeSelection(fieldName)[side];
            return formatTemporalInputValue(this.fieldInfo[fieldName], value);
        },

        temporalSliderMin(fieldName) {
            return temporalToMillis(this.fieldInfo[fieldName], this.temporalBounds(fieldName).min);
        },

        temporalSliderMax(fieldName) {
            return temporalToMillis(this.fieldInfo[fieldName], this.temporalBounds(fieldName).max);
        },

        temporalSliderValue(fieldName, side) {
            const bounds = this.temporalBounds(fieldName);
            const range = this.temporalRangeSelection(fieldName);
            const raw = range[side] || bounds[side === 'low' ? 'min' : 'max'];
            return temporalToMillis(this.fieldInfo[fieldName], raw);
        },

        temporalSliderPercent(fieldName, side) {
            const min = this.temporalSliderMin(fieldName);
            const max = this.temporalSliderMax(fieldName);
            const value = this.temporalSliderValue(fieldName, side);
            if (!Number.isFinite(min) || !Number.isFinite(max) || max <= min) {
                return side === 'low' ? 0 : 100;
            }
            return ((value - min) / (max - min)) * 100;
        },

        temporalSliderRangeStyle(fieldName) {
            const low = this.temporalSliderPercent(fieldName, 'low');
            const high = this.temporalSliderPercent(fieldName, 'high');
            return `left:${Math.min(low, high)}%; width:${Math.max(0, Math.abs(high - low))}%;`;
        },

        setTemporalRange(fieldName, low, high) {
            if (!this.selected[fieldName]) this.selected[fieldName] = [];
            this.selected[fieldName] = this.selected[fieldName]
                .filter(v => !(typeof v === 'string' && v.startsWith('__RANGE__')));
            if (low || high) {
                this.selected[fieldName].push(`__RANGE__${low || ''}|${high || ''}`);
            }
        },

        async updateTemporalInput(fieldName, side, rawValue) {
            const info = this.fieldInfo[fieldName];
            const current = this.temporalRangeSelection(fieldName);
            const next = side === 'low'
                ? { low: info?.isTemporal && String(info.fieldType || '').toLowerCase().includes('datetime') ? normalizeDateTimeInput(rawValue) : normalizeDateInput(rawValue), high: current.high }
                : { low: current.low, high: info?.isTemporal && String(info.fieldType || '').toLowerCase().includes('datetime') ? normalizeDateTimeInput(rawValue) : normalizeDateInput(rawValue) };
            if (next.low && next.high && temporalToMillis(info, next.low) > temporalToMillis(info, next.high)) {
                if (side === 'low') next.high = next.low;
                else next.low = next.high;
            }
            this.setTemporalRange(fieldName, next.low, next.high);
            this.pushUrl();
            await this.executeSearch();
        },

        async updateTemporalSlider(fieldName, side, sliderValue) {
            const info = this.fieldInfo[fieldName];
            const current = this.temporalRangeSelection(fieldName);
            const nextValue = millisToTemporal(info, Number(sliderValue));
            const next = side === 'low'
                ? { low: nextValue, high: current.high }
                : { low: current.low, high: nextValue };
            if (next.low && next.high && temporalToMillis(info, next.low) > temporalToMillis(info, next.high)) {
                if (side === 'low') next.high = next.low;
                else next.low = next.high;
            }
            this.setTemporalRange(fieldName, next.low, next.high);
            this.pushUrl();
            await this.executeSearch();
        },

        async clearTemporalRange(fieldName) {
            this.setTemporalRange(fieldName, '', '');
            this.pushUrl();
            await this.executeSearch();
        },

        sortLabel() {
            if (!this.sortField) return 'relevance';
            return `${this.sortField} ${this.sortDirection === 'desc' ? 'desc' : 'asc'}`;
        },

        facetLabel(fieldName) {
            const hierarchy = this.hierarchyDimensions.get(fieldName);
            return hierarchy?.label || fieldName;
        },

        isSelected(field, value) {
            return (this.selected[field] || []).includes(value);
        },

        visibleFacets(fieldName) {
            const all = this.facets[fieldName] || [];
            if (this.facetExpanded[fieldName] || all.length <= 5) return all;
            return all.slice(0, 5);
        },

        isHierarchy(fieldName) {
            return this.hierarchyDimensions.has(fieldName);
        },

        getHierarchyChildLevel(dim) {
            const levels = this.hierarchyDimensions.get(dim);
            return levels && levels.length >= 2 ? levels[1] : null;
        },

        getHierarchyChildFieldName(dim) {
            return this.getHierarchyChildLevel(dim)?.name || null;
        },

        getHierarchyParentLevel(dim) {
            const levels = this.hierarchyDimensions.get(dim);
            return levels && levels.length >= 1 ? levels[0] : null;
        },

        isHierarchyOpen(dim, parentValue) {
            return !!(this.hierarchyOpen[dim] && this.hierarchyOpen[dim][parentValue]);
        },

        isHierarchyLoading(dim, parentValue) {
            return !!(this.hierarchyLoading[dim] && this.hierarchyLoading[dim][parentValue]);
        },

        getHierarchyChildren(dim, parentValue) {
            return (this.hierarchyChildren[dim] && this.hierarchyChildren[dim][parentValue]) || [];
        },

        inferHierarchySelections(selected) {
            const inferred = {};
            for (const [dim, levels] of this.hierarchyDimensions.entries()) {
                if (!levels || levels.length < 2) continue;
                const parentField = levels[0].name;
                const childField = levels[1].name;
                const parents = selected[parentField] || [];
                const children = selected[childField] || [];
                if (parents.length === 0 || children.length === 0) continue;
                inferred[dim] = {};
                for (const parentValue of parents) {
                    inferred[dim][parentValue] = [...children];
                }
            }
            return inferred;
        },

        isHierarchyChildSelected(dim, value) {
            const childField = this.getHierarchyChildFieldName(dim);
            return childField ? this.isSelected(childField, value) : false;
        },

        buildHierarchyParentClauses(excludedDim = null) {
            const clauses = [];
            for (const [dim, parents] of Object.entries(this.hierarchySelected || {})) {
                if (excludedDim && dim === excludedDim) continue;
                const parentLevel = this.getHierarchyParentLevel(dim);
                if (!parentLevel) continue;
                for (const [parentValue, childValues] of Object.entries(parents || {})) {
                    if (!childValues || childValues.length === 0) continue;
                    clauses.push({
                        op: '=',
                        args: [{property: parentLevel.iri}, parentValue],
                    });
                }
            }
            return clauses;
        },

        async ensureHierarchyChildren(dim, parentValue) {
            if (!this.hierarchyLoading[dim]) this.hierarchyLoading[dim] = {};
            this.hierarchyLoading[dim][parentValue] = true;

            try {
                const levels = this.hierarchyDimensions.get(dim);
                if (!levels || levels.length < 2) return;

                // Drill down by naming the hierarchy's dimension: the engine turns the
                // CQL "=" on the parent level into the taxonomy drill-down path. Asking
                // for the child level's field IRI instead would return that field's own
                // flat counts across every parent, which is a different question.
                const parentLevel = levels[0];
                const childLevel = levels[1];
                const parentLevelIRI = parentLevel.iri;

                const term = this.q.trim() || '*';
                const searchField = 'default';

                const hierFilter = JSON.stringify({
                    op: '=',
                    args: [{ property: parentLevelIRI }, parentValue],
                });
                const selectedWithoutCurrentHierarchy = { ...this.selected };
                delete selectedWithoutCurrentHierarchy[parentLevel.name];
                delete selectedWithoutCurrentHierarchy[childLevel.name];

                const existingCql = buildCqlFilter(
                    selectedWithoutCurrentHierarchy,
                    this.spatialBbox,
                    this.spatialPolygon,
                    this.fieldIRIs,
                    this.extraFilterClauses(dim)
                );
                let combinedFilter;
                if (existingCql) {
                    const existing = JSON.parse(existingCql);
                    combinedFilter = JSON.stringify({
                        op: 'and',
                        args: [existing, JSON.parse(hierFilter)],
                    });
                } else {
                    combinedFilter = hierFilter;
                }

                const query = `${SPARQL_PREFIXES}
SELECT ?field ?value ?low ?high ?count WHERE {
    (?field ?value ?low ?high ?count) luc:facet ('default' ${sparqlQuote(searchField)} ${sparqlQuote(term)} ${sparqlQuote(JSON.stringify([dim]))} ${sparqlQuote(combinedFilter)} ${this.maxFacetValues} 0)
}`;
                const data = await this.runSparql(query);
                const children = [];
                for (const row of (data.results?.bindings || [])) {
                    if (row.value && row.count) {
                        const childVal = row.value.value;
                        const childIsUri = row.value.type === 'uri' || /^https?:\/\//.test(childVal);
                        children.push({
                            value: childVal,
                            label: childIsUri ? shortName(childVal) : childVal,
                            count: parseInt(row.count.value, 10),
                        });
                    }
                }
                children.sort((a, b) => b.count - a.count);
                if (!this.hierarchyChildren[dim]) this.hierarchyChildren[dim] = {};
                this.hierarchyChildren[dim][parentValue] = children;
            } catch (e) {
                console.error('Hierarchy drill-down failed:', e);
            } finally {
                this.hierarchyLoading[dim][parentValue] = false;
            }
        },

        async restoreHierarchySelections() {
            for (const [dim, parents] of Object.entries(this.hierarchySelected || {})) {
                if (!this.hierarchyOpen[dim]) this.hierarchyOpen[dim] = {};
                for (const [parentValue, childValues] of Object.entries(parents || {})) {
                    if (!childValues || childValues.length === 0) continue;
                    this.hierarchyOpen[dim][parentValue] = true;
                    await this.ensureHierarchyChildren(dim, parentValue);
                }
            }
        },

        async toggleHierarchyChild(dim, parentValue, value) {
            const childField = this.getHierarchyChildFieldName(dim);
            if (!childField) return;
            if (!this.selected[childField]) this.selected[childField] = [];
            if (!this.hierarchySelected[dim]) this.hierarchySelected[dim] = {};
            if (!this.hierarchySelected[dim][parentValue]) this.hierarchySelected[dim][parentValue] = [];

            const idx = this.selected[childField].indexOf(value);
            if (idx >= 0) {
                this.selected[childField].splice(idx, 1);
                const values = this.hierarchySelected[dim][parentValue];
                const pairIdx = values.indexOf(value);
                if (pairIdx >= 0) values.splice(pairIdx, 1);
                if (values.length === 0) delete this.hierarchySelected[dim][parentValue];
                if (Object.keys(this.hierarchySelected[dim]).length === 0) delete this.hierarchySelected[dim];
            } else {
                this.selected[childField].push(value);
                this.hierarchySelected[dim][parentValue].push(value);
            }
            this.pushUrl();
            await this.executeSearch();
        },

        async toggleHierarchy(dim, parentValue) {
            if (!this.hierarchyOpen[dim]) this.hierarchyOpen[dim] = {};
            const isOpen = this.hierarchyOpen[dim][parentValue];
            this.hierarchyOpen[dim][parentValue] = !isOpen;

            // Fetch children on first open
            if (!isOpen && !this.getHierarchyChildren(dim, parentValue).length) {
                await this.ensureHierarchyChildren(dim, parentValue);
            }
        },

        _resolveFieldName(fieldUri) {
            return resolveFieldName(fieldUri, this.fieldIRIs);
        },

        _resolveHierarchyDim(fieldName) {
            for (const [dim, levels] of this.hierarchyDimensions) {
                if (levels.some(l => l.name === fieldName)) return dim;
            }
            return null;
        },

        identifierHierarchyDim() {
            for (const [dim, levels] of this.hierarchyDimensions.entries()) {
                const names = (levels || []).map(level => level.name);
                if (names[0] === 'identifierType' && names[1] === 'identifierValueExact') {
                    return dim;
                }
            }
            return 'identifierType_identifierValueExact';
        },

        /**
         * Facets to render in the sidebar.
         *
         * The identifier hierarchy is still requested — its top level supplies the
         * identifier kinds — but it is not rendered as a facet. Drilling into it is
         * useless: the child level is a high-cardinality set of exact identifier values,
         * so a kind either expands to hundreds of one-count entries or, once filtered,
         * to nothing at all. RDF models the kind and value as one nested node; the UI
         * does not have to mirror that. The kinds are presented instead as three
         * independent typeahead fields.
         */
        sidebarFacetFields() {
            const identifierDim = this.identifierHierarchyDim();
            return this.facetFields.filter(f => f !== identifierDim);
        },

        /**
         * The identifier kinds, loaded once and held fixed.
         *
         * They cannot be read off the current search's facets: as soon as an identifier
         * filter is active the engine turns the `=` on the kind into a taxonomy
         * drill-down, so the dimension comes back holding *child* values — and the UI
         * would render "A9412" as though it were a kind, with an input labelled "type
         * ahead within A9412".
         *
         * A flat facet on identifierType is not an option either: it is a nested field,
         * so its values sit on child documents and never count against parents. An
         * unfiltered facet on the dimension returns its top level, which is the vocabulary
         * we want, and it does not change as the user searches.
         */
        async loadIdentifierKinds() {
            const dim = JSON.stringify([this.identifierHierarchyDim()]);
            const query = `${SPARQL_PREFIXES}
SELECT ?value ?count WHERE {
    (?field ?value ?low ?high ?count) luc:facet ('default' 'default' '*' ${sparqlQuote(dim)} '' 50 0)
}`;
            try {
                const data = await this.runSparql(query);
                this.identifierKindList = (data.results?.bindings || [])
                    .filter(row => row.value)
                    .map(row => ({ value: row.value.value, label: row.value.value }))
                    .sort((a, b) => a.label.localeCompare(b.label));
            } catch (e) {
                console.error('Loading identifier kinds failed:', e);
                this.identifierKindList = [];
            }
        },

        identifierKinds() {
            return this.identifierKindList;
        },

        identifierKindValue(kind) {
            return this.correlatedFilters.identifierTerms[kind] || '';
        },

        setIdentifierKindValue(kind, value) {
            const trimmed = String(value || '').trim();
            if (trimmed) {
                this.correlatedFilters.identifierTerms[kind] = trimmed;
            } else {
                delete this.correlatedFilters.identifierTerms[kind];
            }
        },

        /**
         * Typeahead for one identifier kind.
         *
         * Two CQL clauses under an `and`, so both must hold on the *same* nested child:
         * the kind, and an edge-ngram match on what has been typed. `identifierValueText`
         * is indexed with EdgeNGramAnalyzer and queried with LowerCaseKeywordAnalyzer, so
         * a prefix matches without the caller doing anything special.
         *
         * Faceting the hierarchy dimension (rather than the value field) is what makes the
         * counts reflect the kind: the `=` on the kind becomes the taxonomy drill-down.
         */
        async fetchIdentifierKindSuggestions(kind, term) {
            const typed = String(term || '').trim();
            const args = [
                { op: '=', args: [{ property: fieldIri(this.fieldIRIs, 'identifierType') }, kind] },
            ];
            if (typed) {
                args.push({
                    op: 'text_query',
                    args: [{ property: fieldIri(this.fieldIRIs, 'identifierValueText') }, typed],
                });
            }
            const filter = JSON.stringify(args.length === 1 ? args[0] : { op: 'and', args });
            const dim = JSON.stringify([this.identifierHierarchyDim()]);

            const query = `${SPARQL_PREFIXES}
SELECT ?value ?count WHERE {
    (?field ?value ?low ?high ?count) luc:facet ('default' 'default' '*' ${sparqlQuote(dim)} ${sparqlQuote(filter)} ${IDENTIFIER_SUGGESTION_LIMIT} 0)
}`;
            try {
                const data = await this.runSparql(query);
                const suggestions = (data.results?.bindings || [])
                    .filter(row => row.value)
                    .map(row => ({
                        value: row.value.value,
                        label: row.value.value,
                        count: row.count ? parseInt(row.count.value, 10) : 0,
                    }));
                this.identifierKindSuggestionsByKind[kind] = suggestions;
            } catch (e) {
                console.error('Identifier typeahead failed:', e);
                this.identifierKindSuggestionsByKind[kind] = [];
            }
        },

        /** Debounced so a fast typist issues one query, not one per keystroke. */
        ensureIdentifierKindSuggestions(kind, term = '') {
            clearTimeout(this._identifierSuggestTimers[kind]);
            this._identifierSuggestTimers[kind] = setTimeout(() => {
                this.fetchIdentifierKindSuggestions(kind, term);
            }, IDENTIFIER_SUGGESTION_DEBOUNCE_MS);
        },

        identifierSuggestionId(kind) {
            return `identifier-suggestions-${sanitizeDomId(kind)}`;
        },

        // ---- Suggestion dropdown ----
        //
        // Replaces <datalist>, which the browser renders as unstyleable native chrome —
        // a light popup in a dark app, with no control over rows or keyboard behaviour.
        // One list is open at a time, identified by key.

        isSuggestOpen(key) {
            return this.suggestKey === key;
        },

        openSuggest(key) {
            if (this.suggestKey !== key) this.suggestIndex = -1;
            this.suggestKey = key;
        },

        /**
         * Close the open list. Pass a key to close only that one: every input's
         * click-outside handler fires when another input is clicked, so an unqualified
         * close would wipe the list the click just opened.
         */
        closeSuggest(key = null) {
            if (key !== null && this.suggestKey !== key) return;
            this.suggestKey = null;
            this.suggestIndex = -1;
        },

        /** Arrow keys wrap around, so the list is reachable from either end. */
        moveSuggest(delta, count) {
            if (count === 0) return;
            this.suggestIndex = (this.suggestIndex + delta + count) % count;
        },

        chooseIdentifier(kind, value) {
            this.setIdentifierKindValue(kind, value);
            this.closeSuggest();
            this.search();
        },

        /** Enter takes the highlighted row if there is one, otherwise just searches. */
        identifierEnter(kind) {
            const items = this.identifierKindSuggestions(kind);
            const picked = this.suggestIndex >= 0 ? items[this.suggestIndex] : null;
            if (picked) {
                this.chooseIdentifier(kind, picked.value);
                return;
            }
            this.closeSuggest();
            this.search();
        },

        chooseCorrelated(field, value) {
            this.correlatedFilters[field] = value;
            this.closeSuggest();
            this.search();
        },

        correlatedEnter(field, items) {
            const picked = this.suggestIndex >= 0 ? items[this.suggestIndex] : null;
            if (picked) {
                this.chooseCorrelated(field, picked);
                return;
            }
            this.closeSuggest();
            this.search();
        },

        identifierKindSuggestions(kind) {
            return this.identifierKindSuggestionsByKind[kind] || [];
        },

        filteredAttributionAgents() {
            const role = this.correlatedFilters.attributionRole.trim();
            if (role && Array.isArray(this.attributionAgentsByRole[role])) {
                return this.attributionAgentsByRole[role];
            }
            return this.attributionAgentOptions;
        },

        correlatedIdentifierClauses() {
            const clauses = [];
            const typeField = fieldIri(this.fieldIRIs, 'identifierType');
            const valueField = fieldIri(this.fieldIRIs, 'identifierValueText');
            for (const [kind, rawValue] of Object.entries(this.correlatedFilters.identifierTerms || {})) {
                const value = String(rawValue || '').trim();
                if (!kind || !value) continue;
                clauses.push({
                    op: 'and',
                    args: [
                        { op: '=', args: [{ property: typeField }, kind] },
                        { op: 'text_query', args: [{ property: valueField }, value] },
                    ],
                });
            }
            return clauses;
        },

        correlatedAttributionClauses() {
            const role = this.correlatedFilters.attributionRole.trim();
            const agent = this.correlatedFilters.attributionAgent.trim();
            if (!role && !agent) return [];

            const args = [];
            if (role) {
                args.push({ op: '=', args: [{ property: fieldIri(this.fieldIRIs, 'attributionRole') }, role] });
            }
            if (agent) {
                args.push({ op: 'text_query', args: [{ property: fieldIri(this.fieldIRIs, 'attributionAgentText') }, agent] });
            }
            return args.length === 1 ? args : [{ op: 'and', args }];
        },

        extraFilterClauses(excludedDim = null) {
            return [
                ...this.buildHierarchyParentClauses(excludedDim),
                ...this.correlatedIdentifierClauses(),
                ...this.correlatedAttributionClauses(),
            ];
        },

        correlatedFiltersActive() {
            return Object.keys(this.correlatedFilters.identifierTerms || {}).length > 0
                || !!this.correlatedFilters.attributionRole.trim()
                || !!this.correlatedFilters.attributionAgent.trim();
        },

        clearCorrelatedFilters() {
            this.correlatedFilters = emptyCorrelatedFilterState();
        },

        correlatedFilterSummary() {
            const parts = [];
            for (const [kind, value] of Object.entries(this.correlatedFilters.identifierTerms || {})) {
                if (!value) continue;
                parts.push(`${shortName(kind)} contains “${escapeHtml(value)}”`);
            }
            const attrRole = this.correlatedFilters.attributionRole.trim();
            const attrAgent = this.correlatedFilters.attributionAgent.trim();
            if (attrRole || attrAgent) {
                const bits = [];
                if (attrRole) bits.push(`role = “${escapeHtml(attrRole)}”`);
                if (attrAgent) bits.push(`agent contains “${escapeHtml(attrAgent)}”`);
                parts.push(bits.join(' AND '));
            }
            return parts;
        },

        // --- SPARQL execution ---

        async runSparql(query, signal) {
            const resp = await fetch(this.endpoint, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/sparql-query',
                    'Accept': 'application/sparql-results+json',
                },
                body: query,
                signal,
            });
            return parseSparqlJsonResponse(resp);
        },

        async runSparqlText(query, accept = 'text/turtle', signal) {
            const resp = await fetch(this.endpoint, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/sparql-query',
                    'Accept': accept,
                },
                body: query,
                signal,
            });
            return parseSparqlTextResponse(resp);
        },

        // --- Query builders ---

        /**
         * Everything the facet buckets depend on, and nothing else. Notably not the page:
         * paging through an unchanged result set cannot change a bucket count, so page 2+
         * skips the facet branch entirely and keeps the buckets already on screen.
         */
        facetStateKey() {
            return JSON.stringify([
                this.q.trim(),
                this.selected,
                this.spatialBbox,
                this.spatialPolygon,
                this.extraFilterClauses(),
                this.maxFacetValues,
                this.facetFields,
            ]);
        },

        buildSearchQuery(includeFacets = true) {
            const term = this.q.trim() || '*';
            const searchField = 'default';
            const escaped = escapeSparql(term);
            const cqlFilter = buildCqlFilter(
                this.selected,
                this.spatialBbox,
                this.spatialPolygon,
                this.fieldIRIs,
                this.extraFilterClauses()
            );
            const filterArg = cqlFilter ? sparqlQuote(cqlFilter) : "''";
            const sortSpec = buildSortSpec(this.sortField, this.sortDirection, this.fieldIRIs);
            const sortArg = sortSpec ? sparqlQuote(sortSpec) : "''";
            const facetRequests = this.facetFields.map(f => {
                // A hierarchy is addressed by its dimension name; a plain field by its IRI.
                // (Faceting on a level's field IRI now returns that level's own flat counts,
                // so it is no longer a way to reach the hierarchy.)
                const hier = this.hierarchyDimensions.get(f);
                const target = hier ? f : (this.fieldIRIs[f] || f);
                const ranges = facetRangeSpec(f, this.fieldInfo[f]);
                if (ranges) return { field: target, ranges };
                return target;
            });
            const facetFieldsJson = JSON.stringify(facetRequests);
            const offset = (this.currentPage - 1) * this.limit;

            const queryBranch =
                `    { (?hit ?entity ?score ?rank ?totalHits) luc:query ('default' ${sparqlQuote(searchField)} ${sparqlQuote(term)} ${filterArg} ${sortArg} ${this.limit} ${offset}) }`;

            // Page 2+ of an unchanged filter set reuses the buckets already on screen —
            // they cannot have changed, and recomputing them is the expensive half.
            const facetBranch = includeFacets
                ? `\n    UNION\n    { (?field ?value ?low ?high ?count) luc:facet ('default' ${sparqlQuote(searchField)} ${sparqlQuote(term)} ${sparqlQuote(facetFieldsJson)} ${filterArg} ${this.maxFacetValues} 0)\n      BIND(BNODE() AS ?bucket) }`
                : '';

            // CONSTRUCT rather than SELECT: one RDF payload carrying hits and buckets
            // together. ?hit is luc:query's own query-scoped blank node, so it serves as
            // the hit's subject; buckets get a fresh BNODE() per solution. Each branch
            // leaves the other's variables unbound, so only its own triples are emitted.
            return `${SPARQL_PREFIXES}
CONSTRUCT {
    ?hit res:entity ?entity ; res:score ?score ; res:rank ?rank ; res:totalHits ?totalHits .
    ?bucket res:field ?field ; res:value ?value ; res:low ?low ; res:high ?high ; res:count ?count .
}
WHERE {
${queryBranch}${facetBranch}
}`;
        },

        /**
         * Card details for the whole page in one DESCRIBE.
         *
         * DESCRIBE takes any number of resources, so this is a single request, not one
         * per card. It returns each entity's own triples, which is exactly what a card
         * shows — and it says what it means, unlike an `?entity ?p ?o` SELECT.
         *
         * It does not carry the labels of referenced IRIs: an object comes back as
         * commodity:Gold, not "Gold". Those are resolved separately by labels.js, one
         * cacheable GET per IRI.
         */
        buildDetailQuery(uris) {
            const values = uris.map(u => `<${u}>`).join(' ');
            return `${SPARQL_PREFIXES}
DESCRIBE ${values}`;
        },

        buildDescribeQuery(uri) {
            return `${SPARQL_PREFIXES}
DESCRIBE <${uri}>`;
        },

        // --- Result parsing ---

        /**
         * Read the search CONSTRUCT's graph.
         *
         * A graph is unordered, so result order comes back from res:rank — the hit's
         * position in the whole result set. Score cannot do this job: a match-all query
         * (the default '*' view) scores every document 1.0, and real relevance scores tie.
         */
        parseGraphResults(store) {
            const hits = [];
            const facets = {};
            let totalHits = null;

            for (const node of store.getSubjects(nn(RES + 'entity'), null, null)) {
                const entity = getObject(store, node, RES + 'entity');
                if (!entity) continue;
                hits.push({
                    uri: entity.value,
                    score: parseFloat(getLiteral(store, node, RES + 'score')),
                    rank: parseInt(getLiteral(store, node, RES + 'rank'), 10),
                });
                if (totalHits === null) {
                    const total = getLiteral(store, node, RES + 'totalHits');
                    if (total !== null) totalHits = parseInt(total, 10);
                }
            }

            for (const quad of store.getQuads(null, nn(RES + 'field'), null, null)) {
                const bucket = quad.subject;
                // ?field is the field IRI that was requested, or a hierarchy's dimension
                // name when the request named a dimension (which has no IRI).
                const fieldTerm = quad.object;
                let f = fieldTerm.termType === 'Literal'
                    ? fieldTerm.value
                    : this._resolveFieldName(fieldTerm.value);
                f = this._resolveHierarchyDim(f) || f;
                if (!facets[f]) facets[f] = [];

                const valueTerm = getObject(store, bucket, RES + 'value');
                const low = getLiteral(store, bucket, RES + 'low') ?? null;
                const high = getLiteral(store, bucket, RES + 'high') ?? null;

                let rawVal;
                let label;
                if (valueTerm) {
                    rawVal = valueTerm.value;
                    const isUri = valueTerm.termType === 'NamedNode' || /^https?:\/\//.test(rawVal);
                    label = isUri ? shortName(rawVal) : rawVal;
                } else if (low !== null || high !== null) {
                    rawVal = `__RANGE__${low ?? ''}|${high ?? ''}`;
                    label = `${low ?? '*'} to ${high ?? '*'}`;
                } else {
                    rawVal = '__NULL__';
                    label = '(empty)';
                }

                facets[f].push({
                    value: rawVal,
                    label,
                    count: parseInt(getLiteral(store, bucket, RES + 'count'), 10),
                    low,
                    high,
                });
            }

            hits.sort((a, b) => a.rank - b.rank);
            return { hits, facets, totalHits };
        },

        mergeFacets(rawFacets) {
            const merged = {};
            for (const f of this.facetFields) {
                const values = {};
                for (const fv of (rawFacets[f] || [])) {
                    values[fv.value] = fv;
                }
                for (const sv of (this.selected[f] || [])) {
                    if (!values[sv]) {
                        values[sv] = {
                            value: sv,
                            label: typeof sv === 'string' && sv.startsWith('__RANGE__')
                                ? formatSelectedValue(f, sv).replace(`${escapeHtml(f)} in `, '').replace(/<\/?[^>]+(>|$)/g, '')
                                : shortName(sv),
                            count: 0
                        };
                    }
                }
                merged[f] = Object.values(values).sort((a, b) =>
                    b.count - a.count || (a.label || a.value).localeCompare(b.label || b.value)
                );
            }
            return merged;
        },

        /**
         * Build cards from the DESCRIBE graph, one per hit, in rank order.
         *
         * Only the hit entities become cards. A DESCRIBE also pulls in the blank nodes
         * hanging off them (the nested identifier and attribution nodes), which is why
         * blank-node objects are skipped — the same exclusion the previous SELECT did
         * with `FILTER(!isBlank(?o))`.
         */
        parseEntityDetails(store, hitsByUri) {
            const entities = {};
            for (const [uri, hit] of Object.entries(hitsByUri)) {
                entities[uri] = {
                    uri,
                    // Filled in from labels.js once the per-IRI lookups land.
                    label: shortName(uri),
                    score: hit.score ?? 0,
                    rank: hit.rank ?? Number.MAX_SAFE_INTEGER,
                    identifier: null,
                    description: null,
                    properties: {},
                    nested: {},       // predicate → [[{property, displayValue, ...}, ...], ...]
                    rows: [],
                    turtleOpen: false,
                    turtleLoading: false,
                    turtleLoaded: false,
                    turtleText: '',
                    turtleError: null,
                };

                const card = entities[uri];
                for (const quad of store.getQuads(nn(uri), null, null, null)) {
                    const object = quad.object;
                    if (object.value === RDFS_RESOURCE) continue;
                    const pred = shortName(quad.predicate.value);

                    // A blank node is a qualified relation — a nested identifier or
                    // attribution. DESCRIBE returns its triples too, so render them
                    // inline rather than dropping the whole node.
                    if (object.termType === 'BlankNode') {
                        const parts = [];
                        for (const child of store.getQuads(object, null, null, null)) {
                            if (child.object.termType === 'BlankNode') continue;
                            parts.push({
                                property: shortName(child.predicate.value),
                                ...propValue(termToProperty(child.object)),
                            });
                        }
                        if (parts.length > 0) {
                            if (!card.nested[pred]) card.nested[pred] = [];
                            card.nested[pred].push(parts);
                        }
                        continue;
                    }

                    const pv = termToProperty(object);
                    if (!card.properties[pred]) card.properties[pred] = [];
                    if (!card.properties[pred].some(e => e.raw === pv.raw)) {
                        card.properties[pred].push(pv);
                    }
                }
            }

            const cards = Object.values(entities);
            for (const card of cards) {
                if (card.properties.description) {
                    card.description = card.properties.description[0].display;
                }
                for (const [pred, values] of Object.entries(card.properties)) {
                    if (pred === 'description') continue;
                    if (pred === 'identifier') {
                        card.rows.push({
                            property: 'id',
                            values: values.map(pv => propValue(pv)),
                        });
                        continue;
                    }
                    if (pred === 'asWKT') {
                        for (const pv of values) {
                            const geo = parseWktForLeaflet(pv.raw);
                            if (geo) {
                                card.rows.push({
                                    property: 'location',
                                    values: [{
                                        value: geo.type === 'point' ? 'Point' : 'Polygon',
                                        displayValue: geo.type === 'point' ? 'Point' : 'Polygon',
                                        tooltip: geo.type === 'point'
                                            ? `${geo.lat.toFixed(4)}, ${geo.lon.toFixed(4)}`
                                            : geo.coords.map(c => `${c[0].toFixed(2)},${c[1].toFixed(2)}`).join(' '),
                                    }],
                                });
                            }
                        }
                        continue;
                    }
                    if (pred === 'year' || pred === 'depth') {
                        card.rows.push({
                            property: pred,
                            values: values.map(pv => propValue(pv)),
                        });
                        continue;
                    }
                    // Skip non-facetable literal values that do not add much to the card.
                    const facetable = !!this.predicateToFacet[pred];
                    if (!facetable && !values.some(v => v.isUri || v.lang || v.datatype)) continue;
                    card.rows.push({
                        property: pred,
                        values: values.map(pv => propValue(pv)),
                    });
                }

                // Qualified relations last: each nested node becomes one line of
                // "property value" pairs, so the parts that belong together stay together.
                for (const [pred, children] of Object.entries(card.nested)) {
                    card.rows.push({ property: pred, values: [], children });
                }
            }

            // res:rank carries the order — see parseGraphResults. Sorting by score would
            // scramble the default '*' view, where every hit scores 1.0.
            return cards.sort((a, b) => a.rank - b.rank);
        },

        /**
         * Fill in labels for every IRI on the current cards — the entity IRIs themselves
         * and any IRI-valued property. One GET per IRI, resolved from the browser cache
         * on a repeat view. Mutates the cards in place so Alpine re-renders as they land.
         */
        async resolveCardLabels(cards) {
            // Walk the rendered rows rather than the raw properties, so values nested
            // inside a qualified relation are resolved on the same pass.
            const eachValue = (card, fn) => {
                for (const row of card.rows) {
                    row.values.forEach(fn);
                    for (const parts of (row.children || [])) parts.forEach(fn);
                }
            };

            const iris = new Set();
            for (const card of cards) {
                iris.add(card.uri);
                eachValue(card, v => { if (v.isUri) iris.add(v.value); });
            }
            if (iris.size === 0) return;

            const labels = await this._labels.resolveMany([...iris]);
            for (const card of cards) {
                const own = labels.get(card.uri);
                if (own) card.label = own;
                eachValue(card, v => {
                    const label = labels.get(v.value);
                    if (label) v.displayValue = label;
                });
            }
        },

        cardTurtleButtonLabel(card) {
            if (card.turtleLoading) return 'loading';
            return card.turtleOpen ? 'hide ttl' : 'ttl';
        },

        async toggleCardTurtle(card) {
            card.turtleOpen = !card.turtleOpen;
            if (!card.turtleOpen || card.turtleLoaded || card.turtleLoading) return;
            await this.loadCardTurtle(card);
        },

        async loadCardTurtle(card) {
            card.turtleLoading = true;
            card.turtleError = null;
            try {
                const describeQuery = this.buildDescribeQuery(card.uri);
                const rawTurtle = await this.runSparqlText(describeQuery, 'text/turtle');
                const store = await parseTurtle(rawTurtle);
                const quads = store.getQuads(null, null, null, null);
                card.turtleText = await formatTurtle(quads);
                card.turtleLoaded = true;
            } catch (e) {
                card.turtleError = e.message || String(e);
            } finally {
                card.turtleLoading = false;
            }
        },

        // --- Description ---

        buildDescription(hitCount, totalHits, totalSec) {
            const parts = [];
            const q = this.q.trim();
            if (q) {
                parts.push(`Search for <strong>\u201c${escapeHtml(q)}\u201d</strong>`);
            } else {
                parts.push('Showing <strong>all entities</strong>');
            }

            const filters = [];
            for (const [field, values] of Object.entries(this.selected)) {
                if (!values || values.length === 0) continue;
                const rendered = values.map(v => formatSelectedValue(field, v));
                if (rendered.length === 1) filters.push(rendered[0]);
                else filters.push(`(${rendered.join(' OR ')})`);
            }
            if (this.spatialBbox) {
                filters.push('bbox [' + this.spatialBbox.map(n => n.toFixed(1)).join(', ') + ']');
            }
            if (this.spatialPolygon) {
                filters.push('polygon [' + this.spatialPolygon.length + ' vertices]');
            }
            // Identifier and attribution terms are correlated under the hood — each pair
            // must hold on one nested node — but they read as ordinary filters.
            filters.push(...this.correlatedFilterSummary());
            if (filters.length > 0) {
                parts.push('filtered by ' + filters.join(' AND '));
            }
            if (this.sortField) {
                parts.push('sorted by ' + escapeHtml(this.sortLabel()));
            }

            let result = parts.join(' ') + ' \u2014 ';
            const total = totalHits || hitCount;
            if (totalHits != null && totalHits > hitCount) {
                result += `<strong>${hitCount.toLocaleString()}</strong> of <strong>${totalHits.toLocaleString()}</strong> results`;
            } else {
                result += `<strong>${total.toLocaleString()}</strong> results`;
            }
            const tp = this.totalPages();
            if (tp > 1) {
                result += ` \u2014 page <strong>${this.currentPage}</strong> of <strong>${tp.toLocaleString()}</strong>`;
            }
            if (totalSec != null) {
                result += ` in <strong>${totalSec.toFixed(2)}s</strong>`;
            }
            return result;
        },

        // --- Search execution ---

        async executeSearch() {
            // Abort any in-flight search before starting a new one
            if (this._abortController) this._abortController.abort();
            this._abortController = new AbortController();
            const signal = this._abortController.signal;

            // Clear cached hierarchy drill-down (counts change with search/filters)
            this.hierarchyChildren = {};
            this.hierarchyOpen = {};

            this.loading = true;
            this.showLoading = true;
            clearTimeout(this._loadingTimer);
            const loadStart = performance.now();
            this.error = null;

            try {
                // The buckets depend on the query and the filters, not on the page. Fetch
                // them only when that combination has actually changed.
                const facetKey = this.facetStateKey();
                const includeFacets = facetKey !== this._facetKey
                    || Object.keys(this.facets || {}).length === 0;
                const searchQuery = this.buildSearchQuery(includeFacets);
                const activeFacetFilters = Object.entries(this.selected)
                    .filter(([, v]) => v && v.length > 0).length;
                const activeFilters = activeFacetFilters
                    + Object.keys(this.correlatedFilters.identifierTerms || {}).length
                    + (this.correlatedFilters.attributionRole.trim() || this.correlatedFilters.attributionAgent.trim() ? 1 : 0);
                const searchTerm = this.q.trim() || '*';
                const searchLabel = searchTerm
                    + (activeFilters > 0 ? ` + ${activeFilters} filter${activeFilters > 1 ? 's' : ''}` : '');

                const cqlFilter = buildCqlFilter(
                    this.selected,
                    this.spatialBbox,
                    this.spatialPolygon,
                    this.fieldIRIs,
                    this.extraFilterClauses()
                );
                if (cqlFilter) {
                    this.logQuery('CQL Filter', cqlFilter, null, false);
                }

                let t0 = performance.now();
                const turtle = await this.runSparqlText(searchQuery, 'text/turtle', signal);
                const store = await parseTurtle(turtle);
                const searchMs = performance.now() - t0;
                this.logQuery(`Search: ${searchLabel}`, searchQuery, searchMs);

                const { hits, facets, totalHits } = this.parseGraphResults(store);
                this._lastTotalHits = totalHits ?? hits.length;
                if (includeFacets) {
                    this.facets = this.mergeFacets(facets);
                    this._facetKey = facetKey;
                }

                if (hits.length > 0) {
                    const uris = hits.map(h => h.uri);
                    const hitsByUri = Object.fromEntries(hits.map(h => [h.uri, h]));
                    const detailQuery = this.buildDetailQuery(uris);

                    t0 = performance.now();
                    const detailTurtle = await this.runSparqlText(detailQuery, 'text/turtle', signal);
                    const detailStore = await parseTurtle(detailTurtle);
                    const detailMs = performance.now() - t0;
                    this.logQuery(`Details: ${uris.length} entities`, detailQuery, detailMs);

                    this.cards = this.parseEntityDetails(detailStore, hitsByUri);
                    // Labels resolve in the background — the cards render immediately with
                    // short names and upgrade in place as the lookups return.
                    this.resolveCardLabels(this.cards);
                } else {
                    this.cards = [];
                }

                this.updateMap();
                await this.restoreHierarchySelections();
                const totalSec = (performance.now() - loadStart) / 1000;
                this.description = this.buildDescription(hits.length, totalHits, totalSec);
            } catch (e) {
                // Aborted requests are expected — silently ignore
                if (e.name === 'AbortError') return;

                console.error('executeSearch error:', e);
                if (e.message && (e.message.includes('Failed to fetch') || e.message.includes('NetworkError'))) {
                    this.error = `Cannot connect to Fuseki at ${this.endpoint}. Is the server running?`;
                } else {
                    this.error = `Query failed: ${e.message}`;
                }
                this.cards = [];
            }

            this.loading = false;
            const elapsed = performance.now() - loadStart;
            const remaining = Math.max(0, 400 - elapsed);
            this._loadingTimer = setTimeout(() => { this.showLoading = false; }, remaining);
        },

        // --- Map ---

        initMap() {
            if (this._map) return;
            const el = document.getElementById('search-map');
            if (!el) return;

            const osm = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                attribution: '&copy; OpenStreetMap', maxZoom: 19,
            });
            const topo = L.tileLayer('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png', {
                attribution: '&copy; OpenTopoMap', maxZoom: 17,
            });
            const satellite = L.tileLayer(
                'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
                { attribution: '&copy; Esri', maxZoom: 19 }
            );

            this._map = L.map(el, { layers: [osm], zoomControl: true })
                .setView([-25, 134], 4);

            L.control.layers(
                { 'OpenStreetMap': osm, 'Topographic': topo, 'Satellite': satellite },
                null, { position: 'topright' }
            ).addTo(this._map);

            this._mapLayers = L.layerGroup().addTo(this._map);
            this._setupBboxDrawHandlers();
            this._setupPolygonDrawHandlers();
            this.updateMapMarkers();
            if (this.spatialBbox) this.showBboxOverlay();
            if (this.spatialPolygon) this.showPolygonOverlay();
        },

        updateMap() {
            if (!Alpine.store('app').showMap) return;
            if (!this._map) {
                this.$nextTick(() => this.initMap());
                return;
            }
            this._map.invalidateSize();
            this.updateMapMarkers();
        },

        updateMapMarkers() {
            if (!this._mapLayers) return;
            this._mapLayers.clearLayers();
            this._mapMarkersByUri = {};
            const bounds = [];
            let mapped = 0;

            for (const card of this.cards) {
                const wktValues = card.properties.asWKT || [];
                for (const pv of wktValues) {
                    const geo = parseWktForLeaflet(pv.raw);
                    if (!geo) continue;

                    let layer;
                    if (geo.type === 'point') {
                        layer = L.circleMarker([geo.lat, geo.lon], {
                            radius: 7, fillColor: '#d4944c', color: '#d4944c',
                            weight: 2, opacity: 1, fillOpacity: 0.6,
                        });
                        bounds.push([geo.lat, geo.lon]);
                    } else if (geo.type === 'polygon') {
                        layer = L.polygon(geo.coords, {
                            fillColor: '#d4944c', color: '#d4944c',
                            weight: 2, opacity: 0.8, fillOpacity: 0.2,
                        });
                        bounds.push(...geo.coords);
                    }

                    if (layer) {
                        const popup = `<strong>${escapeHtml(card.label)}</strong>`;
                        layer.bindPopup(popup);
                        const uri = card.uri;
                        layer.on('click', () => this.highlightCard(uri));
                        this._mapLayers.addLayer(layer);
                        this._mapMarkersByUri[uri] = layer;
                        mapped++;
                    }
                }
            }

            this.mapMarkerCount = mapped;
            if (bounds.length > 0) {
                this._map.fitBounds(bounds, { padding: [30, 30], maxZoom: 10, animate: false });
            }
        },

        startResize(e) {
            e.preventDefault();
            const handle = e.currentTarget;
            const rightPanel = handle.nextElementSibling;
            const startX = e.clientX;
            const startWidth = rightPanel.offsetWidth;
            handle.classList.add('is-dragging');

            const onMove = (ev) => {
                const newWidth = Math.max(200, Math.min(window.innerWidth * 0.6, startWidth + (startX - ev.clientX)));
                rightPanel.style.width = newWidth + 'px';
                rightPanel.style.flex = 'none';
                if (this._map) this._map.invalidateSize();
            };
            const onUp = () => {
                handle.classList.remove('is-dragging');
                document.removeEventListener('mousemove', onMove);
                document.removeEventListener('mouseup', onUp);
                if (this._map) this._map.invalidateSize();
            };
            document.addEventListener('mousemove', onMove);
            document.addEventListener('mouseup', onUp);
        },

        startResizeLeft(e) {
            e.preventDefault();
            const handle = e.currentTarget;
            const leftPanel = handle.previousElementSibling;
            const startX = e.clientX;
            const startWidth = leftPanel.offsetWidth;
            handle.classList.add('is-dragging');

            const onMove = (ev) => {
                const newWidth = Math.max(150, Math.min(window.innerWidth * 0.4, startWidth + (ev.clientX - startX)));
                leftPanel.style.width = newWidth + 'px';
                leftPanel.style.flex = 'none';
            };
            const onUp = () => {
                handle.classList.remove('is-dragging');
                document.removeEventListener('mousemove', onMove);
                document.removeEventListener('mouseup', onUp);
            };
            document.addEventListener('mousemove', onMove);
            document.addEventListener('mouseup', onUp);
        },

        highlightCard(uri) {
            clearTimeout(this._highlightTimer);
            Alpine.store('app').highlightUri = uri;
            const el = document.querySelector(`[data-uri="${CSS.escape(uri)}"]`);
            if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            this._highlightTimer = setTimeout(() => { Alpine.store('app').highlightUri = null; }, 2000);
        },

        // --- Bbox drawing ---

        enableBboxDraw() {
            if (!this._map) return;
            this.cancelPolygonDraw();
            this.drawingBbox = true;
            this._map.dragging.disable();
            this._map.getContainer().style.cursor = 'crosshair';
        },

        cancelBboxDraw() {
            if (!this._map) return;
            this.drawingBbox = false;
            this._drawStart = null;
            if (this._drawRect) {
                this._map.removeLayer(this._drawRect);
                this._drawRect = null;
            }
            this._map.dragging.enable();
            this._map.getContainer().style.cursor = '';
        },

        clearBbox() {
            this.spatialBbox = null;
            if (this._bboxOverlay && this._map) {
                this._map.removeLayer(this._bboxOverlay);
                this._bboxOverlay = null;
            }
        },

        async clearBboxAndSearch() {
            this.clearBbox();
            this.pushUrl();
            await this.executeSearch();
        },

        showBboxOverlay() {
            if (!this._map || !this.spatialBbox) return;
            if (this._bboxOverlay) this._map.removeLayer(this._bboxOverlay);
            const [swLon, swLat, neLon, neLat] = this.spatialBbox;
            this._bboxOverlay = L.rectangle(
                [[swLat, swLon], [neLat, neLon]],
                { color: '#4db8a4', weight: 2, fillOpacity: 0.08, dashArray: '6 4', interactive: false }
            ).addTo(this._map);
        },

        _setupBboxDrawHandlers() {
            const map = this._map;
            const self = this;

            map.on('mousedown', function (e) {
                if (!self.drawingBbox) return;
                self._drawStart = e.latlng;
                if (self._drawRect) map.removeLayer(self._drawRect);
                self._drawRect = L.rectangle(
                    [e.latlng, e.latlng],
                    { color: '#4db8a4', weight: 2, fillOpacity: 0.12, dashArray: '6 4' }
                ).addTo(map);
            });

            map.on('mousemove', function (e) {
                if (!self.drawingBbox || !self._drawStart || !self._drawRect) return;
                self._drawRect.setBounds(L.latLngBounds(self._drawStart, e.latlng));
            });

            map.on('mouseup', async function (e) {
                if (!self.drawingBbox || !self._drawStart) return;
                const bounds = L.latLngBounds(self._drawStart, e.latlng);
                const sw = bounds.getSouthWest();
                const ne = bounds.getNorthEast();

                // Clean up drawing state
                self.drawingBbox = false;
                self._drawStart = null;
                if (self._drawRect) {
                    map.removeLayer(self._drawRect);
                    self._drawRect = null;
                }
                map.dragging.enable();
                map.getContainer().style.cursor = '';

                // Ignore tiny drags (accidental clicks)
                if (Math.abs(sw.lat - ne.lat) < 0.01 && Math.abs(sw.lng - ne.lng) < 0.01) return;

                // Clear polygon if present — only one spatial filter at a time
                self.clearPolygon();

                // Set bbox as [swLon, swLat, neLon, neLat] — CQL2 order
                self.spatialBbox = [
                    Math.round(sw.lng * 1000) / 1000,
                    Math.round(sw.lat * 1000) / 1000,
                    Math.round(ne.lng * 1000) / 1000,
                    Math.round(ne.lat * 1000) / 1000,
                ];
                self.showBboxOverlay();
                self.pushUrl();
                await self.executeSearch();
            });
        },

        // --- Polygon drawing ---

        enablePolygonDraw() {
            if (!this._map) return;
            this.cancelBboxDraw();
            this.drawingPolygon = true;
            this.polyPoints = [];
            this._polyMarkers = L.layerGroup().addTo(this._map);
            this._map.dragging.disable();
            this._map.doubleClickZoom.disable();
            this._map.getContainer().style.cursor = 'crosshair';
        },

        cancelPolygonDraw() {
            if (!this._map) return;
            this.drawingPolygon = false;
            this.polyPoints = [];
            if (this._polyMarkers) {
                this._map.removeLayer(this._polyMarkers);
                this._polyMarkers = null;
            }
            if (this._polyLine) {
                this._map.removeLayer(this._polyLine);
                this._polyLine = null;
            }
            this._map.dragging.enable();
            this._map.doubleClickZoom.enable();
            this._map.getContainer().style.cursor = '';
        },

        async finishPolygonDraw() {
            if (!this._map || !this.drawingPolygon) return;
            if (this.polyPoints.length < 3) return;

            // Build closed ring in CQL2 [lon, lat] order
            const ring = this.polyPoints.map(ll => [
                Math.round(ll.lng * 1000) / 1000,
                Math.round(ll.lat * 1000) / 1000,
            ]);
            ring.push([...ring[0]]);

            this.cancelPolygonDraw();
            this.clearBbox();

            this.spatialPolygon = ring;
            this.showPolygonOverlay();
            this.pushUrl();
            await this.executeSearch();
        },

        clearPolygon() {
            this.spatialPolygon = null;
            if (this._polyOverlay && this._map) {
                this._map.removeLayer(this._polyOverlay);
                this._polyOverlay = null;
            }
        },

        async clearPolygonAndSearch() {
            this.clearPolygon();
            this.pushUrl();
            await this.executeSearch();
        },

        showPolygonOverlay() {
            if (!this._map || !this.spatialPolygon) return;
            if (this._polyOverlay) this._map.removeLayer(this._polyOverlay);
            // spatialPolygon is [[lon,lat], ...] — Leaflet needs [lat,lon]
            const latlngs = this.spatialPolygon.map(c => [c[1], c[0]]);
            this._polyOverlay = L.polygon(latlngs, {
                color: '#4db8a4', weight: 2, fillOpacity: 0.08, dashArray: '6 4',
                interactive: false,
            }).addTo(this._map);
        },

        _setupPolygonDrawHandlers() {
            const map = this._map;
            const self = this;

            map.on('click', function (e) {
                if (!self.drawingPolygon) return;
                self.polyPoints.push(e.latlng);

                // Add vertex marker
                const marker = L.circleMarker(e.latlng, {
                    radius: 4, fillColor: '#4db8a4', color: '#4db8a4',
                    weight: 2, fillOpacity: 1,
                });
                if (self._polyMarkers) self._polyMarkers.addLayer(marker);

                // Update preview polyline
                if (self._polyLine) map.removeLayer(self._polyLine);
                if (self.polyPoints.length >= 2) {
                    self._polyLine = L.polyline(self.polyPoints, {
                        color: '#4db8a4', weight: 2, dashArray: '6 4',
                    }).addTo(map);
                }
            });
        },
    };
}

// ---------------------------------------------------------------------------
// Alpine.js component: Config page
// ---------------------------------------------------------------------------

function configApp() {
    return {
        config: null,
        configRaw: '',
        configView: 'parsed',
        error: null,

        async init() {
            try {
                this.config = await loadConfig();
                const resp = await fetch(`${CONFIG_PATH}?t=${Date.now()}`);
                this.configRaw = await resp.text();
            } catch (e) {
                this.error = `Failed to load config: ${e.message}`;
            }
        },
    };
}

// ---------------------------------------------------------------------------
// Alpine.js component: Stats page
// ---------------------------------------------------------------------------

function statsApp() {
    return {
        stats: null,
        error: null,
        loading: true,
        nameMode: 'short',

        async init() {
            try {
                const config = await loadConfig();
                const endpoint = config.endpoint;
                const facetFields = config.facetFields;
                const fieldIRIs = config.fieldIRIs;
                const t0 = performance.now();

                // 1. Total entities + facet counts in one query
                const facetRequests = facetFields.map(f => {
                    const iri = fieldIRIs[f] || f;
                    const ranges = facetRangeSpec(f, config.fieldInfo?.[f]);
                    if (ranges) return { field: iri, ranges };
                    return iri;
                });
                const facetFieldsJson = JSON.stringify(facetRequests);
                const statsQuery = `${SPARQL_PREFIXES}
SELECT ?entity ?score ?totalHits ?field ?value ?low ?high ?count
WHERE {
    { (?hit ?entity ?score ?rank ?totalHits) luc:query ('default' 'default' '*' '' '' 0 0) }
    UNION
    { (?field ?value ?low ?high ?count) luc:facet ('default' 'default' '*' ${sparqlQuote(facetFieldsJson)} '' 0 0) }
}`;
                const statsData = await this.runSparql(endpoint, statsQuery);
                const statsMs = performance.now() - t0;

                // Parse union results
                let totalHits = 0;
                const facets = {};
                for (const row of (statsData.results?.bindings || [])) {
                    if (row.totalHits && totalHits === 0) {
                        totalHits = parseInt(row.totalHits.value, 10);
                    }
                    if (row.field) {
                        const f = resolveFieldName(row.field.value, config.fieldIRIs);
                        if (!facets[f]) facets[f] = [];
                        let rawVal = null;
                        let label = null;
                        if (row.value) {
                            rawVal = row.value.value;
                            label = row.value.type === 'uri' ? shortName(rawVal) : rawVal;
                        } else if (row.low || row.high) {
                            const l = row.low ? row.low.value : '*';
                            const h = row.high ? row.high.value : '*';
                            rawVal = `__RANGE__${row.low ? row.low.value : ''}|${row.high ? row.high.value : ''}`;
                            label = `${l} to ${h}`;
                        } else {
                            rawVal = '__NULL__';
                            label = '(empty)';
                        }
                        facets[f].push({
                            value: rawVal,
                            label: label,
                            count: parseInt(row.count.value, 10),
                        });
                    }
                }
                // Sort each facet by count desc
                for (const f of Object.keys(facets)) {
                    facets[f].sort((a, b) => b.count - a.count);
                }

                // 2. Triple count
                const t1 = performance.now();
                const countQuery = `SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }`;
                const countData = await this.runSparql(endpoint, countQuery);
                const countMs = performance.now() - t1;
                const tripleCount = parseInt(
                    countData.results?.bindings?.[0]?.count?.value || '0', 10
                );

                const totalMs = performance.now() - t0;

                this.stats = {
                    totalEntities: totalHits,
                    totalTriples: tripleCount,
                    shapes: config.shapes.length,
                    facetableFields: facetFields.length,
                    facets,
                    facetFields,
                    fieldIRIs: config.fieldIRIs,
                    statsQueryMs: statsMs,
                    countQueryMs: countMs,
                    totalMs,
                };
            } catch (e) {
                if (e.name === 'TypeError' || (e.message && (e.message.includes('Failed to fetch') || e.message.includes('NetworkError')))) {
                    this.error = `Cannot connect to Fuseki. Is the server running?`;
                } else {
                    this.error = `Query failed: ${e.message}`;
                }
            }
            this.loading = false;
        },

        async runSparql(endpoint, query) {
            const resp = await fetch(endpoint, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/sparql-query',
                    'Accept': 'application/sparql-results+json',
                },
                body: query,
            });
            return parseSparqlJsonResponse(resp);
        },
    };
}
