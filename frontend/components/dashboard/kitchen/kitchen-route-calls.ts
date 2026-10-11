/**
 * Every API call the kitchen route makes, with the role floor the SERVER enforces on it (owner
 * ruling 2026-10-10, D-08 on the kitchen route, quick 261010-ppb).
 *
 * THE INVARIANT. The board-stop listener in app/dashboard/kitchen/page.tsx treats EVERY
 * shop-access-denied 403 on this route as "STAFF on this shop is gone" and stops the board with the
 * K2 "Your access has ended" overlay; the pause control says nothing of its own for one. That is
 * only true while every call here has a server floor of STAFF (or no shop floor at all). A call whose
 * floor is above STAFF would be refused for a STAFF user whose access has NOT ended, and the board
 * would tell them it had.
 *
 * So: before adding a call whose server floor is above STAFF to this route, exempt it from that
 * listener, then declare it here. The guard test
 * (components/dashboard/kitchen/__tests__/kitchen-route-calls.test.ts) fails until every `/api/v1/`
 * path in the route's sources is declared, and while any declared floor is above STAFF.
 *
 * `path` uses `{name}` placeholders; the guard compares paths with every placeholder, template
 * expression and query string normalised away.
 */

export interface KitchenRouteCall {
  method: "GET" | "POST" | "PUT" | "DELETE"
  path: string
  /** STAFF: `shopAccessService.require(shop, STAFF)`. NONE: no shop floor (narrowed or a self-read). */
  floor: "STAFF" | "NONE"
  /** Where the server decides it, as File.java:line. */
  server: string
}

export const KITCHEN_ROUTE_CALLS: readonly KitchenRouteCall[] = [
  // The board's read (lib/kitchen-orders-api.ts).
  { method: "GET", path: "/api/v1/orders/kitchen", floor: "STAFF", server: "OrderService.java:452" },
  // Order detail: the board's ticket and the New lane's card (page.tsx, kds-new-lane.tsx).
  { method: "GET", path: "/api/v1/orders/{id}/detail", floor: "STAFF", server: "OrderService.java:324" },
  // The bump: start-preparation, mark-ready, complete (page.tsx). Every transition delegates to one method.
  { method: "POST", path: "/api/v1/orders/{id}/{transition}", floor: "STAFF", server: "OrderService.java:560" },
  // Accept and Reject on the New lane (kds-new-lane.tsx): the same transition method.
  { method: "POST", path: "/api/v1/orders/{id}/confirm", floor: "STAFF", server: "OrderService.java:560" },
  { method: "POST", path: "/api/v1/orders/{id}/cancel", floor: "STAFF", server: "OrderService.java:560" },
  // The allergy acknowledgement (lib/allergy-note-api.ts).
  {
    method: "POST",
    path: "/api/v1/orders/{id}/allergy-note/acknowledgement",
    floor: "STAFF",
    server: "OrderService.java:648",
  },
  // The selected shop's read and the pause (lib/shops-api.ts).
  { method: "GET", path: "/api/v1/shops/{id}", floor: "STAFF", server: "ShopService.java:171" },
  { method: "PUT", path: "/api/v1/shops/{id}/pause", floor: "STAFF", server: "ShopService.java:459" },
  { method: "DELETE", path: "/api/v1/shops/{id}/pause", floor: "STAFF", server: "ShopService.java:485" },
  // The shop switcher's list: narrowed to the caller's grants, never a shop-access-denied 403 for a vendor.
  { method: "GET", path: "/api/v1/shops", floor: "NONE", server: "ShopService.java:179" },
  // The caller's own access: a self-read (lib/shops-api.ts).
  { method: "GET", path: "/api/v1/staff/me", floor: "NONE", server: "StaffController.java:67" },
]
