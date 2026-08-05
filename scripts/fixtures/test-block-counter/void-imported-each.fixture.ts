// EXPECT: VOID (rc 2) — the .each table IS a bare identifier, but it is imported,
// so there is no array literal in this file to count. The counter resolves an
// in-file `const NAME = [...]`; it must refuse anything it cannot see, rather than
// fall back to 1 (which is what "count the declaration" would silently do).
import { hostileRedirects } from "./cases"

describe("x", () => {
  it.each(hostileRedirects)("row %p", (n) => { expect(n).toBeDefined() })
})
