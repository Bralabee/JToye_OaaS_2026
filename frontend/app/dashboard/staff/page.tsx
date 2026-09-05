"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
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
import { Skeleton } from "@/components/ui/skeleton"
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

/**
 * Static page chrome, hoisted so the loading state can render the REAL strings
 * rather than grey bars standing in for them (see `StaffLoading`). Headings and
 * descriptions do not depend on the fetch, so withholding them buys nothing and
 * costs a layout shift when they arrive.
 */
const PAGE_TITLE = "Staff & access"
const PAGE_SUBTITLE = "Who can work on which shop"

/**
 * #450 item 2 — this used to read "…invite them to log in once".
 *
 * There is no invite. Nothing on this page, or anywhere else in the product,
 * sends one: `user_directory` is populated when a person signs in for the first
 * time, and the picker below can only offer people who are already in it. The
 * old sentence described a control that does not exist, so the one thing a group
 * admin could not learn from this screen was the thing they had to do next.
 *
 * Building the invite flow is a separate, decision-gated piece of work. Until it
 * exists the copy says plainly what happens and what the admin has to arrange
 * out-of-band.
 */
const GRANT_DESCRIPTION =
  "Team members appear here only after they have signed in once with their own " +
  "J'Toye account — this page cannot send them an invite. Ask them to sign in " +
  "first, then grant them a shop and a role."

const GRANT_HINT =
  "Group admin always applies to every shop. Granting the same access twice is " +
  "safe — it will not create a duplicate."

const CURRENT_ACCESS_DESCRIPTION =
  "Changes apply to the person's next request. An already-open live view (a " +
  "kitchen or order stream) can keep updating for up to 5 minutes until it " +
  "reconnects."

/**
 * Field labels, shared by the form and by its loading counterpart — the labels
 * are part of what makes the two the same height, so they must not be able to
 * drift apart.
 */
const FIELD_LABELS = {
  user: "Team member",
  shop: "Shop",
  role: "Role",
} as const

/**
 * Loading state, shaped like the page it precedes (#454).
 *
 * It replaced a centred 128px spinner, which is the whole of the CLS defect:
 * that spinner occupied ~150px and then handed over to a ~1190px page at 390px,
 * so everything below it — the three cards and the shell footer — moved on
 * arrival. Measured at the repo's declared throttle profile (390px, Fast-3G, 4x
 * CPU; budget `CLS < 0.1`, webhooks-webperf.spec.ts:37) the route scored
 * **0.1805**, the worst in the app.
 *
 * The fix is not "a skeleton" generically — a wrongly-sized skeleton shifts just
 * as much. Two things make this one hold its place:
 *
 *  1. It is built from the SAME `Card`/`CardHeader`/`CardContent`/`Table`
 *     primitives as the loaded page, so padding, borders, radius and the
 *     `space-y-6` rhythm are identical by construction rather than by
 *     hand-copied pixel values, and it tracks the real page across breakpoints
 *     (the grant grid is 1-up at 390px and 3-up at md, in both).
 *  2. Everything that does not depend on the fetch — the h1, the subtitle, all
 *     three card titles and descriptions, the three field labels — is rendered
 *     for real. Only genuinely unknown data (the option lists, the directory
 *     rows, the grants table) is bars.
 *
 * The bars use the shared `Skeleton` (shimmer keyframe, `motion-reduce:
 * animate-none`) rather than this file's older bare `animate-spin`, which is the
 * direction that component was added for.
 */
function StaffLoading() {
  return (
    <div
      data-width-tier="index"
      className="space-y-6"
      data-testid="staff-loading"
      aria-busy="true"
    >
      <div>
        <h1 className="text-4xl font-bold text-slate-900">{PAGE_TITLE}</h1>
        <p className="mt-2 text-slate-600">{PAGE_SUBTITLE}</p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <UserPlus className="h-5 w-5 text-orange-600" />
            Grant access
          </CardTitle>
          <CardDescription>{GRANT_DESCRIPTION}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-3">
            {Object.values(FIELD_LABELS).map((label) => (
              <div key={label} className="space-y-1.5">
                <span className="block text-sm font-medium text-slate-700">
                  {label}
                </span>
                {/* Same h-10 box as the <select> it stands in for. */}
                <Skeleton className="h-10 w-full rounded-md" />
              </div>
            ))}
          </div>
          <div className="flex items-center justify-between gap-4">
            {/* Static, so rendered for real — it wraps to five lines at 390px and
                a one-bar stand-in left an 80px hole under the fields. */}
            <p className="text-xs text-slate-500">{GRANT_HINT}</p>
            {/* The button is NOT rendered: a real one here would look pressable
                and do nothing. Sized to it instead (h-10, "Grant access"). */}
            <Skeleton className="h-10 w-[121px] shrink-0 rounded-md" />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Users className="h-5 w-5 text-orange-600" />
            Team directory
          </CardTitle>
          {/* The count is the one unknown in this header. */}
          <Skeleton className="h-4 w-44" />
        </CardHeader>
        <CardContent>
          <ul className="divide-y divide-slate-100">
            {[0, 1].map((i) => (
              <li key={i} className="flex items-center justify-between py-3">
                <Skeleton className="h-4 w-40" />
                <Skeleton className="h-3 w-24" />
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Current access</CardTitle>
          <CardDescription>{CURRENT_ACCESS_DESCRIPTION}</CardDescription>
        </CardHeader>
        <CardContent>
          <Table containerLabel="Current access table">
            <TableHeader>
              <TableRow>
                <TableHead>Person</TableHead>
                <TableHead>Shop</TableHead>
                <TableHead>Role</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[0, 1].map((i) => (
                <TableRow key={i}>
                  <TableCell>
                    <Skeleton className="h-4 w-28" />
                  </TableCell>
                  <TableCell>
                    <Skeleton className="h-4 w-20" />
                  </TableCell>
                  <TableCell>
                    <Skeleton className="h-4 w-16" />
                  </TableCell>
                  <TableCell className="text-right">
                    <Skeleton className="ml-auto h-4 w-14" />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}

function lastSeenLabel(lastSeen: string | null): string {
  if (!lastSeen) return "Never signed in"
  const parsed = new Date(lastSeen)
  if (Number.isNaN(parsed.getTime())) return "Never signed in"
  return `Last seen ${formatDistanceToNow(parsed, { addSuffix: true })}`
}

export default function StaffPage() {
  const { toast } = useToast()

  const [directory, setDirectory] = useState<DirectoryEntry[]>([])
  const [grants, setGrants] = useState<StaffMember[]>([])
  const [shops, setShops] = useState<Shop[]>([])
  /** The caller's own Keycloak `sub` (from GET /api/v1/staff/me via fetchMyShops),
   *  the server-authoritative identity for the self-revoke warning (WR-12). */
  const [myUserId, setMyUserId] = useState<string | null>(null)
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
      setMyUserId(myShops.userId)
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
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
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

  // WR-12: self-identification is on the Keycloak `sub` (the `userId` carried by
  // both the grant rows and MyAccessDto), NOT an email round-trip. The old
  // email compare failed whenever the session email was absent or differently
  // cased — and 23-12 now masks directory emails, so it could never match.
  const isSelf = useCallback(
    (userId: string) => !!myUserId && userId === myUserId,
    [myUserId]
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
        // IN-02: a 409 on the GRANT path is a DOWNGRADE refusal (D-11), not a
        // removal — the copy must match the action the operator just took.
        setNotice(
          "You cannot change the last group admin's role — the tenant would be left without one. Grant someone else group-admin access first, then retry."
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

  /*
   * WIDTH TIER — Index, and this was a decision rather than a default.
   *
   * PATTERNS A-7. The competing reading is "there is a form on this page, and
   * forms want a reading measure". The form is inside a Card that is already
   * narrower than the band and keeps its own width whatever the band does, so
   * tiering the whole page to the reading width would buy the form nothing and
   * would cap the directory and grants tables — which are the multi-column
   * surfaces this phase exists to widen.
   *
   * The tier is written into the DOM as a declaration rather than left as the
   * absence of a cap, because "uncapped" and "someone forgot to cap it" render
   * identically and no assertion can tell them apart — ORCH-03 (orchestrator
   * decision, 2026-08-29). It is declared on all THREE render branches — the
   * skeleton in `StaffLoading` above, the access-denied card below and the loaded
   * page — because a branch without it is an undeclared paint, and the skeleton
   * one matters most here: #454 made that branch hold the loaded page's geometry,
   * so a tier mismatch between them would reintroduce the shift it removed.
   */
  if (loading) {
    return <StaffLoading />
  }

  if (forbidden) {
    return (
      <div data-width-tier="index" className="space-y-6">
        <div>
          <h1 className="text-4xl font-bold text-slate-900">{PAGE_TITLE}</h1>
          <p className="mt-2 text-slate-600">{PAGE_SUBTITLE}</p>
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
    <div data-width-tier="index" className="space-y-6">
      <div>
        <h1 className="text-4xl font-bold text-slate-900">{PAGE_TITLE}</h1>
        <p className="mt-2 text-slate-600">{PAGE_SUBTITLE}</p>
      </div>

      {notice && (
        <div
          role="alert"
          className="flex items-start gap-3 rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900"
        >
          <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-700" />
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
          <CardDescription>{GRANT_DESCRIPTION}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-3">
            <div className="space-y-1.5">
              <label
                htmlFor="staff-user"
                className="text-sm font-medium text-slate-700"
              >
                {FIELD_LABELS.user}
              </label>
              <select
                id="staff-user"
                value={targetUserId}
                onChange={(e) => setTargetUserId(e.target.value)}
                className="h-10 w-full rounded-md border border-slate-300 bg-white px-3 text-sm"
              >
                <option value="">Select a person…</option>
                {/* #290: `(displayName || email) + " (" + email + ")"` printed the
                    masked email TWICE for anyone without a display name
                    ("j***@vendor.co.uk (j***@vendor.co.uk)"). The email is only a
                    disambiguator for a name — with no name it IS the label. */}
                {directory.map((d) => (
                  <option key={d.userId} value={d.userId}>
                    {d.displayName ? `${d.displayName} (${d.email})` : d.email}
                  </option>
                ))}
              </select>
            </div>

            <div className="space-y-1.5">
              <label
                htmlFor="staff-shop"
                className="text-sm font-medium text-slate-700"
              >
                {FIELD_LABELS.shop}
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
                {FIELD_LABELS.role}
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
            <p className="text-xs text-slate-500">{GRANT_HINT}</p>
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
          <CardDescription>{CURRENT_ACCESS_DESCRIPTION}</CardDescription>
        </CardHeader>
        <CardContent>
          {holdsSelfGrant && (
            <p className="mb-4 flex items-start gap-2 rounded-md bg-slate-50 p-3 text-xs text-slate-600">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
              Removing your own access will reduce what you can see and do on your
              next request; an already-open live view can persist for up to 5
              minutes until it reconnects.
            </p>
          )}
          {grants.length === 0 ? (
            <p className="py-6 text-center text-sm text-slate-500">
              No access granted yet.
            </p>
          ) : (
            <Table containerLabel="Access grants table">
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
                  // A grant with no directory entry is a JIT / service-account row.
                  // Render a labelled identity, never a bare UUID (plan 23-14 owns
                  // the richer "auto-granted on first sign-in" treatment).
                  const email = emailByUserId.get(g.userId) ?? "Unlisted member"
                  const shopName = g.shopId
                    ? shopNameById.get(g.shopId) ?? "Unknown shop"
                    : "All shops"
                  return (
                    <TableRow key={g.id}>
                      <TableCell>
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-sm text-slate-900">{email}</span>
                          {isSelf(g.userId) && (
                            <Badge variant="secondary" className="text-xs">
                              This is you
                            </Badge>
                          )}
                          {/* V57: JIT rows were auto-granted on first sign-in, not by an
                              operator. Flag them so a group admin knows which grants are
                              deliberate before enabling strict-scoping (which de-honours
                              JIT tenant-wide admin — CR-07). */}
                          {g.grantSource === "JIT" && (
                            <Badge
                              variant="outline"
                              className="text-xs text-slate-500"
                              title="Automatically granted on first sign-in — not a deliberate operator grant"
                            >
                              Auto-granted on first sign-in
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
