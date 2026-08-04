import { render, screen, within } from "@testing-library/react"

// Config-injected support channel (GLOBAL_RULE_6). Set BEFORE the component
// reads process.env at render time — resolveSupportChannel prefers the URL.
process.env.NEXT_PUBLIC_SUPPORT_URL = "https://help.jtoye.test/payments"
delete process.env.NEXT_PUBLIC_SUPPORT_EMAIL

import ReturnPage, { metadata as returnMetadata } from "../return/page"
import RefreshPage, { metadata as refreshMetadata } from "../refresh/page"

/**
 * #295 — the Stripe Connect return/refresh redirect used to 404.
 *
 * These are LANDING DESTINATIONS, so the assertions are about the three things
 * a cold arrival depends on: the page renders at all, it never claims an
 * outcome Stripe did not give us, and it is never a dead end.
 */

describe("Stripe Connect return page (stripe.connect.return-url)", () => {
  it("renders without a 404 and names the arrival", () => {
    render(<ReturnPage />)
    expect(
      screen.getByRole("heading", { level: 1, name: /back from stripe/i })
    ).toBeInTheDocument()
  })

  it("does NOT claim onboarding succeeded — Stripe passes no state through return_url", () => {
    const { container } = render(<ReturnPage />)
    const text = container.textContent ?? ""
    // The whole point of the page: a vendor who ABANDONED the flow lands here too,
    // so any success/connected/complete claim would be wrong for them.
    expect(text).not.toMatch(/\b(connected|successfully connected|setup complete|all set)\b/i)
    expect(text).toMatch(/doesn't tell us/i)
  })

  it("explains that Stripe notifies J'Toye asynchronously", () => {
    render(<ReturnPage />)
    expect(screen.getByText(/notifies J'Toye the moment your account is cleared/i))
      .toBeInTheDocument()
  })

  it("badges the arrival as awaiting Stripe — true whether the vendor finished or bailed", () => {
    render(<ReturnPage />)
    expect(screen.getByText("Awaiting Stripe")).toBeInTheDocument()
    // A vendor who abandoned the flow lands here too, so a reassuring badge would
    // be false for them. Guard the two tempting-but-wrong labels explicitly.
    expect(screen.queryByText(/^(No action needed|Connected)$/)).not.toBeInTheDocument()
  })

  it("tells a vendor who did NOT finish that their progress is kept", () => {
    render(<ReturnPage />)
    expect(screen.getByText(/if you didn't finish, nothing is lost/i)).toBeInTheDocument()
  })

  it("is not a dead end — both onward links point at real dashboard routes", () => {
    render(<ReturnPage />)
    expect(screen.getByRole("link", { name: /go to dashboard/i })).toHaveAttribute(
      "href",
      "/dashboard"
    )
    expect(screen.getByRole("link", { name: /view finance/i })).toHaveAttribute(
      "href",
      "/dashboard/finance"
    )
  })

  it("is marked noindex — an authenticated redirect target must not be crawled", () => {
    expect(returnMetadata.robots).toEqual({ index: false, follow: false })
  })
})

describe("Stripe Connect refresh page (stripe.connect.refresh-url)", () => {
  it("renders without a 404 and names the expired-link state", () => {
    render(<RefreshPage />)
    expect(
      screen.getByRole("heading", { level: 1, name: /stripe link has expired/i })
    ).toBeInTheDocument()
  })

  it("reassures that nothing is broken and prior details are kept", () => {
    render(<RefreshPage />)
    expect(screen.getByText(/nothing has gone wrong with your account/i)).toBeInTheDocument()
    expect(screen.getByText(/won't start from scratch/i)).toBeInTheDocument()
  })

  it("badges the arrival as needing action — the vendor must obtain a new link", () => {
    render(<RefreshPage />)
    expect(screen.getByText("Action needed")).toBeInTheDocument()
  })

  it("offers no self-service mint button — the only mint endpoint is admin-gated", () => {
    render(<RefreshPage />)
    // A button that would 403 (hasRole('admin')) or 404 (no tenant id in session)
    // is worse than an honest instruction. Guard against someone adding one
    // without also adding a tenant-scoped endpoint.
    expect(screen.queryByRole("button")).not.toBeInTheDocument()
    expect(
      screen.getByText(/ask your J'Toye administrator, or our support team/i)
    ).toBeInTheDocument()
  })

  it("is not a dead end — both onward links point at real dashboard routes", () => {
    render(<RefreshPage />)
    expect(screen.getByRole("link", { name: /go to dashboard/i })).toHaveAttribute(
      "href",
      "/dashboard"
    )
    expect(screen.getByRole("link", { name: /view finance/i })).toHaveAttribute(
      "href",
      "/dashboard/finance"
    )
  })

  it("is marked noindex — an authenticated redirect target must not be crawled", () => {
    expect(refreshMetadata.robots).toEqual({ index: false, follow: false })
  })
})

describe("support channel (config-injected, GLOBAL_RULE_6)", () => {
  it("renders the configured support URL as an external link on both pages", () => {
    for (const Page of [ReturnPage, RefreshPage]) {
      const { container, unmount } = render(<Page />)
      const link = within(container).getByRole("link", { name: /contact support/i })
      expect(link).toHaveAttribute("href", "https://help.jtoye.test/payments")
      expect(link).toHaveAttribute("target", "_blank")
      expect(link).toHaveAttribute("rel", "noopener noreferrer")
      unmount()
    }
  })

  it("degrades to plain copy — never a dead link — when no channel is configured", () => {
    const url = process.env.NEXT_PUBLIC_SUPPORT_URL
    delete process.env.NEXT_PUBLIC_SUPPORT_URL
    try {
      render(<RefreshPage />)
      expect(screen.queryByRole("link", { name: /contact support/i })).not.toBeInTheDocument()
      expect(
        screen.getByText(/contact your J'Toye administrator or account manager/i)
      ).toBeInTheDocument()
    } finally {
      process.env.NEXT_PUBLIC_SUPPORT_URL = url
    }
  })
})
