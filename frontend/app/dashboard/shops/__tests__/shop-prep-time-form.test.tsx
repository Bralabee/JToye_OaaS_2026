/**
 * #825 (owner ruling 2026-11-04, persona close-out): each shop has a default preparation time, set in
 * the shop settings, in whole minutes. Empty = not set, and the request must then say so explicitly
 * (null), because the server keeps the stored value when the key is absent and clears it only on null.
 */

import { act, fireEvent, render, screen, within } from "@testing-library/react"
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
    deliveryFeePennies: 0,
    offersDelivery: true,
    offersCollection: true,
    ...extra,
  }
}

const WITH_PREP = shop("rosies-kitchen-825", "Rosie's Kitchen", { prepTimeMinutes: 25 })
const WITHOUT_PREP = shop("plain-kitchen-825", "Plain Kitchen")

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

function sentBody(): Record<string, unknown> {
  expect(mockPut).toHaveBeenCalledTimes(1)
  return mockPut.mock.calls[0][1] as Record<string, unknown>
}

describe("the shop form's preparation time (#825)", () => {
  it("has a labelled numeric field with a hint saying what it is for", async () => {
    stubShops(WITH_PREP)
    const dialog = await openEdit("Rosie's Kitchen")
    const input = within(dialog).getByLabelText("Usual preparation time (minutes)") as HTMLInputElement
    expect(input).toHaveAttribute("inputmode", "numeric")
    expect(input).toHaveClass("h-11")
    const hint = within(dialog).getByText(/Customers see an estimated ready-by time/)
    expect(input.getAttribute("aria-describedby")?.split(" ")).toContain(hint.id)
  })

  it("pre-fills the stored minutes, and an unset shop is empty", async () => {
    stubShops(WITH_PREP)
    const dialog = await openEdit("Rosie's Kitchen")
    expect((within(dialog).getByLabelText("Usual preparation time (minutes)") as HTMLInputElement).value).toBe("25")
  })

  it("sends the typed minutes as a whole number", async () => {
    stubShops(WITHOUT_PREP)
    const dialog = await openEdit("Plain Kitchen")
    expect((within(dialog).getByLabelText("Usual preparation time (minutes)") as HTMLInputElement).value).toBe("")
    await type(within(dialog).getByLabelText("Usual preparation time (minutes)"), "40")
    await click(screen.getByRole("button", { name: "Update Shop" }))
    expect(sentBody().prepTimeMinutes).toBe(40)
  })

  it("an emptied field is sent as an explicit null (the server clears the prep time on null, keeps it when the key is absent)", async () => {
    stubShops(WITH_PREP)
    const dialog = await openEdit("Rosie's Kitchen")
    await type(within(dialog).getByLabelText("Usual preparation time (minutes)"), "")
    await click(screen.getByRole("button", { name: "Update Shop" }))
    const body = sentBody()
    expect(body).toHaveProperty("prepTimeMinutes")
    expect(body.prepTimeMinutes).toBeNull()
  })

  it.each([["0"], ["481"], ["abc"], ["2.5"], ["-5"], ["1e2"]])(
    "%j is refused with the message and nothing is sent",
    async (entry) => {
      stubShops(WITH_PREP)
      const dialog = await openEdit("Rosie's Kitchen")
      const input = within(dialog).getByLabelText("Usual preparation time (minutes)") as HTMLInputElement
      await type(input, entry)
      await click(screen.getByRole("button", { name: "Update Shop" }))
      await act(async () => {
        await new Promise((r) => setTimeout(r, 20))
      })
      expect(mockPut).toHaveBeenCalledTimes(0)
      expect(
        within(dialog).getByText("Enter a whole number of minutes between 1 and 480, or leave it empty.")
      ).toBeInTheDocument()
      expect(input).toHaveAttribute("aria-invalid", "true")
    }
  )

  it.each([["1", 1], ["480", 480], ["25", 25]])("CONTROL: %s is accepted and sent as %i", async (entry, minutes) => {
    stubShops(WITH_PREP)
    const dialog = await openEdit("Rosie's Kitchen")
    await type(within(dialog).getByLabelText("Usual preparation time (minutes)"), entry)
    await click(screen.getByRole("button", { name: "Update Shop" }))
    expect(sentBody().prepTimeMinutes).toBe(minutes)
  })
})
