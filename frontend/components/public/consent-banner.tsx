"use client"

import { useState } from "react"

import { cn } from "@/lib/utils"
import { accept, choosableCategories, reject } from "@/lib/consent"

/**
 * The consent banner — CONTRACTED NOW, SHOWN LATER (S1).
 *
 * It renders only when at least one NON-ESSENTIAL category is registered, and
 * the shipped configuration registers none, so today this component renders
 * `null` on every route. That is deliberate: there is nothing to ask about, and
 * a banner over zero categories is consent theatre that trains people to click
 * through without reading.
 *
 * IT IS BUILT ANYWAY, AND THAT IS THE POINT. The moment a non-essential category
 * is added to `SHIPPED_CATEGORIES`, the legally-required shape has to already be
 * right — "Reject all" as prominent as "Accept all", nothing pre-ticked, a
 * per-category view. Writing it under the time pressure of shipping an analytics
 * integration is exactly when those get quietly traded away. Its tests register
 * a FIXTURE category to make it render, because a component that cannot be seen
 * cannot be tested, and "it did not render" is a claim satisfied equally by a
 * correct dormant banner and by a broken one.
 */

/**
 * ONE class string for BOTH primary choices, so "Reject all" is structurally
 * incapable of being less prominent than "Accept all" — same element, same size,
 * same weight, adjacent. The tests assert `reject.className === accept.className`
 * rather than eyeballing a screenshot, and sharing the constant is what makes
 * that assertion hold by construction instead of by vigilance.
 *
 * `font-semibold` (600), never `font-bold` (700) — weight 700 is barred from
 * every new component in this phase.
 */
const CHOICE_BUTTON_CLASS = cn(
  "inline-flex min-h-11 min-w-11 items-center justify-center rounded-md px-4",
  "text-sm font-semibold leading-[1.5]",
  "bg-cream text-oxblood transition-colors hover:bg-white",
  // Cream ring on an oxblood surface: `--ring` is orange-700, and orange-700 on
  // #3A0B0D is a weak boundary. Ring visibility is itself a 3:1 requirement.
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-cream",
  "focus-visible:ring-offset-2 focus-visible:ring-offset-oxblood"
)

export function ConsentBanner() {
  const [managing, setManaging] = useState(false)
  const [decided, setDecided] = useState(false)

  const categories = choosableCategories()

  // Dormant today. Rendering nothing is the correct behaviour, not a stub.
  if (categories.length === 0 || decided) return null

  const decideAll = (allow: boolean) => {
    for (const category of categories) {
      if (allow) accept(category.id)
      else reject(category.id)
    }
    setDecided(true)
  }

  return (
    <section
      aria-label="Cookie choices"
      className={cn(
        "fixed inset-x-0 bottom-0 z-40 border-t border-white/15 bg-oxblood text-cream",
        "px-4 pt-4 pb-[max(1rem,env(safe-area-inset-bottom))]"
      )}
    >
      <div className="mx-auto max-w-3xl">
        <h2 className="text-sm font-semibold leading-[1.5]">
          Choose what we store on your device
        </h2>
        <p className="mt-1 text-sm leading-[1.5] text-cream/85">
          Strictly necessary storage keeps the site working and is always on. You can change
          this at any time from Cookie settings in the footer.
        </p>

        {managing && (
          <ul className="mt-3 space-y-2">
            {categories.map((category) => (
              <li key={category.id} className="flex items-start gap-2">
                <input
                  type="checkbox"
                  id={`consent-${category.id}`}
                  // NOT pre-ticked. Every non-essential category defaults to off;
                  // a ticked default is not consent, it is a pre-selected answer.
                  defaultChecked={false}
                  className="mt-1 h-4 w-4 accent-cream"
                />
                <label htmlFor={`consent-${category.id}`} className="text-sm leading-[1.5]">
                  <span className="font-semibold">{category.label}</span>{" "}
                  <span className="text-cream/85">{category.purpose}</span>
                </label>
              </li>
            ))}
          </ul>
        )}

        <div className="mt-4 flex flex-wrap items-center gap-2">
          {/* Accept and Reject are siblings sharing one class string, and the
              test asserts exactly that adjacency. */}
          <button type="button" className={CHOICE_BUTTON_CLASS} onClick={() => decideAll(true)}>
            Accept all
          </button>
          <button type="button" className={CHOICE_BUTTON_CLASS} onClick={() => decideAll(false)}>
            Reject all
          </button>
          <button
            type="button"
            onClick={() => setManaging((v) => !v)}
            aria-expanded={managing}
            className={cn(
              "inline-flex min-h-11 items-center justify-center rounded-md px-3",
              "text-sm font-semibold leading-[1.5] text-cream underline underline-offset-4",
              "transition-colors hover:text-white focus-visible:outline-none",
              "focus-visible:ring-2 focus-visible:ring-cream focus-visible:ring-offset-2",
              "focus-visible:ring-offset-oxblood"
            )}
          >
            Manage cookies
          </button>
        </div>
      </div>
    </section>
  )
}
