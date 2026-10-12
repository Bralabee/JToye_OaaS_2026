/**
 * Phase 37-30 — UnverifiedContactBadge (UI-SPEC § K1 "Card layout", § D1 "Unverified badge";
 * D-18; falsifiability F20).
 *
 * The badge reads STORED DATA and nothing else. 37-27 records `orders.contact_verified` on every
 * storefront order (true when a verified customer token proved the email, false when none did)
 * and leaves it NULL — serialised ABSENT — on every vendor, REST and MCP order and on every
 * order placed before V81. So:
 *
 *   false      -> "Unverified contact"
 *   true       -> nothing (the contact is confirmed; there is no "verified" badge to earn)
 *   null/absent -> nothing (NOT RECORDED is not "unverified")
 *
 * F20's break arm is the point of the null case: a variant that derives the badge from the
 * ABSENCE of a confirmation (`!contactVerified`) renders it on every pre-V81 and vendor order,
 * and the null case here goes red. Recorded at GREEN time in the SUMMARY.
 */
import { render, screen } from "@testing-library/react"
import {
  UNVERIFIED_CONTACT_COPY,
  UNVERIFIED_CONTACT_TITLE,
  UnverifiedContactBadge,
} from "@/components/dashboard/kitchen/unverified-contact-badge"

describe("UnverifiedContactBadge reads the stored fact (F20)", () => {
  it("contactVerified === false renders 'Unverified contact' with the explanatory title and sr text", () => {
    const { container } = render(<UnverifiedContactBadge contactVerified={false} />)

    const badge = screen.getByText(UNVERIFIED_CONTACT_COPY).closest("[data-testid='unverified-contact']")
    expect(badge).not.toBeNull()
    expect(badge).toHaveAttribute("title", UNVERIFIED_CONTACT_TITLE)
    // Read by word and shape, never by colour alone: white with a dashed slate border and the
    // UserX glyph (UI-SPEC § D1 "Unverified badge").
    expect(badge).toHaveClass("bg-white", "border-dashed", "border-slate-500", "text-slate-700")
    expect(container.querySelector("svg.lucide-user-x")).not.toBeNull()
    expect(screen.getByText(UNVERIFIED_CONTACT_TITLE)).toHaveClass("sr-only")
  })

  it("contactVerified === null renders nothing (NOT RECORDED is not unverified)", () => {
    const { container } = render(<UnverifiedContactBadge contactVerified={null} />)
    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByText(UNVERIFIED_CONTACT_COPY)).toBeNull()
  })

  it("an absent field renders nothing", () => {
    const { container } = render(<UnverifiedContactBadge />)
    expect(container).toBeEmptyDOMElement()
  })

  it("contactVerified === true renders nothing", () => {
    const { container } = render(<UnverifiedContactBadge contactVerified />)
    expect(container).toBeEmptyDOMElement()
  })
})
