"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { motion } from "framer-motion"
import { formatDistanceToNow } from "date-fns"
import {
  Webhook,
  Plus,
  Eye,
  Pause,
  Play,
  RotateCcw,
  Ban,
  MoreHorizontal,
} from "lucide-react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { useToast } from "@/hooks/use-toast"
import {
  webhooksApi,
  extractErrorDetail,
  EVENT_TYPE_META,
  type WebhookSubscription,
} from "@/lib/webhooks-api"
import { SubscriptionStatusBadge } from "@/components/dashboard/webhooks/status-badge"
import { WebhookCreateDialog } from "@/components/dashboard/webhooks/WebhookCreateDialog"
import { SecretRevealDialog } from "@/components/dashboard/webhooks/SecretRevealDialog"
import { ConfirmActionDialog } from "@/components/dashboard/webhooks/ConfirmActionDialog"

// Config-injected retention window for the revoke copy (GLOBAL_RULE_6) —
// mirrors the backend webhook.delivery.retention-days default (22-05).
const RETENTION_DAYS = process.env.NEXT_PUBLIC_WEBHOOK_RETENTION_DAYS ?? "30"

function eventsSummary(sub: WebhookSubscription): string {
  if (!sub.eventTypes || sub.eventTypes.length === 0) return "None"
  const first = EVENT_TYPE_META[sub.eventTypes[0]]?.family ?? sub.eventTypes[0]
  const rest = sub.eventTypes.length - 1
  return rest > 0 ? `${first} +${rest}` : first
}

type ConfirmState =
  | { kind: "rotate"; sub: WebhookSubscription }
  | { kind: "revoke"; sub: WebhookSubscription }
  | null

export default function WebhooksPage() {
  const { toast } = useToast()
  const [subscriptions, setSubscriptions] = useState<WebhookSubscription[]>([])
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState<string | null>(null)

  const [createOpen, setCreateOpen] = useState(false)
  const [secret, setSecret] = useState<string | null>(null)
  const [secretOpen, setSecretOpen] = useState(false)
  const [confirm, setConfirm] = useState<ConfirmState>(null)

  const fetchSubscriptions = async () => {
    try {
      setLoading(true)
      const data = await webhooksApi.list()
      setSubscriptions(data)
    } catch (err: unknown) {
      toast({
        variant: "destructive",
        title: "Error loading webhooks",
        description: extractErrorDetail(
          err,
          "Couldn't load webhooks — check your connection and try again."
        ),
      })
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchSubscriptions()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handlePauseResume = async (sub: WebhookSubscription) => {
    const resume = sub.status !== "ACTIVE"
    try {
      setBusyId(sub.id)
      if (resume) await webhooksApi.resume(sub.id)
      else await webhooksApi.pause(sub.id)
      toast({
        title: resume ? "Delivery resumed" : "Delivery paused",
        description: resume
          ? "This endpoint will receive new events again."
          : "This endpoint will stop receiving new events.",
      })
      await fetchSubscriptions()
    } catch (err: unknown) {
      toast({
        variant: "destructive",
        title: "Action failed",
        description: extractErrorDetail(err, "Please try again."),
      })
    } finally {
      setBusyId(null)
    }
  }

  const onConfirm = async () => {
    if (!confirm) return
    const { kind, sub } = confirm
    try {
      if (kind === "rotate") {
        const res = await webhooksApi.rotateSecret(sub.id)
        setConfirm(null)
        setSecret(res.signingSecret)
        setSecretOpen(true)
      } else {
        await webhooksApi.revoke(sub.id)
        setConfirm(null)
        toast({
          title: "Endpoint revoked",
          description: "All deliveries to this endpoint have stopped.",
        })
      }
      await fetchSubscriptions()
    } catch (err: unknown) {
      setConfirm(null)
      toast({
        variant: "destructive",
        title: kind === "rotate" ? "Rotate failed" : "Revoke failed",
        description: extractErrorDetail(err, "Please try again."),
      })
    }
  }

  const onCreated = (created: { signingSecret: string }) => {
    setSecret(created.signingSecret)
    setSecretOpen(true)
    toast({ title: "Endpoint added", description: "Your webhook endpoint is live." })
    fetchSubscriptions()
  }

  // Shared action controls (used by both the table row and the mobile card).
  const ActionButtons = ({
    sub,
    layout,
  }: {
    sub: WebhookSubscription
    layout: "row" | "card"
  }) => {
    if (sub.status === "REVOKED") {
      return (
        <Button asChild variant="outline" size="sm" className="min-h-11 sm:min-h-0">
          <Link href={`/dashboard/webhooks/${sub.id}`}>
            <Eye className="mr-1 h-4 w-4" /> View
          </Link>
        </Button>
      )
    }
    const resume = sub.status !== "ACTIVE"
    return (
      <div
        className={
          layout === "card"
            ? "flex flex-wrap gap-2"
            : "flex items-center justify-end gap-2"
        }
      >
        <Button asChild variant="outline" size="sm" className="min-h-11 sm:min-h-0">
          <Link href={`/dashboard/webhooks/${sub.id}`}>
            <Eye className="mr-1 h-4 w-4" /> View
          </Link>
        </Button>
        <Button
          variant="outline"
          size="sm"
          className="min-h-11 sm:min-h-0"
          disabled={busyId === sub.id}
          onClick={() => handlePauseResume(sub)}
        >
          {resume ? (
            <>
              <Play className="mr-1 h-4 w-4" /> Resume
            </>
          ) : (
            <>
              <Pause className="mr-1 h-4 w-4" /> Pause
            </>
          )}
        </Button>
        {layout === "card" ? (
          <>
            <Button
              variant="outline"
              size="sm"
              className="min-h-11"
              onClick={() => setConfirm({ kind: "rotate", sub })}
            >
              <RotateCcw className="mr-1 h-4 w-4" /> Rotate
            </Button>
            <Button
              variant="outline"
              size="sm"
              className="min-h-11 text-red-600 hover:bg-red-50 hover:text-red-700"
              onClick={() => setConfirm({ kind: "revoke", sub })}
            >
              <Ban className="mr-1 h-4 w-4" /> Revoke
            </Button>
          </>
        ) : (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="sm" aria-label="More actions">
                <MoreHorizontal className="h-4 w-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onClick={() => setConfirm({ kind: "rotate", sub })}>
                <RotateCcw className="mr-2 h-4 w-4" /> Rotate secret
              </DropdownMenuItem>
              <DropdownMenuItem
                className="text-red-600 focus:text-red-700"
                onClick={() => setConfirm({ kind: "revoke", sub })}
              >
                <Ban className="mr-2 h-4 w-4" /> Revoke
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </div>
    )
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-12 w-12 animate-spin rounded-full border-b-2 border-t-2 border-orange-500" />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header — stacks on mobile so the CTA never collides with the title */}
      <motion.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="flex flex-col items-start justify-between gap-4 sm:flex-row sm:items-center"
      >
        <div>
          <h1 className="text-2xl font-semibold text-slate-900 sm:text-3xl">
            Webhooks
          </h1>
          <p className="mt-1 text-sm text-slate-600">
            Send signed events to your own systems.
          </p>
        </div>
        <Button
          onClick={() => setCreateOpen(true)}
          className="w-full gap-2 bg-orange-500 text-white hover:bg-orange-600 sm:w-auto"
        >
          <Plus className="h-4 w-4" />
          Add endpoint
        </Button>
      </motion.div>

      <Card>
        <CardHeader>
          <CardTitle className="text-lg">Endpoints</CardTitle>
        </CardHeader>
        <CardContent>
          {subscriptions.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <Webhook className="mb-4 h-12 w-12 text-slate-300" />
              <h3 className="mb-2 text-lg font-semibold text-slate-900">
                No webhook endpoints yet
              </h3>
              <p className="mb-4 max-w-md text-sm text-slate-500">
                Register an HTTPS endpoint to receive signed, real-time events for
                orders, onboarding, payments and refunds.
              </p>
              <Button onClick={() => setCreateOpen(true)} variant="outline">
                <Plus className="mr-2 h-4 w-4" />
                Add endpoint
              </Button>
            </div>
          ) : (
            <>
              {/* sm+ : table (wide content scrolls inside its own container) */}
              <div
                className="hidden overflow-x-auto sm:block"
                data-testid="webhooks-table"
              >
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Endpoint URL</TableHead>
                      <TableHead>Events</TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead>Updated</TableHead>
                      <TableHead className="text-right">Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {subscriptions.map((sub) => (
                      <TableRow key={sub.id}>
                        <TableCell className="max-w-[260px] truncate font-mono text-xs">
                          {sub.targetUrl}
                        </TableCell>
                        <TableCell className="text-sm text-slate-600">
                          {eventsSummary(sub)}
                        </TableCell>
                        <TableCell>
                          <SubscriptionStatusBadge status={sub.status} />
                        </TableCell>
                        <TableCell className="text-sm text-slate-600">
                          {formatDistanceToNow(new Date(sub.updatedAt), {
                            addSuffix: true,
                          })}
                        </TableCell>
                        <TableCell className="text-right">
                          <ActionButtons sub={sub} layout="row" />
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>

              {/* < sm : card-stacking (the hard 375px contract) */}
              <div className="space-y-3 sm:hidden" data-testid="webhooks-cards">
                {subscriptions.map((sub) => (
                  <div
                    key={sub.id}
                    className="rounded-lg border border-slate-200 p-4"
                  >
                    <p className="break-all font-mono text-xs text-slate-900">
                      {sub.targetUrl}
                    </p>
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      <SubscriptionStatusBadge status={sub.status} />
                      <span className="text-xs text-slate-500">
                        {eventsSummary(sub)} ·{" "}
                        {formatDistanceToNow(new Date(sub.updatedAt), {
                          addSuffix: true,
                        })}
                      </span>
                    </div>
                    <div className="mt-3">
                      <ActionButtons sub={sub} layout="card" />
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <WebhookCreateDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={onCreated}
      />

      <SecretRevealDialog
        open={secretOpen}
        onOpenChange={(o) => {
          setSecretOpen(o)
          if (!o) setSecret(null)
        }}
        secret={secret}
      />

      <ConfirmActionDialog
        open={confirm?.kind === "rotate"}
        onOpenChange={(o) => !o && setConfirm(null)}
        title="Rotate signing secret?"
        description="The current secret stops working immediately. Update your endpoint with the new secret to keep verifying signatures — in-flight deliveries signed with the old secret will fail verification."
        confirmLabel="Rotate secret"
        onConfirm={onConfirm}
      />

      <ConfirmActionDialog
        open={confirm?.kind === "revoke"}
        onOpenChange={(o) => !o && setConfirm(null)}
        title="Revoke this endpoint?"
        description={
          confirm?.kind === "revoke"
            ? `Revoking permanently stops all deliveries to ${confirm.sub.targetUrl} and can't be undone. Delivery history is kept for ${RETENTION_DAYS} days.`
            : ""
        }
        confirmLabel="Revoke endpoint"
        destructive
        onConfirm={onConfirm}
      />
    </div>
  )
}
