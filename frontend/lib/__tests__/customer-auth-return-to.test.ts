import { safeReturnTo } from "@/lib/customer-auth"

/**
 * `/shop/signin?next=…` puts the post-login destination in a URL, which means
 * anyone can craft it into a link. The value is eventually handed to
 * `router.replace()` by the OAuth callback, so an unvalidated one is an open
 * redirect — and a persuasive one, because the victim really did just authenticate
 * with us immediately before being bounced somewhere else.
 *
 * Each rejection case below is a distinct bypass, not a variation on one:
 * a plain absolute URL, the protocol-relative form that defeats a naive
 * `startsWith("/")` check, the backslash variants some browsers normalise into
 * that form, and `javascript:` which is an XSS sink rather than a navigation.
 */
describe("safeReturnTo", () => {
  const FALLBACK = "/shop"

  describe("keeps genuine same-origin destinations", () => {
    it.each([
      ["/shop", "/shop"],
      ["/shop/orders", "/shop/orders"],
      ["/shop/some-kitchen-a1b2c3d4", "/shop/some-kitchen-a1b2c3d4"],
      ["/shop/some-kitchen/checkout", "/shop/some-kitchen/checkout"],
      ["/track?order=JT-1234", "/track?order=JT-1234"],
      ["/shop/orders#recent", "/shop/orders#recent"],
    ])("%s -> %s", (input, expected) => {
      expect(safeReturnTo(input)).toBe(expected)
    })
  })

  describe("rejects off-origin destinations", () => {
    it("rejects an absolute http(s) URL", () => {
      expect(safeReturnTo("https://evil.example/harvest")).toBe(FALLBACK)
      expect(safeReturnTo("http://evil.example")).toBe(FALLBACK)
    })

    it("rejects a protocol-relative URL, which a naive startsWith('/') check accepts", () => {
      // The single most likely bypass: it begins with "/" but a browser treats it
      // as absolute, so `router.replace("//evil.example")` leaves the origin.
      expect(safeReturnTo("//evil.example")).toBe(FALLBACK)
      expect(safeReturnTo("//evil.example/shop/orders")).toBe(FALLBACK)
    })

    it("rejects backslash variants that normalise to a protocol-relative URL", () => {
      expect(safeReturnTo("/\\evil.example")).toBe(FALLBACK)
      expect(safeReturnTo("\\\\evil.example")).toBe(FALLBACK)
      expect(safeReturnTo("/shop\\@evil.example")).toBe(FALLBACK)
    })

    it("rejects a javascript: payload", () => {
      // Not a redirect at all — a script sink.
      expect(safeReturnTo("javascript:alert(1)")).toBe(FALLBACK)
      expect(safeReturnTo("JaVaScRiPt:alert(1)")).toBe(FALLBACK)
    })

    it("rejects a bare host, which would resolve relative to the current directory", () => {
      expect(safeReturnTo("evil.example")).toBe(FALLBACK)
      expect(safeReturnTo("shop/orders")).toBe(FALLBACK)
    })

    it("rejects any other scheme", () => {
      expect(safeReturnTo("data:text/html,<script>alert(1)</script>")).toBe(FALLBACK)
      expect(safeReturnTo("mailto:someone@evil.example")).toBe(FALLBACK)
    })
  })

  describe("handles absent input", () => {
    it.each([[null], [undefined], [""], ["   "]])("%p -> the fallback", (input) => {
      expect(safeReturnTo(input as string | null | undefined)).toBe(FALLBACK)
    })
  })

  it("does not reject a path merely for containing a host-like substring", () => {
    // The guard is about URL STRUCTURE, not about spotting suspicious words. A
    // shop slug can legitimately look like anything, and over-rejecting would
    // silently dump real shoppers on /shop instead of where they were.
    expect(safeReturnTo("/shop/evil.example-kitchen-a1b2c3d4")).toBe(
      "/shop/evil.example-kitchen-a1b2c3d4"
    )
    expect(safeReturnTo("/shop/https-kitchen-a1b2c3d4")).toBe(
      "/shop/https-kitchen-a1b2c3d4"
    )
  })
})
