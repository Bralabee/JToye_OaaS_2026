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

const baseShop: Shop = {
  id: "shop-peckham",
  tenantId: "tenant-1",
  name: "Mama Put Peckham",
  address: "12 Rye Lane",
  slug: "mama-put-peckham",
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
}

const sampleShops: Shop[] = [
  baseShop,
  {
    ...baseShop,
    id: "shop-brixton",
    name: "Brixton Grill",
    slug: "brixton-grill",
  },
]

const samplePacks: CulinaryPackDto[] = [
  {
    cuisineKey: "nigerian",
    cuisineLabel: "Nigerian",
    region: "West Africa",
    dishCount: 10,
    dishes: [
      {
        slug: "smoky-party-jollof-rice",
        name: "Smoky Party Jollof Rice",
        cuisineKey: "nigerian",
        cuisineLabel: "Nigerian",
        region: "West Africa",
        course: "rice_and_grain",
        description: "Classic Nigerian party jollof rice",
        shelfLifeDays: 2,
        allergenMask: 1638,
        allergenNames: ["Crustaceans", "Fish", "Soybeans", "Celery", "Mustard"],
        mayContainMask: 1168,
        mayContainNames: ["Peanuts", "Milk", "Sesame"],
        ingredientsText: "Long-grain parboiled rice, plum tomatoes, red bell peppers (tatashe)",
        dietaryTags: ["Halal-friendly"],
      },
    ],
  },
  {
    cuisineKey: "ghanaian",
    cuisineLabel: "Ghanaian",
    region: "West Africa",
    dishCount: 6,
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
      created: 10,
      skipped: 0,
      failed: 0,
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

    // Select nigerian
    const c1Checkbox = screen.getByTestId("culinary-pack-checkbox-nigerian")
    fireEvent.click(c1Checkbox)
    expect(seedBtn).toBeEnabled()

    // Click seed
    fireEvent.click(seedBtn)

    await waitFor(() => {
      expect(mockPost).toHaveBeenCalledWith("/api/v1/products/culinary-packs/seed", {
        shopId: "shop-peckham",
        cuisineKeys: ["nigerian"],
        defaultPricePennies: 950,
      })
    })

    // Result summary shown
    const statusBanner = await screen.findByTestId("seed-culinary-result")
    expect(statusBanner).toHaveTextContent(/10 draft dishes seeded/i)
    expect(onSeeded).toHaveBeenCalledWith(seedResponse)
  })
})
