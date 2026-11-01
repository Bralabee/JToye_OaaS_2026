import * as React from "react"

import { Button, type ButtonProps } from "@/components/ui/button"
import { cn } from "@/lib/utils"

export interface IconButtonProps
  extends Omit<ButtonProps, "children" | "aria-label" | "title"> {
  /**
   * REQUIRED accessible name. This is the whole point of the component: a bare
   * `<Button><Trash2/></Button>` has an EMPTY accessible name, so a screen
   * reader announces "button" and the user has to guess which one deletes.
   *
   * Name the OBJECT, not just the verb — "Delete product Party Jollof Rice",
   * not "Delete". A table of twelve rows otherwise announces twelve identical
   * "Delete" buttons, which is only marginally better than twelve "button"s.
   */
  label: string
  /** The glyph. Hidden from the accessibility tree; `label` is the name. */
  icon: React.ReactNode
  /**
   * Hover/focus tooltip text. Defaults to `label`, because an icon-only control
   * is opaque to sighted users too. Pass `false` to suppress it (e.g. when the
   * control already sits inside a tooltip primitive).
   */
  tooltip?: string | false
  /**
   * SW-TND-12 (swarm epic #964): a 44px square below `md` (the WCAG 2.5.5 / Apple HIG tap target),
   * the 32px default from `md`, where the sidebar layout and a fine pointer take over. For row
   * actions that sit side by side on a phone. Off by default, so desktop density is unchanged.
   */
  touchTarget?: boolean
}

/**
 * The single icon-only control for this app (#451 / QA-A F-M4-A11Y).
 *
 * `label` is a REQUIRED prop rather than an optional `aria-label`, so the type
 * checker — not a future audit — is what stops the next unnamed Edit/Delete
 * button from shipping. `npm run build` runs tsc, so this is an enforced gate.
 *
 * Sizing defaults to a 32px square (`h-8 w-8 p-0`), the shape the dashboard
 * row actions already use; `className` still wins via tailwind-merge, so a
 * caller can go larger without fighting the default.
 */
const IconButton = React.forwardRef<HTMLButtonElement, IconButtonProps>(
  ({ label, icon, tooltip, touchTarget = false, className, variant = "ghost", size = "sm", ...props }, ref) => (
    <Button
      ref={ref}
      variant={variant}
      size={size}
      aria-label={label}
      title={tooltip === false ? undefined : (tooltip ?? label)}
      className={cn("h-8 w-8 p-0", touchTarget && "h-11 w-11 md:h-8 md:w-8", className)}
      {...props}
    >
      <span aria-hidden="true" className="contents">
        {icon}
      </span>
    </Button>
  )
)
IconButton.displayName = "IconButton"

export { IconButton }
