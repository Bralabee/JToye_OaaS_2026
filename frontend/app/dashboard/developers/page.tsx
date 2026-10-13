"use client"

import { useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { AlertTriangle, KeyRound, Plus, ShieldCheck } from "lucide-react"
import { Card, CardContent, CardHeader } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { LoadErrorPanel } from "@/components/dashboard/load-error-panel"
import { ConfirmActionDialog } from "@/components/dashboard/confirm-action-dialog"
import { SecretRevealDialog } from "@/components/dashboard/secret-reveal-dialog"
import { CredentialCreateDialog } from "@/components/dashboard/developers/credential-create-dialog"
import {
  CredentialsTable,
  CredentialsTableSkeleton,
} from "@/components/dashboard/developers/credentials-table"
import { DEVELOPERS_COPY } from "@/components/dashboard/developers/developers-copy"
import { useToast } from "@/hooks/use-toast"
import { describeLoadError } from "@/lib/human-error"
import { fetchMyShops } from "@/lib/shops-api"
import { fetchStaff } from "@/lib/staff-api"
import {
  CREDENTIAL_PROBLEMS,
  credentialActionError,
  credentialProblem,
  deleteCredential,
  listCredentials,
  rotateCredential,
  type Credential,
  type CredentialSecret,
} from "@/lib/credentials-api"
import type { Shop } from "@/types/api"

/**
 * Developers (37-46, UI-SPEC § E1; D-22, D-23, D-24; RWO-035 / UXT-035).
 *
 * A Group admin issues API credentials for a till, a website or an AI agent, so nobody shares a
 * password. Each credential holds the scopes ticked, one role (never Group admin) and one shop or
 * every shop; its secret is shown exactly once, at creation and at each rotation.
 *
 * The gate is the server's (37-45: a person at the dashboard, then a Group admin). This page
 * mirrors it, so a person who may not manage credentials is told so instead of being shown an
 * empty list: the caller's staff/me answer decides before any credential is requested, and the
 * server's own 403 is read the same way.
 *
 * Only ACTIVE credentials are listed. A deleted credential stays on the server as REVOKED (the
 * record of who revoked it and when), but it can do nothing, and listing it beside the working ones
 * would overstate what has access; the delete confirm says it "can't be restored".
 *
 * T-37-112: the secret is held in this component's state only while the show-once dialog is open,
 * and dropped in the same handler that closes it. Nothing here writes to storage.
 */

/** What the show-once dialog holds while it is open, and nothing longer. */
interface Reveal {
  secret: string
  clientId: string
  tokenEndpoint: string | null
}

type PageState =
  | { kind: "loading" }
  | { kind: "refused"; message: string }
  | { kind: "notConfigured" }
  | { kind: "error"; message: string }
  | { kind: "ready" }

type ConfirmState = { kind: "rotate" | "delete"; credential: Credential } | null

export default function DevelopersPage() {
  const { toast } = useToast()
  const [state, setState] = useState<PageState>({ kind: "loading" })
  const [shops, setShops] = useState<Shop[]>([])
  const [credentials, setCredentials] = useState<Credential[]>([])
  const [interval, setIntervalMinutes] = useState<number | null>(null)
  const [nameById, setNameById] = useState<Map<string, string>>(new Map())
  const [createOpen, setCreateOpen] = useState(false)
  const [reveal, setReveal] = useState<Reveal | null>(null)
  const [confirm, setConfirm] = useState<ConfirmState>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  /** A refused rotate or delete, shown next to the list rather than only in a toast. */
  const [notice, setNotice] = useState<string | null>(null)

  /** Directory names for "Created … by {name}". Best-effort: without them the date stands alone. */
  const loadNames = async () => {
    try {
      const staff = await fetchStaff()
      const map = new Map<string, string>()
      staff.directory.forEach((d) => map.set(d.userId, d.displayName || d.email))
      setNameById(map)
    } catch {
      setNameById(new Map())
    }
  }

  const load = async () => {
    setState({ kind: "loading" })
    let mine: Awaited<ReturnType<typeof fetchMyShops>>
    try {
      mine = await fetchMyShops()
    } catch (err: unknown) {
      setState({ kind: "error", message: describeLoadError(err, "Failed to load API credentials").message })
      return
    }
    setShops(mine.shops)
    if (!mine.isGroupAdmin) {
      setState({ kind: "refused", message: DEVELOPERS_COPY.notGroupAdmin })
      return
    }
    try {
      const list = await listCredentials()
      setCredentials(list.credentials)
      setIntervalMinutes(list.lastUsedIntervalMinutes)
      await loadNames()
      setState({ kind: "ready" })
    } catch (err: unknown) {
      const p = credentialProblem(err)
      if (p.type === CREDENTIAL_PROBLEMS.notConfigured) {
        setState({ kind: "notConfigured" })
      } else if (p.status === 403) {
        setState({
          kind: "refused",
          message:
            p.type === CREDENTIAL_PROBLEMS.humanOnly && p.detail ? p.detail : DEVELOPERS_COPY.notGroupAdmin,
        })
      } else {
        setState({ kind: "error", message: describeLoadError(err, "Failed to load API credentials").message })
      }
    }
  }

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch-on-mount; the loading state is the first render's contract (the #709 idiom of the other dashboard lists)
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** Re-read the list after a change, keeping the page as it is if the re-read fails. */
  const refresh = async () => {
    try {
      const list = await listCredentials()
      setCredentials(list.credentials)
      setIntervalMinutes(list.lastUsedIntervalMinutes)
    } catch {
      // The change itself succeeded; a failed re-read must not turn the list into an error.
    }
  }

  const shopNameById = useMemo(() => {
    const map = new Map<string, string>()
    shops.forEach((s) => map.set(s.id, s.name))
    return map
  }, [shops])

  const active = useMemo(() => credentials.filter((c) => c.status !== "REVOKED"), [credentials])

  const onCreated = (created: CredentialSecret) => {
    setCreateOpen(false)
    setNotice(null)
    if (created.secretShown && created.clientSecret) {
      setReveal({
        secret: created.clientSecret,
        clientId: created.clientId,
        tokenEndpoint: created.tokenEndpoint,
      })
    } else {
      toast({
        title: `"${created.name}" was already created`,
        description: "Its secret can't be shown again. Rotate it to get a new one.",
      })
    }
    void refresh()
  }

  const onConfirm = async () => {
    if (!confirm) return
    const { kind, credential } = confirm
    setNotice(null)
    setBusyId(credential.id)
    try {
      if (kind === "rotate") {
        const rotated = await rotateCredential(credential.id)
        setConfirm(null)
        if (rotated.clientSecret) {
          setReveal({
            secret: rotated.clientSecret,
            clientId: rotated.clientId,
            tokenEndpoint: rotated.tokenEndpoint,
          })
        }
      } else {
        await deleteCredential(credential.id)
        setConfirm(null)
        setCredentials((list) => list.filter((c) => c.id !== credential.id))
        toast({
          title: "Credential deleted",
          description: `"${credential.name}" can no longer call J'Toye.`,
        })
      }
      void refresh()
    } catch (err: unknown) {
      setConfirm(null)
      const p = credentialProblem(err)
      if (p.type === CREDENTIAL_PROBLEMS.notConfigured) {
        setState({ kind: "notConfigured" })
        return
      }
      setNotice(credentialActionError(err, kind))
      if (p.type === CREDENTIAL_PROBLEMS.revoked || p.status === 404) void refresh()
    } finally {
      setBusyId(null)
    }
  }

  const canCreate = state.kind === "ready" || state.kind === "loading"

  /*
   * WIDTH TIER — Index: a seven-column table page, the Webhooks precedent. Declared on the one root
   * every state renders through, so no paint is undeclared (ORCH-03).
   */
  return (
    <div data-width-tier="index" className="space-y-6">
      {/* 37-E review: one row only from lg. At md the sidebar leaves main 512 px, too narrow for the title,
          the subtitle and both actions: "Create credential" ended at x=815 in a 768 viewport. */}
      <div className="flex flex-col items-start justify-between gap-4 lg:flex-row lg:items-center">
        <div className="min-w-0 flex-1">
          <h1 className="text-4xl font-semibold text-slate-900">{DEVELOPERS_COPY.title}</h1>
          <p className="mt-2 text-sm text-slate-600">{DEVELOPERS_COPY.subtitle}</p>
        </div>
        <div className="flex w-full shrink-0 flex-col gap-2 sm:w-auto sm:flex-row sm:items-center">
          <Button asChild variant="link" className="h-11 justify-start px-0 sm:px-4">
            <Link href="/dashboard/webhooks">{DEVELOPERS_COPY.webhooksLink}</Link>
          </Button>
          {canCreate && (
            <Button
              className="h-11 w-full gap-2 sm:w-auto"
              disabled={state.kind !== "ready"}
              onClick={() => setCreateOpen(true)}
            >
              <Plus className="h-4 w-4" aria-hidden="true" />
              {DEVELOPERS_COPY.create}
            </Button>
          )}
        </div>
      </div>

      {notice && (
        <div
          role="alert"
          className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700"
        >
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <p>{notice}</p>
        </div>
      )}

      <Card>
        <CardHeader>
          {/* An h2, not CardTitle's h3: the page's headings descend in order (h1, h2, then the
              empty state's h3), which axe's heading-order rule checks. */}
          <h2 className="text-base font-semibold leading-none tracking-tight text-slate-900">
            {DEVELOPERS_COPY.cardTitle}
          </h2>
        </CardHeader>
        <CardContent>
          {state.kind === "loading" && <CredentialsTableSkeleton />}

          {state.kind === "refused" && (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <ShieldCheck className="mb-4 h-12 w-12 text-slate-300" aria-hidden="true" />
              <p className="text-base font-semibold text-slate-900">{state.message}</p>
            </div>
          )}

          {state.kind === "notConfigured" && (
            <p className="py-12 text-center text-sm text-slate-600">{DEVELOPERS_COPY.notConfigured}</p>
          )}

          {state.kind === "error" && (
            <LoadErrorPanel subject="API credentials" message={state.message} onRetry={() => load()} />
          )}

          {state.kind === "ready" && active.length === 0 && (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <KeyRound className="mb-4 h-12 w-12 text-slate-300" aria-hidden="true" />
              <h3 className="mb-2 text-base font-semibold text-slate-900">{DEVELOPERS_COPY.emptyTitle}</h3>
              <p className="mb-4 max-w-md text-sm text-slate-600">{DEVELOPERS_COPY.emptyBody}</p>
              <Button className="h-11 gap-2" onClick={() => setCreateOpen(true)}>
                <Plus className="h-4 w-4" aria-hidden="true" />
                {DEVELOPERS_COPY.create}
              </Button>
            </div>
          )}

          {state.kind === "ready" && active.length > 0 && (
            <CredentialsTable
              credentials={active}
              lastUsedIntervalMinutes={interval}
              shopNameById={shopNameById}
              nameById={nameById}
              busyId={busyId}
              onRotate={(c) => setConfirm({ kind: "rotate", credential: c })}
              onDelete={(c) => setConfirm({ kind: "delete", credential: c })}
            />
          )}
        </CardContent>
      </Card>

      <CredentialCreateDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        shops={shops}
        onCreated={onCreated}
        onNotConfigured={() => {
          setCreateOpen(false)
          setState({ kind: "notConfigured" })
        }}
      />

      <SecretRevealDialog
        open={reveal !== null}
        onOpenChange={(o) => {
          // Dropped in the same handler that closes the dialog (F13, T-37-112).
          if (!o) setReveal(null)
        }}
        title={DEVELOPERS_COPY.revealTitle}
        description={DEVELOPERS_COPY.revealDescription}
        warning={DEVELOPERS_COPY.revealWarning}
        secretLabel="client secret"
        secret={reveal?.secret ?? null}
        clientId={reveal?.clientId ?? null}
        tokenEndpoint={reveal?.tokenEndpoint ?? null}
      />

      <ConfirmActionDialog
        open={confirm?.kind === "rotate"}
        onOpenChange={(o) => !o && setConfirm(null)}
        title={confirm?.kind === "rotate" ? DEVELOPERS_COPY.rotateTitle(confirm.credential.name) : ""}
        description={DEVELOPERS_COPY.rotateBody}
        confirmLabel="Rotate secret"
        cancelLabel="Keep current secret"
        onConfirm={onConfirm}
      />

      <ConfirmActionDialog
        open={confirm?.kind === "delete"}
        onOpenChange={(o) => !o && setConfirm(null)}
        title={confirm?.kind === "delete" ? DEVELOPERS_COPY.deleteTitle(confirm.credential.name) : ""}
        description={DEVELOPERS_COPY.deleteBody}
        confirmLabel="Delete credential"
        cancelLabel="Keep credential"
        destructive
        onConfirm={onConfirm}
      />
    </div>
  )
}
