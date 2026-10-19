# Phase 29 orchestrator ledger (resume point for a compacted or fresh session)

Updated: 2026-10-19. Running `/gsd-execute-phase 29` on branch `phase-29-deployable-staging` (pushed to f684f4f7).
The authority for "done" is a plan's SUMMARY.md, never this file. Re-measure before trusting any line here.

## Position
- Done (SUMMARY present): 29-01 .. 29-14 (29-14 verified by orchestrator 2026-10-19: app 200, realm jtoye-staging OIDC 200, 7 pods Ready, 11 NetworkPolicies, prod LE certs under default-deny).
- 29-14 (tracer, live deploy, owner-approved): Task 1 done (f604776c f124932d 3d9caa82 f684f4f7); Tasks 2-4 running as a
  background executor, DEPLOY_SHA=a784e7e2421c7aba164f3e7db858b1861b7df6be (CI run 39220625340 green, images in GHCR,
  frontend bundle baked to api-staging, measured).
- Nothing from 29-15 on may start until 29-14 is verified (tracer gate).

## Owner rulings this session (2026-10-19)
- Branch phase-29-deployable-staging; 29-09 option-a (billable estate ~£131-145/mo); Network Contributor on outbound IP granted.
- Stripe step automated from .env sk_test_ key; endpoint we_1USBcpDpS9ICNeRt3kIwcIvC pinned 2026-09-30.endive.
- Repo vars point at staging; branch pushes approved as they come.
- 29-14 live deploy approved (migration, realm import, seed, prod LE certs, falsification runs).
- PARALLEL LANES approved (no objection): lane A = this orchestrator, the ONLY one that deploys / applies to the cluster:
  29-15 -> 29-22 -> 29-18 -> 29-26 -> 29-23 -> 29-24 -> 29-25 -> 29-27 -> 29-28, plus 29-17 (D-33: 29-26 needs the 29-18 seed order).
  Lane B = background executors in git worktrees (reset to the phase branch first; they can branch from main), NO
  deploy.sh / kubectl apply: 29-16 first (unblocks 29-22), then 29-21 (before 29-24, both edit ci-cd.yaml), 29-19 (needs
  the estate 24 h old: not before 2026-10-20), 29-20 (PITR into a separate server).
  Orchestrator merges lane B, then updates STATE/ROADMAP; regenerate goldens / metrics.json, never hand-merge them.
- RULED: move 29-26 up to right after 29-22 (order 22 -> 26 -> 23 -> 24 -> 25); recorded as D-33 by a planner revision.
- RULED: testers read staging mail via a public, basic-auth Mailpit UI at https://mail-staging.olajay.co.uk (new task in
  29-22; credential generated into Key Vault; owner adds the A record mail-staging -> 51.105.43.14 at Namecheap).
  Plan revision committed 9c8cdc42 (D-33; 29-22 now 4 tasks; waves 26=10, 23=11, 24=12, 25=13, 27=14, 28=15).
  TODO after 29-14 completes: 29-14-PLAN.md line 92 says "(29-25)" for the customer realm work; it means 29-26.
- DNS mail-staging -> 51.105.43.14 measured at dns1+dns2 2026-10-19 (owner added it).

## Live snapshot mid-29-14 (2026-10-19, before the executor finished)
- app-staging 200 with a publicly trusted LE cert; api-staging 503 (core-java restarting); auth-staging 404 (keycloak
  CrashLoopBackOff); 3 Certificates Ready in jtoye-staging. Bare app./api./apex: no DNS (Phase 32, by design).

## Traps carried
- kube-context: ALWAYS jtoye-stg-aks. Current-context jtoye-staging is a DEAD cluster; never use or change it.
- Azure: pin --subscription c483d353-5f61-4587-a790-addb9ab5fb94; the CLI default is the employer's (DO NOT TOUCH).
- Python gates: PATH=/home/sanmi/miniconda3/envs/jtoye-ops/bin:$PATH (host shim VOIDs bare python3).
- HTTP-01 behind default-deny: see memory trap_http01_solver_unproven_behind_default_deny; retire the README section once
  29-14 evidence records a cert issued with the policies applied.
- Executor dispatch template: scratchpad dispatch-common.md (session-local; its content = the rules above plus GSD
  execute-plan context files, root-pin before writes, stage by name, no push, no AI attribution).

## Update 2026-10-19 (after 29-14)
- HTTP-01 behind default-deny PROVEN; README section + memory retired (aba5e2e8).
- D-34 (7a4cd4bb): nodepool1 scaled 3 -> 4 B2s (owner-approved; az guard needs the literal [az-ok] token in the command);
  provision script NODE_COUNT=4; estate ~£157-171/mo, over D-03 ceiling by owner choice. Storefront image defect fixed
  in-phase as new plan 29-29 (planner writing it; must run before 29-26).
- gitleaks allowlist for 29-14 k8s object UIDs (36f199a7); pushed 36f199a7.
- RUNNING: lane A 29-15 (main checkout, deploys at image a784e7e2); lane B 29-16 (worktree, base aba5e2e8) and 29-21
  (worktree, base 36f199a7; GitHub/Azure writes come back as a checkpoint); planner writing 29-29-PLAN.md.
- Merge lane-B branches into phase-29-deployable-staging, then STATE/ROADMAP + regenerate metrics/goldens.
- Open from 29-14: four seeded dishes still drafts (matters for 29-18); fresh-cluster deploy needs two passes around
  realm import; CI deploy-staging digest-parity step red until 29-21.
- 29-16 DONE and merged (merge + 190838ef roadmap 15/28). Images built from ed07f053+ require STARTTLS: staging mail fails until 29-22 by design. STATE.md not yet updated for 29-16 (lane A owns it; add at next lane-A boundary).
- 29-29 planned + committed 93a63f69 (wave 7; 26/27 depend on it). Lane B running 29-29 Tasks 1-2 (worktree base 93a63f69), returns at Task 3 checkpoint with GREEN_SHA; then orchestrator merges, pushes, waits for CI images, lane A runs Task 4 deploy.
- 29-15 checkpoint resolved: option A (deploy a784e7e2 + rollout restart core-java); core-java runs one STARTTLS literal behind the branch until 29-22, by design.
- 29-21 DONE: merged 43125fbb; owner-approved writes applied (KV Reader for jtoye-ci; GH env staging main-only, no reviewers; AZURE_CLIENT/TENANT/SUBSCRIPTION_ID env vars); b162ca2d. DEPLOY_STAGING_ENABLED still unset (29-27). STATE.md needs 29-21 at next lane-A boundary.
- 29-15 DONE (9fb61b70 STATE/ROADMAP); orchestrator re-verified: pod TRUSTED_PROXY_CIDRS=10.244.0.0/16, check-staging-edge.sh --context jtoye-stg-aks rc 0 (5/5). Pushed 9fb61b70; CI run 39244159587 building images (watcher bly6u5d28) — these images carry 29-16 STARTTLS-required => deploy them only with/after 29-22 Mailpit TLS.
- Lane A now: 29-17 (netpol enforcement proof, no redeploy). Next lane A: 29-22 at image 9fb61b70 (or later), then 29-18, 29-29 Task 4, 29-26. 29-19 not before 2026-10-20 (estate 24 h).
- 29-17 DONE (0543d8d7); verified: API 200, 0 probe pods, live core-java-allow == render (cmp 0).
- CI 39244159587 (9fb61b70) RED: Playwright count oracle 189 vs 190 (staging-gated spec not listed). Fixed oracle to list with STAGING_E2E=1 (efab17d7, pushed). New CI 39247010630 (watcher bhb1yss2a). Old watcher bly6u5d28 will report the red run — expected.
- Lane A now: 29-22 at image efab17d7 (waits for CI images). 29-29 lane B worktree is based on 93a63f69 (lacks the oracle fix; merge brings it; its new staging spec will count correctly).
- 29-29 Tasks 1-2 DONE in lane B (GREEN_SHA 40ceffbc). To avoid perturbing 29-22 (deploying efab17d7), its branch was merged with efab17d7 in the worktree (6e95ee6c) and pushed as SIDE BRANCH phase-29-storefront-images; CI run 39248332647 (watcher b51otrqvd). Worktree .claude/worktrees/agent-ae894c6292ea59a30 KEPT until merged. After 29-22 completes: merge worktree-agent-ae894c6292ea59a30 into phase-29-deployable-staging, regenerate counts if conflicted, then lane A runs 29-29 Task 4 with IMAGE_SHA=6e95ee6c44e357e3ff88964ed9674bf066fd7b61 (contains GREEN_SHA + a784e7e2; manifests from the phase branch). Delete the side branch afterwards.
- CI green + 3 images present for efab17d7 (29-22) and 6e95ee6c (29-29 side branch). 29-29 Task 4 waits for 29-22 to finish.
- 29-22 DONE (974dcddf); verified: mail-staging 401 no/wrong auth, http 308, LE YR2 cert to 2027-01-17, 1025 closed, 8 pods Running on efab17d7. OPEN: Mailpit reads its cert only at start -> mail+health break ~2027-01-17 unless restarted/reloader (29-23 alert should cover; record in 29-27 dispositions). staging-secrets.sh --selftest in no CI job; truststore init image outside digest parity.
- 29-29 merged (Tasks 1-2) + gitleaks allowlist for Mailpit message id (25727576, pushed). Side branch deleted; worktree removed. Lane A now: 29-29 Tasks 3-4 deploy at 6e95ee6c. Then 29-18 (Stripe; 4 seeded dishes are drafts), then 29-26 -> persona-ready. 29-19 on/after 2026-10-20 (lane B).
- 29-29 DONE (4fc07c3c/2515c6d8); verified: staging runs 6e95ee6c; anonymous /public/shops/staging-test-kitchen/products Pelau imageUrl = media/14add455 WebP, fetch 200 image/webp 6538B, no quarantine strings. Rollback = deploy.sh at efab17d7. Not pushed yet.
- Pushed 2515c6d8. OWNER RULING 2026-10-19: 29-18 may make Stripe TEST-mode writes it lists (test PaymentIntents/confirm, trigger test deliveries to existing endpoint we_1USBcpDpS9ICNeRt3kIwcIvC); sk_test_ only, no endpoint/key/product changes. Lane A now: 29-18.
- 29-18 DONE (fea03fd4); verified in Stripe TEST: pi_3USHS1… succeeded 950 gbp livemode=false, metadata order ORD-1AAC0CDE-20261019-4D3B23A2; 0 relay pods; .gitleaksignore pins 2 STUB fixture lines of commit 9e13b945 (not the real key). Pushed.
- Next lane A: 29-26 (persona unlock). Lane B candidates: 29-20 PITR drill (Azure restore = live mutation, needs owner OK), 29-19 cost (>= 2026-10-20).
- OWNER 2026-10-19: 29-20 drill approved (temp PITR server, its firewall rule, 2 markers); WORM lock = decide at checkpoint (NOT pre-approved). Running: lane A 29-26; lane B 29-20 (worktree, base fea03fd4). Watch for orphan jtoye-stg-pg-drill-* servers if 29-20 dies: az postgres flexible-server list --subscription c483d353-5f61-4587-a790-addb9ab5fb94 -g jtoye-rg.
- 29-26 DONE (5d85ff4c) => PERSONA-READY. Verified: both realms OIDC 200, customer account page 200, app /, /shop/staging-test-kitchen, /auth/signin 200. gitleaks allowlist for 29-26 Mailpit listing (cross-line fragment) a77b8e4d, pushed. Added evidence-hygiene rule to dispatch-common.
- Testers: customer sign-up = email+name, then verify email (Mailpit) and set password; closing the tab mid-way => use Forgot Password. Probe customer customer-299-probe.staging@olajay.co.uk exists. Seed order now CONFIRMED.
- 29-20 Tasks 1-2 DONE + merged (drill run 20261019144933 green; temp server deleted, verified only jtoye-stg-pg + snackpass-pg(Stopped, other project) remain). Task 3 PENDING. OWNER 2026-10-19: WORM lock AFTER a verified backup; first CronJob run 02:00 UTC 2026-10-20. Tomorrow: grant owner (admin@jtoyedigital.co.uk) Storage Blob Data Reader scoped to container jtoye-db-backups ONLY (owner-approved, time-limited: remove after check, record both), list the 02:00 backup, check job log, then bring the lock command (runbook 4.4) to the owner. Then write 29-20 SUMMARY + STATE/ROADMAP. Recovery finding: restored server has no firewall rules and empty azure.extensions (uuid-ossp) - in runbook.
- 29-23 checkpoint: monitoring live (8/8 targets up, rules md5 == compose, exporter fail arm ok; jtoye_monitor role pg_monitor-only; node 2 only 99Mi free). OWNER: approved ONE guest collection order via seed-order-metric.sh to create the order counter. OPEN: counter vanishes on each core-java restart -> quiet staging re-reds NoOrdersCreated/metric gate after every deploy; decide in 29-25/29-27.
- 29-23 DONE (1640fb7b, pushed); verified prometheus + 2 exporters Running, no unhealthy pods, app/api 200. Order ORD-…750F976B placed (owner-approved). Lane A now: 29-24 (Alertmanager+Grafana build; Zoho app password is 29-25 / owner via staging-secrets --set-external on stdin). Remaining after: 29-25 (real-inbox proof, checkpoint), 29-19 + 29-20 Task 3 tomorrow, 29-27, 29-28.
- 29-24 DONE (4945dc00, pushed). NOTE: staging deploys now REFUSED by preflight (12 gaps) until 29-25 Tasks 1-2 write the 6 KV objects + grants. Owner must set alertmanager-smtp-credentials--{from,username,password,to} via staging-secrets.sh --set-external (hidden prompt, own terminal). Found: STOMP Grafana dashboard references an unprovisioned datasource uid (empty in compose too) -> deferred-items; local compose Alertmanager stale v0.27.0 vs pin v0.34.1.
- 29-25 prep DONE: alertmanager-smtp-credentials--{from,username,password,to} in KV == .env values (username ola.ibitoye@, to ola.ibitoye@, from alerts@); Zoho STARTTLS login OK with the KV values (wrong-pw control 535). Owner-typed password (wrong, 535) superseded by a new version. Next: dispatch 29-25.

## Persona swarm (owner-run, separate session) — started ~17:45Z 2026-10-19
- Owner is running a MiroFish-style persona interrogation of live staging. Do NOT deploy/roll staging while it runs;
  29-28's merge deploy must happen after it ends. Its findings may feed 29-27's disposition record.
- Guardrails for any FUTURE run (generate the brief fresh when asked; facts change after 29-28):
  public URLs only (app-/api-/auth-/mail-staging, grafana-staging after 29-25); no kubectl/az/deploys (orchestrator
  owns the cluster); a handful of concurrent personas, not a load test (edge ~50 in-flight/client/Traefik replica,
  app ~100 req/min/tenant; a core-java restart resets the order counter); no repeated wrong passwords (Keycloak
  lockout); Stripe test card 4242 only; synthetic data only (mail view is shared); findings follow the 2026-10-03
  persona-run method (epic #880, evidence repo ~/IdeaProjects/JToye_OaaS_2026-evidence/) and the admission test
  (issue only if gate/data/observed). Accounts: customers self-register (verify in mail view); vendors share
  vendor.staging@olajay.co.uk (KV staging-seed--test-vendor-password) or get staff invites; mail view user
  staging-tester (KV mailpit-ui-credentials--password). Read KV values in the owner's terminal, never via `!`.
- 29-25 was told (SendMessage) to expect swarm traffic and record real alerts as observations, not failures.

## END OF DAY 2026-10-19 — RESUME HERE (fresh session: `/gsd-execute-phase 29`, then read this file top to bottom)
Branch phase-29-deployable-staging pushed at 1956e73d. 25/29 plans have SUMMARY (all but 29-19, 29-20, 29-27, 29-28).
Staging runs app images 6e95ee6c44e357e3ff88964ed9674bf066fd7b61; 13 pods Running in jtoye-staging, none unhealthy cluster-wide.
29-25 DONE (verified by orchestrator: grafana-staging /login 200, anonymous /api 401, LE cert; both DPLY-03 gates rc 0 at
17:55Z/18:09Z; owner confirmed the alert email; NoOrdersCreated routed silent). 29-25 fixed two gate defects (preflight
email validators; liveness passing with Zoho broken) — see its SUMMARY.

Next, in order:
1. 29-20 Task 3 (lane B or A): after 02:00 UTC 2026-10-20 — check `kubectl --context jtoye-stg-aks -n jtoye-staging get jobs`
   for the first pg-backup run and its log; grant admin@jtoyedigital.co.uk Storage Blob Data Reader scoped to container
   jtoye-db-backups ONLY (owner-approved, time-limited: remove after, record both; needs `# [az-ok]`); list the blob; then
   ASK the owner to run/approve the lock (runbook §4.4 command, etag-pinned). Then 29-20 SUMMARY + STATE/ROADMAP.
2. 29-19 cost checkpoint: not before ~10:00Z 2026-10-20 (estate 24 h old). Includes the Free-tier Uptime SLA meter question
   and snackpass-pg (free tier ends 2026-11-08). D-34 raised the ceiling for the 4th node (~£26/mo).
3. 29-27 close-out: disposition record per deploy-knot issue. Feed in: deferred-items.md (29-24/29-25 items: deploy.sh doesn't
   wait for monitoring rollouts; secrets selftests not in CI; frontend has no scrape job (#98 crit 1); Mailpit cert read
   only at start → mail/health break ~2027-01-17 unless restarted/reloader; STOMP dashboard datasource uid; truststore
   init image outside digest parity; order counter vanishes on core-java restart; check-keycloak-logout-uri.sh comments
   still say "Open #299"; 29-14-PLAN.md line 92 says 29-25 for the realm work (means 29-26); edge rate limits are per
   Traefik replica (~2x effective)), plus any persona-swarm findings the owner brings.
4. 29-28: merge to main; DEPLOY_STAGING_ENABLED flipped AT the merge; CI deploy-staging is the first real proof CI can log
   in as jtoye-ci; runtime parity = pods run the merge commit's images. NOT while the owner's persona swarm is running.
5. Phase verification (code review, regression gate, gsd-verifier), then PR (no AI attribution in PR body; read it back).
Owner-run commands MUST start with `source ~/miniconda3/etc/profile.d/conda.sh && conda activate jtoye-ops` (pyshim).
Check `.env` and memory BEFORE asking the owner for any value (feedback memory written 2026-10-19).

## Housekeeping 2026-10-19 (end of session)
- Repo var FRONTEND_PUBLIC_API_URL = https://api-staging.olajay.co.uk (set this session for staging builds). Any PRODUCTION frontend build from main would bake the staging API origin: reconcile before any production deploy (29-28 must keep production deploy off; Phase 32 owns prod).
- Machine: claude-memory-backup.timer FAILED 2026-10-19 02:30 (deletion breaker: 26 OaaS memory files moved to memory-archive/ on 2026-10-18 12:06; limit 25). Intended curation. RESOLVED 2026-10-19 18:45Z (owner-approved): one run with MEMORY_BACKUP_MAX_DELETE=30, rc=0; backup memory/ and memory-archive/ diff -rq clean vs live; the 26 superseded backup copies kept under versions/2026-10-19/184541Z; service failed state reset. sync-claude --check: settings.json content drift (machine session only; project sessions do not capture).
- Local branch docs/29-plan-on-main: no PR, every commit already on the pushed phase branch; DELETED 2026-10-19 on owner approval after all 7 commits proved ancestors of origin/phase-29-deployable-staging (negative control rc=1); was edc2c94d, still reachable from the phase branch.
