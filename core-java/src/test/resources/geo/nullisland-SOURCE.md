# Test manifest — the Null Island falsification arm

Declares **9** rows because `postcode-centroids-nullisland.csv` genuinely contains nine. The
importer must still refuse the load: the count matching is not permission to store a `(0,0)` row.

Stating 9 rather than 8 is deliberate. If this said 8 — the row count of
`postcode-centroids-fixture.csv` — the import would fail on the row-count assertion and the test
would pass for the WRONG REASON, proving only that counting works while the Null Island guard
could be entirely absent.

**This file must always declare exactly ONE more row than `fixture-SOURCE.md`.** Two tests depend
on that relationship: the Null Island arm needs this manifest to AGREE with the nullisland CSV,
and `rowCountMismatchAborts` points this manifest at the *fixture* CSV precisely so the two
DISAGREE. 33-08 added a row to both CSVs and had to move both manifests together — a fixture row
added to only one of them silently turns the mismatch arm into a no-op.

| | |
|---|---|
| **Dataset** | fixture + one injected Null Island row |
| **Rows after filter** | **9** (dropped 0) |
