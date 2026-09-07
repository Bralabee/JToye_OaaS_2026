/**
 * QA council 20260902-134741 — A11Y-5: horizontally-scrolling tables were
 * unreachable by keyboard.
 *
 * `components/ui/table.tsx` wraps every `<table>` in `overflow-auto` — that
 * div IS the scrolling node, and it carried no tabIndex, role or name, so axe
 * `scrollable-region-focusable` (serious) fired wherever a table actually
 * overflowed (/dashboard, /dashboard/finance at 320/390). The fix puts
 * `role="region" tabIndex={0} aria-label` on that same node, named by a
 * REQUIRED `containerLabel` prop so a table can never ship as an unnamed
 * scroller again, and the importers' own `overflow-x-auto` wrappers are
 * removed so nothing double-nests (plan-frontend-a11y §4 A11Y-5, plan.md A20).
 *
 * jsdom has no layout, so this file proves the structure; the browser truth
 * (axe 0 nodes where content overflows) is probes/a11y/03 + 09 after rebuild.
 */
import { render, screen } from "@testing-library/react"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

function renderTable() {
  return render(
    <Table containerLabel="Orders table">
      <TableHeader>
        <TableRow>
          <TableHead>Reference</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow>
          <TableCell>ORD-1</TableCell>
        </TableRow>
      </TableBody>
    </Table>
  )
}

describe("Table — the scrolling node is a focusable, named region (A11Y-5)", () => {
  it("exposes the overflow wrapper as role=region with tabIndex=0 and the given label", () => {
    renderTable()
    const region = screen.getByRole("region", { name: "Orders table" })
    expect(region).toHaveAttribute("tabIndex", "0")
    // It is the node that scrolls — not a wrapper around a wrapper.
    expect(region).toHaveClass("overflow-auto")
    expect(region).toBe(screen.getByRole("table").parentElement)
  })

  it("gives the new tab stop a visible focus ring", () => {
    renderTable()
    const region = screen.getByRole("region", { name: "Orders table" })
    expect(region.className).toMatch(/focus-visible:ring-2/)
  })

  it("does not nest a second scroll container inside the region", () => {
    renderTable()
    const region = screen.getByRole("region", { name: "Orders table" })
    const nested = region.querySelectorAll('[class*="overflow-auto"], [class*="overflow-x-auto"]')
    expect(nested).toHaveLength(0)
  })
})
