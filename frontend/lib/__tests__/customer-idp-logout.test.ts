/**
 * @jest-environment node
 *
 * Issue #504 — the back-channel IdP logout. This is the half that makes the
 * Keycloak SSO session die WITHOUT depending on a redirect URI being correct.
 */

import { endCustomerIdpSession } from "../customer-idp-logout"

const realFetch = global.fetch

afterEach(() => {
  global.fetch = realFetch
  jest.restoreAllMocks()
})

const withEnv = async (env: Record<string, string | undefined>, fn: () => Promise<void>) => {
  const saved: Record<string, string | undefined> = {}
  for (const k of Object.keys(env)) {
    saved[k] = process.env[k]
    if (env[k] === undefined) delete process.env[k]
    else process.env[k] = env[k] as string
  }
  try {
    await fn()
  } finally {
    for (const k of Object.keys(env)) {
      if (saved[k] === undefined) delete process.env[k]
      else process.env[k] = saved[k] as string
    }
  }
}

describe("endCustomerIdpSession", () => {
  it("POSTs client_id + refresh_token to the INTERNAL issuer's logout endpoint", async () => {
    const spy = jest.fn(async () => new Response(null, { status: 204 }))
    global.fetch = spy as unknown as typeof fetch

    await withEnv(
      {
        CUSTOMER_KEYCLOAK_ISSUER_INTERNAL: "http://keycloak:8080/realms/jtoye-customers",
        CUSTOMER_KEYCLOAK_ISSUER: "http://localhost:8085/realms/jtoye-customers",
        CUSTOMER_KEYCLOAK_CLIENT_ID: undefined,
      },
      async () => {
        expect(await endCustomerIdpSession("refresh-abc")).toBe("ok")
      }
    )

    expect(spy).toHaveBeenCalledTimes(1)
    const [url, init] = spy.mock.calls[0] as unknown as [string, RequestInit]
    // The #467 split-horizon trap: this runs INSIDE the container, so the
    // public issuer (localhost:8085) is unroutable and would hang.
    expect(url).toBe("http://keycloak:8080/realms/jtoye-customers/protocol/openid-connect/logout")
    expect(init.method).toBe("POST")
    const body = String(init.body)
    expect(body).toContain("client_id=storefront-client")
    expect(body).toContain("refresh_token=refresh-abc")
  })

  it("falls back to the public issuer when no internal one is set (bare `next dev`)", async () => {
    const spy = jest.fn(async () => new Response(null, { status: 204 }))
    global.fetch = spy as unknown as typeof fetch
    await withEnv(
      {
        CUSTOMER_KEYCLOAK_ISSUER_INTERNAL: undefined,
        CUSTOMER_KEYCLOAK_ISSUER: "http://localhost:8085/realms/jtoye-customers",
      },
      async () => {
        expect(await endCustomerIdpSession("refresh-abc")).toBe("ok")
      }
    )
    expect((spy.mock.calls[0] as unknown as [string])[0]).toBe(
      "http://localhost:8085/realms/jtoye-customers/protocol/openid-connect/logout"
    )
  })

  it("skips — without calling out — when there is no refresh token or no issuer", async () => {
    const spy = jest.fn(async () => new Response(null, { status: 204 }))
    global.fetch = spy as unknown as typeof fetch

    await withEnv({ CUSTOMER_KEYCLOAK_ISSUER_INTERNAL: "http://keycloak:8080/realms/x" }, async () => {
      expect(await endCustomerIdpSession(undefined)).toBe("skipped")
      expect(await endCustomerIdpSession("")).toBe("skipped")
    })
    await withEnv(
      { CUSTOMER_KEYCLOAK_ISSUER_INTERNAL: undefined, CUSTOMER_KEYCLOAK_ISSUER: undefined },
      async () => {
        expect(await endCustomerIdpSession("refresh-abc")).toBe("skipped")
      }
    )
    expect(spy).not.toHaveBeenCalled()
  })

  it("reports 'failed' — and never throws — on an IdP error or a network fault", async () => {
    await withEnv({ CUSTOMER_KEYCLOAK_ISSUER_INTERNAL: "http://keycloak:8080/realms/x" }, async () => {
      global.fetch = (async () => new Response("bad", { status: 400 })) as unknown as typeof fetch
      expect(await endCustomerIdpSession("stale")).toBe("failed")

      global.fetch = (async () => {
        throw new Error("ECONNREFUSED")
      }) as unknown as typeof fetch
      // Never rejects: the cookie clear that follows must still run, or a
      // Keycloak outage recreates the shared-device defect.
      await expect(endCustomerIdpSession("refresh-abc")).resolves.toBe("failed")
    })
  })
})
