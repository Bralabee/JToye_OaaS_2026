# Test manifest — the Null Island falsification arm

Declares **8** rows because `postcode-centroids-nullisland.csv` genuinely contains eight. The
importer must still refuse the load: the count matching is not permission to store a `(0,0)` row.

Stating 8 rather than 7 is deliberate. If this said 7, the import would fail on the row-count
assertion and the test would pass for the WRONG REASON — proving only that counting works, while
the Null Island guard could be entirely absent.

| | |
|---|---|
| **Dataset** | fixture + one injected Null Island row |
| **Rows after filter** | **8** (dropped 0) |
