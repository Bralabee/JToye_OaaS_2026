/**
 * SW-ADE-02 (swarm epic #964): after an async accept (202, asset PENDING) the product image dialog
 * stayed on "Processing…" for as long as it was open, although the server had already made the
 * asset ACTIVE: only a new file pick ever left that state, and nothing re-read the asset.
 *
 * The uploader now asks its caller for the asset's outcome on an interval, with a cap, and leaves
 * Processing when the asset is ACTIVE (handing the image URL to onUploadComplete), FAILED (showing
 * the server's reason), or the cap is reached (saying it is still processing, without a spinner
 * that never ends).
 *
 * The upload path is driven for real through the hidden file input. A GIF is used because
 * compressImage passes GIFs through untouched (jsdom has no canvas), and `Image` is stubbed so the
 * dimension check can read a size.
 */
import { act, fireEvent, render, screen } from "@testing-library/react"
import {
  ASSET_POLL_INTERVAL_MS,
  ASSET_POLL_MAX_ATTEMPTS,
  ImageUploader,
} from "@/components/ui/image-uploader"
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
  jest.useFakeTimers()
  jest.clearAllMocks()
  mockedApiClient.post.mockResolvedValue({ status: 202, data: { assetId: "asset-1", status: "PENDING" } } as never)
})
afterEach(() => {
  jest.useRealTimers()
})

async function uploadGif(props: Partial<React.ComponentProps<typeof ImageUploader>>) {
  const onUploadComplete = jest.fn()
  const { container } = render(
    <ImageUploader
      uploadUrl="/api/v1/products/p-1/image"
      onUploadComplete={onUploadComplete}
      label="Product Image"
      {...props}
    />
  )
  const input = container.querySelector('input[type="file"]') as HTMLInputElement
  const file = new File([new Uint8Array([71, 73, 70])], "dish.gif", { type: "image/gif" })
  await act(async () => {
    fireEvent.change(input, { target: { files: [file] } })
  })
  // Let the type gate, dimension read and the (mocked) POST settle.
  for (let i = 0; i < 5; i++) await act(async () => {})
  expect(screen.getByText("Processing…")).toBeInTheDocument()
  return { onUploadComplete }
}

async function tick() {
  await act(async () => {
    jest.advanceTimersByTime(ASSET_POLL_INTERVAL_MS)
  })
  for (let i = 0; i < 3; i++) await act(async () => {})
}

describe("ImageUploader leaves Processing once the asset settles (SW-ADE-02)", () => {
  it("polls the asset and, once ACTIVE, leaves Processing and hands over the image URL", async () => {
    const resolveAcceptedAsset = jest
      .fn()
      .mockResolvedValueOnce({ status: "PENDING", url: null, failureReason: null })
      .mockResolvedValueOnce({ status: "ACTIVE", url: "https://cdn.test/dish.webp", failureReason: null })
    const { onUploadComplete } = await uploadGif({ resolveAcceptedAsset })

    await tick()
    expect(screen.getByText("Processing…")).toBeInTheDocument()
    await tick()

    expect(resolveAcceptedAsset).toHaveBeenCalledWith("asset-1")
    expect(screen.queryByText("Processing…")).toBeNull()
    expect(onUploadComplete).toHaveBeenCalledWith("https://cdn.test/dish.webp")
  })

  it("leaves Processing on FAILED and shows the server's reason", async () => {
    const resolveAcceptedAsset = jest
      .fn()
      .mockResolvedValue({ status: "FAILED", url: null, failureReason: "That file is not a photo we can use." })
    const { onUploadComplete } = await uploadGif({ resolveAcceptedAsset })

    await tick()

    expect(screen.queryByText("Processing…")).toBeNull()
    expect(screen.getByText("That file is not a photo we can use.")).toBeInTheDocument()
    expect(onUploadComplete).not.toHaveBeenCalled()
  })

  it("stops asking after the cap and says the image is still processing", async () => {
    const resolveAcceptedAsset = jest
      .fn()
      .mockResolvedValue({ status: "PENDING", url: null, failureReason: null })
    await uploadGif({ resolveAcceptedAsset })

    for (let i = 0; i < ASSET_POLL_MAX_ATTEMPTS; i++) await tick()

    expect(screen.queryByText("Processing…")).toBeNull()
    expect(screen.getByText(/still processing/i)).toBeInTheDocument()
    const calls = resolveAcceptedAsset.mock.calls.length
    expect(calls).toBe(ASSET_POLL_MAX_ATTEMPTS)
    await tick()
    expect(resolveAcceptedAsset.mock.calls.length).toBe(calls)
  })

  it("review R1-4: ACTIVE with no URL still tells the parent (so it re-reads) and says the photo is ready", async () => {
    const resolveAcceptedAsset = jest
      .fn()
      .mockResolvedValue({ status: "ACTIVE", url: null, failureReason: null })
    const { onUploadComplete } = await uploadGif({ resolveAcceptedAsset })

    await tick()

    expect(screen.queryByText("Processing…")).toBeNull()
    expect(onUploadComplete).toHaveBeenCalledTimes(1)
    expect(onUploadComplete).toHaveBeenCalledWith(null)
    expect(screen.getByRole("status")).toHaveTextContent(/your photo is ready/i)
  })
})
