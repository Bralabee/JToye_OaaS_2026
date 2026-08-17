"use client"

import * as React from "react"
import * as CheckboxPrimitive from "@radix-ui/react-checkbox"
import { Check } from "lucide-react"

import { cn } from "@/lib/utils"

const Checkbox = React.forwardRef<
  React.ElementRef<typeof CheckboxPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof CheckboxPrimitive.Root>
>(({ className, ...props }, ref) => (
  <CheckboxPrimitive.Root
    ref={ref}
    className={cn(
      // 24px box (h-6 w-6), not the shadcn-shipped 16px: the acknowledgement
      // control is a legal-consequence checkbox, and the UI-SPEC requires it
      // inside a 44px (min-h-11) label row. The row is applied by the consumer.
      // Focus treatment matches components/ui/button.tsx rather than the
      // registry default (ring-1, no offset), so keyboard focus is consistent
      // across the house's interactive controls.
      "grid place-content-center peer h-6 w-6 shrink-0 rounded-sm border border-primary shadow ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 data-[state=checked]:bg-primary data-[state=checked]:text-primary-foreground",
      className
    )}
    {...props}
  >
    <CheckboxPrimitive.Indicator
      className={cn("grid place-content-center text-current")}
    >
      {/* Scaled with the box: the registry ships icon size == box size, and
          keeping that ratio at 24px preserves the shipped glyph proportions. */}
      <Check className="h-6 w-6" />
    </CheckboxPrimitive.Indicator>
  </CheckboxPrimitive.Root>
))
Checkbox.displayName = CheckboxPrimitive.Root.displayName

export { Checkbox }
