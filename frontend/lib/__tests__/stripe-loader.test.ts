/**
 * Issue #957 (phase 30, plan 30-01) — the ONE place the browser reads the Stripe publishable key
 * and loads Stripe.js.
 *
 * The staging checkout never showed a payment step because the frontend image was built without
 * NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY, so the page's in-line `getStripe()` returned null and the
 * render fell through to the form. The build channel is fixed in the Dockerfile/CI; this module
 * is the read side, and these tests pin its contract:
 *
 *  - publishableKey(): an unset or empty key is "no key" (null), never "";
 *  - loadStripeOnce(): one loadStripe call per connected account, ONE argument when there is no
 *    account (the #793 lazy-load suite asserts the exact call), `{ stripeAccount }` when there is;
 *  - no key → no loadStripe call at all (nothing injected from js.stripe.com);
 *  - a load that rejects or resolves null is FORGOTTEN, so a "Try again" really loads again
 *    instead of handing back the same dead promise for the life of the page;
 *  - the default `@stripe/stripe-js` entry is never imported (#793: its import alone injects
 *    js.stripe.com) — its factory below throws, so importing it fails the loader's import.
 *
 * The loader is imported INSIDE each test (as stripe-lazy-load.test.tsx does for the page), so a
 * missing or broken module is a failure of a named test rather than a crash of the whole file.
 */

import { loadStripe } from "@stripe/stripe-js/pure"

jest.mock("@stripe/stripe-js", () => {
  throw new Error("default Stripe entry imported")
})

jest.mock("@stripe/stripe-js/pure", () => ({
  loadStripe: jest.fn(),
}))

const mockedLoadStripe = loadStripe as unknown as jest.Mock

const KEY = "pk_test_loader957abcdef"
const ORIGINAL_KEY = process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY

/** A stand-in for a resolved Stripe instance: what a successful loadStripe resolves to. */
const FAKE_STRIPE = { __fakeStripe: true }

async function freshLoader() {
  const loader = await import("@/lib/stripe-loader")
  loader.resetStripeLoaderForTests()
  return loader
}

/** Let promise handlers attached by the loader run. */
async function flush() {
  for (let i = 0; i < 5; i++) await Promise.resolve()
}

beforeEach(() => {
  mockedLoadStripe.mockReset()
  mockedLoadStripe.mockImplementation(() => Promise.resolve(FAKE_STRIPE))
})

afterEach(() => {
  if (ORIGINAL_KEY === undefined) delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
  else process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = ORIGINAL_KEY
})

describe("publishableKey (#957)", () => {
  it("is null when the key is unset", async () => {
    delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
    const { publishableKey } = await freshLoader()
    expect(publishableKey()).toBeNull()
  })

  it("is null when the key is the empty string (an image built without the build-arg inlines \"\")", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = ""
    const { publishableKey } = await freshLoader()
    expect(publishableKey()).toBeNull()
  })

  it("returns the key when one is set, read at CALL time", async () => {
    delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
    const { publishableKey } = await freshLoader()
    expect(publishableKey()).toBeNull()
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = KEY
    expect(publishableKey()).toBe(KEY)
  })
})

describe("loadStripeOnce (#957, #793)", () => {
  it("calls loadStripe ONCE, with exactly one argument, however many times it is asked", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = KEY
    const { loadStripeOnce } = await freshLoader()

    const first = loadStripeOnce()
    const second = loadStripeOnce()

    expect(mockedLoadStripe).toHaveBeenCalledTimes(1)
    expect(mockedLoadStripe.mock.calls[0]).toEqual([KEY])
    expect(first).toBe(mockedLoadStripe.mock.results[0].value)
    expect(second).toBe(first)
    await expect(first).resolves.toBe(FAKE_STRIPE)
  })

  it("loads a SEPARATE Stripe for a connected account, passing { stripeAccount }, and memoises that too", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = KEY
    const { loadStripeOnce } = await freshLoader()

    const platform = loadStripeOnce()
    const connected = loadStripeOnce("acct_1")
    const connectedAgain = loadStripeOnce("acct_1")

    expect(mockedLoadStripe).toHaveBeenCalledTimes(2)
    expect(mockedLoadStripe.mock.calls[0]).toEqual([KEY])
    expect(mockedLoadStripe.mock.calls[1]).toEqual([KEY, { stripeAccount: "acct_1" }])
    expect(connected).not.toBe(platform)
    expect(connectedAgain).toBe(connected)
  })

  it("returns null and never calls loadStripe when no key is configured", async () => {
    delete process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
    const { loadStripeOnce } = await freshLoader()

    expect(loadStripeOnce()).toBeNull()
    expect(loadStripeOnce("acct_1")).toBeNull()
    expect(mockedLoadStripe).not.toHaveBeenCalled()
  })

  it("returns null and never calls loadStripe when the key is the empty string", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = ""
    const { loadStripeOnce } = await freshLoader()

    expect(loadStripeOnce()).toBeNull()
    expect(mockedLoadStripe).not.toHaveBeenCalled()
  })

  it("forgets a load that REJECTS, so the next call loads again", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = KEY
    mockedLoadStripe.mockImplementationOnce(() => Promise.reject(new Error("js.stripe.com unreachable")))
    const { loadStripeOnce } = await freshLoader()

    const failed = loadStripeOnce()
    await expect(failed).rejects.toThrow("js.stripe.com unreachable")
    await flush()

    const retried = loadStripeOnce()
    expect(mockedLoadStripe).toHaveBeenCalledTimes(2)
    expect(retried).not.toBe(failed)
    await expect(retried).resolves.toBe(FAKE_STRIPE)
  })

  it("forgets a load that resolves NULL, so the next call loads again", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = KEY
    mockedLoadStripe.mockImplementationOnce(() => Promise.resolve(null))
    const { loadStripeOnce } = await freshLoader()

    const empty = loadStripeOnce()
    await expect(empty).resolves.toBeNull()
    await flush()

    const retried = loadStripeOnce()
    expect(mockedLoadStripe).toHaveBeenCalledTimes(2)
    await expect(retried).resolves.toBe(FAKE_STRIPE)
  })

  it("keeps a SUCCESSFUL load memoised after it resolves", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = KEY
    const { loadStripeOnce } = await freshLoader()

    const first = loadStripeOnce()
    await expect(first).resolves.toBe(FAKE_STRIPE)
    await flush()

    expect(loadStripeOnce()).toBe(first)
    expect(mockedLoadStripe).toHaveBeenCalledTimes(1)
  })

  it("resetStripeLoaderForTests clears the memo", async () => {
    process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY = KEY
    const loader = await freshLoader()

    loader.loadStripeOnce()
    loader.resetStripeLoaderForTests()
    loader.loadStripeOnce()

    expect(mockedLoadStripe).toHaveBeenCalledTimes(2)
  })
})

describe("STRIPE_APPEARANCE (UI-SPEC Color)", () => {
  it("uses the brand graphics colour #c2410c in place of #f97316", async () => {
    const { STRIPE_APPEARANCE } = await freshLoader()
    expect(STRIPE_APPEARANCE).toEqual({
      theme: "stripe",
      variables: {
        colorPrimary: "#c2410c",
        borderRadius: "12px",
        fontFamily: "system-ui, -apple-system, sans-serif",
      },
    })
  })
})
