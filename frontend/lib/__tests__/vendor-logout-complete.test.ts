/**
 * FE-1 / E-5 — the `VENDOR_LOGOUT_COMPLETE_ENABLED` flag, read through the
 * config layer.
 *
 * The flag decides whether the vendor sign-out's Keycloak return leg is the
 * new server-side clear (`/api/vendor-auth/logout-complete`) or today's
 * `/auth/signin`. Its resolution rule is the E-5 fail-safe in miniature:
 * ANYTHING that is not an explicit ON spelling is OFF, because the cost of a
 * wrong ON (a deployed realm that has not registered the URI answers 400 and
 * leaves the SSO session alive) is strictly worse than the cost of a wrong OFF
 * (today's documented defect).
 */

import { resolveVendorLogoutCompleteEnabled } from "@/lib/env-validation"
import {
  VENDOR_LOGOUT_COMPLETE_PATH,
  isVendorLogoutCompleteEnabled,
} from "@/lib/vendor-logout-complete"

describe("resolveVendorLogoutCompleteEnabled — off unless explicitly on", () => {
  it.each([
    ["true", true],
    ["TRUE", true],
    [" true ", true],
    ["1", true],
  ])("%j -> %s (accepted ON spellings)", (raw, expected) => {
    expect(resolveVendorLogoutCompleteEnabled(raw)).toBe(expected)
  })

  it.each([
    [undefined, false],
    ["", false],
    ["   ", false],
    ["false", false],
    ["0", false],
    ["yes", false],
    ["on", false],
    ["enabled", false],
    ["truthy", false],
  ])("%j -> %s (everything else is OFF)", (raw, expected) => {
    expect(resolveVendorLogoutCompleteEnabled(raw)).toBe(expected)
  })
})

describe("isVendorLogoutCompleteEnabled — reads the env at CALL time", () => {
  const FLAG = "VENDOR_LOGOUT_COMPLETE_ENABLED"
  let saved: string | undefined
  beforeEach(() => {
    saved = process.env[FLAG]
  })
  afterEach(() => {
    if (saved === undefined) delete process.env[FLAG]
    else process.env[FLAG] = saved
  })

  it("is false when the variable is unset", () => {
    delete process.env[FLAG]
    expect(isVendorLogoutCompleteEnabled()).toBe(false)
  })

  it("flips per call, not per module load (so a request-time read sees the live value)", () => {
    process.env[FLAG] = "true"
    expect(isVendorLogoutCompleteEnabled()).toBe(true)
    process.env[FLAG] = "false"
    expect(isVendorLogoutCompleteEnabled()).toBe(false)
  })
})

describe("VENDOR_LOGOUT_COMPLETE_PATH — one definition, no query string", () => {
  it("is the route path the realm must accept under its /* wildcard", () => {
    expect(VENDOR_LOGOUT_COMPLETE_PATH).toBe("/api/vendor-auth/logout-complete")
    expect(VENDOR_LOGOUT_COMPLETE_PATH).not.toContain("?")
  })
})
