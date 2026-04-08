# SPARQL API Reference

All property functions use the `luc:` namespace (`urn:jena:lucene:index#`).

> **Note:** The upstream Jena `text:query` property function is unchanged and still available for classic mode (`text:entityMap`). See the [Apache Jena documentation](https://jena.apache.org/documentation/query/text-query.html) for its syntax. This reference covers only the SHACL mode property functions.

---

## luc:query — Text Search with Filters

Supports field-scoped queries, CQL2-JSON filter arguments, sort pushdown, and faceted navigation.

### Syntax

```
(?hit ?entity ?score ?match ?totalHits ?graph) luc:query (fieldSpec queryString filter? sort? limit? highlight?)
```

### Arguments (positional, left to right)

| Position | Type | Required | Description |
|----------|------|----------|-------------|
| fieldSpec | String literal | No | Which indexed fields to search (see below). Default: `"default"` |
| queryString | String literal | Yes | Lucene query string |
| filter | JSON object literal | No | CQL filter: `'{"op":"=","args":[{"property":"urn:jena:lucene:field#category"},"Technology"]}'` |
| sort | JSON literal | No | Sort spec: `'{"field":"urn:jena:lucene:field#year","order":"desc"}'` |
| limit | Integer | No | Max results. Negative = no limit |
| highlight | String literal | No | Highlight options: `"highlight:m:3\|z:128\|s:→\|e:←\|f:÷"` |

### Field specification

The `fieldSpec` argument controls which Lucene fields are searched. It accepts either `"default"` or a JSON array of field IRIs.

| Value | Meaning |
|-------|---------|
| `"default"` | Search all fields marked `idx:defaultSearch true` in the index configuration |
| `'["urn:jena:lucene:field#title"]'` | Search a single specific field (JSON array with one IRI) |
| `'["urn:jena:lucene:field#title","urn:jena:lucene:field#description"]'` | Search multiple specific fields (JSON array of IRIs) |
| *(omitted)* | Same as `"default"` |

Field IRIs correspond to the named resource IRIs in the SHACL index configuration. They are validated at query time — an unknown IRI produces an error.

### Return bindings

| Variable | Required | Type | Description |
|----------|----------|------|-------------|
| ?hit | Yes | Blank node | Query-scoped hit identifier for joining with `luc:match` |
| ?entity | No | IRI | Matched entity |
| ?score | No | float | Lucene relevance score |
| ?match | No | IRI or literal | Stored value for the first matched field. KEYWORD fields return an IRI, TEXT fields return a string literal, numeric fields return typed literals |
| ?totalHits | No | xsd:integer | Total matching documents (same value on every row) |
| ?graph | No | IRI | Named graph of the match |

The `?hit` binding is a blank node (e.g. `_:hit0`, `_:hit1`) scoped to the query execution. It serves as a join key with `luc:match` to retrieve per-field match details. Each hit gets a unique blank node.

The `?totalHits` binding returns the total number of documents matching the query and filters, regardless of the `limit` parameter. This is useful for displaying "Showing X of Y results" in search UIs. The value is computed efficiently using `IndexSearcher.count()` and is only evaluated when the variable is present in the subject.

The `?match` binding returns the stored value from the first matched field. For full per-field match details (which fields matched, their values), use `luc:match`.

> **Note:** All fields must be defined as named resources (with IRIs) in the SHACL index configuration. Blank node field definitions are not supported.

### Examples

```sparql
PREFIX luc: <urn:jena:lucene:index#>
PREFIX field: <urn:jena:lucene:field#>

# Simple search (all default fields)
(?hit ?entity ?score) luc:query ("machine learning") .

# Search with explicit "default"
(?hit ?entity ?score) luc:query ("default" "machine learning") .

# Search a specific field (JSON array with one IRI)
(?hit ?entity ?score) luc:query ('["urn:jena:lucene:field#title"]' "machine learning") .

# Search multiple fields (JSON array of IRIs)
(?hit ?entity ?score) luc:query ('["urn:jena:lucene:field#title", "urn:jena:lucene:field#description"]' "machine learning") .

# Search with limit
(?hit ?entity ?score) luc:query ('["urn:jena:lucene:field#title"]' "machine learning" 20) .

# Search with total hit count
(?hit ?entity ?score ?match ?totalHits) luc:query ("machine learning" 20) .

# Search with CQL filter (only Technology books)
(?hit ?entity ?score) luc:query ("default" "learning" '{"op":"=","args":[{"property":"urn:jena:lucene:field#category"},"Technology"]}' 20) .

# Search with filter and total hit count
(?hit ?entity ?score ?_match ?totalHits) luc:query ("default" "learning" '{"op":"=","args":[{"property":"urn:jena:lucene:field#category"},"Technology"]}' 20) .

# Search with sort (by field IRI)
(?hit ?entity ?score) luc:query ("default" "learning" '{"field":"urn:jena:lucene:field#year","order":"desc"}' 10) .

# Search with per-field match details (join with luc:match)
(?hit ?entity ?score) luc:query ('["urn:jena:lucene:field#title"]' "machine learning") .
(?hit ?field ?value) luc:match () .
```

### Filter JSON format (CQL2-JSON)

Filters use CQL2-JSON syntax. The `property` value is a field IRI:

```json
{"op": "=", "args": [{"property": "urn:jena:lucene:field#category"}, "Technology"]}
```

- `"="` — exact match on KEYWORD fields
- `"and"` / `"or"` — boolean combinators
- `"s_intersects"` — spatial intersection (LATLON fields)
- Numeric comparisons (`">"`, `"<"`, `">="`, `"<="`) for INT/LONG/DOUBLE fields

---

## luc:match — Per-Hit Field Match Details

Returns per-field match information for each hit from a preceding `luc:query`. Joins with `luc:query` via the shared `?hit` blank node.

### Syntax

```
(?hit ?field ?value) luc:match ()
```

The object is always an empty list `()`.

### Return bindings

| Variable | Required | Type | Description |
|----------|----------|------|-------------|
| ?hit | Yes | Blank node | Join key — must match `?hit` from `luc:query` |
| ?field | No | IRI | Field IRI identifying which field matched (e.g. `urn:jena:lucene:field#title`) |
| ?value | No | IRI or literal | Stored value for the matched field. KEYWORD fields return IRIs, TEXT fields return string literals, numeric fields return typed literals |

`luc:match` produces one row per matched field per hit. If a document matched on two fields (e.g. both `title` and `description`), two rows are returned for that hit.

### How it works

`luc:match` uses Lucene's `NamedMatches` API to determine which fields contributed to each hit. During the initial `luc:query` search, each per-field sub-query is wrapped with `NamedMatches.wrapQuery(fieldIri, fieldQuery)`. After search, `Weight.matches()` is called per hit to extract field-level match details without re-scoring.

### Requirements

- `luc:match` must appear in the same SPARQL query as a `luc:query` — it reads from `luc:query`'s shared `SearchExecution` state
- Without a preceding `luc:query`, `luc:match` returns no results
- The `?hit` variable must be shared between `luc:query` and `luc:match` for the join to work

### Examples

```sparql
PREFIX luc: <urn:jena:lucene:index#>

# Get field match details for each hit
SELECT ?entity ?field ?value WHERE {
    (?hit ?entity ?score) luc:query ('["urn:jena:lucene:field#title"]' "learning") .
    (?hit ?field ?value) luc:match () .
}

# Multi-field search with field attribution
SELECT ?entity ?score ?field ?value WHERE {
    (?hit ?entity ?score) luc:query ('["urn:jena:lucene:field#title", "urn:jena:lucene:field#description"]' "machine learning") .
    (?hit ?field ?value) luc:match () .
}

# OPTIONAL match — hits still returned even without field details
SELECT ?entity ?score ?field ?value WHERE {
    (?hit ?entity ?score) luc:query ("default" "learning") .
    OPTIONAL { (?hit ?field ?value) luc:match () . }
}
```

---

## luc:facet — Facet Counts
### Syntax

```
(?field ?value ?count) luc:facet (fieldSpec queryString facetFields filter? maxValues? minCount?)
(?field ?value ?low ?high ?count) luc:facet (fieldSpec queryString facetFields filter? maxValues? minCount?)
```

### Arguments (positional, left to right)

| Position | Type | Required | Description |
|----------|------|----------|-------------|
| fieldSpec | String literal | No | Which indexed fields to scope the text query (same as luc:query). Default: `"default"` |
| queryString | String literal | Yes | Lucene query string |
| facetFields | JSON array literal | Yes | Field IRIs and/or range specs to facet on (see below) |
| filter | JSON object literal | No | CQL filter (same format as luc:query) |
| maxValues | Integer | No | Max facet values per field. Default: 10. `0` = all values |
| minCount | Integer | No | Exclude values with count below this. Default: 0 |

### Facet fields array

The `facetFields` JSON array accepts two element types, which can be mixed freely:

| Element type | Format | Use case |
|-------------|--------|----------|
| **String** | `"urn:jena:lucene:field#category"` | Flat facet on a KEYWORD field, or hierarchical facet on a hierarchy level field |
| **Range object** | `{"field": "urn:jena:lucene:field#year", "ranges": [2020, 2022, 2024, 2026]}` | Bucketed counts on a numeric field (INT, LONG, DOUBLE) |

**Range object properties:**

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `field` | String | Yes | Field IRI of a numeric field (INT, LONG, or DOUBLE) |
| `ranges` | Array of numbers/nulls | Yes | Bucket boundaries (bin edges). Adjacent pairs define `[low, high)` buckets |

**Boundary semantics:** Boundaries are contiguous, lower-inclusive, upper-exclusive (`[low, high)`). For example, `[2020, 2022, 2024, 2026]` produces three buckets: `[2020, 2022)`, `[2022, 2024)`, `[2024, 2026)`.

**Open-ended ranges:** Use `null` at the start or end to create unbounded buckets:
- `[null, 2020, 2024, null]` → `(-∞, 2020)`, `[2020, 2024)`, `[2024, +∞)`

**Wildcard behaviour:** The `"*"` wildcard expands to all flat and hierarchical facetable fields. It does not include numeric fields, since range facets require explicit boundaries.

**Validation:**

- Requesting a numeric field (INT/LONG/DOUBLE) as a bare string produces an error — numeric fields require range boundaries
- Range objects targeting non-numeric fields produce an error
- Non-null boundaries must be strictly increasing
- `null` is only valid at the start and/or end of the boundary array

### Subject forms

Use the subject form that matches the request:

- `(?field ?value ?count)` for flat and hierarchical facets only
- `(?field ?value ?low ?high ?count)` for any request that includes a range object

Rules:

- the legacy 3-slot form remains valid for flat and hierarchical facets
- the 5-slot form is required for range-only and mixed flat+range requests
- the 5-slot form also works for flat-only requests

### Return bindings

| Variable | Required | Type | Description |
|----------|----------|------|-------------|
| ?field | Yes | IRI | Field IRI identifying the facet field |
| ?value | No | IRI or literal | Flat or hierarchical facet value. For KEYWORD fields, values that look like URIs are returned as IRIs; otherwise as string literals |
| ?low | No | typed numeric literal | Lower bound of a range bucket. Unbound for flat/hierarchical facets and open-ended low bounds |
| ?high | No | typed numeric literal | Upper bound of a range bucket. Unbound for flat/hierarchical facets and open-ended high bounds |
| ?count | No | xsd:long | Number of matching documents |

### Examples

```sparql
PREFIX luc: <urn:jena:lucene:index#>

# Basic facet counts
(?f ?v ?c) luc:facet ("default" "learning" '["urn:jena:lucene:field#category"]' 10) .

# Multiple facet fields
(?f ?v ?c) luc:facet ("default" "learning" '["urn:jena:lucene:field#category", "urn:jena:lucene:field#author"]' 10) .

# With CQL filter applied
(?f ?v ?c) luc:facet ("default" "learning" '["urn:jena:lucene:field#author"]' '{"op":"=","args":[{"property":"urn:jena:lucene:field#category"},"Technology"]}' 10) .

# Return all facet values (maxValues=0)
(?f ?v ?c) luc:facet ("default" "learning" '["urn:jena:lucene:field#category"]' 0) .

# With minCount threshold (exclude rare values)
(?f ?v ?c) luc:facet ("default" "learning" '["urn:jena:lucene:field#author"]' 10 2) .

# Combine maxValues=0 with minCount
(?f ?v ?c) luc:facet ("default" "learning" '["urn:jena:lucene:field#author"]' 0 2) .
```

### Range Facets

Range facets provide bucketed counts for numeric fields. Mark the field `idx:facetable true` in the configuration, then include a range object in the `facetFields` array.

```sparql
PREFIX luc: <urn:jena:lucene:index#>

# Range facets on year field
(?f ?v ?low ?high ?c) luc:facet ("default" "learning"
    '[{"field":"urn:jena:lucene:field#year", "ranges":[2020, 2022, 2024, 2026]}]'
    10) .
```

Returns:

| ?field | ?value | ?low | ?high | ?count |
|--------|--------|------|-------|--------|
| `<urn:jena:lucene:field#year>` | — | `"2020"^^xsd:integer` | `"2022"^^xsd:integer` | 35 |
| `<urn:jena:lucene:field#year>` | — | `"2022"^^xsd:integer` | `"2024"^^xsd:integer` | 28 |
| `<urn:jena:lucene:field#year>` | — | `"2024"^^xsd:integer` | `"2026"^^xsd:integer` | 18 |

**Mixed flat and range facets** in a single call:

```sparql
# Category facets + year range buckets in one request
(?f ?v ?low ?high ?c) luc:facet ("default" "learning"
    '["urn:jena:lucene:field#category", {"field":"urn:jena:lucene:field#year", "ranges":[2020, 2022, 2024, 2026]}]'
    10) .
```

Flat rows bind `?value` and leave `?low` / `?high` unbound. Range rows bind `?low` / `?high` and leave `?value` unbound.

**Open-ended ranges** with `null` boundaries:

```sparql
# Include "before 2020" and "2026+" buckets
(?f ?v ?low ?high ?c) luc:facet ("default" "learning"
    '[{"field":"urn:jena:lucene:field#year", "ranges":[null, 2020, 2022, 2024, 2026, null]}]'
    10) .
```

Open-ended buckets leave the missing bound unbound:

- `(-∞, 2020)` => `?low` unbound, `?high = "2020"^^xsd:integer`
- `[2026, +∞)` => `?low = "2026"^^xsd:integer`, `?high` unbound

**With CQL filters:**

```sparql
# Year ranges for Technology books only
(?f ?v ?low ?high ?c) luc:facet ("default" "learning"
    '[{"field":"urn:jena:lucene:field#year", "ranges":[2020, 2022, 2024, 2026]}]'
    '{"op":"=","args":[{"property":"urn:jena:lucene:field#category"},"Technology"]}'
    10) .
```

**Date fields:** Lucene has no native date type. Store dates as epoch milliseconds in a LONG field (`idx:fieldType idx:LongField ; idx:facetable true`). Range boundaries are then epoch millis values:

```sparql
# Date range facets (epoch millis for 2020-01-01, 2022-01-01, 2024-01-01, 2026-01-01)
(?f ?v ?low ?high ?c) luc:facet ("default" "*"
    '[{"field":"urn:jena:lucene:field#publishDate", "ranges":[1577836800000, 1640995200000, 1704067200000, 1767225600000]}]'
    10) .
```

### Hierarchical vs Range Facets for Dates

Dates can be faceted using either approach, serving different navigation patterns:

| Approach | Fields | Facet type | Navigation pattern |
|----------|--------|------------|-------------------|
| **Hierarchical** | Separate KEYWORD fields per level (e.g. `field:year`, `field:month`, `field:day`) | Taxonomy drill-down | "Drill into 2024 → March → 15th" |
| **Range** | Single LONG field with epoch millis | Numeric bucketing | "How many per 2-year band?" |

Both approaches can coexist on the same underlying data — they use different Lucene fields and different facet mechanisms. A hierarchical date facet uses KEYWORD fields indexed via the taxonomy, while a range date facet uses a single numeric LONG field with numeric docvalues. There is no conflict because they are separate fields, even if they derive from the same RDF property.

You cannot use both hierarchical and range faceting on the *same* Lucene field — hierarchical requires KEYWORD/taxonomy data while range requires numeric doc values.

### Hierarchy Drill-Down

When hierarchical facets are configured (see [Configuration — Hierarchical Facets](03-configuration.md#hierarchical-facets)), drill-down is triggered by combining a CQL `=` filter on a parent level field with a `facetFields` request on the child level field.

**Top-level counts** — request facets on a hierarchy level field IRI. It auto-resolves to the dimension:

```sparql
PREFIX luc: <urn:jena:lucene:index#>

# Get top-level state counts (returns state values with counts)
SELECT ?field ?value ?count WHERE {
    (?field ?value ?count) luc:facet ("default" "*"
        '["urn:jena:lucene:field#state"]' 10) .
}
```

**Drill-down into children** — filter on the parent level and request facets on the child level:

```sparql
PREFIX luc: <urn:jena:lucene:index#>

# Get commodity counts within WA (drill-down)
SELECT ?field ?value ?count WHERE {
    (?field ?value ?count) luc:facet ("default" "*"
        '["urn:jena:lucene:field#commodity"]'
        '{"op":"=","args":[{"property":"urn:jena:lucene:field#state"},"http://example.org/mining/state/WA"]}'
        10) .
}
```

The system auto-detects that `field#state` and `field#commodity` belong to the same hierarchy. The CQL `=` filter on `field#state` becomes a taxonomy drill-down path, and the facet results return child-level (commodity) values scoped to that parent.

**Combined with other filters** — hierarchy drill-down works alongside regular CQL filters:

```sparql
PREFIX luc: <urn:jena:lucene:index#>

# Commodity counts within WA, restricted to Active status
SELECT ?field ?value ?count WHERE {
    (?field ?value ?count) luc:facet ("default" "*"
        '["urn:jena:lucene:field#commodity"]'
        '{"op":"and","args":[
            {"op":"=","args":[{"property":"urn:jena:lucene:field#state"},"http://example.org/mining/state/WA"]},
            {"op":"=","args":[{"property":"urn:jena:lucene:field#status"},"http://example.org/mining/status/Active"]}
        ]}'
        10) .
}
```

---

## Combining Search and Facets

Search hits and facet counts are two fundamentally different result shapes — hits are entities with scores, facets are (field, value, count) aggregations. SPARQL's tabular result model requires care when combining them.

### Recommended: Separate queries

Use one query for hits, another for facets. Each returns a clean result shape with no wasted rows.

```sparql
PREFIX luc: <urn:jena:lucene:index#>

# Query 1: search results
SELECT ?entity ?score WHERE {
    (?hit ?entity ?score) luc:query ("learning") .
}

# Query 2: facet counts
SELECT ?field ?value ?count WHERE {
    (?field ?value ?count) luc:facet ("default" "learning"
        '["urn:jena:lucene:field#category", "urn:jena:lucene:field#author"]' 10) .
}
```

This is the pattern used by search UIs (Elasticsearch, Solr) — one request for results, one for facets. Each result set has exactly the rows the consumer needs.

### Alternative: UNION in a single query

If a single SPARQL request is preferred, use `UNION` to return both result sets without a cartesian product:

```sparql
PREFIX luc: <urn:jena:lucene:index#>

SELECT ?entity ?score ?totalHits ?field ?value ?count WHERE {
    { (?hit ?entity ?score ?_match ?totalHits) luc:query ("default" "learning" 10) . }
    UNION
    { (?field ?value ?count) luc:facet ("default" "learning"
        '["urn:jena:lucene:field#category"]' 10) . }
}
```

This returns N + M rows (not N × M). Hit rows have facet variables unbound; facet rows have `?entity`, `?score`, `?totalHits` unbound. The consumer splits results by checking which columns are present. `?totalHits` appears on every hit row with the same value — read it from the first row. Both PFs share a single Lucene execution via `SearchExecution` (see below).

If the facet branch requests range buckets, include `?low` and `?high` in the projection and use the 5-slot `luc:facet` subject form in that branch.

### Avoid: Combined BGP (cartesian product)

Placing both PFs in the same basic graph pattern produces a cartesian product:

```sparql
# WARNING: produces N × M rows
SELECT ?entity ?score ?field ?value ?count WHERE {
    (?hit ?entity ?score) luc:query ("learning") .
    (?field ?value ?count) luc:facet ("default" "learning"
        '["urn:jena:lucene:field#category"]' 10) .
}
```

With 100 hits and 10 facet values, this returns 1,000 rows — every hit paired with every facet value. The shared execution avoids redundant Lucene work, but the result set still explodes. This is a consequence of SPARQL's join semantics: two patterns with no shared variables produce a cross join.

---

## Shared Execution

When `luc:query`, `luc:facet`, and `luc:match` appear in the same SPARQL query (whether in a BGP, UNION, or subquery) with matching search parameters, they share a single Lucene execution internally. One Lucene query, one index reader snapshot, consistent results.

The shared search key is based on normalised search parameters — search field IRIs, query string, filters, and sort. Facet request details such as requested fields, range boundaries, `maxValues`, and `minCount` are applied after the shared search collection step.

This optimisation is transparent. It reduces Lucene index access but does not change SPARQL result semantics — the cartesian product concern (above) is a SPARQL join issue, not a Lucene execution issue.

---

## Lucene Query Syntax

The query string argument in `luc:query` uses the standard Lucene query parser. Key syntax:

| Syntax | Meaning | Example |
|--------|---------|---------|
| `word1 word2` | OR — matches either term | `"machine learning"` matches "machine" OR "learning" |
| `word1 AND word2` | AND — matches both terms | `"machine AND learning"` |
| `"exact phrase"` | Phrase match | `"\"machine learning\""` (escaped quotes in SPARQL string) |
| `field:value` | Field-scoped query | `"title:learning"` |
| `wild*` | Wildcard | `"learn*"` matches "learning", "learned", etc. |
| `~` | Fuzzy match | `"learninh~"` matches "learning" |
| `-term` | Exclusion | `"learning -neural"` |

These are Lucene query parser conventions — not specific to Jena. Refer to the [Lucene Classic Query Parser documentation](https://lucene.apache.org/core/10_3_1/queryparser/org/apache/lucene/queryparser/classic/package-summary.html) for full syntax.

---

## Java API

For programmatic access via `ShaclTextIndexLucene`. The Java API accepts Lucene field names directly (these are the `idx:fieldName` values from the configuration):

```java
// Open facets (all documents)
Map<String, List<FacetBucket>> counts =
    textIndex.getFacetCounts(Arrays.asList("category"), 10);

// Filtered by query
Map<String, List<FacetBucket>> filtered =
    textIndex.getFacetCounts("machine learning", Arrays.asList("category"), 10);

// With minCount
Map<String, List<FacetBucket>> rare =
    textIndex.getFacetCounts("learning", Arrays.asList("author"), 10, 2);

// With search fields scoping
Map<String, List<FacetBucket>> scoped =
    textIndex.getFacetCounts("learning", List.of("title"),
        Arrays.asList("author"), 10);

// Text query with field scoping
List<TextHit> hits =
    textIndex.queryByFields(List.of("title"), "learning", null, null, 20, null);

// Count total matching documents (efficient — uses IndexSearcher.count())
long total = textIndex.countQueryWithCql("learning", null, null);
```

For flat facets, programmatic results expose a field value plus count. For range facets, programmatic results follow the same model as SPARQL: explicit typed low/high bounds plus count, not a display label string.

### Checking Facet Support

```java
if (textIndex.isFacetingEnabled()) {
    // Facet methods are available
}
```

`isFacetingEnabled()` returns `true` when the index has facetable fields configured, including numeric fields marked `idx:facetable true`.
