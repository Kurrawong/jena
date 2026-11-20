<!-- Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0 -->

# Recipes demo

A small dataset and index configuration for SHACL-mode search: ten made-up recipes, six
reviewers and a CSV of reviews, indexed by [config.ttl](config.ttl). It is small enough
that every result and facet count can be checked by eye. The 22 queries in
[queries/](queries/) each show one feature, and [check.sh](check.sh) checks the row count
of every query against [expected-rows.tsv](expected-rows.tsv).

The published image `ghcr.io/kurrawong/fuseki-lucene-shacl-demo` serves this directory;
see [deploy/README.md](deploy/README.md).

## Run it

With Docker:

```bash
docker run --rm -p 3030:3030 ghcr.io/kurrawong/fuseki-lucene-shacl-demo:latest
```

```bash
demo/check.sh http://localhost:3030
```

From a source build, with Java 21 and [Task](https://taskfile.dev), run these in `demo/`:

```bash
task build
```

```bash
task
```

`task build` builds the Fuseki server jar. `task` starts Fuseki on port 3040 in the
background and runs `check.sh`, which prints one line per query and ends with
`22 queries, all as expected`. The SPARQL endpoint is
`http://localhost:3040/kitchen/query`, read-only. `task stop` stops the server, and
`task query` prints every query's results.

Fuseki has to start from `demo/`. The CSV path in `config.ttl` is resolved against the
working directory, while the Turtle path is resolved against the file.

## The dataset

| | |
|---|---|
| Entities | 10 recipes (`kt:Recipe`) and 6 reviewers (`kt:Reviewer`), indexed by two shapes into one index, `default` |
| Text fields | `name`, fed by `rdfs:label`, `schema:name` and `dcterms:title` on recipes and `schema:name` on reviewers; `summary`; `codeText`, an edge n-gram field over the recipe code |
| Keyword facets | `entityType`, `course`, `country`, `region`, `diet`, `contributor` (fed by `kt:author` and `kt:testedBy`), `code` |
| Numeric and date fields | `prepMinutes` (INT, range facets, sort), `publishedOn` (TEMPORAL, range filter, sort) |
| Hierarchy | `region_country`: three regions over seven countries. A recipe names its country, and the region comes from the country through the sequence path `( kt:country kt:inRegion )` |
| Nested, from the graph | ingredient records, each an item and a weight in grams, through blank nodes on `kt:ingredient`; hierarchy `ingredient_ingredientGrams` |
| Nested, from a CSV file | 25 reviews in [data/reviews.csv](data/reviews.csv), never loaded into the graph, joined on the recipe IRI; three-level hierarchy `reviewer_stars_reviewMonth`. Unstored, so they filter and count but do not project |
| Spatial | each country has one `geo:wktLiteral` point at its capital. `location` (LatLonField) reaches it from the recipe through `( kt:country geo:hasGeometry geo:asWKT )` |

The data is in [data/kitchen.ttl](data/kitchen.ttl). Labels for the field IRIs are in the
same file, so a client labels fields the same way it labels values.

## The queries

| Query | Rows | What it shows |
|---|---|---|
| [01-search-bm25](queries/01-search-bm25.rq) | 2 | BM25 search over the default-search fields, with `?rank` and `?totalHits` |
| [02-facets-flat](queries/02-facets-flat.rq) | 10 | Flat facet counts for course, diet and contributor over the whole index |
| [03-facets-filtered](queries/03-facets-filtered.rq) | 5 | The same facets with a course filter applied |
| [04-hierarchy-top](queries/04-hierarchy-top.rq) | 3 | The top level of `region_country`: regions. The facet target is the dimension name, not a field IRI |
| [05-hierarchy-drill](queries/05-hierarchy-drill.rq) | 3 | An `=` filter on Asia returns its countries: Japan 2, India 1, Thailand 1 |
| [06-date-range](queries/06-date-range.rq) | 6 | `between` on a TEMPORAL field, sorted newest first |
| [07-range-facets](queries/07-range-facets.rq) | 4 | Range buckets over `prepMinutes`; a `null` bound is open-ended |
| [08-typeahead-ngram](queries/08-typeahead-ngram.rq) | 2 | `text_query` on the n-gram field: `2019` matches a middle segment of the code |
| [09-fanin-contributor](queries/09-fanin-contributor.rq) | 4 | One facet fed by two predicates: each person's authored plus tested recipes |
| [10-keyword-iri-filter](queries/10-keyword-iri-filter.rq) | 3 | Exact equality on IRI-valued keyword fields, as a facet selection sends it |
| [11-match](queries/11-match.rq) | 2 | `luc:match`: which stored field matched each hit, and its value |
| [12-nested-match-reviews](queries/12-nested-match-reviews.rq) | 0 | `luc:nestedMatch` on unstored review fields projects nothing, although the filter on them works |
| [13-nested-correlation](queries/13-nested-correlation.rq) | 3 | "Priya gave 5 stars" must hold on one review. Tonkotsu Ramen and Mole Poblano have a Priya review and a 5-star review, but not one 5-star review by Priya, so they are excluded |
| [14-sort-and-page](queries/14-sort-and-page.rq) | 5 | Sorted by prep time, second page of five; `?rank` continues from 5 and `?totalHits` stays at 10 |
| [15-nested-hierarchy-correlated](queries/15-nested-hierarchy-correlated.rq) | 2 | Drilling `reviewer_stars_reviewMonth` on Tonkotsu Ramen: Priya gave 2 and Noor gave 5, and neither is offered the other's rating |
| [16-one-field-many-paths-many-shapes](queries/16-one-field-many-paths-many-shapes.rq) | 4 | One `name` field finds names carried by three predicates across two shapes, and reports which predicate carried each |
| [17-three-level-drilldown](queries/17-three-level-drilldown.rq) | 13 | Three levels of one hierarchy: six reviewers, Priya's four ratings, and the three months of her 5-star reviews |
| [18-ingredient-quantity-correlated](queries/18-ingredient-quantity-correlated.rq) | 2 | "100 g or more of chilli" must hold on one ingredient record: only Mole Poblano. `luc:nestedMatch` projects the record |
| [19-facets-sideways-and-drilldown](queries/19-facets-sideways-and-drilldown.rq) | 4 | The course facet with Main selected: sideways counts keep all three courses, drill-down counts only Main |
| [20-spatial-within-bbox](queries/20-spatial-within-bbox.rq) | 4 | `s_within` a box over western Europe: the French and Italian recipes |
| [21-spatial-dwithin](queries/21-spatial-dwithin.rq) | 2 | `s_dwithin` 5,000 km of Mexico City: the Mexican recipe and the Peruvian one (Lima is about 4,200 km away) |
| [22-config-fields](queries/22-config-fields.rq) | 18 | `luc:config`: every field's name and type, read from the index through the query endpoint |

Query 12 returns no rows on purpose: set `idx:stored true` on the three review fields in
`config.ttl` and it returns them.

The `region_country` hierarchy has one country per recipe, so its counts equal those of
filtering by region and counting country. Queries 15 and 17 use the review hierarchy,
whose levels correlate per review row, to show drilling that two flat facets cannot give.

## Further reading

- [docs/02-sparql-api.md](../docs/02-sparql-api.md): `luc:query`, `luc:facet`,
  `luc:match`, `luc:nestedMatch` and `luc:config`
- [docs/03-configuration.md](../docs/03-configuration.md): the configuration vocabulary
- [docs/09-spatial.md](../docs/09-spatial.md): spatial fields and operators
