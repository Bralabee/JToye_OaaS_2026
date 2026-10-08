"use client"

import { useId, useState } from "react"
import { Clock } from "lucide-react"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { ConfirmActionDialog } from "@/components/dashboard/confirm-action-dialog"
import { ROLE_LABELS, type StaffInvite } from "@/lib/staff-api"

/**
 * D-07 (37-09, UI-SPEC § B1) — "Pending invitations": every link that can still be
 * used, or could be re-sent, with the two things a Group admin does to one.
 *
 * Only OPEN and EXPIRED invitations are listed. ACCEPTED ones have become access (the
 * People card shows it) and CANCELLED ones are dead; an EXPIRED one stays, greyed,
 * because it is still a person who was promised access and has not got it, until it
 * is cancelled or sent again. With nothing to list the card is not rendered at all —
 * an empty "Pending invitations" card would be furniture.
 *
 * Status comes from the server (`StaffInviteDto.status`, computed at read time); the
 * browser does not compare clocks to decide that a link has expired.
 */

export const PENDING_COPY = {
  title: "Pending invitations",
  description: "Invitations that have not been accepted yet. Times are UK time.",
  columns: ["Email", "Access offered", "Expires", "Actions"],
  expired: "Expired",
  allShops: "all shops",
  unknownShop: "Unknown shop",
  resend: "Send invitation again",
  cancel: "Cancel invitation",
  cancelTitle: "Cancel this invitation?",
  keep: "Keep invitation",
} as const

const UK_DATE_TIME = new Intl.DateTimeFormat("en-GB", {
  day: "numeric",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
  timeZone: "Europe/London",
})

/**
 * An expiry as UK time, "10 Oct, 14:32" (UI-SPEC § B1). Pinned to Europe/London, not
 * the browser's zone: the invitation email states UK time, and an operator abroad
 * must read the same moment the invitee was told.
 */
export function formatInviteExpiry(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : UK_DATE_TIME.format(d)
}

/** "Shop manager · Mama Ade's Kitchen", or "Group admin · all shops". */
export function accessOffered(
  invite: Pick<StaffInvite, "role" | "shopId">,
  shopNameById: Map<string, string>
): string {
  const role = ROLE_LABELS[invite.role] ?? invite.role
  const shop = invite.shopId
    ? shopNameById.get(invite.shopId) ?? PENDING_COPY.unknownShop
    : PENDING_COPY.allShops
  return `${role} · ${shop}`
}

/** The invitations this card lists: the ones that can still be used or re-sent. */
export function pendingInvites(invites: StaffInvite[]): StaffInvite[] {
  return invites.filter((i) => i.status === "OPEN" || i.status === "EXPIRED")
}

/** Mobile (≤640px): rows become blocks, the People table's idiom. */
const ROW_CLASS = "flex flex-col gap-3 py-4 sm:table-row sm:py-0"
const CELL_CLASS = "block p-0 align-top sm:table-cell sm:p-4"

export interface PendingInvitesTableProps {
  invites: StaffInvite[]
  shopNameById: Map<string, string>
  busy?: boolean
  onResend: (invite: StaffInvite) => void | Promise<void>
  onCancel: (invite: StaffInvite) => void | Promise<void>
}

export function PendingInvitesTable({
  invites,
  shopNameById,
  busy = false,
  onResend,
  onCancel,
}: PendingInvitesTableProps) {
  const titleId = useId()
  const [cancelling, setCancelling] = useState<StaffInvite | null>(null)
  const rows = pendingInvites(invites)

  if (rows.length === 0) return null

  return (
    <Card role="region" aria-labelledby={titleId}>
      <CardHeader>
        <CardTitle id={titleId} className="flex items-center gap-2">
          <Clock className="h-5 w-5 text-orange-600" aria-hidden="true" />
          {PENDING_COPY.title}
        </CardTitle>
        <CardDescription>{PENDING_COPY.description}</CardDescription>
      </CardHeader>
      <CardContent>
        <Table containerLabel="Pending invitations table">
          <TableHeader className="hidden sm:table-header-group">
            <TableRow>
              {PENDING_COPY.columns.map((head) => (
                <TableHead key={head}>{head}</TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((invite) => {
              const expired = invite.status === "EXPIRED"
              return (
                <TableRow
                  key={invite.id}
                  data-status={invite.status}
                  className={expired ? `${ROW_CLASS} bg-slate-50 text-slate-600` : ROW_CLASS}
                >
                  <TableCell className={CELL_CLASS}>
                    <span
                      className={`text-sm font-semibold [overflow-wrap:anywhere] ${
                        expired ? "text-slate-600" : "text-slate-900"
                      }`}
                    >
                      {invite.email}
                    </span>
                  </TableCell>
                  <TableCell className={CELL_CLASS}>
                    <span className="text-sm [overflow-wrap:anywhere]">
                      {accessOffered(invite, shopNameById)}
                    </span>
                  </TableCell>
                  <TableCell className={CELL_CLASS}>
                    <div className="flex flex-wrap items-center gap-2 text-sm">
                      {expired && (
                        <Badge variant="outline" className="border-slate-300 text-slate-700">
                          {PENDING_COPY.expired}
                        </Badge>
                      )}
                      <span>{formatInviteExpiry(invite.expiresAt)}</span>
                    </div>
                  </TableCell>
                  <TableCell className={CELL_CLASS}>
                    <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
                      <Button
                        variant="outline"
                        className="h-11 w-full sm:w-auto"
                        disabled={busy}
                        aria-label={`${PENDING_COPY.resend} to ${invite.email}`}
                        onClick={() => onResend(invite)}
                      >
                        {PENDING_COPY.resend}
                      </Button>
                      <Button
                        variant="outline"
                        className="h-11 w-full sm:w-auto"
                        disabled={busy}
                        aria-label={`${PENDING_COPY.cancel} for ${invite.email}`}
                        onClick={() => setCancelling(invite)}
                      >
                        {PENDING_COPY.cancel}
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </CardContent>

      {/* Cancelling is destructive: the link stops working (UI-SPEC § Copywriting B1-B3). */}
      <ConfirmActionDialog
        open={cancelling !== null}
        onOpenChange={(o) => !o && setCancelling(null)}
        title={PENDING_COPY.cancelTitle}
        description={
          cancelling
            ? `The link sent to ${cancelling.email} will stop working. You can send a new one later.`
            : ""
        }
        confirmLabel={PENDING_COPY.cancel}
        cancelLabel={PENDING_COPY.keep}
        destructive
        onConfirm={async () => {
          if (!cancelling) return
          await onCancel(cancelling)
          setCancelling(null)
        }}
      />
    </Card>
  )
}

export default PendingInvitesTable
