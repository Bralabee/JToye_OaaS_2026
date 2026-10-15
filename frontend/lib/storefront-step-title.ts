import { loadPublicShop } from "@/lib/storefront-server"

/**
 * SERVER ONLY (it reads the public shop through `storefront-server`). The title of one step of the
 * storefront journey (37-51, UI-SPEC F4, RWO-084 / UXT-084): "{step} · {Shop} — J'Toye".
 *
 * The basket, checkout and order pages are client components and cannot export metadata, so before
 * 37-51 they all inherited the root title and the Next route announcer read the same words at every
 * step. Each route now has a server `layout.tsx` whose `generateMetadata` calls this.
 *
 * A shop that cannot be read (404, 429, 5xx, network) gives "{step} — J'Toye": never a guessed name,
 * and never a thrown notFound() — the page under the layout owns its own empty and error states.
 * T-37-119: the title carries only what the page itself shows (the shop name, and for the order
 * step the order number already in the URL).
 */
export async function storefrontStepTitle(step: string, slug: string): Promise<string> {
  const shop = await loadPublicShop(slug)
  const name = shop.state === "ok" ? shop.data.name?.trim() : ""
  return name ? `${step} · ${name} — J'Toye` : `${step} — J'Toye`
}
