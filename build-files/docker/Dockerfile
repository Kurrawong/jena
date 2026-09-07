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

# The Fuseki UI ships inside the server jar as /webapp. The demo image serves it
# from a directory, so it has to come out of the jar somewhere — here, in the
# Debian builder, because the UBI final stage takes no RUN step (it would fail
# under emulation on Apple Silicon). `jar` is the JDK's own tool and always
# present in this image; unzip is not.

FROM builder AS uiapp

RUN mkdir -p /ui && cd /ui && \
    jar --extract --file $(ls /build/jena-fuseki2/jena-fuseki-server/target/jena-fuseki-server-*.jar) webapp && \
    test -f /ui/webapp/index.html

# ─── fuseki-ai: serving Fuseki ───────────────────────────────────────────────

FROM eclipse-temurin:25-jre-ubi10-minimal AS runtime

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

# ─── fuseki-lucene-shacl-demo: the mining demo, self-contained ────────────────
#
# The runtime image plus the demo's data, configuration and browser app. No
# volumes, no database, no separate indexing step and no second process: the
# dataset is an in-memory ja:MemoryDataset loaded from ja:data, the Lucene index
# is `text:directory "mem"` built at assembly time by text:buildOnStartup, and
# Fuseki's own Jetty serves the app via --base. One port, same origin, so the app
# needs no proxy and no CORS.
#
# Stateless by construction — every start rebuilds the index from the Turtle in
# the image — which is what makes it safe to redeploy on every push and to run at
# scale-to-zero.

FROM runtime AS demo

# Mirrors the repository layout under /demo, because config.ttl reaches its data
# with <../test/data/mining.ttl>. That is a relative IRI resolved against the
# config file's own location, not the working directory, so the two directories
# must keep the same relationship they have in the source tree.
COPY demo/deploy/config.ttl /demo/deploy/config.ttl
COPY demo/test/data/mining.ttl demo/test/data/generated.ttl /demo/test/data/

# One static root holding both front ends:
#
#   /       the Fuseki UI
#   /demo   this fork's search app
#
# The UI has to be the one at the root. It builds every API URL by prefixing the
# path it was served from — getFusekiUrl() in its bundle — so served at /fuseki it
# would call /fuseki/$/server and /fuseki/mining/query, neither of which exists.
# The search app has no such constraint: its asset references are relative and it
# takes its API base from window.location.origin, so it works from any depth.
COPY --from=uiapp /ui/webapp/ /demo/static/
COPY demo/app-static/ /demo/static/demo/

# The real file in place of the symlink `task app` leaves in app-static (excluded in
# .dockerignore). A copied symlink pointing out of the static base is one Jetty
# refuses to follow, and the app's examples list would 404.
COPY demo/test/tests.json /demo/static/demo/tests.json

# Deny-by-default for /$/, read by Fuseki from FUSEKI_BASE. The bundled default
# only checks the socket address, which a proxy in front of the server defeats.
COPY demo/deploy/shiro.ini /demo/deploy/run/shiro.ini

# Same-origin app configuration, replacing the checked-in one that points at the
# local serve_app.py proxy.
COPY demo/deploy/app-config.js /demo/static/demo/app-config.js

COPY build-files/docker/demo-entrypoint.sh /demo/entrypoint.sh

EXPOSE 3030
ENTRYPOINT ["/demo/entrypoint.sh"]
