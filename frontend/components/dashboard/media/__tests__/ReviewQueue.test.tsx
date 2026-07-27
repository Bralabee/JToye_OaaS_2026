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
import { fetchReviewQueue, keepAsset, reprocessAsset } from "@/lib/media-api"
import { useToast } from "@/hooks/use-toast"
import type { MediaAsset } from "@/types/api"

jest.mock("@/lib/media-api", () => ({
  fetchReviewQueue: jest.fn(),
  keepAsset: jest.fn(),
  reprocessAsset: jest.fn(),
}))
// A STABLE toast reference (the real useToast returns a module-level store, so
// `toast` is stable across renders). An unstable mock would make the component's
// `load`/`handleKeep` useCallbacks change every render and re-fire the fetch
// effect — masking real behaviour.
jest.mock("@/hooks/use-toast", () => {
  const toast = jest.fn()
  return { useToast: () => ({ toast }) }
})

/** The stable module-level `toast` spy the mock hands every render. */
const toastSpy = () => (useToast() as unknown as { toast: jest.Mock }).toast

const mockedFetch = fetchReviewQueue as jest.MockedFunction<typeof fetchReviewQueue>
const mockedKeep = keepAsset as jest.MockedFunction<typeof keepAsset>
const mockedReprocess = reprocessAsset as jest.MockedFunction<typeof reprocessAsset>

const FAILED: MediaAsset = {
  assetId: "11111111-1111-1111-1111-111111111111",
  status: "FAILED",
  flagged: false,
  failureReason: "Unsupported image format",
  url: null,
  thumbnailUrl: null,
  width: null,
  height: null,
  // The worker discarded these bytes on a validation veto — nothing to re-process.
  redrivable: false,
  delayed: false,
}

/** 27-01: a dispatch-stall failure — the vendor's original bytes ARE still retained. */
const FAILED_REDRIVABLE: MediaAsset = {
  ...FAILED,
  assetId: "33333333-3333-3333-3333-333333333333",
  failureReason: "Processing stalled",
  redrivable: true,
}

/** 27-01 / D-10: a stalled PENDING upload, now carried by the queue. */
const DELAYED_PENDING: MediaAsset = {
  assetId: "44444444-4444-4444-4444-444444444444",
  status: "PENDING",
  flagged: false,
  failureReason: null,
  url: null,
  thumbnailUrl: null,
  width: null,
  height: null,
  redrivable: true,
  delayed: true,
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
  redrivable: false,
  delayed: false,
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

  // --- 27-01 / D-10: the stalled-upload section and Re-process -----------------

  it("carries a stalled PENDING upload as its own explained section with a re-check", async () => {
    mockedFetch.mockResolvedValue([DELAYED_PENDING])
    render(<ReviewQueue />)

    // Before D-10 this row appeared in NO queue — a stalled upload was a spinner
    // on the one product page it came from, so the empty state would have shown.
    expect(await screen.findByRole("heading", { name: /taking longer than usual/i })).toBeInTheDocument()
    expect(screen.getByText(/your upload is safe and still queued/i)).toBeInTheDocument()
    expect(screen.queryByText(/nothing needs review/i)).toBeNull()

    // Check again re-fetches rather than leaving the vendor to reload the page.
    expect(mockedFetch).toHaveBeenCalledTimes(1)
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /check again/i }))
    })
    await waitFor(() => expect(mockedFetch).toHaveBeenCalledTimes(2))
  })

  it("offers Re-process only on a redrivable FAILED row, and drops the row on success", async () => {
    mockedFetch.mockResolvedValue([FAILED, FAILED_REDRIVABLE])
    mockedReprocess.mockResolvedValue({ assetId: FAILED_REDRIVABLE.assetId, status: "PENDING" })
    render(<ReviewQueue />)

    await screen.findByText(/unsupported image format/i)
    // Two FAILED rows, exactly ONE Re-process — the non-redrivable row must not
    // offer a control that could only ever 409.
    expect(screen.getAllByRole("button", { name: /re-upload/i })).toHaveLength(2)
    const reprocessButtons = screen.getAllByRole("button", { name: /re-process/i })
    expect(reprocessButtons).toHaveLength(1)

    await act(async () => {
      fireEvent.click(reprocessButtons[0])
    })

    expect(mockedReprocess).toHaveBeenCalledWith(FAILED_REDRIVABLE.assetId)
    // Optimistic removal: the asset is back in PENDING, so it is no longer a
    // rejection the vendor needs to act on. The other FAILED row stays.
    await waitFor(() => expect(screen.queryByText(/processing stalled/i)).toBeNull())
    expect(screen.getByText(/unsupported image format/i)).toBeInTheDocument()
  })

  it("surfaces the RFC 7807 code on a 409 and keeps the row", async () => {
    mockedFetch.mockResolvedValue([FAILED_REDRIVABLE])
    mockedReprocess.mockRejectedValue({
      response: { status: 409, data: { code: "media.quarantine_not_retained", detail: "no longer retained" } },
    })
    render(<ReviewQueue />)

    // Resolve the control BEFORE entering act — a findBy inside act races the
    // initial load and reports "unable to find" against the loading skeleton.
    const reprocess = await screen.findByRole("button", { name: /re-process/i })
    await act(async () => {
      fireEvent.click(reprocess)
    })

    // The three 409 codes have three different remedies. A generic "please try
    // again" collapses them into one dead end, so the code must reach the vendor.
    expect(toastSpy()).toHaveBeenCalledWith(
      expect.objectContaining({
        variant: "destructive",
        description: expect.stringContaining("media.quarantine_not_retained"),
      })
    )
    // The row stays — the failure is terminal for these bytes, and the row is
    // where the Re-upload remedy is offered.
    expect(screen.getByText(/processing stalled/i)).toBeInTheDocument()
  })
})
