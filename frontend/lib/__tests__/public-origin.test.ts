/**
 * @jest-environment node
 *
 * Issue #504 — `resolvePublicOrigin` is the module that must never hand a bind
 * address to an external IdP.
 */

import { resolvePublicOrigin, __testables } from "../public-origin"

const { isBindAddress, toOrigin } = __testables

const withEnv = (env: Record<string, string | undefined>, fn: () => void) => {
  const saved: Record<string, string | undefined> = {}
  for (const k of Object.keys(env)) {
    saved[k] = process.env[k]
    if (env[k] === undefined) delete process.env[k]
    else process.env[k] = env[k] as string
  }
  try {
    fn()
  } finally {
    for (const k of Object.keys(env)) {
      if (saved[k] === undefined) delete process.env[k]
      else process.env[k] = saved[k] as string
    }
  }
}

const reqWith = (origin: string) => ({ nextUrl: new URL(origin) })

describe("isBindAddress", () => {
  // The measured value, plus every spelling of the same idea. `[::]` is the
  // bracketed form `new URL()` leaves on `.hostname`, so it must be handled.
  it.each(["0.0.0.0", "::", "[::]", "::0", "0:0:0:0:0:0:0:0", "[0:0:0:0:0:0:0:0]", ""])(
    "classifies %p as a bind address",
    (h) => expect(isBindAddress(h)).toBe(true)
  )

  // Loopback is NOT a bind address: it is a real reachable origin for a
  // non-containerised `next dev`, and treating it as unusable would break dev.
  it.each(["localhost", "127.0.0.1", "::1", "[::1]", "app.jtoye.local", "app.olajay.co.uk", "10.0.0.1"])(
    "classifies %p as usable",
    (h) => expect(isBindAddress(h)).toBe(false)
  )
})

describe("toOrigin", () => {
  it("returns the ORIGIN, dropping any path, query or trailing slash", () => {
    expect(toOrigin("https://app.example.com/")).toBe("https://app.example.com")
    expect(toOrigin("https://app.example.com/some/path?x=1")).toBe("https://app.example.com")
    expect(toOrigin("  http://localhost:3000  ")).toBe("http://localhost:3000")
  })

  it("rejects a bind address, a non-http scheme, junk and empties", () => {
    expect(toOrigin("http://0.0.0.0:3000")).toBeNull()
    expect(toOrigin("http://[::]:3000")).toBeNull()
    expect(toOrigin("javascript:alert(1)")).toBeNull()
    expect(toOrigin("ftp://files.example.com")).toBeNull()
    expect(toOrigin("not a url")).toBeNull()
    expect(toOrigin("")).toBeNull()
    expect(toOrigin(undefined)).toBeNull()
    expect(toOrigin(null)).toBeNull()
  })
})

describe("resolvePublicOrigin precedence", () => {
  it("prefers APP_PUBLIC_ORIGIN, then NEXTAUTH_URL, then the request", () => {
    withEnv({ APP_PUBLIC_ORIGIN: "https://a.example", NEXTAUTH_URL: "https://b.example" }, () => {
      expect(resolvePublicOrigin(reqWith("http://c.example"))).toBe("https://a.example")
    })
    withEnv({ APP_PUBLIC_ORIGIN: undefined, NEXTAUTH_URL: "https://b.example" }, () => {
      expect(resolvePublicOrigin(reqWith("http://c.example"))).toBe("https://b.example")
    })
    withEnv({ APP_PUBLIC_ORIGIN: undefined, NEXTAUTH_URL: undefined }, () => {
      expect(resolvePublicOrigin(reqWith("http://c.example"))).toBe("http://c.example")
    })
  })

  it("skips a MISCONFIGURED higher-precedence value rather than failing on it", () => {
    // A bind address in the override must not shadow a perfectly good
    // NEXTAUTH_URL — otherwise one bad env var reintroduces the whole defect.
    withEnv({ APP_PUBLIC_ORIGIN: "http://0.0.0.0:3000", NEXTAUTH_URL: "http://localhost:3000" }, () => {
      expect(resolvePublicOrigin(reqWith("http://0.0.0.0:3000"))).toBe("http://localhost:3000")
    })
  })

  it("returns null — not a bind-address origin — when NOTHING is trustworthy", () => {
    withEnv({ APP_PUBLIC_ORIGIN: undefined, NEXTAUTH_URL: undefined }, () => {
      expect(resolvePublicOrigin(reqWith("http://0.0.0.0:3000"))).toBeNull()
      expect(resolvePublicOrigin(undefined)).toBeNull()
      expect(resolvePublicOrigin(null)).toBeNull()
    })
  })

  it("survives the compose reality: bind-address request + correct NEXTAUTH_URL", () => {
    // The exact pair measured in jtoye-frontend:
    //   HOSTNAME=0.0.0.0   NEXTAUTH_URL=http://localhost:3000
    withEnv({ APP_PUBLIC_ORIGIN: undefined, NEXTAUTH_URL: "http://localhost:3000" }, () => {
      expect(resolvePublicOrigin(reqWith("http://0.0.0.0:3000"))).toBe("http://localhost:3000")
    })
  })
})
