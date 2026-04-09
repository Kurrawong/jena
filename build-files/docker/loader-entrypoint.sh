#!/bin/sh
# Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0
set -e

CONFIG="${CONFIG:-/config/config.ttl}"
JAVA_OPTS="${JAVA_OPTS:-}"
SHACL_INDEX_FIRST_N="${SHACL_INDEX_FIRST_N:-}"

if [ ! -f "$CONFIG" ]; then
  echo "Missing config: $CONFIG"
  exit 1
fi

echo "=== SHACL Text Index Build ==="
echo "Config: $CONFIG"
if [ -n "$SHACL_INDEX_FIRST_N" ]; then
  echo "Limit: first $SHACL_INDEX_FIRST_N entities per SHACL profile"
else
  echo "Limit: full index"
fi

java $JAVA_OPTS -cp /fuseki/jena-fuseki-server.jar \
  org.apache.jena.query.text.cmd.shacltextindexer --desc="$CONFIG"

echo "=== Done ==="
