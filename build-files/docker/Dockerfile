# Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0

# Single source for both published images, selected with --target:
#
#   --target runtime  ->  fuseki-ai      serving Fuseki
#   --target loader   ->  fuseki-loader  bulk load + index building
#
# Both targets share the `builder` stage, so one Maven reactor build produces
# both images. Previously each image had its own Dockerfile with its own
# `RUN mvn`, which meant CI ran the reactor twice per commit.
#
# `loader` builds FROM `runtime`: every Jena command the loader entrypoint
# invokes (arq.sparql, riotcmd.riot, tdb2.tdbloader, tdb2.tdbstats,
# shacltextindexer, SpatialIndexBuilder) lives in the same shaded
# jena-fuseki-server.jar the runtime already ships. The loader adds only what
# MODE=tdb2.xloader needs on top: the apache-jena distribution (for the
# bin/tdb2.xloader shell script), jq (which that script calls), and the
# entrypoint. The two images stay separate so the runtime is not gated on CVEs
# in the distribution's bundled lib/ jars, which it never executes.

FROM --platform=linux/amd64 maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy only jena modules and pom.xml for caching
COPY pom.xml ./
COPY build-files/license-header build-files/license-header
COPY build-files/rat-exclusions.txt build-files/rat-exclusions.txt
COPY apache-jena/ apache-jena/
COPY apache-jena-libs/ apache-jena-libs/
COPY jena-arq/ jena-arq/
COPY jena-base/ jena-base/
COPY jena-bom/ jena-bom/
COPY jena-cmds/ jena-cmds/
COPY jena-commonsrdf/ jena-commonsrdf/
COPY jena-core/ jena-core/
COPY jena-db/ jena-db/
COPY jena-examples/ jena-examples/
COPY jena-fuseki2/ jena-fuseki2/
COPY jena-geosparql/ jena-geosparql/
COPY jena-integration-tests/ jena-integration-tests/
COPY jena-iri3986/ jena-iri3986/
COPY jena-langtag/ jena-langtag/
COPY jena-ontapi/ jena-ontapi/
COPY jena-querybuilder/ jena-querybuilder/
COPY jena-rdfconnection/ jena-rdfconnection/
COPY jena-rdfpatch/ jena-rdfpatch/
COPY jena-serviceenhancer/ jena-serviceenhancer/
COPY jena-shacl/ jena-shacl/
COPY jena-shex/ jena-shex/
COPY jena-tdb1/ jena-tdb1/
COPY jena-tdb2/ jena-tdb2/
COPY jena-text/ jena-text/
COPY jena-text-embeddings/ jena-text-embeddings/

# -Pcomplete is required because `apache-jena` — the binary distribution the
# loader's tdb2.xloader script comes from — is only a module in that profile.
# -pl/-am then narrows the reactor to the two artifacts the images actually
# consume, skipping jena-examples, jena-commonsrdf, jena-querybuilder and
# jena-integration-tests. Note that every module directory above must still be
# copied regardless: Maven constructs the full reactor graph before -pl
# narrows it, and a missing module directory is a hard error.
RUN mvn --batch-mode --quiet --no-transfer-progress -Dstyle.color=never -Pcomplete \
    clean package -pl apache-jena,jena-fuseki2/jena-fuseki-server -am \
    -DskipTests -Dmaven.javadoc.skip=true

# Loader-only build steps live in their own stage so that `--target runtime`
# skips them entirely rather than paying for a tar and two downloads.
FROM builder AS dist

# Extract the apache-jena distribution here, in the Debian builder, so the UBI
# final stage needs no RUN step. UBI 10 requires the x86-64-v3 CPU level, which
# isn't available when emulating amd64 (e.g. multi-arch builds on Apple Silicon),
# so any RUN in the UBI stage would fail under emulation.
RUN mkdir -p /build/apache-jena-dist && \
    tar -xzf /build/apache-jena/target/apache-jena-*-SNAPSHOT.tar.gz -C /build/apache-jena-dist --strip-components=1

# Fetch statically-linked jq binaries here (Debian builder is emulation-safe);
# the UBI final stage just COPYs the right arch — no RUN step under emulation.
# jq is required by the bundled tdb2.xloader script.
ARG JQ_VERSION=jq-1.7.1
RUN curl -fsSL -o /jq-amd64 "https://github.com/jqlang/jq/releases/download/${JQ_VERSION}/jq-linux-amd64" \
 && curl -fsSL -o /jq-arm64 "https://github.com/jqlang/jq/releases/download/${JQ_VERSION}/jq-linux-arm64" \
 && chmod +x /jq-amd64 /jq-arm64

# ─── fuseki-ai: serving Fuseki ───────────────────────────────────────────────

FROM eclipse-temurin:21-jre-ubi10-minimal AS runtime

# No LABEL here on purpose. CI applies the full OCI label set — source,
# revision, version, created — via docker/metadata-action, which derives them
# from the repository and commit being built. A hardcoded source label went
# stale and wrong when the repo moved (it still read aiworkerjohns/jena long
# after the move); deriving it removes that failure mode.
#
# Careful when inspecting a LOCALLY built image: the UBI base sets its own
# org.opencontainers.image.created and .revision, describing Red Hat's base
# image build, not this source tree. Only images built by CI carry our values
# (buildx --label overrides the inherited keys). Trust those labels on a
# published image, not on a local `docker build`.

WORKDIR /fuseki

COPY --from=builder /build/jena-fuseki2/jena-fuseki-server/target/jena-fuseki-server-*.jar ./jena-fuseki-server.jar

ENTRYPOINT ["java", "-jar", "--add-modules", "jdk.incubator.vector", "--enable-native-access", "ALL-UNNAMED", "/fuseki/jena-fuseki-server.jar", "--config", "/config.ttl"]

# ─── fuseki-loader: bulk load + index building ────────────────────────────────

FROM runtime AS loader

# TARGETARCH is the platform-specific build arg buildx populates per target
# (linux/amd64 -> amd64, linux/arm64 -> arm64). Used to pick the right
# pre-fetched jq binary from the (single, amd64-pinned) dist stage so this
# final stage stays RUN-free and works under emulation on Apple Silicon.
ARG TARGETARCH

COPY build-files/docker/loader-entrypoint.sh ./entrypoint.sh
COPY --from=dist /jq-${TARGETARCH} /usr/local/bin/jq
COPY --from=dist /build/apache-jena-dist /fuseki/apache-jena

ENTRYPOINT ["/fuseki/entrypoint.sh"]
