"use client"

import { useCallback, useEffect, useState } from "react"
import { m } from "framer-motion"
import Link from "next/link"
import apiClient from "@/lib/api-client"
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
  Loader2,
  MinusCircle,
  Store,
  UtensilsCrossed,
  Wheat,
  XCircle,
} from "lucide-react"
import type { LucideIcon } from "lucide-react"
import { formatDistanceToNow } from "date-fns"
import type {
  CreateOnboardingRequest,
  GateDto,
  GateStatus,
  GateType,
  OnboardingDto,
  OnboardingModel,
  OnboardingState,
  Shop,
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

// States that warrant background polling of GET /me (async gate landing +
// background auto-approve).
const POLL_STATES: OnboardingState[] = ["VERIFYING", "PENDING_APPROVAL"]

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
      const res = await apiClient.get("/api/v1/shops?page=0&size=100&sort=name,asc")
      setShops(res.data.content || [])
    } catch {
      // Non-critical — the select simply stays empty.
    }
  }, [])

  useEffect(() => {
    loadOnboarding(true)
    fetchShops()
  }, [loadOnboarding, fetchShops])

  // Poll GET /me every 4s while VERIFYING / PENDING_APPROVAL; the interval is
  // cleared on unmount and as soon as status leaves the polling set (the effect
  // re-runs when `pollStatus` changes and returns early / cleans up).
  const pollStatus = onboarding?.status ?? null
  useEffect(() => {
    if (!pollStatus || !POLL_STATES.includes(pollStatus)) return
    const interval = setInterval(() => {
      void loadOnboarding(false)
    }, 4000)
    return () => clearInterval(interval)
  }, [pollStatus, loadOnboarding])

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

  // --- Render: loading ------------------------------------------------------

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-blue-600" />
      </div>
    )
  }

  // --- Render: create form (no onboarding yet) ------------------------------

  if (!onboarding) {
    return (
      <div className="space-y-6">
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
                  <p className="text-xs text-slate-400">
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
  const failedGates = onboarding.gates.filter((g) => g.status === "FAILED")

  const milestones: { label: string; at: string | null }[] = [
    { label: "Submitted", at: onboarding.submittedAt },
    { label: "Approved", at: onboarding.approvedAt },
    { label: "Went live", at: onboarding.wentLiveAt },
  ]

  return (
    <div className="space-y-6">
      {/* Header + overall-state badge (a focal point) */}
      <m.div initial={{ opacity: 0, y: -20 }} animate={{ opacity: 1, y: 0 }}>
        <div className="flex items-center gap-3">
          <h1 className="text-4xl font-semibold text-slate-900">Go live</h1>
          <Badge className={`${stateMeta.badge} pointer-events-none`}>{stateMeta.label}</Badge>
        </div>
        <p className="mt-2 text-sm text-slate-600">
          {STATE_SUBTITLE[onboarding.status] ?? ""}
        </p>
      </m.div>

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

      {/* Action-required: surface each failed gate reason */}
      {onboarding.status === "ACTION_REQUIRED" && failedGates.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Action required</CardTitle>
          </CardHeader>
          <CardContent>
            <ul className="space-y-2">
              {failedGates.map((gate) => (
                <li key={gate.gateType} className="text-sm text-slate-600">
                  <span className="font-semibold text-slate-900">
                    {(GATE_META[gate.gateType] ?? GATE_FALLBACK).label}:
                  </span>{" "}
                  {gate.reason ?? "This check needs your attention."}
                </li>
              ))}
            </ul>
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
    </div>
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
          <span className="text-xs text-slate-400">{gate.mandatory ? "Required" : "Optional"}</span>
        </div>
        {gate.reason && <p className="mt-1 text-sm text-slate-600">{gate.reason}</p>}
        <p className="mt-1 text-xs text-slate-400">{checkedAtLabel(gate.checkedAt)}</p>
      </div>
      <Badge className={`${statusMeta.badge} pointer-events-none shrink-0`}>
        <StatusIcon className={`mr-1 h-3 w-3 ${gate.status === "PENDING" ? "animate-spin" : ""}`} />
        {statusMeta.label}
      </Badge>
    </div>
  )
}
