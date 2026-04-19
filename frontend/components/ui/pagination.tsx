"use client"

import { Button } from "@/components/ui/button"
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from "lucide-react"
import { cn } from "@/lib/utils"

interface PaginationProps {
  currentPage: number
  totalPages: number
  totalElements: number
  pageSize: number
  onPageChange: (page: number) => void
}

export function Pagination({
  currentPage,
  totalPages,
  totalElements,
  pageSize,
  onPageChange,
}: PaginationProps) {
  if (totalPages <= 1) return null

  const start = currentPage * pageSize + 1
  const end = Math.min((currentPage + 1) * pageSize, totalElements)

  // Build page numbers to display (max 5 visible)
  const pages: number[] = []
  const maxVisible = 5
  let startPage = Math.max(0, currentPage - Math.floor(maxVisible / 2))
  const endPage = Math.min(totalPages, startPage + maxVisible)
  if (endPage - startPage < maxVisible) {
    startPage = Math.max(0, endPage - maxVisible)
  }
  for (let i = startPage; i < endPage; i++) {
    pages.push(i)
  }

  return (
    <div className="flex items-center justify-between border-t border-subtle pt-4">
      <p className="text-sm text-ink-secondary">
        Showing{" "}
        <span className="font-mono tabular-nums text-ink-primary">
          {start}-{end}
        </span>{" "}
        of{" "}
        <span className="font-mono tabular-nums text-ink-primary">{totalElements}</span>
      </p>
      <div className="flex items-center gap-1">
        <Button
          variant="ghost"
          size="iconSm"
          onClick={() => onPageChange(0)}
          disabled={currentPage === 0}
          aria-label="First page"
        >
          <ChevronsLeft className="h-4 w-4" />
        </Button>
        <Button
          variant="ghost"
          size="iconSm"
          onClick={() => onPageChange(currentPage - 1)}
          disabled={currentPage === 0}
          aria-label="Previous page"
        >
          <ChevronLeft className="h-4 w-4" />
        </Button>
        {pages.map((page) => {
          const isCurrent = page === currentPage
          return (
            <button
              key={page}
              type="button"
              aria-current={isCurrent ? "page" : undefined}
              aria-label={`Page ${page + 1}`}
              onClick={() => onPageChange(page)}
              className={cn(
                "inline-flex h-8 min-w-8 items-center justify-center rounded-pill px-2",
                "text-xs font-mono tabular-nums font-semibold transition-colors duration-fast",
                "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-border-tone-focus focus-visible:ring-offset-2 focus-visible:ring-offset-surface-canvas",
                isCurrent
                  ? "bg-brand-primary-subtle text-brand-primary"
                  : "text-ink-secondary hover:text-ink-primary hover:bg-surface-subtle",
              )}
            >
              {page + 1}
            </button>
          )
        })}
        <Button
          variant="ghost"
          size="iconSm"
          onClick={() => onPageChange(currentPage + 1)}
          disabled={currentPage >= totalPages - 1}
          aria-label="Next page"
        >
          <ChevronRight className="h-4 w-4" />
        </Button>
        <Button
          variant="ghost"
          size="iconSm"
          onClick={() => onPageChange(totalPages - 1)}
          disabled={currentPage >= totalPages - 1}
          aria-label="Last page"
        >
          <ChevronsRight className="h-4 w-4" />
        </Button>
      </div>
    </div>
  )
}
