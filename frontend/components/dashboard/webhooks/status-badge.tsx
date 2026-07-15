import * as React from "react"
import {
  CheckCircle2,
  Pause,
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

/** Delivery state badge — tinted bg + lucide icon + text label. */
export function DeliveryStatusBadge({
  status,
  className,
}: {
  status: DeliveryStatus
  className?: string
}) {
  const config = deliveryStatusConfig[status]
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

/** Outline tag marking a delivery row as a manual replay attempt. */
export function ReplayTag({ className }: { className?: string }) {
  return (
    <Badge variant="outline" className={cn("w-fit gap-1 text-slate-600", className)}>
      <RotateCcw className="h-3 w-3" />
      Replay
    </Badge>
  )
}
