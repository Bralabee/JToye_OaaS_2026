/**
 * Client-only persistence + observability for the dashboard shop-context switcher.
 *
 * The selected context is stored in `localStorage['shopContext']` — a per-device
 * UI preference (D-07), NOT a trust boundary: the server re-validates every grant
 * on every request, so a tampered value only ever yields a typed 403 rendered as
 * an access-required state (D-13). Mirrors the theme-toggle localStorage idiom in
 * `sidebar.tsx`.
 *
 * The browser `storage` event only fires in OTHER tabs, so `setShopContext` also
 * dispatches a same-tab `shopcontext:change` event; consumers subscribe to BOTH
 * via `subscribeShopContext`. 23-07's `useShopContext` hook uses this to narrow
 * the products/orders/marketing/kitchen screens live when the switcher changes.
 */

/** localStorage key holding the selected shopContext ("all" or a shopId). */
const SHOP_CONTEXT_KEY = "shopContext"
/** Same-tab CustomEvent name broadcast on every shopContext write. */
const SHOP_CONTEXT_CHANGE_EVENT = "shopcontext:change"

/** The All-shops context sentinel (GROUP_ADMIN default, D-06). */
export const ALL_SHOPS_CONTEXT = "all"

/**
 * Read the persisted shopContext. Returns the "all" context when nothing is
 * saved or during SSR (no `window`).
 */
export function getShopContext(): string {
  if (typeof window === "undefined") return ALL_SHOPS_CONTEXT
  return window.localStorage.getItem(SHOP_CONTEXT_KEY) ?? ALL_SHOPS_CONTEXT
}

/**
 * Persist the selected shopContext and broadcast a same-tab change event so
 * in-tab consumers (23-07) react immediately — the native `storage` event would
 * otherwise only reach OTHER tabs.
 */
export function setShopContext(id: string): void {
  if (typeof window === "undefined") return
  window.localStorage.setItem(SHOP_CONTEXT_KEY, id)
  window.dispatchEvent(new Event(SHOP_CONTEXT_CHANGE_EVENT))
}

/**
 * Subscribe to shopContext changes from BOTH the same-tab `shopcontext:change`
 * event and the cross-tab `storage` event. Returns an unsubscribe function.
 */
export function subscribeShopContext(cb: () => void): () => void {
  if (typeof window === "undefined") return () => {}
  window.addEventListener(SHOP_CONTEXT_CHANGE_EVENT, cb)
  window.addEventListener("storage", cb)
  return () => {
    window.removeEventListener(SHOP_CONTEXT_CHANGE_EVENT, cb)
    window.removeEventListener("storage", cb)
  }
}
