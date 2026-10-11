/**
 * 37-18 (D-14, UXT-021): a 401 the one silent refresh cannot fix is ANNOUNCED before anything
 * navigates, so the kitchen board can stop loudly instead of quietly becoming a sign-in page.
 *
 *   - the api-client dispatches a cancellable `jtoye:session-lapsed` on `window`;
 *   - nobody claims it  -> the browser goes to /auth/signin, exactly as before (every other route);
 *   - a listener claims it (`preventDefault`) -> no navigation, and the request still rejects.
 *
 * The navigation is observed through `navigateToSignIn` (lib/session-lapse.ts), mocked here; the
 * page-level suite (kds-board-stopped.test.tsx) observes the real jsdom navigation instead.
 */
jest.mock("next-auth/react", () => ({
  getSession: jest.fn(),
}))
jest.mock("@/lib/session-lapse", () => ({
  ...jest.requireActual("@/lib/session-lapse"),
  navigateToSignIn: jest.fn(),
}))

import { getSession } from "next-auth/react"
import { navigateToSignIn, SESSION_LAPSED_EVENT } from "@/lib/session-lapse"
// eslint-disable-next-line @typescript-eslint/no-require-imports
const apiClientModule = require("../api-client")
const apiClient = apiClientModule.default as import("axios").AxiosInstance
const __testing = apiClientModule.__testing as { resetRefresh: () => void }

const adapter = jest.fn()
apiClient.defaults.adapter = adapter
apiClient.interceptors.request.use((config) => {
  config.adapter = adapter
  return config
})

function httpError(status: number, config: unknown, data: Record<string, unknown> = {}) {
  const e = new Error(`HTTP ${status}`) as Error & Record<string, unknown>
  e.isAxiosError = true
  e.config = config
  e.response = { status, data, headers: {}, statusText: "err", config }
  return e
}

const signedIn = { accessToken: "token-1", user: { tenantId: "t-1", id: "u-1" }, expires: "2099-12-31" }

const listeners: Array<(e: Event) => void> = []
function listen(fn: (e: Event) => void) {
  listeners.push(fn)
  window.addEventListener(SESSION_LAPSED_EVENT, fn)
}

beforeEach(() => {
  adapter.mockReset()
  __testing.resetRefresh()
  ;(navigateToSignIn as jest.Mock).mockReset()
  ;(getSession as jest.Mock).mockReset()
})

afterEach(() => {
  while (listeners.length) window.removeEventListener(SESSION_LAPSED_EVENT, listeners.pop()!)
})

/** Every request 401s, and the refresh returns a session with no access token: a real lapse. */
function lapse() {
  adapter.mockImplementation((cfg: unknown) => Promise.reject(httpError(401, cfg)))
  ;(getSession as jest.Mock).mockResolvedValue({ user: {}, error: "RefreshTokenError" })
}

describe("api-client: a lapsed session is announced before anything navigates (D-14)", () => {
  it("dispatches a cancellable jtoye:session-lapsed event on a 401 the refresh cannot fix", async () => {
    lapse()
    const seen: Event[] = []
    listen((e) => seen.push(e))

    await expect(apiClient.get("/api/v1/orders/kitchen?shopId=s-1")).rejects.toMatchObject({
      response: { status: 401 },
    })

    expect(seen).toHaveLength(1)
    expect(seen[0].cancelable).toBe(true)
  })

  it("redirects to /auth/signin when no listener claims the lapse (every other route, as today)", async () => {
    lapse()
    listen(() => {
      /* a listener that only observes does not claim it */
    })

    await expect(apiClient.get("/api/v1/products")).rejects.toMatchObject({ response: { status: 401 } })

    expect(navigateToSignIn).toHaveBeenCalledTimes(1)
  })

  it("does not navigate when a listener claims the lapse, and the request still rejects", async () => {
    lapse()
    const seen: Event[] = []
    listen((e) => {
      seen.push(e)
      e.preventDefault()
    })

    await expect(apiClient.get("/api/v1/orders/kitchen?shopId=s-1")).rejects.toMatchObject({
      response: { status: 401 },
    })

    expect(seen).toHaveLength(1)
    expect(seen[0].defaultPrevented).toBe(true)
    expect(navigateToSignIn).not.toHaveBeenCalled()
  })

  it("announces nothing and does not navigate when the silent refresh fixes the 401", async () => {
    let calls = 0
    adapter.mockImplementation((cfg: unknown) => {
      calls += 1
      return calls === 1
        ? Promise.reject(httpError(401, cfg))
        : Promise.resolve({ data: { ok: true }, status: 200, statusText: "OK", headers: {}, config: cfg })
    })
    ;(getSession as jest.Mock).mockResolvedValue(signedIn)
    const seen: Event[] = []
    listen((e) => seen.push(e))

    const res = await apiClient.get("/api/v1/orders/kitchen?shopId=s-1")

    expect(res.data).toEqual({ ok: true })
    expect(seen).toHaveLength(0)
    expect(navigateToSignIn).not.toHaveBeenCalled()
  })

  it("never announces a lapse for a 403: access ending is a different signal", async () => {
    adapter.mockImplementation((cfg: unknown) =>
      Promise.reject(httpError(403, cfg, { type: "https://jtoye.uk/errors/shop-access-denied" }))
    )
    ;(getSession as jest.Mock).mockResolvedValue(signedIn)
    const seen: Event[] = []
    listen((e) => seen.push(e))

    await expect(apiClient.get("/api/v1/orders/kitchen?shopId=s-1")).rejects.toMatchObject({
      response: { status: 403 },
    })

    expect(seen).toHaveLength(0)
    expect(navigateToSignIn).not.toHaveBeenCalled()
  })
})
