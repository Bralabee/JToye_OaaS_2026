"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { useSession } from "next-auth/react"
import { formatDistanceToNow } from "date-fns"
import { ShieldCheck, UserPlus, Users, AlertTriangle } from "lucide-react"
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
import { useToast } from "@/hooks/use-toast"
import { fetchMyShops } from "@/lib/shops-api"
import {
  fetchStaff,
  grantStaff,
  revokeStaff,
  ROLE_LABELS,
  type DirectoryEntry,
  type ShopRole,
  type StaffMember,
} from "@/lib/staff-api"
import type { Shop } from "@/types/api"

/**
 * VSA-04 — GROUP_ADMIN staff management: who is in the tenant, who can do what,
 * and per-shop grant/revoke.
 *
 * The gate is SERVER-side (23-04 `requireGroupAdmin()`): a non-GROUP_ADMIN gets a
 * typed `/shop-access-denied` 403 from `GET /api/v1/staff`, which this screen
 * renders as the shared access-required card (the finance/page.tsx idiom, D-10/D-13)
 * — never a crash, a blank, or an empty table implying "no staff". No directory PII
 * is fetched or rendered in that state (T-23-06-02).
 */

/** Axios error → HTTP status, or undefined for a non-HTTP failure. */
function httpStatus(err: unknown): number | undefined {
  if (err && typeof err === "object" && "response" in err) {
    return (err as { response?: { status?: number } }).response?.status
  }
  return undefined
}

/** Role options carry a plain-English scope hint so a grant is a deliberate act. */
const ROLE_OPTIONS: { value: ShopRole; hint: string }[] = [
  { value: "STAFF", hint: "order ops on one shop" },
  { value: "SHOP_MANAGER", hint: "full CRUD on one shop" },
  { value: "GROUP_ADMIN", hint: "all shops + staff management" },
]

const ALL_SHOPS_VALUE = ""

function lastSeenLabel(lastSeen: string | null): string {
  if (!lastSeen) return "Never signed in"
  const parsed = new Date(lastSeen)
  if (Number.isNaN(parsed.getTime())) return "Never signed in"
  return `Last seen ${formatDistanceToNow(parsed, { addSuffix: true })}`
}

export default function StaffPage() {
  const { data: session } = useSession()
  const { toast } = useToast()

  const [directory, setDirectory] = useState<DirectoryEntry[]>([])
  const [grants, setGrants] = useState<StaffMember[]>([])
  const [shops, setShops] = useState<Shop[]>([])
  const [loading, setLoading] = useState(true)
  const [forbidden, setForbidden] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  /** Inline refusal/So-far message (e.g. the last-GROUP_ADMIN 409) — deliberately
   *  NOT a toast, so the reason stays on screen next to the action that caused it. */
  const [notice, setNotice] = useState<string | null>(null)

  const [targetUserId, setTargetUserId] = useState("")
  const [targetShopId, setTargetShopId] = useState(ALL_SHOPS_VALUE)
  const [targetRole, setTargetRole] = useState<ShopRole>("STAFF")

  // Deliberately NOT a useCallback over `toast`: an unstable toast identity would
  // make the mount effect re-run on every render and hammer GET /api/v1/staff.
  // Same idiom as finance/page.tsx (fetch on mount, explicit reload after writes).
  const load = async () => {
    try {
      setLoading(true)
      const [staff, myShops] = await Promise.all([fetchStaff(), fetchMyShops()])
      setDirectory(staff.directory)
      setGrants(staff.grants)
      setShops(myShops.shops)
      setForbidden(false)
    } catch (error: unknown) {
      // A 403 here is an honest "you are not a group admin" state (D-10/D-13),
      // not a data-load failure — mirror the Finance/Approvals access-required card
      // instead of an empty table plus a red error toast.
      if (httpStatus(error) === 403) {
        setForbidden(true)
      } else {
        toast({
          variant: "destructive",
          title: "Error loading staff",
          description:
            error instanceof Error ? error.message : "Failed to load staff access",
        })
      }
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const emailByUserId = useMemo(() => {
    const map = new Map<string, string>()
    directory.forEach((d) => map.set(d.userId, d.email))
    return map
  }, [directory])

  const shopNameById = useMemo(() => {
    const map = new Map<string, string>()
    shops.forEach((s) => map.set(s.id, s.name))
    return map
  }, [shops])

  const sessionEmail = session?.user?.email ?? null
  const isSelf = useCallback(
    (userId: string) => {
      const email = emailByUserId.get(userId)
      return !!email && !!sessionEmail && email === sessionEmail
    },
    [emailByUserId, sessionEmail]
  )

  const holdsSelfGrant = grants.some((g) => isSelf(g.userId))

  const handleGrant = async () => {
    if (!targetUserId) {
      setNotice("Choose a team member to grant access to.")
      return
    }
    setNotice(null)
    setSubmitting(true)
    try {
      await grantStaff({
        userId: targetUserId,
        shopId: targetShopId === ALL_SHOPS_VALUE ? null : targetShopId,
        role: targetRole,
      })
      await load()
      toast({ title: "Access granted" })
    } catch (error: unknown) {
      const status = httpStatus(error)
      if (status === 409) {
        setNotice(
          "You cannot remove the last group admin. Grant someone else group-admin access first, then retry."
        )
      } else if (status === 400) {
        // 23-04 rejects a shop-scoped GROUP_ADMIN so the last-admin count stays exact.
        setNotice(
          "Group admin applies to every shop — choose “All shops” for a group-admin grant."
        )
      } else if (status === 403) {
        setForbidden(true)
      } else {
        setNotice(
          error instanceof Error ? error.message : "Could not grant access."
        )
      }
    } finally {
      setSubmitting(false)
    }
  }

  const handleRevoke = async (grant: StaffMember) => {
    setNotice(null)
    setSubmitting(true)
    try {
      await revokeStaff(grant.id)
      await load()
      toast({ title: "Access revoked" })
    } catch (error: unknown) {
      const status = httpStatus(error)
      if (status === 409) {
        // D-11: the server refuses to strand the tenant with no group admin.
        setNotice(
          "You cannot remove the last group admin. Grant someone else group-admin access first, then retry."
        )
      } else if (status === 403) {
        setForbidden(true)
      } else {
        setNotice(
          error instanceof Error ? error.message : "Could not revoke access."
        )
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-orange-600"></div>
      </div>
    )
  }

  if (forbidden) {
    return (
      <div className="space-y-6">
        <div>
          <h1 className="text-4xl font-bold text-slate-900">Staff &amp; access</h1>
          <p className="mt-2 text-slate-600">Who can work on which shop</p>
        </div>
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-12 text-center">
            <ShieldCheck className="mb-4 h-12 w-12 text-slate-300" />
            <h3 className="mb-2 text-lg font-semibold text-slate-900">
              Group admin access required
            </h3>
            <p className="text-sm text-slate-500">
              Managing staff access needs the group-admin role. Ask a group admin in
              your business for access.
            </p>
          </CardContent>
        </Card>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-4xl font-bold text-slate-900">Staff &amp; access</h1>
        <p className="mt-2 text-slate-600">Who can work on which shop</p>
      </div>

      {notice && (
        <div
          role="alert"
          className="flex items-start gap-3 rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900"
        >
          <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-600" />
          <p>{notice}</p>
        </div>
      )}

      {/* Grant form — the directory is the grant-target picker (D-09). */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <UserPlus className="h-5 w-5 text-orange-600" />
            Grant access
          </CardTitle>
          <CardDescription>
            Team members appear here after they sign in for the first time — invite
            them to log in once, then grant them a shop and a role.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-3">
            <div className="space-y-1.5">
              <label
                htmlFor="staff-user"
                className="text-sm font-medium text-slate-700"
              >
                Team member
              </label>
              <select
                id="staff-user"
                value={targetUserId}
                onChange={(e) => setTargetUserId(e.target.value)}
                className="h-10 w-full rounded-md border border-slate-300 bg-white px-3 text-sm"
              >
                <option value="">Select a person…</option>
                {directory.map((d) => (
                  <option key={d.userId} value={d.userId}>
                    {(d.displayName || d.email) + " (" + d.email + ")"}
                  </option>
                ))}
              </select>
            </div>

            <div className="space-y-1.5">
              <label
                htmlFor="staff-shop"
                className="text-sm font-medium text-slate-700"
              >
                Shop
              </label>
              <select
                id="staff-shop"
                value={targetShopId}
                onChange={(e) => setTargetShopId(e.target.value)}
                className="h-10 w-full rounded-md border border-slate-300 bg-white px-3 text-sm"
              >
                <option value={ALL_SHOPS_VALUE}>All shops / tenant-wide</option>
                {shops.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            </div>

            <div className="space-y-1.5">
              <label
                htmlFor="staff-role"
                className="text-sm font-medium text-slate-700"
              >
                Role
              </label>
              <select
                id="staff-role"
                value={targetRole}
                onChange={(e) => setTargetRole(e.target.value as ShopRole)}
                className="h-10 w-full rounded-md border border-slate-300 bg-white px-3 text-sm"
              >
                {ROLE_OPTIONS.map((r) => (
                  <option key={r.value} value={r.value}>
                    {`${ROLE_LABELS[r.value]} — ${r.hint}`}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="flex items-center justify-between gap-4">
            <p className="text-xs text-slate-500">
              Group admin always applies to every shop. Granting the same access twice
              is safe — it will not create a duplicate.
            </p>
            <Button onClick={handleGrant} disabled={submitting}>
              Grant access
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Directory — everyone who has signed in, whether or not they hold a grant. */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Users className="h-5 w-5 text-orange-600" />
            Team directory
          </CardTitle>
          <CardDescription>
            {directory.length === 1
              ? "1 person has signed in"
              : `${directory.length} people have signed in`}
          </CardDescription>
        </CardHeader>
        <CardContent>
          {directory.length === 0 ? (
            <p className="py-6 text-center text-sm text-slate-500">
              Nobody has signed in yet. Once a team member logs in, they can be granted
              access here.
            </p>
          ) : (
            <ul className="divide-y divide-slate-100">
              {directory.map((d) => (
                <li
                  key={d.userId}
                  className="flex items-center justify-between py-3"
                >
                  <span className="text-sm font-medium text-slate-900">
                    {d.displayName || d.email}
                  </span>
                  <span className="text-xs text-slate-500">
                    {lastSeenLabel(d.lastSeen)}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {/* Current grants + revoke. */}
      <Card>
        <CardHeader>
          <CardTitle>Current access</CardTitle>
          <CardDescription>
            Changes take effect immediately on the person&apos;s next request.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {holdsSelfGrant && (
            <p className="mb-4 flex items-start gap-2 rounded-md bg-slate-50 p-3 text-xs text-slate-600">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
              Removing your own access takes effect immediately and will reduce what
              you can see and do.
            </p>
          )}
          {grants.length === 0 ? (
            <p className="py-6 text-center text-sm text-slate-500">
              No access granted yet.
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Person</TableHead>
                  <TableHead>Shop</TableHead>
                  <TableHead>Role</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {grants.map((g) => {
                  const email = emailByUserId.get(g.userId) ?? g.userId
                  const shopName = g.shopId
                    ? shopNameById.get(g.shopId) ?? "Unknown shop"
                    : "All shops"
                  return (
                    <TableRow key={g.id}>
                      <TableCell>
                        <div className="flex items-center gap-2">
                          <span className="text-sm text-slate-900">{email}</span>
                          {isSelf(g.userId) && (
                            <Badge variant="secondary" className="text-xs">
                              This is you
                            </Badge>
                          )}
                        </div>
                      </TableCell>
                      <TableCell className="text-sm text-slate-600">
                        {shopName}
                      </TableCell>
                      <TableCell>
                        <span className="text-sm text-slate-900">
                          {ROLE_LABELS[g.role]}
                        </span>
                      </TableCell>
                      <TableCell className="text-right">
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={submitting}
                          aria-label={`Revoke ${email}`}
                          onClick={() => handleRevoke(g)}
                        >
                          Revoke
                        </Button>
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
