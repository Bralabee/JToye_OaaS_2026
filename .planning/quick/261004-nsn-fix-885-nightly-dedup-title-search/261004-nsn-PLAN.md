---
quick_id: 261004-nsn
slug: fix-885-nightly-dedup-title-search
date: 2026-10-04
---
# Fix #885: issue de-dup only sees the 100 newest open issues

The escalation de-dup in `.github/workflows/e2e-nightly.yml` lists `--state open --limit 100` and
filters by exact title in jq. With 177 open issues, #683 (the nightly tracker) is outside that
window, so the 2026-10-04 nightly filed #883 as a duplicate. The same shape is in
`.github/workflows/base-image-freshness.yml` at two sites (findings, VOID), so fix the class.

## Task 1: narrow server-side, keep the exact filter
- files: `.github/workflows/e2e-nightly.yml`, `.github/workflows/base-image-freshness.yml`
- action: at all three sites add `--search "in:title \"${TITLE}\""` and raise `--limit` to 1000.
  Keep the jq exact-title `select`, the stderr separation and the rc handling unchanged.
- verify: extract each step's rendered `run` script from the YAML (main and branch) and execute
  its de-dup part with TITLE = #683's title. Main must return empty at all 3 sites and the branch
  `683` at all 3. Control: with a substring title, the branch returns empty, while the search
  alone without the exact filter returns 683. Also run actionlint and the YAML parse.
