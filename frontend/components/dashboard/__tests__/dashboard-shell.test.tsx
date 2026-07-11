/**
 * Smoke test for the client-side DashboardShell. The real auth check lives
 * in the Server Component parent (frontend/app/dashboard/layout.tsx) and is
 * exercised by `next build` + middleware integration; here we just verify
 * the shell renders the passed children and the sidebar chrome.
 */

import { render, screen } from "@testing-library/react"
import { DashboardShell } from "../dashboard-shell"

// The real Sidebar pulls in next-auth, next/navigation etc. We mock it so the
// shell test stays focused.
jest.mock("@/components/dashboard/sidebar", () => ({
  Sidebar: () => <aside data-testid="sidebar-stub">sidebar</aside>,
}))

describe("DashboardShell", () => {
  it("renders sidebar chrome and child content", () => {
    render(
      <DashboardShell>
        <div data-testid="content">hello</div>
      </DashboardShell>
    )
    expect(screen.getByTestId("sidebar-stub")).toBeInTheDocument()
    expect(screen.getByTestId("content")).toHaveTextContent("hello")
  })
})

describe("Sidebar navigation", () => {
  // The shell test above stubs the Sidebar; render the REAL one here to assert
  // the "Go live" nav item ships. next-auth/next-navigation are mocked globally
  // in jest.setup.js. jsdom lacks matchMedia, which the sidebar's theme effect
  // reads — stub it here.
  beforeAll(() => {
    Object.defineProperty(window, "matchMedia", {
      writable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: jest.fn(),
        removeEventListener: jest.fn(),
        addListener: jest.fn(),
        removeListener: jest.fn(),
        dispatchEvent: jest.fn(),
      }),
    })
  })

  it("shows a 'Go live' link to /dashboard/onboarding", () => {
    const { Sidebar } = jest.requireActual("@/components/dashboard/sidebar") as {
      Sidebar: () => JSX.Element
    }
    render(<Sidebar />)
    const link = screen.getByRole("link", { name: /go live/i })
    expect(link).toHaveAttribute("href", "/dashboard/onboarding")
  })
})
