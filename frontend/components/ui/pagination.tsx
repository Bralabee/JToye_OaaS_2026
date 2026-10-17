"use client"

import { Button } from "@/components/ui/button"
import { IconButton } from "@/components/ui/icon-button"
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from "lucide-react"

/*
 * WHY THE CONTROLS ARE TIERED (G-37-6 defect 3, UAT test 6). This was a fixed row of nine
 * 32 px buttons (9 x 32 + 8 x 4 = 320 px). In the orders card at 390 px (about 308 px of
 * content) the last-page button sat on the screen edge outside the card, and every target
 * was under the phase's 44 x 44 px minimum (UI-SPEC Spacing exceptions).
 *
 * The root is an inline-size container and each control chooses itself from the
 * component's OWN width, not the viewport (the same page can be a phone-width card or a
 * desktop table footer):
 *   - always:      previous, next (and "Page N of M" below 21rem)
 *   - from 19rem:  first and last      (four 44 px controls + the status ~ 300 px)
 *   - from 21rem:  a three-page window replaces the status   (seven controls ~ 332 px)
 *   - from 27rem:  the five-page window                       (nine controls ~ 428 px)
 * These are starting thresholds: 37-59 measures the real widths in Chrome and raises a
 * threshold to the next whole rem if the measurement needs it.
 *
 * Every class string below is a complete literal: Tailwind emits only whole class names it
 * reads in source, so a variant assembled from parts would render in jsdom and be missing
 * from the stylesheet.
 *
 * CALLER CAVEAT: inline-size containment makes the root ignore its content for its own
 * width. That is right for a block that fills its parent (every caller today puts it after
 * a Table inside CardContent); inside a shrink-to-fit box (an inline-flex row, a float, an
 * absolutely positioned box) it would collapse to zero width. Give it a block parent.
 */

interface PaginationProps {
  currentPage: number
  totalPages: number
  totalElements: number
  pageSize: number
  onPageChange: (page: number) => void
}

/**
 * The zero-based page indexes of a window of `size` pages centred on `currentPage` and
 * clamped to the ends (0 .. totalPages - 1). Fewer than `size` when there are fewer pages.
 */
export function paginationWindow(currentPage: number, totalPages: number, size: number): number[] {
  let startPage = Math.max(0, currentPage - Math.floor(size / 2))
  const endPage = Math.min(totalPages, startPage + size)
  if (endPage - startPage < size) {
    startPage = Math.max(0, endPage - size)
  }
  const pages: number[] = []
  for (let i = startPage; i < endPage; i++) {
    pages.push(i)
  }
  return pages
}

const EDGE_CONTROL_CLASS = "h-11 w-11 shrink-0 hidden [@container_(min-width:19rem)]:inline-flex"
const STEP_CONTROL_CLASS = "h-11 w-11 shrink-0"
const NEAR_PAGE_CLASS = "h-11 min-w-11 shrink-0 px-2 hidden [@container_(min-width:21rem)]:inline-flex"
const FAR_PAGE_CLASS = "h-11 min-w-11 shrink-0 px-2 hidden [@container_(min-width:27rem)]:inline-flex"

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

  const pages = paginationWindow(currentPage, totalPages, 5)
  const nearPages = new Set(paginationWindow(currentPage, totalPages, 3))

  return (
    <div className="[container-type:inline-size] flex flex-wrap items-center justify-between gap-x-4 gap-y-2 border-t pt-4">
      <p className="text-sm text-slate-600">
        Showing {start}-{end} of {totalElements}
      </p>
      {/* nav + aria-label: four of these controls are icon-only, so without a
          name a screen reader announced four consecutive "button"s (#451). The
          page-number buttons already carry text, but `aria-current` is what
          tells a non-visual user WHICH page they are on — the orange fill does
          not survive into the accessibility tree. */}
      <nav className="flex min-w-0 items-center gap-1" aria-label="Pagination">
        <IconButton
          variant="outline"
          onClick={() => onPageChange(0)}
          disabled={currentPage === 0}
          label="Go to first page"
          className={EDGE_CONTROL_CLASS}
          icon={<ChevronsLeft className="h-4 w-4" />}
        />
        <IconButton
          variant="outline"
          onClick={() => onPageChange(currentPage - 1)}
          disabled={currentPage === 0}
          label="Go to previous page"
          className={STEP_CONTROL_CLASS}
          icon={<ChevronLeft className="h-4 w-4" />}
        />
        {/* Below 21rem the page numbers do not fit at 44 px; this plain text says where
            the reader is instead. It may wrap for a three-digit count rather than overflow. */}
        <span className="min-w-0 px-1 text-center text-sm tabular-nums text-slate-700 [@container_(min-width:21rem)]:hidden">
          Page {currentPage + 1} of {totalPages}
        </span>
        {pages.map((page) => (
          <Button
            key={page}
            variant={page === currentPage ? "default" : "outline"}
            size="sm"
            onClick={() => onPageChange(page)}
            className={nearPages.has(page) ? NEAR_PAGE_CLASS : FAR_PAGE_CLASS}
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
          className={STEP_CONTROL_CLASS}
          icon={<ChevronRight className="h-4 w-4" />}
        />
        <IconButton
          variant="outline"
          onClick={() => onPageChange(totalPages - 1)}
          disabled={currentPage >= totalPages - 1}
          label="Go to last page"
          className={EDGE_CONTROL_CLASS}
          icon={<ChevronsRight className="h-4 w-4" />}
        />
      </nav>
    </div>
  )
}
