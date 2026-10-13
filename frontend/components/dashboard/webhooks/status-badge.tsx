import * as React from "react"
import {
  CheckCircle2,
  Pause,
  PauseCircle,
  AlertTriangle,
  Ban,
  Clock,
  RefreshCcw,
  XCircle,
  RotateCcw,
} from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { cn } from "@/lib/utils"
import type { SubscriptionStatus, DeliveryStatus } from "@/lib/webhooks-api"

/**
 * Webhook status taxonomy (COMMS-06, UI-SPEC §Color).
 *
 * Every badge = tinted background + a lucide icon + a text label — status is
 * NEVER conveyed by colour alone (accessibility contract). The `statusConfig`
 * shape mirrors `orders/page.tsx` so it reads as one design system.
 */

type BadgeConfig = {
  label: string
  className: string
  icon: React.ComponentType<{ className?: string }>
  /** Outline instead of the tinted fill (UI-SPEC E2: "Held" is an outline badge). */
  variant?: "outline"
}

export const subscriptionStatusConfig: Record<SubscriptionStatus, BadgeConfig> = {
  ACTIVE: {
    label: "Active",
    className: "bg-emerald-100 text-emerald-700",
    icon: CheckCircle2,
  },
  PAUSED: {
    label: "Paused",
    className: "bg-slate-100 text-slate-700",
    icon: Pause,
  },
  AUTO_PAUSED: {
    label: "Auto-paused",
    className: "bg-amber-100 text-amber-700",
    icon: AlertTriangle,
  },
  REVOKED: {
    label: "Revoked",
    className: "bg-red-100 text-red-700",
    icon: Ban,
  },
}

export const deliveryStatusConfig: Record<DeliveryStatus, BadgeConfig> = {
  PENDING: {
    label: "Pending",
    className: "bg-slate-100 text-slate-700",
    icon: Clock,
  },
  DELIVERED: {
    label: "Delivered",
    className: "bg-emerald-100 text-emerald-700",
    icon: CheckCircle2,
  },
  RETRYING: {
    label: "Retrying",
    className: "bg-amber-100 text-amber-700",
    icon: RefreshCcw,
  },
  FAILED: {
    label: "Failed",
    className: "bg-red-100 text-red-700",
    icon: XCircle,
  },
  // 37-43 (UXT-038, UI-SPEC E2): kept while the endpoint is paused, delivered on resume.
  HELD: {
    label: "Held",
    className: "text-slate-700",
    icon: PauseCircle,
    variant: "outline",
  },
}

/**
 * A status this build does not know yet renders as its raw name in an outline badge. Before 37-43
 * an unknown value threw inside the map lookup and took the whole delivery log down with it.
 */
function unknownDeliveryStatus(status: string): BadgeConfig {
  return { label: status, className: "text-slate-700", icon: Clock, variant: "outline" }
}

/** Subscription state badge — tinted bg + lucide icon + text label. */
export function SubscriptionStatusBadge({
  status,
  className,
}: {
  status: SubscriptionStatus
  className?: string
}) {
  const config = subscriptionStatusConfig[status]
  const Icon = config.icon
  return (
    <Badge
      className={cn(
        "w-fit gap-1 border-transparent",
        config.className,
        className
      )}
    >
      <Icon className="h-3 w-3" />
      {config.label}
    </Badge>
  )
}

/** Delivery state badge — tinted bg (or outline) + lucide icon + text label. */
export function DeliveryStatusBadge({
  status,
  className,
}: {
  status: DeliveryStatus
  className?: string
}) {
  const config = deliveryStatusConfig[status] ?? unknownDeliveryStatus(String(status))
  const Icon = config.icon
  const outline = config.variant === "outline"
  return (
    <Badge
      variant={outline ? "outline" : undefined}
      data-delivery-status={status}
      data-badge-variant={outline ? "outline" : "filled"}
      className={cn(
        "w-fit gap-1",
        !outline && "border-transparent",
        config.className,
        className
      )}
    >
      <Icon className="h-3 w-3" />
      {config.label}
    </Badge>
  )
}

/** Outline tag marking a delivery row as a manual replay attempt. */
export function ReplayTag({ className }: { className?: string }) {
  return (
    <Badge variant="outline" className={cn("w-fit gap-1 text-slate-600", className)}>
      <RotateCcw className="h-3 w-3" />
      Replay
    </Badge>
  )
}
