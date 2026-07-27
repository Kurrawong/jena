#!/usr/bin/env python3
## Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0
"""
Take the head of a GSWA dh_summary_measurements dump, keeping only COMPLETE
collar groups.

The trailing group is dropped because a truncated one would look, to the demo,
like a collar that genuinely has fewer analytes than it does. The extract keeps
the dump's own row order, which is lexical by collar_id — the order the streaming
merge consumes. Run tools/check-sorted.py to confirm.

Usage:
    python3 tools/extract-measurements.py <source.csv> <dest.csv> [max_rows]
"""

import csv
import io
import sys


def main():
    if len(sys.argv) not in (3, 4):
        print(__doc__, file=sys.stderr)
        return 2
    src, dest = sys.argv[1], sys.argv[2]
    max_rows = int(sys.argv[3]) if len(sys.argv) == 4 else 1000

    rows = []
    with io.open(src, encoding="utf-8") as handle:
        reader = csv.reader(handle)
        header = next(reader)
        for index, row in enumerate(reader):
            if index >= max_rows:
                break
            rows.append(row)

    if not rows:
        print("No rows read from %s" % src, file=sys.stderr)
        return 1

    # Drop the trailing group: it may have been cut off mid-collar.
    truncated = rows[-1][0]
    rows = [row for row in rows if row[0] != truncated]
    if not rows:
        print("First collar group alone exceeds max_rows=%d" % max_rows, file=sys.stderr)
        return 1

    collars = []
    for row in rows:
        if not collars or collars[-1] != row[0]:
            collars.append(row[0])

    with io.open(dest, "w", encoding="utf-8", newline="") as out:
        writer = csv.writer(out, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)

    analytes = sorted({row[1] for row in rows})
    below = sum(1 for row in rows if row[3] == "t")
    print("Wrote %s: %d rows, %d collars, %d analytes, %d below detection"
          % (dest, len(rows), len(collars), len(analytes), below))
    return 0


if __name__ == "__main__":
    sys.exit(main())
