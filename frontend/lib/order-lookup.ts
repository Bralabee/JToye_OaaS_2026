import publicApiClient from "@/lib/public-api-client"

/**
 * SW-JRD-12 (swarm epic #964): the guest order lookup, with the email in the request BODY.
 *
 * The lookup used to be `GET /public/orders/{number}?email=…`, so every tracking page view and
 * every 15-second poll put the customer's address in a URL, where the browser's history, the
 * API's access logs and any proxy in between keep it. Core's `POST /public/orders/{number}/lookup`
 * takes the same proof of ownership in a JSON body and answers the same order status, uncached.
 * A read: it changes nothing, so a retry or a poll needs no Idempotency-Key.
 *
 * Every guest lookup in the frontend goes through here, so the address cannot drift back into a URL.
 */
export function guestOrderLookupPath(orderNumber: string): string {
  return `/public/orders/${encodeURIComponent(orderNumber)}/lookup`
}

export function lookupGuestOrder<T>(orderNumber: string, email: string) {
  return publicApiClient.post<T>(guestOrderLookupPath(orderNumber), { email })
}
