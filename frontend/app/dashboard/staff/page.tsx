"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
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
import { LoadErrorPanel } from "@/components/dashboard/load-error-panel"
import { ConfirmActionDialog } from "@/components/dashboard/confirm-action-dialog"
import {
  EffectiveAccessCell,
  remainingAccessLines,
} from "@/components/dashboard/staff/effective-access-cell"
import { useToast } from "@/hooks/use-toast"
import { describeLoadError } from "@/lib/human-error"
import { fetchMyShops } from "@/lib/shops-api"
import {
  fetchStaff,
  grantStaff,
  revokeStaff,
  ROLE_LABELS,
  type DirectoryEntry,
  type ShopRole,
  type StaffMember,
  type StaffPerson,
} from "@/lib/staff-api"
import type { Shop } from "@/types/api"

/**
 * VSA-04 — GROUP_ADMIN staff management: who is in the tenant, what each person may
 * do, and per-shop grant/revoke.
 *
 * The gate is SERVER-side (23-04 `requireGroupAdmin()`): a non-GROUP_ADMIN gets a
 * typed `/shop-access-denied` 403 from `GET /api/v1/staff`, which this screen
 * renders as the shared access-required card (the finance/page.tsx idiom, D-10/D-13)
 * — never a crash, a blank, or an empty table implying "no staff". No directory PII
 * is fetched or rendered in that state (T-23-06-02).
 *
 * D-09 (37-06): the People card lists `people[]` and shows each person's
 * `effectiveAccess` exactly as the server computed it (37-05). Grant rows decide only
 * which "Remove" buttons a row carries; they never decide what the Access column
 * says, so an automatic grant that strict scoping no longer honours cannot read as
 * access here (T-37-12).
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
 * #450 item 2, then 37-06. This first promised an invite that did not exist ("invite
 * them to log in once"), and was then corrected to say outright that the page could
 * not send one. D-07 (37-07) builds invitations, so that denial is about to be false
 * as well; the card now says only what is true of THIS form: its picker offers people
 * who have signed in.
 */
const GRANT_DESCRIPTION =
  "Choose someone who has signed in once with their own J'Toye account, then the " +
  "shop and role they need."

const GRANT_HINT =
  "Group admin always applies to every shop. Granting the same access twice is " +
  "safe — it will not create a duplicate."

const PEOPLE_DESCRIPTION =
  "Everyone who has signed in to this business, with the access they have now. " +
  "Changes apply to the person's next request. An already-open live view (a " +
  "kitchen or order stream) can keep updating for up to 5 minutes until it " +
  "reconnects."

/** A grant holder with no directory row: a service account (UI-SPEC § B1 rows). */
const INTEGRATION_ACCOUNT = "Integration account"

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

/** The People table's columns, shared with the skeleton for the same reason. */
const PEOPLE_COLUMNS = ["Person", "Access", "Actions"] as const

/**
 * Mobile (≤640px, UI-SPEC § B1): rows become blocks, so Person, Access and the
 * actions stack inside the focusable `containerLabel` region instead of forcing a
 * sideways scroll. From `sm` up it is an ordinary table.
 */
const ROW_CLASS = "flex flex-col gap-3 py-4 sm:table-row sm:py-0"
const CELL_CLASS = "block p-0 align-top sm:table-cell sm:p-4"

/**
 * Loading state, shaped like the page it precedes (#454).
 *
 * It replaced a centred 128px spinner, which is the whole of the CLS defect:
 * that spinner occupied ~150px and then handed over to a ~1190px page at 390px,
 * so everything below it — the cards and the shell footer — moved on arrival.
 * Measured at the repo's declared throttle profile (390px, Fast-3G, 4x CPU;
 * budget `CLS < 0.1`, webhooks-webperf.spec.ts:37) the route scored **0.1805**,
 * the worst in the app.
 *
 * The fix is not "a skeleton" generically — a wrongly-sized skeleton shifts just
 * as much. Two things make this one hold its place:
 *
 *  1. It is built from the SAME `Card`/`CardHeader`/`CardContent`/`Table`
 *     primitives as the loaded page, so padding, borders, radius and the
 *     `space-y-6` rhythm are identical by construction rather than by
 *     hand-copied pixel values, and it tracks the real page across breakpoints
 *     (the grant grid is 1-up at 390px and 3-up at md, in both).
 *  2. Everything that does not depend on the fetch — the h1, the subtitle, both
 *     card titles and descriptions, the three field labels, the table's column
 *     headers — is rendered for real. Only genuinely unknown data (the option
 *     lists, the people rows) is bars.
 *
 * 37-06 folded the "Team directory" card into the People card (every directory
 * row is a People row, with its last-seen line), so the page and this skeleton
 * are two cards, not three.
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
            People
          </CardTitle>
          <CardDescription>{PEOPLE_DESCRIPTION}</CardDescription>
        </CardHeader>
        <CardContent>
          <Table containerLabel="People table">
            <TableHeader className="hidden sm:table-header-group">
              <TableRow>
                {PEOPLE_COLUMNS.map((head) => (
                  <TableHead key={head}>{head}</TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {[0, 1].map((i) => (
                <TableRow key={i} className={ROW_CLASS}>
                  <TableCell className={CELL_CLASS}>
                    <Skeleton className="h-4 w-32" />
                    <Skeleton className="mt-2 h-4 w-40" />
                  </TableCell>
                  <TableCell className={CELL_CLASS}>
                    <Skeleton className="h-4 w-36" />
                  </TableCell>
                  <TableCell className={CELL_CLASS}>
                    <Skeleton className="h-11 w-full rounded-md sm:w-32" />
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

/** The name a person is shown by: display name, else masked email, else integration. */
function personName(p: StaffPerson): string {
  return p.displayName || p.maskedEmail || INTEGRATION_ACCOUNT
}

/**
 * The rows the People card lists. `people[]` is the server's list (37-05). A server
 * older than 37-05 sends none; the directory still names who has signed in, and each
 * of those rows then reads "Not recorded" — it is never given an access value the
 * server did not send.
 */
function peopleRows(people: StaffPerson[] | null, directory: DirectoryEntry[]): StaffPerson[] {
  if (people) return people
  return directory.map((d) => ({
    userId: d.userId,
    maskedEmail: d.email,
    displayName: d.displayName,
    lastSeen: d.lastSeen,
    effectiveAccess: null,
  }))
}

export default function StaffPage() {
  const { toast } = useToast()

  const [directory, setDirectory] = useState<DirectoryEntry[]>([])
  const [grants, setGrants] = useState<StaffMember[]>([])
  const [people, setPeople] = useState<StaffPerson[] | null>(null)
  const [shops, setShops] = useState<Shop[]>([])
  /** The caller's own Keycloak `sub` (from GET /api/v1/staff/me via fetchMyShops),
   *  the server-authoritative identity for the self-revoke warning (WR-12). */
  const [myUserId, setMyUserId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [forbidden, setForbidden] = useState(false)
  /** A non-403 load failure: the list is UNKNOWN, which must not render as empty. */
  const [loadError, setLoadError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  /** Inline refusal/So-far message (e.g. the last-GROUP_ADMIN 409) — deliberately
   *  NOT a toast, so the reason stays on screen next to the action that caused it. */
  const [notice, setNotice] = useState<string | null>(null)

  /** The grant whose removal is awaiting confirmation, with the person it belongs to. */
  const [removing, setRemoving] = useState<{ grant: StaffMember; person: StaffPerson } | null>(
    null
  )

  const [targetUserId, setTargetUserId] = useState("")
  const [targetShopId, setTargetShopId] = useState(ALL_SHOPS_VALUE)
  const [targetRole, setTargetRole] = useState<ShopRole>("STAFF")

  /** "Grant access" on a No access row moves the operator to the form. */
  const grantCardRef = useRef<HTMLDivElement>(null)
  const shopSelectRef = useRef<HTMLSelectElement>(null)

  // Deliberately NOT a useCallback over `toast`: an unstable toast identity would
  // make the mount effect re-run on every render and hammer GET /api/v1/staff.
  // Same idiom as finance/page.tsx (fetch on mount, explicit reload after writes).
  const load = async () => {
    try {
      setLoading(true)
      const [staff, myShops] = await Promise.all([fetchStaff(), fetchMyShops()])
      setDirectory(staff.directory)
      setGrants(staff.grants)
      setPeople(staff.people)
      setShops(myShops.shops)
      setMyUserId(myShops.userId)
      setForbidden(false)
      setLoadError(null)
    } catch (error: unknown) {
      // A 403 here is an honest "you are not a group admin" state (D-10/D-13),
      // not a data-load failure — mirror the Finance/Approvals access-required card
      // instead of an empty table plus a red error toast.
      if (httpStatus(error) === 403) {
        setForbidden(true)
      } else {
        // QA-council F2: an unknown list must never read as an empty one — and on
        // this page an empty list would also claim nobody has access.
        setLoadError(describeLoadError(error, "Failed to load staff access").message)
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

  const shopNameById = useMemo(() => {
    const map = new Map<string, string>()
    shops.forEach((s) => map.set(s.id, s.name))
    return map
  }, [shops])

  const rows = useMemo(() => peopleRows(people, directory), [people, directory])

  const grantsByUserId = useMemo(() => {
    const map = new Map<string, StaffMember[]>()
    grants.forEach((g) => map.set(g.userId, [...(map.get(g.userId) ?? []), g]))
    return map
  }, [grants])

  /**
   * The picker offers the people who have signed in (the directory), plus — when
   * "Grant access" was pressed on a row that has no directory entry — that one
   * person, so the pre-fill always names a real option.
   */
  const pickerOptions = useMemo(() => {
    const options = directory.map((d) => ({
      userId: d.userId,
      // #290: `(displayName || email) + " (" + email + ")"` printed the masked email
      // TWICE for anyone without a display name. The email is only a disambiguator
      // for a name — with no name it IS the label.
      label: d.displayName ? `${d.displayName} (${d.email})` : d.email,
    }))
    if (targetUserId && !options.some((o) => o.userId === targetUserId)) {
      const p = rows.find((r) => r.userId === targetUserId)
      if (p) options.push({ userId: p.userId, label: personName(p) })
    }
    return options
  }, [directory, rows, targetUserId])

  // WR-12: self-identification is on the Keycloak `sub` (the `userId` carried by
  // both the grant rows and MyAccessDto), NOT an email round-trip. The old
  // email compare failed whenever the session email was absent or differently
  // cased — and 23-12 now masks directory emails, so it could never match.
  const isSelf = useCallback(
    (userId: string) => !!myUserId && userId === myUserId,
    [myUserId]
  )

  const holdsSelfGrant = grants.some((g) => isSelf(g.userId))

  const shopLabel = (shopId: string | null) =>
    shopId ? shopNameById.get(shopId) ?? "Unknown shop" : "all shops"

  const startGrantFor = (p: StaffPerson) => {
    setNotice(null)
    setTargetUserId(p.userId)
    setTargetShopId(ALL_SHOPS_VALUE)
    setTargetRole("STAFF")
    grantCardRef.current?.scrollIntoView({ behavior: "smooth", block: "start" })
    // The person is chosen; what is left to decide is the shop and the role.
    shopSelectRef.current?.focus({ preventScroll: true })
  }

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
      toast({ title: "Access removed" })
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
          error instanceof Error ? error.message : "Could not remove access."
        )
      }
    } finally {
      setSubmitting(false)
    }
  }

  const removeTitle = (grant: StaffMember, person: StaffPerson): string => {
    const role = ROLE_LABELS[grant.role] ?? grant.role
    return `Remove ${role} at ${shopLabel(grant.shopId)} for ${personName(person)}?`
  }

  /** "After this they'll have: …" from the server's access minus this grant. */
  const removeDescription = (grant: StaffMember, person: StaffPerson): string => {
    const remaining = remainingAccessLines(person.effectiveAccess, grant, shopNameById)
    const after =
      remaining === null
        ? ""
        : remaining.length === 0
          ? "After this they'll have no access to this business. "
          : `After this they'll have: ${remaining.join("; ")}. `
    return `${after}They'll lose it on their next action.`
  }

  /*
   * WIDTH TIER — Index, and this was a decision rather than a default.
   *
   * PATTERNS A-7. The competing reading is "there is a form on this page, and
   * forms want a reading measure". The form is inside a Card that is already
   * narrower than the band and keeps its own width whatever the band does, so
   * tiering the whole page to the reading width would buy the form nothing and
   * would cap the People table — the multi-column surface this phase exists to
   * widen.
   *
   * The tier is written into the DOM as a declaration rather than left as the
   * absence of a cap, because "uncapped" and "someone forgot to cap it" render
   * identically and no assertion can tell them apart — ORCH-03 (orchestrator
   * decision, 2026-08-29). It is declared on EVERY render branch — the skeleton
   * in `StaffLoading` above, the access-denied card, the load-error card and the
   * loaded page — because a branch without it is an undeclared paint, and the
   * skeleton one matters most here: #454 made that branch hold the loaded page's
   * geometry, so a tier mismatch between them would reintroduce the shift it
   * removed.
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

  if (loadError) {
    return (
      <div data-width-tier="index" className="space-y-6">
        <div>
          <h1 className="text-4xl font-bold text-slate-900">{PAGE_TITLE}</h1>
          <p className="mt-2 text-slate-600">{PAGE_SUBTITLE}</p>
        </div>
        <Card>
          <CardContent>
            <LoadErrorPanel subject="staff" message={loadError} onRetry={() => load()} />
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
      <Card ref={grantCardRef}>
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
                {pickerOptions.map((o) => (
                  <option key={o.userId} value={o.userId}>
                    {o.label}
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
                ref={shopSelectRef}
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

      {/* People — everyone who has signed in, plus grant holders with no directory
          row, each with the access the server enforces for them (D-09). */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Users className="h-5 w-5 text-orange-600" />
            People
          </CardTitle>
          <CardDescription>{PEOPLE_DESCRIPTION}</CardDescription>
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
          <Table containerLabel="People table">
            <TableHeader className="hidden sm:table-header-group">
              <TableRow>
                {PEOPLE_COLUMNS.map((head) => (
                  <TableHead key={head}>{head}</TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((p) => {
                const name = personName(p)
                const showEmail = !!p.maskedEmail && p.maskedEmail !== name
                const personGrants = grantsByUserId.get(p.userId) ?? []
                const hasNoAccess = p.effectiveAccess?.level === "NONE"
                return (
                  <TableRow key={p.userId} className={ROW_CLASS}>
                    <TableCell className={CELL_CLASS}>
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-sm font-semibold text-slate-900 [overflow-wrap:anywhere]">
                          {name}
                        </span>
                        {isSelf(p.userId) && (
                          <Badge variant="secondary" className="text-xs">
                            This is you
                          </Badge>
                        )}
                      </div>
                      {showEmail && (
                        <p className="text-sm text-slate-600 [overflow-wrap:anywhere]">
                          {p.maskedEmail}
                        </p>
                      )}
                      {p.maskedEmail && (
                        <p className="text-sm text-slate-600">{lastSeenLabel(p.lastSeen)}</p>
                      )}
                    </TableCell>
                    <TableCell className={CELL_CLASS}>
                      <EffectiveAccessCell
                        access={p.effectiveAccess}
                        shopNameById={shopNameById}
                      />
                    </TableCell>
                    <TableCell className={CELL_CLASS}>
                      <div className="flex flex-col gap-2 sm:items-end">
                        {hasNoAccess ? (
                          <Button
                            className="h-11 w-full sm:w-auto"
                            disabled={submitting}
                            aria-label={`Grant access to ${name}`}
                            onClick={() => startGrantFor(p)}
                          >
                            Grant access
                          </Button>
                        ) : (
                          personGrants.map((g) => {
                            const role = ROLE_LABELS[g.role] ?? g.role
                            const shop = shopLabel(g.shopId)
                            return (
                              <Button
                                key={g.id}
                                variant="outline"
                                className="h-11 w-full sm:w-auto"
                                disabled={submitting}
                                aria-label={`Remove ${role} at ${shop} for ${name}`}
                                onClick={() => setRemoving({ grant: g, person: p })}
                              >
                                {personGrants.length === 1
                                  ? "Remove access"
                                  : `Remove ${role} · ${shop}`}
                              </Button>
                            )
                          })
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* Remove access is destructive, and its copy names the consequence: what the
          person keeps, from the server's effective access minus this one grant, or
          "no access" when it is the last (UI-SPEC § Copywriting B1-B3). */}
      <ConfirmActionDialog
        open={removing !== null}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={removing ? removeTitle(removing.grant, removing.person) : ""}
        description={removing ? removeDescription(removing.grant, removing.person) : ""}
        confirmLabel="Remove access"
        cancelLabel="Keep access"
        destructive
        onConfirm={async () => {
          if (!removing) return
          await handleRevoke(removing.grant)
          setRemoving(null)
        }}
      />
    </div>
  )
}
