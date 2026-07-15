import { Fragment } from "react"

/**
 * Renders ingredient text with Natasha's-Law allergen emphasis. Allergen substrings are
 * marked in the source as `**allergen**` (e.g. "mango, **yoghurt (milk)**, cardamom");
 * splitting on `**` yields the emphasised text at odd indices, which we render bold.
 * Rendering the raw string (the QA FE-1 defect) leaked the literal `**` asterisks and
 * lost the legally-meaningful allergen emphasis.
 */
export function IngredientText({
  text,
  className,
}: {
  text: string | null | undefined
  className?: string
}) {
  if (!text) return null
  const parts = text.split("**")
  return (
    <span className={className}>
      {parts.map((segment, i) =>
        i % 2 === 1 ? (
          <strong key={i} className="font-semibold text-slate-700">
            {segment}
          </strong>
        ) : (
          <Fragment key={i}>{segment}</Fragment>
        )
      )}
    </span>
  )
}
