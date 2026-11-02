/**
 * How many kitchens one /shop page shows (server render and client "load more" alike).
 *
 * Its own plain module, NOT exported from `app/shop/shop-discovery-client.tsx`: that file is
 * `"use client"`, and a value imported from a client module into a Server Component is a client
 * reference, not the number. The server render of /shop then sent core
 * `size=function(){throw Error("Attempted to call SHOPS_PAGE_SIZE() from the server…")}`
 * (measured, quick 261102-vwa probe; review 1106 round 1), so core fell back to its own default
 * page size and the server's page 0 and the client's next page disagreed about where page 1 starts.
 */
export const SHOPS_PAGE_SIZE = 12
