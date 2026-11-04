import { useId } from "react"
import {
  DECLARED_FILTER_ANY,
  DECLARED_FILTER_HELP,
  DECLARED_FILTER_LABEL,
  DECLARED_FILTER_OPTIONS,
} from "@/lib/declared-allergen-filter"

/**
 * The "show dishes that declare ..." control (#862, owner ruling 2026-11-04). A native select, so
 * it is keyboard- and screen-reader-complete with no extra ARIA, and on a phone it opens the OS
 * picker. The help line is permanently visible and tied to the control with aria-describedby: it
 * is the statement that this reflects the vendor's declaration, not a safety promise.
 *
 * The value is the allergen's bit (or "" for no filter); the caller owns what the bit filters.
 */
export function DeclaredAllergenFilter({
  value,
  onChange,
  className = "",
}: {
  value: number | null
  onChange: (bit: number | null) => void
  className?: string
}) {
  const selectId = useId()
  const helpId = useId()
  return (
    <div className={className} data-testid="declared-allergen-filter">
      <label htmlFor={selectId} className="block text-sm font-medium text-slate-700">
        {DECLARED_FILTER_LABEL}
      </label>
      <select
        id={selectId}
        value={value === null ? "" : String(value)}
        onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))}
        aria-describedby={helpId}
        className="mt-1 block w-full max-w-xs rounded-lg border border-slate-400 bg-white px-3 py-2 text-sm text-slate-900 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-500"
      >
        <option value="">{DECLARED_FILTER_ANY}</option>
        {DECLARED_FILTER_OPTIONS.map((a) => (
          <option key={a.bit} value={a.bit}>
            {a.name}
          </option>
        ))}
      </select>
      <p id={helpId} className="mt-1 max-w-xl text-xs text-slate-600">
        {DECLARED_FILTER_HELP}
      </p>
    </div>
  )
}
