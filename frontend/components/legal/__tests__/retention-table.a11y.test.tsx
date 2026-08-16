/**
 * The published retention schedule: accessibility, and the transcription gate.
 *
 * TWO JOBS, AND THE SECOND ONE IS THE LOAD-BEARING ONE.
 *
 * 1. Accessibility — a jest-axe scan of the real table, with a NON-VACUITY
 *    CONTROL asserted before the scan. This project has already paid for the
 *    alternative: a naive axe run once reported "0 button-name violations" over
 *    tables that never mounted. A scan of an empty table returns zero violations
 *    and is indistinguishable from a pass, so the last test in this file renders
 *    exactly that and proves the artefact is real rather than describing it.
 *
 * 2. The transcription gate — `app/legal/retention/page.tsx` cannot import
 *    `docs/retention-manifest.json` (Turbopack refuses to resolve outside the
 *    frontend project root; the failure is recorded in that file's header), so
 *    its rows are a SECOND COPY of a legally operative document. Jest's resolver
 *    has no such restriction, so this file reads the manifest directly and
 *    asserts the two agree FIELD BY FIELD — not just the four gated numbers.
 *
 *    That distinction matters. `scripts/gates/claims.manifest` gates the four
 *    periods that are integers; it cannot gate "Kept indefinitely - deliberately
 *    never deleted", a lawful basis, or a category name, because the claim
 *    engine compares integers and semvers. Eight of the twelve rows publish no
 *    number at all. Without the deep comparison below, every non-numeric word on
 *    that page could drift from the manifest with nothing noticing.
 *
 *    The row COUNT is asserted against the manifest's own count rather than a
 *    hardcoded 12, so adding a row to the manifest without publishing it fails
 *    here instead of silently shipping a schedule that omits a category.
 */
import { render, screen, within } from "@testing-library/react"
import { axe, toHaveNoViolations } from "jest-axe"

import manifest from "../../../../docs/retention-manifest.json"
import { RETENTION_ROWS } from "@/app/legal/retention/page"
import {
  RETENTION_COLUMNS,
  RETENTION_REGION_LABEL,
  RetentionTable,
} from "@/components/legal/retention-table"

expect.extend(toHaveNoViolations)

// axe walks the whole subtree and jsdom has no layout; the default 5s timeout is
// tight enough to flake on a loaded CI runner.
const AXE_TIMEOUT_MS = 30_000

/** The manifest rows, in the shape the page is expected to publish them. */
const MANIFEST_ROWS = manifest.rows.map((row) => ({
  id: row.id,
  category: row.category,
  detail: row.detail,
  period: row.period_display,
  lawfulBasis: row.lawful_basis,
  enforcement: row.enforcement,
}))

/** Body rows only — `getAllByRole("row")` includes the header row. */
function bodyRows(): HTMLElement[] {
  return screen.getAllByRole("row").slice(1)
}

describe("RetentionTable — non-vacuity control", () => {
  it("actually renders a table with a caption, real rows and four column headers", () => {
    render(<RetentionTable rows={RETENTION_ROWS} />)

    // A <caption> has no ARIA role, so it is asserted through the DOM. It is
    // also the table's accessible name, which the region label below is not.
    const table = screen.getByRole("table")
    expect(table.querySelectorAll("caption")).toHaveLength(1)
    expect(table.querySelector("caption")?.textContent ?? "").not.toHaveLength(0)

    expect(bodyRows().length).toBeGreaterThan(1)
    expect(
      table.querySelectorAll("th[scope='col'], th[scope='row']").length
    ).toBeGreaterThanOrEqual(4)
    expect(table.querySelectorAll("th[scope='col']")).toHaveLength(
      RETENTION_COLUMNS.length
    )
  })

  it("wraps the table in a focusable, labelled scroll region", () => {
    render(<RetentionTable rows={RETENTION_ROWS} />)

    // axe's scrollable-region-focusable fails an unfocusable scroll container,
    // and without tabindex the table is keyboard-unreachable if it ever does
    // overflow. The region is the safety net, so it must itself be sound.
    const region = screen.getByRole("region", { name: RETENTION_REGION_LABEL })
    expect(region).toHaveAttribute("tabindex", "0")
    expect(region.className).toContain("overflow-x-auto")
  })
})

describe("RetentionTable — accessibility", () => {
  it(
    "has no axe violations, with the fixture proven to have rendered first",
    async () => {
      const { container } = render(<RetentionTable rows={RETENTION_ROWS} />)

      // CONTROL FIRST. Everything below is worth exactly as much as this.
      expect(bodyRows().length).toBeGreaterThan(1)
      expect(container.querySelectorAll("caption")).toHaveLength(1)

      expect(await axe(container)).toHaveNoViolations()
    },
    AXE_TIMEOUT_MS
  )

  it("gives every row a row-scoped header naming its data category", () => {
    render(<RetentionTable rows={RETENTION_ROWS} />)

    const headers = screen.getAllByRole("rowheader")
    expect(headers).toHaveLength(RETENTION_ROWS.length)
    headers.forEach((header, i) => {
      expect(header).toHaveAttribute("scope", "row")
      expect(header.textContent ?? "").toContain(RETENTION_ROWS[i].category)
    })
  })
})

describe("RetentionTable — the enforcement column carries words, not colour", () => {
  it("renders the literal word Automated or Operational in every enforcement cell", () => {
    render(<RetentionTable rows={RETENTION_ROWS} />)

    const rows = bodyRows()
    expect(rows).toHaveLength(RETENTION_ROWS.length)

    rows.forEach((row, i) => {
      // The row header is a `rowheader`, so `cell` yields exactly the three
      // <td>s: period, lawful basis, enforcement. The last is the badge cell.
      const cells = within(row).getAllByRole("cell")
      expect(cells).toHaveLength(3)

      const enforcement = (cells[2].textContent ?? "").trim()
      expect(enforcement).toBe(RETENTION_ROWS[i].enforcement)
      expect(["Automated", "Operational"]).toContain(enforcement)
    })
  })

  it("publishes no empty cell and no placeholder anywhere", () => {
    render(<RetentionTable rows={RETENTION_ROWS} />)

    bodyRows().forEach((row) => {
      const text = row.textContent ?? ""
      expect(text.trim().length).toBeGreaterThan(0)
      // A published TBD in a retention schedule is a defect, not a draft.
      expect(text).not.toMatch(/\bTBD\b/i)
      expect(text).not.toMatch(/\bTODO\b/i)

      const cells = within(row).getAllByRole("cell")
      cells.forEach((cell) => {
        expect((cell.textContent ?? "").trim().length).toBeGreaterThan(0)
      })
    })
  })
})

describe("the published rows are the manifest's rows", () => {
  it("publishes exactly as many rows as the manifest declares", () => {
    render(<RetentionTable rows={RETENTION_ROWS} />)

    // Both numbers are READ, neither is hardcoded: a row added to the manifest
    // and not published fails here rather than shipping a schedule that omits it.
    expect(MANIFEST_ROWS.length).toBeGreaterThan(0)
    expect(RETENTION_ROWS.length).toBe(MANIFEST_ROWS.length)
    expect(bodyRows().length).toBe(MANIFEST_ROWS.length)
  })

  it("publishes every field verbatim from the manifest, in manifest order", () => {
    // The page transcribes because Turbopack cannot import from docs/. This is
    // the assertion that makes the transcription safe, and it covers the eight
    // rows that publish prose rather than a number — which no claims rule can.
    expect(RETENTION_ROWS).toEqual(MANIFEST_ROWS)
  })

  it("renders each manifest row's category, period and lawful basis on the page", () => {
    render(<RetentionTable rows={RETENTION_ROWS} />)

    const rows = bodyRows()
    MANIFEST_ROWS.forEach((expected, i) => {
      const text = rows[i].textContent ?? ""
      expect(text).toContain(expected.category)
      expect(text).toContain(expected.period)
      expect(text).toContain(expected.lawfulBasis)
    })
  })

  it("marks every row Automated or Operational and nothing else", () => {
    MANIFEST_ROWS.forEach((row) => {
      expect(["Automated", "Operational"]).toContain(row.enforcement)
    })
  })
})

/**
 * PERMANENT ARTEFACT ARM — kept, not deleted after one run.
 *
 * An axe scan over an empty table reports ZERO violations. That result is
 * byte-identical to the result for a correct table, which is why every scan in
 * this file is preceded by a control. This test renders the empty case and
 * asserts BOTH halves: axe is clean, and the control is what notices.
 */
describe("the empty-table artefact this file's controls exist to catch", () => {
  it(
    "reports zero axe violations over a table with no rows — the control is what fires",
    async () => {
      const { container } = render(<RetentionTable rows={[]} />)

      // axe is perfectly happy. This is the false negative.
      expect(await axe(container)).toHaveNoViolations()

      // The control is the only thing that can tell the difference.
      expect(container.querySelectorAll("tbody tr")).toHaveLength(0)
      expect(screen.getAllByRole("row").slice(1).length).not.toBeGreaterThan(1)
      expect(screen.queryAllByRole("rowheader")).toHaveLength(0)
    },
    AXE_TIMEOUT_MS
  )
})
