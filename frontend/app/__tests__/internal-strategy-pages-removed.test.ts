/**
 * #836 / SW-CLR-05 (owner ruling 2026-11-04, persona close-out): the internal strategy
 * pages (/competitive teardown and /business-model-guide) are OFF the public site. Not
 * noindexed, not unlinked: deleted, so a direct hit is the app's 404. They carried the
 * platform's own "Do not claim" list, a repository path, "Raised 0 GBP", and a payouts
 * row that contradicted the landing page.
 *
 * The routes are App Router directories, so "a direct hit returns 404" is proven by the
 * absence of `page.tsx` under `app/<route>/`: Next serves the root not-found for an
 * unmatched path. `e2e/internal-strategy-404.spec.ts` asserts the HTTP status against a
 * running stack.
 */
import fs from "fs"
import path from "path"
import { PUBLIC_STATIC_ROUTES } from "@/lib/public-routes"

const root = process.cwd()
const ROUTES = ["business-model-guide", "competitive"] as const

function files(dir: string, out: string[] = []): string[] {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === "node_modules" || e.name === ".next" || e.name === "__tests__") continue
    const full = path.join(dir, e.name)
    if (e.isDirectory()) files(full, out)
    else out.push(full)
  }
  return out
}

describe("internal strategy pages are not served (#836, SW-CLR-05)", () => {
  it.each(ROUTES)("app/%s has no page, so a direct hit is a 404", (route) => {
    expect(fs.existsSync(path.join(root, "app", route, "page.tsx"))).toBe(false)
    expect(fs.existsSync(path.join(root, "app", route))).toBe(false)
  })

  it("the printable PDF of the guide is not shipped from public/", () => {
    expect(fs.existsSync(path.join(root, "public", "business-model-guide.pdf"))).toBe(false)
  })

  it("the sitemap's static routes list neither page", () => {
    const paths = PUBLIC_STATIC_ROUTES.map((r) => r.path)
    expect(paths.length).toBeGreaterThan(0) // control: the list is real
    for (const r of ROUTES) expect(paths).not.toContain(`/${r}`)
  })

  it("nothing in app/, components/ or lib/ links to either route", () => {
    const offenders: string[] = []
    for (const dir of ["app", "components", "lib"]) {
      for (const file of files(path.join(root, dir))) {
        if (!/\.(tsx?|mjs)$/.test(file)) continue
        const src = fs.readFileSync(file, "utf8")
        const hit = src.match(/["'`(]\/(competitive|business-model-guide)(\.pdf)?["'`)#?]/)
        if (hit) offenders.push(`${path.relative(root, file)}: ${hit[0]}`)
      }
    }
    expect(offenders).toEqual([])
  })

  it("no public page carries a repository path (docs/analysis, .planning)", () => {
    const offenders: string[] = []
    for (const dir of ["app", "components"]) {
      for (const file of files(path.join(root, dir))) {
        if (!/\.tsx?$/.test(file)) continue
        const lines = fs.readFileSync(file, "utf8").split("\n")
        lines.forEach((l, i) => {
          if (/^\s*(\/\/|\*|\/\*)/.test(l)) return
          if (/docs\/analysis\/|\.planning\/|core-java\/src\//.test(l)) {
            offenders.push(`${path.relative(root, file)}:${i + 1}`)
          }
        })
      }
    }
    expect(offenders).toEqual([])
  })
})
