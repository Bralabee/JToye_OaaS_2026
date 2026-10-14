"use client"

import { useId } from "react"
import { formatDistanceToNow } from "date-fns"
import { RotateCcw, Trash2 } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { scopeLabel, type Credential } from "@/lib/credentials-api"
import { ROLE_LABELS, type ShopRole } from "@/lib/staff-api"
import { formatUkDate, formatUkDateTime } from "@/lib/uk-datetime"

/**
 * CredentialsTable (37-46, UI-SPEC § E1 "Credentials card", "Last used"; D-24).
 *
 * From `sm` up: a table inside the shared focusable `containerLabel` region (it scrolls inside that
 * region rather than the page when it must). Below `sm`: one card per credential with the same
 * fields as a `<dl>` (the Webhooks precedent), so nothing scrolls sideways at 375px. Names, client
 * IDs and scope chips wrap (`overflow-wrap:anywhere`, chips `whitespace-normal`); nothing truncates.
 *
 * "Last used" is only as precise as the server's throttled write, so the column carries a footnote
 * whose interval comes from the API (`lastUsedIntervalMinutes`), never a literal here.
 */

/** The columns, shared with the loading skeleton so the two cannot drift. */
export const CREDENTIAL_COLUMNS = [
  "Name",
  "Can",
  "Shops",
  "Role",
  "Last used",
  "Created",
  "Actions",
] as const

export const TABLE_LABEL = "API credentials table"

export const LAST_USED_COPY = {
  never: "Never used",
  /** UI-SPEC: "Updated at most every {N} minutes." N is the API's interval. */
  footnote: (minutes: number) =>
    minutes === 1 ? "Updated at most every minute." : `Updated at most every ${minutes} minutes.`,
} as const

const HEAD_CLASS = "font-semibold text-slate-700"
const CELL_CLASS = "p-3 align-top text-sm text-slate-900"

/** "All shops", the shop's name, or "Unknown shop" (a shop this caller cannot read). */
function shopsLabel(shopId: string | null, shopNameById: Map<string, string>): string {
  if (!shopId) return "All shops"
  return shopNameById.get(shopId) ?? "Unknown shop"
}

/** "{date} by {name}", the date alone when the issuer is not in the directory, never a guess. */
export function createdLabel(c: Credential, nameById: Map<string, string>): string {
  if (!c.createdAt) return "Not recorded"
  const date = formatUkDate(c.createdAt)
  const by = c.createdBy ? nameById.get(c.createdBy) : undefined
  return by ? `${date} by ${by}` : date
}

function LastUsed({ at }: { at: string | null }) {
  const parsed = at ? new Date(at) : null
  if (!parsed || Number.isNaN(parsed.getTime())) {
    return <span className="text-slate-600">{LAST_USED_COPY.never}</span>
  }
  const absolute = formatUkDateTime(parsed)
  return (
    <time dateTime={parsed.toISOString()} title={absolute}>
      {formatDistanceToNow(parsed, { addSuffix: true })}
      <span className="sr-only">{` (${absolute})`}</span>
    </time>
  )
}

function ScopeChips({ scopes }: { scopes: string[] }) {
  return (
    <ul className="flex flex-wrap gap-1">
      {scopes.map((scope) => (
        <li key={scope}>
          <Badge
            variant="outline"
            title={scope}
            className="whitespace-normal text-sm font-normal text-slate-700 [overflow-wrap:anywhere]"
          >
            {scopeLabel(scope)}
          </Badge>
        </li>
      ))}
    </ul>
  )
}

export interface CredentialsTableProps {
  credentials: Credential[]
  /** From the API; null when the server did not send one (the footnote is then omitted). */
  lastUsedIntervalMinutes: number | null
  shopNameById: Map<string, string>
  /** Directory names by user id, for "Created … by {name}". */
  nameById: Map<string, string>
  busyId: string | null
  onRotate: (c: Credential) => void
  onDelete: (c: Credential) => void
}

export function CredentialsTable({
  credentials,
  lastUsedIntervalMinutes,
  shopNameById,
  nameById,
  busyId,
  onRotate,
  onDelete,
}: CredentialsTableProps) {
  const footnoteId = useId()
  const hasFootnote = lastUsedIntervalMinutes !== null

  const actions = (c: Credential, layout: "row" | "card") => (
    <div className={layout === "card" ? "flex flex-col gap-2" : "flex flex-col items-end gap-2"}>
      <Button
        variant="outline"
        className="h-11 w-full gap-2 sm:w-auto"
        disabled={busyId === c.id}
        aria-label={`Rotate secret for ${c.name}`}
        onClick={() => onRotate(c)}
      >
        <RotateCcw className="h-4 w-4" aria-hidden="true" />
        Rotate secret
      </Button>
      <Button
        variant="outline"
        className="h-11 w-full gap-2 sm:w-auto"
        disabled={busyId === c.id}
        aria-label={`Delete credential ${c.name}`}
        onClick={() => onDelete(c)}
      >
        <Trash2 className="h-4 w-4" aria-hidden="true" />
        Delete credential
      </Button>
    </div>
  )

  const roleLabel = (role: string) => ROLE_LABELS[role as ShopRole] ?? role

  return (
    <div className="space-y-4">
      <div className="hidden sm:block" data-testid="credentials-table">
        <Table containerLabel={TABLE_LABEL}>
          <TableHeader>
            <TableRow>
              {CREDENTIAL_COLUMNS.map((head) =>
                head === "Last used" && hasFootnote ? (
                  <TableHead key={head} className={HEAD_CLASS} aria-describedby={footnoteId}>
                    {head}
                    <span aria-hidden="true">*</span>
                  </TableHead>
                ) : (
                  <TableHead
                    key={head}
                    className={head === "Actions" ? `${HEAD_CLASS} text-right` : HEAD_CLASS}
                  >
                    {head}
                  </TableHead>
                )
              )}
            </TableRow>
          </TableHeader>
          <TableBody>
            {credentials.map((c) => (
              <TableRow key={c.id}>
                <TableCell className={`${CELL_CLASS} min-w-[10rem]`}>
                  <p className="font-semibold [overflow-wrap:anywhere]">{c.name}</p>
                  <p className="font-mono text-slate-600 [overflow-wrap:anywhere]">{c.clientId}</p>
                </TableCell>
                <TableCell className={`${CELL_CLASS} min-w-[10rem]`}>
                  <ScopeChips scopes={c.scopes} />
                </TableCell>
                <TableCell className={CELL_CLASS}>{shopsLabel(c.shopId, shopNameById)}</TableCell>
                <TableCell className={CELL_CLASS}>{roleLabel(c.role)}</TableCell>
                <TableCell className={CELL_CLASS}>
                  <LastUsed at={c.lastUsedAt} />
                </TableCell>
                <TableCell className={CELL_CLASS}>{createdLabel(c, nameById)}</TableCell>
                <TableCell className={CELL_CLASS}>{actions(c, "row")}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <ul className="space-y-4 sm:hidden" data-testid="credentials-cards">
        {credentials.map((c) => (
          <li key={c.id} className="rounded-lg border border-slate-200 p-4">
            <p className="text-sm font-semibold text-slate-900 [overflow-wrap:anywhere]">{c.name}</p>
            <p className="font-mono text-sm text-slate-600 [overflow-wrap:anywhere]">{c.clientId}</p>
            <dl className="mt-4 grid grid-cols-1 gap-2 text-sm">
              <dt className="font-semibold text-slate-700">Can</dt>
              <dd>
                <ScopeChips scopes={c.scopes} />
              </dd>
              <dt className="font-semibold text-slate-700">Shops</dt>
              <dd className="text-slate-900 [overflow-wrap:anywhere]">{shopsLabel(c.shopId, shopNameById)}</dd>
              <dt className="font-semibold text-slate-700">Role</dt>
              <dd className="text-slate-900">{roleLabel(c.role)}</dd>
              <dt className="font-semibold text-slate-700">Last used</dt>
              <dd className="text-slate-900">
                <LastUsed at={c.lastUsedAt} />
              </dd>
              <dt className="font-semibold text-slate-700">Created</dt>
              <dd className="text-slate-900 [overflow-wrap:anywhere]">{createdLabel(c, nameById)}</dd>
            </dl>
            <div className="mt-4">{actions(c, "card")}</div>
          </li>
        ))}
      </ul>

      {hasFootnote && (
        <p id={footnoteId} className="text-sm text-slate-600">
          <span aria-hidden="true">* </span>
          <span>{LAST_USED_COPY.footnote(lastUsedIntervalMinutes)}</span>
        </p>
      )}
    </div>
  )
}

/** The loading state: the real column headers over skeleton rows (UI-SPEC § E1 "Loading / error"). */
export function CredentialsTableSkeleton() {
  return (
    <Table containerLabel={TABLE_LABEL} aria-busy="true">
      <TableHeader>
        <TableRow>
          {CREDENTIAL_COLUMNS.map((head) => (
            <TableHead key={head} className={HEAD_CLASS}>
              {head}
            </TableHead>
          ))}
        </TableRow>
      </TableHeader>
      <TableBody>
        {[0, 1, 2].map((i) => (
          <TableRow key={i} data-testid="credential-skeleton-row">
            {CREDENTIAL_COLUMNS.map((head) => (
              <TableCell key={head} className={CELL_CLASS}>
                <Skeleton className="h-4 w-full min-w-12" />
              </TableCell>
            ))}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

export default CredentialsTable
