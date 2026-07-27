#!/usr/bin/env bash
## Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0
#
# Report the on-disk size of a Lucene index, broken down by what the bytes actually
# are. Optionally difference it against a second index to attribute those bytes to
# origin.
#
# Within a single index, graph-derived and external content share segment files —
# there is no per-origin accounting to read off. But the split is exact in principle:
# every graph field lives on a parent document, every external value on a child. So
# building the same collars twice, once with the idx:nested block and once without,
# and differencing, gives the answer directly.
#
# Usage:
#   ./tools/index-size.sh Lucene-full
#   ./tools/index-size.sh Lucene-full Lucene-graphonly     # attribute the difference

set -euo pipefail

describe_ext() {
  case "$1" in
    dvd|dvm) echo "docvalues — sort keys, facet values, numeric columns" ;;
    dim|dii) echo "BKD point trees — numeric range filters" ;;
    tim|tip|tmd) echo "term dictionary" ;;
    doc|pos|pay) echo "postings — which docs hold which terms" ;;
    fdt|fdx|fdm) echo "stored fields — values returned to the caller" ;;
    nvd|nvm) echo "norms — text scoring lengths" ;;
    kdd|kdi|kdm) echo "BKD point trees" ;;
    cfs|cfe) echo "compound segment (mixed contents)" ;;
    si|segments*) echo "segment metadata" ;;
    *) echo "" ;;
  esac
}

report() {
  local dir="$1"
  [ -d "$dir" ] || { echo "  (absent)"; return; }
  local total
  total=$(du -sb "$dir" | cut -f1)
  printf "  total: %s (%d bytes)\n" "$(du -sh "$dir" | cut -f1)" "$total"
  find "$dir" -type f -printf '%f %s\n' 2>/dev/null \
    | awk '{ n=split($1,p,"."); ext=(n>1?p[n]:"none"); s[ext]+=$2 } END { for (e in s) printf "%s %d\n", e, s[e] }' \
    | sort -k2 -nr \
    | while read -r ext bytes; do
        printf "    %-10s %8.1f MB   %s\n" ".$ext" "$(echo "$bytes" | awk '{print $1/1048576}')" "$(describe_ext "$ext")"
      done
}

bytes_of() { [ -d "$1" ] && du -sb "$1" | cut -f1 || echo 0; }

MAIN="${1:?usage: index-size.sh <lucene-dir> [baseline-dir]}"
BASE="${2:-}"

echo "── $MAIN"
report "$MAIN"

TAXO_MAIN="${MAIN/Lucene/Taxonomy}"
if [ -d "$TAXO_MAIN" ]; then
  echo "── $TAXO_MAIN (hierarchical facet ordinals)"
  report "$TAXO_MAIN"
fi

if [ -n "$BASE" ]; then
  echo
  echo "── $BASE (same collars, no external content)"
  report "$BASE"

  m=$(bytes_of "$MAIN"); t=$(bytes_of "$TAXO_MAIN"); b=$(bytes_of "$BASE")
  echo
  echo "── attribution"
  awk -v m="$m" -v t="$t" -v b="$b" -v rows="${ROWS:-29707584}" -v collars="${COLLARS:-2470212}" '
    BEGIN {
      ext = m + t - b;
      printf "    graph-derived (parents only) : %8.1f MB\n", b/1048576;
      printf "    external content             : %8.1f MB   (%.1fx the graph side)\n", ext/1048576, ext/b;
      printf "    total                        : %8.1f MB\n", (m+t)/1048576;
      printf "\n    per external child document  : %8.1f bytes  (%d rows)\n", ext/rows, rows;
      printf "    per collar (graph fields)    : %8.1f bytes  (%d collars)\n", b/collars, collars;
    }'
fi
