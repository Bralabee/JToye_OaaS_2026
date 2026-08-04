"use client"

import type { OrderDetail } from "@/types/api"

/**
 * A printable kitchen ticket (#105).
 *
 * Before this there was no print flow anywhere in the product: zero `window.print`
 * and zero `@media print` outside a marketing document. A vendor with a rail and a
 * receipt printer had nothing to put on it.
 *
 * WHAT A KITCHEN TICKET IS, AND WHAT IT IS NOT. It is a prep instruction that gets
 * torn off and clipped to a rail, read at arm's length in a hot room. So the order
 * number is the biggest thing on it, quantities lead each line, and the whole thing
 * is monochrome and unstyled by the brand — printers are black-on-white and toner is
 * not a design surface. It carries no prices, no payment status and no refund
 * history: the money questions are answered on /dashboard/orders, and every glyph
 * that is not needed at the pass is one more to read past.
 *
 * WIDTH. The column is 72mm, which is the printable width of a standard 80mm thermal
 * roll, and which also prints sanely on A4 (a narrow column, wasted margin, entirely
 * legible). A dedicated thermal driver story can follow — #105 says as much — without
 * this needing to change.
 */

const dtf = new Intl.DateTimeFormat("en-GB", {
  hour12: false,
  hour: "2-digit",
  minute: "2-digit",
  day: "2-digit",
  month: "short",
})

function stamp(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? "—" : dtf.format(d)
}

export function KitchenTicket({
  order,
  shopName,
  printedAt,
}: {
  order: OrderDetail
  shopName: string | null
  /** Passed in rather than read from the clock so the markup is deterministic. */
  printedAt: number
}) {
  const ref = order.orderNumber || `#${order.id.substring(0, 8)}`
  const isDelivery = order.fulfilmentType === "DELIVERY"
  const addressLines = [
    order.addressLine1,
    order.addressLine2,
    order.addressCity,
    order.addressPostcode,
  ].filter((l): l is string => !!l && l.trim().length > 0)

  return (
    <article
      data-testid="kitchen-ticket"
      data-order-id={order.id}
      className="kds-ticket"
    >
      <header className="kds-ticket__head">
        <p className="kds-ticket__shop">{shopName || "Kitchen"}</p>
        <p className="kds-ticket__ref">{ref}</p>
        <p className="kds-ticket__fulfilment">
          {isDelivery ? "DELIVERY" : "COLLECTION"}
        </p>
      </header>

      <dl className="kds-ticket__meta">
        <div>
          <dt>Customer</dt>
          <dd>{order.customerName || "Walk-in"}</dd>
        </div>
        <div>
          <dt>Ordered</dt>
          <dd>{stamp(order.createdAt)}</dd>
        </div>
        <div>
          <dt>Status</dt>
          <dd>{order.status}</dd>
        </div>
      </dl>

      <ul className="kds-ticket__items">
        {order.items && order.items.length > 0 ? (
          order.items.map((item, i) => (
            <li key={item.id || i}>
              <span className="kds-ticket__qty">{item.quantity}&times;</span>
              <span className="kds-ticket__name">{item.productName}</span>
            </li>
          ))
        ) : (
          <li>
            <span className="kds-ticket__name">No items on this order</span>
          </li>
        )}
      </ul>

      {order.notes ? (
        <p className="kds-ticket__notes">
          <span>NOTES</span> {order.notes}
        </p>
      ) : null}

      {isDelivery && addressLines.length > 0 ? (
        <div className="kds-ticket__address">
          <span>DELIVER TO</span>
          {addressLines.map((line) => (
            <p key={line}>{line}</p>
          ))}
        </div>
      ) : null}

      <footer className="kds-ticket__foot">
        Printed {stamp(new Date(printedAt).toISOString())}
      </footer>
    </article>
  )
}
