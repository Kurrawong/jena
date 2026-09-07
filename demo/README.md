# demo-mini

Ten made-up recipes and six reviewers, two SHACL shapes, and every feature of this fork's
entity-per-document index demonstrated exactly once. Small enough that you can check any facet count by eye.

It is deliberately separate from `demo/`, which has grown to three data files, 25 queries
and a 3,000-line app. Nothing here is shared with it.

```bash
task build     # once — builds the Fuseki server jar
task model     # once — fetches the embedding model (~130MB); optional, see below
task up        # load, index, serve, and open the app on :8070
```

Without `go-task`, the same steps are:

```bash
# from the repo root — the server jar, plus the optional embeddings module and its classpath
mvn install -pl jena-fuseki2/jena-fuseki-server -am -DskipTests -Drat.skip
mvn -q -pl jena-text-embeddings -am -Pdev -DskipTests -Drat.skip install
mvn -q -pl jena-text-embeddings dependency:build-classpath \
    -Dmdep.outputFile=$(pwd)/demo/.embeddings-cp

cd demo
JAR=../jena-fuseki2/jena-fuseki-server/target/jena-fuseki-server-6.2.0-SNAPSHOT.jar
CP="$JAR:$(cat .embeddings-cp):../jena-text-embeddings/target/classes"

rm -rf DB Lucene Taxonomy
java -cp $JAR tdb2.tdbloader --loc=DB data/kitchen.ttl
java -cp "$CP" org.apache.jena.query.text.cmd.shacltextindexer --desc=config.ttl
java -cp "$CP" org.apache.jena.fuseki.main.cmds.FusekiServerCmd --port 3040 --config config.ttl &
python3 serve_app.py --port 8070 --directory app --backend http://localhost:3040
```

`jena-text-embeddings` is deliberately **not** a dependency of the Fuseki server jar — an ML
runtime should not ship by default with the text index — so both the indexer and the server
assemble a classpath instead of running the shaded jar. Unlike the Jlama path, no
`--add-modules jdk.incubator.vector` is needed: ONNX Runtime does not use the Vector API.

App on <http://localhost:8070>, Fuseki on :3040. `task query` runs every file in
`queries/` from the shell; the app's **Feature tests** tab runs the same files in the browser.

`task check` runs them all and compares each row count with `expected-rows.tsv`. The counts
are exact rather than "returned something", because most of these queries exist to pin a
number: `13` returns three recipes and not five because the same-child fold rejects two that
satisfy each clause separately, and `12` returns none at all because its fields are
`idx:stored false`. A non-empty check would pass while either silently broke. It also fails
if a query is not listed, so a new one cannot be added and never checked.

That check is what CI runs (`.github/workflows/demo.yml`), on demo changes **and** on
`jena-text` changes — a library edit can break this demo with every unit test still green.
Adding a level to a hierarchy renames its dimension, and a query naming the old name returns
nothing rather than erroring; that happened while this demo was being written.

**Load and index with the server stopped.** The review children come from a CSV via
`idx:externalSource`, which makes the shape rebuild-only: a live graph update cannot
re-derive CSV rows, so the change listener refuses to touch those documents rather than
silently dropping them. Only the bulk indexer builds them, and it needs exclusive TDB2
access.

## What each feature looks like, and where

| # | Feature | Config | Query | In the app |
|---|---|---|---|---|
| 1 | Faceting | `idx:facetable` | `02`, `03` | left panel, counts update per filter |
| 2 | Date range slicing | `idx:TemporalField` | `06`, `07` | "Published" histogram slider |
| 3a | TEXT / BM25 | `idx:TextField`, no analyzer | `01` | Keyword mode |
| 3b | KEYWORD on IRIs | `idx:KeywordField` | `10` | every facet checkbox |
| 3c | Edge n-gram | `text:EdgeNGramAnalyzer` + `text:tokenized` | `08` | Code typeahead mode |
| 3d | INT / range facets | `idx:IntField` | `07` | "Prep time" buckets |
| 4 | Hierarchical faceting, **3 levels** | `idx:facetHierarchy` (root **and** nested) | `04`, `05`, `17`, `19` | Region › country, Reviewer › stars › month |
| 5 | One field, many paths **and many shapes** | repeated `sh:property` occurrences | `09`, `18` | "People (author or tester)", and the Recipes / Reviewers / Both toggle |
| 6 | Match projection | `idx:stored` | `11`, `12` | Index view panel, and the "matched on" line |
| 7a | Nested children from the **graph** | `idx:nested` + `idx:joinPath` | `20` | Ingredients panel |
| 7b | Nested children from a **CSV**, unstored | `idx:externalSource` + `idx:stored false` | `12`, `13`, `17` | Reviews panel, and the review list on each card |
| 8 | Vector search | `idx:VectorField` | `15`, `16` | Semantic mode |

## One field, three predicates, two shapes

`field:name` is the loudest version of the fan-in, because the demo data is deliberately
untidy in the way real data is:

| Fed by | On |
|---|---|
| `rdfs:label` | recipes r01, r02, r03, r09 |
| `schema:name` | recipes r04, r05, r06 |
| `dcterms:title` | recipes r07, r08, r10 |
| `schema:name` | all six reviewers — a **different target class** |

One search over one field finds a name whatever predicate carries it and whatever kind of
thing it is on. No `UNION`, no per-predicate field, no separate query per entity type.
Query `18` prints the source predicate beside each hit so the spread is visible.

The **Recipes / Reviewers / Both** toggle above the search box filters on `field:entityType`,
which both shapes populate from `rdf:type`. Two things are worth noticing while using it:

- **"Both" is the absence of a filter**, not an `in` of every class. With two shapes the two
  are equivalent and no filter is cheaper; it would have to become an explicit `in` the
  moment a third shape existed that should be excluded.
- **Switch to Reviewers and the entire facet sidebar disappears.** Every facetable field in
  this index belongs to the recipe shape — a reviewer has a name and a description and
  nothing else to count. A field belongs to whichever shapes populate it, so nothing had to
  be nullable or defaulted for the shape that does not use it.

Searching across both kinds at once is where it pays off. `pastry` returns Tarte Tatin and
the reviewer Tomas, ranked together by one BM25 pass. In Semantic mode, "someone who cares
about long simmered stock" returns Yuki, whose description is "broths, stocks and anything
simmered for longer than seems reasonable" — and Tonkotsu Ramen a few places below.

## The date histogram

"Published" is a two-handle slider over a bar per year, and the bars are a **range facet on
the `idx:TemporalField`** — the same `luc:facet` call as the "Prep time" buckets, with date
boundaries instead of integers:

```json
[{"field":"urn:jena:lucene:field#publishedOn",
  "ranges":["2019-01-01","2020-01-01","2021-01-01","2022-01-01","2023-01-01","2024-01-01","2025-01-01"]}]
```

The point is that you can see where the data is before choosing a window; a pair of date
inputs lets you pick an empty range and gives no clue why it was empty. Dragging compiles to
one `between` on the same field that query `06` uses by hand.

Three details that matter:

- **The bars are counted with the date filter excluded**, so dragging never flattens the
  thing you are dragging over. Same rule as the hierarchy levels.
- **They do still reflect every other filter.** Search for "chilli" and the histogram drops
  to 2020 and 2022, which is where those two recipes are — so it answers "when is what I am
  looking at from", not just "when is everything from".
- **The axis is the data's**, discovered at startup from `MIN`/`MAX` of `kt:publishedOn`
  rather than hardcoded, and a year with no data keeps its slot as a sliver so a gap reads
  as a gap.

## The three sources

Three buttons in the header open one overlay, switchable while open, showing the inputs the
index is built from — so what is being searched is never a black box:

| | |
|---|---|
| **RDF** | `data/kitchen.ttl`, everything that gets indexed |
| **Index config** | served by Fuseki from `GET /$/config` — the actual file it started with, not a copy beside the app |
| **Reviews CSV** | `data/reviews.csv`, never loaded into the graph and not stored in the index either |

## Correlation without a CSV: 100g *of chilli*

The reviews demonstrate same-child correlation, but they need an external file to explain.
Ingredients make the same point with nothing but RDF. Each recipe has `kt:ingredient`
records carrying an item and a quantity, reached with `idx:joinPath` — the graph-side
counterpart of the CSV block:

```turtle
kt:recipe-r08 kt:ingredient
    [ kt:item kt:ing-chilli ; kt:grams 400 ] ,
    [ kt:item kt:ing-almond ; kt:grams 150 ] ,
    [ kt:item kt:ing-chocolate ; kt:grams 20 ] .
```

Ask for **100g or more of chilli** and the two clauses must land on one record:

| | |
|---|---|
| has chilli at all | Green Papaya Salad, Ceviche, Mole Poblano |
| has any ingredient ≥ 100g | all ten recipes |
| **≥ 100g of chilli** | **Mole Poblano, and only Mole Poblano** |

The rejected two are the point. Green Papaya Salad has chilli (10g) *and* has a 600g
ingredient; Ceviche has chilli (5g) *and* a 500g one. Neither has a single record that is
both, so neither matches — without the fold, "100g of chilli" would quietly mean "some
chilli, and 100g of anything". Query `20`, and in the app: open **Chilli** in the
Ingredients panel, see 5g / 10g / 400g, tick 400g.

These fields are `idx:stored true`, unlike the review fields beside them, so
`luc:nestedMatch` projects the record that matched — chilli, 400g. One nested block of each
kind, which is what makes the cost of `idx:stored false` legible.

## The URL is the query

Every view is a shareable link, and what it carries is the **compiled arguments** rather
than the widget states:

```
?q=beef
&mode=semantic
&filter={"op":"and","args":[{"op":"=","args":[{"property":"…#course"},"…course-main"]}, …]}
&sort={"field":"…#prepMinutes","order":"asc","missing":"last"}
&facet=["region_country","reviewer_stars_reviewMonth","…#course", …]
&page=1
```

`filter`, `sort` and `facet` are byte-for-byte the `cqlFilter`, `sortSpec` and `facetFields`
that go to `luc:query` and `luc:facet`. So a link is also a readable statement of the
question, and the three can be pasted straight into a SPARQL call.

`filter` is parsed back into widget state on load — the drill paths, the ticked boxes, the
year slider, the kind toggle. That works because the app generates the filter and knows its
shape; it is **not** a general CQL2 reader. Anything it does not recognise is ignored rather
than guessed at, so a hand-edited filter still runs correctly even where the sidebar cannot
show why.

The percent-encoding is kept to what a URL actually requires. `URLSearchParams` serialises
as form-encoded, which escapes every `:` in an IRI and every brace and bracket of the JSON;
RFC 3986 allows all of those unencoded in a query, so they are put back:

```
filter=%7B%22op%22%3A%22%3D%22%2C%22args%22%3A%5B%7B%22property%22%3A%22urn%3Ajena%3A…   before
filter={%22op%22:%22=%22,%22args%22:[{%22property%22:%22urn:jena:lucene:field%23reviewer…  after
```

The remaining `%22` and `%23` are **not** ours to fix: `"` is in the URL standard's query
percent-encode set and `#` starts the fragment, so a browser re-encodes both no matter what
is written. Verified by writing literal quotes and reading `location.search` back.

`facet` is written but not read back: which facets to request follows from the index
configuration, not from anything the user chose, so it is recomputed on load. It is in the
URL because it is part of the question. It is also omitted entirely from the bare landing
page, where it would be 300 characters of noise.

One thing deliberately **not** in the URL: which facet nodes are merely *open*. Expanding is
not filtering and does not change the results, so it is not part of the view worth sharing;
a restored link expands whatever the filter drilled into.

## Opening a facet is not the same as filtering by it

Each drillable value has a **twisty** beside its checkbox, and the two are independent:
the twisty shows what is inside a value, the checkbox narrows the results by it. Opening
"Asia" leaves all ten recipes on screen.

This needs no client-side trickery, because `luc:facet` takes its **own** `cqlFilter`,
separate from the one `luc:query` gets. Asking for Asia's countries is an `=` on
`field:region` sent only to the facet call:

```sparql
# facet call — "=" on region, so the dimension returns Asia's CHILDREN
(?f ?value ?lo ?hi ?count) luc:facet ("default" "default" "*" '["region_country"]'
    '{"op":"=","args":[{"property":"urn:jena:lucene:field#region"},"...region-asia"]}' 50 0)

# results call — no region clause at all, so nothing is narrowed
(?hit ?entity) luc:query ("default" "default" "*" "" "" 10 0)
```

Ticking a value adds the clause to *both*. One open node costs one extra facet request.

## Everything on screen is a label, never a CURIE

The chips, the facet lists, the badges and the "matched on" line all show `rdfs:label`,
resolved through the browser-cached label endpoint one IRI at a time. That includes the
**index fields themselves**: `data/kitchen.ttl` gives `field:region` and friends an
`rdfs:label`, so a chip reads "COUNTRY France" through exactly the same mechanism as the
value beside it, rather than by title-casing `idx:fieldName` in JavaScript. Those triples
describe the index vocabulary rather than a recipe, and no shape targets them, so they add
nothing to the index.

Two views are deliberately exempt, because their whole point is to show what the server
actually returned: the **Feature tests** table and the **Turtle** panels. Turtle without
CURIEs would not be Turtle.

## Why a result matched

Each card carries a **Matched on** line naming the indexed field that produced the hit,
from `luc:match` — `Summary` when the text query hit the summary, and the value itself on
hover. Three cases are worth knowing, and all three appear in the demo:

| Line | Means |
|---|---|
| `Summary`, `Title`… | the text query matched that field |
| `nearest neighbour · cosine 0.820` | a vector hit; `luc:match` projects nothing because proximity is the whole explanation |
| `filter only — no text match` | the CQL filter selected it and the query string was a match-all |

## The Index view panel

The right-hand column is a `CONSTRUCT` over the same search, fetched as `text/turtle` and
shown verbatim. It answers "what did the index actually hit on":

```turtle
kt:recipe-r06  rdfs:label     "Tiramisu" ;
        field:summary  "Savoiardi soaked in espresso, layered with mascarpone and dusted with cocoa." ;
        luc:rank       0 ;
        luc:score      "1.0000615"^^xsd:float .
```

Field IRIs are used **as predicates**, which is what makes it readable: `field:summary` is
the indexed field `luc:match` says matched, and the object is the stored value it matched
on. A filter that selected CSV children adds them as a nested record, so you see the row
itself:

```turtle
kt:recipe-r10  rdfs:label  "Miso Soup" ;
        luc:matchedRecord  [ field:reviewMonth "2025-11" ; field:reviewer "Priya" ; field:stars 5 ] ;
        luc:rank 2 .
```

Two things it makes visible that are otherwise invisible:

- **A vector hit has no `field:` predicate at all.** `luc:match` has nothing to project,
  because a KNN hit is near the query in embedding space rather than matching a term. The
  panel says so explicitly in Semantic mode.
- **`luc:rank` has to be carried in the payload.** A graph is unordered, so the ranking
  cannot survive as row order — which is precisely why `?rank` exists (see
  [02-sparql-api.md](../docs/02-sparql-api.md#use-rank-for-order-not-score)).

Its `luc:query` arguments are identical to the results query, so it shares one Lucene
execution with the results and the facets rather than searching again. It is hidden below
1180px of viewport width.

## The record beside each card

Each result also carries a column showing **the record itself, as it sits in the graph** —
its own triples and nothing else:

```turtle
kt:recipe-r06  rdf:type      kt:Recipe ;
        rdfs:label           "Tiramisu" ;
        kt:author            kt:person-bruno ;
        kt:code              "RCP-2021-0154" ;
        kt:country           kt:country-italy ;
        kt:course            kt:course-dessert ;
        kt:prepMinutes       30 ;
        kt:publishedOn       "2021-09-30"^^xsd:date ;
        dcterms:description  "Savoiardi soaked in espresso, …" .
```

This is a separate `CONSTRUCT` over the same hits — the RDF the index was built **from**, as
against the index view in the right-hand panel. It reaches one hop into blank nodes, a
concise bounded description rather than only the entity's own triples: without that an
ingredient record is an empty `[]`, since its contents hang off a blank node the recipe
merely points at. That nesting is exactly the structure `idx:joinPath` walks. Both are one request for
the whole page, split on their subject blocks client-side, which is reliable because Jena
writes each subject at column 0 with its predicates indented.

Putting the two side by side is the clearest thing in the demo:

| | Card column | Right-hand panel |
|---|---|---|
| What it is | the record in the graph | what Lucene holds and matched |
| `kt:author`, `kt:course`, … | ✅ | ✗ (not what the index stores) |
| `luc:rank`, `luc:score` | ✗ | ✅ |
| `field:summary "…"` — the field that matched | ✗ | ✅ |
| The review rows | **✗ — not in the graph** | **✗ — indexed but `idx:stored false`** |

That last row is the whole external-content story. The reviews are in neither document:
the graph never held them, and the index holds only what it needs to filter and count. They
are on the card because the app read `data/reviews.csv` directly.

Two extras fall out of the above: **sort pushdown and paging** (`14`, the Sort dropdown),
and the **config endpoint** — the badge top-right is `GET /$/config/effective`, reporting
the index-shape fingerprint status and field count.

## Vector search: what actually works

Semantic search runs on a real model, through the **`onnx`** provider added for this.
Try these in the app's Semantic mode — none shares a word with the recipe it finds:

| Query | Returns | Because the recipe says |
|---|---|---|
| a coffee flavoured Italian pudding | Tiramisu | espresso, mascarpone, savoiardi |
| a savoury broth with bean curd | Miso Soup | dashi, fermented soybean paste, tofu |
| meat cooked very slowly in wine until tender | Slow-Braised Beef Shin | braised four hours in red wine |
| pasta with a sharp cheese and lots of pepper | Cacio e Pepe | spaghetti, pecorino, black pepper |
| raw seafood marinated in citrus | Ceviche | raw white fish cured in lime juice |

The same strings in Keyword mode do not find those recipes at all. That contrast is the
demo.

### Two honest limits

**There is no relevance cutoff.** A KNN search returns the k nearest neighbours, full stop
— so with ten recipes and `idx:knnTopK` at its default of 100, *everything* comes back on
every query, ranked. `?totalHits` is "neighbours returned, bounded by k", not a corpus
count. Set `idx:knnTopK` low (3, say) and reindex if you want to watch the cut-off bite;
the top-k-scoped facet counts shrink with it.

**It matches topic, not inference.** These queries work because they paraphrase what the
text says. Queries needing a leap — "something warm and comforting for a cold night", which
requires knowing braised beef is comfort food — do *not* reliably return the stew. That is
the model, not the plumbing: an independent numpy forward pass over the same weights ranks
it the same way. Small sentence-embedding models are paraphrase engines.

### The jlama provider is broken — do not use it

It loads `BAAI/bge-small-en-v1.5` without error and returns 384-dimensional vectors that
are **not the model's output**. Measured against a reference BERT forward pass written in
numpy, reading the same `model.safetensors` and the same token ids from Jlama's own
(correct) tokenizer:

| Comparison | Reference | ONNX provider | Jlama 0.8.4 |
|---|---|---|---|
| beef stew vs "a slow-cooked meat dish in wine" | 0.6534 | 0.6534 | 0.77 |
| beef stew vs "quantum chromodynamics lattice gauge theory" | **0.2919** | **0.2919** | **0.71** |
| tiramisu vs "quantum chromodynamics lattice gauge theory" | 0.4367 | 0.4367 | 0.78 |
| vector vs the reference vector, same text | 1.00 | ~1.00 | **-0.06** |

The last row is the finding: under Jlama's default `MODEL` pooling the embedding is
orthogonal to the correct one, and the best of its four pooling modes reaches 0.27. Every
recipe ends up closer to a particle-physics phrase than to another recipe.

Ruled out: not our configuration, not the model files. The tokenizer emits correct
WordPiece ids with `[CLS]`/`[SEP]`; `F32`/`F32` and `F32`/`I8` working dtypes are
bit-identical; all four `PoolingType` values are wrong; the same weights behave correctly
under both the numpy reference and ONNX Runtime. 0.8.4 is the latest release. Nothing
throws — it is the "confident garbage" failure `docs/03-configuration.md` warns about for a
model mismatch, arriving through the engine instead.

`TestOnnxEmbeddingProvider.embeddingsMatchTheReferenceForwardPass` pins the numbers above.

### How the onnx provider is put together

ONNX Runtime does the forward pass; **Jlama's `BertTokenizer` still does the tokenisation**,
because it was verified correct and is pure Java, so reusing it avoids a second native
dependency. Pooling is per-model and silently wrong if guessed — BGE pools `[CLS]`, E5 and
the MiniLM family average — so it is inferred from the model name and overridable with the
`pooling` option, as are the query/document prefixes BGE and E5 require.

It also removes the constraint that motivated the design note's fallback: Jlama loads only
encoder-only BERT, whereas anything with an ONNX export now works. `idx:dimension` must
match what the model produces — 384 here — and Lucene fixes it at index time, so changing
model means a reindex.

Model files land in `./models` on first run (~130MB from HuggingFace) and are reused after
that. `task model` fetches them as a deliberate step instead, and is a no-op once they are
present; nothing under `models/` is ever committed. Neither is the TDB2 store, the Lucene
index or the taxonomy — `task up` regenerates all of it in a couple of seconds. For a fully offline demo, switch `config.ttl` to the `hashing` provider and set
`idx:dimension 64` on `field:embedding`; it compares words rather than meaning, and
demonstrates the plumbing only.

## Almost nothing is stored

`idx:stored` defaults to **true**, and this config turns it off on ten of eighteen fields.
Filtering, faceting, sorting and range queries read points and docvalues, never the stored
copy — so storing costs nothing but *projection*, and the graph already holds the values
(the app reads them from there, see [The record beside each card](#the-record-beside-each-card)).

Only two kinds of field are stored:

| Stored | Why |
|---|---|
| `name`, `summary` | searchable TEXT — `luc:match` shows *which* field matched and with what |
| `ingredient`, `ingredientGrams` | `luc:nestedMatch` projects the record that matched (query `20`) |

Everything else — the vocabulary terms, the hierarchy levels, the entity class, the code,
the prep time, the date — is `idx:stored false`. Verified after the change: facet values
still bind, range facets still bucket, sort still orders, and all twenty queries still
return their expected counts. `codeText` is worth singling out: it had no `idx:stored` line
at all, so it was defaulting to stored, and an n-gram field is never projected — `luc:match`
reports what the *query string* matched, and that field is reached through a `text_query` in
the `cqlFilter` instead.

Do not read a size saving into this at ten recipes; the index is 64K either way. The point
is the rule, which is [doc 10](../docs/10-suggested-configuration.md): store a field only
when `luc:match` or `luc:nestedMatch` must project it.

## Two behaviours worth knowing

**Hierarchical facets are addressed by dimension name, not field IRI.** Everything else in
the public API uses field IRIs; a hierarchy uses `"region_country"`, derived by joining the
levels' `idx:fieldName` values with `_`. Faceting on `field:region` instead silently gives
you region's *flat* counts — which look identical at the top level and stop being
equivalent the moment you drill in. The app reads the real names from
`/$/config/effective` rather than hardcoding them.

**A hierarchy level must not be counted under its own filter.** The server returns one
level at a time — no drill filter means the top level, an `=` on a level means that level's
children — so a two-level tree is two requests, each omitting the filter belonging to the
level it is asking about. Without that, choosing "Europe" removes Europe and its siblings
from the very list you chose it from, and choosing "France" empties the list entirely,
because the drill path is then complete and the server is being asked for a third level
that does not exist. Both levels stay on screen with the chosen values ticked, and sibling
counts stay correct because they are computed under the *other* active filters. Solr and
Elasticsearch spell the same idea as excluding a tagged filter.

**Region › country is a real taxonomy dimension, but it proves nothing.** Asking for the
dimension `region_country` returns *regions*, which no flat field can do — `field:country`
returns countries — and it needs the on-disk `text:taxonomyDirectory` that only a taxonomy
uses. But every recipe has exactly one country, so its counts are identical to what
"filter by region, then flat-count country" would give. As a demonstration it is worthless;
as a hierarchy it is genuine. Query `19` is the one that discriminates.

**Drilling is a path, and it is not capped at two levels.** `reviewer_stars_reviewMonth`
has three, and the server returns the children of whatever path the `=` clauses describe:

```
level 1   no filter               ->  Priya 6, Noor 5, Tomas 4, Yuki 4, Ines 3, Kwame 3
level 2   = Priya                 ->  ★★★★★ 3, ★★ 1, ★★★★ 1, ★★★ 1
level 3   = Priya AND = 5 stars   ->  2024-02, 2024-04, 2025-11
```

Level 3 is the proof. Those three recipes carry **nine** review rows between them, and a
flat count of the month field would return all nine months. Three come back, because the
taxonomy holds the whole path per review row and only Priya's five-star rows contribute.
It could not be faked with flat facets regardless: a child-scoped field has no
entity-level flat facet at all.

**Adding a level renames the dimension.** `reviewer_stars` became
`reviewer_stars_reviewMonth` when the third level went in, and a query naming the old one
silently returns nothing rather than erroring. This bit query `17` while it was being
written. The app reads the current names from `/$/config/effective` for that reason.

**A nested hierarchy's levels are correlated per child record, not cross-produced.** This
is the guarantee that makes drilling into `Reviewer › stars` mean anything, and query `17`
is built to falsify it. Tonkotsu Ramen has exactly two reviews — Noor gave 5, Priya gave 2
— and drilling each reviewer on that one recipe returns exactly the rating they gave:

```
?reviewer  ?stars  ?recipes
"Noor"     "5"     1
"Priya"    "2"     1
```

Cross-produced levels would offer *both* ratings under *both* reviewers, promising a
five-star Priya review that is not in the data and letting the children out-count the
parent. Declaring `reviewer` and `stars` as **root** occurrences rather than inside the
`idx:nested` block would do exactly that — see `03-configuration.md`, Pattern 4. The same
guarantee on the filtering side is query `13`.

**A child-scoped field has no entity-level flat facet.** `field:reviewer` is declared
`idx:facetable true` and the index log lists it as facetable, but `luc:facet` on it returns
zero rows, and `"*"` does not expand to it. This is documented (`03-configuration.md`,
Pattern 4) and it is why the reviews panel uses a nested `idx:facetHierarchy` — which is
the better demo anyway, since its levels correlate per review row.

## The data

`data/kitchen.ttl` — 10 recipes, 7 countries in 3 regions, 3 courses, 3 diets, 4 people.
Each recipe has exactly one country, so the root hierarchy has no cartesian product to get
wrong. Region is never stated on a recipe; it is reached by the sequence path
`( kt:country kt:inRegion )`.

### The reviews are indexed but not stored

The three review fields are `idx:stored false`. The index holds enough to **find, correlate,
count and sort** on them and keeps **no values**:

| Still works | Gone |
|---|---|
| same-child filter "Priya AND 5 stars" (`13`) | `luc:nestedMatch` projects nothing (`12`) |
| the `reviewer_stars` hierarchy and its drill-down (`17`) | |
| facet counts per reviewer and per rating | |
| range filters and sort on `stars` | |

So the graph and the index are the **filter**; the values stay in the source of truth. The
app fetches `data/reviews.csv` itself — bundled and served beside it — and renders the rows.
That is why a result card can show a reviewer and a rating that query `12` cannot produce,
and why it shows *every* review on the recipe rather than only the ones that matched, with
the matching ones marked.

A browser cannot read an arbitrary path on disk, which is the real constraint; in a
deployment this would be whatever API owns the reviews, and the shape of the code would not
change. It is fetched once per session and cached.

The trade is **display staleness against index size**: what you see is always the current
file, and the index carries no copy of it. It does **not** buy filter freshness — the index
is still a snapshot and must be rebuilt when the source moves. Query `12` asserts the
absence, and says which one boolean brings projection back.

`data/reviews.csv` — 25 review rows keyed by recipe IRI, **never loaded into the graph**.
The browser does not read this file and there is no DuckDB involved: the bulk indexer reads
it once and writes each row as a child document inside the recipe's Lucene block. Values
come back out through `luc:nestedMatch`.

The rows are arranged so same-child correlation is falsifiable. Tonkotsu Ramen has a Priya
review *and* a five-star review, but no five-star Priya review, so "Priya AND 5 stars" must
not return it. Expect exactly Slow-Braised Beef Shin, Cacio e Pepe and Miso Soup — that is
query `13`, and it is what the Reviews panel produces when you pick Priya then ★★★★★.

## The label cache

Labels are never joined into the search query. Each IRI is resolved by its own `GET`
through `app/labels.js`, so every IRI is its own HTTP cache key and a returning view costs
no network transfer. `serve_app.py` rewrites Fuseki's `no-store` header on those requests
to make that legal, and bounds what it will proxy under `/$/` — Fuseki's default
`LocalhostFilter` compares the *socket* address, which a proxy would otherwise launder.

Carried over from `../label-cdn`; the design point is the browser's own HTTP cache rather
than an in-page `Map`. Language negotiation is deliberately not carried over — the demo
data is untagged.

## Files

```
config.ttl        server, index, fields, one shape — read top to bottom
data/kitchen.ttl  10 recipes and the vocabulary
data/reviews.csv  external children, never in the graph
queries/*.rq      one file per feature; also the app's test panel
app/              index.html, app.js, app.css, labels.js
serve_app.py      static files + a Fuseki proxy on one origin
```
