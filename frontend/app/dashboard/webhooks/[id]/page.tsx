"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { useParams } from "next/navigation"
import { m } from "framer-motion"
import { formatDistanceToNow, format } from "date-fns"
import {
  ArrowLeft,
  RotateCcw,
  Pause,
  Play,
  Ban,
  AlertTriangle,
} from "lucide-react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Pagination } from "@/components/ui/pagination"
import { useToast } from "@/hooks/use-toast"
import {
  webhooksApi,
  extractErrorDetail,
  makeIdempotencyKey,
  EVENT_TYPE_META,
  type WebhookSubscription,
  type WebhookDelivery,
  type DeliveryStatus,
} from "@/lib/webhooks-api"
import {
  SubscriptionStatusBadge,
  DeliveryStatusBadge,
  ReplayTag,
} from "@/components/dashboard/webhooks/status-badge"
import { SecretRevealDialog } from "@/components/dashboard/webhooks/SecretRevealDialog"
import { ConfirmActionDialog } from "@/components/dashboard/webhooks/ConfirmActionDialog"

const PAGE_SIZE = 20
const RETENTION_DAYS = process.env.NEXT_PUBLIC_WEBHOOK_RETENTION_DAYS ?? "30"

const DELIVERY_STATUSES: DeliveryStatus[] = [
  "PENDING",
  "DELIVERED",
  "RETRYING",
  "FAILED",
]

type ConfirmKind = "rotate" | "revoke" | null

export default function WebhookDetailPage() {
  const params = useParams<{ id: string }>()
  const id = params?.id as string
  const { toast } = useToast()

  const [subscription, setSubscription] = useState<WebhookSubscription | null>(null)
  const [subError, setSubError] = useState(false)
  const [loadingSub, setLoadingSub] = useState(true)

  const [deliveries, setDeliveries] = useState<WebhookDelivery[]>([])
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [page, setPage] = useState(0)
  const [statusFilter, setStatusFilter] = useState<string>("ALL")
  const [eventTypeFilter, setEventTypeFilter] = useState<string>("ALL")

  const [confirm, setConfirm] = useState<ConfirmKind>(null)
  const [replayTarget, setReplayTarget] = useState<WebhookDelivery | null>(null)
  const [secret, setSecret] = useState<string | null>(null)
  const [secretOpen, setSecretOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  const fetchSubscription = useCallback(async () => {
    if (!id) return
    try {
      setLoadingSub(true)
      setSubError(false)
      setSubscription(await webhooksApi.get(id))
    } catch (err: unknown) {
      setSubError(true)
      toast({
        variant: "destructive",
        title: "Error loading endpoint",
        description: extractErrorDetail(
          err,
          "Couldn't load this endpoint — check your connection and try again."
        ),
      })
    } finally {
      setLoadingSub(false)
    }
  }, [id, toast])

  const fetchDeliveries = useCallback(async () => {
    if (!id) return
    try {
      const res = await webhooksApi.listDeliveries(id, {
        status: statusFilter === "ALL" ? null : (statusFilter as DeliveryStatus),
        eventType: eventTypeFilter === "ALL" ? null : eventTypeFilter,
        page,
        size: PAGE_SIZE,
      })
      setDeliveries(res.content ?? [])
      setTotalPages(res.totalPages ?? 0)
      setTotalElements(res.totalElements ?? 0)
    } catch (err: unknown) {
      toast({
        variant: "destructive",
        title: "Error loading deliveries",
        description: extractErrorDetail(
          err,
          "Couldn't load deliveries — check your connection and try again."
        ),
      })
    }
  }, [id, statusFilter, eventTypeFilter, page, toast])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
    fetchSubscription()
  }, [fetchSubscription])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
    fetchDeliveries()
  }, [fetchDeliveries])

  const filtersActive = statusFilter !== "ALL" || eventTypeFilter !== "ALL"

  // Most recent delivery that recorded an error backs the auto-pause reason line.
  const lastFailure = deliveries.find((d) => d.lastError)

  const handlePauseResume = async () => {
    if (!subscription) return
    const resume = subscription.status !== "ACTIVE"
    try {
      setBusy(true)
      if (resume) await webhooksApi.resume(subscription.id)
      else await webhooksApi.pause(subscription.id)
      toast({
        title: resume ? "Delivery resumed" : "Delivery paused",
        description: resume
          ? "This endpoint will receive new events again."
          : "This endpoint will stop receiving new events.",
      })
      await fetchSubscription()
    } catch (err: unknown) {
      toast({
        variant: "destructive",
        title: "Action failed",
        description: extractErrorDetail(err, "Please try again."),
      })
    } finally {
      setBusy(false)
    }
  }

  const onConfirmAction = async () => {
    if (!subscription || !confirm) return
    try {
      if (confirm === "rotate") {
        const res = await webhooksApi.rotateSecret(subscription.id)
        setConfirm(null)
        setSecret(res.signingSecret)
        setSecretOpen(true)
      } else {
        await webhooksApi.revoke(subscription.id)
        setConfirm(null)
        toast({
          title: "Endpoint revoked",
          description: "All deliveries to this endpoint have stopped.",
        })
      }
      await fetchSubscription()
    } catch (err: unknown) {
      setConfirm(null)
      toast({
        variant: "destructive",
        title: confirm === "rotate" ? "Rotate failed" : "Revoke failed",
        description: extractErrorDetail(err, "Please try again."),
      })
    }
  }

  const onConfirmReplay = async () => {
    if (!replayTarget || !subscription) return
    try {
      await webhooksApi.replay(
        subscription.id,
        replayTarget.id,
        makeIdempotencyKey()
      )
      setReplayTarget(null)
      toast({
        title: "Replay queued",
        description: "Replay queued — the new attempt appears in the log shortly.",
      })
      await fetchDeliveries()
    } catch (err: unknown) {
      setReplayTarget(null)
      toast({
        variant: "destructive",
        title: "Replay failed",
        description: extractErrorDetail(err, "Please try again."),
      })
    }
  }

  const clearFilters = () => {
    setStatusFilter("ALL")
    setEventTypeFilter("ALL")
    setPage(0)
  }

  /*
   * WIDTH TIER — Index. READ THIS BEFORE "CORRECTING" IT.
   *
   * This is a bracketed `[id]` DETAIL route, and it deliberately does NOT take
   * the same tier as the other bracketed routes in the dashboard. That looks like
   * an inconsistency and is not one. It is PATTERNS A-3, and it is the single
   * case in phase 35 that proves a width tier must be DECLARED rather than
   * inferred from a route's shape — because inferring it from the route gets this
   * page wrong.
   *
   * The reason anyone opens this page is the DELIVERY LOG: a wide, multi-column,
   * timestamp-heavy table (event id, event type, status, attempts, HTTP status,
   * last error, next attempt, actions) that already carries its own horizontal
   * scroll region. The subscription summary card above it is a header, not the
   * content. Narrowing this page to the reading width would make its log table
   * scroll MORE than it does at today's 1400px band — a strict regression on the
   * page's primary job, dressed up as a consistency fix.
   *
   * So: tier by what the page HOLDS, not by what its route looks like.
   *
   * The tier is written into the DOM as a declaration rather than left as the
   * absence of a cap, because "uncapped" and "someone forgot to cap it" render
   * identically and no assertion can tell them apart — ORCH-03 (orchestrator
   * decision, 2026-08-29). All three render branches declare it; a branch without
   * it is an undeclared first paint.
   *
   * The value here is the one in the phase that would survive longest if it were
   * wrong — both tiers render plausibly on this page — so it is the value whose
   * fail direction plan 35-04 armed and recorded, and it is asserted in
   * `../__tests__/delivery-log.test.tsx` both positively and against the tier a
   * route-shape correction would reach for.
   */
  if (loadingSub) {
    return (
      <div data-width-tier="index" className="flex h-full items-center justify-center">
        <div className="h-12 w-12 animate-spin rounded-full border-b-2 border-t-2 border-orange-500" />
      </div>
    )
  }

  if (subError || !subscription) {
    return (
      <div data-width-tier="index" className="space-y-6">
        <Link
          href="/dashboard/webhooks"
          className="inline-flex items-center gap-1 text-sm text-slate-600 hover:text-slate-900"
        >
          <ArrowLeft className="h-4 w-4" /> Back to webhooks
        </Link>
        <Card>
          <CardContent className="py-12 text-center text-sm text-slate-500">
            Couldn&apos;t load this endpoint. Go back and try again.
          </CardContent>
        </Card>
      </div>
    )
  }

  const isRevoked = subscription.status === "REVOKED"
  const resume = subscription.status !== "ACTIVE"

  return (
    <div data-width-tier="index" className="space-y-6">
      <Link
        href="/dashboard/webhooks"
        className="inline-flex items-center gap-1 text-sm text-slate-600 hover:text-slate-900"
      >
        <ArrowLeft className="h-4 w-4" /> Back to webhooks
      </Link>

      {/* Summary card */}
      <m.div initial={{ opacity: 0, y: -10 }} animate={{ opacity: 1, y: 0 }}>
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Endpoint</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <p className="break-all font-mono text-xs text-slate-900">
              {subscription.targetUrl}
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <SubscriptionStatusBadge status={subscription.status} />
              {subscription.eventTypes.map((et) => (
                <Badge key={et} variant="outline" className="text-slate-600">
                  {EVENT_TYPE_META[et]?.family ?? et}
                </Badge>
              ))}
            </div>
            <p className="text-xs text-slate-500">
              Created {format(new Date(subscription.createdAt), "PPp")}
            </p>
            {!isRevoked && (
              <div className="flex flex-wrap gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  className="min-h-11 sm:min-h-0"
                  disabled={busy}
                  onClick={handlePauseResume}
                >
                  {resume ? (
                    <>
                      <Play className="mr-1 h-4 w-4" /> Resume delivery
                    </>
                  ) : (
                    <>
                      <Pause className="mr-1 h-4 w-4" /> Pause delivery
                    </>
                  )}
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  className="min-h-11 sm:min-h-0"
                  onClick={() => setConfirm("rotate")}
                >
                  <RotateCcw className="mr-1 h-4 w-4" /> Rotate secret
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  className="min-h-11 text-red-600 hover:bg-red-50 hover:text-red-700 sm:min-h-0"
                  onClick={() => setConfirm("revoke")}
                >
                  <Ban className="mr-1 h-4 w-4" /> Revoke
                </Button>
              </div>
            )}
          </CardContent>
        </Card>
      </m.div>

      {/* Auto-pause amber alert */}
      {subscription.status === "AUTO_PAUSED" && (
        <div
          role="status"
          className="rounded-md border border-amber-200 bg-amber-50 p-4 text-amber-800"
        >
          <div className="flex items-start gap-2">
            <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" />
            <div className="space-y-2">
              <p className="text-sm">
                Auto-paused after {subscription.consecutiveFailures} consecutive
                failures. We stopped sending to protect your endpoint. Fix the
                endpoint, then resume delivery.
              </p>
              {lastFailure && (
                <p className="text-xs">
                  Last error: {lastFailure.lastError}
                  {lastFailure.lastHttpStatus != null &&
                    ` · HTTP ${lastFailure.lastHttpStatus}`}{" "}
                  ·{" "}
                  {formatDistanceToNow(new Date(lastFailure.updatedAt), {
                    addSuffix: true,
                  })}
                </p>
              )}
              <Button
                size="sm"
                className="min-h-11 bg-orange-700 text-white hover:bg-orange-800 sm:min-h-0"
                disabled={busy}
                onClick={handlePauseResume}
              >
                <Play className="mr-1 h-4 w-4" /> Resume delivery
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Delivery-log browser */}
      <Card>
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between sm:space-y-0">
          <CardTitle className="text-lg">Delivery log</CardTitle>
          <div className="flex flex-col gap-2 sm:flex-row">
            <Select
              value={eventTypeFilter}
              onValueChange={(v) => {
                setEventTypeFilter(v)
                setPage(0)
              }}
            >
              <SelectTrigger
                aria-label="Filter by event type"
                className="w-full sm:w-[180px]"
              >
                <SelectValue placeholder="Event type" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">All events</SelectItem>
                {subscription.eventTypes.map((et) => (
                  <SelectItem key={et} value={et}>
                    {EVENT_TYPE_META[et]?.family ?? et}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Select
              value={statusFilter}
              onValueChange={(v) => {
                setStatusFilter(v)
                setPage(0)
              }}
            >
              <SelectTrigger
                aria-label="Filter by status"
                className="w-full sm:w-[180px]"
              >
                <SelectValue placeholder="Status" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">All statuses</SelectItem>
                {DELIVERY_STATUSES.map((s) => (
                  <SelectItem key={s} value={s}>
                    {s.charAt(0) + s.slice(1).toLowerCase()}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </CardHeader>
        <CardContent>
          {deliveries.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              {filtersActive ? (
                <>
                  <p className="mb-4 text-sm text-slate-500">
                    No deliveries match these filters.
                  </p>
                  <Button variant="outline" onClick={clearFilters}>
                    Clear filters
                  </Button>
                </>
              ) : (
                <>
                  <h3 className="mb-2 text-lg font-semibold text-slate-900">
                    No deliveries yet
                  </h3>
                  <p className="max-w-md text-sm text-slate-500">
                    Deliveries appear here once an event matches this
                    endpoint&apos;s selected types.
                  </p>
                </>
              )}
            </div>
          ) : (
            <>
              {/* sm+ : table scrolls inside its own container */}
              <div
                className="hidden sm:block"
                data-testid="deliveries-table"
              >
                <Table containerLabel="Deliveries table">
                  <TableHeader>
                    <TableRow>
                      <TableHead>Event type</TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead>Attempts</TableHead>
                      <TableHead>When</TableHead>
                      <TableHead className="text-right">Action</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {deliveries.map((d) => (
                      <TableRow key={d.id}>
                        <TableCell className="font-mono text-xs">
                          {d.eventType}
                        </TableCell>
                        <TableCell>
                          <div className="flex flex-wrap items-center gap-2">
                            <DeliveryStatusBadge status={d.status} />
                            {d.lastHttpStatus != null && (
                              <span className="font-mono text-xs text-slate-500">
                                {d.lastHttpStatus}
                              </span>
                            )}
                            {d.replay && <ReplayTag />}
                          </div>
                        </TableCell>
                        <TableCell className="text-sm text-slate-600">
                          {d.attemptCount}
                        </TableCell>
                        <TableCell className="text-sm text-slate-600">
                          {formatDistanceToNow(new Date(d.createdAt), {
                            addSuffix: true,
                          })}
                        </TableCell>
                        <TableCell className="text-right">
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => setReplayTarget(d)}
                          >
                            <RotateCcw className="mr-1 h-4 w-4" /> Replay
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>

              {/* < sm : card-stacking (375px contract) */}
              <div className="space-y-3 sm:hidden" data-testid="deliveries-cards">
                {deliveries.map((d) => (
                  <div
                    key={d.id}
                    className="rounded-lg border border-slate-200 p-4"
                  >
                    <p className="break-all font-mono text-xs text-slate-900">
                      {d.eventType}
                    </p>
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      <DeliveryStatusBadge status={d.status} />
                      {d.lastHttpStatus != null && (
                        <span className="font-mono text-xs text-slate-500">
                          {d.lastHttpStatus}
                        </span>
                      )}
                      {d.replay && <ReplayTag />}
                    </div>
                    <p className="mt-2 text-xs text-slate-500">
                      {d.attemptCount} attempt{d.attemptCount === 1 ? "" : "s"} ·{" "}
                      {formatDistanceToNow(new Date(d.createdAt), {
                        addSuffix: true,
                      })}
                    </p>
                    <div className="mt-3">
                      <Button
                        variant="outline"
                        size="sm"
                        className="min-h-11"
                        onClick={() => setReplayTarget(d)}
                      >
                        <RotateCcw className="mr-1 h-4 w-4" /> Replay
                      </Button>
                    </div>
                  </div>
                ))}
              </div>

              <Pagination
                currentPage={page}
                totalPages={totalPages}
                totalElements={totalElements}
                pageSize={PAGE_SIZE}
                onPageChange={setPage}
              />
            </>
          )}
        </CardContent>
      </Card>

      {/* Dialogs */}
      <SecretRevealDialog
        open={secretOpen}
        onOpenChange={(o) => {
          setSecretOpen(o)
          if (!o) setSecret(null)
        }}
        secret={secret}
      />

      <ConfirmActionDialog
        open={confirm === "rotate"}
        onOpenChange={(o) => !o && setConfirm(null)}
        title="Rotate signing secret?"
        description="The current secret stops working immediately. Update your endpoint with the new secret to keep verifying signatures — in-flight deliveries signed with the old secret will fail verification."
        confirmLabel="Rotate secret"
        onConfirm={onConfirmAction}
      />

      <ConfirmActionDialog
        open={confirm === "revoke"}
        onOpenChange={(o) => !o && setConfirm(null)}
        title="Revoke this endpoint?"
        description={`Revoking permanently stops all deliveries to ${subscription.targetUrl} and can't be undone. Delivery history is kept for ${RETENTION_DAYS} days.`}
        confirmLabel="Revoke endpoint"
        destructive
        onConfirm={onConfirmAction}
      />

      <ConfirmActionDialog
        open={replayTarget !== null}
        onOpenChange={(o) => !o && setReplayTarget(null)}
        title="Replay this delivery?"
        description={`We'll re-send this event to ${subscription.targetUrl} as a new attempt tagged "Replay". The original delivery record is unchanged.`}
        confirmLabel="Replay delivery"
        onConfirm={onConfirmReplay}
      />
    </div>
  )
}
