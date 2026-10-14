/**
 * 37-49 (UI-SPEC G1 and G2; UXT-023, UXT-028; RWO-023, RWO-028): the shop form sets what delivery
 * costs, which fulfilment types the shop takes, and shows the web address it will have.
 *
 * Before this plan the only delivery field was the free-text "Delivery Info", whose placeholder
 * ("Free delivery over £30") imitated a threshold nothing enforced, and a vendor could not run a
 * collection-only shop at all. 37-48 gave the API the four fields; this file drives the REAL shop
 * dialog, so every claim about what is saved is a claim about the request body it sends.
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

// What 37-48's ShopDto returns for a shop saved since V83: a fee, a threshold and both flags.
const KITCHEN = shop("rosies-kitchen-019835a3", "Rosie's Kitchen", {
  deliveryFeePennies: 250,
  freeDeliveryThresholdPennies: 3000,
  offersDelivery: true,
  offersCollection: true,
})
const COLLECTION_ONLY = shop("pick-up-place-1a2b3c4d", "Pick Up Place", {
  deliveryFeePennies: 0,
  offersDelivery: false,
  offersCollection: true,
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

async function openCreate() {
  render(<ShopsPage />)
  await screen.findByRole("button", { name: "Edit shop Rosie's Kitchen" })
  await act(async () => {
    fireEvent.click(screen.getAllByRole("button", { name: /Add Shop/ })[0])
  })
  return screen.findByRole("dialog")
}

async function click(el: HTMLElement) {
  await act(async () => {
    fireEvent.click(el)
  })
}

async function type(el: HTMLElement, value: string) {
  await act(async () => {
    fireEvent.change(el, { target: { value } })
  })
}

const offered = () => screen.getByRole("group", { name: "How customers get their order" })
const deliveryBox = () => within(offered()).getByRole("checkbox", { name: "Delivery" })
const collectionBox = () => within(offered()).getByRole("checkbox", { name: "Collection" })

/** The body of the one PUT the dialog sent. */
function sentBody(): Record<string, unknown> {
  expect(mockPut).toHaveBeenCalledTimes(1)
  return mockPut.mock.calls[0][1] as Record<string, unknown>
}

describe("the shop form's delivery fields (UI-SPEC G1, UXT-023)", () => {
  it("has 'Delivery fee (£)' and 'Free delivery over (£)' (text with a decimal keypad, h-11) with the empty-threshold hint", async () => {
    stubShops(KITCHEN)
    const dialog = await openEdit("Rosie's Kitchen")
    for (const label of ["Delivery fee (£)", "Free delivery over (£)"]) {
      const input = within(dialog).getByLabelText(label) as HTMLInputElement
      // 37-G review: text, not number, so a malformed entry is validated rather than silently emptied.
      expect(input).toHaveAttribute("type", "text")
      expect(input).toHaveAttribute("inputmode", "decimal")
      expect(input).toHaveClass("h-11")
    }
    const threshold = within(dialog).getByLabelText("Free delivery over (£)")
    const hint = within(dialog).getByText("Leave empty if you don't offer free delivery.")
    expect(threshold.getAttribute("aria-describedby")?.split(" ")).toContain(hint.id)
  })

  it("pre-fills the stored fee and threshold in pounds", async () => {
    stubShops(KITCHEN)
    const dialog = await openEdit("Rosie's Kitchen")
    expect((within(dialog).getByLabelText("Delivery fee (£)") as HTMLInputElement).value).toBe("2.50")
    expect((within(dialog).getByLabelText("Free delivery over (£)") as HTMLInputElement).value).toBe("30.00")
  })

  it("fee 2.99 and threshold 30 are sent as 299 and 3000 pennies", async () => {
    stubShops(KITCHEN)
    const dialog = await openEdit("Rosie's Kitchen")
    await type(within(dialog).getByLabelText("Delivery fee (£)"), "2.99")
    await type(within(dialog).getByLabelText("Free delivery over (£)"), "30")
    await click(screen.getByRole("button", { name: "Update Shop" }))
    const body = sentBody()
    expect(body.deliveryFeePennies).toBe(299)
    expect(body.freeDeliveryThresholdPennies).toBe(3000)
  })

  it("an emptied threshold is sent as an explicit null (37-48: null clears it; an absent key would keep £30)", async () => {
    stubShops(KITCHEN)
    const dialog = await openEdit("Rosie's Kitchen")
    await type(within(dialog).getByLabelText("Free delivery over (£)"), "")
    await click(screen.getByRole("button", { name: "Update Shop" }))
    const body = sentBody()
    expect(body).toHaveProperty("freeDeliveryThresholdPennies")
    expect(body.freeDeliveryThresholdPennies).toBeNull()
  })

  it("partial: a fee with an empty threshold is valid and saves", async () => {
    stubShops(shop("plain-1", "Rosie's Kitchen", { deliveryFeePennies: 0, offersDelivery: true, offersCollection: true }))
    const dialog = await openEdit("Rosie's Kitchen")
    await type(within(dialog).getByLabelText("Delivery fee (£)"), "1.50")
    await click(screen.getByRole("button", { name: "Update Shop" }))
    const body = sentBody()
    expect(body.deliveryFeePennies).toBe(150)
    expect(body.freeDeliveryThresholdPennies).toBeNull()
  })

  it("keeps the free-text Delivery Info, with the placeholder 'e.g. Delivery within 3 miles' instead of an imitation threshold", async () => {
    stubShops(KITCHEN)
    const dialog = await openEdit("Rosie's Kitchen")
    const info = within(dialog).getByLabelText("Delivery Info")
    expect(info).toHaveAttribute("placeholder", "e.g. Delivery within 3 miles")
    expect(within(dialog).queryByPlaceholderText("Free delivery over £30")).not.toBeInTheDocument()
  })
})

/**
 * 37-G review round 1: a malformed amount must not save as "no threshold". As `type="number"` the
 * browser reported "1e" or "3-0" as value "" (validity.badInput), which the form read as an EMPTY
 * field: the threshold was sent as null (the server CLEARS it) and the fee as 0. Chromium also read
 * "30,00" as 3000. The fields are text with a decimal keypad, and the typed text is validated strictly.
 */
describe("the delivery amounts refuse malformed entries (37-G review)", () => {
  const FIELDS = ["Delivery fee (£)", "Free delivery over (£)"] as const

  async function settle() {
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20))
    })
  }

  // A literal it.each table, not a loop around it(): the docs-freshness counter and the jest test-count
  // oracle must count the same tests.
  it.each([
    ["Delivery fee (£)", "1e"],
    ["Delivery fee (£)", "30,00"],
    ["Delivery fee (£)", "abc"],
    ["Delivery fee (£)", "-1"],
    ["Delivery fee (£)", "3-0"],
    ["Delivery fee (£)", "2.999"],
    ["Free delivery over (£)", "1e"],
    ["Free delivery over (£)", "30,00"],
    ["Free delivery over (£)", "abc"],
    ["Free delivery over (£)", "-1"],
    ["Free delivery over (£)", "3-0"],
    ["Free delivery over (£)", "2.999"],
  ])(
    "'%s' = %j shows the amount message and sends nothing",
    async (label, entry) => {
      stubShops(KITCHEN)
      const dialog = await openEdit("Rosie's Kitchen")
      const input = within(dialog).getByLabelText(label) as HTMLInputElement
      await type(input, entry)
      // The field keeps exactly what was typed; nothing is silently emptied.
      expect(input.value).toBe(entry)
      await click(screen.getByRole("button", { name: "Update Shop" }))
      await settle()
      expect(mockPut).toHaveBeenCalledTimes(0)
      expect(within(dialog).getByText("Enter an amount of £0.00 or more.")).toBeInTheDocument()
      expect(input).toHaveAttribute("aria-invalid", "true")
    }
  )

  it("the two amount fields are text with a decimal keypad, so the browser never empties a bad entry", async () => {
    stubShops(KITCHEN)
    const dialog = await openEdit("Rosie's Kitchen")
    for (const label of FIELDS) {
      const input = within(dialog).getByLabelText(label)
      expect(input).toHaveAttribute("type", "text")
      expect(input).toHaveAttribute("inputmode", "decimal")
    }
  })

  it.each([
    ["30", 3000],
    ["30.5", 3050],
    ["2.99", 299],
  ])("CONTROL: a well-formed threshold %s is sent as %i pennies", async (entry, pennies) => {
    stubShops(KITCHEN)
    const dialog = await openEdit("Rosie's Kitchen")
    await type(within(dialog).getByLabelText("Free delivery over (£)"), entry)
    await click(screen.getByRole("button", { name: "Update Shop" }))
    expect(sentBody().freeDeliveryThresholdPennies).toBe(pennies)
  })

  it.each([
    ["30", 3000],
    ["30.5", 3050],
    ["2.99", 299],
  ])("CONTROL: a well-formed fee %s is sent as %i pennies", async (entry, pennies) => {
    stubShops(KITCHEN)
    const dialog = await openEdit("Rosie's Kitchen")
    await type(within(dialog).getByLabelText("Delivery fee (£)"), entry)
    await click(screen.getByRole("button", { name: "Update Shop" }))
    expect(sentBody().deliveryFeePennies).toBe(pennies)
  })

  it("CONTROL: both fields empty still send threshold null and fee 0", async () => {
    stubShops(KITCHEN)
    const dialog = await openEdit("Rosie's Kitchen")
    await type(within(dialog).getByLabelText("Delivery fee (£)"), "")
    await type(within(dialog).getByLabelText("Free delivery over (£)"), "")
    await click(screen.getByRole("button", { name: "Update Shop" }))
    const body = sentBody()
    expect(body.deliveryFeePennies).toBe(0)
    expect(body).toHaveProperty("freeDeliveryThresholdPennies")
    expect(body.freeDeliveryThresholdPennies).toBeNull()
  })
})

describe("'How customers get their order' (UI-SPEC G1, zero-one-many)", () => {
  it("a new shop starts with Delivery and Collection both ticked (today's behaviour)", async () => {
    stubShops(KITCHEN)
    await openCreate()
    expect(deliveryBox()).toBeChecked()
    expect(collectionBox()).toBeChecked()
  })

  it("an edited shop shows its stored flags", async () => {
    stubShops(COLLECTION_ONLY)
    await openEdit("Pick Up Place")
    expect(deliveryBox()).not.toBeChecked()
    expect(collectionBox()).toBeChecked()
  })

  it("unticking Delivery sends offersDelivery false and offersCollection true", async () => {
    stubShops(KITCHEN)
    await openEdit("Rosie's Kitchen")
    await click(deliveryBox())
    await click(screen.getByRole("button", { name: "Update Shop" }))
    const body = sentBody()
    expect(body.offersDelivery).toBe(false)
    expect(body.offersCollection).toBe(true)
  })

  it("unticking both shows 'Choose at least one: delivery or collection.' in role=alert and sends NO request", async () => {
    stubShops(KITCHEN)
    await openEdit("Rosie's Kitchen")
    await click(deliveryBox())
    await click(collectionBox())
    const alert = within(offered()).getByRole("alert")
    expect(alert).toHaveTextContent("Choose at least one: delivery or collection.")
    await click(screen.getByRole("button", { name: "Update Shop" }))
    // Let any pending submit settle before asserting nothing was sent.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20))
    })
    expect(mockPut).toHaveBeenCalledTimes(0)
    expect(mockPost).toHaveBeenCalledTimes(0)
    expect(screen.getByRole("dialog")).toBeInTheDocument()
  })

  it("re-ticking one clears the alert and the save goes through", async () => {
    stubShops(KITCHEN)
    await openEdit("Rosie's Kitchen")
    await click(deliveryBox())
    await click(collectionBox())
    await click(collectionBox())
    expect(within(offered()).queryByRole("alert")).not.toBeInTheDocument()
    await click(screen.getByRole("button", { name: "Update Shop" }))
    await waitFor(() => expect(mockPut).toHaveBeenCalledTimes(1))
    expect(sentBody()).toMatchObject({ offersDelivery: false, offersCollection: true })
  })

  it("a new shop sends both flags and the fee fields on create", async () => {
    stubShops(KITCHEN)
    const dialog = await openCreate()
    await type(within(dialog).getByLabelText("Shop Name *"), "New Place")
    await type(within(dialog).getByLabelText("Delivery fee (£)"), "3")
    await click(screen.getByRole("button", { name: "Create Shop" }))
    await waitFor(() => expect(mockPost).toHaveBeenCalledTimes(1))
    expect(mockPost.mock.calls[0][1]).toMatchObject({
      deliveryFeePennies: 300,
      freeDeliveryThresholdPennies: null,
      offersDelivery: true,
      offersCollection: true,
    })
  })

  it("each option is a 44px row (min-h-11) holding a 24px checkbox", async () => {
    stubShops(KITCHEN)
    await openEdit("Rosie's Kitchen")
    expect(deliveryBox().closest("label")).toHaveClass("min-h-11")
    expect(collectionBox().closest("label")).toHaveClass("min-h-11")
    expect(deliveryBox()).toHaveClass("h-6", "w-6")
  })
})

describe("the web address (UI-SPEC G2, UXT-028)", () => {
  it("the create dialog previews the address the server will generate from the name, before anything is saved", async () => {
    stubShops(KITCHEN)
    const dialog = await openCreate()
    // No name yet: nothing to preview, so no invented address.
    expect(within(dialog).queryByTestId("web-address-preview")).not.toBeInTheDocument()
    await type(within(dialog).getByLabelText("Shop Name *"), "Rosie's  Kitchen & Bar!")
    const preview = within(dialog).getByTestId("web-address-preview")
    // ShopService.generateSlug: lower-case, drop everything but [a-z0-9 whitespace -], whitespace runs
    // to one hyphen, then a random 8-character code the browser cannot know.
    expect(preview).toHaveTextContent("/shop/rosies-kitchen-bar-")
    expect(preview).toHaveClass("font-mono", "text-sm", "break-all")
    expect(
      within(dialog).getByText("A short code is added to the end when the shop is created, so no two shops share an address.")
    ).toBeInTheDocument()
    // The create dialog keeps auto-generation: no change control, no input.
    expect(within(dialog).queryByRole("button", { name: "Change web address" })).not.toBeInTheDocument()
    expect(within(dialog).queryByLabelText("New web address")).not.toBeInTheDocument()
  })

  it("a name with no usable characters shows no preview", async () => {
    stubShops(KITCHEN)
    const dialog = await openCreate()
    await type(within(dialog).getByLabelText("Shop Name *"), "!!!")
    expect(within(dialog).queryByTestId("web-address-preview")).not.toBeInTheDocument()
  })

  it("the edit dialog has no create preview; it shows the stored address read-only", async () => {
    stubShops(KITCHEN)
    const dialog = await openEdit("Rosie's Kitchen")
    expect(within(dialog).queryByTestId("web-address-preview")).not.toBeInTheDocument()
    expect(within(dialog).getByText("/shop/rosies-kitchen-019835a3")).toBeInTheDocument()
  })

  it("saving the edit dialog with the fee fields changed but the address untouched sends no slug", async () => {
    stubShops(KITCHEN)
    const dialog = await openEdit("Rosie's Kitchen")
    await type(within(dialog).getByLabelText("Delivery fee (£)"), "1.99")
    await click(collectionBox())
    await click(screen.getByRole("button", { name: "Update Shop" }))
    const body = sentBody()
    expect(body).not.toHaveProperty("slug")
    expect(body.deliveryFeePennies).toBe(199)
  })

  it("long-text and overflow (planner assumption): the read-only address breaks anywhere and the link sits on its own line below sm", async () => {
    const longSlug = "the-very-long-named-west-african-kitchen-and-grill-on-coldharbour-lane-brixton-0198a3b4"
    stubShops({ ...KITCHEN, slug: longSlug })
    const dialog = await openEdit("Rosie's Kitchen")
    const address = within(dialog).getByText(`/shop/${longSlug}`)
    expect(address).toHaveClass("break-all", "font-mono", "text-sm")
    const row = address.parentElement as HTMLElement
    // flex-col below sm puts "Change web address" under the address; sm:flex-row restores one line.
    expect(row).toHaveClass("flex-col", "sm:flex-row")
    expect(row).toContainElement(within(dialog).getByRole("button", { name: "Change web address" }))
    // No truncation anywhere up the row: the whole address stays readable.
    expect(address.className).not.toMatch(/truncate|whitespace-nowrap/)
  })
})
