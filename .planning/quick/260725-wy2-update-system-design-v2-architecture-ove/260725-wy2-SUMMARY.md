---
id: 260725-wy2
description: Update SYSTEM_DESIGN_V2 architecture overview to verified 2026-07-25 topology
date: 2026-07-25
status: complete
files_modified:
  - docs/architecture/SYSTEM_DESIGN_V2.md
---

# Quick Task 260725-wy2 — SUMMARY

## What changed

`docs/architecture/SYSTEM_DESIGN_V2.md` §1 now describes the system as built, verified
against the tree on 2026-07-25. §1.2 and §§2-10 were left untouched — they are design
intent from the 2025-12-30 draft, and the new header block says so explicitly so a reader
can tell verified fact from plan.

| Section | Change |
|---|---|
| Header | Version 2.1; records the §1 re-verification date and the fact/intent split; names §1 as the source the published topology diagram is regenerated from |
| §1.1 | Stale "Phase 1" diagram replaced. Was: an nginx/ALB fronting three services, `frontend :3000`, `edge :8080`, `postgres :5433`, with Redis and a message queue marked *"(Future)"*. Now: all 11 compose services with real host ports, protocol labelled on every edge, and the three independent ingresses (browser, edge-go, mcp-server) drawn correctly |
| §1.3 | Versions corrected (Next.js 14→16.2.2, React 18→19, Go 1.22→1.25, Spring Boot 3→3.5.16). Added the four rows that existed in the stack but not the table: mcp-server, MinIO/S3, Ollama, SMTP. edge-go's responsibility reworded from "API Gateway / auth proxy" — it fronts two routes, it is not a gateway |
| §1.4 | **New.** Protocol matrix for every hop: sync REST table, async table (AMQP / STOMP / SSE / webhook / SMTP), the five-exchange AMQP inventory with its consumers, the two outbox flushers, outbound-webhook egress hardening (SSRF resolver + the three signed headers), and the split-horizon auth plane |
| §1.5 | **New.** The confirmed STOMP relay defect (A3), including the operator-facing tell |

## Why §1.5 had to be in here

§1.4 documents `relay` as the k8s broker mode. Stating that without the defect would have
asserted a working production KDS fan-out that provably does not work — RabbitMQ's STOMP
plugin rejects the app's three-segment `/topic/kitchen/{tenantId}/{shopId}` destination
outright, and the symptom is masked by reconnect-driven polling that looks identical to
working realtime. The doc now tells a reader which mode works where, and how to tell.

## Verification

- `grep -n "^### 1\."` → 1.1–1.5 present and ordered.
- Stale-claim sweep over §1: no `:3100`, `Next.js 14`, `React 18`, `Go 1.22`, or
  `(Future) Message Queue` remains. The two `Application Load Balancer` / `Next.js 14`
  hits that remain in the file are both inside §1.2 (target architecture) — intentional,
  and now explicitly fenced off by the header note.
- Every §1 claim traces to a named file; the mapping is recorded in the PLAN's
  "Verification basis" table. No claim was written from recall.

## Not done (deliberate)

- **A3 is recorded, not fixed.** The fix changes the KDS destination shape (and therefore
  the broker convention, `TenantChannelInterceptor`, and the frontend subscription), which
  is architectural work that belongs in its own scoped task.
- §1.2 and §§2-10 not re-verified. They are known to contain 2025-12-30 aspirations; the
  header now labels them rather than silently leaving them to read as current.
