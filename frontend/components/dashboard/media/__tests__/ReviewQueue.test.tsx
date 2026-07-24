/**
 * ReviewQueue (Phase 24 IMG-04) — the vendor media review/rejection screen.
 *
 * These cases prove the queue lists the two attention kinds the backend
 * /api/v1/media/review-queue returns (24-05): FAILED uploads (a vendor-visible
 * failureReason + Re-upload) and flagged-ACTIVE images (Keep / Replace), and
 * that Keep is wired to the keep endpoint while Replace/Re-upload initiate the
 * re-upload flow (a dialog routing to the product, since the queue asset carries
 * no product linkage in the 24-05 DTO).
 */
import { render, screen, waitFor, fireEvent, act } from "@testing-library/react"
import { ReviewQueue } from "@/components/dashboard/media/ReviewQueue"
import { fetchReviewQueue, keepAsset } from "@/lib/media-api"
import type { MediaAsset } from "@/types/api"

jest.mock("@/lib/media-api", () => ({
  fetchReviewQueue: jest.fn(),
  keepAsset: jest.fn(),
}))
// A STABLE toast reference (the real useToast returns a module-level store, so
// `toast` is stable across renders). An unstable mock would make the component's
// `load`/`handleKeep` useCallbacks change every render and re-fire the fetch
// effect — masking real behaviour.
jest.mock("@/hooks/use-toast", () => {
  const toast = jest.fn()
  return { useToast: () => ({ toast }) }
})

const mockedFetch = fetchReviewQueue as jest.MockedFunction<typeof fetchReviewQueue>
const mockedKeep = keepAsset as jest.MockedFunction<typeof keepAsset>

const FAILED: MediaAsset = {
  assetId: "11111111-1111-1111-1111-111111111111",
  status: "FAILED",
  flagged: false,
  failureReason: "Unsupported image format",
  url: null,
  thumbnailUrl: null,
  width: null,
  height: null,
}

const FLAGGED: MediaAsset = {
  assetId: "22222222-2222-2222-2222-222222222222",
  status: "ACTIVE",
  flagged: true,
  failureReason: null,
  url: "http://localhost:9000/jtoye-images/t/media/a.webp",
  thumbnailUrl: "http://localhost:9000/jtoye-images/t/media/a_thumb.webp",
  width: 1600,
  height: 1200,
}

beforeEach(() => {
  jest.clearAllMocks()
})

describe("ReviewQueue", () => {
  it("lists a FAILED row with its reason + a Re-upload control, and a flagged row with Keep + Replace", async () => {
    mockedFetch.mockResolvedValue([FAILED, FLAGGED])
    render(<ReviewQueue />)

    // FAILED row: reason surfaced + Re-upload control present.
    expect(await screen.findByText(/unsupported image format/i)).toBeInTheDocument()
    expect(screen.getByRole("button", { name: /re-upload/i })).toBeInTheDocument()

    // Flagged row: both Keep and Replace controls present.
    expect(screen.getByRole("button", { name: /^keep$/i })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: /replace/i })).toBeInTheDocument()
  })

  it("Keep calls the keep endpoint with the assetId and drops the row from the queue", async () => {
    mockedFetch.mockResolvedValue([FLAGGED])
    mockedKeep.mockResolvedValue({ ...FLAGGED, flagged: false })
    render(<ReviewQueue />)

    const keepBtn = await screen.findByRole("button", { name: /^keep$/i })
    // Wrap in act so the async keep continuation (setAssets) commits before we assert.
    await act(async () => {
      fireEvent.click(keepBtn)
    })

    expect(mockedKeep).toHaveBeenCalledWith(FLAGGED.assetId)
    // The kept asset drops out of the queue → the empty state takes over.
    expect(await screen.findByText(/nothing needs review/i)).toBeInTheDocument()
  })

  it("Replace triggers the re-upload flow (a dialog routing to the product re-upload)", async () => {
    mockedFetch.mockResolvedValue([FLAGGED])
    render(<ReviewQueue />)

    const replaceBtn = await screen.findByRole("button", { name: /replace/i })
    fireEvent.click(replaceBtn)

    // The re-upload dialog opens with a route to the product (where the
    // uploader re-posts to /products/{id}/image on the async accept path).
    expect(await screen.findByText(/replace this image/i)).toBeInTheDocument()
    const link = screen.getByRole("link", { name: /go to products/i })
    expect(link).toHaveAttribute("href", "/dashboard/products")
  })

  it("Re-upload on a FAILED row also opens the re-upload flow", async () => {
    mockedFetch.mockResolvedValue([FAILED])
    render(<ReviewQueue />)

    const reupload = await screen.findByRole("button", { name: /re-upload/i })
    fireEvent.click(reupload)

    expect(await screen.findByRole("link", { name: /go to products/i })).toBeInTheDocument()
  })

  it("renders an empty state when nothing needs review", async () => {
    mockedFetch.mockResolvedValue([])
    render(<ReviewQueue />)
    expect(await screen.findByText(/nothing needs review/i)).toBeInTheDocument()
  })
})
