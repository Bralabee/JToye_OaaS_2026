/**
 * Centralised font loader for the Warm Editorial design system.
 *
 * All three typefaces are loaded via `next/font/google` so they are:
 *   - downloaded at build time (no runtime third-party font CDN)
 *   - served from `/_next/static/media/` (same-origin -> CSP `font-src 'self'` safe)
 *   - exposed to the browser as CSS variables so Tailwind and custom CSS can
 *     read them without any component-level className plumbing.
 *
 * OpenType feature activation (cv05, cv11, ss01, tnum, etc.) lives in
 * `app/globals.css` per DESIGN-SPEC.md §3.4 — it is applied at the CSS layer
 * on `body`, `.font-mono`, and Fraunces display scales, not at font-load time.
 */

import { Fraunces, Inter, Geist_Mono } from "next/font/google";

/**
 * Fraunces — variable display serif.
 * Used for hero / marketing / editorial headlines.
 * Axes: opsz, wght, SOFT, WONK (spec §3.1). Italic supported for pull quotes.
 */
export const fraunces = Fraunces({
  subsets: ["latin"],
  variable: "--font-display",
  display: "swap",
  style: ["normal", "italic"],
  axes: ["opsz", "SOFT", "WONK"],
});

/**
 * Inter — variable body/UI face.
 * Used for all default UI copy, form text, nav, tables.
 * Full weight range loaded so `font-variable` typographic effects (e.g.
 * hero subheads at 300, badges at 600) all resolve against the same axis.
 */
export const inter = Inter({
  subsets: ["latin"],
  variable: "--font-sans",
  display: "swap",
  weight: ["100", "200", "300", "400", "500", "600", "700", "800", "900"],
});

/**
 * Geist Mono — variable monospace.
 * Used for KDS order numbers, financial ledger amounts, order IDs, code.
 * `tnum`/`ss01` feature activation happens in globals.css on `.font-mono`.
 */
export const geistMono = Geist_Mono({
  subsets: ["latin"],
  variable: "--font-mono",
  display: "swap",
  weight: ["400", "500", "600", "700"],
});

/**
 * Combined variable class string for use on the root `<html>` element in
 * `app/layout.tsx`. Exposes all three CSS custom properties
 * (`--font-display`, `--font-sans`, `--font-mono`) globally.
 */
export const fontVariables = `${fraunces.variable} ${inter.variable} ${geistMono.variable}`;
