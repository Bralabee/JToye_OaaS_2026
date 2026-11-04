/**
 * #460 / UXT-030: the shop form sets how far the shop delivers.
 *
 * The field is a number of km (0.1 to 100). Empty means no limit and is sent as an explicit null: core keeps
 * the stored radius when the key is absent and clears it on null, so an emptied field must reach it as null.
 */
import { act, fireEvent, render, screen, within } from "@testing-library/react"
import ShopsPage from "@/app/dashboard/shops/page"

jest.mock("@/hooks/use-toast", () => ({ useToast: () => ({ toast: jest.fn() }) }))

const mockGet = jest.fn()
const mockPut = jest.fn()
jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockGet(...args),
    put: (...args: unknown[]) => mockPut(...args),
    post: jest.fn(),
    delete: jest.fn(),
  },
}))

function shop(extra: Record<string, unknown> = {}) {
  return {
    id: "rosies-kitchen-019835a3",
    tenantId: "tenant-1",
    name: "Rosie's Kitchen",
    address: "1 High St",
    slug: "rosies-kitchen-019835a3",
    description: null,
    logoUrl: null,
    bannerUrl: null,
    phone: null,
    email: null,
    latitude: 51.47,
    longitude: -0.07,
    openingHours: null,
    deliveryInfo: null,
    minimumOrderPennies: 0,
    published: true,
    tags: null,
    createdAt: "2026-10-01T10:00:00Z",
    updatedAt: "2026-10-01T10:00:00Z",
    ordersPausedIndefinitely: false,
    deliveryFeePennies: 250,
    offersDelivery: true,
    offersCollection: true,
    ...extra,
  }
}

function stub(s: ReturnType<typeof shop>) {
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) =>
    url.startsWith("/api/v1/shops?")
      ? Promise.resolve({ data: { content: [s], totalElements: 1, totalPages: 1 } })
      : Promise.resolve({ data: s }),
  )
  mockPut.mockReset()
  mockPut.mockResolvedValue({ data: {} })
}

async function openEdit() {
  render(<ShopsPage />)
  const edit = await screen.findByRole("button", { name: "Edit shop Rosie's Kitchen" })
  await act(async () => {
    fireEvent.click(edit)
  })
  return screen.findByRole("dialog")
}

async function type(el: HTMLElement, value: string) {
  await act(async () => {
    fireEvent.change(el, { target: { value } })
  })
}

async function save() {
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Update Shop" }))
  })
}

const sent = () => mockPut.mock.calls[0][1] as Record<string, unknown>

describe("Delivery radius on the shop form (#460)", () => {
  it("has a 'Delivery radius (km)' field with the no-limit hint, pre-filled from the stored radius", async () => {
    stub(shop({ deliveryRadiusKm: 7.5 }))
    const dialog = await openEdit()

    const input = within(dialog).getByLabelText("Delivery radius (km)") as HTMLInputElement
    expect(input.value).toBe("7.5")
    const hint = within(dialog).getByText(/Leave empty to deliver to any address/i)
    expect(input.getAttribute("aria-describedby")?.split(" ")).toContain(hint.id)
  })

  it("sends the typed radius as a number", async () => {
    stub(shop())
    const dialog = await openEdit()

    await type(within(dialog).getByLabelText("Delivery radius (km)"), "10")
    await save()

    expect(sent().deliveryRadiusKm).toBe(10)
  })

  it("sends an emptied field as an explicit null, which is what clears the stored radius", async () => {
    stub(shop({ deliveryRadiusKm: 7.5 }))
    const dialog = await openEdit()

    await type(within(dialog).getByLabelText("Delivery radius (km)"), "")
    await save()

    expect(sent()).toHaveProperty("deliveryRadiusKm")
    expect(sent().deliveryRadiusKm).toBeNull()
  })

  it.each(["0", "-3", "100.5", "abc", "5 km"])("refuses %p with a message and sends nothing", async (bad) => {
    stub(shop())
    const dialog = await openEdit()

    await type(within(dialog).getByLabelText("Delivery radius (km)"), bad)
    await save()

    expect(await within(dialog).findByText("Enter a distance between 0.1 and 100 km, or leave it empty.")).toBeInTheDocument()
    expect(mockPut).not.toHaveBeenCalled()
  })
})
