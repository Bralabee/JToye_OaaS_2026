/**
 * @jest-environment node
 *
 * Contract test for /api/health — the k8s liveness/readiness probe endpoint.
 * It must be reachable with no auth cookie and return exactly
 * {"status":"ok"} / 200.
 */

import { GET } from "../route"

describe("/api/health", () => {
  it("returns 200", async () => {
    const res = await GET()
    expect(res.status).toBe(200)
  })

  it("returns a {status:'ok'} body", async () => {
    const res = await GET()
    await expect(res.json()).resolves.toEqual({ status: "ok" })
  })
})
