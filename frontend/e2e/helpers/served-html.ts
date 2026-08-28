/**
 * The raw-HTML instrument: read what the SERVER returned, not what the browser
 * ended up showing.
 *
 * WHY THIS FILE EXISTS
 *
 * The whole question these helpers answer is what arrives BEFORE JavaScript
 * runs. A `page.goto` + `expect(locator)` cannot answer it: it sees the union
 * of the server render and the browser render, and a client-side fetch fills
 * the DOM in about two and a half seconds while Playwright patiently waits. So
 * the DOM assertion passes identically against a server that rendered the
 * content and against a server that rendered nothing at all — measured on the
 * pre-#507 tree, where `/shop/brixton-village-grill` served 34,419 bytes with 1
 * spinner, 0 `<h1>` and 0 occurrences of the shop's own name while every DOM
 * assertion stayed green.
 *
 * `request.get` performs no navigation, runs no script, and hands back the
 * bytes the crawler and the first paint actually get. It is also NOT
 * intercepted by `context.route` — see `e2e/ssr-coverage.spec.ts`, which proves
 * that in one run, with a live stub as its own positive control. That is the
 * property that makes this module a coverage instrument rather than a
 * convenience: a route-interception stub can satisfy the DOM and cannot satisfy
 * these functions.
 *
 * WHY A PLAIN MODULE AND NOT A SPEC
 *
 * These five functions were grown inside `e2e/storefront-ssr-seo.spec.ts` and
 * are now needed by `e2e/ssr-coverage.spec.ts` as well. Copying them would
 * create two definitions of "what did the server actually serve", and the whole
 * point of the instrument is that there is exactly one answer to that question.
 *
 * Importing one spec file from another is not an option: it EXECUTES the
 * imported module's body, which registers every `test.describe` in the
 * importing file's scope as well — the SEO suite would then run twice and its
 * accounting would be wrong. A plain module is not collected by Playwright
 * (`testMatch` is `*.spec.ts`), so it is the only shape that shares code
 * without sharing tests. Same reasoning, and same shape, as
 * `e2e/helpers/public-surface.ts`.
 *
 * WHAT THIS FILE MUST NOT DECLARE
 *
 * No origin, no host, no port, and no base-URL environment-variable fallback of
 * any kind. `playwright.config.ts` is the only base-URL authority (#505) and
 * `scripts/check-e2e-baseurl-contract.sh` enforces that — but its scan covers
 * `*.spec.ts` files ONLY, so a constant declared here would sit OUTSIDE the
 * gate it is supposed to satisfy and would diverge silently, which is exactly
 * the failure #505 was. These helpers therefore take relative paths and let
 * Playwright resolve them against the configured `baseURL`.
 *
 * The forbidden tokens are described rather than spelled out on purpose: the
 * plan's acceptance check for this file is a grep for those literals, and a
 * docblock that names the string it forbids would fire the rule on its own
 * definition. `scripts/check-e2e-baseurl-contract.sh` and `frontend/e2e/
 * helpers/public-surface.ts` carry the full statement of the contract.
 */
import { expect, type APIRequestContext } from "@playwright/test"

/** The raw response body — no browser, no hydration, no waiting. */
export async function servedHtml(request: APIRequestContext, path: string): Promise<string> {
  const res = await request.get(path)
  expect(res.status(), `${path} should serve 200`).toBe(200)
  return res.text()
}

export function countOf(html: string, needle: string | RegExp): number {
  const re =
    typeof needle === "string"
      ? new RegExp(needle.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "g")
      : new RegExp(needle.source, needle.flags.includes("g") ? needle.flags : needle.flags + "g")
  return (html.match(re) ?? []).length
}

export function titleOf(html: string): string | null {
  const m = html.match(/<title[^>]*>([\s\S]*?)<\/title>/)
  // Next escapes the apostrophe in "J'Toye" as &#x27;. Normalise so a title is
  // compared as text rather than as an encoding.
  return m ? m[1].replace(/&#x27;/g, "'").replace(/&amp;/g, "&").trim() : null
}

/** Every `<script type="application/ld+json">` payload, parsed. */
export function jsonLdNodes(html: string): unknown[] {
  const blocks = [
    ...html.matchAll(/<script[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g),
  ]
  const nodes: unknown[] = []
  for (const [, body] of blocks) {
    const parsed: unknown = JSON.parse(body) // throws -> the block fails, which is correct
    nodes.push(...(Array.isArray(parsed) ? parsed : [parsed]))
  }
  return nodes
}

export function typesOf(nodes: unknown[]): string[] {
  return nodes.map((n) => (n as { "@type"?: string })["@type"] ?? "(none)")
}
