#!/usr/bin/env bash
## Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0
#
# Run the demo's headline queries against a running server and print the results.
# Each one crosses the graph/external boundary in a way that only works because
# both sides are in the same Lucene document.
#
# Usage: ./tools/smoke.sh [server-url]     (default http://localhost:3030)

set -euo pipefail

SERVER="${1:-http://localhost:3030}"
ENDPOINT="$SERVER/geochem/query"
FP="urn:jena:lucene:field#"

ask() {
  local title="$1" sparql="$2"
  echo
  echo "── $title"
  curl -s -m 30 "$ENDPOINT" \
    --data-urlencode "query=$sparql" \
    -H "Accept: text/csv" | sed 's/^/   /'
}

ask "1. Collars carrying a gold assay (external child filter)" "
PREFIX luc: <urn:jena:lucene:index#>
SELECT ?collars WHERE {
  (?hit ?s ?score ?collars) luc:query ('default' 'default' '*'
    '{\"op\":\"=\",\"args\":[{\"property\":\"${FP}analyte\"},\"Au\"]}' '' 1 0) .
}"

ask "2. Au above 0.05 ppm, ABOVE detection — one child must satisfy all three" "
PREFIX luc: <urn:jena:lucene:index#>
SELECT ?collars WHERE {
  (?hit ?s ?score ?collars) luc:query ('default' 'default' '*'
    '{\"op\":\"and\",\"args\":[{\"op\":\"=\",\"args\":[{\"property\":\"${FP}analyte\"},\"Au\"]},{\"op\":\">\",\"args\":[{\"property\":\"${FP}analyteValue\"},0.05]},{\"op\":\"=\",\"args\":[{\"property\":\"${FP}belowDetection\"},\"f\"]}]}' '' 1 0) .
}"

ask "3. Graph field AND external field together — Diamond holes with Cu over 50 ppm" "
PREFIX luc: <urn:jena:lucene:index#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?label WHERE {
  (?hit ?s ?score) luc:query ('default' 'default' '*'
    '{\"op\":\"and\",\"args\":[{\"op\":\"=\",\"args\":[{\"property\":\"${FP}drillType\"},\"Diamond\"]},{\"op\":\"and\",\"args\":[{\"op\":\"=\",\"args\":[{\"property\":\"${FP}analyte\"},\"Cu\"]},{\"op\":\">\",\"args\":[{\"property\":\"${FP}analyteValue\"},50]}]}]}' '' 10 0) .
  ?s rdfs:label ?label .
}"

ask "4. Top 5 collars by gold grade (nested sort selector over a not-stored value)" "
PREFIX luc: <urn:jena:lucene:index#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?label WHERE {
  (?hit ?s ?score) luc:query ('default' 'default' '*'
    '{\"op\":\"=\",\"args\":[{\"property\":\"${FP}analyte\"},\"Au\"]}'
    '{\"field\":\"${FP}analyteValue\",\"filter\":{\"field\":\"${FP}analyte\",\"eq\":\"Au\"},\"order\":\"desc\",\"missing\":\"last\"}' 5 0) .
  ?s rdfs:label ?label .
}"

ask "5. Analyte facet — the property dimension comes free under the nested model" "
PREFIX luc: <urn:jena:lucene:index#>
SELECT ?value ?count WHERE {
  (?field ?value ?low ?high ?count) luc:facet ('default' 'default' '*'
    '[\"${FP}analyte\"]' '' 8 0) .
}"

ask "6. Region facet (graph) restricted by an external filter (Au present)" "
PREFIX luc: <urn:jena:lucene:index#>
SELECT ?value ?count WHERE {
  (?field ?value ?low ?high ?count) luc:facet ('default' 'default' '*'
    '[\"${FP}region\"]'
    '{\"op\":\"=\",\"args\":[{\"property\":\"${FP}analyte\"},\"Au\"]}' 10 0) .
}"

echo
echo "Done."
