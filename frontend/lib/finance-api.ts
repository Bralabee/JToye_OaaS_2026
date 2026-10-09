import apiClient from "@/lib/api-client"
import type { FinancialSummary, FinancialTransaction } from "@/types/api"

/**
 * Finance reads scoped to one shop or the whole tenant (37-12, UI-SPEC § B5; server 37-11).
 *
 * Every response carries the scope the server actually FILTERED ON. Callers show that
 * echo, never the switcher's local value: if the two ever disagree the data matches the
 * echo, so a banner built from the switcher would be a false statement about the figures
 * beneath it (say is not data, T-37-33). An older server sends no scope; the echo is then
 * null and the caller claims nothing.
 */

/** The scope a finance response was filtered on (FinanceScopeDto). */
export interface FinanceScope {
  /** The shop the figures are for; null means every shop of the tenant. */
  shopId: string | null
  /** That shop's name; null when shopId is null. */
  shopName: string | null
  /** First UK day included (ISO date); null means from the first row. */
  from: string | null
  /** Last UK day included (ISO date); null means up to now. */
  to: string | null
}

/** What a caller asks for. Absent or null fields are simply not sent. */
export interface FinanceQuery {
  shopId?: string | null
  from?: string | null
  to?: string | null
}

export interface FinanceTransactionsPage {
  content: FinancialTransaction[]
  totalPages: number
  totalElements: number
  scope: FinanceScope | null
}

/** `shopId=…&from=…&to=…` for the fields present, in that order; "" when none are. */
export function financeQueryString(query: FinanceQuery): string {
  const params = new URLSearchParams()
  if (query.shopId) params.set("shopId", query.shopId)
  if (query.from) params.set("from", query.from)
  if (query.to) params.set("to", query.to)
  return params.toString()
}

/** The query that reproduces an echoed scope, so an export is exactly what is shown. */
export function queryFromScope(scope: FinanceScope): FinanceQuery {
  return { shopId: scope.shopId, from: scope.from, to: scope.to }
}

function readScope(raw: unknown): FinanceScope | null {
  if (!raw || typeof raw !== "object") return null
  const s = raw as Record<string, unknown>
  const str = (v: unknown) => (typeof v === "string" && v.length > 0 ? v : null)
  return { shopId: str(s.shopId), shopName: str(s.shopName), from: str(s.from), to: str(s.to) }
}

const BASE = "/api/v1/financial-transactions"

/**
 * GET /summary for the query. With no query fields the URL is the bare path, exactly as
 * before 37-12, so the unscoped call is unchanged on the wire.
 */
export async function fetchFinanceSummary(
  query: FinanceQuery
): Promise<{ summary: FinancialSummary | null; scope: FinanceScope | null }> {
  const qs = financeQueryString(query)
  const res = await apiClient.get(`${BASE}/summary${qs ? `?${qs}` : ""}`)
  const data = res.data ?? null
  // A body that is not a summary (a proxy page, a truncated response) is no figures at
  // all, never a half-read summary rendered as NaN or a crash in the charts.
  const isSummary =
    !!data &&
    typeof data === "object" &&
    typeof data.totalRevenuePennies === "number" &&
    Array.isArray(data.vatBreakdown)
  return { summary: isSummary ? data : null, scope: isSummary ? readScope(data.scope) : null }
}

/** GET the transaction list page for the query, newest first. */
export async function fetchFinanceTransactions(
  query: FinanceQuery,
  page: number,
  size: number
): Promise<FinanceTransactionsPage> {
  const qs = financeQueryString(query)
  const res = await apiClient.get(
    `${BASE}?page=${page}&size=${size}&sort=createdAt,desc${qs ? `&${qs}` : ""}`
  )
  return {
    content: res.data?.content || [],
    totalPages: res.data?.totalPages || 0,
    totalElements: res.data?.totalElements || 0,
    scope: readScope(res.data?.scope),
  }
}

/**
 * The filename from a Content-Disposition header: RFC 6266 `filename*=UTF-8''…` first,
 * then a quoted or bare `filename=`. Null when there is none (an older server, or a
 * proxy that dropped the exposed header).
 */
export function contentDispositionFilename(header: unknown): string | null {
  if (typeof header !== "string" || !header) return null
  const star = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(header)
  if (star) {
    try {
      return decodeURIComponent(star[1].trim())
    } catch {
      // fall through to the plain form
    }
  }
  const quoted = /filename\s*=\s*"((?:[^"\\]|\\.)*)"/.exec(header)
  if (quoted) return quoted[1].replace(/\\(.)/g, "$1")
  const bare = /filename\s*=\s*([^;\s]+)/.exec(header)
  return bare ? bare[1] : null
}

/** Used only when the server's Content-Disposition cannot be read. */
export const FALLBACK_EXPORT_FILENAME = "jtoye-finance-export.csv"

/**
 * GET /export.csv for the query as a Blob, with the server's own filename (which names
 * the scope: jtoye-finance-{shop-slug|all-shops}-{from}-{to}.csv).
 */
export async function fetchFinanceExport(
  query: FinanceQuery
): Promise<{ blob: Blob; filename: string }> {
  const qs = financeQueryString(query)
  const res = await apiClient.get(`${BASE}/export.csv${qs ? `?${qs}` : ""}`, {
    responseType: "blob",
  })
  const headers = (res.headers ?? {}) as Record<string, unknown>
  const filename =
    contentDispositionFilename(headers["content-disposition"]) ?? FALLBACK_EXPORT_FILENAME
  return { blob: res.data as Blob, filename }
}

/** Hand a Blob to the browser as a download under `filename`. */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/** U+2212 MINUS SIGN, spelled as a code point so no editor or tool can turn it into a hyphen. */
export const MINUS_SIGN = String.fromCharCode(0x2212)

const GBP = new Intl.NumberFormat("en-GB", { style: "currency", currency: "GBP" })

/**
 * Pennies as pounds with a REAL minus sign (U+2212) for a negative amount: "−£24.50".
 * Intl writes a hyphen-minus before the symbol ("-£24.50"), which reads as a dash and is
 * easy to miss; the sign must carry the meaning, never colour alone (UI-SPEC § B5).
 */
export function formatSignedPennies(pennies: number): string {
  const text = GBP.format(Math.abs(pennies) / 100)
  return pennies < 0 ? `${MINUS_SIGN}${text}` : text
}

/** The banner sentence parts for an echoed scope: the shop name, or null for all shops. */
export function scopeShopLabel(scope: FinanceScope): string | null {
  if (!scope.shopId) return null
  return scope.shopName ?? "the selected shop"
}

/**
 * What a finance surface should do before asking the server anything (UI-SPEC § B5):
 * - "wait": the caller's access is still loading; ask nothing yet.
 * - "fetch": a shop is selected (the server checks SHOP_MANAGER on it), or the caller is
 *   a Group admin on All shops, or access is unknown (rendered outside the provider, or
 *   staff/me could not be read) — then the server decides, exactly as before 37-12.
 * - "choose-shop": a non-admin on All shops. Tenant totals are Group-admin only, so they
 *   are not even requested; the page asks the manager to pick a shop.
 * - "staff-refused": tenant-wide STAFF on All shops; the existing refusal stays.
 * The server is the boundary in every case; this only avoids asking for a refusal.
 */
export type FinanceGate = "wait" | "fetch" | "choose-shop" | "staff-refused"

export function financeGate(
  contextShopId: string | null,
  switcher: { loading: boolean; access: { groupAdmin: boolean; tenantWideRole: string | null } | null } | null
): FinanceGate {
  if (contextShopId) return switcher?.loading ? "wait" : "fetch"
  if (!switcher) return "fetch"
  if (switcher.loading) return "wait"
  const access = switcher.access
  if (!access || access.groupAdmin) return "fetch"
  if (access.tenantWideRole === "STAFF") return "staff-refused"
  return "choose-shop"
}
