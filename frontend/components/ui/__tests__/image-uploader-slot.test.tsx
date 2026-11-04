/**
 * #831 (UXT-062 / ADE-02): saving a shop overwrote its banner with the logo URL.
 *
 * The legacy synchronous shop endpoints (POST /shops/{id}/logo and /banner) return the WHOLE ShopDto,
 * so the response carries logoUrl AND bannerUrl. The uploader used to take the first non-empty of
 * imageUrl || logoUrl || bannerUrl, so a banner upload on a shop that already had a logo handed the
 * LOGO url to the banner slot's onUploadComplete, and Update Shop then persisted it as bannerUrl.
 * The uploader must hand over the field of the slot it uploaded for.
 */
import { act, fireEvent, render } from "@testing-library/react"
import { ImageUploader } from "@/components/ui/image-uploader"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

class FakeImage {
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  naturalWidth = 1200
  naturalHeight = 1200
  private _src = ""
  set src(v: string) {
    this._src = v
    void Promise.resolve().then(() => this.onload?.())
  }
  get src() {
    return this._src
  }
}

const realImage = global.Image
beforeAll(() => {
  ;(global as unknown as { Image: unknown }).Image = FakeImage
  URL.createObjectURL = jest.fn(() => "blob:local-preview")
  URL.revokeObjectURL = jest.fn()
})
afterAll(() => {
  ;(global as unknown as { Image: unknown }).Image = realImage
})
beforeEach(() => {
  jest.clearAllMocks()
})

const SHOP_DTO = {
  id: "shop-1",
  imageUrl: null,
  logoUrl: "https://cdn.test/logo.webp",
  bannerUrl: "https://cdn.test/banner.webp",
}

async function upload(aspectRatio: "square" | "banner" | "logo", data: Record<string, unknown>) {
  mockedApiClient.post.mockResolvedValue({ status: 200, data } as never)
  const onUploadComplete = jest.fn()
  const { container } = render(
    <ImageUploader uploadUrl="/api/v1/shops/shop-1/x" onUploadComplete={onUploadComplete} aspectRatio={aspectRatio} label="x" />
  )
  const input = container.querySelector('input[type="file"]') as HTMLInputElement
  const file = new File([new Uint8Array([71, 73, 70])], "x.gif", { type: "image/gif" })
  await act(async () => {
    fireEvent.change(input, { target: { files: [file] } })
  })
  for (let i = 0; i < 5; i++) await act(async () => {})
  return onUploadComplete
}

describe("ImageUploader hands over the field of the slot it uploaded (#831)", () => {
  it("banner slot gets bannerUrl, not the logo that sits beside it in the ShopDto", async () => {
    const done = await upload("banner", SHOP_DTO)
    expect(done).toHaveBeenCalledWith("https://cdn.test/banner.webp")
  })

  it("logo slot gets logoUrl, not the banner", async () => {
    const done = await upload("logo", SHOP_DTO)
    expect(done).toHaveBeenCalledWith("https://cdn.test/logo.webp")
  })

  it("square (product) slot still takes imageUrl, from the bare DTO or a nested product", async () => {
    const bare = await upload("square", { imageUrl: "https://cdn.test/dish.webp" })
    expect(bare).toHaveBeenCalledWith("https://cdn.test/dish.webp")
    const nested = await upload("square", { product: { imageUrl: "https://cdn.test/nested.webp" } })
    expect(nested).toHaveBeenCalledWith("https://cdn.test/nested.webp")
  })

  it("a banner response with no bannerUrl never falls back to the logo", async () => {
    const done = await upload("banner", { ...SHOP_DTO, bannerUrl: null })
    expect(done).not.toHaveBeenCalledWith("https://cdn.test/logo.webp")
  })
})
