---
quick_id: 261004-nsn
status: complete
date: 2026-10-04
---
# Summary: issue de-dup searches by title (#885)

**Change:** three de-dup sites now query `gh issue list --state open --search "in:title \"${TITLE}\""
--limit 1000`. The sites are the e2e-nightly escalation and the base-image-freshness findings and
VOID steps. The exact-title jq `select` stays as the filter. Each site has a comment saying why.

**Proof.** Each step's `run` script was extracted from the YAML and its de-dup part executed against
the live repo (177 open issues), with TITLE = "Nightly E2E is failing — the full-suite lane is dark":

| site | origin/main | branch |
|---|---|---|
| e2e-nightly escalation | `existing=[]` (misses #683, would file a duplicate) | `existing=[683]` |
| base-image-freshness findings | `existing=[]` | `existing=[683]` |
| base-image-freshness VOID | `existing=[]` | `existing=[683]` |

**Controls:**
- With the substring title "Nightly E2E is failing", all 3 branch sites give `existing=[]`.
- The search alone, without the exact filter, gives `[683]`. So the exact filter is what stops a false match.

**Checks:** actionlint rc=0, both YAML files parse, and `check-gate-enforcement.sh` rc=0.

**Not proven here:** the next real scheduled failure is the in-situ test. The token in CI is
`GITHUB_TOKEN` instead of a user token; both can use the search API on this repo, but that was not
run from CI.

**Deviation:** planned and executed inline. The fix was specified by the issue.
