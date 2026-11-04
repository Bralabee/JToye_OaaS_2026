/**
 * Issue #843 — the browser half, and the identity-lifecycle contract of CLAUDE.md: a probe that
 * started BEFORE a sign-in or sign-out must not be joined by a caller that started after it, and its
 * late answer must not rewrite the stored identity. Asserted BY CONTENT in localStorage.
 *
 * Also pinned here, because the stateless server cannot do it (see the route test header): the
 * browser never sends a probe carrying a refresh cookie that an earlier probe is about to replace.
 * MEASURED on Keycloak 26.8.1 a replayed refresh token revokes the session, so the fake IdP below
 * revokes it too, and "two tabs probe at once" is run against it.
 */
import { customerLogout, getCustomerSession, handleCallback } from "@/lib/customer-auth"

const MARKER = "jtoye-customer-logged-in"
const EXPIRES = "jtoye-customer-expires-at"
const CUSTOMER_ID = "jtoye-customer-id"

type Json = Record<string, unknown>
const res = (body: Json, ok = true) => ({ ok, status: ok ? 200 : 500, json: async () => body }) as unknown as Response
const signedIn = (sub = "u1") =>
  res({ authenticated: true, expiresAt: Math.floor(Date.now() / 1000) + 300, profile: { sub, email: `${sub}@example.com`, name: sub, emailVerified: true } })
const anonymous = () => res({ authenticated: false })
const refused = () => res({ authenticated: false, refreshRefused: true })

function jwt(claims: Json) {
  const enc = (o: Json) => Buffer.from(JSON.stringify(o)).toString("base64url")
  return `${enc({ alg: "none" })}.${enc(claims)}.sig`
}

function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

const stored = () => ({
  marker: localStorage.getItem(MARKER),
  expires: localStorage.getItem(EXPIRES),
  id: localStorage.getItem(CUSTOMER_ID),
})

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  const realConsoleError = console.error
  jest.spyOn(console, "error").mockImplementation((...a: unknown[]) => {
    if (String(a[0]).includes("Not implemented: navigation")) return
    realConsoleError(...a)
  })
})
afterEach(() => jest.restoreAllMocks())

describe("getCustomerSession — the one retry (#843)", () => {
  it("re-probes once WITH the retry marker after a refused answer, and returns the second answer", async () => {
    const calls: Array<Record<string, string> | undefined> = []
    const answers = [refused(), signedIn("u1")]
    global.fetch = jest.fn(async (_u: RequestInfo | URL, init?: RequestInit) => {
      calls.push(init?.headers as Record<string, string> | undefined)
      return answers.shift()!
    }) as unknown as typeof fetch

    const session = await getCustomerSession()

    expect(session?.profile.sub).toBe("u1")
    expect(calls).toHaveLength(2)
    expect(calls[0]?.["x-session-retry"]).toBeUndefined()
    expect(calls[1]?.["x-session-retry"]).toBe("1")
    expect(stored().marker).toBe("true")
  })

  it("a second refusal is final: null, the marker cleared, and no third request", async () => {
    localStorage.setItem(MARKER, "true")
    const fetchMock = jest.fn().mockResolvedValueOnce(refused()).mockResolvedValueOnce(anonymous())
    global.fetch = fetchMock as unknown as typeof fetch

    expect(await getCustomerSession()).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(stored().marker).toBeNull()
  })

  it("an ordinary anonymous answer is NOT retried (every anonymous page view probes)", async () => {
    const fetchMock = jest.fn().mockResolvedValue(anonymous())
    global.fetch = fetchMock as unknown as typeof fetch
    expect(await getCustomerSession()).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe("getCustomerSession — through an identity transition", () => {
  function seedCallback(sub: string) {
    sessionStorage.setItem("jtoye-pkce-verifier", "v")
    sessionStorage.setItem("jtoye-oauth-state", "s")
    sessionStorage.setItem("jtoye-oauth-nonce", "n")
    return jwt({ sub, nonce: "n", email: `${sub}@example.com`, name: sub })
  }

  it("SIGN-IN: a caller after the callback does not join a probe that started before it, and the stale answer cannot erase the marker", async () => {
    const stale = deferred<Response>()
    const fresh = deferred<Response>()
    const sessionCalls: Deferred[] = [stale, fresh]
    type Deferred = ReturnType<typeof deferred<Response>>
    const idToken = seedCallback("u1")
    global.fetch = jest.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes("/protocol/openid-connect/token")) return res({ id_token: idToken, access_token: "a", refresh_token: "r", expires_in: 300 })
      if (url.includes("/api/customer-auth/login")) return res({})
      return sessionCalls.shift()!.promise // /api/customer-auth/session
    }) as unknown as typeof fetch

    // The callback page's nav probe leaves while the visitor is still anonymous ...
    const early = getCustomerSession()
    // ... then the callback completes: cookies set, marker stamped.
    expect((await handleCallback("code", "s"))?.sub).toBe("u1")
    expect(stored()).toMatchObject({ marker: "true", id: "u1" })

    // The destination page's nav probes now. It must send its OWN request.
    const late = getCustomerSession()
    expect((global.fetch as jest.Mock).mock.calls.filter((c) => String(c[0]).includes("/session"))).toHaveLength(2)

    // The early probe finally answers "anonymous". It must not rewrite what sign-in stored.
    stale.resolve(anonymous())
    await early
    expect(stored()).toMatchObject({ marker: "true", id: "u1" })

    fresh.resolve(signedIn("u1"))
    expect((await late)?.profile.sub).toBe("u1")
    expect(stored()).toMatchObject({ marker: "true", id: "u1" })
  })

  it("SIGN-OUT: a probe that started signed-in cannot resurrect the marker after the customer signs out", async () => {
    localStorage.setItem(MARKER, "true")
    localStorage.setItem(EXPIRES, String(Math.floor(Date.now() / 1000) + 300))
    localStorage.setItem(CUSTOMER_ID, "u1")
    const stale = deferred<Response>()
    global.fetch = jest.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes("logout-url")) return res({ url: "http://kc.example/logout" })
      if (url.includes("/api/customer-auth/logout")) return res({})
      return stale.promise
    }) as unknown as typeof fetch

    const early = getCustomerSession()
    await customerLogout()
    expect(stored()).toEqual({ marker: null, expires: null, id: null })

    stale.resolve(signedIn("u1")) // the pre-sign-out probe answers "authenticated"
    await early
    expect(stored()).toEqual({ marker: null, expires: null, id: null })
  })

  it("overlapping callers within ONE identity still share a single request", async () => {
    const pending = deferred<Response>()
    const fetchMock = jest.fn(() => pending.promise)
    global.fetch = fetchMock as unknown as typeof fetch
    const a = getCustomerSession()
    const b = getCustomerSession()
    pending.resolve(signedIn("u1"))
    await Promise.all([a, b])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe("getCustomerSession — two tabs against the REAL realm's rules (#843)", () => {
  /** A fake of navigator.locks: exclusive, FIFO, shared by every module instance in the test. */
  function installLocks() {
    const queues = new Map<string, Promise<unknown>>()
    Object.defineProperty(navigator, "locks", {
      configurable: true,
      value: {
        request: (name: string, ...rest: unknown[]) => {
          const cb = rest[rest.length - 1] as () => Promise<unknown>
          const prev = queues.get(name) ?? Promise.resolve()
          const run = prev.then(cb, cb)
          queues.set(name, run.catch(() => undefined))
          return run
        },
      },
    })
  }
  function removeLocks() {
    delete (navigator as any).locks
  }

  /**
   * One browser cookie jar, one realm that revokes the session when a spent refresh token is
   * replayed, and a route that behaves like app/api/customer-auth/session/route.ts: a refusal says
   * refreshRefused and clears nothing, a refusal on the retry clears the jar. Every response takes
   * `latencyTurns` event-loop turns, so a second probe really is in flight with the first.
   */
  function world() {
    const jar = { refresh: "R0" as string | null }
    const spent = new Set<string>()
    let revoked = false
    let n = 0
    const sent: string[] = []
    const fetchImpl = async (_u: RequestInfo | URL, init?: RequestInit) => {
      const cookie = jar.refresh // read at SEND time, like a browser
      const retry = (init?.headers as Record<string, string> | undefined)?.["x-session-retry"] === "1"
      sent.push(`${cookie}${retry ? " (retry)" : ""}`)
      for (let i = 0; i < 5; i += 1) await Promise.resolve() // time on the wire
      if (!cookie) return anonymous()
      const dead = revoked || spent.has(cookie)
      if (dead) {
        if (spent.has(cookie)) revoked = true // the measured behaviour: a replay kills the session
        if (retry) {
          jar.refresh = null
          return anonymous()
        }
        return refused()
      }
      spent.add(cookie)
      n += 1
      jar.refresh = `R${n}`
      return signedIn("u1")
    }
    return { jar, sent, fetchImpl, isRevoked: () => revoked }
  }

  function twoTabs() {
    const tabs: Array<typeof getCustomerSession> = []
    for (let i = 0; i < 2; i += 1) {
      jest.isolateModules(() => {
        // eslint-disable-next-line @typescript-eslint/no-require-imports
        tabs.push(require("@/lib/customer-auth").getCustomerSession)
      })
    }
    return tabs
  }

  afterEach(removeLocks)

  it("keeps the customer signed in when two tabs probe at once (serialised by the lock)", async () => {
    installLocks()
    const w = world()
    global.fetch = jest.fn(w.fetchImpl) as unknown as typeof fetch
    const [tabA, tabB] = twoTabs()

    const [a, b] = await Promise.all([tabA(), tabB()])

    expect(a?.profile.sub).toBe("u1")
    expect(b?.profile.sub).toBe("u1")
    expect(w.isRevoked()).toBe(false)
    expect(w.sent).toEqual(["R0", "R1"]) // the second probe left with the cookie the first had just set
    expect(w.jar.refresh).toBe("R2")
  })

  it("a lock holder that never releases cannot stop a tab probing: after the bounded wait it probes unlocked", async () => {
    jest.useFakeTimers()
    try {
      Object.defineProperty(navigator, "locks", {
        configurable: true,
        value: {
          // never grants; rejects with AbortError when the caller's signal fires, like the real API
          request: (_n: string, opts: { signal?: AbortSignal }) =>
            new Promise((_res, rej) => {
              opts.signal?.addEventListener("abort", () => rej(new DOMException("aborted", "AbortError")))
            }),
        },
      })
      const fetchMock = jest.fn().mockResolvedValue(signedIn("u1"))
      global.fetch = fetchMock as unknown as typeof fetch

      const pending = getCustomerSession()
      expect(fetchMock).not.toHaveBeenCalled()
      await jest.advanceTimersByTimeAsync(30_001)

      expect((await pending)?.profile.sub).toBe("u1")
      expect(fetchMock).toHaveBeenCalledTimes(1)
    } finally {
      jest.useRealTimers()
    }
  })

  it("WITHOUT the lock the same two tabs replay a spent token and the realm ends the session (the measured failure)", async () => {
    removeLocks()
    const w = world()
    global.fetch = jest.fn(w.fetchImpl) as unknown as typeof fetch
    const [tabA, tabB] = twoTabs()

    const [a, b] = await Promise.all([tabA(), tabB()])

    expect(w.isRevoked()).toBe(true)
    expect([a, b].filter(Boolean).length).toBeLessThan(2)
  })
})
