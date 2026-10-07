"use client"

import { useCallback, useEffect, useState } from "react"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import apiClient from "@/lib/api-client"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import type { TraderEntityType, TraderIdentity, UpdateTraderIdentityRequest } from "@/types/api"
import {
  EMPTY_VALUES,
  ENTITY_TYPE_OPTIONS,
  FIELD_ORDER,
  traderIdentitySchema,
  type TraderIdentityFormValues,
} from "./trader-identity-schema"

const URL = "/api/v1/trader-identity"

// The native <select> carries the same classes as the page's shop select, so the two read
// as one form family; Input supplies the rest.
const SELECT_CLASS =
  "flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring aria-[invalid=true]:border-red-500"

function responseOf(err: unknown): { status?: number; data?: unknown } | undefined {
  if (err && typeof err === "object" && "response" in err) {
    return (err as { response?: { status?: number; data?: unknown } }).response
  }
  return undefined
}

function toValues(identity: TraderIdentity): TraderIdentityFormValues {
  return {
    legalName: identity.legalName ?? "",
    entityType: identity.entityType ?? "",
    addressLine1: identity.addressLine1 ?? "",
    addressLine2: identity.addressLine2 ?? "",
    addressCity: identity.addressCity ?? "",
    addressPostcode: identity.addressPostcode ?? "",
    vatNumber: identity.vatNumber ?? "",
  }
}

/**
 * #789 (31.1-10): the vendor declares, once per business, the legal entity customers buy
 * from. GET loads it (404 = nothing on file yet, an empty form, not an error); PUT saves it
 * (GROUP_ADMIN only, server-side). Field errors from either the zod mirror or the server are
 * rendered under their field in a role="alert", the input is aria-invalid + aria-describedby,
 * and focus moves to the first one (the products-form A11Y-7 and checkout allergen-panel conventions).
 * A save is confirmed in a persistent polite live region, then the identity is RE-READ so the
 * fields show what was stored (the server canonicalises postcode and VAT number).
 */
export function TraderIdentityForm() {
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [companyNumber, setCompanyNumber] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    reset,
    setError,
    setFocus,
    formState: { errors, isSubmitting },
  } = useForm<TraderIdentityFormValues>({
    resolver: zodResolver(traderIdentitySchema),
    defaultValues: EMPTY_VALUES,
    shouldFocusError: true,
  })

  // Applying a GET result is split from issuing it, so the mount effect only SUBSCRIBES to the
  // request (state is set in the promise callbacks, never synchronously in the effect body) and
  // the post-save re-read reuses exactly the same handling.
  const applyLoaded = useCallback(
    (identity: TraderIdentity) => {
      reset(toValues(identity))
      setCompanyNumber(identity.companyNumber ?? null)
      setLoadError(null)
      setLoading(false)
    },
    [reset]
  )

  const applyLoadFailure = useCallback(
    (err: unknown) => {
      if (responseOf(err)?.status === 404) {
        // Nothing on file yet: the empty form IS the correct state.
        reset(EMPTY_VALUES)
        setLoadError(null)
      } else {
        setLoadError("We couldn't load your business details. You can still enter them below.")
      }
      setLoading(false)
    },
    [reset]
  )

  useEffect(() => {
    let cancelled = false
    apiClient.get(URL).then(
      (res) => {
        if (!cancelled) applyLoaded(res.data as TraderIdentity)
      },
      (err: unknown) => {
        if (!cancelled) applyLoadFailure(err)
      }
    )
    return () => {
      cancelled = true
    }
  }, [applyLoaded, applyLoadFailure])

  const reload = async () => {
    try {
      const res = await apiClient.get(URL)
      applyLoaded(res.data as TraderIdentity)
    } catch (err: unknown) {
      applyLoadFailure(err)
    }
  }

  const onSubmit = async (values: TraderIdentityFormValues) => {
    setFormError(null)
    setSaved(false)
    const body: UpdateTraderIdentityRequest = {
      legalName: values.legalName,
      entityType: values.entityType as TraderEntityType,
      addressLine1: values.addressLine1,
      addressLine2: values.addressLine2 || null,
      addressCity: values.addressCity,
      addressPostcode: values.addressPostcode,
      vatNumber: values.vatNumber || null,
    }
    try {
      await apiClient.put(URL, body)
      setSaved(true)
      await reload()
    } catch (err: unknown) {
      const response = responseOf(err)
      const fieldErrors =
        response?.status === 400 && response.data && typeof response.data === "object"
          ? ((response.data as { errors?: Record<string, string> }).errors ?? {})
          : {}
      const named = FIELD_ORDER.filter((field) => typeof fieldErrors[field] === "string")
      if (named.length > 0) {
        named.forEach((field) => setError(field, { type: "server", message: fieldErrors[field] }))
        setFocus(named[0])
      } else if (response?.status === 403) {
        setFormError("Only a group admin can change your business details.")
      } else {
        setFormError("We couldn't save your business details. Please try again.")
      }
    }
  }

  /** Props shared by every field: required is programmatic, errors are associated. */
  const a11y = (field: keyof TraderIdentityFormValues, opts: { required: boolean; hintId?: string }) => {
    const errorId = `trader-${field}-error`
    const describedBy = [opts.hintId, errors[field] ? errorId : undefined].filter(Boolean).join(" ")
    return {
      id: `trader-${field}`,
      "aria-required": opts.required ? ("true" as const) : undefined,
      "aria-invalid": errors[field] ? ("true" as const) : undefined,
      "aria-describedby": describedBy || undefined,
    }
  }

  const fieldError = (field: keyof TraderIdentityFormValues) =>
    errors[field] ? (
      <p id={`trader-${field}-error`} role="alert" className="text-sm text-red-600">
        {errors[field]?.message}
      </p>
    ) : null

  return (
    <Card id="business-details">
      <CardHeader>
        <CardTitle className="text-lg">Business details shown to customers</CardTitle>
        <CardDescription>
          Customers see your legal name and address before they order, as UK consumer law
          requires (the Consumer Contracts Regulations 2013 and the Electronic Commerce
          Regulations 2002). Enter them once for your business; every shop uses them.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {loadError && (
          <p role="alert" className="mb-4 text-sm text-red-600">
            {loadError}
          </p>
        )}
        <form
          id="trader-identity-form"
          noValidate
          onSubmit={handleSubmit(onSubmit)}
          className="space-y-4"
          aria-busy={loading || undefined}
        >
          <div className="space-y-2">
            <Label htmlFor="trader-legalName" className="font-normal">
              Legal name
            </Label>
            <p id="trader-legalName-hint" className="text-xs text-slate-500">
              Your company&apos;s registered name, or your own name if you&apos;re a sole trader.
            </p>
            <Input
              autoComplete="organization"
              {...a11y("legalName", { required: true, hintId: "trader-legalName-hint" })}
              {...register("legalName")}
            />
            {fieldError("legalName")}
          </div>

          <div className="space-y-2">
            <Label htmlFor="trader-entityType" className="font-normal">
              Business type
            </Label>
            <select className={SELECT_CLASS} {...a11y("entityType", { required: true })} {...register("entityType")}>
              <option value="">Select a business type</option>
              {ENTITY_TYPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            {fieldError("entityType")}
            {companyNumber && (
              <p className="text-xs text-slate-500">
                Company number {companyNumber}, from your application.
              </p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="trader-addressLine1" className="font-normal">
              Address line 1
            </Label>
            <Input
              autoComplete="address-line1"
              {...a11y("addressLine1", { required: true })}
              {...register("addressLine1")}
            />
            {fieldError("addressLine1")}
          </div>

          <div className="space-y-2">
            <Label htmlFor="trader-addressLine2" className="font-normal">
              Address line 2 (optional)
            </Label>
            <Input
              autoComplete="address-line2"
              {...a11y("addressLine2", { required: false })}
              {...register("addressLine2")}
            />
            {fieldError("addressLine2")}
          </div>

          <div className="grid gap-4 sm:grid-cols-[2fr_1fr]">
            <div className="space-y-2">
              <Label htmlFor="trader-addressCity" className="font-normal">
                Town or city
              </Label>
              <Input
                autoComplete="address-level2"
                {...a11y("addressCity", { required: true })}
                {...register("addressCity")}
              />
              {fieldError("addressCity")}
            </div>
            <div className="space-y-2">
              <Label htmlFor="trader-addressPostcode" className="font-normal">
                Postcode
              </Label>
              <Input
                autoComplete="postal-code"
                className="uppercase"
                {...a11y("addressPostcode", { required: true })}
                {...register("addressPostcode")}
              />
              {fieldError("addressPostcode")}
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="trader-vatNumber" className="font-normal">
              VAT number (optional)
            </Label>
            <p id="trader-vatNumber-hint" className="text-xs text-slate-500">
              GB followed by 9 or 12 digits. Leave it empty if you&apos;re not VAT-registered.
            </p>
            <Input
              className="uppercase"
              {...a11y("vatNumber", { required: false, hintId: "trader-vatNumber-hint" })}
              {...register("vatNumber")}
            />
            {fieldError("vatNumber")}
          </div>

          {formError && (
            <p role="alert" className="text-sm text-red-600">
              {formError}
            </p>
          )}

          <div className="flex flex-wrap items-center gap-3 pt-1">
            <Button type="submit" variant="outline" disabled={isSubmitting || loading}>
              {isSubmitting ? "Saving…" : "Save business details"}
            </Button>
            {/* Persistent polite live region: present from first render so assistive tech
                announces the confirmation when its text changes. */}
            <p role="status" aria-live="polite" className="text-sm text-emerald-700">
              {saved ? "Business details saved." : ""}
            </p>
          </div>
        </form>
      </CardContent>
    </Card>
  )
}
