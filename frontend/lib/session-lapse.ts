/**
 * 37-18 (D-14, UXT-021): the one signal a lapsed dashboard session sends before the browser
 * leaves for the sign-in page.
 *
 * WHY AN EVENT. The api-client used to answer a 401 that its one silent refresh could not fix by
 * setting `window.location.href = "/auth/signin"` on every route. On the kitchen board that turns a
 * wall-mounted screen into a sign-in page with no sound and nothing a cook across the kitchen would
 * notice, so a Friday rush's orders go unseen. The client now dispatches
 * {@link SESSION_LAPSED_EVENT} on `window`, CANCELLABLE, and navigates only if no listener claimed
 * it (`preventDefault()`). Every route that does not listen behaves exactly as before; the kitchen
 * claims it and shows the board-stopped overlay instead (K2).
 *
 * Its own module (not api-client.ts) for the reason lib/access-events.ts gives: a test that
 * auto-mocks the api-client still sees the real event name.
 */
export const SESSION_LAPSED_EVENT = "jtoye:session-lapsed"

/** Where an unclaimed lapse goes: the operator sign-in page, as it always has. */
export const SIGN_IN_PATH = "/auth/signin"

/**
 * Announce a lapse. Returns true when a listener claimed it, i.e. the caller must NOT redirect.
 * `dispatchEvent` returns false exactly when a listener called `preventDefault()` on a cancellable
 * event.
 */
export function announceSessionLapse(): boolean {
  if (typeof window === "undefined") return false
  const event = new CustomEvent(SESSION_LAPSED_EVENT, { cancelable: true })
  window.dispatchEvent(event)
  return event.defaultPrevented
}

/** Today's behaviour for an unclaimed lapse. A seam so a test can observe it without jsdom navigation. */
export function navigateToSignIn(): void {
  if (typeof window !== "undefined") window.location.href = SIGN_IN_PATH
}
