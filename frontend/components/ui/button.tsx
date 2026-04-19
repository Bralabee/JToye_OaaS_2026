import * as React from "react"
import { Slot } from "@radix-ui/react-slot"
import { cva, type VariantProps } from "class-variance-authority"
import { Loader2 } from "lucide-react"

import { cn } from "@/lib/utils"

/**
 * Button — Warm Editorial (DESIGN-SPEC §9.1).
 *
 * Legacy variants (`default`, `outline`, `secondary`, `ghost`, `link`,
 * `destructive`) are preserved as aliases for back-compat. New tokens
 * (`primary`, `editorial`, `subtle`) expose the new palette.
 *
 * Legacy sizes (`sm`, `default`, `lg`, `icon`) behave identically to before.
 * New `md` (alias of `default`) and `iconSm` are additive.
 */
const buttonVariants = cva(
  [
    "inline-flex items-center justify-center gap-2 whitespace-nowrap",
    "rounded-md text-sm font-medium",
    "transition-all duration-default ease-standard",
    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-border-tone-focus focus-visible:ring-offset-2 focus-visible:ring-offset-surface-canvas",
    "disabled:pointer-events-none disabled:opacity-50",
    "motion-reduce:transition-none",
    "@media (hover: hover) { hover:-translate-y-px }",
  ].join(" "),
  {
    variants: {
      variant: {
        // New canonical Warm Editorial variants
        primary:
          "bg-brand-primary text-ink-on-brand shadow-subtle hover:bg-brand-primary-hover active:bg-brand-primary-press active:translate-y-0",
        editorial:
          "bg-accent-editorial text-ink-on-accent shadow-subtle hover:brightness-95 active:translate-y-0",
        subtle:
          "bg-surface-subtle text-ink-primary hover:bg-surface-muted active:translate-y-0",
        // Legacy shadcn variants — map onto new tokens
        default:
          "bg-primary text-primary-foreground shadow-subtle hover:bg-brand-primary-hover active:translate-y-0",
        secondary:
          "border border-border-tone text-ink-primary bg-transparent hover:bg-surface-subtle active:translate-y-0",
        outline:
          "border border-border-tone bg-background text-ink-primary hover:bg-surface-subtle hover:text-ink-primary active:translate-y-0",
        ghost:
          "text-ink-primary hover:bg-surface-subtle hover:text-ink-primary active:translate-y-0",
        destructive:
          "bg-danger text-ink-on-danger shadow-subtle hover:brightness-95 active:translate-y-0",
        link:
          "text-brand-primary underline-offset-4 hover:underline hover:-translate-y-0",
      },
      size: {
        sm: "h-8 px-3 text-xs",
        md: "h-10 px-4 py-2",
        default: "h-10 px-4 py-2",
        lg: "h-12 rounded-md px-6 text-base",
        icon: "h-10 w-10 p-0",
        iconSm: "h-8 w-8 p-0",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
)

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
  /** Show a spinner and mark the button busy. Width stays stable. */
  isLoading?: boolean
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      className,
      variant,
      size,
      asChild = false,
      isLoading = false,
      disabled,
      children,
      ...props
    },
    ref,
  ) => {
    const Comp = asChild ? Slot : "button"
    const isDisabled = disabled || isLoading

    // When used via `asChild`, Radix's Slot requires a single child element —
    // we cannot inject a sibling spinner. In that case, fall through with the
    // caller's child untouched and rely on the busy attribute.
    if (asChild) {
      return (
        <Comp
          className={cn(buttonVariants({ variant, size, className }))}
          ref={ref}
          aria-busy={isLoading || undefined}
          aria-disabled={isDisabled || undefined}
          {...props}
        >
          {children}
        </Comp>
      )
    }

    return (
      <Comp
        className={cn(buttonVariants({ variant, size, className }))}
        ref={ref}
        disabled={isDisabled}
        aria-busy={isLoading || undefined}
        aria-disabled={isDisabled || undefined}
        {...props}
      >
        {isLoading ? (
          <>
            <Loader2
              className="h-4 w-4 animate-spin motion-reduce:animate-none"
              aria-hidden="true"
            />
            <span className="sr-only">Loading</span>
          </>
        ) : null}
        {children}
      </Comp>
    )
  },
)
Button.displayName = "Button"

export { Button, buttonVariants }
