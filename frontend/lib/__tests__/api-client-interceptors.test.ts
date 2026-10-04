/**
 * Behavioural tests for the hardened api-client interceptors:
 *  - Injects Authorization + X-Tenant-Id on every request
 *  - Retries 5xx up to 2 times with backoff then rejects
 *  - Does NOT retry 4xx — except one rate-limiter 429 with Retry-After N of 0-10, after N + 1 s
 *  - Debounces 401s so concurrent calls trigger ONE getSession() refresh
 */

// next-auth/react is already mocked globally in jest.setup.js; we re-mock
// per-test so we can control the resolved value and count calls.
jest.mock("next-auth/react", () => ({
  getSession: jest.fn(),
}))

import { getSession } from "next-auth/react"
// eslint-disable-next-line @typescript-eslint/no-require-imports
const apiClientModule = require("../api-client")
const apiClient = apiClientModule.default as import("axios").AxiosInstance
const __testing = apiClientModule.__testing as { resetRefresh: () => void }

// Replace the axios adapter with a jest.fn() so we can script responses per
// request. The adapter signature: (config) => Promise<AxiosResponse>.
//
// NOTE: We set the adapter as a request interceptor-level concern rather than
// only on defaults, because axios 1.x may clone config on recursive requests
// (retries) and lose per-instance adapters. Setting via a request interceptor
// ensures every request — initial AND retry — routes through our mock.
const adapter = jest.fn()
apiClient.defaults.adapter = adapter
apiClient.interceptors.request.use((config) => {
  config.adapter = adapter
  return config
})

// Silence jsdom "not implemented: navigation" noise and capture redirects.
// We intercept console.error so the intentional navigation in the 401 path
// does not clutter test output, and replace the Location.href setter so the
// interceptor can observe "I would have navigated here".
let consoleErrorSpy: jest.SpyInstance
let hrefSetter: PropertyDescriptor | undefined
beforeAll(() => {
  consoleErrorSpy = jest.spyOn(console, "error").mockImplementation((msg) => {
    if (typeof msg === "string" && msg.includes("Not implemented: navigation")) return
  })
  try {
    hrefSetter = Object.getOwnPropertyDescriptor(window.location, "href")
    Object.defineProperty(window.location, "href", {
      configurable: true,
      set: () => {},
      get: () => "",
    })
  } catch {
    /* jsdom may refuse — the console spy is enough */
  }
})
afterAll(() => {
  consoleErrorSpy?.mockRestore()
  if (hrefSetter) {
    try {
      Object.defineProperty(window.location, "href", hrefSetter)
    } catch {
      /* ignore */
    }
  }
})

function ok(data: unknown, status = 200) {
  return {
    data,
    status,
    statusText: "OK",
    headers: {},
    config: {},
  }
}

function err(status: number, config: unknown = {}) {
  const e = new Error(`HTTP ${status}`) as Error & {
    response: { status: number; data: Record<string, unknown>; headers: Record<string, string>; config: unknown; statusText: string }
    isAxiosError: boolean
    config: unknown
  }
  // Mimic an AxiosError — `config` must be the ACTUAL request config so that
  // the response interceptor can recurse with `apiClient.request(config)`.
  e.isAxiosError = true
  e.config = config
  e.response = {
    status,
    data: {},
    headers: {},
    statusText: "err",
    config,
  }
  return e
}

// Convert a queue of mock call outcomes ("reject 500", "resolve ok") into a
// jest implementation that captures the live config passed by axios into each
// adapter invocation. Using the live config is essential because retries
// re-submit via `apiClient.request(config)`.
function queueAdapter(outcomes: Array<
  | { kind: "reject"; status: number }
  | { kind: "resolve"; data: unknown }
>) {
  let i = 0
  adapter.mockImplementation((config: unknown) => {
    const outcome = outcomes[i++] ?? outcomes[outcomes.length - 1]
    if (outcome.kind === "reject") {
      return Promise.reject(err(outcome.status, config))
    }
    return Promise.resolve({
      data: outcome.data,
      status: 200,
      statusText: "OK",
      headers: {},
      config,
    })
  })
}

beforeEach(() => {
  adapter.mockReset()
  __testing.resetRefresh()
  ;(getSession as jest.Mock).mockReset()
  ;(getSession as jest.Mock).mockResolvedValue({
    accessToken: "token-1",
    user: { tenantId: "tenant-42", id: "u-1", name: "Test", email: "t@t" },
    expires: "2099-12-31",
  })
})

describe("api-client interceptors", () => {
  it("injects Authorization and X-Tenant-Id headers on requests", async () => {
    queueAdapter([{ kind: "resolve", data: { ok: true } }])
    await apiClient.get("/anything")
    expect(adapter).toHaveBeenCalledTimes(1)
    const sentConfig = adapter.mock.calls[0][0]
    // Axios 1.x uses AxiosHeaders with .get()
    const authHeader =
      typeof sentConfig.headers.get === "function"
        ? sentConfig.headers.get("Authorization")
        : sentConfig.headers.Authorization
    const tenantHeader =
      typeof sentConfig.headers.get === "function"
        ? sentConfig.headers.get("X-Tenant-Id")
        : sentConfig.headers["X-Tenant-Id"]
    expect(authHeader).toBe("Bearer token-1")
    expect(tenantHeader).toBe("tenant-42")
  })

  it("retries a 500 response exactly twice then rejects", async () => {
    queueAdapter([
      { kind: "reject", status: 500 },
      { kind: "reject", status: 500 },
      { kind: "reject", status: 500 },
    ])
    await expect(apiClient.get("/flaky")).rejects.toMatchObject({
      response: { status: 500 },
    })
    // Initial + 2 retries = 3 calls
    expect(adapter).toHaveBeenCalledTimes(3)
  })

  it("retries and succeeds on the second attempt when 5xx is transient", async () => {
    queueAdapter([
      { kind: "reject", status: 502 },
      { kind: "resolve", data: { recovered: true } },
    ])
    const res = await apiClient.get("/eventually")
    expect(res.data).toEqual({ recovered: true })
    expect(adapter).toHaveBeenCalledTimes(2)
  })

  it("does NOT retry 4xx errors", async () => {
    queueAdapter([{ kind: "reject", status: 404 }])
    await expect(apiClient.get("/missing")).rejects.toMatchObject({
      response: { status: 404 },
    })
    expect(adapter).toHaveBeenCalledTimes(1)
  })

  it("debounces concurrent 401s: getSession() runs exactly once per stampede", async () => {
    // Every request returns 401 so the interceptor falls through to refresh
    adapter.mockImplementation((cfg: unknown) => Promise.reject(err(401, cfg)))
    // After refresh we still don't get an access token so the retry also 401s;
    // we will expect the final rejection here
    ;(getSession as jest.Mock).mockResolvedValue({ user: {} })

    const calls = [
      apiClient.get("/a").catch(() => "rejected"),
      apiClient.get("/b").catch(() => "rejected"),
      apiClient.get("/c").catch(() => "rejected"),
    ]
    const results = await Promise.all(calls)
    expect(results).toEqual(["rejected", "rejected", "rejected"])
    // getSession is called by (a) the request interceptor for each call, plus
    // (b) the 401 refresh path. The 401 refresh path must be deduped — so the
    // total count is 3 (request interceptor) + 1 (single refresh) = 4.
    expect((getSession as jest.Mock).mock.calls.length).toBe(4)
  })
})

/**
 * 429 single retry (nightly E2E 2026-10-02/04). core-java's per-tenant
 * RateLimitInterceptor answered GET /api/v1/shops with 429 `Retry-After: 3` and
 * POST /api/v1/onboarding with 429 `Retry-After: 9`; the client gave up at once
 * and the onboarding page told a vendor with shops that they had none.
 *
 * The header object is a real `AxiosHeaders`, as the XHR adapter delivers it — a
 * plain-object fixture would not prove the lookup works on the shape the browser
 * actually hands the interceptor.
 */
describe("api-client interceptors — 429 Retry-After", () => {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { AxiosHeaders } = require("axios") as typeof import("axios")

  function rateLimited(config: unknown, retryAfter?: string, type = "https://jtoye.uk/errors/rate-limited") {
    const e = err(429, config)
    e.response.data = { type }
    e.response.headers = (
      retryAfter === undefined ? new AxiosHeaders() : AxiosHeaders.from({ "Retry-After": retryAfter })
    ) as unknown as Record<string, string>
    return e
  }

  // Scripted adapter: each entry is a Retry-After value for a 429, or "ok".
  function script(steps: Array<{ retryAfter?: string; type?: string } | "ok">) {
    let i = 0
    adapter.mockImplementation((config: { data?: unknown }) => {
      const step = steps[i++] ?? steps[steps.length - 1]
      if (step === "ok") {
        return Promise.resolve({ data: { recovered: true }, status: 200, statusText: "OK", headers: {}, config })
      }
      return Promise.reject(rateLimited(config, step.retryAfter, step.type))
    })
  }

  afterEach(() => {
    jest.useRealTimers()
  })

  it("retries a 429 once after Retry-After: 1 + 1 s and resolves — the server floors, so N s is early", async () => {
    jest.useFakeTimers()
    script([{ retryAfter: "1" }, "ok"])

    const pending = apiClient.get("/api/v1/shops")
    await jest.advanceTimersByTimeAsync(1999)
    // Still waiting: the true wait lies in [1, 2) s, so a replay at 1 s would meet a second 429.
    expect(adapter).toHaveBeenCalledTimes(1)

    await jest.advanceTimersByTimeAsync(1)
    await expect(pending).resolves.toMatchObject({ data: { recovered: true } })
    expect(adapter).toHaveBeenCalledTimes(2)
  })

  it("replays a rate-limited POST with the same body (the 429 is raised in preHandle, before any controller)", async () => {
    jest.useFakeTimers()
    script([{ retryAfter: "2" }, "ok"])

    const pending = apiClient.post("/api/v1/onboarding", { shopId: "shop-1", model: "MARKETPLACE" })
    await jest.advanceTimersByTimeAsync(2999)
    expect(adapter).toHaveBeenCalledTimes(1)
    await jest.advanceTimersByTimeAsync(1)
    await expect(pending).resolves.toMatchObject({ data: { recovered: true } })
    expect(adapter).toHaveBeenCalledTimes(2)
    const bodies = adapter.mock.calls.map(([cfg]) => JSON.parse(String(cfg.data)))
    expect(bodies[1]).toEqual(bodies[0])
    expect(bodies[1]).toEqual({ shopId: "shop-1", model: "MARKETPLACE" })
  })

  it("does NOT replay a 429 that is not the rate limiter's (e.g. a service-raised 429 after a write)", async () => {
    script([{ retryAfter: "1", type: "https://jtoye.uk/errors/dsar-rate-limited" }, "ok"])
    await expect(apiClient.post("/api/v1/x", { a: 1 })).rejects.toMatchObject({ response: { status: 429 } })
    expect(adapter).toHaveBeenCalledTimes(1)
  })

  it("does NOT retry a 429 that carries no Retry-After header", async () => {
    script([{}, "ok"])
    await expect(apiClient.get("/api/v1/shops")).rejects.toMatchObject({ response: { status: 429 } })
    expect(adapter).toHaveBeenCalledTimes(1)
  })

  it("does NOT retry a 429 whose Retry-After exceeds 10 seconds", async () => {
    script([{ retryAfter: "11" }, "ok"])
    await expect(apiClient.get("/api/v1/shops")).rejects.toMatchObject({ response: { status: 429 } })
    expect(adapter).toHaveBeenCalledTimes(1)
  })

  it("retries a 429 whose Retry-After is 0 after 1 second (core-java floors sub-second waits to 0)", async () => {
    jest.useFakeTimers()
    script([{ retryAfter: "0" }, "ok"])

    const pending = apiClient.get("/api/v1/shops")
    await jest.advanceTimersByTimeAsync(999)
    expect(adapter).toHaveBeenCalledTimes(1)
    await jest.advanceTimersByTimeAsync(1)
    await expect(pending).resolves.toMatchObject({ data: { recovered: true } })
    expect(adapter).toHaveBeenCalledTimes(2)
  })

  it("does NOT retry a 429 whose Retry-After is blank, negative or an HTTP-date", async () => {
    for (const retryAfter of ["", "-1", "Wed, 21 Oct 2026 07:28:00 GMT"]) {
      adapter.mockReset()
      script([{ retryAfter }, "ok"])
      await expect(apiClient.get("/api/v1/shops")).rejects.toMatchObject({ response: { status: 429 } })
      expect(adapter).toHaveBeenCalledTimes(1)
    }
  })

  it("retries at most once: a second 429 rejects instead of looping", async () => {
    jest.useFakeTimers()
    script([{ retryAfter: "1" }, { retryAfter: "1" }, { retryAfter: "1" }, "ok"])

    const pending = apiClient.get("/api/v1/shops")
    const settled = pending.then(
      () => "resolved",
      (e: { response?: { status?: number } }) => e.response?.status
    )
    await jest.advanceTimersByTimeAsync(5000)
    await expect(settled).resolves.toBe(429)
    expect(adapter).toHaveBeenCalledTimes(2)
  })
})
