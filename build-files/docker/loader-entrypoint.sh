#!/bin/sh
# Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0
set -e

CONFIG="${CONFIG:-/config/config.ttl}"
INPUT_DIR="${INPUT_DIR:-/input}"
OUTPUT_DIR="${OUTPUT_DIR:-/output/ds}"
MODE="${MODE:-all}"
SHACL_INDEX_FIRST_N="${SHACL_INDEX_FIRST_N:-}"
SPATIAL_INDEX_SRS="${SPATIAL_INDEX_SRS:-}"
JAVA_OPTS="${JAVA_OPTS:-}"

if [ ! -f "$CONFIG" ]; then
  echo "Missing config: $CONFIG"
  exit 1
fi

# Step 1: Load data files into TDB2
if [ "$MODE" = "all" ] || [ "$MODE" = "tdb2" ]; then
  echo "=== TDB2 Dataset Build ==="
  FILES=$(ls "$INPUT_DIR"/*.nq "$INPUT_DIR"/*.ttl "$INPUT_DIR"/*.nt 2>/dev/null || true)
  if [ -z "$FILES" ]; then
    echo "No data files found in $INPUT_DIR"
    exit 1
  fi
  java $JAVA_OPTS -cp /fuseki/jena-fuseki-server.jar tdb2.tdbloader --loc "$OUTPUT_DIR" $FILES
fi

# Build SHACL Lucene index
if [ "$MODE" = "all" ] || [ "$MODE" = "text" ]; then
  echo "=== SHACL Text Index Build ==="
  echo "Config: $CONFIG"
  if [ -n "$SHACL_INDEX_FIRST_N" ]; then
    echo "Limit: first $SHACL_INDEX_FIRST_N entities per SHACL profile"
  else
    echo "Limit: full index"
  fi

  java $JAVA_OPTS -cp /fuseki/jena-fuseki-server.jar \
    org.apache.jena.query.text.cmd.shacltextindexer --desc="$CONFIG"
fi

# Build Spatial Index
if [ "$MODE" = "all" ] || [ "$MODE" = "spatial" ]; then
  echo "=== Spatial Index Build ==="
  echo "Dataset: $OUTPUT_DIR"
  if [ -n "$SPATIAL_INDEX_SRS" ]; then
    echo "SRS: $SPATIAL_INDEX_SRS"
  else
    echo "SRS: auto-detect"
  fi

  SPATIAL_CMD="java $JAVA_OPTS -cp /fuseki/jena-fuseki-server.jar \
    org.apache.jena.geosparql.spatial.cmd.SpatialIndexBuilder --loc=\"$OUTPUT_DIR\" \
    --output=\"$OUTPUT_DIR/spatial.index\""
  
  if [ -n "$SPATIAL_INDEX_SRS" ]; then
    SPATIAL_CMD="$SPATIAL_CMD --srs=\"$SPATIAL_INDEX_SRS\""
  fi
  
  eval "$SPATIAL_CMD"
fi

echo "=== Done ==="
