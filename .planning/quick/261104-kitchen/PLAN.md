# Quick 261104-kitchen: persona close-out, kitchen / orders / finance lane

Branch `fix/persona-kitchen`. Issues: 818, 820, 822, 824, 866, 858, 827, 857. Skipped by instruction: 821, 825 (owner decisions).

## Per-issue fix and test plan

| Issue | Fix | Test (written first) |
|---|---|---|
| 818 double-tap jumps to READY | per-order in-flight ref in `handleBump`, mirrored to `disabled`; released in `finally`. Server unchanged: every step is its own state-machine event, so a skipped step is already refused. | kitchen `page.test.tsx`: two taps = one POST, no mark-ready; next step allowed after the answer; guard released on failure |
| 820 row Cancel is one tap | `RejectOrdersDialog` `mode="cancel"`: required reason, single-order `POST /orders/{id}/cancel {reasonCode}` (bulk-cancel refuses CONFIRMED); 44px row actions on coarse pointer | `orders/__tests__/row-cancel.test.tsx` |
| 822 search covers one page | `q` on `GET /orders` and `GET /customers` (`ListSearch`, repository `search`), scope + explicit tenant predicate in the query, email only where visible (856), `%`/`_`/`\` escaped, 100 chars; UI debounce, in-place re-read, stale-answer guard | `OrderAndCustomerSearchIntegrationTest` (8), `ListSearchTest` (5), `server-search.test.tsx`, `customers/__tests__/search.test.tsx` |
| 824 newest-first, lines | grid oldest-first; summary uses `unitCount`, falls back to lines | kitchen `page.test.tsx` |
| 866 Live while dropping | `lastSyncedAt` advances only after the frame's order loads; failure sets `syncFailed` and re-reads the board | kitchen `page.test.tsx` |
| 858 four taps to 86 | 44px `role=switch` on each product row, `PATCH {available}`, optimistic + rollback | `product-row-availability.test.tsx` |
| 827 finance range | presets + From/To feeding summary, list and export; in-place reload | `finance-range.test.tsx` |
| 857 timeline | `GET /orders/{id}/timeline` over `orders_aud`+`revinfo`+`user_directory`; `OrderTimeline` + CSV | `OrderTimelineServiceTest` (6), `OrderTimelineIntegrationTest` (3), `order-timeline.test.tsx` |

## Threat notes
- Search: scope (GROUP_ADMIN / grant set / STAFF on named shop) is in the query, not a post-filter; explicit `tenant_id` on top of RLS; term is data (LIKE metacharacters escaped) and bounded; email matched only where `CustomerContactScope` shows it, so the box is not an email oracle (856).
- Timeline: STAFF on the order's shop (same as order detail), foreign/unknown id is 404; raw JWT subject never returned; the CSV defuses spreadsheet formulas in a name.
- No migration (V90 is taken by #1115; V91-V92 reserved; this lane needs none). No new third-party script. No client-held tenant/shop authority.
- MCP: search and timeline are read capabilities of the dashboard; an MCP `read_orders` `q`/timeline is out of scope here (agents can already read by order id / shop) and is recorded as a follow-up, not decided.
