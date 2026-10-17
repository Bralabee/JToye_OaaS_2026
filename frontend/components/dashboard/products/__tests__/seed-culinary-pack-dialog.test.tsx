import { render, screen, waitFor, fireEvent } from "@testing-library/react"
import { SeedCulinaryPackDialog } from "../seed-culinary-pack-dialog"
import type { CulinaryPackDto, CulinaryPackSeedResult, Shop } from "@/types/api"

const mockGet = jest.fn()
const mockPost = jest.fn()

jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockGet(...args),
    post: (...args: unknown[]) => mockPost(...args),
  },
}))

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

const sampleShops: Shop[] = [
  {
    id: "shop-peckham",
    tenantId: "tenant-1",
    name: "Mama Put Peckham",
    slug: "mama-put-peckham",
    published: true,
    createdAt: "2026-10-01T10:00:00Z",
    updatedAt: "2026-10-01T10:00:00Z",
  },
  {
    id: "shop-brixton",
    tenantId: "tenant-1",
    name: "Brixton Grill",
    slug: "brixton-grill",
    published: true,
    createdAt: "2026-10-01T10:00:00Z",
    updatedAt: "2026-10-01T10:00:00Z",
  },
]

const samplePacks: CulinaryPackDto[] = [
  {
    id: "C1",
    name: "Nigerian",
    region: "West Africa",
    dishCount: 10,
    statutoryNotice:
      "Starter templates are seeded as unpublished drafts (available=false). Under FIR 2014 Reg. 5 & Reg. 8, the Food Business Operator must verify every ingredient.",
    dishes: [
      {
        id: "C1-D01",
        commonName: "Smoky Party Jollof Rice",
        localNames: ["Jollof", "Party Jollof"],
        category: "Rice & Grains",
        typicalIngredients: "Long-grain parboiled rice, plum tomatoes, red bell peppers (tatashe)",
        allergenMask: 1638,
        allergenNames: ["Crustaceans", "Fish", "Soybeans", "Celery", "Mustard"],
        mayContainMask: 1168,
        crossContaminationNotes: "Shared prep surfaces",
        prepStyle: "HOT_IMMEDIATE",
        vatNote: "Hot takeaway food (20% standard VAT)",
        dietaryTags: "Halal-friendly",
      },
    ],
  },
  {
    id: "C2",
    name: "Ghanaian",
    region: "West Africa",
    dishCount: 6,
    statutoryNotice:
      "Starter templates are seeded as unpublished drafts (available=false). Under FIR 2014 Reg. 5 & Reg. 8, the Food Business Operator must verify every ingredient.",
    dishes: [],
  },
]

describe("SeedCulinaryPackDialog (GTM-BLK-02)", () => {
  beforeEach(() => {
    mockGet.mockReset()
    mockPost.mockReset()
  })

  it("loads culinary packs, displays the statutory FBO draft verification notice, and seeds selected packs", async () => {
    mockGet.mockResolvedValue({ data: samplePacks })
    const seedResponse: CulinaryPackSeedResult = {
      shopId: "shop-peckham",
      requestedPacks: ["C1"],
      created: 10,
      skipped: 0,
      statutoryNotice:
        "All seeded dishes are saved as unpublished drafts (available=false). Verify every recipe before publishing.",
      items: [],
    }
    mockPost.mockResolvedValue({ data: seedResponse })

    const onSeeded = jest.fn()
    render(
      <SeedCulinaryPackDialog
        open={true}
        onOpenChange={jest.fn()}
        shops={sampleShops}
        defaultShopId="shop-peckham"
        onSeeded={onSeeded}
      />
    )

    // Statutory notice and loaded packs appear
    expect(await screen.findByTestId("culinary-pack-list")).toBeInTheDocument()
    expect(screen.getByTestId("culinary-statutory-notice")).toHaveTextContent(/FIR 2014/i)
    expect(screen.getByText("Nigerian")).toBeInTheDocument()
    expect(screen.getByText("10 dishes")).toBeInTheDocument()
    expect(screen.getByText("Ghanaian")).toBeInTheDocument()

    // Submit button disabled until at least one pack is selected
    const seedBtn = screen.getByTestId("seed-culinary-submit-btn")
    expect(seedBtn).toBeDisabled()

    // Select C1 (Nigerian)
    const c1Checkbox = screen.getByTestId("culinary-pack-checkbox-C1")
    fireEvent.click(c1Checkbox)
    expect(seedBtn).toBeEnabled()

    // Click seed
    fireEvent.click(seedBtn)

    await waitFor(() => {
      expect(mockPost).toHaveBeenCalledWith("/api/v1/products/culinary-packs/seed", {
        shopId: "shop-peckham",
        cuisineIds: ["C1"],
        defaultPricePennies: 950,
      })
    })

    // Result summary shown
    const statusBanner = await screen.findByTestId("seed-culinary-result")
    expect(statusBanner).toHaveTextContent(/10 draft dishes seeded/i)
    expect(onSeeded).toHaveBeenCalledWith(seedResponse)
  })
})
