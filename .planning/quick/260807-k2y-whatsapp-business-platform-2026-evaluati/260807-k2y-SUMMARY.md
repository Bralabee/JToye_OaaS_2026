---
quick_id: 260807-k2y
description: WhatsApp Business Platform 2026 evaluation doc
date: 2026-08-07
status: complete
docs_only: true
---

# Quick Task 260807-k2y — Summary

## What was done

Wrote `docs/analysis/WHATSAPP-BUSINESS-PLATFORM-EVALUATION-2026-08-07.md` — an
evidence-backed evaluation of the 2026 WhatsApp Business Platform changes against the
OaaS WhatsApp integration as it actually exists in the tree, and indexed it in
`docs/analysis/README.md`.

**No code, config or migration was changed.** The document records findings; acting on
them is a separate decision.

## Verdict recorded

Connect, don't build. Catalogs plus the `order` webhook replace the free-text parser;
Meta provides no multi-tenant routing, so Embedded Signup under a Tech Provider account
is the real remaining phase.

## Findings the review surfaced (9, in the doc's §4)

Highest-impact three:

1. **The webhook cannot be subscribed.** Only `POST` is registered at
   `edge-go/cmd/edge/main.go:299`; Meta's subscription handshake is a `GET` with
   `hub.challenge`, and no challenge/verify-token handling exists in `edge-go`. The
   intake path has never been connectable to a live WhatsApp Business Account.
2. **BSUID breaks the identity assumption.** `CustomerPhone` is set from the webhook
   `from` field (`edge-go/cmd/edge/handlers.go:369`), which during 2026 may carry a
   business-scoped user ID or be absent.
3. **No tenant dimension.** One global tenant and one global shop for all WhatsApp
   traffic — the only ingress in the system without a tenant dimension.

Also recorded: infrastructure failures swallowed as 200 (declining 48h of free Meta
retries), the Meta-direct-inbound vs Twilio-shaped-outbound provider straddle, text-only
intake, a stale retry doc string, and an effort estimate that predates the multi-tenancy
requirement.

## Verification

Task 1's `done` condition was a three-arm run of the repo's own citation gate:

| Arm | Command | Result |
|---|---|---|
| **Clean** | `CITATION_DOCS="<doc>" bash scripts/check-doc-citations.sh` | `citations total=34 verified=34 uncheckable=0`, `violations 0`, **rc=0** |
| **Break** | same, over a copy with one citation repointed to `main.go:99999` | `FAIL: C-2 … but that file has only 387 line(s)`, `violations 2`, **rc=1** |
| **Clean again** | as arm 1, after removing the broken copy | `verified=34`, `violations 0`, **rc=0** |

The closing clean arm is the one that proves the restore happened. Restore was also
verified **by content**, not by `git diff --stat`: the break-arm file is absent from
disk, the doc still carries its 2 `main.go:299` citations, and a search for the
corruption token `99999` returns rc=1 (not found).

Worth recording separately: **the gate's first run over the initial draft FAILED with 11
violations** — citations whose backticked token had wrapped onto a different line from
the citation itself. That was not a staged break arm; it was the gate doing its job on a
real draft, which is the strongest available evidence that it is capable of failing.

`docs/analysis/` is not in the gate's default doc set, so this check is on-demand, not
CI-enforced. Noted in the document's method section so a later reader does not assume CI
coverage.

## Files changed

- `docs/analysis/WHATSAPP-BUSINESS-PLATFORM-EVALUATION-2026-08-07.md` (new)
- `docs/analysis/README.md` (one row added to the Documents table)

## Deliberately not done

- No change to the parser, handler, or `WhatsAppSmsChannel` stub
- No GitHub issues opened — the doc names candidates; filing is the user's call
- No Meta-direct vs BSP decision made — the doc frames the trade, does not settle it

## Open evidence gaps (carried in the document, not resolved here)

- Meta's changelog returned HTTP 500 on 2026-08-07, so BSUID rollout **dates** rest on
  Twilio's changelog and BSP advisories. Direction certain, dates approximate.
- A secondary source's claim that utility templates become chargeable in-window from
  1 Oct 2026 is contradicted by Meta's own pricing page; recorded as unconfirmed.
- UK availability of catalogs and the `order` webhook was not positively confirmed from
  primary documentation — no stated restriction is not the same as confirmed availability.
- The absence-of-`GET`-handler finding covers `edge-go` only; ingress and proxy layers
  were not audited.

---

## Landing note (added by the session that shipped this, 2026-08-07)

The authoring session was terminated before opening a PR. Its work was cherry-picked onto a branch
off `main` and landed unchanged, with an **addendum** appended to the analysis document rather than
edits to its findings — the original analysis stands on its own and the second-reviewer trail is
worth keeping separate.

Everything in the original verified. The addendum records four things it did not have:

- **Finding 1's `edge-go`-only caveat is closed.** Ingress, compose and Next.js config were audited;
  nothing in front of the service answers the handshake either, so the absence is repo-wide.
- **Finding 1 is a re-discovery.** First recorded **2026-04-27** in
  `docs/audit/remediation/07-edge-absorb-remediation.md:146`, unfixed since, and tracked by **no**
  open issue. The same document asserts four lines later that there is `no need to change` the
  webhook path because Meta already has it registered — which cannot be true, and is the likeliest
  reason a three-month-old finding was never actioned.
- **Finding 5 is understated.** There are **four** always-200 paths, not one, and the two that are
  unambiguously infrastructure (`handlers.go:306` service-token failure, `handlers.go:377` Core-call
  failure) are not the one cited. It is also a *documented* decision — the published OpenAPI
  contract states it — so the remedy includes regenerating that contract.
- **Finding 8 is 6 occurrences, not 1**, three of them in the generated API artifacts.

Citation gate on the landed file: **51/51 verified, 0 uncheckable, 0 violations** (the original
draft's figure was 34/34; the addendum added 17 citations and no debt). Falsified in both directions
after committing — see the landing commit message for the arms.

**One trap fired and is recorded rather than hidden.** The first verification attempt ran the break
arm on an *uncommitted* tree and restored with `git checkout -- <file>`, which restores from the
index and therefore deleted the entire addendum instead of undoing the break. The closing clean arm
caught it — 34 citations where there should have been 51. The break arm looked identical in both
attempts. Commit before running arms; assert the clean state last as well as first.
