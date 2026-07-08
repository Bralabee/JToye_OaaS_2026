import { NextResponse } from "next/server"

/**
 * GET /api/health
 *
 * Unauthenticated liveness/readiness probe endpoint. Returns a fixed
 * {"status":"ok"} / 200 with no version, build, or tenant data leaked. This is
 * the exact path the k8s liveness/readiness probes and the frontend Dockerfile
 * HEALTHCHECK already call on container port 3000, so it must never be
 * prerendered or cached — the probe has to hit live code.
 */
export const dynamic = "force-dynamic"

export async function GET() {
  return NextResponse.json({ status: "ok" }, { status: 200 })
}
