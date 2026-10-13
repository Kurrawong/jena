---
title: "Phrase queries for split terms, and what search clients escape"
date: "2026-10-13"
status: "Proposed"
---

# Phrase Queries for Split Terms, and What Search Clients Escape

This design changes how the `luc:` query parser treats a search term that an analyzer splits
into several tokens, such as the tenement code `E45/5326`. It also sets out which characters a
search client should escape before sending a term, and what the data has to look like for
identifier searches to match.

GSWA reported the problem in ADO #266862: a search for `E45/5326` returns more than 4,000
results where the user expected 2.

Code references are to `101ea8e036`. The parse results below come from Lucene 10.3.1's classic
`MultiFieldQueryParser`, run outside Fuseki with the analyzers the GSWA configuration uses: the
default `StandardAnalyzer` on `label` and `LowerCaseKeywordAnalyzer` on `identifier`. Result
counts against the GSWA index have not been measured.

## How a search term reaches Lucene

1. The search client sends the term to Prez as the `q` parameter.
2. Prez puts the term into the SPARQL query as a string literal and escapes it for SPARQL only:
   `"` becomes `\"` and `\` becomes `\\`. Prez does not change Lucene syntax, so the term reaches
   jena-facet as the client sent it.
3. jena-facet parses the term with Lucene's classic `QueryParser`, or `MultiFieldQueryParser`
   when the search covers several fields (`ShaclTextIndexLucene.parseQueryForFields`, from line
   767). With no field list, the search covers every field marked `idx:defaultSearch true`.
4. The parser passes each term through the query analyzer of each field.

Lucene syntax is the client's to use or to escape. Prez stays neutral, so a client that wants
wildcards or phrases can send them, and a client whose users never type Lucene syntax escapes it.

## Why `E45/5326` returns thousands of results

There are two separate causes.

- **The parser reads `/` as the start of a regular expression.** An unescaped `E45/5326` fails
  with a `ParseException`, because the regular expression `/5326` is never closed. The reporter
  got results, so their term was escaped somewhere before it reached jena-facet.
- **The text fields split the escaped term and OR the parts.** `E45\/5326` parses to:

  ```
  (label:e45 identifier:e45/5326) (label:5326)
  ```

  `identifier` keeps the code whole and matches it exactly. `label` and the other
  `StandardAnalyzer` fields split it into `e45` and `5326`, and the parser joins the parts with
  OR. Any record whose label contains `e45` or `5326` matches. In the GSWA configuration that
  includes `tenementLabel`, which copies the linked tenement's `rdfs:label` onto Reports and Sites.

## The change

Lucene's classic parser has two settings for this:

- `setSplitOnWhitespace(true)` splits the term on whitespace before analysis, so each word is
  analyzed on its own.
- `setAutoGeneratePhraseQueries(true)` turns a word that analyzes to several tokens into a phrase
  query. The tokens then have to appear next to each other, in order. Lucene rejects this setting
  with an `IllegalArgumentException` unless split-on-whitespace is also on.

With both on, `E45\/5326` parses to:

```
label:"e45 5326" identifier:e45/5326
```

The text fields keep their analyzers and their indexed tokens. Only the query changes, so the
change needs no reindex.

### Steps

1. Add two properties to `TextVocab`: `text:splitOnWhitespace` and
   `text:autoGeneratePhraseQueries`. Both are booleans set on the SHACL text index resource, beside
   `text:storeValues`.
2. Read both in `ShaclTextIndexAssembler`, in the same way as `text:storeValues`, and store them on
   `TextIndexConfig`. Both default to `true`.
3. Fail at assembly when `text:autoGeneratePhraseQueries` is `true` and `text:splitOnWhitespace`
   is `false`. Lucene would otherwise throw on every query.
4. Add one method in `ShaclTextIndexLucene` that builds a `QueryParser` or `MultiFieldQueryParser`
   and applies `setAllowLeadingWildcard(true)` and the two settings. Replace the seven places that
   build a parser today: `parseQueryForFields` (lines 789 to 824) and `buildNamedQuery` (line 855).
5. Leave both settings out of the index configuration fingerprint. They change how queries are
   parsed, not what is indexed.
6. Add parser tests for the cases in the before-and-after table below, and configuration tests for
   the defaults, for both settings off, and for the rejected combination.
7. Add both properties to the index property table in `docs/03-configuration.md`.

The change covers `luc:` queries on the SHACL index only. The upstream `text:query` path in
`TextIndexLucene` is unchanged.

### Defaults

| Property | Default | Effect when `true` |
| --- | --- | --- |
| `text:splitOnWhitespace` | `true` | Each whitespace-separated word is parsed and analyzed on its own |
| `text:autoGeneratePhraseQueries` | `true` | A word that analyzes to several tokens becomes a phrase query |

Backward compatibility is not required, so the defaults change behaviour for every existing
deployment. A deployment that needs the previous parsing sets both properties to `false`.

### Before and after

| Term sent | Current parse | Parse with both settings on |
| --- | --- | --- |
| `E45\/5326` | `(label:e45 identifier:e45/5326) (label:5326)` | `label:"e45 5326" identifier:e45/5326` |
| `Fe-rich granite` | `(label:fe identifier:fe-rich granite) (label:rich) (label:granite)` | `(label:"fe rich" identifier:fe-rich) (label:granite identifier:granite)` |
| `E45 5326` | `(label:e45 identifier:e45 5326) (label:5326)` | `(label:e45 identifier:e45) (label:5326 identifier:5326)` |

The second row shows the change applies to every term with internal punctuation, not only to
identifiers. `Fe-rich` now needs `fe` followed by `rich`. Before, either word matched.

The third row shows the cost. Today an unquoted `E45 5326` reaches `identifier` as the single
keyword `e45 5326`. With split-on-whitespace, `identifier` sees `e45` and `5326` separately, so a
keyword value that contains a space only matches when the client quotes the term or escapes the
space.

## Characters the search client should escape

We strongly recommend that a search client escape every character in this table, unless it offers
that Lucene feature to its users on purpose. The escape character is `\`, placed before the
character. "Unescaped" shows what Lucene does with the character as typed.

| Character | Unescaped | Example |
| --- | --- | --- |
| `/` | Starts a regular expression | `E45/5326` fails with a `ParseException` |
| `:` | Names a field | `E45:5326` searches a field named `E45` for `5326`, so it matches nothing |
| `\` | Escapes the next character | `E45\` fails with a `ParseException` |
| `"` | Starts or ends a phrase | An unbalanced `"E45` fails with a `ParseException` |
| `(` `)` | Groups clauses | An unbalanced `(E45` fails with a `ParseException` |
| `[` `]` `{` `}` | Range query | An unbalanced `[E45` fails with a `ParseException` |
| `^` | Boosts the preceding term | `E45^` fails with a `ParseException` |
| `~` | Fuzzy match, or phrase slop | `E45~` matches terms within two edits of `e45` |
| `+` | At the start of a word, the word is required | `+E45` |
| `-` | At the start of a word, excludes the word | `E45 -5326` excludes every record containing `5326` |
| `!` | At the start of a word, excludes the word; at the end, fails | `!E45` excludes, `E45!` fails |
| `&` `\|` | Doubled, they are AND and OR | `E45 && 5326` requires both words |
| `*` `?` | Wildcards | `E45*` matches every term starting with `e45` |

The words `AND`, `OR` and `NOT` in capitals are operators. On their own, or at the end of the
term, they fail with a `ParseException`. Backslash cannot escape a whole word, but escaping its
first letter works: `\OR` searches for the word `or`.

Lucene's `QueryParserBase.escape` escapes the same characters. A client in JavaScript can use:

```js
const escapeLucene = (s) =>
  s.replace(/[\\+\-!():^[\]"{}~*?|&/]/g, "\\$&")
   .replace(/\b(AND|OR|NOT)\b/g, "\\$1");
```

If the client offers wildcards or phrases, remove `*`, `?` or `"` from the character class. A
wildcard term is not split by the analyzer, so `E45\/53*` matches identifiers starting with
`e45/53` but matches no label.

Escaping a space keeps two words together as one term. `E45\ 5326` reaches `identifier` as
`e45 5326`, which is how a client can match a keyword value that contains a space.

Prez escapes the term for SPARQL after the client has escaped it for Lucene. The client must not
escape for SPARQL as well, or Lucene receives doubled backslashes.

## What the data needs for identifier searches

These conditions hold for the GSWA configuration in `gswa-prez-config`
(`fuseki/full_data/configuration/config.ttl`), where `field:identifier` is a default search field
that uses `LowerCaseKeywordAnalyzer` for indexing and queries.

- **The code is in `schema:identifier`.** A shape maps the code to `field:identifier` with
  `sh:path ( schema:identifier schema:value )`. `:TenementShape` does this. A code that appears
  only in a label is matched by the phrase query, not by `identifier`.
- **The value has no spaces.** With split-on-whitespace on, a value containing a space matches only
  when the client quotes the term or escapes the space. A deployment whose identifiers contain
  spaces either has its client do that, or sets both parser properties to `false`, which brings
  back the split-and-OR behaviour on text fields.
- **The value has no leading or trailing whitespace.** The keyword analyzer indexes the whole value
  as one token, spaces included, so ` E45/5326` does not match `E45/5326`.
- **Case does not matter.** The keyword analyzer lowercases the indexed value and the query term,
  including wildcard terms.
- **Matching is on the whole value.** `E45\/532` does not match `E45/5326`. A partial match needs a
  wildcard, `E45\/532*`.

With the change, the code also matches on text fields, as a phrase. Any label where `e45` is
followed directly by `5326` matches, whatever punctuation separates them in the label:
`E45/5326`, `E45-5326` and `E45 5326` all match. The result set therefore holds the tenement and
every record whose `label`, `tenementLabel` or other default search text field contains the code.
A tenement that matches on both `identifier` and `label` is likely to rank above a record that
matches on one field, but BM25 scoring does not guarantee it.

If GSWA expects only the tenement records, a search restricted to `identifier` gives that. That
restriction is a choice of fields in the client or the Prez profile, and this change does not
make it.
