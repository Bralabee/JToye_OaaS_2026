import { test, expect } from "@playwright/test"

/**
 * #836 / SW-CLR-05 (owner ruling 2026-11-04, persona close-out): the internal strategy
 * pages and the guide's printable PDF are gone, so a direct hit is a 404, not a noindexed
 * page. Stack-free: the app's own not-found answers, no backend is consulted.
 */
for (const path of ["/competitive", "/business-model-guide", "/business-model-guide.pdf"]) {
  test(`@stack-free ${path} is a 404`, async ({ request }) => {
    const res = await request.get(path, { maxRedirects: 0 })
    expect(res.status()).toBe(404)
    const body = await res.text()
    expect(body).not.toMatch(/Do not claim|docs\/analysis|Raised 0/i)
  })
}
