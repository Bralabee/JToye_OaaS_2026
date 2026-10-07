"use client"

import { useEffect, useRef, useState } from "react"
import Link from "next/link"
import { AlertTriangle, Download, FileText, Loader2 } from "lucide-react"
import publicApiClient from "@/lib/public-api-client"

/**
 * The client island of `/data-request/download` (#778, D-01, 31.1-17).
 *
 * THE TOKEN IS A SINGLE-USE BEARER CREDENTIAL for the subject's personal data,
 * so this component is mostly about WHEN it is spent:
 *
 *   1. It arrives in the URL fragment (`#token=…`), which a browser never sends
 *      to any server, and is read once on mount.
 *   2. It is removed from the address bar immediately (`history.replaceState`),
 *      so it does not survive in history, a copied URL or a screenshot.
 *   3. It lives in memory only (a ref). Nothing is written to localStorage or
 *      sessionStorage — this page registers no client-persisted key
 *      (lib/client-storage-keys.ts) — so reloading the page loses it, which is
 *      the point.
 *   4. It is POSTed in a JSON body only when the person presses "Show my
 *      data". A mail scanner or link prefetcher that renders this page spends
 *      nothing (threat T-31.1-61); the endpoint has no GET variant either.
 *
 * The response is the stored export (format jtoye-dsar-export/1), requested as
 * TEXT so "Save as file" saves exactly the bytes the server sent, and parsed
 * separately for the readable view. Everything is rendered as React text
 * children — no HTML injection API is used anywhere — so content that looks
 * like markup stays text (T-31.1-66).
 *
 * Every refusal (used, expired, not recognised) is the server's one 404 and
 * renders one message. Any OTHER failure keeps the token in memory and offers
 * a retry: the server rolls the consume back when it fails, so the link is
 * not spent by an error.
 */

const EXPORT_PATH = "/api/v1/public/gdpr/dsar/export"

type Json = string | number | boolean | null | Json[] | { [key: string]: Json }
type JsonObject = { [key: string]: Json }

type Phase =
  | { kind: "checking" }
  | { kind: "ready" }
  | { kind: "loading" }
  | { kind: "failed" }
  | { kind: "unavailable" }
  | { kind: "shown"; raw: string; doc: JsonObject }

export function DownloadClient() {
  const tokenRef = useRef<string | null>(null)
  const inFlight = useRef(false)
  const [phase, setPhase] = useState<Phase>({ kind: "checking" })

  useEffect(() => {
    const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ""))
    const token = fragment.get("token")
    if (window.location.hash) {
      // Drop the fragment before anything else can observe it.
      window.history.replaceState(
        window.history.state,
        "",
        window.location.pathname + window.location.search
      )
    }
    // Only a token actually read may replace the held one: under Strict Mode
    // (next dev) this effect runs twice, and the second run sees the fragment
    // the first run already stripped.
    if (token && token.trim()) tokenRef.current = token.trim()
    // The fragment exists only in the browser, so which state to show can only be
    // decided after mount. No request is made here.
    setPhase(tokenRef.current ? { kind: "ready" } : { kind: "unavailable" })
  }, [])

  async function showMyData() {
    const token = tokenRef.current
    if (!token || inFlight.current) return
    inFlight.current = true
    setPhase({ kind: "loading" })
    try {
      const res = await publicApiClient.post<string>(
        EXPORT_PATH,
        { token },
        { responseType: "text", transformResponse: [(data: unknown) => data] }
      )
      const raw = typeof res.data === "string" ? res.data : JSON.stringify(res.data, null, 2)
      const doc = JSON.parse(raw) as JsonObject
      tokenRef.current = null
      setPhase({ kind: "shown", raw, doc })
    } catch (error: unknown) {
      const status = (error as { response?: { status?: number } })?.response?.status
      if (status === 404) {
        tokenRef.current = null
        setPhase({ kind: "unavailable" })
      } else {
        setPhase({ kind: "failed" })
      }
    } finally {
      inFlight.current = false
    }
  }

  if (phase.kind === "shown") {
    return <ExportView raw={phase.raw} doc={phase.doc} />
  }

  return (
    <div className="mx-auto max-w-lg px-4 py-8 sm:py-12">
      <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        {phase.kind === "checking" && (
          <div className="flex flex-col items-center py-4 text-center">
            <Loader2 className="mb-4 h-8 w-8 animate-spin text-orange-600" aria-hidden="true" />
            <h1 className="text-2xl font-semibold leading-tight text-slate-900">Checking your link…</h1>
          </div>
        )}

        {(phase.kind === "ready" || phase.kind === "loading" || phase.kind === "failed") && (
          <div className="flex flex-col py-2">
            <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-orange-50">
              <FileText className="h-6 w-6 text-orange-700" aria-hidden="true" />
            </span>
            <h1 className="text-2xl font-semibold leading-tight text-slate-900">Your copy is ready</h1>
            <p className="mt-3 text-sm leading-relaxed text-slate-600">
              This is the copy of your personal data you asked us for. It lists every shop on
              J&apos;Toye that holds data linked to your email address, and what each one holds.
            </p>
            <p className="mt-3 text-sm leading-relaxed text-slate-600">
              The link works <strong className="font-semibold text-slate-900">once</strong>. When
              you press the button we show your data and then delete our copy, so save it as a
              file if you want to keep it.
            </p>
            {phase.kind === "failed" && (
              <p role="alert" className="mt-4 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
                Something went wrong on our side and your data was not shown. Your link has not
                been used — please try again.
              </p>
            )}
            <button
              type="button"
              onClick={showMyData}
              disabled={phase.kind === "loading"}
              className="mt-6 inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-orange-700 px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-orange-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-700 disabled:cursor-not-allowed disabled:opacity-70"
            >
              {phase.kind === "loading" && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
              Show my data
            </button>
          </div>
        )}

        {phase.kind === "unavailable" && <Unavailable />}
      </div>
    </div>
  )
}

function Unavailable() {
  return (
    <div className="flex flex-col py-2">
      <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-amber-100">
        <AlertTriangle className="h-6 w-6 text-amber-700" aria-hidden="true" />
      </span>
      <h1 className="text-2xl font-semibold leading-tight text-slate-900">
        {"This link can't be used"}
      </h1>
      <p className="mt-3 text-sm leading-relaxed text-slate-600">
        This download link has already been used, has expired, or was not recognised. You can
        request a new copy.
      </p>
      <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:items-center">
        <Link
          href="/shop/account"
          className="inline-flex min-h-11 items-center justify-center rounded-lg bg-orange-700 px-5 py-2.5 text-sm font-semibold text-white hover:bg-orange-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-700"
        >
          Request a new copy
        </Link>
        <Link
          href="/legal/privacy"
          className="inline-flex min-h-11 items-center text-sm font-medium text-slate-700 underline underline-offset-4 hover:text-slate-900"
        >
          Read our privacy notice
        </Link>
      </div>
    </div>
  )
}

// ---- the readable view of a jtoye-dsar-export/1 document ------------------------------------

function ExportView({ raw, doc }: { raw: string; doc: JsonObject }) {
  const summary = asObject(doc.summary)
  const about = asObject(doc.about)
  const account = asObject(doc.platformAccount)
  const vendors = asArray(doc.vendors).map(asObject).filter((v): v is JsonObject => v !== null)

  function saveAsFile() {
    const blob = new Blob([raw], { type: "application/json" })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement("a")
    anchor.href = url
    anchor.download = `jtoye-my-data-${fileDate(doc.generatedAt)}.json`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="mx-auto max-w-3xl px-4 py-8 sm:py-12">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold leading-tight text-slate-900 sm:text-3xl">Your data</h1>
          <p className="mt-2 text-sm text-slate-600">
            Prepared {formatValue("generatedAt", doc.generatedAt)} for{" "}
            <span className="font-medium text-slate-900">{text(doc.requestedFor)}</span>
          </p>
        </div>
        <button
          type="button"
          onClick={saveAsFile}
          className="inline-flex min-h-11 shrink-0 items-center justify-center gap-2 rounded-lg bg-orange-700 px-5 py-2.5 text-sm font-semibold text-white hover:bg-orange-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-700"
        >
          <Download className="h-4 w-4" aria-hidden="true" />
          Save as file
        </button>
      </header>

      <p className="mt-4 rounded-lg bg-slate-50 p-3 text-sm text-slate-700">
        This link has now been used and our copy deleted. Save the file if you want to keep it —
        this page cannot be opened again.
      </p>

      {summary && text(summary.statement) && (
        <p className="mt-6 text-base leading-relaxed text-slate-800">{text(summary.statement)}</p>
      )}

      {account && (
        <section className="mt-8" aria-labelledby="dsar-account">
          <h2 id="dsar-account" className="text-xl font-semibold text-slate-900">
            Your J&apos;Toye account
          </h2>
          {text(account.note) && <p className="mt-2 text-sm text-slate-700">{text(account.note)}</p>}
          <RecordList items={asArray(account.accounts)} empty="No sign-in account." />
        </section>
      )}

      {vendors.map((vendor, index) => (
        <VendorSection key={text(vendor.reference) || index} vendor={vendor} index={index} />
      ))}

      {about && (
        <section className="mt-10" aria-labelledby="dsar-about">
          <h2 id="dsar-about" className="text-xl font-semibold text-slate-900">
            About this copy
          </h2>
          <AboutBlock about={about} />
        </section>
      )}
    </div>
  )
}

function VendorSection({ vendor, index }: { vendor: JsonObject; index: number }) {
  const recipient = asObject(vendor.recipient) ?? {}
  const shops = asArray(recipient.shops).map(text).filter(Boolean)
  const name = text(recipient.legalName) || shops.join(", ") || "A shop on J'Toye"
  const headingId = `dsar-vendor-${index}`
  const { legalName: _omit, ...recipientRest } = recipient
  void _omit

  return (
    <section className="mt-10 border-t border-slate-200 pt-8" aria-labelledby={headingId}>
      <h2 id={headingId} className="text-xl font-semibold text-slate-900">
        {name}
      </h2>
      <p className="mt-1 text-sm text-slate-600">
        The business that runs {shops.length > 0 ? shops.join(", ") : "this shop"} and holds the
        data below.
      </p>
      <Record value={recipientRest} />

      <h3 className="mt-6 text-lg font-semibold text-slate-900">Your details</h3>
      <RecordList items={asArray(vendor.customerRecords)} empty="No customer record held." />

      <h3 className="mt-6 text-lg font-semibold text-slate-900">Orders</h3>
      {asArray(vendor.orders).length === 0 ? (
        <p className="mt-2 text-sm text-slate-600">No orders held.</p>
      ) : (
        <ol className="mt-2 space-y-4">
          {asArray(vendor.orders).map((order, i) => {
            const o = asObject(order) ?? {}
            const { orderNumber, ...rest } = o
            return (
              <li key={text(orderNumber) || i} className="rounded-lg border border-slate-200 p-4">
                <h4 className="text-base font-semibold text-slate-900">{text(orderNumber) || "Order"}</h4>
                <Record value={rest} />
              </li>
            )
          })}
        </ol>
      )}

      <h3 className="mt-6 text-lg font-semibold text-slate-900">Reviews</h3>
      <RecordList items={asArray(vendor.reviews)} empty="No reviews held." />

      <h3 className="mt-6 text-lg font-semibold text-slate-900">Message preferences</h3>
      <Record value={vendor.communicationPreferences ?? null} />

      {asArray(vendor.staffDirectory).length > 0 && (
        <>
          <h3 className="mt-6 text-lg font-semibold text-slate-900">Staff directory</h3>
          <RecordList items={asArray(vendor.staffDirectory)} empty="" />
        </>
      )}
    </section>
  )
}

function AboutBlock({ about }: { about: JsonObject }) {
  const purposes = asArray(about.purposes).map(text).filter(Boolean)
  const rights = asArray(about.rights).map(asObject).filter((r): r is JsonObject => r !== null)
  return (
    <div className="mt-3 space-y-4 text-sm leading-relaxed text-slate-700">
      {purposes.length > 0 && (
        <div>
          <h3 className="font-semibold text-slate-900">Why it is used</h3>
          <ul className="mt-1 list-disc space-y-1 pl-5">
            {purposes.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </div>
      )}
      {text(about.recipients) && (
        <div>
          <h3 className="font-semibold text-slate-900">Who received it</h3>
          <p className="mt-1">{text(about.recipients)}</p>
        </div>
      )}
      {text(about.retention) && (
        <div>
          <h3 className="font-semibold text-slate-900">How long it is kept</h3>
          <p className="mt-1">{text(about.retention)}</p>
        </div>
      )}
      {text(about.source) && (
        <div>
          <h3 className="font-semibold text-slate-900">Where it came from</h3>
          <p className="mt-1">{text(about.source)}</p>
        </div>
      )}
      {rights.length > 0 && (
        <div>
          <h3 className="font-semibold text-slate-900">Your rights</h3>
          <ul className="mt-1 list-disc space-y-1 pl-5">
            {rights.map((r, i) => (
              <li key={text(r.right) || i}>{text(r.how)}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

function RecordList({ items, empty }: { items: Json[]; empty: string }) {
  if (items.length === 0) {
    return empty ? <p className="mt-2 text-sm text-slate-600">{empty}</p> : null
  }
  return (
    <ol className="mt-2 space-y-3">
      {items.map((item, i) => (
        <li key={i} className="rounded-lg border border-slate-200 p-4">
          <Record value={item} />
        </li>
      ))}
    </ol>
  )
}

/**
 * A generic, complete view of one record: every field the export carries is
 * shown, with a readable label, so nothing in the document is hidden by the
 * presentation. Plain text children only.
 */
function Record({ value }: { value: Json }) {
  const obj = asObject(value)
  if (!obj) {
    return <p className="mt-2 text-sm text-slate-800">{formatValue("", value)}</p>
  }
  const entries = Object.entries(obj)
  if (entries.length === 0) return null
  return (
    <dl className="mt-2 grid grid-cols-1 gap-x-4 gap-y-1 text-sm sm:grid-cols-[minmax(0,12rem)_1fr]">
      {entries.map(([key, v]) => (
        <div key={key} className="contents">
          <dt className="pt-1 font-medium text-slate-600">{labelFor(key)}</dt>
          <dd className="break-words pb-1 text-slate-900">
            {isNested(v) ? <Nested value={v} /> : formatValue(key, v)}
          </dd>
        </div>
      ))}
    </dl>
  )
}

function Nested({ value }: { value: Json }) {
  if (Array.isArray(value)) {
    return (
      <ol className="space-y-2">
        {value.map((item, i) => (
          <li key={i} className="rounded-md bg-slate-50 p-2">
            <Record value={item} />
          </li>
        ))}
      </ol>
    )
  }
  return (
    <div className="rounded-md bg-slate-50 p-2">
      <Record value={value} />
    </div>
  )
}

// ---- formatting --------------------------------------------------------------------------

const ISO_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/

const DATE_TIME = new Intl.DateTimeFormat("en-GB", {
  timeZone: "Europe/London",
  dateStyle: "medium",
  timeStyle: "short",
})

const GBP = new Intl.NumberFormat("en-GB", { style: "currency", currency: "GBP" })

const LABELS: Record<string, string> = {
  line1: "Address line 1",
  line2: "Address line 2",
  vatNumber: "VAT number",
  traderIdentityOnFile: "Trader details on file",
  allergyNoteReadByShopAt: "Allergy note read by the shop",
  marketingOptIn: "Marketing opt-in",
}

function labelFor(key: string): string {
  if (LABELS[key]) return LABELS[key]
  const words = key
    .replace(/Pennies$/, "")
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

function isNested(v: Json): boolean {
  if (v === null || typeof v !== "object") return false
  if (Array.isArray(v)) return v.some((item) => item !== null && typeof item === "object")
  return true
}

function formatValue(key: string, v: Json | undefined): string {
  if (v === null || v === undefined) return "Not recorded"
  if (typeof v === "boolean") return v ? "Yes" : "No"
  if (typeof v === "number") return key.endsWith("Pennies") ? GBP.format(v / 100) : String(v)
  if (Array.isArray(v)) return v.length === 0 ? "None" : v.map((item) => formatValue("", item)).join(", ")
  if (typeof v === "string") {
    if (ISO_TIMESTAMP.test(v)) {
      const d = new Date(v)
      if (!Number.isNaN(d.getTime())) return DATE_TIME.format(d)
    }
    return v
  }
  return JSON.stringify(v)
}

function fileDate(v: Json | undefined): string {
  if (typeof v === "string" && /^\d{4}-\d{2}-\d{2}/.test(v)) return v.slice(0, 10)
  return new Date().toISOString().slice(0, 10)
}

function asObject(v: Json | undefined): JsonObject | null {
  return v !== null && v !== undefined && typeof v === "object" && !Array.isArray(v) ? v : null
}

function asArray(v: Json | undefined): Json[] {
  return Array.isArray(v) ? v : []
}

function text(v: Json | undefined): string {
  return typeof v === "string" ? v : ""
}

export default DownloadClient
