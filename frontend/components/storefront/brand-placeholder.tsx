import { cn } from "@/lib/utils"

/**
 * BrandPlaceholder — imagery fallback (DESIGN-SPEC §8.4).
 *
 * When photography is unavailable, show a muted warm surface with the
 * centred brand mark at low opacity. Maintains brand presence in empty
 * states without reaching for a gradient.
 *
 * Defaults to the 4:3 product/category card ratio; pass `aspect` to
 * override (e.g. `aspect-square` for product cards, `aspect-[21/9]` for
 * hero banners).
 */
export function BrandPlaceholder({
  aspect = "aspect-[4/3]",
  className,
}: {
  aspect?: string
  className?: string
}) {
  return (
    <div
      className={cn(
        aspect,
        "flex items-center justify-center bg-surface-muted overflow-hidden",
        className,
      )}
      aria-hidden="true"
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src="/brand/mark.svg"
        alt=""
        className="h-12 w-12 opacity-30"
        loading="lazy"
      />
    </div>
  )
}
