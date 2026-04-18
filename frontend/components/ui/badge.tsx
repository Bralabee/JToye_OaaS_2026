import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

/**
 * Badge — Warm Editorial (DESIGN-SPEC §9.4).
 *
 * Legacy shadcn variants (`default`, `secondary`, `destructive`, `outline`)
 * are preserved as aliases. New Warm Editorial tones (`brand`, `success`,
 * `warning`, `danger`, `info`, `editorial`, `subtle`) are additive.
 *
 * Default emphasis is "soft" (subtle tinted background + strong text) per
 * spec — solid badges are reserved for unread counts + primary statuses.
 */
const badgeVariants = cva(
  [
    "inline-flex items-center gap-1 rounded-pill border font-medium",
    "transition-colors duration-fast ease-standard motion-reduce:transition-none",
    "focus:outline-none focus:ring-2 focus:ring-border-tone-focus focus:ring-offset-2",
  ].join(" "),
  {
    variants: {
      variant: {
        // New canonical Warm Editorial tones (soft emphasis)
        brand:
          "border-transparent bg-brand-primary-subtle text-brand-primary",
        success:
          "border-transparent bg-success-subtle text-success",
        warning:
          "border-transparent bg-warning-subtle text-ink-primary",
        danger:
          "border-transparent bg-danger-subtle text-danger",
        info:
          "border-transparent bg-info-subtle text-info",
        editorial:
          "border-transparent bg-accent-editorial-subtle text-ink-primary",
        subtle:
          "border-transparent bg-surface-subtle text-ink-secondary",
        // Legacy aliases — map onto new tokens but keep original feel
        default:
          "border-transparent bg-brand-primary-subtle text-brand-primary",
        secondary:
          "border-transparent bg-surface-subtle text-ink-secondary",
        destructive:
          "border-transparent bg-danger-subtle text-danger",
        outline:
          "border-border-tone bg-transparent text-ink-primary",
      },
      size: {
        sm: "h-5 px-2 text-[11px] leading-none",
        md: "h-6 px-2.5 py-0.5 text-xs leading-none",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "md",
    },
  },
)

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, size, ...props }: BadgeProps) {
  return (
    <div
      className={cn(badgeVariants({ variant, size }), className)}
      {...props}
    />
  )
}

export { Badge, badgeVariants }
