/**
 * OAuth callback page — the missing-code branch must be a RENDER-TIME
 * derivation, not a mount-effect `setState` (#202 / plan 34-04).
 *
 * WHY A SERVER RENDER IS THE LOAD-BEARING ASSERTION. "The error is derived
 * during render" is not observable through `@testing-library/react`:
 * `render()` flushes effects inside `act`, so a value written by a mount
 * effect and a value derived during render produce an identical DOM.
 * `renderToStaticMarkup` is the one environment where effects provably never
 * run, so the error copy can only appear in its output if it was computed
 * during render.
 *
 * That is not a contrivance for the test's benefit: `app/layout.tsx` sets
 * `dynamic = "force-dynamic"`, so this page really is server-rendered on every
 * request, and before this change the server could only ever emit the spinner.
 *
 * The exchange path stays in an effect on purpose (RESEARCH's measured
 * rule-shape row B: a `.then()` continuation is not flagged by
 * `react-hooks/set-state-in-effect`), so its three behaviours are asserted
 * through the browser-like DOM instead.
 */
import { render, screen, waitFor } from "@testing-library/react"
import { StrictMode, type ReactElement } from "react"
import { TextEncoder as NodeTextEncoder, TextDecoder as NodeTextDecoder } from "node:util"
import { useSearchParams, useRouter } from "next/navigation"
import { handleCallback, getAuthReturnUrl } from "@/lib/customer-auth"
import AuthCallbackPage from "../page"

/**
 * `react-dom/server` resolves to its BROWSER build under `testEnvironment:
 * jsdom`, and that build reaches for two APIs jsdom does not implement:
 * `MessageChannel` (its scheduler) and `TextEncoder` (its byte writer). Without
 * both, the import throws before a single assertion runs — which reads in the
 * output as a broken test rather than as a missing browser API. Both were
 * measured in that order: MessageChannel first, then TextEncoder.
 *
 * The channel is a local fake rather than `node:worker_threads`'s: react-dom
 * holds one at module scope for the life of the process, and a real Node
 * MessagePort re-`ref`s itself the moment `onmessage` is assigned, so jest
 * then hangs and needs `--forceExit` — which would mask any genuinely leaked
 * handle in this file from every future reader. Measured: with the
 * worker_threads channel, `npx jest app/shop/auth/callback` printed its
 * results and then sat until the 240s timeout (rc=124).
 *
 * The surface react-dom actually uses is exactly two members
 * (`react-dom-server.browser.development.js:8818-8822`): a settable
 * `port1.onmessage` and `port2.postMessage`.
 */
if (typeof globalThis.MessageChannel === "undefined") {
  type FakePort = { onmessage: ((event: { data: unknown }) => void) | null }
  ;(globalThis as unknown as { MessageChannel: unknown }).MessageChannel =
    class FakeMessageChannel {
      port1: FakePort = { onmessage: null }
      port2: { postMessage: (data: unknown) => void }

      constructor() {
        const port1 = this.port1
        this.port2 = {
          postMessage: (data: unknown) => {
            // A macrotask, matching a real port's ordering, but on a timer that
            // clears itself so no handle outlives the run.
            setTimeout(() => port1.onmessage?.({ data }), 0)
          },
        }
      }
    }
}
if (typeof globalThis.TextEncoder === "undefined") {
  ;(globalThis as unknown as { TextEncoder: unknown }).TextEncoder = NodeTextEncoder
}
if (typeof globalThis.TextDecoder === "undefined") {
  ;(globalThis as unknown as { TextDecoder: unknown }).TextDecoder = NodeTextDecoder
}

/**
 * Imported lazily on purpose: a static `import` is hoisted above the polyfill
 * above, so the module body would still execute first and still throw.
 */
async function serverRender(element: ReactElement): Promise<string> {
  const { renderToStaticMarkup } = await import("react-dom/server")
  return renderToStaticMarkup(element)
}

jest.mock("@/lib/customer-auth", () => ({
  handleCallback: jest.fn(),
  getAuthReturnUrl: jest.fn(),
}))

const mockedHandleCallback = handleCallback as jest.MockedFunction<typeof handleCallback>
const mockedGetAuthReturnUrl = getAuthReturnUrl as jest.MockedFunction<typeof getAuthReturnUrl>

/** Drive the globally-mocked `useSearchParams` with a real URLSearchParams. */
function setSearchParams(params: Record<string, string>) {
  ;(useSearchParams as jest.Mock).mockReturnValue(new URLSearchParams(params))
}

const replace = jest.fn()

beforeEach(() => {
  jest.clearAllMocks()
  ;(useRouter as jest.Mock).mockReturnValue({
    push: jest.fn(),
    replace,
    back: jest.fn(),
    forward: jest.fn(),
    refresh: jest.fn(),
    prefetch: jest.fn(),
  })
  mockedGetAuthReturnUrl.mockReturnValue("/shop/brixton-village-grill")
  // Default: a never-settling exchange, so the spinner branch is observable.
  mockedHandleCallback.mockReturnValue(new Promise(() => {}))
})

describe("OAuth callback page — missing authorization code", () => {
  it("emits the error copy from a SERVER render, where no effect can run", async () => {
    setSearchParams({})
    const html = await serverRender(<AuthCallbackPage />)

    // The load-bearing assertion. False on the mount-effect version.
    expect(html.includes("No authorization code received.")).toBe(true)
    // And the way back out is served with it, not after a hydration round-trip.
    expect(html.includes("Back to shop")).toBe(true)
    expect(html.includes('href="/shop"')).toBe(true)
    // The spinner is NOT what a code-less callback serves.
    expect(html.includes("animate-spin")).toBe(false)
    expect(html.includes("Signing you in")).toBe(false)
  })

  it("never attempts a token exchange when there is no code", async () => {
    setSearchParams({})
    render(<AuthCallbackPage />)

    expect(await screen.findByText("No authorization code received.")).toBeInTheDocument()
    expect(screen.getByRole("link", { name: "Back to shop" })).toHaveAttribute("href", "/shop")
    expect(mockedHandleCallback).not.toHaveBeenCalled()
    expect(replace).not.toHaveBeenCalled()
  })
})

describe("OAuth callback page — the exchange path is unchanged", () => {
  it("shows the spinner and exchanges the code with the returned state", async () => {
    setSearchParams({ code: "auth-code-1", state: "state-1" })
    const { container } = render(<AuthCallbackPage />)

    expect(container.querySelector(".animate-spin")).not.toBeNull()
    expect(screen.getByText("Signing you in...")).toBeInTheDocument()
    await waitFor(() => expect(mockedHandleCallback).toHaveBeenCalledTimes(1))
    expect(mockedHandleCallback).toHaveBeenCalledWith("auth-code-1", "state-1")
  })

  it("redirects to the validated return URL when a profile comes back", async () => {
    setSearchParams({ code: "auth-code-2", state: "state-2" })
    mockedHandleCallback.mockResolvedValue({
      id: "cust-1",
      email: "shopper@example.com",
      name: "Shopper",
    } as Awaited<ReturnType<typeof handleCallback>>)

    render(<AuthCallbackPage />)

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/shop/brixton-village-grill"))
    expect(mockedGetAuthReturnUrl).toHaveBeenCalledTimes(1)
  })

  it("shows the failure copy when the exchange resolves without a profile", async () => {
    setSearchParams({ code: "auth-code-3", state: "state-3" })
    mockedHandleCallback.mockResolvedValue(null)

    render(<AuthCallbackPage />)

    expect(
      await screen.findByText("Authentication failed. Please try again."),
    ).toBeInTheDocument()
    expect(screen.getByRole("link", { name: "Back to shop" })).toHaveAttribute("href", "/shop")
    expect(replace).not.toHaveBeenCalled()
  })

  it("exchanges a one-time code EXACTLY once under a strict-mode double mount", async () => {
    // T-34-04-03: a second exchange of a one-time authorization code is a real
    // failure mode (the IdP rejects the replay and the shopper is bounced to an
    // error), not a cosmetic double-render. React 18/19 StrictMode mounts,
    // unmounts and remounts every effect in development, which is the exact
    // shape that burns the code.
    setSearchParams({ code: "one-time-code", state: "state-4" })
    mockedHandleCallback.mockResolvedValue(null)

    render(
      <StrictMode>
        <AuthCallbackPage />
      </StrictMode>,
    )

    await screen.findByText("Authentication failed. Please try again.")
    expect(mockedHandleCallback).toHaveBeenCalledTimes(1)
  })
})
