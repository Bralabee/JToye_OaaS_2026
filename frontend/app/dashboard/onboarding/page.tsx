"use client"

// ⚠ MERGE NOTE: the app runs `<LazyMotion strict>` — full `motion.*` components
// THROW at runtime; only `m.*` (import { m } from "framer-motion") is allowed.
// jest mocks framer-motion so it won't catch a stray `motion.*` — verify this
// page in a browser after resolving. Recipe: docs/integration/motion-foundation-integration.md
import { useCallback, useEffect, useState } from "react"
import { m } from "framer-motion"
import Link from "next/link"
import apiClient from "@/lib/api-client"
import { cn } from "@/lib/utils"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { fetchAllMyShops } from "@/lib/shops-api"
import { useToast } from "@/hooks/use-toast"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Badge } from "@/components/ui/badge"
import {
  AlertTriangle,
  Building2,
  CheckCircle2,
  Circle,
  ExternalLink,
  LifeBuoy,
  Loader2,
  LogOut,
  MinusCircle,
  Pencil,
  Store,
  UtensilsCrossed,
  Wheat,
  XCircle,
} from "lucide-react"
import type { LucideIcon } from "lucide-react"
import { formatDistanceToNow } from "date-fns"
import { resolveSupportChannel } from "@/lib/env-validation"
import type {
  CreateOnboardingRequest,
  GateDto,
  GateStatus,
  GateType,
  OnboardingDto,
  OnboardingModel,
  OnboardingState,
  Shop,
  UpdateOnboardingRequest,
} from "@/types/api"

// --- Static mappings (from the LOCKED 18-UI-SPEC "State & Status Mapping") ---

const STATE_META: Record<OnboardingState, { label: string; badge: string }> = {
  DRAFT: { label: "Draft", badge: "bg-slate-100 text-slate-600" },
  VERIFYING: { label: "Running checks", badge: "bg-amber-100 text-amber-700" },
  ACTION_REQUIRED: { label: "Action required", badge: "bg-orange-100 text-orange-700" },
  PENDING_APPROVAL: { label: "Awaiting approval", badge: "bg-blue-100 text-blue-700" },
  APPROVED: { label: "Ready to go live", badge: "bg-blue-100 text-blue-700" },
  LIVE: { label: "Live", badge: "bg-emerald-100 text-emerald-700" },
  SUSPENDED: { label: "Suspended", badge: "bg-red-100 text-red-700" },
  REJECTED: { label: "Rejected", badge: "bg-red-100 text-red-700" },
  WITHDRAWN: { label: "Withdrawn", badge: "bg-slate-100 text-slate-500" },
}

const STATE_SUBTITLE: Record<OnboardingState, string> = {
  DRAFT: "Submit your application to run the compliance checks.",
  VERIFYING:
    "Running your compliance checks. This usually takes under a minute — you can leave this page and come back.",
  ACTION_REQUIRED: "One or more checks need your attention before you can go live.",
  PENDING_APPROVAL: "Your checks passed — approval is being finalised.",
  APPROVED: "You're ready to publish your storefront.",
  LIVE: "Your storefront is live and visible to customers.",
  SUSPENDED: "Your storefront has been suspended. Contact support for details.",
  REJECTED: "Your application was not approved. Contact support for details.",
  WITHDRAWN: "This application has been withdrawn.",
}

const GATE_META: Record<GateType, { label: string; icon: LucideIcon }> = {
  BUSINESS_VERIFIED: { label: "Business verification", icon: Building2 },
  FOOD_HYGIENE_RATING: { label: "Food hygiene rating", icon: UtensilsCrossed },
  ALLERGEN_DATA_COMPLETE: { label: "Allergen data", icon: Wheat },
}

const GATE_STATUS_META: Record<
  GateStatus,
  { label: string; badge: string; icon: LucideIcon }
> = {
  PENDING: { label: "Checking…", badge: "bg-amber-100 text-amber-700", icon: Loader2 },
  PASSED: { label: "Passed", badge: "bg-emerald-100 text-emerald-700", icon: CheckCircle2 },
  FAILED: { label: "Failed", badge: "bg-red-100 text-red-700", icon: XCircle },
  MANUAL_REVIEW: { label: "Manual review", badge: "bg-amber-100 text-amber-700", icon: AlertTriangle },
  WAIVED: { label: "Not required", badge: "bg-slate-100 text-slate-600", icon: MinusCircle },
}

// Defensive fallbacks — an unknown gateType/status renders neutral slate,
// never crashes (18-UI-SPEC: "Map defensively").
const GATE_FALLBACK = { label: "Check", icon: MinusCircle as LucideIcon }
const GATE_STATUS_FALLBACK = {
  label: "Unknown",
  badge: "bg-slate-100 text-slate-600",
  icon: MinusCircle as LucideIcon,
}

// Remediation map (ONBD-04): (gateType, status) -> why / what-to-do / where to go.
// The gate `reason` already carries the specifics (offending SKUs, the FHRS miss);
// these blocks add a generic "why" fallback, the actionable "what", and the deep
// link (D-08): BUSINESS_VERIFIED -> the inline company-number edit (#company-number),
// ALLERGEN_DATA_COMPLETE -> the products screen, FOOD_HYGIENE_RATING -> the shop edit
// screen. An unmapped (gateType, status) falls back to a neutral render — never crashes.
const REMEDIATION: Partial<
  Record<`${GateType}:${GateStatus}`, { why: string; what: string; href: string; cta: string }>
> = {
  "BUSINESS_VERIFIED:FAILED": {
    why: "We couldn't verify your business against Companies House.",
    what:
      "Check your Companies House number is right — or clear it if you trade as a sole trader — then re-run your checks.",
    href: "#company-number",
    cta: "Edit company number",
  },
  "ALLERGEN_DATA_COMPLETE:FAILED": {
    why: "Some of your products are missing the allergen information the law requires.",
    what: "Add the missing allergen data to the products listed above, then re-run your checks.",
    href: "/dashboard/products",
    cta: "Fix these products",
  },
  "FOOD_HYGIENE_RATING:MANUAL_REVIEW": {
    why: "We couldn't automatically match your shop to a Food Standards Agency hygiene rating.",
    what:
      "Make sure your shop's registered name and address match your premises exactly, then re-run your checks.",
    href: "/dashboard/shops",
    cta: "Edit shop details",
  },
}

// The 5 pre-live states the state machine wires WITHDRAW from (D-05) — a vendor
// can bail any time before LIVE; terminal states can't.
const WITHDRAWABLE_STATES: OnboardingState[] = [
  "DRAFT",
  "VERIFYING",
  "ACTION_REQUIRED",
  "PENDING_APPROVAL",
  "APPROVED",
]

// States that warrant background polling of GET /me (async gate landing +
// background auto-approve).
const POLL_STATES: OnboardingState[] = ["VERIFYING", "PENDING_APPROVAL"]
// Fast poll while gates are actively running; back right off once a human is in
// the loop (reviewPending) — a manual review advances on a reviewer action, not a
// webhook, so hammering GET /me every 4s is pointless (ONBD-03 / Pitfall 5).
const FAST_POLL_MS = 4000
const REVIEW_POLL_MS = 30000

function httpStatus(err: unknown): number | undefined {
  if (err && typeof err === "object" && "response" in err) {
    return (err as { response?: { status?: number } }).response?.status
  }
  return undefined
}

function checkedAtLabel(checkedAt: string | null): string {
  if (!checkedAt) return "Not yet checked"
  return `Last checked ${formatDistanceToNow(new Date(checkedAt), { addSuffix: true })}`
}

export default function OnboardingPage() {
  const { toast } = useToast()

  const [onboarding, setOnboarding] = useState<OnboardingDto | null>(null)
  const [loading, setLoading] = useState(true)

  // Create-form state
  const [shops, setShops] = useState<Shop[]>([])
  const [model, setModel] = useState<OnboardingModel>("MARKETPLACE")
  const [shopId, setShopId] = useState("")
  const [companyNumber, setCompanyNumber] = useState("")
  const [creating, setCreating] = useState(false)

  // Status-view action state
  const [submitting, setSubmitting] = useState(false)
  const [goLiveOpen, setGoLiveOpen] = useState(false)
  const [goingLive, setGoingLive] = useState(false)

  // Withdraw confirm dialog (ONBD-01)
  const [withdrawOpen, setWithdrawOpen] = useState(false)
  const [withdrawing, setWithdrawing] = useState(false)

  // Inline company-number correction (ONBD-02) — seeded from the loaded
  // application (see the id-keyed effect below) so a re-poll never clobbers typing.
  const [editCompanyNumber, setEditCompanyNumber] = useState("")
  const [savingCompanyNumber, setSavingCompanyNumber] = useState(false)

  // --- Data loading ---------------------------------------------------------

  const loadOnboarding = useCallback(
    async (initial: boolean) => {
      try {
        const res = await apiClient.get("/api/v1/onboarding/me")
        setOnboarding(res.data)
      } catch (err: unknown) {
        // 404 => no onboarding yet -> show the create form (primary job).
        if (httpStatus(err) === 404) {
          setOnboarding(null)
        } else {
          // 5xx/network already auto-retried by the api-client, then surfaced.
          toast({
            variant: "destructive",
            title: "Couldn't load your onboarding",
            description: "Check your connection and try again.",
          })
        }
      } finally {
        if (initial) setLoading(false)
      }
    },
    [toast]
  )

  const fetchShops = useCallback(async () => {
    try {
      // #485 (call site :232): was a single `/api/v1/shops?page=0&size=100&...`,
      // whose first page was treated as the whole list. Past 100 shops the tail
      // could not be picked when starting an onboarding application — so those
      // shops could never be taken through onboarding, and onboarding is the sole
      // writer of `Shop.published`. The `name,asc` sort is passed through so the
      // select stays alphabetical.
      setShops(await fetchAllMyShops("name,asc"))
    } catch {
      // Non-critical — the select simply stays empty.
    }
  }, [])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
    loadOnboarding(true)
    fetchShops()
  }, [loadOnboarding, fetchShops])

  // Poll GET /me while VERIFYING / PENDING_APPROVAL; the interval is cleared on
  // unmount and as soon as status leaves the polling set (the effect re-runs when
  // `pollStatus`/`reviewPending` change and returns early / cleans up). Once a human
  // is in the loop (reviewPending) the cadence backs off from 4s to 30s (ONBD-03).
  const pollStatus = onboarding?.status ?? null
  const reviewPending = onboarding?.reviewPending ?? false
  useEffect(() => {
    if (!pollStatus || !POLL_STATES.includes(pollStatus)) return
    const intervalMs = reviewPending ? REVIEW_POLL_MS : FAST_POLL_MS
    const interval = setInterval(() => {
      void loadOnboarding(false)
    }, intervalMs)
    return () => clearInterval(interval)
  }, [pollStatus, reviewPending, loadOnboarding])

  // Seed the inline company-number field once per application (keyed by id, so a
  // background re-poll of the same application never overwrites in-progress typing).
  const onboardingId = onboarding?.id ?? null
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
    setEditCompanyNumber(onboarding?.companyNumber ?? "")
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onboardingId])

  // --- Actions --------------------------------------------------------------

  const handleCreate = async () => {
    if (!shopId) return
    try {
      setCreating(true)
      const body: CreateOnboardingRequest = {
        model,
        shopId,
        companyNumber: companyNumber.trim() || undefined,
      }
      const res = await apiClient.post("/api/v1/onboarding", body)
      setOnboarding(res.data)
    } catch (err: unknown) {
      if (httpStatus(err) === 409) {
        toast({
          title: "Onboarding already started",
          description: "You already have an application in progress.",
        })
        void loadOnboarding(false)
      } else {
        toast({
          variant: "destructive",
          title: "Couldn't create your application",
          description: err instanceof Error ? err.message : "Please try again.",
        })
      }
    } finally {
      setCreating(false)
    }
  }

  const handleSubmit = async () => {
    try {
      setSubmitting(true)
      const res = await apiClient.post("/api/v1/onboarding/submit", {})
      setOnboarding(res.data)
    } catch (err: unknown) {
      toast({
        variant: "destructive",
        title: "Couldn't submit for verification",
        description: err instanceof Error ? err.message : "Please try again.",
      })
    } finally {
      setSubmitting(false)
    }
  }

  // CR-03: ACTION_REQUIRED recovery. Fixing the flagged data then re-running the
  // checks calls the dedicated POST /onboarding/resubmit (RESUBMIT: ACTION_REQUIRED
  // -> VERIFYING), which resets the FAILED/MANUAL_REVIEW gates to PENDING and
  // re-kicks the gate chain. (The old "Re-run checks" wiring hit /submit, which the
  // state machine only accepts from DRAFT, so it always 400'd.) The success response
  // lands the page in VERIFYING and polling resumes automatically.
  const handleResubmit = async () => {
    try {
      setSubmitting(true)
      const res = await apiClient.post("/api/v1/onboarding/resubmit", {})
      setOnboarding(res.data)
    } catch (err: unknown) {
      toast({
        variant: "destructive",
        title: "Couldn't re-run your checks",
        description:
          "Update the flagged information on your products, then try again or contact support.",
      })
    } finally {
      setSubmitting(false)
    }
  }

  const handleGoLive = async () => {
    try {
      setGoingLive(true)
      const res = await apiClient.post("/api/v1/onboarding/go-live", {})
      setOnboarding(res.data)
      setGoLiveOpen(false)
    } catch (err: unknown) {
      // Guard veto (400): a mandatory gate is not satisfied. Keep the status
      // view (and its gate breakdown) mounted so the vendor sees the blocker.
      if (httpStatus(err) === 400) {
        toast({
          variant: "destructive",
          title: "Not ready to go live yet",
          description: "Every check below must pass first. Resolve the flagged items and try again.",
        })
      } else {
        toast({
          variant: "destructive",
          title: "Couldn't take your storefront live",
          description: err instanceof Error ? err.message : "Please try again.",
        })
      }
      setGoLiveOpen(false)
    } finally {
      setGoingLive(false)
    }
  }

  // ONBD-01: withdraw from any pre-live state. POST /onboarding/withdraw is body-less
  // and drives the canonical WITHDRAW transition server-side -> terminal WITHDRAWN.
  const handleWithdraw = async () => {
    try {
      setWithdrawing(true)
      const res = await apiClient.post("/api/v1/onboarding/withdraw", {})
      setOnboarding(res.data)
      setWithdrawOpen(false)
    } catch (err: unknown) {
      toast({
        variant: "destructive",
        title: "Couldn't withdraw your application",
        description: err instanceof Error ? err.message : "Please try again.",
      })
      setWithdrawOpen(false)
    } finally {
      setWithdrawing(false)
    }
  }

  // ONBD-02: correct the company number in place (blank = sole trader), then the
  // vendor re-runs the checks via the existing handleResubmit. POST /onboarding/company-number
  // is gated to DRAFT/ACTION_REQUIRED server-side and re-validated like create (400 on garbage).
  const handleSaveCompanyNumber = async () => {
    try {
      setSavingCompanyNumber(true)
      const body: UpdateOnboardingRequest = {
        companyNumber: editCompanyNumber.trim() || undefined,
      }
      const res = await apiClient.post("/api/v1/onboarding/company-number", body)
      setOnboarding(res.data)
      toast({
        title: "Company number updated",
        description: "Re-run your checks to verify it.",
      })
    } catch (err: unknown) {
      toast({
        variant: "destructive",
        title: "Couldn't update your company number",
        description:
          httpStatus(err) === 400
            ? "That doesn't look like a valid Companies House number. Enter 2–10 letters or digits, or leave it blank if you're a sole trader."
            : err instanceof Error
              ? err.message
              : "Please try again.",
      })
    } finally {
      setSavingCompanyNumber(false)
    }
  }

  // --- Render: loading ------------------------------------------------------

  // Same Detail tier as the loaded branch, deliberately — see the note on the
  // main return below. Without it this spinner sits in the full Shell band and
  // the page snaps narrower the instant the request settles.
  if (loading) {
    return (
      <div
        data-width-tier="detail"
        className={cn(
          "mx-auto",
          WIDTH_TIER_CLASS.detail,
          "flex h-full items-center justify-center"
        )}
      >
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-blue-600" />
      </div>
    )
  }

  // --- Render: create form (no onboarding yet) ------------------------------

  // Same tier again, and this branch is not a transient: it is the state a
  // brand-new vendor sees FIRST, so a width that disagreed with the loaded
  // state would be the version most people met.
  if (!onboarding) {
    return (
      <div
        data-width-tier="detail"
        className={cn("mx-auto", WIDTH_TIER_CLASS.detail, "space-y-6")}
      >
        <m.div initial={{ opacity: 0, y: -20 }} animate={{ opacity: 1, y: 0 }}>
          <h1 className="text-4xl font-semibold text-slate-900">Take your shop live</h1>
          <p className="mt-2 text-sm text-slate-600">
            Run our free compliance checks — business registration, food hygiene rating, and
            allergen data — then publish your storefront. It takes a couple of minutes.
          </p>
        </m.div>

        {shops.length === 0 ? (
          <Card>
            <CardContent className="flex flex-col items-center justify-center py-12 text-center">
              <Store className="mb-4 h-12 w-12 text-slate-300" />
              <h3 className="mb-2 text-lg font-semibold text-slate-900">Create a shop first</h3>
              <p className="mb-4 text-sm text-slate-500">
                You&apos;ll need a shop before you can take it live.
              </p>
              <Link href="/dashboard/shops">
                <Button variant="outline">Go to shops</Button>
              </Link>
            </CardContent>
          </Card>
        ) : (
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">Start your application</CardTitle>
              <CardDescription>
                Tell us how you&apos;ll sell and which shop you&apos;re taking live.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-6">
                {/* Model */}
                <div className="space-y-2">
                  <Label className="font-normal">How will you sell?</Label>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      type="button"
                      variant={model === "MARKETPLACE" ? "default" : "outline"}
                      onClick={() => setModel("MARKETPLACE")}
                    >
                      On the J&apos;Toye marketplace
                    </Button>
                    <Button
                      type="button"
                      variant={model === "WHITE_LABEL" ? "default" : "outline"}
                      onClick={() => setModel("WHITE_LABEL")}
                    >
                      On my own storefront
                    </Button>
                  </div>
                </div>

                {/* Shop */}
                <div className="space-y-2">
                  <Label htmlFor="onboarding-shop" className="font-normal">
                    Which shop?
                  </Label>
                  <select
                    id="onboarding-shop"
                    value={shopId}
                    onChange={(e) => setShopId(e.target.value)}
                    className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    <option value="">Select a shop</option>
                    {shops.map((shop) => (
                      <option key={shop.id} value={shop.id}>
                        {shop.name}
                      </option>
                    ))}
                  </select>
                </div>

                {/* Company number */}
                <div className="space-y-2">
                  <Label htmlFor="onboarding-company" className="font-normal">
                    Companies House number (optional)
                  </Label>
                  <Input
                    id="onboarding-company"
                    value={companyNumber}
                    onChange={(e) => setCompanyNumber(e.target.value)}
                    placeholder="e.g. 01234567"
                  />
                  <p className="text-xs text-slate-600">
                    Companies House number — leave blank if you&apos;re a sole trader.
                  </p>
                </div>

                <Button onClick={handleCreate} disabled={!shopId || creating}>
                  {creating ? "Creating…" : "Create application"}
                </Button>
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    )
  }

  // --- Render: status view --------------------------------------------------

  const stateMeta = STATE_META[onboarding.status] ?? {
    label: onboarding.status,
    badge: "bg-slate-100 text-slate-600",
  }

  // ONBD-03: an honest "in review" state the moment a human is needed. `reviewPending`
  // is derived server-side; the SLA copy is config-injected (no "N days" literal here).
  const inReview = onboarding.reviewPending === true
  const reviewSlaDays = process.env.NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS?.trim()
  const badgeLabel = inReview ? "In review" : stateMeta.label
  const subtitle = inReview
    ? reviewSlaDays
      ? `Your checks are with our team for review. A reviewer looks at these within ${reviewSlaDays} business days — we'll email you when there's an update, so you can safely leave this page.`
      : `Your checks are with our team for review. A reviewer is looking at these now — we'll email you when there's an update, so you can safely leave this page.`
    : STATE_SUBTITLE[onboarding.status] ?? ""

  // ONBD-05: config-injected support channel for REJECTED/SUSPENDED — the email
  // link scheme is built inside resolveSupportChannel, so no link literal lives here.
  const support = resolveSupportChannel(
    process.env.NEXT_PUBLIC_SUPPORT_EMAIL,
    process.env.NEXT_PUBLIC_SUPPORT_URL
  )

  // ONBD-04: gates the vendor can act on now — anything FAILED, plus any (gateType,status)
  // with an explicit remediation (e.g. FHRS MANUAL_REVIEW). Still-running PENDING gates
  // are excluded so we never nag mid-check.
  const actionableGates = onboarding.gates.filter(
    (g) =>
      g.status === "FAILED" ||
      Boolean(REMEDIATION[`${g.gateType}:${g.status}` as `${GateType}:${GateStatus}`])
  )

  const canWithdraw = WITHDRAWABLE_STATES.includes(onboarding.status)
  const canEditCompanyNumber =
    onboarding.status === "DRAFT" || onboarding.status === "ACTION_REQUIRED"

  const milestones: { label: string; at: string | null }[] = [
    { label: "Submitted", at: onboarding.submittedAt },
    { label: "Approved", at: onboarding.approvedAt },
    { label: "Went live", at: onboarding.wentLiveAt },
  ]

  // THE DETAIL TIER (phase 35, UIX-09). This page is a form with sequential
  // gates — read, then filled in, one decision at a time. Width buys it nothing:
  // detail and reading columns cluster between 1016px (Square's content ladder)
  // and 1136px (Linear's), with Lightspeed's content column at 1100, and
  // prose-measure guidance (45-75 characters a line) is why they converge
  // (CONTEXT.md section 3). So this element is deliberately narrower than the
  // Shell band around it and centres inside it. The number lives once, in
  // lib/layout-widths.ts (DETAIL_MAX_PX) — change it there, never here.
  //
  // RemediationRow and GateRow below are rendered INSIDE this element and
  // inherit the tier. Do not cap them: a cap nested inside a cap resolves by
  // cascade, looks correct in review, and is wrong at exactly one viewport.
  return (
    <div
      data-width-tier="detail"
      className={cn("mx-auto", WIDTH_TIER_CLASS.detail, "space-y-6")}
    >
      {/* Header + overall-state badge (a focal point) */}
      <m.div initial={{ opacity: 0, y: -20 }} animate={{ opacity: 1, y: 0 }}>
        <div className="flex items-center gap-3">
          <h1 className="text-4xl font-semibold text-slate-900">Go live</h1>
          <Badge className={`${stateMeta.badge} pointer-events-none`}>{badgeLabel}</Badge>
        </div>
        <p className="mt-2 text-sm text-slate-600">{subtitle}</p>
      </m.div>

      {/* Rejection / suspension (ONBD-05): the actual recorded reason + a
          config-injected support channel — replaces the bare "Contact support". */}
      {(onboarding.status === "REJECTED" || onboarding.status === "SUSPENDED") && (
        <Card className="border-red-100">
          <CardHeader>
            <CardTitle className="text-lg">
              {onboarding.status === "REJECTED"
                ? "Your application wasn't approved"
                : "Your storefront is suspended"}
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {onboarding.rejectionReason ? (
              <p className="text-sm text-slate-700">{onboarding.rejectionReason}</p>
            ) : (
              <p className="text-sm text-slate-600">
                No specific reason was recorded. Our support team can explain what happened and
                what to do next.
              </p>
            )}
            {support.href ? (
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <LifeBuoy className="h-4 w-4 text-slate-400" />
                <span className="text-slate-600">Need help?</span>
                <a
                  href={support.href}
                  target={support.href.startsWith("http") ? "_blank" : undefined}
                  rel={support.href.startsWith("http") ? "noopener noreferrer" : undefined}
                  className="font-medium text-blue-600 hover:underline"
                >
                  Contact support{support.label ? ` (${support.label})` : ""}
                </a>
              </div>
            ) : (
              <p className="text-sm text-slate-500">
                Please contact your J&apos;Toye account manager for details.
              </p>
            )}
          </CardContent>
        </Card>
      )}

      {/* Gate breakdown */}
      <Card>
        <CardHeader>
          <CardTitle className="text-lg">Compliance checks</CardTitle>
          <CardDescription>
            Every mandatory check must pass before your storefront can go live.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            {onboarding.gates.map((gate) => (
              <GateRow key={gate.gateType} gate={gate} />
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Action-required (ONBD-04): each actionable gate as why -> what -> go-there.
          The gate reason (which names the offending SKUs / states the FHRS miss) is
          preserved; an unmapped gate falls back to a neutral render, never a crash. */}
      {actionableGates.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">What needs your attention</CardTitle>
            <CardDescription>Fix the items below, then re-run your checks.</CardDescription>
          </CardHeader>
          <CardContent>
            <ul className="space-y-4">
              {actionableGates.map((gate) => (
                <RemediationRow key={gate.gateType} gate={gate} />
              ))}
            </ul>
          </CardContent>
        </Card>
      )}

      {/* Correctable data (ONBD-02): edit the company number in place, then re-run.
          `id` is the deep-link target of the BUSINESS_VERIFIED remediation block. */}
      {canEditCompanyNumber && (
        <Card id="company-number">
          <CardHeader>
            <CardTitle className="text-lg">Company details</CardTitle>
            <CardDescription>
              Correct your Companies House number here, then re-run your checks. Leave it blank if
              you trade as a sole trader.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              <Label htmlFor="edit-company-number" className="font-normal">
                Companies House number
              </Label>
              <Input
                id="edit-company-number"
                value={editCompanyNumber}
                onChange={(e) => setEditCompanyNumber(e.target.value)}
                placeholder="e.g. 01234567"
              />
              <div className="pt-1">
                <Button
                  variant="outline"
                  onClick={handleSaveCompanyNumber}
                  disabled={savingCompanyNumber}
                >
                  {savingCompanyNumber ? "Saving…" : "Save company number"}
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Milestone timeline */}
      <Card>
        <CardHeader>
          <CardTitle className="text-lg">Progress</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-2">
            {milestones.map((m) => (
              <div key={m.label} className="flex items-center gap-2 text-xs text-slate-500">
                {m.at ? (
                  <CheckCircle2 className="h-4 w-4 text-emerald-500" />
                ) : (
                  <Circle className="h-4 w-4 text-slate-300" />
                )}
                <span>
                  {m.label}
                  {m.at
                    ? ` · ${formatDistanceToNow(new Date(m.at), { addSuffix: true })}`
                    : " · pending"}
                </span>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* State-driven primary CTA */}
      <div className="space-y-3">
        <div>
          {onboarding.status === "DRAFT" && (
            <Button onClick={handleSubmit} disabled={submitting}>
              {submitting ? "Submitting…" : "Submit for verification"}
            </Button>
          )}
          {onboarding.status === "ACTION_REQUIRED" && (
            <Button onClick={handleResubmit} disabled={submitting}>
              {submitting ? "Re-running…" : "Re-run checks"}
            </Button>
          )}
          {onboarding.status === "APPROVED" && (
            <Button onClick={() => setGoLiveOpen(true)}>Go live</Button>
          )}
          {onboarding.status === "LIVE" && (
            <p className="text-sm font-semibold text-emerald-700">Your storefront is live.</p>
          )}
          {onboarding.status === "WITHDRAWN" && (
            <p className="text-sm text-slate-600">
              This application has been withdrawn. Contact support if you&apos;d like to onboard again.
            </p>
          )}
        </div>

        {/* Withdraw (ONBD-01): a low-emphasis exit available on any pre-live state. */}
        {canWithdraw && (
          <div>
            <Button
              variant="ghost"
              className="text-red-600 hover:bg-red-50 hover:text-red-700"
              onClick={() => setWithdrawOpen(true)}
            >
              <LogOut className="mr-1.5 h-4 w-4" />
              Withdraw application
            </Button>
          </div>
        )}
      </div>

      {/* Go-live confirmation dialog (non-destructive, primary confirm) */}
      <Dialog open={goLiveOpen} onOpenChange={setGoLiveOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Go live?</DialogTitle>
            <DialogDescription>
              This publishes your storefront and makes it visible to customers.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setGoLiveOpen(false)} disabled={goingLive}>
              Not yet
            </Button>
            <Button onClick={handleGoLive} disabled={goingLive}>
              {goingLive ? "Going live…" : "Go live"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Withdraw confirmation dialog (destructive, terminal) */}
      <Dialog open={withdrawOpen} onOpenChange={setWithdrawOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Withdraw your application?</DialogTitle>
            <DialogDescription>
              This cancels your onboarding and can&apos;t be undone — your storefront won&apos;t go
              live. To onboard again afterwards, you&apos;ll need to contact support.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setWithdrawOpen(false)} disabled={withdrawing}>
              Keep my application
            </Button>
            <Button variant="destructive" onClick={handleWithdraw} disabled={withdrawing}>
              {withdrawing ? "Withdrawing…" : "Withdraw application"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

// --- Remediation row ----------------------------------------------------------
// (gateType, status) -> why (the specific gate reason, or a generic fallback) ->
// what-to-do -> a button that goes there (deep link). Unmapped gates render a
// neutral instruction and never crash.

function RemediationRow({ gate }: { gate: GateDto }) {
  const label = (GATE_META[gate.gateType] ?? GATE_FALLBACK).label
  const remediation =
    REMEDIATION[`${gate.gateType}:${gate.status}` as `${GateType}:${GateStatus}`]
  const isInternal = remediation?.href.startsWith("/") ?? false

  return (
    <li className="rounded-lg border border-slate-100 p-4">
      <p className="text-sm font-semibold text-slate-900">{label}</p>
      {/* The gate reason carries the specifics (offending SKUs / the FHRS miss). */}
      <p className="mt-1 text-sm text-slate-600">
        {gate.reason ?? remediation?.why ?? "This check needs your attention."}
      </p>
      {remediation ? (
        <>
          <p className="mt-2 text-sm text-slate-600">{remediation.what}</p>
          <div className="mt-3">
            {isInternal ? (
              <Link href={remediation.href}>
                <Button variant="outline" size="sm">
                  {remediation.cta}
                  <ExternalLink className="ml-1.5 h-3.5 w-3.5" />
                </Button>
              </Link>
            ) : (
              <a href={remediation.href}>
                <Button variant="outline" size="sm">
                  {remediation.cta}
                  <Pencil className="ml-1.5 h-3.5 w-3.5" />
                </Button>
              </a>
            )}
          </div>
        </>
      ) : (
        <p className="mt-2 text-sm text-slate-500">
          Update the flagged information, then re-run your checks.
        </p>
      )}
    </li>
  )
}

// --- Gate row -----------------------------------------------------------------

function GateRow({ gate }: { gate: GateDto }) {
  const typeMeta = GATE_META[gate.gateType] ?? GATE_FALLBACK
  const statusMeta = GATE_STATUS_META[gate.status] ?? GATE_STATUS_FALLBACK
  const TypeIcon = typeMeta.icon
  const StatusIcon = statusMeta.icon

  return (
    <div className="flex items-start gap-3 border-b border-slate-100 pb-4 last:border-0 last:pb-0">
      <TypeIcon className="mt-0.5 h-5 w-5 text-slate-400" />
      <div className="flex-1">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-slate-900">{typeMeta.label}</span>
          <span className="text-xs text-slate-600">{gate.mandatory ? "Required" : "Optional"}</span>
        </div>
        {gate.reason && <p className="mt-1 text-sm text-slate-600">{gate.reason}</p>}
        <p className="mt-1 text-xs text-slate-600">{checkedAtLabel(gate.checkedAt)}</p>
      </div>
      <Badge className={`${statusMeta.badge} pointer-events-none shrink-0`}>
        <StatusIcon className={`mr-1 h-3 w-3 ${gate.status === "PENDING" ? "animate-spin" : ""}`} />
        {statusMeta.label}
      </Badge>
    </div>
  )
}
