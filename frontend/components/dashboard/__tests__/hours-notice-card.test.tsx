/**
 * 37-21 Task 3 (UI-SPEC K4 vendor notices, D-25, RWO-022): every vendor whose shop takes orders "at
 * any time" by legacy, or whose hours could not be read, is told so on the dashboard home and can fix
 * it in one click.
 *
 * The notice reads `hoursSource` from the API and never re-parses the free text. The fixtures in the
 * last block are built so a card computed from `legacyOpeningHours` gives the WRONG answer: a
 * STRUCTURED shop whose old unreadable text is still on the row, and a LEGACY shop with text that
 * reads cleanly.
 */

import { render, screen, within } from "@testing-library/react"
import { HoursNoticeCard } from "@/components/dashboard/hours-notice-card"
import type { Shop } from "@/types/api"

function shop(id: string, name: string, extra: Partial<Shop> = {}): Shop {
  return {
    id,
    tenantId: "tenant-1",
    name,
    address: "1 High St",
    slug: id,
    description: null,
    logoUrl: null,
    bannerUrl: null,
    phone: null,
    email: null,
    latitude: null,
    longitude: null,
    openingHours: null,
    deliveryInfo: null,
    minimumOrderPennies: 0,
    published: true,
    tags: null,
    createdAt: "2026-10-01T10:00:00Z",
    updatedAt: "2026-10-01T10:00:00Z",
    ...extra,
  }
}

const CLOSED_WEEK = { mon: [], tue: [], wed: [], thu: [], fri: [], sat: [], sun: [] }

const LEGACY = shop("s-legacy", "Legacy Grill", { hoursSource: "LEGACY_ALWAYS_OPEN" })
const LEGACY_2 = shop("s-legacy-2", "Suya Spot", { hoursSource: "LEGACY_ALWAYS_OPEN" })
const NEEDS_REVIEW = shop("s-review", "Mama Put", {
  hoursSource: "NEEDS_REVIEW",
  openingSchedule: { ...CLOSED_WEEK, mon: [{ open: "10:00", close: "16:00" }] },
  legacyOpeningHours: { mon: "10:00-16:00", tue: "lunchtime till late", thu: "ask", sun: "Closed" },
})
const STRUCTURED = shop("s-structured", "Rosie's Kitchen", {
  hoursSource: "STRUCTURED",
  openingSchedule: { ...CLOSED_WEEK, mon: [{ open: "09:00", close: "17:00" }] },
})
const MIGRATED = shop("s-migrated", "Jollof Express", {
  hoursSource: "MIGRATED_FROM_TEXT",
  openingSchedule: { ...CLOSED_WEEK, wed: [{ open: "10:00", close: "16:00" }] },
})
const UNCLASSIFIED = shop("s-new", "Brand New")

const card = () => screen.queryByTestId("hours-notice-card")

describe("HoursNoticeCard on the dashboard home (UI-SPEC K4 vendor notices)", () => {
  it("one LEGACY shop: '1 shop has no opening hours', 'Customers can order from it at any time, including when you're closed.', and 'Set opening hours' opens that shop's dialog at the hours", () => {
    render(<HoursNoticeCard shops={[LEGACY, STRUCTURED]} />)
    expect(card()).toBeInTheDocument()
    const c = card() as HTMLElement
    expect(within(c).getByText("1 shop has no opening hours")).toBeInTheDocument()
    expect(within(c).getByText("Customers can order from it at any time, including when you're closed.")).toBeInTheDocument()
    const link = within(c).getByRole("link", { name: "Set opening hours for Legacy Grill" })
    expect(link).toHaveTextContent("Set opening hours")
    expect(link).toHaveAttribute("href", "/dashboard/shops?edit=s-legacy&focus=hours")
    expect(link.className).toContain("h-11")
    expect(link.className).toContain("bg-primary")
  })

  it("two LEGACY shops: '2 shops have no opening hours', 'them', and one button per shop", () => {
    render(<HoursNoticeCard shops={[LEGACY, LEGACY_2]} />)
    expect(card()).toBeInTheDocument()
    const c = card() as HTMLElement
    expect(within(c).getByText("2 shops have no opening hours")).toBeInTheDocument()
    expect(within(c).getByText("Customers can order from them at any time, including when you're closed.")).toBeInTheDocument()
    expect(within(c).getByRole("link", { name: "Set opening hours for Legacy Grill" })).toBeInTheDocument()
    expect(within(c).getByRole("link", { name: "Set opening hours for Suya Spot" })).toHaveAttribute(
      "href",
      "/dashboard/shops?edit=s-legacy-2&focus=hours"
    )
  })

  it("a NEEDS_REVIEW shop: 'Check the opening hours for Mama Put' names the unreadable days and says, per the D-25 ruling, that customers can still order on them", () => {
    render(<HoursNoticeCard shops={[NEEDS_REVIEW]} />)
    expect(card()).toBeInTheDocument()
    const c = card() as HTMLElement
    expect(within(c).getByText("Check the opening hours for Mama Put")).toBeInTheDocument()
    expect(
      within(c).getByText(
        "We couldn't read the hours you entered for Tuesday and Thursday. Customers can order on those days at any time until you save your hours."
      )
    ).toBeInTheDocument()
    expect(within(c).queryByText(/show as closed/)).not.toBeInTheDocument()
    expect(within(c).getByRole("link", { name: "Check hours for Mama Put" })).toHaveAttribute(
      "href",
      "/dashboard/shops?edit=s-review&focus=hours"
    )
  })

  it("STRUCTURED, MIGRATED_FROM_TEXT and unclassified shops: no card at all", () => {
    render(<HoursNoticeCard shops={[STRUCTURED, MIGRATED, UNCLASSIFIED]} />)
    expect(card()).not.toBeInTheDocument()
  })

  it("no list yet (pending) or a failed load (null): no card", () => {
    render(<HoursNoticeCard shops={null} />)
    expect(card()).not.toBeInTheDocument()
  })
})

describe("the notice reads hoursSource, never the text (fixtures where source and text disagree)", () => {
  it("a STRUCTURED shop whose old unreadable text is still on the row gets no card", () => {
    const savedSinceReview = shop("s-saved", "Saved Since", {
      hoursSource: "STRUCTURED",
      openingSchedule: CLOSED_WEEK,
      legacyOpeningHours: { tue: "lunchtime till late", wed: "whenever" },
    })
    render(<HoursNoticeCard shops={[savedSinceReview]} />)
    expect(card()).not.toBeInTheDocument()
  })

  it("a LEGACY shop is told it has no hours even when some text on the row reads cleanly", () => {
    const legacyWithText = shop("s-legacy-text", "Legacy With Text", {
      hoursSource: "LEGACY_ALWAYS_OPEN",
      legacyOpeningHours: { mon: "09:00-17:00" },
    })
    render(<HoursNoticeCard shops={[legacyWithText]} />)
    expect(card()).toBeInTheDocument()
    expect(within(card() as HTMLElement).getByText("1 shop has no opening hours")).toBeInTheDocument()
  })
})
