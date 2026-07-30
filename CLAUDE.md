# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Apache Jena — a Java framework for semantic web and linked data applications. This fork adds **SHACL-based entity-per-document indexing with faceted search** to the `jena-text` module using Lucene, alongside the existing triple-per-document model.

**Version**: 6.2.0-SNAPSHOT | **Java**: 21+ | **Build**: Maven 3.9+

## Repository

This tracks `apache/jena` as an upstream source, but is a **standalone repo**, not a
GitHub-level fork (`isFork: false`).

- **Ours:** `Kurrawong/jena` (`origin`) — all issues, PRs, and pushes go here
- **Upstream:** `apache/jena` — do NOT create issues, PRs, or push to this repo

Always use `-R Kurrawong/jena` if there is any ambiguity. Never use `-R apache/jena`
for write operations.

`upstream` is not configured by default. To add it:

```bash
git remote add upstream https://github.com/apache/jena.git
git fetch upstream main
```

**Docker image pushes to GHCR**: images publish to `ghcr.io/kurrawong/*`, following the
repo to its new home:

| Image | Dockerfile target |
|-------|-------------------|
| `ghcr.io/kurrawong/fuseki-lucene-shacl` | `runtime` |
| `ghcr.io/kurrawong/fuseki-lucene-shacl-loader` | `loader` |

The owner is written as the **lowercase literal `kurrawong`**, not
`${{ github.repository_owner }}`. That expression preserves the owner's casing —
`Kurrawong` — and Docker repository names must be lowercase, so buildx rejects the tag
before contacting the registry. Every CI push failed this way between the repo move and
2026-07-30 with `invalid tag "ghcr.io/Kurrawong/...": repository name must be lowercase`.
Do not "simplify" it back to the expression.

CI pushes with `GITHUB_TOKEN` (`packages: write`), which needs no extra setup. For local
`task *-ghcr-push`, the `gh` CLI needs the `write:packages` scope for the `Kurrawong` org —
verify with `gh auth status`. Override per-invocation with `GHCR_OWNER=...`.

Earlier images published under `ghcr.io/aiworkerjohns/*` as `fuseki-ai` / `fuseki-loader`;
that account did not move with the repo and those tags are not updated.

## Build Commands

```bash
# Development build (fast, skips license checks and javadoc)
mvn clean install -Pdev

# Full build with all modules
mvn clean install

# Fastest possible build (skip tests and javadoc)
mvn -DskipTests -Dmaven.javadoc.skip=true clean install

# Build specific module and its dependencies
mvn -pl :jena-text -am install

# Skip license header checks during development
mvn clean install -Drat.skip
```

## Running Tests

```bash
# Run all jena-text tests (646 tests)
mvn test -pl jena-text

# Run a single test class
mvn test -pl jena-text -Dtest=TestNativeFacetCounts

# Run a single test method
mvn test -pl jena-text -Dtest=TestNativeFacetCounts#testBasicFacetCounts

# Run only SHACL/faceting tests
mvn test -pl jena-text -Dtest="TestShaclIndexMapping,TestShaclDocumentBuilding,TestShaclTextDocProducer,TestShaclAssembler,TestShaclEntityPerDocument,TestNativeFacetCounts,TestTextFacetPF,TestTextQueryPFFilters,TestSearchExecution"
```

**Important**: `jena-text/pom.xml` restricts surefire to `**/TS_*.java`, so `TS_Text.java`
is the only entry point. A new test class that is not added to its `@SelectClasses` list
is **silently never run** — it is not reported as skipped, it simply does not appear.
After adding a test, confirm its name appears in the `-- in <class>` lines of the output.

Three classes are currently unregistered and therefore dead: `TestDateLiteralRoundTrip`,
`TestFacetedResults`, `TestUpdateDocumentFacets` (12 `@Test` methods).

### JUnit 4 vs JUnit 5

Upstream migrated `jena-text` to JUnit 5. The fork's ~40 SHACL/faceting test classes are
still JUnit 4, so `jena-text/pom.xml` carries **`junit-vintage-engine`** to let the JUnit
Platform run them. Do not remove it while any JUnit 4 test remains — without it those
classes stop running and the build still passes green.

`TS_Text` itself uses the JUnit 5 `@Suite` / `@SelectClasses` annotations; JUnit 4 classes
can be listed there and the vintage engine picks them up. New tests should be written in
JUnit 5. If migrating existing ones, note that `assertEquals` flips argument order between
the two (JUnit 4 puts the message first, JUnit 5 last) — there are ~197 message-first call
sites, so a blind find-and-replace will silently invert assertions.

### Test discipline

- **Documented recommendations must be backed by a test.** Any behaviour or config pattern we recommend in documentation (e.g. the `idx:normalizer` twin-field pattern) must have a test that exercises the *exact* recommended shape — including the real field cardinality (single- vs multi-valued). Advice must not outrun coverage.
- **Write the test first and confirm it fails (red) before fixing (green).** For a bug fix or a new behaviour, add the failing test, run it, and verify it fails for the expected reason; only then apply the fix and watch it pass. Keep the red and green as separate commits where practical so the TDD step is visible in history.
- **Test the corners of the config matrix, not one axis at a time.** When a change interacts with existing flags (sortable × multiValued × normalized × field type), cover the intersection that production configs actually use — not just each axis in isolation.

### Fuseki UI (JavaScript)

```bash
cd jena-fuseki2/jena-fuseki-ui
yarn install
yarn dev          # Vite dev server
yarn test:unit    # Vitest
yarn test:e2e     # Cypress
yarn lint         # ESLint with --fix
```

## Architecture

### Module Hierarchy

25+ Maven modules in two build profiles:

- **`-Pdev`** — Core modules only (jena-base, jena-core, jena-arq, jena-tdb2, jena-text, jena-fuseki2, etc.). Fast for local dev.
- **`-Pcomplete`** (default) — Everything including distribution, examples, benchmarks, geosparql.

Key dependency chain: `jena-base` → `jena-core` → `jena-arq` → `jena-tdb2` → `jena-text` → `jena-fuseki2`

### Dual Indexing in jena-text

**Classic mode** (upstream): Triple-per-document. Config via `text:entityMap`. SPARQL via `text:query`. No faceting.

**SHACL mode** (new): Entity-per-document. Config via `text:shapes` (SHACL). SPARQL via `luc:query` + `luc:facet`. Supports typed fields (TEXT, KEYWORD, INT, LONG, DOUBLE), range queries, and faceted navigation.

All new code is additive — upstream code paths are unmodified.

**Backward compatibility policy**: The classic `text:query` mode (upstream) must remain untouched. Within SHACL mode (`luc:query` / `luc:facet`), **no backward compatibility is required**. Breaking changes are expected as the query syntax and implementation are refined. Do not maintain multiple syntaxes or support previous commit-era formats — only the target model matters. Once stable, backward compatibility will be considered for release.

### Key SHACL Mode Classes (jena-text)

| Class | Role |
|-------|------|
| `ShaclIndexMapping` | Parsed data model: `IndexProfile` (shape), `FieldDef` (field), `FieldType` enum |
| `ShaclTextDocProducer` | Change listener — rebuilds entity Lucene docs on triple add/delete |
| `ShaclTextQueryPF` | `luc:query` property function with JSON filter support and `?totalHits` binding |
| `TextFacetPF` | `luc:facet` property function — returns (field, value, count) bindings |
| `SearchExecution` | Shared state between `luc:query` and `luc:facet` in same SPARQL query via `ExecutionContext` |
| `ShaclIndexAssembler` | Parses `text:shapes` RDF config into `ShaclIndexMapping` |
| `TextIndexLucene` | Extended with SHACL faceting methods (core methods unchanged) |

### Shared Execution Pattern

When `luc:query` and `luc:facet` appear in the same SPARQL query, both build a normalised key from query params. `SearchExecution.getOrCreate()` stores/retrieves shared state in `ExecutionContext`, avoiding redundant Lucene searches. Key normalisation sorts property URIs and filter values for deterministic matching.

### Change Listener Flow

`DatasetGraphTextMonitor.add()` → `super.add()` (base dataset updated first) → `ShaclTextDocProducer.change()` → if relevant predicate or `rdf:type`, calls `rebuildEntityDocuments()` which reads all entity triples from base dataset and replaces the Lucene document.

## Dependencies and Upstream Sync

**Dependency version updates come from upstream, not Dependabot.** Apache Jena runs
Dependabot over the same tree — roughly half of all upstream commits are dependency
bumps — so raising them here duplicates that work and turns every root-pom version
property into a conflict on the next sync.

`.github/dependabot.yml` therefore covers only what this repo owns:

| Ecosystem | Status |
|-----------|--------|
| `github-actions` | Tracked — our workflows |
| `docker` (`/build-files/docker`) | Tracked — our Fuseki images |
| `maven` | **Not tracked** — comes from upstream sync |
| `npm` (`jena-fuseki-ui`) | **Not tracked** — upstream's module |

Dependabot *security* updates are a separate repository-level feature and are unaffected;
they still raise PRs for CVEs across the full Maven/npm tree between syncs.

**Do not re-add the `maven` or `npm` ecosystems** without also deciding what happens to
the sync workflow — they conflict by design.

### Syncing upstream

`.github/workflows/upstream-sync.yml` runs monthly (and on demand), merging
`apache/jena@main` onto a dated branch. Clean merges open a draft PR; conflicts file an
issue for manual resolution.

**Merge, never rebase.** The fork carries ~180 commits and many live branches. Rebasing
would rewrite published history and replay the same pom conflicts once per commit; a
merge resolves the whole gap once.

Conflicts recur in a predictable set — the root `pom.xml` version properties,
`jena-benchmarks` module lists, `TS_Text.java`, and `jena-text/pom.xml`. When resolving:

- Prefer upstream's version for anything upstream owns; it minimises divergence and keeps
  eventual contribution back to Apache tractable.
- Check `junit-vintage-engine` survived in `jena-text/pom.xml` — an auto-merge has
  silently dropped it before.
- Use `${project.version}` for intra-project deps the fork adds, so a version bump cannot
  strand them.
- Re-run `mvn test -pl jena-text` and confirm the test count, not just a green build.

## Git Commits

- Do NOT add `Co-Authored-By` lines to commit messages
- Do NOT add "Generated with Claude Code" or similar attribution lines to PR descriptions

## Code Style

- K&R "Egyptian brackets" braces
- **4 spaces** for Java, **2 spaces** for XML (no tabs)
- One statement per line
- Use `@Override`, proper generic types, no `@author` tags
- No compiler warnings (use `@SuppressWarnings` as needed)
- Don't mix reformatting with functional changes
- **All source files require Apache License 2.0 header** (enforced by RAT plugin, skip with `-Drat.skip`). This includes generated/data files like `.ttl`. Use `## Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0` as the first line for Turtle files. The `task build` command runs a full build **with** RAT checks — files missing headers will fail the build.

## Documentation

Fork-specific documentation lives in `/docs/`:
- `01-user-guide.md` — Configuration and usage
- `02-sparql-api.md` — SPARQL query API
- `03-configuration.md` — Assembler configuration
- `04-architecture.md` — Internal design
- `05-testing.md` — Test coverage overview

## Running Fuseki Server

```bash
mvn clean install -pl jena-fuseki2/jena-fuseki-server -am -DskipTests
java -jar jena-fuseki2/jena-fuseki-server/target/jena-fuseki-server-*.jar --config config.ttl
```
