#!/usr/bin/env python3
## Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0
"""
Check that a measurements extract is grouped and ascending by collar_id under
LEXICAL comparison — the ordering the streaming merge uses.

This is worth checking because collar ids in the GSWA extract are 6 and 7 digits,
so lexical and numeric order disagree:

    lexical:  1175968  <  117597   <  1175971  <  117598
    numeric:   117597  <  117598   <  1175968  <  1175971

The dump happens to be sorted as text, which is what the merge needs. Re-exporting
with `ORDER BY collar_id` on an integer column would produce the second order, and
the build would refuse it. Re-sort such a file with:

    LC_ALL=C sort -t, -k1,1 -s

(keeping the header line separate).

Usage:
    python3 tools/check-sorted.py data/measurements.csv
"""

import csv
import io
import sys


def main():
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    path = sys.argv[1]

    previous = None
    seen = set()
    rows = 0
    violations = []

    with io.open(path, encoding="utf-8") as handle:
        reader = csv.reader(handle)
        next(reader)
        for row in reader:
            rows += 1
            current = row[0]
            if current == previous:
                continue
            if previous is not None and current < previous:
                violations.append((rows + 1, previous, current))
            if current in seen:
                violations.append((rows + 1, "(ungrouped) " + current, current))
            seen.add(current)
            previous = current

    if violations:
        print("NOT usable with idx:sorted true — %d violation(s):" % len(violations))
        for line, before, after in violations[:10]:
            print("  line %d: %r followed by %r" % (line, before, after))
        print("\nRe-sort with:  LC_ALL=C sort -t, -k1,1 -s")
        return 1

    print("OK: %d rows, %d collars, lexically grouped and ascending." % (rows, len(seen)))
    print("Safe to declare idx:sorted true.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
