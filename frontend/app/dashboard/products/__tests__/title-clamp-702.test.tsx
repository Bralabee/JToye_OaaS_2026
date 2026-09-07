/**
 * QA council 20260902-134741 — A11Y-10, issue #702.
 *
 * The products title cell was `<div className="font-medium">` with computed
 * `line-clamp: none`, so a realistic 224-character title took its row from
 * 72px to 189px while the storefront truncates the same string on one line
 * (probes/a11y/16). Its sibling IngredientText already carried `line-clamp-1`.
 * `line-clamp-2` (a title is the row's identifier; one line truncates real
 * titles) + `break-words` (an unbreakable 224-char token otherwise overflows
 * the table horizontally instead of wrapping).
 *
 * jsdom does not lay out, so the class is asserted here; the 189px -> ~72px
 * row height is probe 16 after the rebuild.
 */
import { render, screen } from "@testing-library/react"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

const LONG_TITLE =
  "Grandma Ade's Signature Slow-Cooked Smoky Jollof Rice with Grilled Peppered Chicken Thighs, Fried Sweet Plantain, Coleslaw and a Side of Pepper Sauce — Family Feast Portion for Four to Six People (Serves 4-6)"

describe("Products table — long title clamp (A11Y-10 / #702)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [
          {
            id: "prod-702",
            tenantId: "tenant-1",
            sku: "LONG-702",
            title: LONG_TITLE,
            ingredientsText: "Rice, tomato, chicken",
            allergenMask: 0,
            pricePennies: 4500,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
          },
        ],
        totalElements: 1,
        totalPages: 1,
      },
    })
  })

  it("clamps the title cell to two lines and lets unbreakable tokens wrap", async () => {
    render(<ProductsPage />)
    const title = await screen.findByText(LONG_TITLE)
    expect(title).toHaveClass("line-clamp-2")
    expect(title).toHaveClass("break-words")
    // Control: the neighbouring ingredient line still has its own clamp.
    expect(title.parentElement?.querySelector(".line-clamp-1")).not.toBeNull()
  })
})
