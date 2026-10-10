/**
 * Owner ruling 2026-10-10 (D-08 on the kitchen route, quick 261010-ppb): a shop-access-denied 403
 * anywhere on the kitchen page stops the board with the K2 "Your access has ended" overlay. That is
 * true only while every call the page makes has the STAFF floor (or none): a call whose server floor
 * were SHOP_MANAGER would be refused for a STAFF user who still has access, and the board would tell
 * them their access had ended when it had not.
 *
 * This guard keeps the invariant executable. It reads the kitchen route's own sources for every
 * `/api/v1/` path literal and requires each to be declared in KITCHEN_ROUTE_CALLS, and every declared
 * call to have a floor of STAFF or NONE. Adding a call with a higher floor means exempting it from
 * the board-stop listener first (app/dashboard/kitchen/page.tsx), then declaring it.
 */

import fs from "fs"
import path from "path"
import { KITCHEN_ROUTE_CALLS } from "@/components/dashboard/kitchen/kitchen-route-calls"

const FRONTEND = path.resolve(__dirname, "../../../..")
const KITCHEN_DIR = path.join(FRONTEND, "components/dashboard/kitchen")

/** The kitchen route's sources: the page, the kitchen components (not tests, not the registry), and the lib modules they call through. */
function kitchenSources(): string[] {
  const components = fs
    .readdirSync(KITCHEN_DIR)
    .filter((f) => /\.(ts|tsx)$/.test(f) && f !== "kitchen-route-calls.ts")
    .map((f) => path.join("components/dashboard/kitchen", f))
  return [
    "app/dashboard/kitchen/page.tsx",
    ...components,
    "lib/kitchen-orders-api.ts",
    "lib/allergy-note-api.ts",
    "lib/shops-api.ts",
  ]
}

/** `/api/v1/orders/${id}/detail?x=1` and `/api/v1/orders/{id}/detail` both become `/api/v1/orders/{}/detail`. */
function normalise(p: string): string {
  return p.split("?")[0].replace(/\$\{[^}]*\}/g, "{}").replace(/\{[^}]*\}/g, "{}")
}

function pathLiterals(): { file: string; path: string }[] {
  const found: { file: string; path: string }[] = []
  for (const file of kitchenSources()) {
    const text = fs.readFileSync(path.join(FRONTEND, file), "utf8")
    for (const m of text.matchAll(/["'`](\/api\/v1\/[^"'`]*)["'`]/g)) {
      found.push({ file, path: normalise(m[1]) })
    }
  }
  return found
}

describe("kitchen route API calls stay at the STAFF floor (D-08, owner ruling 2026-10-10)", () => {
  it("every declared kitchen-route call has a server floor of STAFF or NONE, and cites the server line", () => {
    expect(KITCHEN_ROUTE_CALLS.length).toBeGreaterThan(0)
    for (const call of KITCHEN_ROUTE_CALLS) {
      expect({ call: `${call.method} ${call.path}`, floor: call.floor }).toEqual({
        call: `${call.method} ${call.path}`,
        floor: expect.stringMatching(/^(STAFF|NONE)$/),
      })
      expect(call.server).toMatch(/^[A-Za-z]+\.java(:\d+)?/)
    }
  })

  it("every /api/v1/ path the kitchen route's sources call is declared in KITCHEN_ROUTE_CALLS", () => {
    const literals = pathLiterals()
    // Positive control: the scan sees the calls it exists to police (the board read, the bump, the pause).
    expect(literals.map((l) => l.path)).toEqual(
      expect.arrayContaining(["/api/v1/orders/kitchen", "/api/v1/orders/{}/{}", "/api/v1/shops/{}/pause"])
    )
    const declared = new Set(KITCHEN_ROUTE_CALLS.map((c) => normalise(c.path)))
    const undeclared = literals.filter((l) => !declared.has(l.path))
    expect(undeclared).toEqual([])
  })
})
