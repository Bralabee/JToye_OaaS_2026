import Link from "next/link"
import { ArrowRight, Banknote, LayoutDashboard, LifeBuoy, Link2Off, PlaneLanding } from "lucide-react"
import type { LucideIcon } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { resolveSupportChannel } from "@/lib/env-validation"

/**
 * The two Stripe Connect redirect destinations (#295).
 *
 * `stripe.connect.return-url` / `refresh-url` (core-java `application.yml`,
 * consumed by `StripeConnectService.createOrResumeExpressOnboarding` via
 * `AccountLinkCreateParams.setReturnUrl` / `setRefreshUrl`) point Stripe at
 * `/dashboard/payments/connect/return` and `/dashboard/payments/connect/refresh`.
 * Neither route existed, so a vendor finishing — or abandoning — Express
 * onboarding landed on a 404. These are LANDING DESTINATIONS: a vendor arrives
 * cold, on whatever device Stripe was open on, often minutes or hours after they
 * last touched the dashboard. They are not the tail of a happy path.
 *
 * ── Why neither page reports a Connect status ──────────────────────────────
 *
 * Two independent reasons, both load-bearing:
 *
 *  1. **Stripe passes no state through either URL.** `return_url` fires when the
 *     vendor *leaves* the flow — completed or abandoned, Stripe does not say
 *     which, and appends no query parameters. Rendering "Connected!" here would
 *     be a guess, and it would be wrong every time someone backs out.
 *
 *  2. **The platform has no vendor-facing read for it.** `stripeConnectStatus`
 *     is exposed only on `TenantDto`, served only by `TenantAdminController`
 *     (`/api/v1/admin/tenants/{tenantId}`, `@PreAuthorize("hasRole('admin')")`).
 *     That needs a tenant id, and the vendor session deliberately does not carry
 *     one — the server derives tenancy from the JWT, and the client is never the
 *     authority on scope. So there is nothing honest to poll from here.
 *
 * The truth is that capability state arrives asynchronously on the
 * `account.updated` webhook (`StripeConnectService.handleAccountUpdated`), which
 * is exactly what the copy says. When the vendor payments/payouts surface is
 * built with a tenant-scoped read endpoint behind it, this page gains a live
 * status panel; until then it does not pretend to have one.
 *
 * Server component on purpose — no state, no effects, no fetch, so no client
 * bundle. The root layout is already `dynamic = "force-dynamic"` (CSP nonce),
 * and `app/dashboard/layout.tsx` gates the session, so an expired-session or
 * deep-linked arrival is redirected to `/auth/signin` rather than shown a shell.
 */

export type ConnectOutcomeVariant = "return" | "refresh"

interface OutcomeCopy {
  icon: LucideIcon
  iconWrapper: string
  badge: { label: string; className: string }
  title: string
  lede: string
  detail: string
  stepsTitle: string
  stepsDescription: string
  steps: string[]
  supportPrompt: string
  supportFallback: string
}

/**
 * All user-facing copy for both variants in one reviewable place — the same
 * shape the onboarding page uses for its state/gate maps, so a copy change is a
 * one-file diff and neither page can drift from the other.
 */
const OUTCOME_COPY: Record<ConnectOutcomeVariant, OutcomeCopy> = {
  return: {
    icon: PlaneLanding,
    iconWrapper: "bg-blue-50 text-blue-600",
    // The badge carries information the heading does not, and stays true whether
    // the vendor finished or backed out: either way J'Toye is waiting to hear from
    // Stripe. "No action needed" / "Connected" would be a lie for anyone who bailed
    // halfway, and that vendor lands on exactly this page.
    badge: { label: "Awaiting Stripe", className: "bg-blue-100 text-blue-700" },
    title: "You're back from Stripe",
    lede:
      "Stripe has sent you back to J'Toye. Stripe doesn't tell us at this point whether you finished, so we won't guess — here's what actually happens next.",
    detail:
      "Stripe checks the business and identity details you submitted, then tells J'Toye directly when your account is cleared to take payments. That is usually a few minutes, but it can take longer if Stripe needs another document from you. You don't need to stay on this page, and closing it changes nothing. And if you didn't finish, nothing is lost — Stripe keeps what you entered and picks up where you left off next time you open an onboarding link.",
    stepsTitle: "What happens next",
    stepsDescription: "Stripe drives each of these — there's nothing to do on this page.",
    steps: [
      "Stripe verifies the business and identity details you submitted.",
      "Stripe notifies J'Toye the moment your account is cleared for payouts.",
      "Payouts for your marketplace orders start routing to your Stripe account automatically.",
    ],
    supportPrompt:
      "Stripe still asking you for something, or nothing has changed after a working day?",
    supportFallback:
      "If Stripe is still asking for documents, or nothing has changed after a working day, contact your J'Toye account manager.",
  },
  refresh: {
    icon: Link2Off,
    iconWrapper: "bg-amber-50 text-amber-600",
    badge: { label: "Action needed", className: "bg-amber-100 text-amber-700" },
    title: "That Stripe link has expired",
    lede:
      "Stripe sent you here because the onboarding link you opened has expired, had already been used, or is no longer valid. Nothing has gone wrong with your account.",
    detail:
      "Stripe onboarding links are single-use and short-lived, so this happens if the tab sat open for a while or the same link was opened twice. Anything you already submitted to Stripe is kept — a fresh link picks up where you left off rather than starting over.",
    stepsTitle: "How to carry on",
    stepsDescription: "A new link has to be issued for you — it can't be re-created from this page.",
    steps: [
      "Ask your J'Toye administrator, or our support team, to issue a new Stripe onboarding link.",
      "Open the new link straight away and complete it in one sitting.",
      "Stripe keeps the details you already submitted, so you won't start from scratch.",
    ],
    supportPrompt: "Need a new link?",
    supportFallback:
      "To get a new onboarding link, contact your J'Toye administrator or account manager.",
  },
}

/**
 * Onward routes. A landing destination must never be a dead end, and these are
 * the two surfaces a vendor arriving from Stripe actually wants: the dashboard
 * they were signed out of, and the money screen this flow is about.
 */
const ONWARD = {
  primary: { href: "/dashboard", label: "Go to dashboard", icon: LayoutDashboard },
  secondary: { href: "/dashboard/finance", label: "View finance", icon: Banknote },
} as const

export function ConnectOutcome({ variant }: { variant: ConnectOutcomeVariant }) {
  const copy = OUTCOME_COPY[variant]
  const Icon = copy.icon
  const PrimaryIcon = ONWARD.primary.icon
  const SecondaryIcon = ONWARD.secondary.icon

  // Config-injected support channel (GLOBAL_RULE_6 — no mailto/URL literal in a
  // component). Absent config degrades to plain copy, never a dead link.
  const support = resolveSupportChannel(
    process.env.NEXT_PUBLIC_SUPPORT_EMAIL,
    process.env.NEXT_PUBLIC_SUPPORT_URL,
  )
  const supportIsExternal = support.href?.startsWith("http") ?? false

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <div className="flex flex-wrap items-center gap-3">
          <span
            className={`inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-xl ${copy.iconWrapper}`}
          >
            <Icon className="h-5 w-5" aria-hidden="true" />
          </span>
          <h1 className="text-3xl font-semibold text-slate-900 sm:text-4xl">{copy.title}</h1>
          <Badge className={`${copy.badge.className} pointer-events-none`}>
            {copy.badge.label}
          </Badge>
        </div>
        <p className="mt-3 text-sm text-slate-600">{copy.lede}</p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-lg">{copy.stepsTitle}</CardTitle>
          <CardDescription>{copy.stepsDescription}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <p className="text-sm text-slate-600">{copy.detail}</p>
          <ol className="space-y-3">
            {copy.steps.map((step, i) => (
              <li key={step} className="flex items-start gap-3">
                <span className="mt-0.5 inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-slate-100 text-xs font-semibold text-slate-600">
                  {i + 1}
                </span>
                <span className="text-sm text-slate-700">{step}</span>
              </li>
            ))}
          </ol>
        </CardContent>
      </Card>

      {/* Never a dead end. Full-width stacked at 375px, side by side from sm. */}
      <div className="flex flex-col gap-3 sm:flex-row">
        <Button asChild className="w-full sm:w-auto">
          <Link href={ONWARD.primary.href}>
            <PrimaryIcon className="mr-2 h-4 w-4" aria-hidden="true" />
            {ONWARD.primary.label}
            <ArrowRight className="ml-2 h-4 w-4" aria-hidden="true" />
          </Link>
        </Button>
        <Button asChild variant="outline" className="w-full sm:w-auto">
          <Link href={ONWARD.secondary.href}>
            <SecondaryIcon className="mr-2 h-4 w-4" aria-hidden="true" />
            {ONWARD.secondary.label}
          </Link>
        </Button>
      </div>

      <div className="flex flex-wrap items-start gap-2 rounded-lg border border-slate-100 bg-slate-50/60 p-4 text-sm">
        <LifeBuoy className="mt-0.5 h-4 w-4 shrink-0 text-slate-400" aria-hidden="true" />
        {support.href ? (
          <p className="text-slate-600">
            {copy.supportPrompt}{" "}
            <a
              href={support.href}
              target={supportIsExternal ? "_blank" : undefined}
              rel={supportIsExternal ? "noopener noreferrer" : undefined}
              className="font-medium text-blue-600 hover:underline"
            >
              Contact support{support.label ? ` (${support.label})` : ""}
            </a>
          </p>
        ) : (
          <p className="text-slate-600">{copy.supportFallback}</p>
        )}
      </div>
    </div>
  )
}
