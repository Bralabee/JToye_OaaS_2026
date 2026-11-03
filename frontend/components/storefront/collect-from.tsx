import { MapPin, Phone } from "lucide-react"

/**
 * SW-JRD-09 (swarm epic #964, reconfirms UXT-056 / UXT-060): a Collection checkout says WHERE to
 * collect. Before this the Collection branch showed only the delivery wording ("Delivery: Free")
 * and nothing about the pickup: no address and no phone.
 *
 * Only what the shop has published is shown. A missing address or phone is left out rather than
 * filled in, and no ready time is promised: the platform holds no preparation time for a shop. The
 * order page's tracker is what says when the order is ready, so the copy points there.
 */
export const COLLECT_FROM_COPY = {
  heading: "Collect from",
  ready: "Your order page shows when it is ready to collect.",
  call: "Call the shop",
}

export function CollectFrom({
  shopName,
  address,
  phone,
}: {
  shopName: string
  address: string | null | undefined
  phone: string | null | undefined
}) {
  const addr = address?.trim()
  const tel = phone?.trim()
  return (
    <section
      aria-labelledby="collect-from-heading"
      data-testid="collect-from"
      className="rounded-xl bg-white border border-cream-100 p-4 shadow-sm space-y-2"
    >
      <h2 id="collect-from-heading" className="text-sm font-semibold text-slate-900">
        {COLLECT_FROM_COPY.heading}
      </h2>
      <p className="text-sm font-medium text-slate-900 [overflow-wrap:anywhere]">{shopName}</p>
      {addr ? (
        <p className="flex items-start gap-2 text-sm text-slate-700 [overflow-wrap:anywhere]">
          <MapPin className="mt-0.5 h-4 w-4 flex-shrink-0 text-oxblood" aria-hidden="true" />
          <span>{addr}</span>
        </p>
      ) : null}
      {tel ? (
        <p className="flex items-center gap-2 text-sm">
          <Phone className="h-4 w-4 flex-shrink-0 text-oxblood" aria-hidden="true" />
          <a
            href={`tel:${tel.replace(/[^\d+]/g, "")}`}
            className="inline-flex min-h-11 items-center font-medium text-slate-900 underline underline-offset-4 [overflow-wrap:anywhere]"
          >
            <span className="sr-only">{COLLECT_FROM_COPY.call}: </span>
            {tel}
          </a>
        </p>
      ) : null}
      <p className="text-xs text-slate-600">{COLLECT_FROM_COPY.ready}</p>
    </section>
  )
}
