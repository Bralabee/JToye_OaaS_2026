/**
 * One source of truth for the vendor E2E credential.
 *
 * WHY THIS EXISTS. Six specs each carried `process.env.E2E_VENDOR_PASSWORD ??
 * "password123"`. That literal cannot authenticate — `onboarding-blocked-flow.
 * spec.ts` already recorded why when QA ONB-7 removed it there: "the stale
 * `password123` literal was removed — it fails against the re-imported realm."
 * The other six were never updated.
 *
 * The consequence was not a clear error. A wrong password is not a missing one,
 * so `vendorLogin()` submitted it, Keycloak refused, and the spec sat on
 * `waitForURL(/\/dashboard/)` until it timed out ~21s later. Measured against
 * the live compose stack on 2026-08-01: supplying the real password took the
 * suite from 55 failures to 20, and 37 of those "failures" were this — every
 * one of them reading as a product defect in the report.
 *
 * So the default is deliberately EMPTY, not a guess. An empty password is a
 * state the specs can detect and skip on with a message that names the fix;
 * a wrong password is indistinguishable from a broken dashboard.
 *
 * SUPPLY IT one of two ways:
 *   E2E_VENDOR_PASSWORD=…            explicit, wins over everything
 *   KC_SEED_USER_PASSWORD=…          what the compose stack renders the realm
 *                                    with — `set -a; . ./.env; set +a`
 */

import { test } from "@playwright/test"

/** The dev-realm vendor. Maps to tenant 00000000-…-0001 and carries `admin`. */
export const VENDOR_USERNAME = process.env.E2E_VENDOR_USERNAME ?? "admin-user"

/**
 * Never committed, and never defaulted to a literal — see the file header.
 * Empty means "not supplied", which is the only honest fallback.
 */
export const VENDOR_PASSWORD =
  process.env.E2E_VENDOR_PASSWORD ?? process.env.KC_SEED_USER_PASSWORD ?? ""

/** Names the remedy, so a skipped run tells the reader what to do next. */
export const NO_VENDOR_PASSWORD_REASON =
  "No vendor password — set E2E_VENDOR_PASSWORD, or source the stack's .env " +
  "(KC_SEED_USER_PASSWORD), before running vendor-authenticated specs"

/**
 * Call FIRST in a spec's `vendorLogin()`. Skips the test with an actionable
 * reason instead of letting it time out against a credential that cannot work.
 *
 * This must never become an unconditional skip: with the password supplied it
 * returns immediately and every test runs. The arm that proves it is a run WITH
 * the credential set — 37 of these tests pass there.
 */
export function skipWithoutVendorPassword(): void {
  test.skip(!VENDOR_PASSWORD, NO_VENDOR_PASSWORD_REASON)
}
