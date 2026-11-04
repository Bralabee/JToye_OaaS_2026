/**
 * #862 (owner ruling 2026-11-04, persona close-out): the kitchen directory can be narrowed by what
 * a vendor DECLARES. The control says "declares", asks the API with `declares=<Name>`, and never
 * says or implies "free from". A search that asks for the ABSENCE of an allergen ("gluten free")
 * is answered with a plain statement that the platform shows declarations, not absences.
 */
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { ShopDiscoveryClient } from "@/app/shop/shop-discovery-client"
import publicApiClient from "@/lib/public-api-client"
import type { PageResponse } from "@/types/api"
import type { PublicShop } from "@/types/storefront"

jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn() },
}))
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockGet = publicApiClient.get as jest.Mock

function shop(slug: string, name: string): PublicShop {
  return {
    slug,
    name,
    description: null,
    address: null,
    logoUrl: null,
    bannerUrl: null,
    phone: null,
    email: null,
    latitude: null,
    longitude: null,
    openingHours: null,
    deliveryInfo: null,
    minimumOrderPennies: 0,
    deliveryFeePennies: 0,
    freeDeliveryThresholdPennies: null,
    tags: null,
  }
}

function page(content: PublicShop[]): PageResponse<PublicShop> {
  return {
    content,
    totalElements: content.length,
    totalPages: content.length ? 1 : 0,
    size: 12,
    number: 0,
    first: true,
    last: true,
  }
}

function renderDiscovery(query = "") {
  return render(
    <ShopDiscoveryClient
      initial={page([shop("a", "Alpha Kitchen")])}
      initialQuery={query}
      initialInterpretation={{ kind: "text" }}
    />
  )
}

beforeEach(() => {
  mockGet.mockReset()
})

describe("kitchen directory: the declared-allergen filter (#862)", () => {
  it("offers the 14 UK FSA allergens in a labelled select, with the declaration help always visible", () => {
    renderDiscovery()
    const select = screen.getByRole("combobox", { name: /show dishes that declare/i })
    // 14 allergens + the "Any allergen" default.
    expect(select.querySelectorAll("option")).toHaveLength(15)
    expect(screen.getByRole("option", { name: "Peanuts" })).toBeInTheDocument()
    expect(screen.getByRole("option", { name: "Gluten" })).toBeInTheDocument()
    // The help is tied to the control and says what the list is and is not.
    const help = screen.getByText(/reflects only what each kitchen has declared/i)
    expect(select.getAttribute("aria-describedby")).toBe(help.id)
    expect(help).toHaveTextContent(/not confirmed free of it/i)
  })

  it("picking Peanuts asks the API with declares=Peanuts and says what is shown", async () => {
    mockGet.mockResolvedValue({
      data: page([shop("suya", "Suya House")]),
      headers: {},
    })
    renderDiscovery()

    fireEvent.change(screen.getByRole("combobox", { name: /show dishes that declare/i }), {
      target: { value: "4" },
    })

    await waitFor(() => expect(mockGet).toHaveBeenCalled())
    const [url, config] = mockGet.mock.calls[mockGet.mock.calls.length - 1]
    expect(url).toBe("/public/shops")
    expect(config.params.declares).toBe("Peanuts")
    expect(await screen.findByText("Suya House")).toBeInTheDocument()
    expect(screen.getByText(/showing kitchens with a dish that declares peanuts/i)).toBeInTheDocument()
  })

  it("returning to 'Any allergen' drops the parameter", async () => {
    mockGet.mockResolvedValue({ data: page([shop("a", "Alpha Kitchen")]), headers: {} })
    renderDiscovery()
    const select = screen.getByRole("combobox", { name: /show dishes that declare/i })

    fireEvent.change(select, { target: { value: "4" } })
    await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(1))
    fireEvent.change(select, { target: { value: "" } })
    await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(2))

    expect(mockGet.mock.calls[1][1].params.declares).toBeUndefined()
  })

  it("never claims 'free from' anywhere on the page while the filter is active", async () => {
    mockGet.mockResolvedValue({ data: page([shop("suya", "Suya House")]), headers: {} })
    const { container } = renderDiscovery()
    fireEvent.change(screen.getByRole("combobox", { name: /show dishes that declare/i }), {
      target: { value: "0" },
    })
    await screen.findByText("Suya House")

    const text = container.textContent ?? ""
    expect(text).not.toMatch(/allergen[- ]free|gluten[- ]free|nut[- ]free|\bsafe\b|guarantee/i)
    // The help line may DENY it ("not confirmed free of it"); it must never assert it.
    expect(text).not.toMatch(/\bfree from\b/i)
  })

  it("a search that asks for the ABSENCE of an allergen gets the plain statement, not a declaration list", async () => {
    mockGet.mockResolvedValue({ data: page([]), headers: {} })
    renderDiscovery("gluten free")

    expect(
      await screen.findByText(/we can show dishes that declare an allergen, not dishes that are free from one/i)
    ).toBeInTheDocument()
  })

  it("CONTROL: an ordinary search shows no such statement", () => {
    renderDiscovery("jollof")
    expect(screen.queryByText(/not dishes that are free from one/i)).toBeNull()
    act(() => {})
  })
})
