"use client"

import { useEffect, useState } from "react"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { z } from "zod"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  webhooksApi,
  extractErrorDetail,
  EVENT_TYPE_META,
  EVENT_TYPE_ORDER,
  type WebhookEventType,
  type WebhookSubscriptionWithSecret,
} from "@/lib/webhooks-api"

/**
 * WebhookCreateDialog (COMMS-06, UI-SPEC Surface A + Interaction Contracts).
 *
 * HTTPS-only Zod-validated URL + grouped event-type checkboxes (≥1 required).
 * On success the caller receives the create response — whose plaintext
 * `signingSecret` is shown ONCE via `SecretRevealDialog` — and refreshes the
 * list. Stays inside the orange/emerald/slate food-delivery palette; no new
 * primitives (native checkboxes with orange accent, `min-h-11` touch rows).
 */

const schema = z.object({
  targetUrl: z
    .string()
    .min(1, "Enter your endpoint URL.")
    .url("Enter a valid URL.")
    .startsWith(
      "https://",
      "Enter an HTTPS URL — http:// endpoints aren't allowed."
    ),
  eventTypes: z
    .array(
      z.enum([
        "ORDER_STATE_CHANGED",
        "ORDER_REFUNDED",
        "ONBOARDING_STATE_CHANGED",
        "PAYMENT_EVENT",
      ])
    )
    .min(1, "Select at least one event type."),
})

type FormValues = z.infer<typeof schema>

interface WebhookCreateDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreated: (created: WebhookSubscriptionWithSecret) => void
}

export function WebhookCreateDialog({
  open,
  onOpenChange,
  onCreated,
}: WebhookCreateDialogProps) {
  const [submitting, setSubmitting] = useState(false)
  const [serverError, setServerError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { targetUrl: "", eventTypes: [] },
  })

  const selected = watch("eventTypes")

  // Reset on open so stale values/errors never bleed across sessions.
  useEffect(() => {
    if (open) {
      reset({ targetUrl: "", eventTypes: [] })
      setServerError(null)
    }
  }, [open, reset])

  const toggleEvent = (et: WebhookEventType) => {
    const next = selected.includes(et)
      ? selected.filter((e) => e !== et)
      : [...selected, et]
    setValue("eventTypes", next, { shouldValidate: true })
  }

  const onSubmit = async (values: FormValues) => {
    setSubmitting(true)
    setServerError(null)
    try {
      const created = await webhooksApi.create({
        targetUrl: values.targetUrl.trim(),
        eventTypes: values.eventTypes,
      })
      reset({ targetUrl: "", eventTypes: [] })
      onOpenChange(false)
      onCreated(created)
    } catch (err: unknown) {
      setServerError(
        extractErrorDetail(err, "Couldn't add the endpoint — please try again.")
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Add webhook endpoint</DialogTitle>
          <DialogDescription>
            We&apos;ll POST signed events to this URL. Only HTTPS endpoints are
            accepted.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="targetUrl">Endpoint URL</Label>
            <Input
              id="targetUrl"
              type="url"
              inputMode="url"
              autoComplete="off"
              placeholder="https://your-app.example.com/webhooks/jtoye"
              className="font-mono text-xs"
              {...register("targetUrl")}
            />
            {errors.targetUrl && (
              <p className="text-xs text-red-600">{errors.targetUrl.message}</p>
            )}
          </div>

          <fieldset className="space-y-2">
            <legend className="text-sm font-medium text-slate-900">
              Events to send — pick at least one
            </legend>
            <div className="space-y-1">
              {EVENT_TYPE_ORDER.map((et) => {
                const meta = EVENT_TYPE_META[et]
                const checked = selected.includes(et)
                return (
                  <label
                    key={et}
                    className="flex min-h-11 cursor-pointer items-start gap-3 rounded-md border border-slate-200 px-3 py-2 hover:bg-slate-50"
                  >
                    <input
                      type="checkbox"
                      className="mt-0.5 h-4 w-4 accent-orange-500 focus:ring-2 focus:ring-orange-500"
                      checked={checked}
                      onChange={() => toggleEvent(et)}
                    />
                    <span className="flex flex-col">
                      <span className="text-sm font-medium text-slate-900">
                        {meta.family}
                        <span className="ml-2 text-xs font-normal text-slate-500">
                          {meta.label}
                        </span>
                      </span>
                      <span className="text-xs text-slate-500">
                        {meta.description}
                      </span>
                    </span>
                  </label>
                )
              })}
            </div>
            {errors.eventTypes && (
              <p className="text-xs text-red-600">{errors.eventTypes.message}</p>
            )}
          </fieldset>

          {serverError && (
            <p
              role="alert"
              className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
            >
              {serverError}
            </p>
          )}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={submitting}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              disabled={submitting}
              className="bg-orange-500 text-white hover:bg-orange-600"
            >
              {submitting ? "Adding…" : "Add endpoint"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

export default WebhookCreateDialog
