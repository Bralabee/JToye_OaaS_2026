/**
 * SellerBlock — who the customer is buying from (#789, 31.1-24; D-10/D-11/D-13/D-20).
 *
 * The block is legally operative: CCR 2013 Sch 2(b)-(c) and (o), and E-Commerce Regs 2002
 * reg 6(1)(c). Its copy is compared as LITERAL strings here, not by importing the constants, so a
 * paraphrase of a statutory statement fails this suite instead of passing it by construction.
 *
 * What is pinned:
 *   - every field the server sends is rendered, in ONE fixed order on every surface (legal name,
 *     company number, VAT number, address, email, phone, platform statement, cancellation);
 *   - a field the trader does not have is ABSENT — never a label with nothing after it;
 *   - numbers render exactly as stored (no reformatting, no rounding, leading zeros kept);
 *   - no seller details -> the not-provided copy with the platform contact, never an invented field;
 *   - the shop page (a SERVER component) emits the block itself, from the shop it already loaded:
 *     no client fetch, so it is in the served HTML and cannot shift the layout when it arrives.
 */
import { render, screen, within } from "@testing-library/react"
import { axe, toHaveNoViolations } from "jest-axe"

import {
  SellerBlock,
  CANCELLATION_STATEMENT_COPY,
  PLATFORM_NOT_SELLER_COPY,
  SELLER_DETAILS_MISSING_COPY,
} from "@/components/storefront/seller-block"
import type { PublicSeller, PublicShop } from "@/types/storefront"

expect.extend(toHaveNoViolations)

const PLATFORM_LITERAL =
  "J'Toye is the ordering platform. Your contract for this order is with the seller named here."
const CANCELLATION_LITERAL =
  "Your order is freshly prepared food, which is liable to deteriorate rapidly, so the 14-day right to cancel under the Consumer Contracts Regulations 2013 (regulation 28(1)(c)) does not apply. This does not affect your rights if the food is faulty or not as described."
const MISSING_LITERAL = "The seller has not provided their legal details yet."

const COMPANY: PublicSeller = {
  legalName: "Mama Ade Foods Ltd",
  entityType: "COMPANY",
  companyNumber: "01234567",
  vatNumber: "GB123456789",
  addressLines: ["12 Market Street", "Unit 4", "Birmingham", "B1 1AA"],
  email: "orders@mama-ade.example.com",
  phone: "0121 496 0000",
}

const SOLE_TRADER: PublicSeller = {
  legalName: "Ada Okafor",
  entityType: "SOLE_TRADER",
  addressLines: ["48 Rye Lane", "London", "SE15 5BS"],
  email: "ada@example.com",
}

function block() {
  return screen.getByRole("region", { name: "Who you are buying from" })
}

describe("SellerBlock: the statutory copy is the contracted wording", () => {
  it("states that J'Toye is the platform and not the seller, verbatim", () => {
    expect(PLATFORM_NOT_SELLER_COPY).toBe(PLATFORM_LITERAL)
  })

  it("carries the CCR reg 28(1)(c) cancellation statement for freshly prepared food, verbatim", () => {
    expect(CANCELLATION_STATEMENT_COPY).toBe(CANCELLATION_LITERAL)
  })

  it("says the seller has not provided their details, verbatim", () => {
    expect(SELLER_DETAILS_MISSING_COPY).toBe(MISSING_LITERAL)
  })
})

describe("SellerBlock: a company with every field", () => {
  it("renders the legal name, entity type, company number, VAT number and address", () => {
    render(<SellerBlock seller={COMPANY} />)
    const region = block()
    expect(within(region).getByText("Mama Ade Foods Ltd")).toBeInTheDocument()
    expect(within(region).getByText("Registered company")).toBeInTheDocument()
    expect(within(region).getByText("01234567")).toBeInTheDocument()
    expect(within(region).getByText("GB123456789")).toBeInTheDocument()
    expect(within(region).getByText("12 Market Street, Unit 4, Birmingham, B1 1AA")).toBeInTheDocument()
  })

  it("renders the email as a mailto link and the phone as a tel link", () => {
    render(<SellerBlock seller={COMPANY} />)
    const region = block()
    const email = within(region).getByRole("link", { name: "orders@mama-ade.example.com" })
    expect(email).toHaveAttribute("href", "mailto:orders@mama-ade.example.com")
    const phone = within(region).getByRole("link", { name: "0121 496 0000" })
    expect(phone).toHaveAttribute("href", "tel:01214960000")
  })

  it("carries the platform statement and the cancellation statement", () => {
    render(<SellerBlock seller={COMPANY} />)
    const region = block()
    expect(within(region).getByText(PLATFORM_LITERAL)).toBeInTheDocument()
    expect(within(region).getByText(CANCELLATION_LITERAL)).toBeInTheDocument()
    expect(within(region).queryByText(MISSING_LITERAL)).not.toBeInTheDocument()
  })

  it("labels each field, so a screen reader hears what each value is", () => {
    render(<SellerBlock seller={COMPANY} />)
    const terms = within(block()).getAllByRole("term").map((t) => t.textContent)
    expect(terms).toEqual(["Seller", "Company number", "VAT number", "Address", "Email", "Phone"])
  })

  it("puts the fields in ONE fixed order: name, company no., VAT, address, email, phone, platform, cancellation", () => {
    render(<SellerBlock seller={COMPANY} />)
    const text = block().textContent ?? ""
    const positions = [
      "Mama Ade Foods Ltd",
      "01234567",
      "GB123456789",
      "12 Market Street",
      "orders@mama-ade.example.com",
      "0121 496 0000",
      PLATFORM_LITERAL,
      CANCELLATION_LITERAL,
    ].map((needle) => text.indexOf(needle))
    expect(positions.every((p) => p >= 0)).toBe(true)
    expect([...positions].sort((a, b) => a - b)).toEqual(positions)
  })

  it("renders company and VAT numbers exactly as stored (no reformatting, leading zero kept)", () => {
    render(<SellerBlock seller={{ ...COMPANY, companyNumber: "SC012345", vatNumber: "GB123456789012" }} />)
    const region = block()
    expect(within(region).getByText("SC012345")).toBeInTheDocument()
    expect(within(region).getByText("GB123456789012")).toBeInTheDocument()
  })

  it("has no axe violations (after a non-vacuity control)", async () => {
    const { container } = render(<SellerBlock seller={COMPANY} />)
    expect(within(block()).getByText("Mama Ade Foods Ltd")).toBeInTheDocument()
    expect(await axe(container)).toHaveNoViolations()
  })
})

describe("SellerBlock: a field the trader does not have is absent, never blank", () => {
  it("a sole trader without company number, VAT or phone shows none of those labels", () => {
    render(<SellerBlock seller={SOLE_TRADER} />)
    const region = block()
    expect(within(region).getByText("Ada Okafor")).toBeInTheDocument()
    expect(within(region).getByText("Sole trader")).toBeInTheDocument()
    expect(within(region).getByText("48 Rye Lane, London, SE15 5BS")).toBeInTheDocument()
    const terms = within(region).getAllByRole("term").map((t) => t.textContent)
    expect(terms).toEqual(["Seller", "Address", "Email"])
    expect(region.querySelector('a[href^="tel:"]')).toBeNull()
    expect(within(region).getByText(CANCELLATION_LITERAL)).toBeInTheDocument()
  })
})

describe("SellerBlock: no seller details on file", () => {
  it.each([
    ["null", null],
    ["undefined (absent on the wire)", undefined],
  ])("seller %s -> the not-provided copy with the platform contact, and no invented field", (_label, seller) => {
    render(<SellerBlock seller={seller} />)
    const region = block()
    expect(within(region).getByText(MISSING_LITERAL)).toBeInTheDocument()
    const contact = within(region).getByRole("link", { name: "J'Toye's legal and contact details" })
    expect(contact).toHaveAttribute("href", "/legal")
    expect(within(region).queryAllByRole("term")).toHaveLength(0)
    // "the seller named here" would be false: nobody is named
    expect(within(region).queryByText(PLATFORM_LITERAL)).not.toBeInTheDocument()
    expect(region.textContent).toContain("J'Toye is the ordering platform, not the seller.")
    // the food is still fresh food: the no-cancellation information still applies
    expect(within(region).getByText(CANCELLATION_LITERAL)).toBeInTheDocument()
  })
})

// ---- the shop page: a SERVER component emits the block ------------------------------------

const mockLoadShopDetail = jest.fn()
jest.mock("@/lib/storefront-server", () => ({
  loadShopDetail: (...args: unknown[]) => mockLoadShopDetail(...args),
}))
jest.mock("next/headers", () => ({
  headers: async () => new Map([["x-nonce", "n0nce"]]),
}))
jest.mock("next/navigation", () => ({
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND")
  },
}))
jest.mock("@/app/shop/[slug]/shop-detail-client", () => ({
  ShopDetailClient: () => <div data-testid="shop-detail-client" />,
}))

function shopWith(seller: PublicSeller | undefined): PublicShop {
  return {
    slug: "mama-ade",
    name: "Mama Ade's Kitchen",
    description: null,
    address: "48 Rye Lane, London",
    logoUrl: null,
    bannerUrl: null,
    phone: null,
    email: "kitchen@mama-ade.example.com",
    latitude: null,
    longitude: null,
    openingHours: null,
    deliveryInfo: null,
    minimumOrderPennies: null,
    deliveryFeePennies: null,
    freeDeliveryThresholdPennies: null,
    tags: null,
    ...(seller ? { seller } : {}),
  }
}

function okLoad(seller: PublicSeller | undefined) {
  return {
    state: "ok",
    data: {
      shop: shopWith(seller),
      products: {},
      reviews: [],
      reviewCount: 0,
      avgRating: 0,
      promotions: [],
      announcements: [],
      isOpen: true,
    },
  }
}

describe("shop page (server component): the seller block is in the server output", () => {
  // Imported lazily so the mocks above are in place before the page module evaluates.
  async function renderShopPage() {
    const { default: ShopDetailPage } = await import("@/app/shop/[slug]/page")
    const element = await ShopDetailPage({ params: Promise.resolve({ slug: "mama-ade" }) })
    return render(element)
  }

  beforeEach(() => mockLoadShopDetail.mockReset())

  it("renders the seller block from the loaded shop, after the menu island", async () => {
    mockLoadShopDetail.mockResolvedValue(okLoad(SOLE_TRADER))
    const { container } = await renderShopPage()
    const region = block()
    expect(within(region).getByText("Ada Okafor")).toBeInTheDocument()
    expect(within(region).getByText(PLATFORM_LITERAL)).toBeInTheDocument()
    const island = screen.getByTestId("shop-detail-client")
    expect(island.compareDocumentPosition(region) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // one load, shared with generateMetadata: the block makes no fetch of its own
    expect(mockLoadShopDetail).toHaveBeenCalledTimes(1)
    expect(container.querySelectorAll("[data-seller-block]")).toHaveLength(1)
  })

  it("a shop whose trader has given no details renders the not-provided copy", async () => {
    mockLoadShopDetail.mockResolvedValue(okLoad(undefined))
    await renderShopPage()
    expect(within(block()).getByText(MISSING_LITERAL)).toBeInTheDocument()
  })

  it("a shop the server could not read (defer) renders no seller block — a load failure is not 'not provided'", async () => {
    mockLoadShopDetail.mockResolvedValue({ state: "defer" })
    await renderShopPage()
    expect(screen.getByTestId("shop-detail-client")).toBeInTheDocument()
    expect(screen.queryByRole("region", { name: "Who you are buying from" })).not.toBeInTheDocument()
    expect(screen.queryByText(MISSING_LITERAL)).not.toBeInTheDocument()
  })
})
