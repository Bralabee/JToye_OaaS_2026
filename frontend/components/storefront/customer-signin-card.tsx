"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { ArrowLeft, LogIn, ShoppingBag, Store, UserPlus } from "lucide-react"
import {
  customerLogin,
  customerRegister,
  getCustomerSession,
  safeReturnTo,
} from "@/lib/customer-auth"

/**
 * The interactive half of /shop/signin. Split out of the page so the page itself
 * stays a server component and keeps its `metadata` export (SEO): a "use client"
 * page cannot export metadata at all.
 *
 * Both actions are buttons rather than links because they are redirects to Keycloak
 * built at click time (PKCE verifier, `state`, `nonce` are generated per attempt and
 * must not be baked into a static href).
 */
export function CustomerSignInCard() {
  const searchParams = useSearchParams()
  const router = useRouter()
  const [pending, setPending] = useState<"login" | "register" | null>(null)

  // `next` is attacker-controllable (it is in a URL anyone can send), so it is
  // narrowed to a same-origin relative path here as well as inside customerLogin.
  const returnTo = safeReturnTo(searchParams.get("next"))

  // Already signed in? Don't make a shopper authenticate twice — send them on.
  // Keyed off the SERVER-side session, not the localStorage marker: the marker is
  // explicitly UI-only and cannot be trusted to decide navigation.
  useEffect(() => {
    let cancelled = false
    getCustomerSession().then((session) => {
      if (!cancelled && session) router.replace(returnTo)
    })
    return () => {
      cancelled = true
    }
  }, [router, returnTo])

  return (
    <>
      <div className="w-full rounded-2xl border border-cream-100 bg-white p-6 shadow-xl sm:p-8">
        <div className="flex flex-col items-center gap-4 text-center">
          <span className="inline-flex h-12 w-12 items-center justify-center rounded-2xl bg-oxblood text-lg font-bold text-white">
            J
          </span>
          <div className="space-y-1.5">
            <h1 className="text-2xl font-bold tracking-tight text-oxblood">
              Sign in to order
            </h1>
            <p className="text-base text-slate-600">
              Place orders with independent kitchens, track deliveries and see
              everything you&apos;ve ordered before.
            </p>
          </div>
        </div>

        <div className="mt-6 space-y-3">
          <button
            type="button"
            disabled={pending !== null}
            onClick={() => {
              setPending("login")
              customerLogin(returnTo)
            }}
            className="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-full bg-oxblood px-6 text-base font-semibold text-white transition-colors hover:bg-oxblood-700 disabled:opacity-70"
          >
            <LogIn className="h-5 w-5" aria-hidden="true" />
            {pending === "login" ? "Taking you to sign in…" : "Sign in"}
          </button>

          <button
            type="button"
            disabled={pending !== null}
            onClick={() => {
              setPending("register")
              customerRegister(returnTo)
            }}
            className="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-full border border-oxblood/25 bg-white px-6 text-base font-semibold text-oxblood transition-colors hover:bg-cream disabled:opacity-70"
          >
            <UserPlus className="h-5 w-5" aria-hidden="true" />
            {pending === "register" ? "Taking you to sign up…" : "Create an account"}
          </button>

          <p className="text-center text-xs text-slate-500">
            Secure sign-in via Keycloak. We never see your password.
          </p>
        </div>

        {/* Escape hatches. This page is a landing destination for expired sessions
            and deep links, so it must never be a dead end. */}
        <div className="mt-6 flex items-center justify-center gap-6 border-t border-cream-100 pt-4 text-sm">
          <Link
            href="/shop"
            className="inline-flex items-center gap-1 text-slate-600 transition-colors hover:text-slate-900"
          >
            <ArrowLeft className="h-4 w-4" aria-hidden="true" />
            Browse kitchens
          </Link>
          <Link
            href="/track"
            className="inline-flex items-center gap-1 font-medium text-amber-600 transition-colors hover:text-amber-700"
          >
            <ShoppingBag className="h-4 w-4" aria-hidden="true" />
            Track an order
          </Link>
        </div>
      </div>

      {/* The persona cross-link. This is the whole point of the split: someone who
          lands on the wrong one of the two sign-in pages needs a visible, one-tap
          route to the other, because the two realms cannot authenticate each
          other's accounts and the failure is otherwise silent and unexplained. */}
      <p className="mt-6 text-center text-sm text-slate-600">
        Run a kitchen?{" "}
        <Link
          href="/auth/signin"
          className="inline-flex items-center gap-1 font-semibold text-oxblood underline-offset-4 hover:underline"
        >
          <Store className="h-4 w-4" aria-hidden="true" />
          Vendor sign in
        </Link>
      </p>
    </>
  )
}
