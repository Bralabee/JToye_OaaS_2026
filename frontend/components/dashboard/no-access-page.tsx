"use client"

import { useState } from "react"
import { ShieldOff } from "lucide-react"
import { Button } from "@/components/ui/button"
// R-01 (P0): the vendor sign-out ends the Keycloak SSO session too, not just the app
// cookie — the same call the sidebar and the mobile "More" sheet make.
import { vendorLogout } from "@/lib/vendor-logout"

/**
 * D-08 (37-06, UI-SPEC § B3) — what a signed-in person with no access sees, inside
 * the dashboard shell (so the sidebar's user block and Sign out stay reachable), in
 * place of a dashboard full of empty "No orders yet" states (the root of UXT-098).
 *
 * The heading is chosen by data (T-37-13). "You no longer have access" is printed
 * ONLY when `observedAccess` is true, which the shell sets when this browser session
 * itself saw the person hold access. With nothing observed it says "You don't have
 * access yet", because claiming access was taken away needs evidence it existed.
 *
 * "Check access again" re-reads staff/me. While that is in flight the button is
 * disabled and reads "Checking…"; if the read fails, an inline alert says so and the
 * page stays (UI-SPEC B3 loading/error, resolved as planner assumptions in 37-06).
 */

export const NO_ACCESS_COPY = {
  headingNoLonger: "You no longer have access",
  headingYet: "You don't have access yet",
  checkAgain: "Check access again",
  checking: "Checking…",
  signOut: "Sign out",
  checkFailed: "Couldn't check your access. Check your connection and try again.",
} as const

/**
 * The body copy. The business name is not known to the browser for a person with
 * no access (every tenant read is refused), so it falls back to "your business".
 */
function bodyCopy(businessName: string | null | undefined): string {
  const business = businessName?.trim() || "your business"
  return (
    `Your account is signed in, but no one at ${business} has given you access to a ` +
    "shop. Ask a Group admin to invite you or grant you access."
  )
}

interface NoAccessPageProps {
  /** This session saw the person hold access earlier (in memory, never stored). */
  observedAccess: boolean
  /** The staff/me re-read is in flight. */
  checking: boolean
  /** The last re-read failed (network or 5xx), as opposed to answering "none". */
  checkFailed: boolean
  onCheckAgain: () => void
  businessName?: string | null
}

export function NoAccessPage({
  observedAccess,
  checking,
  checkFailed,
  onCheckAgain,
  businessName,
}: NoAccessPageProps) {
  // Busy until the page goes away, never reset (the sidebar's CR-02 reasoning):
  // `vendorLogout` only schedules the navigation, and a second tap could override it.
  const [signingOut, setSigningOut] = useState(false)
  const handleSignOut = () => {
    setSigningOut(true)
    void vendorLogout()
  }

  return (
    <section
      data-testid="no-access-page"
      aria-labelledby="no-access-heading"
      className="mx-auto flex max-w-md flex-col items-center py-16 text-center"
    >
      <ShieldOff className="h-12 w-12 text-slate-400" aria-hidden="true" />
      <h1
        id="no-access-heading"
        className="mt-6 text-2xl font-semibold leading-tight text-slate-900"
      >
        {observedAccess ? NO_ACCESS_COPY.headingNoLonger : NO_ACCESS_COPY.headingYet}
      </h1>
      <p className="mt-4 text-base leading-normal text-slate-600 [overflow-wrap:anywhere]">
        {bodyCopy(businessName)}
      </p>
      {checkFailed && (
        <p role="alert" className="mt-4 w-full rounded-lg bg-red-50 p-4 text-sm text-red-700">
          {NO_ACCESS_COPY.checkFailed}
        </p>
      )}
      <div className="mt-8 flex w-full flex-col gap-2 sm:w-auto sm:flex-row">
        <Button className="h-11" onClick={onCheckAgain} disabled={checking} aria-busy={checking}>
          {checking ? NO_ACCESS_COPY.checking : NO_ACCESS_COPY.checkAgain}
        </Button>
        <Button
          variant="outline"
          className="h-11"
          onClick={handleSignOut}
          disabled={signingOut}
          aria-busy={signingOut}
        >
          {NO_ACCESS_COPY.signOut}
        </Button>
      </div>
    </section>
  )
}
