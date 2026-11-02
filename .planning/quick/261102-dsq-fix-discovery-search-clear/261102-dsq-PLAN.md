---
phase: quick
plan: 261102-dsq
type: execute
wave: 1
depends_on: []
files_modified:
  - frontend/app/shop/shop-discovery-client.tsx
  - frontend/app/shop/__tests__/shop-discovery-client.test.tsx
  - docs/metrics.json
  - README.md
  - CLAUDE.md
  - AGENTS.md
  - docs/CHANGELOG.md
  - .planning/quick/261102-dsq-fix-discovery-search-clear/evidence/red-green.txt
autonomous: true
requirements: [QUICK-261102-dsq]
branch: "fix/discovery-search-clear, from origin/main 9b737e61, in an isolated worktree (Lane A of a five-lane run)"
decision: "none needed: two defects reported by an external tester on /shop, fixed inside the discovery island"

estimate:
  tokens: 60000
  tasks: 2
  confidence: high

must_haves:
  truths:
    - "With text in the /shop search box Chrome paints ONE clear X, not the native cancel button beside the island's 'Clear search' button. The input stays type=search. Proven by before/after box screenshots and a Jest arm that is RED on the unfixed class list."
    - "The backspace glitch is characterised in a real browser BEFORE any code change: input value, URL and render state sampled per key and per frame, at three cadences and with 250 ms simulated latency, from a typed query and from a /shop?q=jollof deep link."
    - "Backspacing a query to empty fetches no intermediate fragment, never renders the 'No kitchens found' empty state on the way, ends with value '', no q in the URL and no re-seed, from a typed query and from a deep link. Both Jest arms are RED on the unfixed component and GREEN on the fix."
    - "Clearing (X, chip toggle, 'Browse all kitchens', backspace to empty, a URL change) is applied at once, not after the debounce. A term the customer pauses on is still searched, once (POSITIVE ARM)."
    - "The R-02 block passes unmodified. The R-09 block keeps its claim (two requests in flight, the older settling last) with its harness waiting for the debounce."
    - "Full Jest suite, npm run lint (0 errors) and npm run build pass. docs/metrics.json, README.md, CLAUDE.md and AGENTS.md agree (docs-freshness.sh and check-doc-metrics.sh rc 0), each shown failing first."
  artifacts:
    - path: "frontend/app/shop/shop-discovery-client.tsx"
      provides: "SEARCH_DEBOUNCE_MS, resultsQuery, applyQueryNow, native cancel button hidden"
      contains: "[&::-webkit-search-cancel-button]:appearance-none"
    - path: "frontend/app/shop/__tests__/shop-discovery-client.test.tsx"
      provides: "the search box clears cleanly (2026-11-02 tester), 4 cases"
    - path: ".planning/quick/261102-dsq-fix-discovery-search-clear/evidence/red-green.txt"
      provides: "both directions of every check, and the before/after browser recordings"
---

<objective>
Fix two defects an external tester found on the public kitchen directory `/shop`
(`frontend/app/shop/shop-discovery-client.tsx`):

1. Two clear "X" buttons in the search box when it has text: `type="search"` makes Chrome and
   Safari paint a native cancel button beside the island's own `aria-label="Clear search"` button.
2. A "glitch" when backspacing the query to empty instead of using the X.

Out of scope (other lanes): HANDOFF.md, `frontend/app/page.tsx`,
`frontend/components/public/public-footer.tsx`, `core-java/`, `infra/`, `k8s/`, `design/`.
</objective>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Reproduce and characterise the backspace glitch, then RED tests</name>
  <action>
- Run the frontend from the worktree (`next dev -p 3100`, NEXT_PUBLIC_API_URL at the running
  Compose core-java over HTTP; no `docker compose` command from the worktree).
- Drive it with Playwright (evidence/playwright-record.js): type "pounded yam", wait, backspace to
  empty; deep-link /shop?q=jollof and backspace to empty. Sample value, location.search and render
  state per key and per frame; repeat at 450 and 700 ms per key and with 250 ms simulated latency.
  Save the JSON and box screenshots as the BEFORE record.
- Read the R-02 and R-09 tests first, then add four Jest cases that encode the measured symptom and
  the one-X contract, and run them against the unfixed component: they must fail, for the
  measured reason.
  </action>
  <verify>npx jest app/shop/__tests__/shop-discovery-client.test.tsx  # expect 4 failed on the unfixed component</verify>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Fix, GREEN, browser AFTER run, gates, records</name>
  <action>
- Debounce the FETCH (SEARCH_DEBOUNCE_MS = 300) through a separate `resultsQuery`; apply a cleared
  box and every deliberate choice at once (`applyQueryNow`); the summary and empty-state copy read
  the term the results are for. Hide `::-webkit-search-cancel-button` and
  `::-webkit-search-decoration` with Tailwind arbitrary variants, keeping type=search.
- Adjust only the R-09 harness for the debounce, keeping its claim.
- Run the file, the full Jest suite, lint, build; re-run the Playwright recorder as AFTER.
- docs-freshness.sh --write, check-doc-metrics.sh, update the quoted counts.
- CHANGELOG top entry; branch-behind-base check; PR; add the PR number to the heading.
  </action>
  <verify>npx jest; npm run lint; npm run build; scripts/docs-freshness.sh; scripts/check-doc-metrics.sh; scripts/check-branch-behind-base.sh</verify>
</task>

</tasks>
