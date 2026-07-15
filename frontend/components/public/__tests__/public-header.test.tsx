/**
 * PublicHeader wordmark home-target contract.
 *
 * The logo returns you to the "home" of the surface you're actually on:
 *  - /track is a customer order surface (part of the shopping app) so its
 *    wordmark goes to /shop — fixing the reported bug where /track kicked
 *    shoppers back out to the marketing landing.
 *  - Marketing surfaces (/, /for-operators, /business-model-guide) home to /.
 * (/shop* pages use the shop layout's own wordmark, already -> /shop, so the
 * whole app is consistent: any customer surface -> /shop, marketing -> /.)
 */
import { render, screen } from "@testing-library/react"
import { usePathname } from "next/navigation"
import { PublicHeader } from "@/components/public/public-header"

const mockedPathname = usePathname as jest.Mock

function wordmarkHref() {
  // The wordmark is the only link whose accessible name contains "Toye".
  return screen.getByRole("link", { name: /toye/i }).getAttribute("href")
}

describe("PublicHeader wordmark home target", () => {
  afterEach(() => mockedPathname.mockReturnValue("/"))

  it("marketing landing (/) -> wordmark links to /", () => {
    mockedPathname.mockReturnValue("/")
    render(<PublicHeader />)
    expect(wordmarkHref()).toBe("/")
  })

  it("/for-operators (marketing) -> wordmark links to /", () => {
    mockedPathname.mockReturnValue("/for-operators")
    render(<PublicHeader />)
    expect(wordmarkHref()).toBe("/")
  })

  it("/track (customer order surface) -> wordmark links to /shop, not the landing", () => {
    mockedPathname.mockReturnValue("/track")
    render(<PublicHeader />)
    expect(wordmarkHref()).toBe("/shop")
  })

  it("/track/ORD-123 (nested track route) -> wordmark still links to /shop", () => {
    mockedPathname.mockReturnValue("/track/ORD-123")
    render(<PublicHeader />)
    expect(wordmarkHref()).toBe("/shop")
  })
})
