# J'Toye OaaS — Brand Kit

Bespoke identity assets for the J'Toye OaaS product. All marks are hand-authored SVG, `currentColor`-driven where possible, and CSP-safe (no embedded fonts, no external refs, no external URLs).

## Swap contract

To refresh the visual identity without touching application code:

1. Overwrite the SVG file(s) below in place. Keep the filename, viewBox proportions, and any `currentColor` conventions.
2. If you add or rename an asset, update the `marks` map in `frontend/lib/brand.ts`.
3. Consumers (shells, dialogs, SEO metadata) import only from `frontend/lib/brand.ts` — they never hardcode a path.

Result: zero component changes, a single atomic PR that swaps the identity.

## File checklist

| File                                  | Canvas    | Purpose                                    |
|---------------------------------------|-----------|--------------------------------------------|
| `mark.svg`                            | 32×32     | Primary monogram (`currentColor`)          |
| `mark-dark.svg`                       | 32×32     | Monogram on fig-primary rounded square     |
| `wordmark.svg`                        | 180×40    | Full wordmark (`currentColor`)             |
| `wordmark-with-oaas.svg`              | 240×48    | Wordmark + middot + muted "OaaS"           |
| `og-default.svg`                      | 1200×630  | Default Open Graph card                    |
| `og-storefront.svg`                   | 1200×630  | Vendor-facing Open Graph card              |
| `../favicon.svg`                      | 32×32     | Browser tab favicon, 16px-safe             |
| `../apple-touch-icon.svg`             | 180×180   | iOS home-screen / touch icon               |

## Colour variable reference

The brand kit mirrors the palette defined in `frontend/app/globals.css` (see `DESIGN-SPEC.md` §2). Hard-coded HSL values in the dark-background SVGs (where `currentColor` cannot inherit) are drawn from:

| Intent            | HSL                | Usage in kit                         |
|-------------------|--------------------|--------------------------------------|
| Fig (brand primary)      | `hsl(1, 35%, 42%)`  | `mark-dark.svg`, `apple-touch-icon.svg`, OG wordmark fill |
| Paper (surface canvas)   | `hsl(36, 33%, 97%)` | OG canvas, inverted mark fill         |
| Ink Olive (brand secondary) | `hsl(75, 15%, 18%)` | OG tagline fill                    |

If the palette changes in `globals.css`, update these three constants in:

- `mark-dark.svg` (`<rect fill>` and `<g fill>`)
- `apple-touch-icon.svg` (`<rect fill>` and `<g fill>`)
- `og-default.svg` and `og-storefront.svg` (canvas, wordmark, tagline)

## Authoring conventions

- **Viewport:** always include `viewBox`, never a hardcoded `width`/`height` — consumers scale via CSS.
- **Colour:** prefer `fill="currentColor"` for anything that appears over a neutral surface. Only hardcode HSL on dark/reversed variants and OG canvases.
- **Accessibility:** every mark ships `role="img"`, `aria-label`, and a `<title>` child.
- **Weight budget:** each mark under 10KB, each OG card under 30KB, no external hrefs.
- **Text on OG:** the wordmark itself is rendered as paths. The OG tagline uses a safe system serif (`Georgia, 'Times New Roman', serif`) — these are universally pre-installed on OG rasterisers (Facebook, LinkedIn, X, Slack, iMessage) and require no external font fetch. If strict path-only tagline rendering is required in future, hand-author the tagline glyphs.

## Licensing

All marks in this directory are bespoke to J'Toye OaaS. Not for redistribution or reuse outside the product.
