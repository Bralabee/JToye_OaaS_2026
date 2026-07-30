# Documentation Index — J'Toye OaaS 2026

**Last reconciled:** 2026-07-26
**Milestone:** v2.3 (`vendor-ops-ai-interleaved`) — build complete, phases 21–26
**Schema:** Flyway V59 · **Test suite:** 1736 logical invocations (source of truth: `docs/metrics.json`,
enforced by the `docs-freshness` CI gate)

> Sections below are **not globally numbered**. A continuous 1..N sequence across sections has to be
> rewritten on every insertion, and it silently drifted — the previous revision had two entries
> numbered 28. Add a row to the relevant table instead.

---

## Quick Reference: which doc do I need?

| Question | Document |
|---|---|
| How do I get started? | [guides/QUICK_START.md](guides/QUICK_START.md) |
| How do I set up `.env` / what variables exist? | [guides/ENVIRONMENT_SETUP.md](guides/ENVIRONMENT_SETUP.md) |
| How do I configure services? | [config/CONFIGURATION.md](config/CONFIGURATION.md) |
| How do I test the API? | [guides/TESTING.md](guides/TESTING.md) |
| How do I perform QA testing? | [guides/QA_TEST_PLAN.md](guides/QA_TEST_PLAN.md) |
| What about dev/staging/prod? | [guides/ENVIRONMENT_STRATEGY.md](guides/ENVIRONMENT_STRATEGY.md) |
| How do I deploy to Kubernetes? | [k8s/DEPLOYMENT.md](../k8s/DEPLOYMENT.md) |
| How do I run the local k8s overlay? | [k8s/LOCAL.md](../k8s/LOCAL.md) |
| **Why was an architectural decision made?** | [architecture/decisions/](architecture/decisions/) — the ADRs |
| **What is the system topology?** | [architecture/SYSTEM_DESIGN_V2.md](architecture/SYSTEM_DESIGN_V2.md) |
| **An alert fired — what do I do?** | [runbooks/alerts.md](runbooks/alerts.md) |
| Docker networking issues? | [troubleshooting/DOCKER_IPTABLES_ISSUE.md](troubleshooting/DOCKER_IPTABLES_ISSUE.md) |
| What business model should J'Toye pursue? | [analysis/BUSINESS_MODEL_DECISION_GUIDE.md](analysis/BUSINESS_MODEL_DECISION_GUIDE.md) |
| How does J'Toye compare to Flipdish? | [analysis/flipdish-vs-jtoye-teardown.md](analysis/flipdish-vs-jtoye-teardown.md) |
| What pages exist in the frontend? | [SITEMAP.md](SITEMAP.md) |
| What's the project overview? | [README.md](../README.md) |

---

## Architecture & Decisions

| Document | Covers |
|---|---|
| [architecture/SYSTEM_DESIGN_V2.md](architecture/SYSTEM_DESIGN_V2.md) | Canonical system design — §1 is the authoritative comms topology (REST/JSON + AMQP + STOMP) |
| [architecture/API_REFERENCE.md](architecture/API_REFERENCE.md) | REST API reference |
| [architecture/SECURITY_ARCHITECTURE.md](architecture/SECURITY_ARCHITECTURE.md) | Auth model, RLS/tenant isolation, threat surface |
| [architecture/VENDOR_ONBOARDING_STATE_MODEL.md](architecture/VENDOR_ONBOARDING_STATE_MODEL.md) | The onboarding state machine — sole writer of `Shop.published` |
| [AI_CONTEXT.md](AI_CONTEXT.md) | System architecture summary for AI/agent context |

### Architecture Decision Records

| ADR | Status | Decision |
|---|---|---|
| [ADR-0001](architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md) | Accepted | Onboarding approval gates + Stripe money flow (marketplace destination charges) |
| [ADR-0002](architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md) | **Proposed** | Managed vs in-cluster manifests for stateful infra. Needs owner sign-off; the RabbitMQ half is still unbuilt |
| [ADR-0003](architecture/decisions/ADR-0003-messaging-broker-selection.md) | Accepted | Remain on RabbitMQ — Redpanda and NATS assessed and rejected, with revisit triggers |
| [ADR-0004](architecture/decisions/ADR-0004-knowledge-graph-strategy.md) | Accepted | Relational ingredient/entity graph in the existing Postgres — Apache AGE and Neo4j assessed and rejected on tenant-wall grounds, with revisit triggers |

---

## Getting Started

| Document | Covers |
|---|---|
| [../README.md](../README.md) | Project overview |
| [guides/QUICK_START.md](guides/QUICK_START.md) | Complete getting-started guide |
| [guides/ENVIRONMENT_SETUP.md](guides/ENVIRONMENT_SETUP.md) | Environment configuration, all platforms |
| [guides/DOCKER_QUICK_START.md](guides/DOCKER_QUICK_START.md) | Docker setup |
| [setup/SETUP.md](setup/SETUP.md) · [setup/INTELLIJ_SETUP.md](setup/INTELLIJ_SETUP.md) | Machine/IDE setup |

## Configuration

| Document | Covers |
|---|---|
| [config/CONFIGURATION.md](config/CONFIGURATION.md) | Detailed configuration reference |
| [config/CREDENTIALS.md](config/CREDENTIALS.md) | Development credentials |
| [guides/USER_GUIDE.md](guides/USER_GUIDE.md) | Application usage |

## Testing & Verification

| Document | Covers |
|---|---|
| [guides/TESTING.md](guides/TESTING.md) | Comprehensive testing guide with API examples |
| [guides/QA_TEST_PLAN.md](guides/QA_TEST_PLAN.md) | Detailed QA testing procedures |
| [guides/TESTING_GUIDE.md](guides/TESTING_GUIDE.md) | Automated testing |
| [guides/APPLICATION_VERIFICATION.md](guides/APPLICATION_VERIFICATION.md) | End-to-end application verification |
| [reports/FRESH_CLONE_TEST_RESULTS.md](reports/FRESH_CLONE_TEST_RESULTS.md) | First-time clone experience results |

## Deployment & Operations

| Document | Covers |
|---|---|
| [guides/DEPLOYMENT_GUIDE.md](guides/DEPLOYMENT_GUIDE.md) | Production deployment |
| [guides/ENVIRONMENT_STRATEGY.md](guides/ENVIRONMENT_STRATEGY.md) | Dev/staging/prod strategy |
| [../k8s/DEPLOYMENT.md](../k8s/DEPLOYMENT.md) | Kubernetes deployment + the runtime-parity gates |
| [../k8s/QUICK_START.md](../k8s/QUICK_START.md) | Fast path to a running cluster |
| [../k8s/LOCAL.md](../k8s/LOCAL.md) | The committed local-k8s overlay runbook (what local does and does **not** prove) |
| [../k8s/PRODUCTION_READINESS_REPORT.md](../k8s/PRODUCTION_READINESS_REPORT.md) | K8s production-readiness assessment |
| [reports/PRODUCTION_READINESS_REPORT.md](reports/PRODUCTION_READINESS_REPORT.md) | Application production checklist |

### Runbooks

| Runbook | Covers |
|---|---|
| [runbooks/alerts.md](runbooks/alerts.md) | Every alert, what it means, what to do |
| [runbooks/backups.md](runbooks/backups.md) | Backup + restore drill |
| [runbooks/sealed-secrets.md](runbooks/sealed-secrets.md) | Secret names, keys, and the sealing workflow |

## Reference

| Document | Covers |
|---|---|
| [CHANGELOG.md](CHANGELOG.md) | Version history (append-only) |
| [metrics.json](metrics.json) | **Single source of truth** for test/schema counts; enforced by `scripts/docs-freshness.sh` |
| [SITEMAP.md](SITEMAP.md) | Frontend page map (public, storefront, dashboard, onboarding) |
| [api/README.md](api/README.md) | API docs + `openapi-snapshot.json` |
| [idempotency.md](idempotency.md) | The uniform `Idempotency-Key` contract |
| [security-scopes.md](security-scopes.md) | OAuth scope model / least-privilege credentials |
| [ppds-label-markup.md](ppds-label-markup.md) | PPDS / Natasha's Law allergen label markup |
| [vendor-onboarding-research.md](vendor-onboarding-research.md) | Onboarding research background |
| [integration/motion-foundation-integration.md](integration/motion-foundation-integration.md) | Motion/animation foundation integration |
| [CREDITS-demo-images.md](CREDITS-demo-images.md) | Attribution for demo imagery |

## Analysis (Deep Dive)

| Document | Covers |
|---|---|
| [analysis/README.md](analysis/README.md) | Index for this directory |
| [analysis/PROJECT_DEEP_DIVE.md](analysis/PROJECT_DEEP_DIVE.md) | Architecture overview, domain model, security, infrastructure |
| [analysis/CORE_JAVA_CATALOG.md](analysis/CORE_JAVA_CATALOG.md) | Every class, config, migration and test in the Spring Boot backend |
| [analysis/EDGE_GO_CATALOG.md](analysis/EDGE_GO_CATALOG.md) | Go gateway endpoints, circuit breaker, JWT, WhatsApp webhook |
| [analysis/FRONTEND_CATALOG.md](analysis/FRONTEND_CATALOG.md) | Next.js pages, components, auth flow, types, tests |
| [analysis/INFRASTRUCTURE_CATALOG.md](analysis/INFRASTRUCTURE_CATALOG.md) | Docker, K8s, CI/CD, scripts, monitoring, backups |
| [analysis/GAPS_AND_IMPROVEMENTS.md](analysis/GAPS_AND_IMPROVEMENTS.md) | Discrepancies, feature gaps, improvement opportunities |
| [analysis/REMEDIATION-BACKLOG-2026-07-08.md](analysis/REMEDIATION-BACKLOG-2026-07-08.md) | Prioritised P0–P3 backlog from the 2026-07-08 four-agent audit |
| [analysis/MESSAGING-BROKER-EVALUATION-2026-07-26.md](analysis/MESSAGING-BROKER-EVALUATION-2026-07-26.md) | RabbitMQ vs Redpanda vs NATS assessed against the real topology; the five messaging-layer defects; the verified RLS-vs-logical-decoding finding |
| [analysis/BUSINESS_MODEL_DECISION_GUIDE.md](analysis/BUSINESS_MODEL_DECISION_GUIDE.md) | Authoritative business model, evidence boundaries, 90-day validation gates |
| [analysis/flipdish-vs-jtoye-teardown.md](analysis/flipdish-vs-jtoye-teardown.md) | Flipdish vs J'Toye feature teardown (29 features, 8 categories; live at `/competitive`) |
| [analysis/MCKINSEY_ANALYSIS.md](analysis/MCKINSEY_ANALYSIS.md) | Strategic situation/complication/horizons assessment |
| [analysis/ENTERPRISE_STRATEGIC_ANALYSIS.md](analysis/ENTERPRISE_STRATEGIC_ANALYSIS.md) | 16-framework enterprise analysis (SWOT, Porter, PESTLE, TOGAF, Wardley, …) |

## Security & Audit

| Document | Covers |
|---|---|
| [security/PII-EXPOSURE-ASSESSMENT-2026-07-08.md](security/PII-EXPOSURE-ASSESSMENT-2026-07-08.md) | PII exposure assessment |
| [reports/SECURITY_AUDIT_REPORT.md](reports/SECURITY_AUDIT_REPORT.md) | Security assessment |
| [audit/COUNCIL-AUDIT-2026-04-27.md](audit/COUNCIL-AUDIT-2026-04-27.md) | Ten-discipline council audit |
| [audit/REMEDIATION-PLAN-2026-04-27.md](audit/REMEDIATION-PLAN-2026-04-27.md) | The remediation plan derived from it |
| [audit/sources/](audit/sources/) | 10 per-discipline source reports feeding the council audit |
| [audit/remediation/](audit/remediation/) | 8 per-discipline remediation write-ups |
| [legal/derivation-clause.md](legal/derivation-clause.md) | **DRAFT, not in force** — ToS + DPA data-derivation clause backing ADR-0004's Layer B, with the Article 9 exclusion and an effectiveness gate |

## Troubleshooting

| Document | Covers |
|---|---|
| [troubleshooting/DOCKER_IPTABLES_ISSUE.md](troubleshooting/DOCKER_IPTABLES_ISSUE.md) | Docker networking fix (iptables) |
| [troubleshooting/IPTABLES_FIX_RESULTS.md](troubleshooting/IPTABLES_FIX_RESULTS.md) | Verification of the iptables fix |
| [troubleshooting/DOCKER_NETWORKING_FIX.md](troubleshooting/DOCKER_NETWORKING_FIX.md) | Docker networking fix details |

---

## Point-in-time material (deliberately not itemised)

These directories hold **dated snapshots and session artifacts**, not current reference material.
They are listed so nothing is silently dropped, but individual files are not indexed — read them as
history, and prefer the current docs above when they disagree.

| Directory | Files | What it is |
|---|---|---|
| [status/](status/) | 9 | Point-in-time implementation summaries, session handoffs, project-status snapshots |
| [planning/](planning/) | 9 | Superseded roadmaps and phase plans. **Live planning is `.planning/` at the repo root**, not here |
| [setup/](setup/) | 5 (2 indexed above) | Historic setup verification, test results, implementation summaries |
| [reports/](reports/) | 5 (4 indexed above) | `GAP_ANALYSIS.md` and `QA_IMPLEMENTATION_V1.0.0.md` are dated snapshots |
| [archive/](archive/) | — | Explicitly archived documentation |

Repo-root docs outside `docs/`: `README.md`, `CLAUDE.md` (project instructions), `AGENTS.md`,
`HANDOFF.md` (session continuity), plus two dated reports —
`ORDER_NUMBER_GENERATION_REPORT.md` and `SPRING_FRAMEWORK_UPGRADE_REPORT.md`.

---

## Maintaining this index

- Numeric claims (test counts, schema version) come from `docs/metrics.json` — regenerate with
  `scripts/docs-freshness.sh --write`, never by hand.
- After adding a doc, add its row here **and** to the directory's own `README.md` where one exists
  (`analysis/`, `api/`).
- To check this index has not drifted, run both directions — dead links and unindexed files:

  ```bash
  cd docs
  # (a) dead links
  grep -oE '\]\(([A-Za-z0-9._/-]+\.md)\)' DOCUMENTATION_INDEX.md \
    | sed -E 's/^\]\(//; s/\)$//' | sort -u \
    | while read -r p; do [ -f "$p" ] || echo "MISSING: $p"; done
  # (b) unindexed docs
  linked=$(grep -oE '\]\(([A-Za-z0-9._/-]+\.md)\)' DOCUMENTATION_INDEX.md \
    | sed -E 's/^\]\(//; s/\)$//' | sort -u)
  find . -name '*.md' -not -path './archive/*' | sed 's|^\./||' | sort \
    | while read -r f; do grep -qxF "$f" <<< "$linked" || echo "UNINDEXED: $f"; done
  ```

  Both are written to be falsifiable: check (a) against a deliberately broken link and (b) against a
  newly-created scratch `.md` — each must print a line before you trust its silence. Note the
  here-string in (b): `grep -qxF` inside a pipeline would invert on match under `set -o pipefail`
  via SIGPIPE→141.
