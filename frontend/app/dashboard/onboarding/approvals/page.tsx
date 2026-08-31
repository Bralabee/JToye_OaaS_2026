"use client"

import { useCallback, useEffect, useState } from "react"
import { m } from "framer-motion"
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
import { Label } from "@/components/ui/label"
import { Badge } from "@/components/ui/badge"
import {
  AlertTriangle,
  Building2,
  CheckCircle2,
  Inbox,
  Loader2,
  MinusCircle,
  ShieldCheck,
  UtensilsCrossed,
  Wheat,
  XCircle,
} from "lucide-react"
import type { LucideIcon } from "lucide-react"
import { formatDistanceToNow } from "date-fns"
import type {
  AdminOnboardingDto,
  GateDto,
  GateStatus,
  GateType,
  OnboardingModel,
  ResolveGateRequest,
} from "@/types/api"

// --- Static mappings (same vocabulary as the vendor onboarding page) ----------

const MODEL_META: Record<OnboardingModel, { label: string; badge: string }> = {
  // MARKETPLACE always requires this human step (ADR-0001) — amber signals
  // "platform carries the risk"; WHITE_LABEL normally auto-approves.
  MARKETPLACE: { label: "Marketplace", badge: "bg-amber-100 text-amber-700 hover:bg-amber-100" },
  WHITE_LABEL: { label: "White label", badge: "bg-slate-100 text-slate-600 hover:bg-slate-100" },
}

const GATE_META: Record<GateType, { label: string; icon: LucideIcon }> = {
  BUSINESS_VERIFIED: { label: "Business verification", icon: Building2 },
  FOOD_HYGIENE_RATING: { label: "Food hygiene rating", icon: UtensilsCrossed },
  ALLERGEN_DATA_COMPLETE: { label: "Allergen data", icon: Wheat },
}

const GATE_STATUS_META: Record<GateStatus, { label: string; badge: string; icon: LucideIcon }> = {
  PENDING: { label: "Pending", badge: "bg-amber-100 text-amber-700 hover:bg-amber-100", icon: Loader2 },
  PASSED: { label: "Passed", badge: "bg-emerald-100 text-emerald-700 hover:bg-emerald-100", icon: CheckCircle2 },
  FAILED: { label: "Failed", badge: "bg-red-100 text-red-700 hover:bg-red-100", icon: XCircle },
  MANUAL_REVIEW: { label: "Manual review", badge: "bg-amber-100 text-amber-700 hover:bg-amber-100", icon: AlertTriangle },
  WAIVED: { label: "Not required", badge: "bg-slate-100 text-slate-600 hover:bg-slate-100", icon: MinusCircle },
}

// Defensive fallbacks — an unknown gateType/status renders neutral slate, never crashes.
const GATE_FALLBACK = { label: "Check", icon: MinusCircle as LucideIcon }
const GATE_STATUS_FALLBACK = {
  label: "Unknown",
  badge: "bg-slate-100 text-slate-600 hover:bg-slate-100",
  icon: MinusCircle as LucideIcon,
}

function httpStatus(err: unknown): number | undefined {
  if (err && typeof err === "object" && "response" in err) {
    return (err as { response?: { status?: number } }).response?.status
  }
  return undefined
}

function gateSummary(app: AdminOnboardingDto): { green: number; total: number } {
  const mandatory = app.gates.filter((g) => g.mandatory)
  const green = mandatory.filter((g) => g.status === "PASSED" || g.status === "WAIVED").length
  return { green, total: mandatory.length }
}

export default function OnboardingApprovalsPage() {
  const { toast } = useToast()

  const [applications, setApplications] = useState<AdminOnboardingDto[]>([])
  // ONBD-03: review-pending queue (VERIFYING + a MANUAL_REVIEW gate) — an addition
  // alongside the existing approve/reject queue (Incremental Betterment).
  const [reviews, setReviews] = useState<AdminOnboardingDto[]>([])
  const [loading, setLoading] = useState(true)
  const [forbidden, setForbidden] = useState(false)

  // Per-action state
  const [actioning, setActioning] = useState(false)
  const [approveTarget, setApproveTarget] = useState<AdminOnboardingDto | null>(null)
  const [rejectTarget, setRejectTarget] = useState<AdminOnboardingDto | null>(null)
  const [rejectReason, setRejectReason] = useState("")

  // Gate-resolve dialog target + form state
  const [resolveTarget, setResolveTarget] = useState<{
    app: AdminOnboardingDto
    gate: GateDto
  } | null>(null)
  const [resolveDecision, setResolveDecision] = useState<ResolveGateRequest["decision"]>("PASS")
  const [resolveReason, setResolveReason] = useState("")

  const loadQueue = useCallback(async () => {
    try {
      // Both queues in parallel; a 403 on either surfaces the same forbidden state.
      const [pendingRes, reviewsRes] = await Promise.all([
        apiClient.get("/api/v1/onboarding/admin/pending"),
        apiClient.get("/api/v1/onboarding/admin/reviews"),
      ])
      setApplications(pendingRes.data ?? [])
      setReviews(reviewsRes.data ?? [])
      setForbidden(false)
    } catch (err: unknown) {
      if (httpStatus(err) === 403) {
        setForbidden(true)
      } else {
        toast({
          variant: "destructive",
          title: "Couldn't load the approval queue",
          description: "Check your connection and try again.",
        })
      }
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
    void loadQueue()
  }, [loadQueue])

  const removeFromQueue = (id: string) =>
    setApplications((apps) => apps.filter((a) => a.id !== id))

  const handleApprove = async () => {
    if (!approveTarget) return
    const target = approveTarget
    try {
      setActioning(true)
      await apiClient.post(`/api/v1/onboarding/admin/${target.id}/approve`, {})
      removeFromQueue(target.id)
      toast({
        title: "Application approved",
        description: `${target.shopName ?? "The vendor"} can now go live.`,
      })
    } catch (err: unknown) {
      // 400 = the APPROVE guard vetoed — a mandatory gate went red since submission.
      if (httpStatus(err) === 400) {
        toast({
          variant: "destructive",
          title: "Approval blocked",
          description: "A mandatory check is no longer green. Refresh the queue and review the gates.",
        })
      } else {
        toast({
          variant: "destructive",
          title: "Couldn't approve the application",
          description: err instanceof Error ? err.message : "Please try again.",
        })
      }
    } finally {
      setActioning(false)
      setApproveTarget(null)
    }
  }

  const handleReject = async () => {
    if (!rejectTarget || !rejectReason.trim()) return
    const target = rejectTarget
    try {
      setActioning(true)
      await apiClient.post(`/api/v1/onboarding/admin/${target.id}/reject`, {
        reason: rejectReason.trim(),
      })
      removeFromQueue(target.id)
      toast({
        title: "Application rejected",
        description: "The reason has been recorded on the application.",
      })
    } catch (err: unknown) {
      toast({
        variant: "destructive",
        title: "Couldn't reject the application",
        description: err instanceof Error ? err.message : "Please try again.",
      })
    } finally {
      setActioning(false)
      setRejectTarget(null)
      setRejectReason("")
    }
  }

  const openResolve = (app: AdminOnboardingDto, gate: GateDto) => {
    setResolveTarget({ app, gate })
    setResolveDecision("PASS")
    setResolveReason("")
  }

  const closeResolve = () => {
    setResolveTarget(null)
    setResolveReason("")
    setResolveDecision("PASS")
  }

  // ONBD-03 (D-01 interim resolver): unstick a MANUAL_REVIEW/FAILED gate. The backend
  // writes only the gate row then recomputes; the state machine advances itself.
  // FAIL requires a reason (also enforced server-side).
  const handleResolveGate = async () => {
    if (!resolveTarget) return
    if (resolveDecision === "FAIL" && !resolveReason.trim()) return
    const { app, gate } = resolveTarget
    try {
      setActioning(true)
      const body: ResolveGateRequest = {
        decision: resolveDecision,
        reason: resolveReason.trim() || undefined,
      }
      await apiClient.post(
        `/api/v1/onboarding/admin/${app.id}/gates/${gate.gateType}/resolve`,
        body
      )
      toast({
        title: "Check resolved",
        description: `${GATE_META[gate.gateType]?.label ?? "The check"} was updated.`,
      })
      // Refresh both queues — the application may leave manual review (or advance).
      await loadQueue()
    } catch (err: unknown) {
      toast({
        variant: "destructive",
        title: "Couldn't resolve the check",
        description: err instanceof Error ? err.message : "Please try again.",
      })
    } finally {
      setActioning(false)
      closeResolve()
    }
  }

  // --- Render: loading / access denied ----------------------------------------

  /*
   * WIDTH TIER — Index, and this is the PHASE'S LOWEST-CONFIDENCE tier call.
   * Flagged for the human-verification pass in plan 35-13; if it looks wrong on a
   * real screen, this is the surface to change.
   *
   * PATTERNS A-8. The case genuinely cuts both ways. It is a QUEUE, which is the
   * resource-index case by name — but each entry is a review card carrying
   * narrative gate reasons, and prose wants a reading measure, not columns.
   *
   * Index is chosen deliberately rather than arrived at. The honest reason is
   * recorded rather than dressed up: the reading tier is the only dashboard tier
   * that would need a new wrapper element here, and a silent default would have
   * shipped this page as Index regardless — so choosing Index explicitly is the
   * truthful version of the same outcome, and it leaves a marker a later reader
   * can find and argue with instead of a silence they cannot.
   *
   * The tier is written into the DOM as a declaration rather than left as the
   * absence of a cap, because "uncapped" and "someone forgot to cap it" render
   * identically and no assertion can tell them apart — ORCH-03 (orchestrator
   * decision, 2026-08-29). It is declared on EVERY render branch below, not just
   * the loaded one: a branch without it is an undeclared first paint.
   */
  if (loading) {
    return (
      <div data-width-tier="index" className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-blue-600" />
      </div>
    )
  }

  if (forbidden) {
    return (
      <div data-width-tier="index" className="space-y-6">
        <Header />
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-12 text-center">
            <ShieldCheck className="mb-4 h-12 w-12 text-slate-300" />
            <h3 className="mb-2 text-lg font-semibold text-slate-900">Admin access required</h3>
            <p className="text-sm text-slate-500">
              Reviewing onboarding applications needs the admin role. Ask your administrator for access.
            </p>
          </CardContent>
        </Card>
      </div>
    )
  }

  // --- Render: queue ------------------------------------------------------------

  const nothingWaiting = applications.length === 0 && reviews.length === 0

  return (
    <div data-width-tier="index" className="space-y-8">
      <Header />

      {/* Manual-review queue (ONBD-03): VERIFYING + MANUAL_REVIEW applications an
          admin can unstick by resolving the flagged gate (interim resolver, D-01). */}
      {reviews.length > 0 && (
        <section className="space-y-4">
          <div>
            <h2 className="text-lg font-semibold text-slate-900">In manual review</h2>
            <p className="text-sm text-slate-600">
              These applications have a check that needs a human decision. Resolve the flagged gate
              to let the checks continue.
            </p>
          </div>
          {reviews.map((app) => (
            <ReviewCard
              key={app.id}
              app={app}
              onResolve={(gate) => openResolve(app, gate)}
              disabled={actioning}
            />
          ))}
        </section>
      )}

      {nothingWaiting ? (
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-12 text-center">
            <Inbox className="mb-4 h-12 w-12 text-slate-300" />
            <h3 className="mb-2 text-lg font-semibold text-slate-900">No applications waiting</h3>
            <p className="text-sm text-slate-500">
              Applications appear here when their compliance checks pass and a human decision is needed.
            </p>
          </CardContent>
        </Card>
      ) : applications.length > 0 ? (
        <section className="space-y-4">
          {reviews.length > 0 && (
            <h2 className="text-lg font-semibold text-slate-900">Awaiting approval</h2>
          )}
          {applications.map((app) => {
            const summary = gateSummary(app)
            const allGreen = summary.green === summary.total && summary.total > 0
            return (
              <m.div key={app.id} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}>
                <Card>
                  <CardHeader className="pb-3">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <div className="flex items-center gap-3">
                        <CardTitle className="text-lg">
                          {app.shopName ?? "Unnamed shop"}
                        </CardTitle>
                        <Badge className={`${MODEL_META[app.model]?.badge ?? GATE_STATUS_FALLBACK.badge} pointer-events-none`}>
                          {MODEL_META[app.model]?.label ?? app.model}
                        </Badge>
                      </div>
                      <div className="flex items-center gap-2">
                        <Button
                          variant="outline"
                          className="border-red-200 text-red-700 hover:bg-red-50 hover:text-red-800"
                          onClick={() => setRejectTarget(app)}
                          disabled={actioning}
                        >
                          Reject
                        </Button>
                        <Button
                          className="bg-emerald-600 text-white hover:bg-emerald-700"
                          onClick={() => setApproveTarget(app)}
                          disabled={actioning}
                        >
                          Approve
                        </Button>
                      </div>
                    </div>
                    <CardDescription>
                      {app.submittedAt
                        ? `Submitted ${formatDistanceToNow(new Date(app.submittedAt), { addSuffix: true })}`
                        : "Submission date unknown"}
                      {app.companyNumber ? ` · Company no. ${app.companyNumber}` : " · Sole trader"}
                      {" · "}
                      <span className={allGreen ? "font-medium text-emerald-700" : "font-medium text-amber-700"}>
                        {summary.green}/{summary.total} required checks green
                      </span>
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    <div className="flex flex-wrap gap-2">
                      {app.gates.map((gate) => {
                        const typeMeta = GATE_META[gate.gateType] ?? GATE_FALLBACK
                        const statusMeta = GATE_STATUS_META[gate.status] ?? GATE_STATUS_FALLBACK
                        const StatusIcon = statusMeta.icon
                        return (
                          <Badge
                            key={gate.gateType}
                            className={`${statusMeta.badge} pointer-events-none`}
                            title={gate.reason ?? undefined}
                          >
                            <StatusIcon className="mr-1 h-3 w-3" />
                            {typeMeta.label}: {statusMeta.label}
                          </Badge>
                        )
                      })}
                    </div>
                  </CardContent>
                </Card>
              </m.div>
            )
          })}
        </section>
      ) : null}

      {/* Approve confirmation dialog */}
      <Dialog open={approveTarget !== null} onOpenChange={(open) => !open && setApproveTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Approve this application?</DialogTitle>
            <DialogDescription>
              {approveTarget?.shopName ?? "This vendor"} will be able to publish their storefront.
              The compliance gates are re-checked before approval is granted.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setApproveTarget(null)} disabled={actioning}>
              Cancel
            </Button>
            <Button
              className="bg-emerald-600 text-white hover:bg-emerald-700"
              onClick={handleApprove}
              disabled={actioning}
            >
              {actioning ? "Approving…" : "Approve"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Reject dialog — the reason is REQUIRED (persisted + audited server-side) */}
      <Dialog
        open={rejectTarget !== null}
        onOpenChange={(open) => {
          if (!open) {
            setRejectTarget(null)
            setRejectReason("")
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Reject this application?</DialogTitle>
            <DialogDescription>
              Rejection is final for this application. A reason is required — it is recorded on the
              application and kept in the audit history.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="reject-reason" className="font-normal">
              Reason
            </Label>
            <textarea
              id="reject-reason"
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
              maxLength={500}
              rows={3}
              placeholder="e.g. Hygiene evidence inconsistent with the registered premises"
              className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            />
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => {
                setRejectTarget(null)
                setRejectReason("")
              }}
              disabled={actioning}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleReject}
              disabled={actioning || !rejectReason.trim()}
            >
              {actioning ? "Rejecting…" : "Reject application"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Gate-resolve dialog — decision (PASS/WAIVE/FAIL) + reason; FAIL requires a reason */}
      <Dialog open={resolveTarget !== null} onOpenChange={(open) => !open && closeResolve()}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Resolve this check?</DialogTitle>
            <DialogDescription>
              {resolveTarget
                ? `${GATE_META[resolveTarget.gate.gateType]?.label ?? "This check"} for ${
                    resolveTarget.app.shopName ?? "this vendor"
                  }. `
                : ""}
              Choose an outcome. A reason is required when you fail a check; it is recorded in the
              audit history.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="resolve-decision" className="font-normal">
                Decision
              </Label>
              <select
                id="resolve-decision"
                value={resolveDecision}
                onChange={(e) =>
                  setResolveDecision(e.target.value as ResolveGateRequest["decision"])
                }
                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <option value="PASS">Pass — mark this check satisfied</option>
                <option value="WAIVE">Waive — not required for this vendor</option>
                <option value="FAIL">Fail — send back for action</option>
              </select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="resolve-reason" className="font-normal">
                Reason{resolveDecision === "FAIL" ? " (required)" : " (optional)"}
              </Label>
              <textarea
                id="resolve-reason"
                value={resolveReason}
                onChange={(e) => setResolveReason(e.target.value)}
                maxLength={500}
                rows={3}
                placeholder="e.g. Verified the FHRS rating manually against the FSA register"
                className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={closeResolve} disabled={actioning}>
              Cancel
            </Button>
            <Button
              onClick={handleResolveGate}
              disabled={actioning || (resolveDecision === "FAIL" && !resolveReason.trim())}
            >
              {actioning ? "Saving…" : "Resolve check"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function Header() {
  return (
    <m.div initial={{ opacity: 0, y: -20 }} animate={{ opacity: 1, y: 0 }}>
      <div className="flex items-center gap-3">
        <h1 className="text-4xl font-semibold text-slate-900">Approvals</h1>
      </div>
      <p className="mt-2 text-sm text-slate-600">
        Onboarding applications whose checks passed and now need a human decision. Marketplace
        vendors always require approval before they can go live.
      </p>
    </m.div>
  )
}

// --- Review-pending card ------------------------------------------------------
// A VERIFYING application whose gate chain parked on a MANUAL_REVIEW (or FAILED)
// gate. Reuses the same gate vocabulary as the approve/reject queue and offers a
// per-gate "Resolve" control that opens the gate-resolve dialog.

function ReviewCard({
  app,
  onResolve,
  disabled,
}: {
  app: AdminOnboardingDto
  onResolve: (gate: GateDto) => void
  disabled: boolean
}) {
  const summary = gateSummary(app)
  const resolvable = app.gates.filter(
    (g) => g.status === "MANUAL_REVIEW" || g.status === "FAILED"
  )

  return (
    <m.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}>
      <Card>
        <CardHeader className="pb-3">
          <div className="flex flex-wrap items-center gap-3">
            <CardTitle className="text-lg">{app.shopName ?? "Unnamed shop"}</CardTitle>
            <Badge
              className={`${MODEL_META[app.model]?.badge ?? GATE_STATUS_FALLBACK.badge} pointer-events-none`}
            >
              {MODEL_META[app.model]?.label ?? app.model}
            </Badge>
          </div>
          <CardDescription>
            {app.submittedAt
              ? `Submitted ${formatDistanceToNow(new Date(app.submittedAt), { addSuffix: true })}`
              : "Submission date unknown"}
            {app.companyNumber ? ` · Company no. ${app.companyNumber}` : " · Sole trader"}
            {" · "}
            <span className="font-medium text-amber-700">
              {summary.green}/{summary.total} required checks green
            </span>
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap gap-2">
            {app.gates.map((gate) => {
              const typeMeta = GATE_META[gate.gateType] ?? GATE_FALLBACK
              const statusMeta = GATE_STATUS_META[gate.status] ?? GATE_STATUS_FALLBACK
              const StatusIcon = statusMeta.icon
              return (
                <Badge
                  key={gate.gateType}
                  className={`${statusMeta.badge} pointer-events-none`}
                  title={gate.reason ?? undefined}
                >
                  <StatusIcon className="mr-1 h-3 w-3" />
                  {typeMeta.label}: {statusMeta.label}
                </Badge>
              )
            })}
          </div>
          {resolvable.length > 0 && (
            <div className="flex flex-wrap gap-2 border-t border-slate-100 pt-3">
              {resolvable.map((gate) => (
                <Button
                  key={gate.gateType}
                  variant="outline"
                  size="sm"
                  onClick={() => onResolve(gate)}
                  disabled={disabled}
                >
                  Resolve {GATE_META[gate.gateType]?.label ?? "check"}
                </Button>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </m.div>
  )
}
