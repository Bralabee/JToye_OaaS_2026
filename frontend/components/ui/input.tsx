import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

/**
 * Input — Warm Editorial (DESIGN-SPEC §9.3).
 *
 * Public API preserved: any `InputHTMLAttributes` still pass through.
 * New props (`size`, `tone`, `invalid`) are additive and optional.
 *
 * `size` intentionally shadows the native `HTMLInputElement['size']`
 * attribute (which controls visible character width and is almost never
 * used). Callers that need the native attribute can still pass it via
 * `{...props}` on a typed wrapper; in practice this is a non-issue.
 */
const inputVariants = cva(
  [
    "flex w-full rounded-md border bg-surface-card text-ink-primary",
    "ring-offset-surface-canvas",
    "file:border-0 file:bg-transparent file:text-sm file:font-medium",
    "placeholder:text-ink-tertiary",
    "transition-colors duration-fast ease-standard motion-reduce:transition-none",
    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2",
    "disabled:cursor-not-allowed disabled:opacity-50 disabled:bg-surface-muted",
  ].join(" "),
  {
    variants: {
      size: {
        sm: "h-8 px-2.5 py-1 text-xs",
        md: "h-10 px-3 py-2 text-sm",
        lg: "h-12 px-4 py-3 text-base",
      },
      tone: {
        default:
          "border-border-tone focus-visible:ring-border-tone-focus hover:border-border-tone-strong",
        muted:
          "border-border-tone-subtle bg-surface-subtle focus-visible:ring-border-tone-focus",
        brand:
          "border-brand-primary-subtle focus-visible:ring-brand-primary",
      },
      invalid: {
        true: "border-danger bg-danger-subtle focus-visible:ring-danger",
        false: "",
      },
    },
    defaultVariants: {
      size: "md",
      tone: "default",
      invalid: false,
    },
  },
)

type InputVariantProps = VariantProps<typeof inputVariants>

export interface InputProps
  extends Omit<React.InputHTMLAttributes<HTMLInputElement>, "size">,
    InputVariantProps {
  /** Short flag for invalid state — applies the danger ring + tint. */
  invalid?: boolean
}

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type, size, tone, invalid, ...props }, ref) => {
    return (
      <input
        type={type}
        className={cn(inputVariants({ size, tone, invalid, className }))}
        ref={ref}
        aria-invalid={invalid || undefined}
        {...props}
      />
    )
  },
)
Input.displayName = "Input"

export { Input, inputVariants }
