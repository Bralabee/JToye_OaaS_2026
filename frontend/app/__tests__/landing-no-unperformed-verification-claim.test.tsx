/**
 * #788 / SW-CLR-14 (owner ruling 2026-11-04, persona close-out): the landing page must
 * not claim a food-hygiene VERIFICATION that nobody performs. A FHRS gate that finds no
 * match, or an ambiguous one, goes to MANUAL_REVIEW, and with no platform operator that
 * review can be resolved by the vendor's own admin; the public shop DTO carries no FHRS
 * rating at all. So "UK food-hygiene verified" was a statement the platform could not
 * stand behind. The ruling: remove it. The FSA manual-review adjudication stays unbuilt.
 *
 * Two instruments, because the rendered page proves the landing and the source scan
 * proves no OTHER public surface (footer, operators page, legal, shop card) grew the
 * same sentence under a different file.
 */
import fs from "fs"
import path from "path"
import { render } from "@testing-library/react"
import Home from "@/app/page"

jest.mock("@/lib/storefront-server", () => ({
  loadShopList: jest.fn(async () => ({
    state: "ok",
    data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 8 },
  })),
  loadFeaturedShops: jest.fn(async () => []),
}))
jest.mock("next/headers", () => ({
  headers: async () => ({ get: () => null }),
}))

/** A hygiene claim that asserts verification/checking by the platform. */
const UNPERFORMED_CLAIM =
  /(food[- ]hygiene|hygiene rating|FHRS)[^.\n"'`]{0,40}\b(verified|checked|approved|certified)\b|\b(verified|checked|approved|certified)\b[^.\n"'`]{0,25}(food[- ]hygiene|hygiene rating|FHRS)/i

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === "__tests__" || entry.name === "node_modules" || entry.name === ".next") continue
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) sourceFiles(full, out)
    else if (/\.(tsx?|mdx?)$/.test(entry.name)) out.push(full)
  }
  return out
}

describe("no unperformed hygiene-verification claim (#788, SW-CLR-14)", () => {
  it("the rendered landing page does not say the kitchens are food-hygiene verified", async () => {
    const { container } = render(await Home())
    const text = container.textContent ?? ""
    // Control: the page rendered its other trust markers, so absence is not an empty render.
    expect(text).toMatch(/allergen info on every item/i)
    expect(text).not.toMatch(/food-hygiene verified/i)
    expect(text).not.toMatch(UNPERFORMED_CLAIM)
  })

  it("no public-surface source (app/, components/, lib/) carries the claim", () => {
    const root = process.cwd()
    const offenders: string[] = []
    for (const dir of ["app", "components", "lib"]) {
      for (const file of sourceFiles(path.join(root, dir))) {
        // The vendor dashboard is not a public claim: its admin placeholder tells a reviewer
        // what a MANUAL review note could say. Public surfaces are everything else.
        if (path.relative(root, file).startsWith("app/dashboard")) continue
        const lines = fs.readFileSync(file, "utf8").split("\n")
        lines.forEach((line, i) => {
          if (/^\s*(\/\/|\*|\/\*)/.test(line)) return // a comment recording the ruling may name it
          if (/food-hygiene verified/i.test(line) || UNPERFORMED_CLAIM.test(line)) {
            offenders.push(`${path.relative(root, file)}:${i + 1}: ${line.trim().slice(0, 120)}`)
          }
        })
      }
    }
    expect(offenders).toEqual([])
  })
})
