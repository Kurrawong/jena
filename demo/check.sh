#!/usr/bin/env bash
## Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0
##
## Run every query in queries/ against a running server and compare the row count with
## expected-rows.tsv.
##
## Exact counts: see the header of expected-rows.tsv.
##
## Usage:  ./check.sh [SERVER_URL]     (default http://localhost:3040)

set -uo pipefail

SERVER="${1:-http://localhost:3040}"
ENDPOINT="$SERVER/kitchen/query"
DIR="$(cd "$(dirname "$0")" && pwd)"

if ! curl -sf "$SERVER/$/ping" >/dev/null; then
    echo "No server at $SERVER — start one with 'task serve' first." >&2
    exit 2
fi

fail=0
checked=0

while read -r name expected; do
    ## Skip comments and blank lines.
    [[ "$name" =~ ^#|^$ ]] && continue

    file="$DIR/queries/$name.rq"
    if [[ ! -f "$file" ]]; then
        printf '  %-42s MISSING %s\n' "$name" "$file"
        fail=$((fail + 1))
        continue
    fi

    ## -f so an HTTP error fails the query rather than counting the error page's lines.
    if ! result=$(curl -sf -X POST "$ENDPOINT" \
            -H "Content-Type: application/sparql-query" \
            -H "Accept: text/tab-separated-values" \
            --data-binary "@$file"); then
        printf '  %-42s ERROR (HTTP request failed)\n' "$name"
        fail=$((fail + 1))
        continue
    fi
    ## Row count excludes the header line that SPARQL TSV always emits.
    actual=$(printf '%s\n' "$result" | tail -n +2 | awk 'NF { n++ } END { print n + 0 }')

    checked=$((checked + 1))
    if [[ "$actual" == "$expected" ]]; then
        printf '  %-42s %3s ok\n' "$name" "$actual"
    else
        printf '  %-42s %3s EXPECTED %s\n' "$name" "$actual" "$expected"
        fail=$((fail + 1))
    fi
done < "$DIR/expected-rows.tsv"

## Every query must be listed, or a new one could be added and never checked.
declare -a unlisted=()
for f in "$DIR"/queries/*.rq; do
    name="$(basename "$f" .rq)"
    grep -qE "^$name[[:space:]]" "$DIR/expected-rows.tsv" || unlisted+=("$name")
done
if (( ${#unlisted[@]} )); then
    printf '\n  not listed in expected-rows.tsv: %s\n' "${unlisted[*]}"
    fail=$((fail + ${#unlisted[@]}))
fi

echo
if (( fail )); then
    echo "$fail problem(s) across $checked queries"
    exit 1
fi
echo "$checked queries, all as expected"
