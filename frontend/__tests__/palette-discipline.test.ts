/**
 * @jest-environment node
 *
 * Palette / type / IA discipline gates (Phase 19 UIX-06, backlog #10/#11/#13).
 *
 * These are static grep gates over the shipped `app/` and `components/` trees.
 * They fail CI on any regression of the cross-cutting sweep in plan 19-08:
 *   #10  the undocumented "purple" 7th hue must stay removed (→ amber/blue)
 *   #11  no off-scale sub-12px arbitrary text token (text-[10px] et al.) — the
 *        12px accessibility floor is `text-xs`
 *   marketing surfaces must use palette tokens, not raw hex colors
 *   IA   the guest `/track` entry point must stay wired app-wide
 *
 * This file lives in `__tests__/` (outside app/ + components/) so its own
 * pattern literals are never scanned by the gates below.
 */
import { execFileSync } from "child_process"
import path from "path"

const FRONTEND_ROOT = path.resolve(__dirname, "..")

/**
 * Run grep from the frontend root and return the number of matching lines
 * (or matches, with -o). grep exits 1 when there are zero matches — that is a
 * count of 0, not a failure, so we translate it rather than throw.
 */
function grepCount(args: string[]): number {
  try {
    const out = execFileSync("grep", args, {
      cwd: FRONTEND_ROOT,
      encoding: "utf8",
    })
    return out.split("\n").filter((line) => line.trim().length > 0).length
  } catch (err) {
    const e = err as { status?: number }
    if (e && e.status === 1) return 0 // no matches
    throw err
  }
}

describe("palette / type / IA discipline gates (19-08 · UIX-06)", () => {
  it("has zero undocumented purple hue app-wide (#10)", () => {
    expect(grepCount(["-rn", "purple-", "app", "components"])).toBe(0)
    // also catch a bare `purple-<n>` fragment (e.g. in a template literal)
    expect(grepCount(["-rno", "purple-[0-9]*", "app", "components"])).toBe(0)
  })

  it("has zero off-scale sub-12px text tokens app-wide (#11)", () => {
    // text-[10px] was the primary offender; lock the whole sub-12px range so a
    // text-[9px]/text-[11px] can't creep back below the 12px accessibility floor.
    expect(grepCount(["-rn", "text-\\[10px\\]", "app", "components"])).toBe(0)
    expect(grepCount(["-rnE", "text-\\[([0-9]|1[01])px\\]", "app", "components"])).toBe(0)
  })

  it("keeps marketing surfaces on palette tokens, not raw hex colors", () => {
    expect(grepCount(["-rnoE", "#[0-9a-fA-F]{3,8}", "components/marketing"])).toBe(0)
  })

  it("keeps the guest /track entry point wired app-wide (IA)", () => {
    expect(grepCount(["-rn", 'href="/track"', "app", "components"])).toBeGreaterThanOrEqual(3)
  })
})
