# Persona user-testing — PASS 2 brief (read fully, AFTER the pass-1 brief)

First read `/home/sanmi/IdeaProjects/JToye_OaaS_2026/.planning/ux-persona-test-20261003/COMMON-BRIEF.md`.
Everything in it still applies: environment, accounts, ground rules, the browser recipe, and the REPORT.md
deliverable. This file adds what pass 1 taught us. Where the two disagree, this file wins.

## Why there is a second pass
Pass 1 (2026-10-03) sent eight personas. Six finished. The busy vendor and the multi-shop owner never wrote
reports, and the card-payment leg was blocked. Pass 2 asks how the product holds up in REAL-LIFE
SITUATIONS: rush hours, shifts, shared devices, bad actors, regulators, integrators, assistive technology,
and things changing underneath a user mid-journey. Opinions, expectations and behaviours matter as much as defects.

## Environment facts, measured 2026-10-03 (not findings for you to re-discover)
- core-java is on :9090. The pass-1 :9091 outage (#671) is not happening now. If you see "Network Error",
  check `docker port jtoye_oaas_2026-core-java-1` before blaming the product.
- **The Stripe card path cannot run on this stack** (core has no STRIPE_API_KEY, and the browser bundle has no
  publishable key baked in). Every shop is cash-only. Do NOT spend time on card payments. Treat
  "cash-only, no payment taken" as the real-life state: think about what it means for your persona.
- Pass-1 test data is still present (pending cash orders named ORD-…-20261003-…, `ade-*` products, customers
  `jordan-*`, `sam-*`, `priya-*`). Ignore it unless your persona would really see it, and if they would, say so.
- Other pass-2 personas are running AT THE SAME TIME on the same tenants. Rate limit: 100 req/min per tenant.
  A 429 may be someone else's traffic, so note it as such and never treat it as a finding without a re-run.

## Do not re-report pass-1 findings as new
Before you start, skim the Findings tables in `../ux-persona-test-20261003/*/REPORT.md` for your area.
If you hit one of those findings again, record it as `RECONFIRMS <pass-1 id>` and add only what is new:
a different trigger, worse impact, or a real-life consequence pass 1 missed. New findings are the point.

## Extra deliverable: findings.json (machine-readable, REQUIRED)
Next to REPORT.md, write `findings.json`: a JSON array with one object per finding (positives too). Use this shape:
```json
{
  "id": "P2-<PERSONA-CODE>-NN",
  "title": "one-line statement of the defect, as a user would describe it",
  "severity": "blocker|major|minor|polish|positive",
  "status": "CONFIRMED|SUSPECTED",
  "area": "vendor-onboarding|catalogue|media|kitchen|orders|checkout|storefront|discovery|tracking|accounts|staff-scoping|finance|legal-compliance|privacy|allergens|accessibility|security|api-mcp|webhooks|notifications|performance|content",
  "surface": "URL path or API endpoint",
  "steps": ["…"],
  "expected": "…",
  "actual": "…",
  "realWorldImpact": "what happens to a real vendor/customer/business because of this",
  "evidence": ["file names inside your folder"],
  "reconfirms": ["pass-1 id, e.g. ADE-04"]
}
```
Keep REPORT.md as the narrative in character. findings.json feeds the issue tracker, so make each entry
self-contained: someone who never reads your report must be able to reproduce it.

## Real-life lens: ask these on every journey
- What does this person do NEXT when it goes wrong: phone, give up, go to Just Eat, re-order, complain?
- Would the business lose money, food, a customer, a licence, or its reputation?
- Does the product behave the same at 7pm on a Friday as it does on a quiet Tuesday?
- Is anything true in the UI that is not true in the data, or the other way round? Check BOTH.
