#!/bin/sh
# Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0

# Self-contained demo: in-memory dataset, in-memory index, static app, one process.
# Nothing is read from or written to a volume, so a restart is a rebuild.

set -e

# Container hosts differ on how the port is named. Cloud Run and App Runner inject
# PORT and expect the process to honour it; Compose and plain `docker run` do not set
# it at all. Defaulting to Fuseki's own 3030 keeps `docker run -p 3030:3030` working.
PORT="${PORT:-3030}"

CONFIG="${CONFIG:-/demo/deploy/config.ttl}"
STATIC_DIR="${STATIC_DIR:-/demo/static}"

# Heap. The whole dataset and index live in it, so the default (1/4 of container
# memory) is fine on a 512 MiB instance — override JAVA_OPTS if the data grows.
JAVA_OPTS="${JAVA_OPTS:-}"

# Lucene's Panama Vector API, matching the runtime image's ENTRYPOINT. Kept out of
# JAVA_OPTS so overriding heap cannot drop it.
if [ -z "${JAVA_VECTOR_OPTS+set}" ]; then
  JAVA_VECTOR_OPTS="--enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector"
fi

# Fuseki's run area — where it reads shiro.ini from. Set explicitly rather than left to
# default to the working directory, so the admin lockdown baked into the image is found
# no matter what WORKDIR a derived image sets.
export FUSEKI_BASE="${FUSEKI_BASE:-/demo/deploy/run}"

# --ui rather than --base, though both end up setting the same static file area:
# --ui goes through FMod_UI, which also registers the validators the UI links to
# and enables /$/stats. --base sets the directory and stops there, leaving the UI
# served but with those pages dead.
exec java ${JAVA_VECTOR_OPTS} ${JAVA_OPTS} \
    -jar /fuseki/jena-fuseki-server.jar \
    --port "${PORT}" \
    --config "${CONFIG}" \
    --ui "${STATIC_DIR}"
