---
title: "Searching for codes with punctuation, and what search clients escape"
date: "2026-10-13"
status: "Proposed"
---

# Searching for Codes with Punctuation, and What Search Clients Escape

This design fixes searches for codes that contain punctuation, such as the tenement code
`E45/5326`. It covers two changes to how jena-facet reads a search, the recommendations for the
search client, and what the data has to look like for code searches to match.

A search for `E45/5326` returns more than 4,000 results where the user expected 2.

Code references are to `101ea8e036`. The parse results below come from Lucene 10.3.1's classic
query parser, run outside Fuseki with the analyzers of the configuration that showed the
problem: the default `StandardAnalyzer` on `label` and `LowerCaseKeywordAnalyzer` on
`identifier`. Result counts against that index have not been measured.

## How a search reaches Lucene

1. The search client sends the search text to Prez as the `q` parameter.
2. Prez puts the text into the SPARQL query as a string, escaping only what SPARQL requires:
   `"` becomes `\"` and `\` becomes `\\`. Prez does not change Lucene syntax, so jena-facet
   receives the text the client sent.
3. jena-facet reads the text with Lucene's classic query parser
   (`ShaclTextIndexLucene.parseQueryForFields`, from line 767). The text is searched in every field
   marked `idx:defaultSearch true`, unless the request names its own fields.
4. Each field breaks the text into searchable pieces with its own analyzer. Text fields such as
   `label` use `StandardAnalyzer`, which splits on punctuation and spaces and lowercases. Code
   fields such as `identifier` use `LowerCaseKeywordAnalyzer`, which keeps the whole value as one
   piece and lowercases it.

The query parser gives meaning to some characters, such as `/`, `:` and `*`. Prez passes them
through unchanged. Whether a user can use that syntax is the search client's decision.

## Why `E45/5326` returns thousands of results

There are two causes.

- **The query parser reads `/` as the start of a regular expression.** If `E45/5326` reaches
  jena-facet as typed, the search fails, because the regular expression `/5326` is never closed.
  The reporter got results, so something between the user and jena-facet escaped the text to
  `E45\/5326`. Whether the search client does this, and with which rules, has not been checked.
- **Text fields split the code and accept either half.** `E45\/5326` becomes:

  ```
  (label:e45 identifier:e45/5326) (label:5326)
  ```

  `identifier` keeps the code whole and matches it exactly. `label` and the other text fields split
  it into `e45` and `5326`, and a record matches if its label contains either piece. In that
  configuration they include `tenementLabel`, which copies the linked tenement's label onto
  Reports and Sites.

## Changes to jena-facet

### Change 1: each word is searched separately

**What it is.** jena-facet cuts the search text at spaces before searching. Each word is then
searched for in every field.

**What it does.** Text fields behave as before, because they already split at spaces. Code fields
change. Today, `E45 5326` is looked up in `identifier` as the single value `e45 5326`. After the
change, `identifier` is searched for `e45` and for `5326` separately, so a code that contains a
space no longer matches as a whole. See [What the data needs](#what-the-data-needs).

**Why it is needed.** Change 1 has no benefit of its own. It is needed because Lucene allows
change 2 only when change 1 is on.

### Change 2: a word's pieces must appear together

**What it is.** When a text field splits one word into pieces, the pieces have to appear in a
record next to each other and in the same order. A word means text with no spaces in it:
`E45/5326`, `Fe-rich` or `and/or`.

**What it does.** `E45/5326` splits into `e45` and `5326` in a text field. Today a label containing
either piece matches. After the change, a label matches only where `e45` is followed directly by
`5326`. Labels containing `E45/5326`, `E45-5326` or `E45 5326` match, because the text field
ignores the punctuation. A label containing only `E45 Project` no longer matches.

Searches with several words still match any of the words. `sand clay` matches records containing
`sand` or `clay`, as it does today.

The change applies to every word with punctuation inside it. `Fe-rich` now matches `fe` followed by
`rich`. Today it matches either `fe` or `rich`.

### Results before and after

| Search text received by jena-facet | Today | With both changes |
| --- | --- | --- |
| `E45\/5326` | `(label:e45 identifier:e45/5326) (label:5326)` | `label:"e45 5326" identifier:e45/5326` |
| `Fe\-rich granite` | `(label:fe identifier:fe-rich granite) (label:rich) (label:granite)` | `(label:"fe rich" identifier:fe-rich) (label:granite identifier:granite)` |
| `E45 5326` | `(label:e45 identifier:e45 5326) (label:5326)` | `(label:e45 identifier:e45) (label:5326 identifier:5326)` |

The table shows only `label` and `identifier`. The full query repeats each `label` clause for
every other text field searched by default. The first column is the text after the client
escaping in [Recommendations for the search client](#recommendations-for-the-search-client).

A quoted label term, such as `label:"e45 5326"`, means the pieces must appear together and in
order.

### Configuration and defaults

Today jena-facet uses Lucene's defaults, which have both changes off. Each change gets a new
on/off property on the SHACL text index resource, beside `text:storeValues`. The names follow the
Lucene settings they control.

| Property | Default | Turns on |
| --- | --- | --- |
| `text:splitOnWhitespace` | `true` | Change 1: each word is searched separately |
| `text:autoGeneratePhraseQueries` | `true` | Change 2: a word's pieces must appear together |

Both are on by default. Backward compatibility is not required, so existing deployments get the
new behaviour without a configuration change. A deployment whose codes contain spaces may turn
both off; see [What the data needs](#what-the-data-needs). The index fails to load if change 2 is
on and change 1 is off, because Lucene does not allow that combination.

Neither change alters what is stored in the index, so no reindex is needed. Prez needs no change.

### Implementation notes

- Add both properties to `TextVocab`, read them in `ShaclTextIndexAssembler` the way
  `text:storeValues` is read, and store them on `TextIndexConfig`.
- Build every query parser in one method in `ShaclTextIndexLucene` that applies
  `setAllowLeadingWildcard(true)`, `setSplitOnWhitespace` and `setAutoGeneratePhraseQueries`. This
  replaces the seven places that build a parser today, in `parseQueryForFields` (lines 789 to 824)
  and `buildNamedQuery` (line 855).
- Leave both properties out of the index configuration fingerprint, because they do not change
  what is indexed.
- Add parser tests for the rows of the before-and-after table, and configuration tests for the
  defaults, for both off, and for the rejected combination.
- Add both properties to the index property table in `docs/03-configuration.md`.
- The upstream `text:query` path in `TextIndexLucene` is unchanged.

## Recommendations for the search client

These recommendations assume users type plain text and never Lucene syntax.

1. **Escape the search text with this function before every search.** It puts `\` before each
   character the query parser treats as syntax, and before the words `AND`, `OR` and `NOT`. If
   the client already escapes the text, compare its rules with this function.

   ```js
   const escapeLucene = (s) =>
     s.replace(/[\\+\-!():^[\]"{}~*?|&/]/g, "\\$&")
      .replace(/\b(AND|OR|NOT)\b/g, "\\$1");
   ```

   `E45/5326` becomes `E45\/5326`. `sand OR clay` becomes `sand \OR clay`, which searches for the
   three words.
2. **Send the escaped text as `q` and do not escape it again.** Prez escapes it for SPARQL. Escaping
   for SPARQL in the client as well doubles the backslashes Lucene receives.
3. **Do not offer wildcards, fuzzy search or quoted phrases.** The function escapes `*`, `?`, `~`
   and `"`. Offering one of them means removing its character from the function and documenting
   it for users. A wildcard search matches codes only: `E45\/53*` matches identifiers starting with
   `e45/53` and matches no label.
4. **For a search that should return only the record with a code, limit it to the identifier
   field.** Prez's Lucene search endpoints accept a `fields` parameter, which takes field IRIs.
   Sending `fields=urn:jena:lucene:field#identifier` with `q=E45\/5326` searches `identifier`
   alone. This parameter was found in the Prez code and has not been tested against a
   deployment.

The function escapes these characters. The table shows what each does when it is not escaped.

| Character | Unescaped, the query parser reads it as | Example |
| --- | --- | --- |
| `/` | The start of a regular expression | `E45/5326` fails |
| `:` | A field name | `E45:5326` searches a field named `E45`, so it matches nothing |
| `\` | An escape for the next character | `E45\` fails |
| `"` | The start or end of a phrase | `"E45` fails |
| `(` `)` | A group | `(E45` fails |
| `[` `]` `{` `}` | A range | `[E45` fails |
| `^` | A boost for the preceding word | `E45^` fails |
| `~` | Fuzzy matching | `E45~` matches words within two edits of `e45` |
| `+` | At the start of a word, the word is required | `+E45` |
| `-` | At the start of a word, the word is excluded | `E45 -5326` excludes every record containing `5326` |
| `!` | At the start of a word, the word is excluded | `!E45`; `E45!` fails |
| `&` `\|` | Doubled, AND and OR | `E45 && 5326` requires both words |
| `*` `?` | Wildcards | `E45*` matches every word starting with `e45` |
| `AND` `OR` `NOT` | Operators, when in capitals | `OR` on its own fails |

"Fails" means the search returns an error, not results.

## What the data needs

These conditions apply to a configuration where `field:identifier` is searched by default and
uses `LowerCaseKeywordAnalyzer`.

- **The code is in `schema:identifier`.** A shape maps it to `field:identifier` with
  `sh:path ( schema:identifier schema:value )`, as `:TenementShape` does. A code that appears only
  in a label is found by change 2, but not as an exact match.
- **The code has no spaces.** With change 1, a code containing a space is not found as a whole. The
  client would have to escape the space (`E45\ 5326`) for it to match. A deployment with such codes
  should either do that in the client or turn both changes off, which brings back the
  thousands-of-results behaviour on text fields.
- **The code has no spaces before or after it.** The whole value, spaces included, is one piece, so
  a stored ` E45/5326` does not match a search for `E45/5326`.
- **Case does not matter.** Both the stored code and the search are lowercased.
- **The whole code must match.** `E45\/532` does not find `E45/5326`. A partial code needs a
  wildcard, which recommendation 3 leaves out.

With both changes, a search for `E45/5326` across the default fields returns:

- the tenement, through an exact `identifier` match, and through its label if the label contains
  the code;
- every Report and Site linked to the tenement whose `tenementLabel` contains the code;
- any other record whose label or text contains `E45` followed directly by `5326`.

A tenement that matches on more than one field is likely to rank first, but BM25 scoring does not
guarantee it. Returning only the tenement needs recommendation 4. Whether tenement labels
contain the code has not been checked against the data.
