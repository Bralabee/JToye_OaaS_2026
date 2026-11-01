/**
 * SW-ADE-02 (swarm epic #964): the product dialog's image uploader stayed on "Processing…" although
 * the server had made the asset ACTIVE, because the page gave the uploader no way to read the asset
 * back. The page now passes `resolveAcceptedAsset`, which reads the outcome from the two places the
 * server reports it: the product's own `media` list (placed assets) and the review queue (a FAILED
 * upload is never placed, D-04a).
 *
 * The uploader is replaced by a probe that records its props, so this tests the page's wiring and the
 * reader it uses; the uploader's own polling is image-uploader-processing.test.tsx.
 */
import { fireEvent, render, screen } from "@testing-library/react"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"
import type { AcceptedAssetOutcome } from "@/types/api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

let uploaderProps: { resolveAcceptedAsset?: (assetId: string) => Promise<AcceptedAssetOutcome> } = {}
jest.mock("@/components/ui/image-uploader", () => ({
  ImageUploader: (props: typeof uploaderProps) => {
    uploaderProps = props
    return <div data-testid="uploader-probe" />
  },
}))

const product = {
  id: "p-1",
  tenantId: "tenant-1",
  sku: "JOL-1",
  title: "Jollof Rice",
  ingredientsText: "rice, tomato",
  allergenMask: 0,
  pricePennies: 950,
  vatRate: "STANDARD",
  description: null,
  imageUrl: null,
  additionalImageUrls: [],
  category: null,
  displayOrder: 0,
  available: true,
  featured: false,
  preparationTimeMinutes: null,
  dietaryTags: null,
  shopId: null,
  quantityInStock: null,
  allergenWarnings: [],
  createdAt: "2026-10-09T12:00:00Z",
  updatedAt: "2026-10-09T12:00:00Z",
}

function asset(assetId: string, status: string, extra: Record<string, unknown> = {}) {
  return {
    assetId,
    status,
    flagged: false,
    failureReason: null,
    url: status === "ACTIVE" ? `https://cdn.test/${assetId}.webp` : null,
    thumbnailUrl: null,
    width: null,
    height: null,
    redrivable: false,
    delayed: false,
    ...extra,
  }
}

function serve({ media, queue }: { media: unknown[]; queue: unknown[] }) {
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url === "/api/v1/products/p-1") return Promise.resolve({ data: { ...product, media } })
    if (url === "/api/v1/media/review-queue") return Promise.resolve({ data: queue })
    if (url.startsWith("/api/v1/products"))
      return Promise.resolve({ data: { content: [product], totalElements: 1, totalPages: 1 } })
    return Promise.resolve({ data: { content: [], totalElements: 0, totalPages: 1 } })
  }) as never)
}

async function openEdit() {
  render(<ProductsPage />)
  fireEvent.click(await screen.findByRole("button", { name: "Edit product Jollof Rice" }))
  await screen.findByTestId("uploader-probe")
  expect(typeof uploaderProps.resolveAcceptedAsset).toBe("function")
  return uploaderProps.resolveAcceptedAsset as (assetId: string) => Promise<AcceptedAssetOutcome>
}

beforeEach(() => {
  jest.clearAllMocks()
  uploaderProps = {}
})

describe("product image dialog reads the accepted asset back (SW-ADE-02)", () => {
  it("reports ACTIVE with the derivative URL once the asset is placed on the product", async () => {
    serve({ media: [asset("asset-1", "ACTIVE")], queue: [] })
    const resolve = await openEdit()
    await expect(resolve("asset-1")).resolves.toEqual({
      status: "ACTIVE",
      url: "https://cdn.test/asset-1.webp",
      failureReason: null,
    })
  })

  it("reports FAILED with the server's reason from the review queue (a failed upload is never placed)", async () => {
    serve({
      media: [asset("old-asset", "ACTIVE")],
      queue: [asset("asset-1", "FAILED", { failureReason: "Not an image we can read." })],
    })
    const resolve = await openEdit()
    await expect(resolve("asset-1")).resolves.toEqual({
      status: "FAILED",
      url: null,
      failureReason: "Not an image we can read.",
    })
  })

  it("reports PENDING while neither the product nor the queue settles it", async () => {
    serve({ media: [asset("old-asset", "ACTIVE")], queue: [] })
    const resolve = await openEdit()
    await expect(resolve("asset-1")).resolves.toEqual({ status: "PENDING", url: null, failureReason: null })
  })
})
