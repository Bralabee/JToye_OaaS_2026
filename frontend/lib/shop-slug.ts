/**
 * The shape of a shop slug the server stores: `CreateShopRequest.slug`'s @Pattern (non-blank arm),
 * lowercase letters and digits in hyphen-separated runs. Lowercase only, deliberately: the server
 * never stores an uppercase slug, so a path that carries one is not a shop page.
 *
 * `app/api/customer-orders/route.ts` keeps its own, looser, length-bounded check: it validates a
 * query parameter before a proxy call, and tightening it would change which requests that route
 * refuses.
 */
export const SHOP_SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
