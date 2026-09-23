#!/bin/sh
# Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0
#
# Does loader-entrypoint.sh report a failed build step?
#
# Each case runs the entrypoint with a stub `java` on PATH that fails for one
# build step and succeeds for everything else, then checks the exit status and
# whether the success banner was printed. A loader that exits 0 after a crashed
# indexer leaves a truncated index that Fuseki serves without complaint, so the
# status is the only signal downstream has.
#
# Run: sh build-files/docker/test/test-loader-entrypoint.sh

set -u

HERE=$(cd "$(dirname "$0")" && pwd)
ENTRYPOINT="$HERE/../loader-entrypoint.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

failures=0

# Stub `java`. Recognises each Jena entry point by its main class and exits with
# the code named in the matching FAKE_*_EXIT variable; the SPARQL extractions
# always succeed, returning a two-line TSV because the caller reads line 2.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/java" <<'STUB'
#!/bin/sh
for arg in "$@"; do
  case "$arg" in
    arq.sparql)                printf '?location\n/tmp/fake-location\n'; exit 0 ;;
    *shacltextindexer)         exit "${FAKE_INDEXER_EXIT:-0}" ;;
    *SpatialIndexBuilder)      exit "${FAKE_SPATIAL_EXIT:-0}" ;;
  esac
done
exit 0
STUB
chmod +x "$WORK/bin/java"

echo "config placeholder" > "$WORK/config.ttl"

# Run the entrypoint with the given VAR=value settings; sets $status and $out
# (stdout and stderr merged). `env` rather than an assignment prefix: settings
# arriving via "$@" are words, and a shell only recognises assignments written
# literally in the command, so a prefix would be read as the command name.
run_entrypoint() {
  out=$(env PATH="$WORK/bin:$PATH" CONFIG="$WORK/config.ttl" JAVA_VECTOR_OPTS="" \
        "$@" sh "$ENTRYPOINT" 2>&1)
  status=$?
}

check() {
  if [ "$1" = "$2" ]; then
    echo "ok   - $3"
  else
    echo "FAIL - $3 (expected '$2', got '$1')"
    failures=$((failures + 1))
  fi
}

# Says "complete" if the success banner is present, "aborted" otherwise.
banner() {
  case "$out" in
    *"Processing complete."*) echo complete ;;
    *)                        echo aborted ;;
  esac
}

echo "== a failed text index build aborts the load =="
run_entrypoint MODE=text FAKE_INDEXER_EXIT=1
check "$status" 1 "exit status is the indexer's"
check "$(banner)" aborted "no success banner"
case "$out" in
  *"SHACL text index build failed"*) echo "ok   - names the failed step" ;;
  *) echo "FAIL - names the failed step"; failures=$((failures + 1)) ;;
esac

echo "== the exit code is passed through, not flattened to 1 =="
run_entrypoint MODE=text FAKE_INDEXER_EXIT=3
check "$status" 3 "exit status is 3"

echo "== a failed spatial index build aborts the load =="
run_entrypoint MODE=spatial FAKE_SPATIAL_EXIT=1
check "$status" 1 "exit status is the builder's"
check "$(banner)" aborted "no success banner"

echo "== a clean run still succeeds =="
run_entrypoint MODE=text
check "$status" 0 "exit status is 0"
check "$(banner)" complete "success banner printed"

echo
if [ "$failures" -eq 0 ]; then
  echo "All checks passed."
else
  echo "$failures check(s) failed."
fi
exit "$failures"
