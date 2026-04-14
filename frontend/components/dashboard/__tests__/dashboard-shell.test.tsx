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
