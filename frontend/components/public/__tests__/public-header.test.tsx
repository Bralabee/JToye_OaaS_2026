/**
 * PublicHeader wordmark home-target contract.
 *
 * The wordmark ALWAYS returns you to the landing page (/), from every public
 * surface. It used to be context-aware (/track homed to /shop), which meant the
 * logo landed you somewhere different depending on where you clicked it — the
 * reported inconsistent-nav bug. The storefront chrome (app/shop/layout.tsx)
 * points its own wordmark at / too, so the rule holds app-wide.
 */
import { render, screen } from "@testing-library/react"
import { usePathname } from "next/navigation"
import { PublicHeader } from "@/components/public/public-header"

const mockedPathname = usePathname as jest.Mock

function wordmarkHref() {
  // The wordmark is the only link whose accessible name contains "Toye".
  return screen.getByRole("link", { name: /toye/i }).getAttribute("href")
}

// Plain per-case blocks (deliberately not a table-driven `each` form): the
// docs-freshness metric gate counts literal block openings, so a table would
// make this file's coverage invisible to it.
describe("PublicHeader wordmark home target", () => {
  afterEach(() => mockedPathname.mockReturnValue("/"))

  function expectHomeFrom(pathname: string) {
    mockedPathname.mockReturnValue(pathname)
    render(<PublicHeader />)
    expect(wordmarkHref()).toBe("/")
  }

  it("marketing landing (/) -> wordmark links to /", () => {
    expectHomeFrom("/")
  })

  it("/for-operators -> wordmark links to /", () => {
    expectHomeFrom("/for-operators")
  })

  it("/track -> wordmark links to /, NOT /shop (it used to home to the shop)", () => {
    expectHomeFrom("/track")
  })

  it("/track/ORD-123 (nested track route) -> wordmark still links to /", () => {
    expectHomeFrom("/track/ORD-123")
  })

  it("/business-model-guide -> wordmark links to /", () => {
    expectHomeFrom("/business-model-guide")
  })

  it("/competitive -> wordmark links to /", () => {
    expectHomeFrom("/competitive")
  })
})
