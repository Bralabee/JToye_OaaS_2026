---
status: complete
created: 2026-07-10
scope: documentation-and-frontend
---

# Business model decision guide

## Goal

Preserve the July 2026 evidence-backed business-model decision as a durable, agent-readable
Markdown authority and publish a public, interactive frontend guide that makes the recommendation,
assumptions, alternatives, risks, economics, and validation gates easier to interrogate and share.

## Deliverables

- `docs/analysis/BUSINESS_MODEL_DECISION_GUIDE.md` with sources, evidence strength, caveats,
  product boundaries, commercial assumptions, regulatory constraints, and 90-day decision gates.
- Discoverability links from the repository README and documentation index.
- Public `/business-model-guide` route with accessible topic navigation, model comparison,
  adjustable economics, evidence filtering, distribution actions, and responsive presentation.
- Focused component tests and documentation-metric updates required by the repository gate.

## Verification

- Focused Jest test passed: `3` tests.
- Frontend production build passed with `/business-model-guide` included.
- Documentation metrics regenerated to `778` logical invocations; freshness check passed.
