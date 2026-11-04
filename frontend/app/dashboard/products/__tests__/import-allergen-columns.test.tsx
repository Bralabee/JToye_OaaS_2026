/**
 * #799 remainder (C-10): the CSV import took allergens only as the numeric `allergen_mask` bitmask,
 * which the page listed without saying what the number means. The server now also reads the 14 UK
 * allergen NAMES (an `allergens` column, or names in the allergen_mask cell), so the page documents
 * both, using the same names the catalogue holds (frontend ALLERGENS, kept in parity with the server).
 *
 * Out of scope here, said on purpose: an EPOS / Square / aggregator import. That is a separate
 * feature with no plan, and this change does not claim to start it.
 */
import { render, screen } from "@testing-library/react"
import ImportProductsPage from "../import/page"
import apiClient from "@/lib/api-client"
import { ALLERGENS } from "@/types/api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/lib/shop-context", () => ({
  ALL_SHOPS_CONTEXT: "all",
  getShopContext: jest.fn(() => "all"),
  setShopContext: jest.fn(),
  subscribeShopContext: jest.fn(() => () => {}),
}))

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockResolvedValue({ data: { content: [], totalElements: 0, totalPages: 0 } } as never)
})

describe("the import page documents allergen names (#799)", () => {
  it("lists the allergens column and says names or the number are accepted", () => {
    render(<ImportProductsPage />)
    const columns = screen.getByTestId("import-columns")
    expect(columns).toHaveTextContent("allergens")
    expect(columns).toHaveTextContent("allergen_mask")
    expect(columns).toHaveTextContent(/names or the number/i)
  })

  it("says to separate allergens with a semicolon or a bar, and never a comma (a comma shifts the columns)", () => {
    render(<ImportProductsPage />)
    const text = screen.getByTestId("import-allergen-help").textContent ?? ""
    expect(text).toMatch(/semicolons? \(;\)/i)
    expect(text).toMatch(/bars? \(\|\)/i)
    expect(text).toMatch(/never (a )?commas?/i)
    expect(text).not.toMatch(/commas or semicolons/i)
  })

  it("names every one of the 14 allergens, exactly as the catalogue spells them", () => {
    render(<ImportProductsPage />)
    const text = screen.getByTestId("import-allergen-help").textContent ?? ""
    for (const a of ALLERGENS) expect(text).toContain(a.name)
    expect(ALLERGENS).toHaveLength(14)
  })
})
