"use client"

import { useId, useRef, useState } from "react"
import { AlertTriangle } from "lucide-react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import {
  CREDENTIAL_PROBLEMS,
  CREDENTIAL_ROLES,
  CREDENTIAL_SCOPES,
  createAttemptKey,
  createCredential,
  credentialActionError,
  credentialProblem,
  type CreateAttempt,
  type CredentialRole,
  type CredentialScope,
  type CredentialSecret,
} from "@/lib/credentials-api"
import { ROLE_LABELS } from "@/lib/staff-api"
import type { Shop } from "@/types/api"

/**
 * CredentialCreateDialog (37-46, UI-SPEC § E1 "Create dialog"; D-22, D-23, Q7).
 *
 * Name, what it can do (the six #206 scopes, each in plain English with its id), a role (Staff or
 * Shop manager: never Group admin, so no credential can manage staff or mint credentials) and a
 * shop or every shop. At least one scope is required; with none ticked the form says so inline and
 * sends nothing. A server refusal is shown in the dialog, next to the fields it is about.
 *
 * The dialog never sees the secret: it hands the create response straight to `onCreated`, and the
 * page opens the show-once dialog with it.
 */

/** UI-SPEC § Copywriting E1, verbatim. Exported so the suites assert the contract, not a copy. */
export const CREATE_COPY = {
  title: "Create credential",
  description:
    "It can do only what you tick, at the shop you choose. You'll see its secret once, when it is created.",
  nameLabel: "Name",
  nameHint: "Something you'll recognise, like 'Till system'",
  nameRequired: "Give this credential a name.",
  scopesLegend: "What it can do",
  noScope: "Choose at least one thing this credential can do.",
  roleLabel: "Role",
  shopLabel: "Shop",
  allShops: "All shops",
  submit: "Create credential",
  submitting: "Creating…",
  shopGone: "That shop is no longer part of this business. Choose another shop.",
} as const

const ALL_SHOPS_VALUE = ""

const SELECT_CLASS = "h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-sm"

export interface CredentialCreateDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  shops: Shop[]
  /** The 201 (secret shown) or the 200 Idempotency-Key replay (secret withheld). */
  onCreated: (created: CredentialSecret) => void
  /** The server answered `credentials-not-configured`: the page hides creation. */
  onNotConfigured: () => void
}

export function CredentialCreateDialog({
  open,
  onOpenChange,
  shops,
  onCreated,
  onNotConfigured,
}: CredentialCreateDialogProps) {
  const ids = useId()
  const nameId = `${ids}-name`
  const nameHintId = `${ids}-name-hint`
  const nameErrorId = `${ids}-name-error`
  const scopeErrorId = `${ids}-scope-error`

  const [name, setName] = useState("")
  const [scopes, setScopes] = useState<CredentialScope[]>([])
  const [role, setRole] = useState<CredentialRole>("STAFF")
  const [shopId, setShopId] = useState(ALL_SHOPS_VALUE)
  const [nameError, setNameError] = useState<string | null>(null)
  const [scopeError, setScopeError] = useState<string | null>(null)
  const [refusal, setRefusal] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const inFlight = useRef(false)
  const nameRef = useRef<HTMLInputElement>(null)
  // The last create attempt and its Idempotency-Key, kept until a create succeeds or the form is
  // reset, so pressing Create again after a lost response replays instead of issuing a second
  // credential.
  const attempt = useRef<CreateAttempt | null>(null)

  const reset = () => {
    attempt.current = null
    setName("")
    setScopes([])
    setRole("STAFF")
    setShopId(ALL_SHOPS_VALUE)
    setNameError(null)
    setScopeError(null)
    setRefusal(null)
  }

  const handleOpenChange = (next: boolean) => {
    if (!next && inFlight.current) return
    if (!next) reset()
    onOpenChange(next)
  }

  const toggleScope = (scope: CredentialScope, checked: boolean) => {
    setScopeError(null)
    setScopes((current) => {
      const without = current.filter((s) => s !== scope)
      if (!checked) return without
      // Kept in the form's order, so the request reads the way the list does.
      return CREDENTIAL_SCOPES.map((s) => s.id).filter((id) => id === scope || without.includes(id))
    })
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (inFlight.current) return
    setRefusal(null)
    const trimmed = name.trim()
    const missingName = trimmed.length === 0
    const missingScope = scopes.length === 0
    setNameError(missingName ? CREATE_COPY.nameRequired : null)
    setScopeError(missingScope ? CREATE_COPY.noScope : null)
    if (missingName) {
      nameRef.current?.focus()
      return
    }
    if (missingScope) return

    inFlight.current = true
    setSubmitting(true)
    try {
      const input = {
        name: trimmed,
        scopes,
        role,
        shopId: shopId === ALL_SHOPS_VALUE ? null : shopId,
      }
      attempt.current = createAttemptKey(attempt.current, input)
      const created = await createCredential(input, attempt.current.key)
      inFlight.current = false
      reset()
      onCreated(created)
    } catch (err: unknown) {
      const problem = credentialProblem(err)
      if (problem.type === CREDENTIAL_PROBLEMS.notConfigured) {
        inFlight.current = false
        reset()
        onNotConfigured()
        return
      }
      if (problem.type === CREDENTIAL_PROBLEMS.scopeInvalid) {
        setScopeError(problem.detail ?? CREATE_COPY.noScope)
      } else if (problem.status === 404) {
        setRefusal(CREATE_COPY.shopGone)
      } else {
        setRefusal(credentialActionError(err, "create"))
      }
    } finally {
      inFlight.current = false
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-lg overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{CREATE_COPY.title}</DialogTitle>
          <DialogDescription>{CREATE_COPY.description}</DialogDescription>
        </DialogHeader>

        <form noValidate onSubmit={submit} className="space-y-6">
          {refusal && (
            <div
              role="alert"
              className="flex items-start gap-2 rounded-md border border-red-200 bg-red-50 px-4 py-2 text-sm text-red-700"
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              <p>{refusal}</p>
            </div>
          )}

          <div className="space-y-2">
            <label htmlFor={nameId} className="text-sm font-semibold text-slate-900">
              {CREATE_COPY.nameLabel}
            </label>
            <Input
              id={nameId}
              ref={nameRef}
              value={name}
              maxLength={100}
              autoComplete="off"
              required
              aria-invalid={nameError ? true : undefined}
              aria-describedby={nameError ? `${nameHintId} ${nameErrorId}` : nameHintId}
              onChange={(e) => {
                setName(e.target.value)
                if (nameError) setNameError(null)
              }}
              className="h-11"
            />
            <p id={nameHintId} className="text-sm text-slate-600">
              {CREATE_COPY.nameHint}
            </p>
            {nameError && (
              <p id={nameErrorId} role="alert" className="text-sm text-red-700">
                {nameError}
              </p>
            )}
          </div>

          <fieldset
            className="space-y-1"
            aria-describedby={scopeError ? scopeErrorId : undefined}
          >
            <legend className="mb-2 text-sm font-semibold text-slate-900">
              {CREATE_COPY.scopesLegend}
            </legend>
            {CREDENTIAL_SCOPES.map((scope) => {
              const boxId = `${ids}-scope-${scope.id.replace(":", "-")}`
              return (
                <div key={scope.id} className="flex min-h-11 items-center gap-4">
                  <Checkbox
                    id={boxId}
                    checked={scopes.includes(scope.id)}
                    onCheckedChange={(c) => toggleScope(scope.id, c === true)}
                  />
                  <label htmlFor={boxId} className="text-sm text-slate-900 [overflow-wrap:anywhere]">
                    {scope.label}
                    <span aria-hidden="true"> · </span>
                    <code className="font-mono text-sm text-slate-600">{scope.id}</code>
                  </label>
                </div>
              )
            })}
            {scopeError && (
              <p id={scopeErrorId} role="alert" className="pt-2 text-sm text-red-700">
                {scopeError}
              </p>
            )}
          </fieldset>

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <label htmlFor={`${ids}-role`} className="text-sm font-semibold text-slate-900">
                {CREATE_COPY.roleLabel}
              </label>
              <select
                id={`${ids}-role`}
                value={role}
                onChange={(e) => setRole(e.target.value as CredentialRole)}
                className={SELECT_CLASS}
              >
                {CREDENTIAL_ROLES.map((r) => (
                  <option key={r} value={r}>
                    {ROLE_LABELS[r]}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-2">
              <label htmlFor={`${ids}-shop`} className="text-sm font-semibold text-slate-900">
                {CREATE_COPY.shopLabel}
              </label>
              <select
                id={`${ids}-shop`}
                value={shopId}
                onChange={(e) => setShopId(e.target.value)}
                className={SELECT_CLASS}
              >
                <option value={ALL_SHOPS_VALUE}>{CREATE_COPY.allShops}</option>
                {shops.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <DialogFooter>
            <Button type="submit" className="h-11 w-full sm:w-auto" disabled={submitting}>
              {submitting ? CREATE_COPY.submitting : CREATE_COPY.submit}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

export default CredentialCreateDialog
