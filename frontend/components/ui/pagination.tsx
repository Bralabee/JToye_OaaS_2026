"use client"

import { Button } from "@/components/ui/button"
import { IconButton } from "@/components/ui/icon-button"
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from "lucide-react"

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
    <div className="flex items-center justify-between border-t pt-4">
      <p className="text-sm text-slate-600">
        Showing {start}-{end} of {totalElements}
      </p>
      {/* nav + aria-label: four of these controls are icon-only, so without a
          name a screen reader announced four consecutive "button"s (#451). The
          page-number buttons already carry text, but `aria-current` is what
          tells a non-visual user WHICH page they are on — the orange fill does
          not survive into the accessibility tree. */}
      <nav className="flex items-center gap-1" aria-label="Pagination">
        <IconButton
          variant="outline"
          onClick={() => onPageChange(0)}
          disabled={currentPage === 0}
          label="Go to first page"
          icon={<ChevronsLeft className="h-4 w-4" />}
        />
        <IconButton
          variant="outline"
          onClick={() => onPageChange(currentPage - 1)}
          disabled={currentPage === 0}
          label="Go to previous page"
          icon={<ChevronLeft className="h-4 w-4" />}
        />
        {pages.map((page) => (
          <Button
            key={page}
            variant={page === currentPage ? "default" : "outline"}
            size="sm"
            onClick={() => onPageChange(page)}
            className="h-8 w-8 p-0"
            aria-label={`Go to page ${page + 1}`}
            aria-current={page === currentPage ? "page" : undefined}
          >
            {page + 1}
          </Button>
        ))}
        <IconButton
          variant="outline"
          onClick={() => onPageChange(currentPage + 1)}
          disabled={currentPage >= totalPages - 1}
          label="Go to next page"
          icon={<ChevronRight className="h-4 w-4" />}
        />
        <IconButton
          variant="outline"
          onClick={() => onPageChange(totalPages - 1)}
          disabled={currentPage >= totalPages - 1}
          label="Go to last page"
          icon={<ChevronsRight className="h-4 w-4" />}
        />
      </nav>
    </div>
  )
}
