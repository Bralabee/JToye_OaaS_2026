import { Ban } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { cn } from "@/lib/utils"

/**
 * The "Voided" badge (37-12, UI-SPEC § B4/B5): white with a heavy slate border and the
 * Ban icon, so it reads by word and shape, never by colour alone. One component for
 * every place a voided order or its sale appears (order detail, Orders list, finance).
 */
export function VoidedBadge({ className }: { className?: string }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        "flex w-fit items-center gap-1 border-2 border-slate-900 bg-white text-slate-900",
        className
      )}
    >
      <Ban className="h-3 w-3" aria-hidden="true" />
      Voided
    </Badge>
  )
}
