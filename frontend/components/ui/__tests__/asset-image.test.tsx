/**
 * AssetImage (Phase 24 IMG-04) — the status-aware wrapper over SafeImage.
 *
 * These cases are the IMG-04 acceptance proof for the four render states a media
 * asset moves through in the safe async pipeline: PENDING (processing), ACTIVE
 * (WebP derivative with alt + explicit dimensions for CLS — D-07), FAILED
 * (vendor-visible reason + Re-upload), and ACTIVE&flagged (Needs-review badge).
 */
import { render, screen, fireEvent } from "@testing-library/react"
import { AssetImage } from "@/components/ui/asset-image"

const WEBP = "http://localhost:9000/jtoye-images/tenant-a/media/asset-1.webp"

describe("AssetImage", () => {
  it("PENDING renders a processing indicator (no servable <img> yet)", () => {
    render(<AssetImage status="PENDING" url={null} alt="Jollof rice" />)
    expect(screen.getByText(/processing/i)).toBeInTheDocument()
    expect(screen.queryByRole("img")).not.toBeInTheDocument()
  })

  it("ACTIVE renders an <img> preserving alt + explicit width/height (CLS — D-07)", () => {
    render(
      <AssetImage
        status="ACTIVE"
        url={WEBP}
        alt="Jollof rice"
        width={1600}
        height={1200}
      />
    )
    const img = screen.getByAltText("Jollof rice") as HTMLImageElement
    expect(img.tagName).toBe("IMG")
    expect(img).toHaveAttribute("src", WEBP)
    expect(img).toHaveAttribute("alt", "Jollof rice")
    expect(img).toHaveAttribute("width", "1600")
    expect(img).toHaveAttribute("height", "1200")
  })

  it("ACTIVE with useThumbnail renders the thumbnail derivative", () => {
    const thumb = "http://localhost:9000/jtoye-images/tenant-a/media/asset-1_thumb.webp"
    render(
      <AssetImage
        status="ACTIVE"
        url={WEBP}
        thumbnailUrl={thumb}
        alt="Jollof rice"
        useThumbnail
      />
    )
    expect(screen.getByAltText("Jollof rice")).toHaveAttribute("src", thumb)
  })

  it("FAILED renders the vendor failureReason + a working Re-upload control", () => {
    const onReupload = jest.fn()
    render(
      <AssetImage
        status="FAILED"
        url={null}
        alt="Jollof rice"
        failureReason="Unsupported image format"
        onReupload={onReupload}
      />
    )
    expect(screen.getByText(/unsupported image format/i)).toBeInTheDocument()
    const btn = screen.getByRole("button", { name: /re-upload/i })
    fireEvent.click(btn)
    expect(onReupload).toHaveBeenCalledTimes(1)
  })

  it("FAILED still shows a Re-upload control when no reason is supplied", () => {
    render(<AssetImage status="FAILED" url={null} alt="Jollof rice" />)
    expect(screen.getByRole("button", { name: /re-upload/i })).toBeInTheDocument()
    expect(screen.getByText(/image processing failed/i)).toBeInTheDocument()
  })

  it("ACTIVE & flagged shows a Needs-review badge over the rendered derivative", () => {
    render(
      <AssetImage
        status="ACTIVE"
        url={WEBP}
        alt="Jollof rice"
        width={400}
        height={400}
        flagged
      />
    )
    expect(screen.getByAltText("Jollof rice")).toBeInTheDocument()
    expect(screen.getByText(/needs review/i)).toBeInTheDocument()
  })

  // --- 27-01 / D-10: the DELAYED state (M4) ------------------------------------
  //
  // Both directions are mandatory. A single "the delayed card renders" test passes
  // just as happily on a card rendered unconditionally for every PENDING asset,
  // and is therefore incapable of detecting the defect it claims to guard — it
  // would have destroyed the fresh-upload spinner without going red.

  it("shows the delayed card for a stalled PENDING asset", () => {
    const onCheckAgain = jest.fn()
    render(
      <AssetImage status="PENDING" url={null} alt="Jollof rice" delayed onCheckAgain={onCheckAgain} />
    )
    expect(screen.getByText(/taking longer than usual/i)).toBeInTheDocument()
    expect(screen.getByText(/your upload is safe and still queued/i)).toBeInTheDocument()

    const check = screen.getByRole("button", { name: /check again/i })
    fireEvent.click(check)
    expect(onCheckAgain).toHaveBeenCalledTimes(1)
  })

  it("shows the plain spinner for a fresh PENDING asset, not the delayed card", () => {
    render(<AssetImage status="PENDING" url={null} alt="Jollof rice" delayed={false} />)
    expect(screen.getByText(/processing/i)).toBeInTheDocument()
    // The working good: an upload inside the grace still shows the ordinary
    // spinner. This is the assertion that goes red if the delayed card is made
    // unconditional.
    expect(screen.queryByText(/taking longer than usual/i)).toBeNull()
    expect(screen.queryByRole("button", { name: /check again/i })).toBeNull()
  })

  // --- 27-01: Re-process appears exactly when the bytes are retained -----------

  it("shows Re-process on a FAILED asset when redrivable", () => {
    const onReprocess = jest.fn()
    render(
      <AssetImage
        status="FAILED"
        url={null}
        alt="Jollof rice"
        failureReason="Dispatch stalled"
        redrivable
        onReprocess={onReprocess}
      />
    )
    const btn = screen.getByRole("button", { name: /re-process/i })
    fireEvent.click(btn)
    expect(onReprocess).toHaveBeenCalledTimes(1)
    expect(screen.getByText(/your original upload is still saved/i)).toBeInTheDocument()
  })

  it("hides Re-process on a FAILED asset when the bytes are gone", () => {
    render(
      <AssetImage
        status="FAILED"
        url={null}
        alt="Jollof rice"
        failureReason="Not a valid image"
        redrivable={false}
      />
    )
    // Offering Re-process here would be a button that can only ever 409 — the
    // vendor's real remedy is a re-upload.
    expect(screen.queryByRole("button", { name: /re-process/i })).toBeNull()
    expect(screen.queryByText(/your original upload is still saved/i)).toBeNull()
  })

  it("retains the existing Re-upload control alongside Re-process", () => {
    const onReupload = jest.fn()
    render(
      <AssetImage
        status="FAILED"
        url={null}
        alt="Jollof rice"
        failureReason="Dispatch stalled"
        redrivable
        onReupload={onReupload}
        onReprocess={jest.fn()}
      />
    )
    // Incremental Betterment receipt: Re-process is ADDITIVE. Re-upload must
    // still be present AND still work — replacing it would be a regression by
    // omission that a "Re-process renders" test alone would never catch.
    const reupload = screen.getByRole("button", { name: /re-upload/i })
    expect(reupload).toBeInTheDocument()
    expect(screen.getByRole("button", { name: /re-process/i })).toBeInTheDocument()

    fireEvent.click(reupload)
    expect(onReupload).toHaveBeenCalledTimes(1)
  })
})
