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
})
