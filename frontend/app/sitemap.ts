import type { MetadataRoute } from "next"

// Machine-readable sitemap for the PUBLIC surface only (served at /sitemap.xml).
// Authenticated vendor dashboard routes and per-shop dynamic storefronts are
// deliberately excluded: dashboards must not be indexed, and shop slugs are
// tenant data that would require a DB round-trip at build time. The human
// audience-classified inventory (all 22 pages, incl. dashboard) lives in
// docs/SITEMAP.md — keep both in sync when adding pages.
// Base URL is environment-injected (never hardcoded): set NEXT_PUBLIC_SITE_URL
// in production (e.g. https://jtoye.co.uk); the fallback matches local compose.
const BASE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3100"

export default function sitemap(): MetadataRoute.Sitemap {
  const now = new Date()
  return [
    { url: `${BASE_URL}/`, lastModified: now, changeFrequency: "weekly", priority: 1 },
    { url: `${BASE_URL}/shop`, lastModified: now, changeFrequency: "daily", priority: 0.9 },
    { url: `${BASE_URL}/for-operators`, lastModified: now, changeFrequency: "monthly", priority: 0.8 },
    { url: `${BASE_URL}/business-model-guide`, lastModified: now, changeFrequency: "monthly", priority: 0.5 },
    { url: `${BASE_URL}/track`, lastModified: now, changeFrequency: "monthly", priority: 0.4 },
  ]
}
