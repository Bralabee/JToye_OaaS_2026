import { STRIPE_UK_PRICING_LINK_TEXT, STRIPE_UK_PRICING_URL } from "@/lib/pricing"

/**
 * The sentence that follows PRICING_CARD_FEE_LINE on /for-operators and
 * /legal/terms (SW-CLR-06, swarm epic 964 on GitHub): it points at Stripe's UK price list
 * instead of quoting a rate (owner ruling 2026-11-01).
 *
 * One component, so the wording, the new-tab attributes and the screen-reader
 * announcement cannot drift between the two pages; only the link styling
 * differs, and it is passed in. Not a client module, so the server-rendered
 * terms page and the client operator pitch can both render it.
 */
export function StripeUkPricingSentence({ linkClassName }: { linkClassName: string }) {
  return (
    <>
      Current rates are on{" "}
      <a
        href={STRIPE_UK_PRICING_URL}
        target="_blank"
        rel="noopener noreferrer"
        className={linkClassName}
      >
        {STRIPE_UK_PRICING_LINK_TEXT}
        <span className="sr-only"> (opens in a new tab)</span>
      </a>
      .
    </>
  )
}
