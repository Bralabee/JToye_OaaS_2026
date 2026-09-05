"use client"

import { useState, useSyncExternalStore } from "react"
import Link from "next/link"
import { useParams, usePathname } from "next/navigation"
import { m } from "framer-motion"
import { User, LogOut, Package, MapPin, Menu, X, ShoppingBag } from "lucide-react"
import { cn } from "@/lib/utils"
import { springPop } from "@/lib/motion"
import { useCartCount } from "@/hooks/use-cart-count"
import { useCustomerSession } from "@/hooks/use-customer-session"
import { customerIdpSignOut, customerLogout } from "@/lib/customer-auth"
import { hasRememberedSignIn } from "@/lib/cart-identity"

/**
 * FE-5 — subscribe to the "a sign-in happened here and nobody signed out since"
 * stamp so it can be read SYNCHRONOUSLY during render (no mount-time setState,
 * the same reason `lib/customer-session-store` is an external store). `storage`
 * fires only in OTHER documents of the origin; same-document changes arrive
 * through re-renders the session store already causes, and `getSnapshot` is
 * re-read on each of those. The server snapshot is a literal `false`: every
 * render here is a server render first, and the server cannot know this
 * browser's history — nor may it emit anything that looks as if it does.
 */
function subscribeToStorage(onChange: () => void): () => void {
  if (typeof window === "undefined") return () => {}
  window.addEventListener("storage", onChange)
  return () => window.removeEventListener("storage", onChange)
}
const noRememberedSignInOnServer = () => false
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet"

export function StorefrontNav() {
  // Session state comes from the shared hook, not a local copy — PublicHeader
  // reads the same one so the two headers can never disagree (#457).
  const { profile } = useCustomerSession()
  const [menuOpen, setMenuOpen] = useState(false)
  // R-04: a sign-out is now bounded at 3s per round-trip, so it can take a
  // visible moment on a bad connection. Without a busy state the shopper gets
  // no acknowledgement at all and taps again — which is how a sign-out that
  // silently did nothing went unreported for so long.
  // WR-06: NO `finally { setSigningOut(false) }`. `customerLogout()` resolves at
  // the end of its OWN finally — i.e. immediately after it assigns
  // `window.location.href`. Assigning `location.href` only SCHEDULES a
  // navigation; the document stays live and interactive until it commits, which
  // on the bad connection this feature targets is precisely the slow part. So
  // resetting there re-enabled the button for exactly the window the busy state
  // exists to cover, and the flag made a promise it did not keep.
  //
  // A sign-out button's correct terminal state is "busy until this document
  // goes away". If a reset is ever wanted for the navigation-was-blocked case,
  // it belongs on `pagehide`/`visibilitychange`, not on promise resolution.
  const [signingOut, setSigningOut] = useState(false)
  const handleSignOut = async () => {
    setSigningOut(true)
    await customerLogout()
  }
  // FE-5 (QA council 20260902-134741): "Not you? Sign out". Offered only when
  // the session is UNAUTHENTICATED and this browser remembers a sign-in nobody
  // explicitly signed out of — the honest proxy for "the previous person's
  // Keycloak SSO cookies are probably still alive on the IdP host", which
  // nothing on this origin can read directly. Without it, the next person's
  // "Create an account" dead-ended on Keycloak's "already authenticated as
  // different user" page (0 links, 0 buttons, 0 forms, A's email shown).
  // An EXPLICIT sign-out: `customerIdpSignOut` tears down the stamp and the
  // baskets (#459) — a lapse alone never reaches it, so R-16 holds.
  // Same busy contract as `handleSignOut` (WR-06): busy until the document
  // goes away, never reset on promise resolution.
  const rememberedSignIn = useSyncExternalStore(
    subscribeToStorage,
    hasRememberedSignIn,
    noRememberedSignInOnServer
  )
  const offerNotYou = !profile && rememberedSignIn
  const handleIdpSignOut = async () => {
    setSigningOut(true)
    await customerIdpSignOut("/shop/signin")
  }
  const pathname = usePathname()
  const params = useParams<{ slug?: string }>()
  const slug = params?.slug
  const cartCount = useCartCount(slug)
  const isActive = (href: string) =>
    pathname === href || pathname.startsWith(`${href}/`)

  const desktopLink = (active: boolean) =>
    cn(
      "transition-colors",
      active ? "text-oxblood font-semibold" : "text-slate-600 hover:text-oxblood"
    )

  // Same mobile sheet-link idiom as PublicHeader (44px touch target, active tint).
  const mobileLink = (active: boolean) =>
    cn(
      "flex min-h-11 items-center rounded-lg px-4 text-sm transition-colors",
      active
        ? "bg-cream text-oxblood font-semibold"
        : "text-slate-600 hover:bg-slate-50 hover:text-slate-900"
    )

  return (
    // `aria-label` is what makes this landmark DISTINGUISHABLE, not merely
    // named (31-02 / LGL-02). `landmark-unique` fires on ambiguity: /shop/[slug]
    // renders this header nav AND the sticky category strip, and two <nav>s that
    // are both called "Navigation" satisfy a naive fix while leaving a screen
    // reader's landmark list exactly as useless as it was. These three storefront
    // navs therefore carry three DIFFERENT names — see the strip in
    // app/shop/[slug]/shop-detail-client.tsx and the sheet below.
    <nav aria-label="Storefront" className="flex items-center gap-3 sm:gap-4 text-sm">
      {/* Desktop links (>=sm) — destination parity with the shared PublicHeader,
          now PERSONA-GATED (#458).

          The earlier contract was unconditional parity, "including 'For
          operators' so the operator door is reachable from /shop". That intent
          is kept, not discarded — it is just satisfied for the audience it was
          written for. Someone signed in as a CUSTOMER has already self-selected:
          the operator pitch is noise on their surface, and a standalone "Track
          order" (the GUEST lookup, which asks for an order number and an email)
          is redundant when the system already knows every order they have
          placed. Their route is My Orders -> the tracking view, auto-populated.

          Neither door is deleted:
           - /for-operators stays in this header for everyone NOT signed in as a
             customer, and in the PublicFooter on every surface (incl. /shop).
           - /track stays in this header for guests, in the PublicFooter for
             everyone, and behind every order card in My Orders.
          So both remain one visible link away at every breakpoint. */}
      <div className="hidden sm:flex items-center gap-4">
        {/* Label parity with PublicHeader — the same destination must not be
            called "Shops" on one surface and "Browse" on the next. */}
        <Link href="/shop" className={desktopLink(pathname === "/shop")}>
          Shops
        </Link>
        {!profile && (
          <>
            <Link
              href="/for-operators"
              className={desktopLink(isActive("/for-operators"))}
            >
              For operators
            </Link>
            <Link
              href="/track"
              className={cn("flex items-center gap-1", desktopLink(isActive("/track")))}
            >
              <MapPin className="h-3.5 w-3.5" />
              Track order
            </Link>
          </>
        )}
        {profile && (
          <Link
            href="/shop/orders"
            className="text-slate-600 hover:text-slate-900 transition-colors flex items-center gap-1"
          >
            <Package className="h-3.5 w-3.5" />
            My Orders
          </Link>
        )}
      </div>

      {/* Basket — only on /shop/[slug] routes where a cart exists (all
          viewports). Badge remounts on count change so the spring replays. */}
      {slug && (
        <Link
          href={`/shop/${slug}/cart`}
          aria-live="polite"
          onClick={(e) => {
            // Plain left-click opens the slide-over drawer; modified clicks
            // (new tab/window) and keyboard/AT/JS-off still hit the cart PAGE.
            if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
            e.preventDefault()
            window.dispatchEvent(new CustomEvent("jtoye:cart-open"))
          }}
          className="relative inline-flex h-9 w-9 items-center justify-center rounded-full text-slate-700 hover:bg-slate-100 active:scale-95 transition-all"
        >
          <ShoppingBag className="h-5 w-5" />
          {cartCount > 0 && (
            <m.span
              key={cartCount}
              initial={{ scale: 0.5 }}
              animate={{ scale: 1 }}
              transition={springPop}
              className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-oxblood px-1 text-xs font-bold leading-none text-white"
            >
              {cartCount}
            </m.span>
          )}
          {/* Renders to zero pixels, so no screenshot or visible-text assertion
              can catch a wrong plural here — this announced "1 items in basket"
              (#272). Same conditional idiom as cart-drawer.tsx and the cart page,
              which describe the SAME basket. */}
          <span className="sr-only">
            {cartCount} item{cartCount !== 1 ? "s" : ""} in basket
          </span>
        </Link>
      )}

      {profile ? (
        <div className="flex items-center gap-2">
          <div className="hidden sm:flex items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-1 text-xs text-emerald-700">
            <div className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
            <span className="truncate max-w-[100px]">{profile.name || profile.email}</span>
          </div>
          <div className="sm:hidden flex h-7 w-7 items-center justify-center rounded-full bg-emerald-100 text-emerald-700">
            <User className="h-3.5 w-3.5" />
          </div>
          <button
            onClick={handleSignOut}
            disabled={signingOut}
            aria-busy={signingOut}
            className="flex items-center gap-1 text-slate-400 hover:text-slate-600 transition-colors disabled:opacity-60"
            title="Sign out"
          >
            <LogOut className="h-3.5 w-3.5" />
          </button>
        </div>
      ) : (
        <div className="flex items-center gap-3">
          {/* FE-5: the explicit way out of a previous person's lingering SSO
              session. Desktop row only (>=sm) — the sheet below carries the
              mobile copy, the same split as every other secondary control
              here. Subordinate by design: plain text beside the Sign-in pill,
              never a competing button. No `title="Sign out"`: the e2e scripts
              locate the SIGNED-IN control by that title and must not find this. */}
          {offerNotYou && (
            <button
              type="button"
              onClick={handleIdpSignOut}
              disabled={signingOut}
              aria-busy={signingOut}
              className="hidden sm:inline-flex items-center gap-1 text-xs text-slate-600 hover:text-oxblood transition-colors disabled:opacity-60"
            >
              <LogOut className="h-3 w-3" aria-hidden="true" />
              Not you? Sign out
            </button>
          )}
          {/* A Link to the sign-in page, not a button firing customerLogin() at
              Keycloak. The bare redirect left a shopper with no landing destination:
              nothing to bookmark, nothing to come back to when a session expired, and
              no visible route to the vendor page if they had guessed wrong. `pathname`
              comes from usePathname() rather than window so it is stable during SSR. */}
          <Link
            href={`/shop/signin?next=${encodeURIComponent(pathname || "/shop")}`}
            className="inline-flex items-center gap-1.5 rounded-full bg-oxblood px-3 py-1.5 text-xs font-medium text-white hover:bg-oxblood-700 transition-colors"
          >
            <User className="h-3 w-3" />
            Sign in
          </Link>
        </div>
      )}

      {/* Mobile hamburger (<sm) — same idiom as PublicHeader. The sheet is a
          SEPARATE code path from the desktop row above, so the #458 gating has
          to be applied here too; a desktop-only fix leaves the link live on the
          viewport where it was actually reported. */}
      <Sheet open={menuOpen} onOpenChange={setMenuOpen}>
        <SheetTrigger asChild>
          <button
            type="button"
            aria-label="Open menu"
            className="sm:hidden inline-flex h-11 w-11 items-center justify-center rounded-lg text-slate-700 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300"
          >
            <Menu className="h-5 w-5" />
          </button>
        </SheetTrigger>
        <SheetContent side="right" hideCloseButton className="w-72 p-0">
          <div className="flex h-14 items-center justify-between border-b border-cream-100 px-4">
            <SheetTitle className="text-base font-semibold text-slate-900">
              Menu
            </SheetTitle>
            <SheetClose
              aria-label="Close menu"
              className="inline-flex h-11 w-11 items-center justify-center rounded-lg text-slate-700 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300"
            >
              <X className="h-5 w-5" />
            </SheetClose>
          </div>
          {/* Distinct from the header nav above: while the sheet is open BOTH
              are in the accessibility tree, which is precisely the `landmark-unique`
              condition. */}
          <nav aria-label="Storefront menu" className="flex flex-col p-2">
            <SheetClose asChild>
              <Link href="/shop" className={mobileLink(pathname === "/shop")}>
                Shops
              </Link>
            </SheetClose>
            {!profile && (
              <>
                <SheetClose asChild>
                  <Link
                    href="/for-operators"
                    className={mobileLink(isActive("/for-operators"))}
                  >
                    For operators
                  </Link>
                </SheetClose>
                <SheetClose asChild>
                  <Link href="/track" className={mobileLink(isActive("/track"))}>
                    Track order
                  </Link>
                </SheetClose>
                {/* FE-5 — the mobile copy of "Not you? Sign out" (the desktop
                    row's is `hidden` below sm). The sheet stays open on purpose:
                    the tap navigates the whole document away, and closing first
                    would only flash the page underneath. */}
                {offerNotYou && (
                  <button
                    type="button"
                    onClick={handleIdpSignOut}
                    disabled={signingOut}
                    aria-busy={signingOut}
                    className={cn(mobileLink(false), "w-full gap-2 text-left disabled:opacity-60")}
                  >
                    <LogOut className="h-4 w-4 text-slate-400" aria-hidden="true" />
                    Not you? Sign out
                  </button>
                )}
              </>
            )}
            {profile && (
              <SheetClose asChild>
                <Link
                  href="/shop/orders"
                  className={mobileLink(isActive("/shop/orders"))}
                >
                  My Orders
                </Link>
              </SheetClose>
            )}
          </nav>
        </SheetContent>
      </Sheet>
    </nav>
  )
}
