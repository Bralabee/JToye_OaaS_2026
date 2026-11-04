/**
 * #835 (UXT-066 / ADE-11, P2-KEM-04): the "Publish to storefront" checkbox was ignored, or threw the
 * whole shop edit away with a raw developer error.
 *
 * Shop.published has ONE writer: the onboarding state machine (V43). PUT /shops/{id} refuses a
 * request that asks to CHANGE it (409) BEFORE it applies any other field, so a vendor who ticked the
 * box and also changed the hours lost the hours and was shown "Shop.published is written only by the
 * onboarding state machine ... contact support". The form must therefore not carry a publish control:
 * it shows the shop's status read-only, links to the go-live flow, and never sends `published`.
 */

import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import ShopsPage from "@/app/dashboard/shops/page"

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

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

function shop(id: string, name: string, published: boolean) {
  return {
    id,
    tenantId: "tenant-1",
    name,
    address: "1 High St",
    slug: id,
    description: "Old description",
    logoUrl: null,
    bannerUrl: null,
    phone: null,
    email: null,
    latitude: null,
    longitude: null,
    openingHours: null,
    deliveryInfo: null,
    minimumOrderPennies: 0,
    published,
    tags: null,
    createdAt: "2026-10-01T10:00:00Z",
    updatedAt: "2026-10-01T10:00:00Z",
    ordersPausedIndefinitely: false,
    offersDelivery: true,
    offersCollection: true,
  }
}

function stubShops(...shops: ReturnType<typeof shop>[]) {
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) =>
    url.startsWith("/api/v1/shops?")
      ? Promise.resolve({ data: { content: shops, totalElements: shops.length, totalPages: 1 } })
      : Promise.resolve({ data: shops[0] })
  )
  mockPut.mockReset()
  mockPut.mockResolvedValue({ data: {} })
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

describe("the shop form does not offer a publish switch (#835)", () => {
  it("shows a not-live shop as read-only status with a link to the go-live flow, and no checkbox", async () => {
    stubShops(shop("draft-shop-1", "Draft Shop", false))
    const dialog = await openEdit("Draft Shop")
    expect(within(dialog).queryByRole("checkbox", { name: /publish/i })).toBeNull()
    expect(within(dialog).getByText("Not live yet")).toBeInTheDocument()
    const link = within(dialog).getByRole("link", { name: /go live/i })
    expect(link).toHaveAttribute("href", "/dashboard/onboarding")
  })

  it("shows a live shop as Live", async () => {
    stubShops(shop("live-shop-1", "Live Shop", true))
    const dialog = await openEdit("Live Shop")
    expect(within(dialog).queryByRole("checkbox", { name: /publish/i })).toBeNull()
    expect(within(dialog).getByText("Live on the storefront")).toBeInTheDocument()
  })

  it("saves another edit without sending `published` at all", async () => {
    stubShops(shop("draft-shop-1", "Draft Shop", false))
    const dialog = await openEdit("Draft Shop")
    await act(async () => {
      fireEvent.change(within(dialog).getByLabelText("Description"), { target: { value: "New description" } })
    })
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Update Shop" }))
    })
    await waitFor(() => expect(mockPut).toHaveBeenCalledTimes(1))
    const body = mockPut.mock.calls[0][1] as Record<string, unknown>
    expect(body.description).toBe("New description")
    expect("published" in body).toBe(false)
  })

  it("an old server's publish refusal is shown in plain words, never the developer text", async () => {
    stubShops(shop("draft-shop-1", "Draft Shop", false))
    mockPut.mockRejectedValue({
      isAxiosError: true,
      message: "Request failed with status code 409",
      response: {
        status: 409,
        data: {
          type: "https://jtoye.uk/errors/shop-publish-not-accepted",
          detail:
            "Shop.published is written only by the onboarding state machine and cannot be changed through this endpoint. To publish, use POST /api/v1/onboarding/go-live (APPROVED -> LIVE). contact support",
        },
      },
    })
    await openEdit("Draft Shop")
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Update Shop" }))
    })
    await waitFor(() => expect(mockToast).toHaveBeenCalled())
    const shown = JSON.stringify(mockToast.mock.calls)
    expect(shown).not.toMatch(/onboarding state machine|POST \/api|Shop\.published|contact support/)
  })
})
