"use client"

import { Button } from "@/components/ui/button"
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
      <div className="flex items-center gap-1">
        <Button
          variant="outline"
          size="sm"
          onClick={() => onPageChange(0)}
          disabled={currentPage === 0}
          className="h-8 w-8 p-0"
        >
          <ChevronsLeft className="h-4 w-4" />
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={() => onPageChange(currentPage - 1)}
          disabled={currentPage === 0}
          className="h-8 w-8 p-0"
        >
          <ChevronLeft className="h-4 w-4" />
        </Button>
        {pages.map((page) => (
          <Button
            key={page}
            variant={page === currentPage ? "default" : "outline"}
            size="sm"
            onClick={() => onPageChange(page)}
            className="h-8 w-8 p-0"
          >
            {page + 1}
          </Button>
        ))}
        <Button
          variant="outline"
          size="sm"
          onClick={() => onPageChange(currentPage + 1)}
          disabled={currentPage >= totalPages - 1}
          className="h-8 w-8 p-0"
        >
          <ChevronRight className="h-4 w-4" />
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={() => onPageChange(totalPages - 1)}
          disabled={currentPage >= totalPages - 1}
          className="h-8 w-8 p-0"
        >
          <ChevronsRight className="h-4 w-4" />
        </Button>
      </div>
    </div>
  )
}
