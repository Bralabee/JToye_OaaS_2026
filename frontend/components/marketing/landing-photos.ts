import type { PublicShop } from "@/types/storefront"

/**
 * The landing's dish photographs (design D10, D13, D14).
 *
 * Eight credited open-collection pictures, copied from the approved elevate
 * sketch (design/runs/2026-11-01-landing-elevate/sketch/) into
 * public/marketing/landing/, whose credits.json records each one's title,
 * creator, licence and source page. Width and height are the files' real
 * pixel sizes, so next/image reserves the right box (CLS).
 *
 * A found picture shows the world around a kitchen and is never presented as a
 * kitchen's own dish (D10): on a kitchen card it is captioned
 * "<Cuisine> — example photo" (D13 wording) and only when the kitchen has no
 * banner of its own.
 */
export interface LandingPhoto {
  src: string
  width: number
  height: number
  alt: string
}

const DIR = "/marketing/landing"

export const PHOTOS = {
  egusi: { src: `${DIR}/egusi-soup-with-pounded-yam.webp`, width: 800, height: 784, alt: "Egusi soup with pounded yam" },
  suya: { src: `${DIR}/suya-with-pepper-sauce.webp`, width: 800, height: 1067, alt: "Suya with pepper sauce and sliced onion" },
  jollofVeg: { src: `${DIR}/jollof-rice-with-vegetable.webp`, width: 1000, height: 1000, alt: "Jollof rice with greens and a boiled egg" },
  puffPuff: { src: `${DIR}/puff-puff.webp`, width: 800, height: 1067, alt: "A plate of puff-puff" },
  jollofDodo: { src: `${DIR}/jollof-rice-and-dodo.webp`, width: 800, height: 532, alt: "A plate of jollof rice with fried plantain" },
  riceAndPeas: { src: `${DIR}/jamaican-rice-and-peas.webp`, width: 800, height: 843, alt: "Jamaican rice and peas" },
  biryani: { src: `${DIR}/mutton-biryani.webp`, width: 800, height: 596, alt: "A plate of mutton biryani" },
  chana: { src: `${DIR}/chana-masala.webp`, width: 800, height: 600, alt: "Chana masala" },
} satisfies Record<string, LandingPhoto>

export interface PhotoCredit {
  src: string
  title: string
  creator: string
  /** The source page on Wikimedia Commons. */
  page: string
  license: string
  licenseUrl: string
}

/**
 * The visible credits, one record per picture, mirrored from credits.json
 * (landing-photos.test.ts holds the two byte-for-byte). CC BY-SA 4.0 §3(a)(1)
 * asks for the creator, the title, a link to the licence and a note that the
 * work was modified; a bare list of names is not that (review 1085).
 */
export const PHOTO_CREDITS: PhotoCredit[] = [
  { src: "/marketing/landing/jollof-rice-with-vegetable.webp", title: "Jollof rice with vegetable", creator: "Segun Famisa", page: "https://commons.wikimedia.org/wiki/File:Jollof_rice_with_vegetable.jpg", license: "CC BY-SA 4.0", licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0" },
  { src: "/marketing/landing/jollof-rice-and-dodo.webp", title: "Jollof rice and Dodo", creator: "Skarfmacaronne", page: "https://commons.wikimedia.org/wiki/File:Jollof_rice_and_Dodo.jpg", license: "CC BY-SA 4.0", licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0" },
  { src: "/marketing/landing/suya-with-pepper-sauce.webp", title: "Suya with pepper sauce", creator: "Bukky658", page: "https://commons.wikimedia.org/wiki/File:Suya_with_pepper_sauce.jpg", license: "CC BY-SA 4.0", licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0" },
  { src: "/marketing/landing/egusi-soup-with-pounded-yam.webp", title: "Egusi soup with pounded yam and assorted meats", creator: "Onyenachi64", page: "https://commons.wikimedia.org/wiki/File:Egusi_soup_with_pounded_yam_and_assorted_meats.jpg", license: "CC BY-SA 4.0", licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0" },
  { src: "/marketing/landing/puff-puff.webp", title: "Puff Puff", creator: "Halima Waziri", page: "https://commons.wikimedia.org/wiki/File:Puff_Puff.jpg", license: "CC BY-SA 4.0", licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0" },
  { src: "/marketing/landing/jamaican-rice-and-peas.webp", title: "Jamaican rice and peas 1", creator: "Xaymacan", page: "https://commons.wikimedia.org/wiki/File:Jamaican_rice_and_peas_1.jpg", license: "CC BY-SA 4.0", licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0" },
  { src: "/marketing/landing/chana-masala.webp", title: "Chana Masala - Mohammed - Spice Of Life 2024-05-27", creator: "Andy Li", page: "https://commons.wikimedia.org/wiki/File:Chana_Masala_-_Mohammed_-_Spice_Of_Life_2024-05-27.jpg", license: "CC0", licenseUrl: "https://creativecommons.org/publicdomain/zero/1.0/deed.en" },
  { src: "/marketing/landing/mutton-biryani.webp", title: "A home made plate of mutton biryani served with chicken kassa cooked in the bengali style", creator: "Subhrajyoti07", page: "https://commons.wikimedia.org/wiki/File:A_home_made_plate_of_mutton_biryani_served_with_chicken_kassa_cooked_in_the_bengali_style.jpg", license: "CC BY-SA 4.0", licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0" },
]

/** Every file was resized and re-encoded (credits.json `derivation`); the chip thumbnails are also centre-cropped. */
export const PHOTO_DERIVATION_NOTE = "resized, re-encoded and in places cropped for the web"

/**
 * Which pictures stand for which cuisine, matched against a kitchen's own tags
 * (lower-cased). Order within a list is the order kitchens of that cuisine
 * receive them, so two Nigerian kitchens get two different dishes.
 */
const CUISINES: Array<{ words: string[]; photos: LandingPhoto[] }> = [
  { words: ["nigerian"], photos: [PHOTOS.egusi, PHOTOS.jollofDodo, PHOTOS.jollofVeg] },
  { words: ["west african", "ghanaian"], photos: [PHOTOS.jollofVeg, PHOTOS.jollofDodo] },
  { words: ["jollof"], photos: [PHOTOS.jollofDodo, PHOTOS.jollofVeg] },
  { words: ["grill", "suya", "bbq"], photos: [PHOTOS.suya] },
  { words: ["caribbean", "jamaican"], photos: [PHOTOS.riceAndPeas] },
  { words: ["south asian", "indian", "pakistani", "bangladeshi", "biryani"], photos: [PHOTOS.biryani] },
  { words: ["vegan", "vegetarian"], photos: [PHOTOS.chana] },
  { words: ["dessert", "desserts", "bakery"], photos: [PHOTOS.puffPuff] },
]

export interface ExamplePhoto {
  /** The tag that matched, spelled as the kitchen wrote it ("Nigerian"). */
  cuisine: string
  photo: LandingPhoto
}

function matchCuisine(tags: string | null): { cuisine: string; index: number } | null {
  if (!tags) return null
  for (const raw of tags.split(",")) {
    const tag = raw.trim()
    const lower = tag.toLowerCase()
    const index = CUISINES.findIndex((c) => c.words.includes(lower))
    if (index >= 0) return { cuisine: tag, index }
  }
  return null
}

/**
 * One example photo per kitchen with no banner, keyed by slug.
 *
 * The first tag that names a known cuisine decides the picture; a kitchen whose
 * tags name none gets no example photo at all rather than a guess. Within a
 * cuisine, kitchens are taken in SLUG order, not display order, so re-ordering
 * the row by distance (33-07) never moves a picture from one kitchen to another.
 */
export function assignExamplePhotos(shops: PublicShop[]): Map<string, ExamplePhoto> {
  const out = new Map<string, ExamplePhoto>()
  const used = new Map<number, number>()
  const unique = [...new Map(shops.map((s) => [s.slug, s])).values()]
  unique.sort((a, b) => (a.slug < b.slug ? -1 : a.slug > b.slug ? 1 : 0))
  for (const shop of unique) {
    if (shop.bannerUrl) continue
    const hit = matchCuisine(shop.tags)
    if (!hit) continue
    const n = used.get(hit.index) ?? 0
    used.set(hit.index, n + 1)
    const photos = CUISINES[hit.index].photos
    out.set(shop.slug, { cuisine: hit.cuisine, photo: photos[n % photos.length] })
  }
  return out
}
