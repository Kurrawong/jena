# keyword-sort-demo — pre-work results

Captured 2026-07-01 14:42:26

```
=== 01-sort-raw-bytes.rq ===
Finding #1 - KEYWORD sort is raw UTF-8 byte order (case-sensitive, NOT locale-collated).
Expect: Apple, Smith, Zebra, apple, banana, Äpfel  (every uppercase before any lowercase; accent last).
--- result ---
name
Apple
Smith
Zebra
apple
banana
Äpfel

=== 02-exact-match-miss.rq ===
Finding #2a - KEYWORD '=' is verbatim: lowercase "smith" does NOT match indexed "Smith".
Expect: 0 rows.
--- result ---
name

=== 03-exact-match-hit.rq ===
Finding #2b - exact-case "Smith" DOES match (control for 02).
Expect: 1 row -> Smith.
--- result ---
name
Smith

=== 04-analyzer-inert.rq ===
Finding #4 - the analyzer slot on a KEYWORD field is inert. field:nameLC has a
LowerCaseKeywordAnalyzer attached, yet sorting by it is identical to field:name.
Expect: same order as 01 -> Apple, Smith, Zebra, apple, banana, Äpfel.
--- result ---
name
Apple
Smith
Zebra
apple
banana
Äpfel

=== 05-normalizer-sort.rq ===
FEATURE (idx:normalizer) - sort by field:nameCI, which declares idx:normalizer [LowerCaseKeywordAnalyzer].
PRE-WORK  expect: raw order (same as 01)  -> Apple, Smith, Zebra, apple, banana, Äpfel
POST-WORK expect: case-insensitive order  -> apple, Apple, banana, Smith, Zebra, Äpfel (the two apples adjacent)
--- result ---
name
Apple
Smith
Zebra
apple
banana
Äpfel

=== 06-normalizer-match.rq ===
FEATURE (idx:normalizer) - exact '=' match on field:nameCI with lowercase "smith".
PRE-WORK  expect: 0 rows (normalizer ignored -> verbatim, case-sensitive)
POST-WORK expect: 1 row -> Smith (query value normalized to match indexed term)
--- result ---
name

Compare the above against keyword-sort-demo/README.md (Expected results).
```
