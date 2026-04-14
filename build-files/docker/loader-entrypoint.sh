#!/bin/sh
# Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0
set -e

# Configuration variables
CONFIG="${CONFIG:-/config.ttl}"
INPUT_DIR="${INPUT_DIR:-/rdf}"
MODE="${MODE:-all}"
TARGET_GRAPH="${TARGET_GRAPH:-}"
NO_VALIDATION="${NO_VALIDATION:-}"
TDB2_MODE="${TDB2_MODE:-phased}"
NO_STATS="${NO_STATS:-}"
THREADS="${THREADS:-$(($(nproc) > 1 ? $(nproc) - 1 : 1))}"
JAVA_OPTS="${JAVA_OPTS:-}"

# Define all Jena command variables
SPARQL_CMD="java -cp /fuseki/jena-fuseki-server.jar arq.sparql"
RIOT_CMD="java -cp /fuseki/jena-fuseki-server.jar riotcmd.riot"
TDB2_LOADER_CMD="java -cp /fuseki/jena-fuseki-server.jar tdb2.tdbloader"
TDB2_XLOADER_CMD="java -cp /fuseki/jena-fuseki-server.jar tdb2.xloader"
TDB2_STATS_CMD="java -cp /fuseki/jena-fuseki-server.jar tdb2.tdbstats"
TEXT_INDEXER_CMD="java $JAVA_OPTS -cp /fuseki/jena-fuseki-server.jar org.apache.jena.query.text.cmd.shacltextindexer"
SPATIAL_INDEXER_CMD="java $JAVA_OPTS -cp /fuseki/jena-fuseki-server.jar org.apache.jena.geosparql.spatial.cmd.SpatialIndexBuilder"

echo "=== Loader Configuration ==="
echo "CONFIG: $CONFIG"
echo "INPUT_DIR: $INPUT_DIR"
echo "MODE: $MODE"
echo "TARGET_GRAPH: ${TARGET_GRAPH:-default graph}"
echo "NO_VALIDATION: ${NO_VALIDATION:-false}"
echo "TDB2_MODE: $TDB2_MODE"
echo "THREADS: $THREADS (default: nproc-1)"
echo "NO_STATS: ${NO_STATS:-false}"
echo "==========================="

# Early failure checks
if [ ! -f "$CONFIG" ]; then
  echo "ERROR: Missing config file at $CONFIG"
  exit 1
fi

# Extract locations from config
echo "=== Extracting locations from config ==="

# Create temporary query files
TDB2_QUERY="/tmp/tdb2_location.rq"
TEXT_QUERY="/tmp/text_location.rq"
SPATIAL_QUERY="/tmp/spatial_location.rq"
SRS_QUERY="/tmp/srs_uri.rq"

# Write TDB2 location query
{
  echo 'PREFIX tdb2: <http://jena.apache.org/2016/tdb#>'
  echo 'SELECT ?tdb2Location WHERE {'
  echo '  ?s a tdb2:DatasetTDB2 ;'
  echo '     tdb2:location ?tdb2Location .'
  echo '}'
} > "$TDB2_QUERY"

# Write text index location query
{
  echo 'PREFIX text: <http://jena.apache.org/text#>'
  echo 'SELECT ?shaclIndexDirectory WHERE {'
  echo '  ?s a text:TextIndexShacl ;'
  echo '     text:directory ?shaclIndexDirectory .'
  echo '}'
} > "$TEXT_QUERY"

# Write spatial index location query
{
  echo 'PREFIX geosparql: <http://jena.apache.org/geosparql#>'
  echo 'SELECT ?spatialIndexFile WHERE {'
  echo '  ?s a geosparql:geosparqlDataset ;'
  echo '     geosparql:spatialIndexFile ?spatialIndexFile .'
  echo '}'
} > "$SPATIAL_QUERY"

# Write SRS URI query
{
  echo 'PREFIX geosparql: <http://jena.apache.org/geosparql#>'
  echo 'SELECT ?srsUri WHERE {'
  echo '  ?s a geosparql:geosparqlDataset ;'
  echo '     geosparql:srsUri ?srsUri .'
  echo '}'
} > "$SRS_QUERY"

# Extract TDB2 location using Java-based SPARQL
TDB2_LOCATION=$($SPARQL_CMD --query="$TDB2_QUERY" --data="$CONFIG" --results=tsv 2>/dev/null | awk 'NR==2 {print $1}')

# Extract text index location using Java-based SPARQL
TEXT_INDEX_LOCATION=$($SPARQL_CMD --query="$TEXT_QUERY" --data="$CONFIG" --results=tsv 2>/dev/null | awk 'NR==2 {print $1}')

# Extract spatial index location using Java-based SPARQL
SPATIAL_INDEX_LOCATION=$($SPARQL_CMD --query="$SPATIAL_QUERY" --data="$CONFIG" --results=tsv 2>/dev/null | awk 'NR==2 {print $1}')

# Extract SRS URI for spatial indexing using Java-based SPARQL
SRS_URI=$($SPARQL_CMD --query="$SRS_QUERY" --data="$CONFIG" --results=tsv 2>/dev/null | awk 'NR==2 {print $1}' | sed 's/^[<>]//g; s/[<>]$//g')

# Clean up temporary query files
rm -f "$TDB2_QUERY" "$TEXT_QUERY" "$SPATIAL_QUERY" "$SRS_QUERY"

echo "TDB2_LOCATION: ${TDB2_LOCATION:-Not found in config}"
echo "TEXT_INDEX_LOCATION: ${TEXT_INDEX_LOCATION:-Not found in config}"
echo "SPATIAL_INDEX_LOCATION: ${SPATIAL_INDEX_LOCATION:-Not found in config}"
echo "SRS_URI: ${SRS_URI:-Not specified in config (will use default behavior)}"

# Validate required locations based on mode
check_volume_mount() {
  path="$1"
  parent_path=""
  
  # Check if path or any parent is mounted
  while [ "$path" != "/" ]; do
    if grep -q " $path " /proc/mounts; then
      return 0
    fi
    parent_path="$(dirname "$path")"
    if [ "$parent_path" = "$path" ]; then
      break
    fi
    path="$parent_path"
  done
  
  echo "WARNING: No volume mount found for $path or its parents"
  return 1
}

# Check required locations
if [ "$MODE" = "all" ] || [ "$MODE" = "tdb2" ] || [ "$MODE" = "tdb2.xloader" ]; then
  if [ -z "$TDB2_LOCATION" ]; then
    echo "ERROR: TDB2 location not found in config but required for mode $MODE"
    exit 1
  fi
  if ! check_volume_mount "$TDB2_LOCATION"; then
    echo "WARNING: TDB2 location volume check failed, but continuing anyway"
  fi
fi

if [ "$MODE" = "all" ] || [ "$MODE" = "index" ] || [ "$MODE" = "text" ]; then
  if [ -z "$TEXT_INDEX_LOCATION" ]; then
    echo "ERROR: Text index location not found in config but required for mode $MODE"
    exit 1
  fi
  if ! check_volume_mount "$TEXT_INDEX_LOCATION"; then
    echo "WARNING: Text index location volume check failed, but continuing anyway"
  fi
fi

if [ "$MODE" = "all" ] || [ "$MODE" = "index" ] || [ "$MODE" = "spatial" ]; then
  if [ -z "$SPATIAL_INDEX_LOCATION" ]; then
    echo "ERROR: Spatial index location not found in config but required for mode $MODE"
    exit 1
  fi
  if ! check_volume_mount "$SPATIAL_INDEX_LOCATION"; then
    echo "WARNING: Spatial index location volume check failed, but continuing anyway"
  fi
fi

# Find all RDF files (only needed for TDB2 loading modes)
if [ "$MODE" = "all" ] || [ "$MODE" = "tdb2" ] || [ "$MODE" = "tdb2.xloader" ]; then
  echo "=== Finding RDF files ==="
  FILES=$(find "$INPUT_DIR" -type f \( -name "*.trig" -o -name "*.nq" -o -name "*.ttl" -o -name "*.nt" -o -name "*.rdf" -o -name "*.xml" \) 2>/dev/null || true)

  if [ -z "$FILES" ]; then
    echo "ERROR: No RDF files found in $INPUT_DIR"
    exit 1
  fi

  file_count=$(echo "$FILES" | wc -l)
  echo "Found $file_count RDF files:"
  for file in $FILES; do
    echo "  - $file"
  done
fi

# Validation (only for TDB2 loading modes and when not disabled)
if { [ "$MODE" = "all" ] || [ "$MODE" = "tdb2" ] || [ "$MODE" = "tdb2.xloader" ]; } && [ -z "$NO_VALIDATION" ]; then
  echo "=== Validating RDF files ==="
  
  VALID_FILES=""
  INVALID_FILES=""
  WARNING_FILES=""
  
  for file in $FILES; do
    validation_output=$($RIOT_CMD --validate "$file" 2>&1)
    
    if echo "$validation_output" | grep -q 'ERROR'; then
      echo "$file: ❌"
      INVALID_FILES="$INVALID_FILES $file"
    elif echo "$validation_output" | grep -q 'WARN'; then
      echo "$file: ⚠️"
      WARNING_FILES="$WARNING_FILES $file"
      VALID_FILES="$VALID_FILES $file"
    else
      echo "$file: ✅"
      VALID_FILES="$VALID_FILES $file"
    fi
  done
  
  
  echo "Validation Summary:"
  valid_count=$(echo "$VALID_FILES" | wc -w)
  echo "  Valid files: $valid_count"
  warning_count=$(echo "$WARNING_FILES" | wc -w)
  echo "  Files with warnings: $warning_count"
  invalid_count=$(echo "$INVALID_FILES" | wc -w)
  echo "  Invalid files: $invalid_count"
  
  # Exclude invalid files from processing
  FILES="$VALID_FILES"
  if [ -z "$FILES" ]; then
    echo "ERROR: No valid files to process"
    exit 1
  fi
else
  echo "Skipping validation (NO_VALIDATION set or mode doesn't require it)"
fi

# TDB2 Loading
if [ "$MODE" = "all" ] || [ "$MODE" = "tdb2" ] || [ "$MODE" = "tdb2.xloader" ]; then
  echo "=== TDB2 Dataset Build ==="
  
  if [ "$MODE" = "tdb2.xloader" ]; then
    echo "Using tdb2.xloader"
    LOADER_CMD="$TDB2_XLOADER_CMD --loc $TDB2_LOCATION"
  else
    echo "Using tdb2.tdbloader with mode $TDB2_MODE"
    LOADER_CMD="$TDB2_LOADER_CMD --loader=$TDB2_MODE --loc $TDB2_LOCATION"
  fi
  
  # Add target graph if specified
  if [ -n "$TARGET_GRAPH" ]; then
    LOADER_CMD="$LOADER_CMD --graph $TARGET_GRAPH"
  fi
  
  # Add threads if specified (only for xloader)
  if [ "$MODE" = "tdb2.xloader" ] && [ -n "$THREADS" ]; then
    LOADER_CMD="$LOADER_CMD --threads $THREADS"
  fi
  
  # Add all files to loader command
  for file in $FILES; do
    LOADER_CMD="$LOADER_CMD \"$file\""
  done
  
  echo "Loader command: $LOADER_CMD"
  eval "$LOADER_CMD"
fi

# Text Indexing
if [ "$MODE" = "all" ] || [ "$MODE" = "index" ] || [ "$MODE" = "text" ]; then
  echo "=== SHACL Text Index Build ==="
  echo "Config: $CONFIG"
  echo "Text index location: $TEXT_INDEX_LOCATION"
  
  $TEXT_INDEXER_CMD --desc="$CONFIG"
fi

# Spatial Indexing
if [ "$MODE" = "all" ] || [ "$MODE" = "index" ] || [ "$MODE" = "spatial" ]; then
  echo "=== Spatial Index Build ==="
  echo "Dataset: $TDB2_LOCATION"
  echo "Spatial index location: $SPATIAL_INDEX_LOCATION"
  echo "SRS_URI: ${SRS_URI:-auto-detect}"
  
  SPATIAL_CMD="$SPATIAL_INDEXER_CMD --loc=\"$TDB2_LOCATION\" --output=\"$SPATIAL_INDEX_LOCATION\""
  
  # Add SRS_URI if specified in config
  if [ -n "$SRS_URI" ]; then
    SPATIAL_CMD="$SPATIAL_CMD --srs=\"$SRS_URI\""
  fi
  
  eval "$SPATIAL_CMD"
fi

# Statistics
if [ -z "$NO_STATS" ] && { [ "$MODE" = "all" ] || [ "$MODE" = "tdb2" ] || [ "$MODE" = "tdb2.xloader" ]; }; then
  echo "=== Generating Statistics ==="
  # Clean up TDB2_LOCATION in case it has extra quotes
  CLEAN_TDB2_LOCATION=$(echo "$TDB2_LOCATION" | sed 's/^"//; s/"$//')
  STATS_FILE="${CLEAN_TDB2_LOCATION}/stats.opt"
  
  echo "DEBUG: Clean TDB2 location: $CLEAN_TDB2_LOCATION"
  echo "DEBUG: Stats file path: $STATS_FILE"
  
  # Run stats command and capture both output and exit code
  STATS_OUTPUT=$($TDB2_STATS_CMD --graph "urn:x-arq:UnionGraph" --loc "$CLEAN_TDB2_LOCATION" 2>&1)
  
  # Show warnings to user but still create stats file
  echo "$STATS_OUTPUT" | grep -v "^WARN" > "$STATS_FILE"
  
  # Show any warnings to the user
  echo "$STATS_OUTPUT" | grep "^WARN" | while read -r warning; do
    echo "$warning"
  done
  
  # If stats file is empty, try without the UnionGraph
  if [ ! -s "$STATS_FILE" ]; then
    echo "DEBUG: Stats file empty, trying without UnionGraph"
    $TDB2_STATS_CMD --loc "$CLEAN_TDB2_LOCATION" > "$STATS_FILE" 2>&1
  fi
  
  # Show final result
  if [ -s "$STATS_FILE" ]; then
    echo "Statistics generated successfully at $STATS_FILE"
  else
    echo "WARNING: Statistics file is empty - dataset may be empty or have issues"
  fi
  
  echo "Statistics generated at $STATS_FILE"
  echo "First 5 lines:"
  head -n 5 "$STATS_FILE"
  echo "..."
  echo "Last 5 lines:"
  tail -n 5 "$STATS_FILE"
fi

echo "=== Done ==="
echo "Processing complete."
