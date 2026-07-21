# keyword-multivalued-sort — verified results

Captured 2026-07-17 15:43:46

```
=== 01-multivalued-sort.rq ===
Multi-valued + normalized KEYWORD sort (SortedSet path). One name per entity in this group.
Expect: apple, Zebra, Äpfel  (case-insensitive). Raw bytes would give Zebra, apple, Äpfel.
--- result ---
name
apple
Zebra
Äpfel

=== 02-multivalued-min.rq ===
Genuine multi-valued entity: ascending sort uses the normalized MIN per entity.
eOne has names {Yak, apple} -> normalized min "apple"; eTwo has "Beta" -> "beta".
Expect entity order: http://example.org/eOne then .../eTwo  (apple < beta).
Raw bytes would pick eOne's min as "Yak" (0x59) < "Beta" (0x42)? -> eTwo first (wrong).
--- result ---
s
http://example.org/eOne
http://example.org/eTwo

Compare the above against keyword-multivalued-sort/README.md (Expected results).
```
