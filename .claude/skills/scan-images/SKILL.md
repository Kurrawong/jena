---
name: scan-images
description: >-
  Scan the Fuseki Docker images (fuseki-ai runtime, fuseki-loader indexer) for OS
  and Java-library CVEs by running the Taskfile `scan` task (trivy), then report the
  findings and propose the exact dependency / base-image changes that fix them. Use
  when asked to scan, audit, or check the Docker images for vulnerabilities or CVEs.
  Report-and-suggest only: never rebuild or push images automatically.
---

# Scan Docker images for vulnerabilities

The scan logic lives in the **root `Taskfile.yml`** (`scan`, `scan-image`, `scan-check-trivy`).
This skill *runs* those tasks and then interprets the results into concrete, reviewable fixes.
It does **not** rebuild or push images — that stays a human decision (rebuilds are slow
Maven-in-Docker builds).

## The images

Two images built from a single `build-files/docker/Dockerfile` via `--target`, sharing one
Maven builder stage and the same `eclipse-temurin:21-jre-ubi10-minimal` runtime base.
`loader` builds `FROM runtime`, so it is a strict superset:

| Image | Target | Contents |
|-------|--------|----------|
| `fuseki-ai` | `runtime` | lean server — just `jena-fuseki-server.jar` |
| `fuseki-loader` | `loader` | everything in `runtime` **+** bundled `apache-jena` distribution (`/fuseki/apache-jena/lib/*.jar`) **+** `jq`; multi-mode `loader-entrypoint.sh` |

Implication for triage: a **base / OS** CVE affects *both* images. A **Java-library** CVE
often exists in both, but trivy can only version-detect the unpacked jars in the loader's
bundled distribution — the runtime's shaded uber-jar usually shows clean even when it
contains the same library.

## Run the scan

From the repo root:

```bash
task scan                                    # both images at the current VERSION, HIGH+CRITICAL
task scan-image SCAN_IMAGE=<name>:<tag>      # one image (any local or registry reference)
task scan SCAN_SEVERITY=CRITICAL,HIGH,MEDIUM # widen severity
```

- The task **requires a recent trivy** (the floor is `TRIVY_MIN_VERSION` in the Taskfile;
  older trivy cannot scan the UBI/RHEL 10 base — trivy-db issue #435). If `scan-check-trivy`
  fails, follow the install/upgrade line it prints (`brew upgrade trivy`, or the no-admin
  `install.sh` one-liner).
- To use a trivy binary that is not on `PATH`: append `TRIVY=/path/to/trivy`.
- Each run writes a JSON report to `build-files/docker/scan-reports/<image>.json`
  (git-ignored). **Read that JSON** — it has the full detail (`FixedVersion`, `PkgPath`,
  `References`) needed to suggest fixes; the printed table is just a summary.
- If an image is missing locally the task tells you to build it first
  (`task loader-build` / `task runtime-build`).

> **Scan the *right* image.** A scan only describes the exact local tag you point it at.
> `demo/test/docker-compose.yml` reuses the `fuseki-ai` image tag for a *different*,
> Alpine-based demo build — so a stale demo image can masquerade as the production runtime and
> report Alpine CVEs the real UBI image never had. For an accurate production scan, rebuild
> from the root Taskfile first (`task runtime-build` / `task loader-build`) and confirm
> `Metadata.OS` in the JSON report reads `redhat` (the UBI base), not `alpine`.

## Turn each finding into a suggested fix

For every HIGH/CRITICAL in the JSON report, classify by `Class` / `Type` and propose an edit.
**Verify before recommending** — confirm the fixed version actually exists and is plausibly
compatible; do not invent versions.

**Java library** (`Class: lang-pkgs`, `Type: jar`):
1. `PkgName` is `group:artifact`; note the jar path (`Target` / `PkgPath`).
2. Find the version in the root `pom.xml` — search for a `ver.<artifact>` property in
   `<properties>`, or the `<version>` in that dependency's `<dependencyManagement>` block.
3. The fix is bumping to `FixedVersion`. **If `FixedVersion` is empty**, open the advisory
   (`PrimaryURL` + `References`) to find the patched release (e.g. an upstream GitHub release
   tag) and use that.
4. Output the exact `pom.xml` line and the new value. Flag if the dependency is upstream-managed
   (a plain bump in our fork's parent pom is the lever).

**OS package** (`Class: os-pkgs`, `Type: redhat`):
1. The fix is almost always a newer base image. Propose bumping
   `eclipse-temurin:21-jre-ubi10-minimal` in `build-files/docker/Dockerfile` to a newer
   tag or pinned digest. There is a single `FROM` line for both images — the `loader`
   target inherits it via `FROM runtime`, so one edit covers both.
2. If no fixed version is published yet, say so and name the affected package — don't guess.

## Report format

1. **Findings table** — one row per CVE: Image · CVE · Severity · Package · Installed → Fixed · Location.
2. **Suggested changes** — exact `file:line` edits, grouped by file, with the new value and a
   one-line rationale (and real-world exposure if the vulnerable code path is unused).
3. **To apply (do not run automatically)** — the rebuild + re-scan commands for the user:
   ```
   task loader-build      # or runtime-build, for whichever image(s) changed
   task scan              # confirm the CVE is gone
   ```
   Pushing fixed images (`task loader-ghcr-push` / `*-acr-push`) is a separate, explicit step.

Then stop and let the user decide. Offer to apply a specific bump on request; after applying,
rebuild the affected image and re-scan to prove the finding is cleared.

## Worked example — CVE-2026-43869 (libthrift)

Captured from a real loader scan, as a template for the analysis above:

- **Finding:** `org.apache.thrift:libthrift` `0.22.0`, HIGH, *Improper certificate hostname
  validation*, at `fuseki/apache-jena/lib/libthrift-0.22.0.jar` (loader only). `FixedVersion`
  was empty; the advisory references point to the **0.23.0** release.
- **Why the library is there:** libthrift backs **RDF Thrift** — Jena's compact binary RDF
  serialization (used across `jena-arq/.../riot/thrift/`, plus RDF Patch binary and binary
  SPARQL results). It cannot be removed without dropping that format.
- **Real exposure:** ~nil. The CVE is in Thrift's TLS **transport / RPC** layer; Jena uses only
  the serialization codecs and `pom.xml` explicitly excludes the HTTP/servlet/RPC transports
  ("Jena does not use the RPC capabilities of Thrift, only the binary data format").
- **Suggested change:** `pom.xml` — `<ver.libthrift>0.22.0</ver.libthrift>` → `0.23.0`.
- **Apply + verify:** `task loader-build` then `task scan`.
