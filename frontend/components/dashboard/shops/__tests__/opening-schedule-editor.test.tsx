/**
 * 37-21 Task 2 (UI-SPEC K4, D-15, D-25, RWO-022): vendors set real hours the server can enforce.
 *
 * Before this plan the shop dialog had seven free-text inputs ("09:00-17:00 or Closed") that wrote
 * `openingHours`, which 37-20 made decide nothing: the server enforces `openingSchedule`, and only a
 * save of THAT ends a shop's legacy state. UXT-022's vendor half: "hours cannot be set or cleared".
 *
 * Driven through the real shop dialog, so every assertion about what is SAVED is an assertion on the
 * request body the dialog sends.
 */

import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import ShopsPage from "@/app/dashboard/shops/page"

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

const mockGet = jest.fn()
const mockPut = jest.fn()
const mockPost = jest.fn()
jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockGet(...args),
    put: (...args: unknown[]) => mockPut(...args),
    post: (...args: unknown[]) => mockPost(...args),
    delete: jest.fn(),
  },
}))

type Window = { open: string; close: string }

function shop(id: string, name: string, extra: Record<string, unknown> = {}) {
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
    ordersPausedIndefinitely: false,
    ...extra,
  }
}

const CLOSED_WEEK: Record<string, Window[]> = { mon: [], tue: [], wed: [], thu: [], fri: [], sat: [], sun: [] }

const STRUCTURED = shop("s-structured", "Rosie's Kitchen", {
  hoursSource: "STRUCTURED",
  openingSchedule: { ...CLOSED_WEEK, mon: [{ open: "09:00", close: "17:00" }], sat: [{ open: "18:00", close: "02:00" }] },
  // The old text stays on the row after a structured save; it must not drive anything.
  openingHours: { mon: "whenever" },
  legacyOpeningHours: { mon: "whenever" },
})
const ALL_CLOSED = shop("s-closed", "Closed Shop", { hoursSource: "STRUCTURED", openingSchedule: CLOSED_WEEK })
const LEGACY = shop("s-legacy", "Legacy Grill", { hoursSource: "LEGACY_ALWAYS_OPEN" })
const NEEDS_REVIEW = shop("s-review", "Mama Put", {
  hoursSource: "NEEDS_REVIEW",
  // V80 read Monday; Tuesday's text it could not read; Sunday the vendor wrote closed.
  openingSchedule: { ...CLOSED_WEEK, mon: [{ open: "10:00", close: "16:00" }] },
  openingHours: { mon: "10:00-16:00", tue: "lunchtime till late", sun: "Closed" },
  legacyOpeningHours: { mon: "10:00-16:00", tue: "lunchtime till late", sun: "Closed" },
})

function stubShops(...shops: ReturnType<typeof shop>[]) {
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) =>
    url.startsWith("/api/v1/shops?")
      ? Promise.resolve({ data: { content: shops, totalElements: shops.length, totalPages: 1 } })
      : Promise.resolve({ data: shops[0] })
  )
  mockPut.mockReset()
  mockPut.mockResolvedValue({ data: {} })
  mockPost.mockReset()
  mockPost.mockResolvedValue({ data: {} })
  mockToast.mockReset()
}

async function openEdit(name: string) {
  render(<ShopsPage />)
  const edit = await screen.findByRole("button", { name: `Edit shop ${name}` })
  await act(async () => {
    fireEvent.click(edit)
  })
  return screen.findByRole("dialog")
}

const hours = () => screen.getByRole("group", { name: "Opening hours" })
const day = (label: string) => within(hours()).getByRole("group", { name: label })
const timeInput = (label: string) => within(hours()).getByLabelText(label) as HTMLInputElement
const closedBox = (dayName: string) => within(hours()).getByRole("checkbox", { name: `Closed on ${dayName}` })

async function setTime(label: string, value: string) {
  await act(async () => {
    fireEvent.change(timeInput(label), { target: { value } })
  })
}

async function click(el: HTMLElement) {
  await act(async () => {
    fireEvent.click(el)
  })
}

async function save() {
  await click(screen.getByRole("button", { name: "Update Shop" }))
}

/** The body of the one PUT the dialog sent. */
function sentBody(): Record<string, unknown> {
  expect(mockPut).toHaveBeenCalledTimes(1)
  return mockPut.mock.calls[0][1] as Record<string, unknown>
}

const DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]

describe("OpeningScheduleEditor in the shop dialog (UI-SPEC K4, D-15)", () => {
  it("replaces the seven free-text inputs with a fieldset 'Opening hours' (14px/600 slate-900 legend), one row per day, Monday first, each with a 'Closed' checkbox", async () => {
    stubShops(STRUCTURED)
    await openEdit("Rosie's Kitchen")
    expect(screen.queryAllByPlaceholderText("09:00-17:00 or Closed")).toHaveLength(0)
    const fieldset = hours()
    expect(fieldset.tagName).toBe("FIELDSET")
    const legend = fieldset.querySelector("legend") as HTMLElement
    expect(legend).toHaveTextContent("Opening hours")
    for (const token of ["text-sm", "font-semibold", "text-slate-900"]) expect(legend.classList).toContain(token)
    expect(legend.className).not.toContain("text-slate-400")
    const rows = within(fieldset).getAllByRole("group").map((g) => g.getAttribute("aria-label"))
    expect(rows).toEqual(DAYS)
    for (const d of DAYS) expect(closedBox(d)).toBeInTheDocument()
  })

  it("pre-fills a STRUCTURED shop from openingSchedule: native time inputs (step 900, h-11) labelled 'Monday opens' / 'Monday closes'", async () => {
    stubShops(STRUCTURED)
    await openEdit("Rosie's Kitchen")
    const opens = timeInput("Monday opens")
    expect(opens.type).toBe("time")
    expect(opens.step).toBe("900")
    expect(opens.className).toContain("h-11")
    expect(opens.value).toBe("09:00")
    expect(timeInput("Monday closes").value).toBe("17:00")
    expect(closedBox("Monday")).toHaveAttribute("aria-checked", "false")
    expect(closedBox("Tuesday")).toHaveAttribute("aria-checked", "true")
    expect(within(day("Tuesday")).queryByLabelText("Tuesday opens")).not.toBeInTheDocument()
  })

  it("Monday 09:00-17:00 + 'Copy Monday to every day' -> the update request carries that window on all seven days", async () => {
    stubShops(ALL_CLOSED)
    await openEdit("Closed Shop")
    await click(closedBox("Monday"))
    await setTime("Monday opens", "09:00")
    await setTime("Monday closes", "17:00")
    await click(within(hours()).getByRole("button", { name: "Copy Monday to every day" }))
    await save()
    const body = sentBody()
    expect(mockPut.mock.calls[0][0]).toBe("/api/v1/shops/s-closed")
    const window = [{ open: "09:00", close: "17:00" }]
    expect(body.openingSchedule).toEqual({
      mon: window, tue: window, wed: window, thu: window, fri: window, sat: window, sun: window,
    })
    // The free text is no longer written from the dialog.
    expect(body).not.toHaveProperty("openingHours")
  })

  it("overlapping windows -> 'These times overlap. Change one of them.' in role=alert, aria-invalid on both windows' inputs, focus on the first, and no request", async () => {
    stubShops(STRUCTURED)
    await openEdit("Rosie's Kitchen")
    await click(within(day("Monday")).getByRole("button", { name: "Add another time" }))
    await setTime("Monday opens, time 2", "16:00")
    await setTime("Monday closes, time 2", "20:00")
    await save()
    expect(mockPut).not.toHaveBeenCalled()
    expect(within(day("Monday")).getByRole("alert")).toHaveTextContent("These times overlap. Change one of them.")
    for (const label of ["Monday opens", "Monday closes", "Monday opens, time 2", "Monday closes, time 2"]) {
      expect(timeInput(label)).toHaveAttribute("aria-invalid", "true")
    }
    expect(document.activeElement).toBe(timeInput("Monday opens"))
  })

  it("an overnight window that runs into the next day's hours is an overlap too (the server refuses it)", async () => {
    stubShops(STRUCTURED)
    await openEdit("Rosie's Kitchen")
    // Saturday 18:00-02:00 runs into Sunday; give Sunday a 01:00 start.
    await click(closedBox("Sunday"))
    await setTime("Sunday opens", "01:00")
    await setTime("Sunday closes", "05:00")
    await save()
    expect(mockPut).not.toHaveBeenCalled()
    expect(within(day("Saturday")).getByRole("alert")).toHaveTextContent("These times overlap. Change one of them.")
    expect(timeInput("Sunday opens")).toHaveAttribute("aria-invalid", "true")
  })

  it("a window with no closing time -> 'Add a closing time, or tick Closed.' and no request", async () => {
    stubShops(ALL_CLOSED)
    await openEdit("Closed Shop")
    await click(closedBox("Wednesday"))
    await setTime("Wednesday opens", "11:00")
    await save()
    expect(mockPut).not.toHaveBeenCalled()
    expect(within(day("Wednesday")).getByRole("alert")).toHaveTextContent("Add a closing time, or tick Closed.")
    expect(timeInput("Wednesday closes")).toHaveAttribute("aria-invalid", "true")
    expect(document.activeElement).toBe(timeInput("Wednesday closes"))
  })

  it("validates on blur too, before any save", async () => {
    stubShops(ALL_CLOSED)
    await openEdit("Closed Shop")
    await click(closedBox("Friday"))
    await setTime("Friday opens", "12:00")
    await act(async () => {
      fireEvent.blur(timeInput("Friday closes"))
    })
    expect(within(day("Friday")).getByRole("alert")).toHaveTextContent("Add a closing time, or tick Closed.")
  })

  it("close 02:00 after open 18:00 shows 'Closes after midnight (next day)' and is valid", async () => {
    stubShops(ALL_CLOSED)
    await openEdit("Closed Shop")
    await click(closedBox("Friday"))
    await setTime("Friday opens", "18:00")
    await setTime("Friday closes", "02:00")
    expect(within(day("Friday")).getByText("Closes after midnight (next day)")).toBeInTheDocument()
    await save()
    expect(sentBody().openingSchedule).toEqual({ fri: [{ open: "18:00", close: "02:00" }] })
  })

  it("every day Closed saves as closed every day ({}) with the amber note 'With no opening hours, customers can't order from this shop.'", async () => {
    stubShops(STRUCTURED)
    await openEdit("Rosie's Kitchen")
    await click(closedBox("Monday"))
    await click(closedBox("Saturday"))
    const note = within(hours()).getByText("With no opening hours, customers can't order from this shop.")
    expect(note.className).toContain("text-amber-800")
    await save()
    expect(sentBody().openingSchedule).toEqual({})
  })

  it("a LEGACY_ALWAYS_OPEN shop opens with every day empty and the legacy info line; a save that leaves the hours alone omits openingSchedule (keeps the legacy state)", async () => {
    stubShops(LEGACY)
    await openEdit("Legacy Grill")
    expect(
      within(hours()).getByText(
        "Customers can currently order from this shop at any time, because no opening hours were set. Add your hours to change that."
      )
    ).toBeInTheDocument()
    for (const d of DAYS) expect(closedBox(d)).toHaveAttribute("aria-checked", "true")
    // Untouched, the all-closed note would be false: the legacy shop takes orders at any time.
    expect(within(hours()).queryByText("With no opening hours, customers can't order from this shop.")).not.toBeInTheDocument()
    await save()
    expect(sentBody()).not.toHaveProperty("openingSchedule")
  })

  it("a NEEDS_REVIEW shop (D-25, owner ruling 2026-10-10): an unreadable day opens as ONE all-day window 00:00-00:00, 'Open 24 hours', with the ruled amber line; read days keep their windows; a day written 'closed' stays Closed with no line", async () => {
    stubShops(NEEDS_REVIEW)
    await openEdit("Mama Put")
    // The server keeps Tuesday open all day; the editor now shows exactly that.
    expect(closedBox("Tuesday")).toHaveAttribute("aria-checked", "false")
    expect(within(day("Tuesday")).getAllByLabelText(/^Tuesday opens/)).toHaveLength(1)
    expect(timeInput("Tuesday opens").value).toBe("00:00")
    expect(timeInput("Tuesday closes").value).toBe("00:00")
    expect(within(day("Tuesday")).getByText("Open 24 hours")).toBeInTheDocument()
    expect(within(day("Tuesday")).queryByText("Closes after midnight (next day)")).not.toBeInTheDocument()
    const line = within(day("Tuesday")).getByText(
      'We could not read "lunchtime till late", so this day is open all day. Set the real times, or tick Closed.'
    )
    expect(line.className).toContain("text-amber-800")
    expect(timeInput("Monday opens").value).toBe("10:00")
    expect(timeInput("Monday closes").value).toBe("16:00")
    expect(within(day("Monday")).queryByText(/We could not read/)).not.toBeInTheDocument()
    expect(closedBox("Sunday")).toHaveAttribute("aria-checked", "true")
    expect(within(day("Sunday")).queryByText(/We could not read/)).not.toBeInTheDocument()
    // Neither the 37-21 orchestrator wording nor the original UI-SPEC line survives.
    expect(screen.queryByText(/Customers can still order on this day/)).not.toBeInTheDocument()
    expect(screen.queryByText(/This day shows as closed/)).not.toBeInTheDocument()
  })

  it("a NEEDS_REVIEW shop: changing another day and saving sends the unreadable day as its all-day window, never omitted (so the partial fix keeps it open)", async () => {
    stubShops(NEEDS_REVIEW)
    await openEdit("Mama Put")
    await setTime("Monday closes", "17:00")
    await save()
    const body = sentBody()
    expect(body.openingSchedule).toEqual({
      mon: [{ open: "10:00", close: "17:00" }],
      tue: [{ open: "00:00", close: "00:00" }],
    })
    expect(body.openingSchedule).not.toHaveProperty("sun")
  })

  it("a NEEDS_REVIEW shop left untouched still omits openingSchedule, so the stored hours and their source are kept", async () => {
    stubShops(NEEDS_REVIEW)
    await openEdit("Mama Put")
    await save()
    expect(sentBody()).not.toHaveProperty("openingSchedule")
  })

  it("a NEEDS_REVIEW shop with an overnight Sunday (18:00-02:00) and an unreadable Monday refuses the save: the all-day Monday overlaps Sunday's spill until its real times are set", async () => {
    stubShops(shop("s-spill", "Night Grill", {
      hoursSource: "NEEDS_REVIEW",
      openingSchedule: { ...CLOSED_WEEK, sun: [{ open: "18:00", close: "02:00" }] },
      legacyOpeningHours: { mon: "brunch-ish", sun: "18:00-02:00" },
    }))
    await openEdit("Night Grill")
    expect(timeInput("Monday opens").value).toBe("00:00")
    await click(closedBox("Wednesday"))
    await setTime("Wednesday opens", "09:00")
    await setTime("Wednesday closes", "17:00")
    await save()
    expect(mockPut).not.toHaveBeenCalled()
    expect(within(day("Sunday")).getByRole("alert")).toHaveTextContent("These times overlap. Change one of them.")
    expect(timeInput("Monday opens")).toHaveAttribute("aria-invalid", "true")
  })

  it("zero-one-many: 0 windows is Closed, then 1, up to 3; 'Add another time' hides at 3 and removing the last window ticks Closed", async () => {
    stubShops(ALL_CLOSED)
    await openEdit("Closed Shop")
    expect(within(day("Thursday")).queryByRole("button", { name: "Add another time" })).not.toBeInTheDocument()
    await click(closedBox("Thursday"))
    expect(within(day("Thursday")).getAllByLabelText(/^Thursday opens/)).toHaveLength(1)
    await click(within(day("Thursday")).getByRole("button", { name: "Add another time" }))
    await click(within(day("Thursday")).getByRole("button", { name: "Add another time" }))
    expect(within(day("Thursday")).getAllByLabelText(/^Thursday opens/)).toHaveLength(3)
    expect(within(day("Thursday")).queryByRole("button", { name: "Add another time" })).not.toBeInTheDocument()
    for (let i = 0; i < 3; i++) {
      await click(within(day("Thursday")).getAllByRole("button", { name: /^Remove Thursday/ })[0])
    }
    expect(closedBox("Thursday")).toHaveAttribute("aria-checked", "true")
  })

  it("names the remove button by its window: 'Remove Saturday 18:00 to 02:00'", async () => {
    stubShops(STRUCTURED)
    await openEdit("Rosie's Kitchen")
    const remove = within(day("Saturday")).getByRole("button", { name: "Remove Saturday 18:00 to 02:00" })
    expect(remove.className).toContain("h-11")
    expect(remove.className).toContain("w-11")
  })

  it("an edit to other fields only omits openingSchedule, so the stored hours are kept", async () => {
    stubShops(STRUCTURED)
    await openEdit("Rosie's Kitchen")
    await act(async () => {
      fireEvent.change(screen.getByLabelText("Shop Name *"), { target: { value: "Rosie's Kitchen 2" } })
    })
    await save()
    const body = sentBody()
    expect(body.name).toBe("Rosie's Kitchen 2")
    expect(body).not.toHaveProperty("openingSchedule")
    expect(body).not.toHaveProperty("openingHours")
  })

  it("a save the server refuses uses the dialog's existing error path (toast with the server's detail) and the dialog stays open", async () => {
    stubShops(STRUCTURED)
    mockPut.mockRejectedValue({
      response: { status: 400, data: { status: 400, detail: "mon: windows 09:00-17:00 and 10:00-11:00 overlap" } },
    })
    await openEdit("Rosie's Kitchen")
    await click(closedBox("Tuesday"))
    await setTime("Tuesday opens", "09:00")
    await setTime("Tuesday closes", "12:00")
    await save()
    await waitFor(() => expect(mockToast).toHaveBeenCalled())
    expect(mockToast.mock.calls[0][0]).toEqual(expect.objectContaining({ variant: "destructive", title: "Error updating shop" }))
    expect(screen.getByRole("dialog")).toBeInTheDocument()
  })
})

describe("OpeningScheduleEditor layout (UI-SPEC K4 overflow and long-text backstops, structural half)", () => {
  it("a window's two time inputs share the row equally and may shrink (flex-1 basis-0 min-w-0), with day name and Closed stacked above on mobile", async () => {
    stubShops(STRUCTURED)
    await openEdit("Rosie's Kitchen")
    const opens = timeInput("Monday opens")
    const closes = timeInput("Monday closes")
    for (const input of [opens, closes]) {
      for (const token of ["flex-1", "basis-0", "min-w-0"]) expect(input.classList).toContain(token)
    }
    expect(opens.parentElement).toBe(closes.parentElement)
    const dayRow = day("Monday").firstElementChild as HTMLElement
    expect(dayRow.className).toContain("flex-col")
    expect(dayRow.className).toContain("sm:flex-row")
  })

  it("a 200-character original text in the needs-review line wraps: overflow-wrap anywhere, no nowrap or truncation up to the dialog", async () => {
    const long = "Open from about nine most weekdays but it really depends on deliveries and whether the oven is working, " +
      "so please ring ahead first, especially on bank holidays and during Ramadan evenings thanks a lot"
    expect(long).toHaveLength(200)
    stubShops(shop("s-long", "Long Text", {
      hoursSource: "NEEDS_REVIEW",
      openingSchedule: CLOSED_WEEK,
      legacyOpeningHours: { wed: long },
    }))
    await openEdit("Long Text")
    const line = within(day("Wednesday")).getByText(new RegExp(`^We could not read "${long.slice(0, 20)}`))
    expect(line.className).toContain("[overflow-wrap:anywhere]")
    let el: HTMLElement | null = line
    while (el && el.getAttribute("role") !== "dialog") {
      expect(el.className).not.toMatch(/\btruncate\b|whitespace-nowrap|line-clamp/)
      el = el.parentElement
    }
  })
})

describe("shops table notices and the deep link from the dashboard home (37-21 Task 3, UI-SPEC K3/K4)", () => {
  /** Tuesday 13 October 2026, 18:20 in the UK (BST). Only Date is faked. */
  function freezeClock() {
    jest.useFakeTimers({
      now: new Date("2026-10-13T17:20:00Z"),
      doNotFake: [
        "setTimeout", "clearTimeout", "setInterval", "clearInterval", "setImmediate", "clearImmediate",
        "nextTick", "queueMicrotask", "requestAnimationFrame", "cancelAnimationFrame",
        "requestIdleCallback", "cancelIdleCallback", "hrtime", "performance",
      ],
    })
  }

  afterEach(() => {
    jest.useRealTimers()
    window.history.replaceState(null, "", "/")
  })

  const row = (name: string) => screen.getByText(name, { selector: "td *, td" }).closest("tr") as HTMLElement

  it("badges read hoursSource and the pause: 'Hours not set' (LEGACY), 'Check hours' (NEEDS_REVIEW), 'Paused until 19:40' / 'Paused'; nothing for STRUCTURED", async () => {
    freezeClock()
    const pausedTimed = shop("s-paused", "Paused Timed", {
      hoursSource: "STRUCTURED",
      openingSchedule: CLOSED_WEEK,
      ordersPausedUntil: "2026-10-13T18:40:00Z",
    })
    const pausedIndef = shop("s-indef", "Paused Indef", { hoursSource: "STRUCTURED", ordersPausedIndefinitely: true })
    const pauseEnded = shop("s-ended", "Pause Ended", { hoursSource: "STRUCTURED", ordersPausedUntil: "2026-10-13T16:00:00Z" })
    stubShops(LEGACY, NEEDS_REVIEW, STRUCTURED, pausedTimed, pausedIndef, pauseEnded)
    render(<ShopsPage />)
    await screen.findByRole("button", { name: "Edit shop Legacy Grill" })
    const legacyBadge = within(row("Legacy Grill")).getByText("Hours not set")
    expect(legacyBadge.className).toContain("text-amber-800")
    expect(legacyBadge.querySelector("svg")).not.toBeNull()
    expect(within(row("Mama Put")).getByText("Check hours")).toBeInTheDocument()
    expect(within(row("Rosie's Kitchen")).queryByText(/Hours not set|Check hours|Paused/)).not.toBeInTheDocument()
    expect(within(row("Paused Timed")).getByText("Paused until 19:40")).toBeInTheDocument()
    expect(within(row("Paused Indef")).getByText("Paused")).toBeInTheDocument()
    expect(within(row("Pause Ended")).queryByText(/Paused/)).not.toBeInTheDocument()
    // The text never decides: Rosie's old "whenever" is on the row and earns no badge.
    expect(within(row("Rosie's Kitchen")).queryByText("Check hours")).not.toBeInTheDocument()
  })

  it("?edit={id}&focus=hours opens that shop's dialog with focus on the Opening hours fieldset, and drops the parameter", async () => {
    window.history.replaceState(null, "", "/dashboard/shops?edit=s-review&focus=hours")
    stubShops(LEGACY, NEEDS_REVIEW)
    render(<ShopsPage />)
    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByRole("heading", { name: "Edit Shop" })).toBeInTheDocument()
    expect(within(dialog).getByLabelText("Shop Name *")).toHaveValue("Mama Put")
    await waitFor(() => expect(document.activeElement).toBe(hours()))
    expect(window.location.search).toBe("")
  })

  it("a deep link to a shop that is not on the first page reads it by id and opens it", async () => {
    window.history.replaceState(null, "", "/dashboard/shops?edit=s-legacy&focus=hours")
    stubShops(STRUCTURED)
    mockGet.mockImplementation((url: string) =>
      url.startsWith("/api/v1/shops?")
        ? Promise.resolve({ data: { content: [STRUCTURED], totalElements: 2, totalPages: 1 } })
        : url === "/api/v1/shops/s-legacy"
          ? Promise.resolve({ data: LEGACY })
          : Promise.resolve({ data: {} })
    )
    render(<ShopsPage />)
    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByLabelText("Shop Name *")).toHaveValue("Legacy Grill")
    expect(mockGet).toHaveBeenCalledWith("/api/v1/shops/s-legacy")
  })
})

describe("saving hours keeps the shop's storefront address (37-21 Rule 1 deviation)", () => {
  // ShopService.updateShop regenerates the slug (name + a random 8-character suffix) whenever the
  // request carries none, and the dialog never sent one: every save, an hours save included, moved
  // the shop to a new /shop/{slug} and broke every link to it. The server's own comment says the
  // regeneration is meant for a NAME change, so the dialog now sends the current slug unless the
  // name changed.
  it("an hours save sends the shop's current slug", async () => {
    stubShops(STRUCTURED)
    await openEdit("Rosie's Kitchen")
    await click(closedBox("Tuesday"))
    await setTime("Tuesday opens", "09:00")
    await setTime("Tuesday closes", "12:00")
    await save()
    expect(sentBody().slug).toBe("s-structured")
  })

  it("a rename leaves the slug out, so the server's rename behaviour is unchanged", async () => {
    stubShops(STRUCTURED)
    await openEdit("Rosie's Kitchen")
    await act(async () => {
      fireEvent.change(screen.getByLabelText("Shop Name *"), { target: { value: "Rosie's Canteen" } })
    })
    await save()
    expect(sentBody()).not.toHaveProperty("slug")
  })

  it("creating a shop sends no slug (the server generates one)", async () => {
    stubShops(STRUCTURED)
    render(<ShopsPage />)
    await screen.findByRole("button", { name: "Edit shop Rosie's Kitchen" })
    await click(screen.getAllByRole("button", { name: /Add Shop/ })[0])
    await act(async () => {
      fireEvent.change(await screen.findByLabelText("Shop Name *"), { target: { value: "New Place" } })
    })
    await click(screen.getByRole("button", { name: "Create Shop" }))
    await waitFor(() => expect(mockPost).toHaveBeenCalledTimes(1))
    expect(mockPost.mock.calls[0][1]).not.toHaveProperty("slug")
  })
})
