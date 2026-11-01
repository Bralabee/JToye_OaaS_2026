/**
 * The `/pay` document's own referrer policy (issue #1071).
 *
 * `/pay#token=…` carries a single-use bearer token in the fragment. next.config.mjs sends
 * `Referrer-Policy: no-referrer` on `/pay/:path*` (plan 30-19, asserted by
 * __tests__/header-snapshot.test.ts), but on staging the edge's ingress snippet REPLACED that
 * header with strict-origin-when-cross-origin, so every request the page made (Stripe.js, fonts)
 * named the page's origin. The edge line is gone (k8s/base/ingress.yaml); this is the second
 * layer: the Metadata API renders `<meta name="referrer" content="no-referrer">` into the
 * document, which no proxy rewriting response headers can overwrite.
 */
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
}))

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

import { metadata } from "../page"

describe("/pay metadata (issue #1071)", () => {
  it("sets the document referrer policy to no-referrer and stays noindex,nofollow", () => {
    expect(metadata.referrer).toBe("no-referrer")
    const robots = (metadata.robots ?? {}) as { index?: boolean; follow?: boolean }
    expect(robots.index).toBe(false)
    expect(robots.follow).toBe(false)
  })
})
