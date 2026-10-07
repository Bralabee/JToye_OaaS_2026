import Link from "next/link"
import { Store } from "lucide-react"

import { cn } from "@/lib/utils"
import type { PublicSeller } from "@/types/storefront"

/**
 * Who the customer is buying from (#789, 31.1-24; D-10/D-11/D-13/D-20).
 *
 * ONE component, rendered on the shop page (server-rendered, from the shop the page already
 * loaded), at checkout before Place order, and on the confirmation. Every surface therefore shows
 * the same fields in the same fixed order:
 *
 *   legal name (+ entity type), company number, VAT number, address, email, phone,
 *   the platform statement, the cancellation statement.
 *
 * WHY IT EXISTS. Every shop used to publish only a trading name and its premises: no legal entity,
 * no email, nothing saying J'Toye is not the seller, and nothing about the 14-day cancellation right
 * — which does not apply to freshly prepared food, and which CCR 2013 Sch 2(o) requires the
 * customer to be TOLD does not apply.
 *
 * THE COPY BELOW IS LEGALLY OPERATIVE (CCR 2013 Sch 2(b)-(c), (o) and reg 28(1)(c); E-Commerce
 * Regs 2002 reg 6(1)(c)). It is exported as named constants so the surfaces, the tests and the
 * order emails (31.1-25) quote ONE source; do not paraphrase it in passing. The cancellation
 * wording is RESEARCH's and is listed for adviser review before Phase 32 (Assumption A3).
 *
 * WHAT IT NEVER DOES. It never invents or blanks a field: a field the server did not send is
 * absent, with no label left behind. When the trader has provided no details at all it says so,
 * and points at the platform's own contact details — it does not fall back to the shop name as if
 * that were a legal name.
 *
 * It carries no "use client" and no hooks, so a server component can render it into the served
 * HTML (no client fetch, no layout shift) and a client page can render it too.
 */

/** The block's heading, and its accessible name. */
export const SELLER_BLOCK_HEADING_COPY = "Who you are buying from"

/** D-13: the platform is not the seller. Shown whenever a seller is named. */
export const PLATFORM_NOT_SELLER_COPY =
  "J'Toye is the ordering platform. Your contract for this order is with the seller named here."

/**
 * CCR 2013 Sch 2(o) with reg 28(1)(c): the customer must be told there is no right to cancel.
 * Wording from 31.1-RESEARCH ("Suggested statement"), unreviewed by an adviser (Assumption A3).
 */
export const CANCELLATION_STATEMENT_COPY =
  "Your order is freshly prepared food, which is liable to deteriorate rapidly, so the 14-day right to cancel under the Consumer Contracts Regulations 2013 (regulation 28(1)(c)) does not apply. This does not affect your rights if the food is faulty or not as described."

/** No trader identity on file (pre-gate data): say so, never invent one. */
export const SELLER_DETAILS_MISSING_COPY = "The seller has not provided their legal details yet."

/**
 * The platform statement when NO seller can be named. PLATFORM_NOT_SELLER_COPY would be false
 * here ("the seller named here" names nobody), so the platform states its role and its contact.
 */
export const PLATFORM_CONTACT_INTRO_COPY =
  "J'Toye is the ordering platform, not the seller. To contact J'Toye, see"

/** The link text for the platform's own legal and contact details (/legal). */
export const PLATFORM_CONTACT_LINK_COPY = "J'Toye's legal and contact details"

export const SELLER_ENTITY_TYPE_LABELS: Record<PublicSeller["entityType"], string> = {
  COMPANY: "Registered company",
  SOLE_TRADER: "Sole trader",
  PARTNERSHIP: "Partnership",
}

export interface SellerBlockProps {
  /** The shop's seller; null/undefined when the trader has provided no details. */
  seller: PublicSeller | null | undefined
  /** The heading's id (the region is labelled by it). Override only if two blocks share a page. */
  id?: string
  className?: string
}

/** `tel:` keeps digits and a leading +; the visible text stays exactly as the shop wrote it. */
function telHref(phone: string): string {
  return `tel:${phone.replace(/[^\d+]/g, "")}`
}

function Field({ term, children }: { term: string; children: React.ReactNode }) {
  return (
    <div className="grid gap-0.5 sm:grid-cols-[9rem_1fr] sm:gap-4">
      <dt className="text-slate-500">{term}</dt>
      <dd className="min-w-0 text-slate-900 [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}

export function SellerBlock({ seller, id = "seller-details-heading", className }: SellerBlockProps) {
  return (
    <section
      aria-labelledby={id}
      data-seller-block=""
      className={cn("rounded-xl border border-cream-100 bg-white p-4 shadow-sm", className)}
    >
      <h2 id={id} className="flex items-center gap-2 text-sm font-semibold text-slate-900">
        <Store aria-hidden="true" className="h-4 w-4 text-slate-500" />
        {SELLER_BLOCK_HEADING_COPY}
      </h2>

      {seller ? (
        <>
          <dl className="mt-3 space-y-2 text-sm">
            <Field term="Seller">
              <span className="font-medium">{seller.legalName}</span>
              <span className="block text-xs text-slate-600">
                {SELLER_ENTITY_TYPE_LABELS[seller.entityType]}
              </span>
            </Field>
            {seller.companyNumber && <Field term="Company number">{seller.companyNumber}</Field>}
            {seller.vatNumber && <Field term="VAT number">{seller.vatNumber}</Field>}
            {seller.addressLines.length > 0 && (
              <Field term="Address">{seller.addressLines.join(", ")}</Field>
            )}
            {seller.email && (
              <Field term="Email">
                <a
                  href={`mailto:${seller.email}`}
                  className="text-oxblood underline underline-offset-2 hover:text-oxblood-700"
                >
                  {seller.email}
                </a>
              </Field>
            )}
            {seller.phone && (
              <Field term="Phone">
                <a
                  href={telHref(seller.phone)}
                  className="text-oxblood underline underline-offset-2 hover:text-oxblood-700"
                >
                  {seller.phone}
                </a>
              </Field>
            )}
          </dl>
          <p className="mt-3 text-sm text-slate-600">{PLATFORM_NOT_SELLER_COPY}</p>
        </>
      ) : (
        <>
          <p className="mt-3 text-sm text-slate-900">{SELLER_DETAILS_MISSING_COPY}</p>
          <p className="mt-2 text-sm text-slate-600">
            {PLATFORM_CONTACT_INTRO_COPY}{" "}
            <Link
              href="/legal"
              className="text-oxblood underline underline-offset-2 hover:text-oxblood-700"
            >
              {PLATFORM_CONTACT_LINK_COPY}
            </Link>
            .
          </p>
        </>
      )}

      <p className="mt-2 text-sm text-slate-600">{CANCELLATION_STATEMENT_COPY}</p>
    </section>
  )
}
