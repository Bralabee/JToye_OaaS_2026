# Project Analysis

Full codebase analysis generated on 2026-04-01 via comprehensive crawl of every source file, config, migration, test, and documentation file in the project.

## Documents

| Document | What It Covers |
|----------|---------------|
| [PROJECT_DEEP_DIVE.md](PROJECT_DEEP_DIVE.md) | Complete architecture overview, domain model, API catalog, security model, infrastructure, and observability |
| [CORE_JAVA_CATALOG.md](CORE_JAVA_CATALOG.md) | Every class, package, migration, config profile, caching strategy, and test in the Spring Boot backend |
| [EDGE_GO_CATALOG.md](EDGE_GO_CATALOG.md) | Go gateway endpoints, circuit breaker config, JWT middleware, WhatsApp webhook, and tests |
| [FRONTEND_CATALOG.md](FRONTEND_CATALOG.md) | Next.js pages, components, auth flow, API integration, type system, and tests |
| [INFRASTRUCTURE_CATALOG.md](INFRASTRUCTURE_CATALOG.md) | Docker Compose, Kubernetes manifests, CI/CD pipeline, scripts, monitoring, and backups |
| [GAPS_AND_IMPROVEMENTS.md](GAPS_AND_IMPROVEMENTS.md) | Discrepancies between docs and code, feature gaps, architecture opportunities, and security observations |
| [MCKINSEY_ANALYSIS.md](MCKINSEY_ANALYSIS.md) | Strategic McKinsey-style assessment of the project's current state, complications, and structured horizons for improvement |
| [ENTERPRISE_STRATEGIC_ANALYSIS.md](ENTERPRISE_STRATEGIC_ANALYSIS.md) | Comprehensive 16-framework enterprise analysis: SWOT, Porter's Five Forces, PESTLE, Value Chain, BCG, Ansoff, TRL, TOGAF, Balanced Scorecard, ISO 31000 Risk, CMMI, Business Model Canvas, McKinsey 7S, Technical Debt Quadrant, MoSCoW, Wardley Map |
| [REMEDIATION-BACKLOG-2026-07-08.md](REMEDIATION-BACKLOG-2026-07-08.md) | Prioritized P0-P3 remediation backlog distilled from the 2026-07-08 four-agent enterprise-readiness audit (verified against main @ 805e02e) |
| [MESSAGING-BROKER-EVALUATION-2026-07-26.md](MESSAGING-BROKER-EVALUATION-2026-07-26.md) | RabbitMQ vs Redpanda vs NATS, assessed against the actual topology. Verdict: stay on RabbitMQ (decision recorded in ADR-0003). Also records the five real messaging-layer defects that became Phase 27, and the verified finding that PostgreSQL logical decoding bypasses RLS |
| [WHATSAPP-BUSINESS-PLATFORM-EVALUATION-2026-08-07.md](WHATSAPP-BUSINESS-PLATFORM-EVALUATION-2026-08-07.md) | The 2026 WhatsApp Business Platform changes assessed against the integration as actually built (edge-go inbound webhook + INERT core-java outbound stub). Verdict: connect, don't build — catalogs and the `order` webhook replace the free-text parser, but Meta provides no multi-tenant routing, so Embedded Signup is the real remaining phase. Records nine defects, including a webhook that cannot be subscribed (no `GET` challenge handler) and a BSUID identity assumption that 2026 breaks |

## Usage

- Reference these documents when planning new features or onboarding
- Update after significant changes to keep analysis current
- Use GAPS_AND_IMPROVEMENTS.md as a living backlog for continuous improvement
