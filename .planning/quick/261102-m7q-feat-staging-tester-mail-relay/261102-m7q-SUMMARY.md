---
phase: quick
plan: 261102-m7q
subsystem: staging mail / Mailpit
tags: [staging, mailpit, smtp-relay, D-20, D-35, networkpolicy, keyvault-chain]
status: complete
issue: none
pr: 1096
requires: []
provides:
  - "Mailpit tester relay: MP_SMTP_RELAY_MATCHING + ALLOWED_RECIPIENTS from the optional Secret mailpit-relay, off when absent"
  - "mailpit-allow egress: kube-dns 53 and the public range on 587 only"
  - "INV-18(c) relay-shape rules + 5 selftest arms; INV-7 row; keyvault-chain out-of-band optional exemption + 3 selftest arms; UI-7 relay-aware + 2 selftest arms"
  - "docs/runbooks/staging-tester-mail.md (Secret steps, verify, remove, kcadm zero-code path)"
  - "D-35 recorded in 29-CONTEXT.md"
affects: [staging mail, Phase 29 D-20, D-23, D-33]
tech-stack:
  added: []
  patterns: ["optional out-of-band Secret whose absence is the safe state, pinned by render invariants"]
key-files:
  created:
    - docs/runbooks/staging-tester-mail.md
    - .planning/quick/261102-m7q-feat-staging-tester-mail-relay/evidence/
  modified:
    - k8s/components/mailpit/mailpit.yaml
    - k8s/components/mailpit/kustomization.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/goldens/staging.yaml
    - k8s/base/networkpolicies/README.md
    - k8s/scripts/check-render-invariants.sh
    - k8s/scripts/keyvault-chain.py
    - k8s/scripts/check-keyvault-chain.sh
    - scripts/staging-mail-ui.sh
    - k8s/DEPLOYMENT.md
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/29-CONTEXT.md
    - docs/CHANGELOG.md
decisions:
  - "Owner ruling 2026-11-02: D-20 amended narrowly (recorded as D-35 in 29-CONTEXT.md, which carries the phase's appended owner rulings D-33/D-34, and in the mailpit kustomization header)"
  - "MP_SMTP_RELAY_MATCHING, never MP_SMTP_RELAY_ALL; ALLOWED_RECIPIENTS reads the same key, because a configured relay also enables Mailpit's UI/API release"
  - "The Secret is hand-applied, not Key Vault-synced: an SPC mount fails the pod on a missing object, so D-23's pattern cannot be optional; losing it fails safe"
  - "STARTTLS and PLAIN auth are manifest literals; host/port/credentials/allow-list/override-from come only from the Secret"
metrics:
  completed: 2026-11-02
commits: 2
plan_head_before: 9b737e61
---

# Quick 261102-m7q: Staging tester mail relay (D-20 amended) Summary

Mailpit still captures every staging email. It now also relays a copy to recipients on an
owner-maintained allow-list, through Zoho on 587 with STARTTLS, and to nobody else. The relay is
configured only from the optional Secret `mailpit-relay`, which nothing in git creates. Without it
(or with any of host, port, username, password or allow-list empty) the start script unsets every
relay variable and Mailpit traps everything exactly as before.

## Mailpit facts used (v1.31.6, read from the tag's source; evidence/mailpit-v1.31.6-relay-source.txt)

- Env names (`cmd/root.go` 332-355): `MP_SMTP_RELAY_HOST`, `_PORT`, `_STARTTLS`, `_AUTH`,
  `_USERNAME`, `_PASSWORD`, `_MATCHING`, `_ALLOWED_RECIPIENTS`, `_OVERRIDE_FROM` (used); `_ALL`,
  `_CONFIG`, `_TLS`, `_ALLOW_INSECURE`, `_FWD_SMTP_ERRORS`, `_BLOCKED_RECIPIENTS`, `_SECRET`,
  `_RETURN_PATH`, `_PRESERVE_MESSAGE_IDS` (not used; INV-18 refuses the first five).
- Empty host: `validateRelayConfig` returns nil before anything else, so no relay and no release.
  `MATCHING` without a host is a fatal start error ("a relay configuration must be set to
  auto-relay any messages"), as is `AUTH=plain` with an empty password; the start-script gate
  exists so a half-made Secret cannot reach either.
- Selective relay: `autoRelayMessage` relays only the envelope recipients matching the regex; with
  none matching it returns before dialling. Success is logged at DEBUG only; there is no UI marker.
- Failure: `[relay] error: ...` at ERROR; the message is still stored; the sender is told only if
  `MP_SMTP_RELAY_FWD_SMTP_ERRORS` is set (it is not).
- A configured relay enables manual release; `ALLOWED_RECIPIENTS` bounds it, and
  `GET /api/v1/webui` exposes that regex to UI credential holders (recorded in the runbook).
- Docs page: https://mailpit.axllent.org/docs/configuration/smtp-relay/ (unversioned; agrees).

## Gates (both directions; evidence/)

| Gate | Fail direction | After |
|------|----------------|-------|
| check-render-invariants.sh | before the gate amendment: INV-7 names 3 undeclared mailpit egress tuples, INV-18 names all 9 relay variables, rc 1 | PASS rc 0 |
| --selftest-inv18 | 5 new relay arms (literal, configmap, required, split, cleartext) each caught | 11/11 caught, CLEAN rc 0 |
| --selftest-inv7 / --selftest-inv23 | existing arms caught | CLEAN rc 0 |
| check-keyvault-chain.sh | origin/main library on the new render: 7 breaks, rc 1 | PASS; --selftest 21/21 (3 new relay arms) |
| keyvault-chain.py objects | | 38 objects, identical to origin/main |
| render-golden.sh | staging render differs from golden (75 lines) | regenerated; OK both overlays |
| staging-mail-ui.sh --selftest | ui7-relay, ui7-relay-unbounded, ui7-relay-env caught | 16/16 (good-relay passes) |
| check-env-contract.sh | n/a (Mailpit is not one of its three services) | PASS |
| validate-networkpolicies.py / check-no-plaintext-secrets.sh / check-gate-enforcement.sh | | PASS |
| docs-freshness.sh / check-doc-metrics.sh / check-claims.sh / check-doc-citations.sh | | PASS (6997; 37/37; 56/56; 46/46) |
| check-branch-behind-base.sh | | PASS, 0 behind 9b737e61 |

`kubectl kustomize k8s/staging` before/after: 0 lines removed, 75 added, in three hunks: the
mailpit container's start-script gate (6 lines), its env (46 lines) and the mailpit-allow egress
(23 lines). Nothing else in the render changed.

## Deviations from the brief

1. **Files outside the named set.** The change trips three mailpit-specific gates that live
   outside `k8s/components/mailpit/**`: INV-7/INV-18 in `k8s/scripts/check-render-invariants.sh`,
   `k8s/scripts/keyvault-chain.py` (+ its selftest in `check-keyvault-chain.sh`), and the live
   probe `scripts/staging-mail-ui.sh` UI-7. Each was amended narrowly and shown failing first;
   `k8s/goldens/staging.yaml` was regenerated by `render-golden.sh --write`;
   `k8s/base/networkpolicies/README.md` and `k8s/DEPLOYMENT.md` carry the matching rows/notes.
2. **The Secret is not Key Vault-synced (D-23).** Recorded as a deliberate exception in D-35, the
   kustomization header and the runbook, with the reason.
3. **A sixth Secret key, `override-from`.** Zoho accepts only its own account (or an alias) as
   the sender, and Keycloak sends as `no-reply@olajay.co.uk`; optional.

## Not verified (no cluster was touched)

- The relay end-to-end against Zoho, and where Zoho shows SMTP-submitted mail (Sent folder vs
  admin mail logs).
- Whether `no-reply@olajay.co.uk` is an alias of the Zoho relay account (if not, `override-from`
  is required).
- The kcadm commands' exact output and that the bootstrap admin is still valid on the running
  realm.

## Self-Check: PASSED
