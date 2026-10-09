import { Ban } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { ROLE_LABELS, type EffectiveAccess, type ShopRole } from "@/lib/staff-api"

/**
 * D-09 (37-06, UI-SPEC § B1) — one person's access, exactly as the server computed it.
 *
 * The value comes from `people[].effectiveAccess` on `GET /api/v1/staff`, which
 * `ShopAccessService.effectiveAccessFor` produces through the same decision path the
 * API enforces with (37-05). This component formats that value and nothing else: it
 * never reads grant rows, so a de-honoured automatic grant cannot show up here as
 * access (the say≠data defect D-09 exists to end, T-37-12). When the server sent no
 * value, the cell says "Not recorded" rather than guessing.
 *
 * Long names wrap with `[overflow-wrap:anywhere]` and nothing truncates, so whose
 * access is shown, and on which shop, is never hidden (UI-SPEC B1 long-text).
 */

export const ACCESS_COPY = {
  groupAdmin: "Group admin · all shops",
  realmAdmin: "Admin account",
  none: "No access",
  notRecorded: "Not recorded",
  bootstrapLine:
    "Kept automatically as the first person to sign in. Grant Group admin to someone to change this.",
  realmAdminLine: "Full access from their sign-in account, not from this page.",
  allShops: "all shops",
  unknownShop: "Unknown shop",
} as const

const LINE = "text-sm text-slate-900 [overflow-wrap:anywhere]"
const NOTE = "text-sm text-slate-600 [overflow-wrap:anywhere]"

function roleLabel(role: ShopRole | string): string {
  return ROLE_LABELS[role as ShopRole] ?? role
}

/**
 * The lines a SHOP_ROLES value reads as: the tenant-wide role first, then one line
 * per specifically granted shop, ordered by shop name. Never comma-joined.
 */
export function shopRoleLines(
  access: Pick<EffectiveAccess, "tenantWideRole" | "perShopRole">,
  shopNameById: ReadonlyMap<string, string>
): string[] {
  const lines: string[] = []
  if (access.tenantWideRole) {
    lines.push(`${roleLabel(access.tenantWideRole)} · ${ACCESS_COPY.allShops}`)
  }
  const perShop = Object.entries(access.perShopRole ?? {})
    .map(([shopId, role]) => ({
      shop: shopNameById.get(shopId) ?? ACCESS_COPY.unknownShop,
      role,
    }))
    .sort((a, b) => a.shop.localeCompare(b.shop))
  for (const { shop, role } of perShop) {
    lines.push(`${roleLabel(role)} · ${shop}`)
  }
  return lines
}

/**
 * What a person keeps if one grant is removed, for the Remove access confirm (UI-SPEC
 * § Copywriting B1-B3). It starts from the SERVER's effective access and takes away
 * the one thing that grant confers. It never adds anything the server did not say:
 *
 * - removing the tenant-wide GROUP_ADMIN grant drops "Group admin";
 * - removing a tenant-wide STAFF / SHOP_MANAGER grant drops that "all shops" line;
 * - removing a shop grant drops that shop's line;
 * - a realm admin keeps "Admin account" whatever is removed (it comes from their
 *   sign-in account, not from a grant).
 *
 * A grant the server does not honour (an automatic GROUP_ADMIN row under strict
 * scoping) drops nothing, because it conferred nothing. An empty result means "no
 * access to this business". Returns null when the server sent no value, so the
 * confirm makes no claim about what remains.
 */
export function remainingAccessLines(
  access: EffectiveAccess | null | undefined,
  removed: { shopId: string | null; role: ShopRole },
  shopNameById: ReadonlyMap<string, string>
): string[] | null {
  if (!access) return null
  const removesGroupAdmin = removed.shopId === null && removed.role === "GROUP_ADMIN"
  if (access.level === "GROUP_ADMIN" && !removesGroupAdmin) {
    return [ACCESS_COPY.groupAdmin]
  }
  const lines: string[] = []
  if (access.realmAdminSeenAt) lines.push(ACCESS_COPY.realmAdmin)
  const removesTenantWide = removed.shopId === null && removed.role !== "GROUP_ADMIN"
  const perShopRole = { ...(access.perShopRole ?? {}) }
  if (removed.shopId !== null) delete perShopRole[removed.shopId]
  lines.push(
    ...shopRoleLines(
      { tenantWideRole: removesTenantWide ? null : access.tenantWideRole, perShopRole },
      shopNameById
    )
  )
  return lines
}

interface EffectiveAccessCellProps {
  access: EffectiveAccess | null | undefined
  shopNameById: ReadonlyMap<string, string>
}

export function EffectiveAccessCell({ access, shopNameById }: EffectiveAccessCellProps) {
  if (!access) {
    return <p className={NOTE}>{ACCESS_COPY.notRecorded}</p>
  }

  switch (access.level) {
    case "NONE":
      return (
        <Badge
          variant="outline"
          data-access-badge="none"
          className="gap-1 border-slate-300 text-sm font-semibold text-slate-700"
        >
          <Ban className="h-4 w-4" aria-hidden="true" />
          {ACCESS_COPY.none}
        </Badge>
      )

    case "GROUP_ADMIN":
      return (
        <div className="space-y-1">
          <p className={LINE}>{ACCESS_COPY.groupAdmin}</p>
          {access.bootstrapAdmin && <p className={NOTE}>{ACCESS_COPY.bootstrapLine}</p>}
        </div>
      )

    case "REALM_ADMIN":
      return (
        <div className="space-y-1">
          <p className={LINE}>{ACCESS_COPY.realmAdmin}</p>
          <p className={NOTE}>{ACCESS_COPY.realmAdminLine}</p>
        </div>
      )

    case "SHOP_ROLES": {
      const lines = shopRoleLines(access, shopNameById)
      if (lines.length === 0) {
        return <p className={NOTE}>{ACCESS_COPY.notRecorded}</p>
      }
      return (
        <ul className="space-y-1">
          {lines.map((line) => (
            <li key={line} className={LINE}>
              {line}
            </li>
          ))}
        </ul>
      )
    }

    default:
      // A level this build does not know (a newer server). Say so; do not guess.
      return <p className={NOTE}>{ACCESS_COPY.notRecorded}</p>
  }
}
