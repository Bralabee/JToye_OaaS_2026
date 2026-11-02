import type { PublicShop } from "@/types/storefront"

/**
 * The landing's dish photographs (design D10, D13, D14, D16).
 *
 * D16 (2026-11-02): the owner's own BeesLens photographs lead. Credited
 * open-collection pictures (copied from the approved elevate sketch,
 * design/runs/2026-11-01-landing-elevate/sketch/) remain only where no own
 * photograph exists yet. Every file sits in public/marketing/landing/, whose
 * credits.json records each one with its `kind` ("own" or "open-collection"):
 * title, creator, licence and, for an open-collection picture, its source page.
 * Width and height are the files' real pixel sizes, so next/image reserves the
 * right box (CLS).
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
  // Open-collection: kept where no own photograph exists yet (D16).
  suya: { src: `${DIR}/suya-with-pepper-sauce.webp`, width: 800, height: 1067, alt: "Suya with pepper sauce and sliced onion" },
  riceAndPeas: { src: `${DIR}/jamaican-rice-and-peas.webp`, width: 800, height: 843, alt: "Jamaican rice and peas" },
  biryani: { src: `${DIR}/mutton-biryani.webp`, width: 800, height: 596, alt: "A plate of mutton biryani" },
  chana: { src: `${DIR}/chana-masala.webp`, width: 800, height: 600, alt: "Chana masala" },
  // D16: the owner's own BeesLens photographs.
  spaghetti: { src: `${DIR}/beeslens-spaghetti-chicken-plantain.webp`, width: 1000, height: 627, alt: "Spaghetti with chicken and fried plantain on a tray" },
  jollofBox: { src: `${DIR}/beeslens-jollof-rice-meal-box.webp`, width: 1000, height: 667, alt: "Jollof rice with chicken and stewed meat in a takeaway meal box" },
  riceBeansStew: { src: `${DIR}/beeslens-rice-beans-plantain-stew.webp`, width: 1000, height: 627, alt: "White rice, beans and fried plantain on a tray with a bowl of pepper stew" },
  plantainYam: { src: `${DIR}/beeslens-plantain-yam-meal-prep.webp`, width: 1000, height: 667, alt: "Fried plantain, yam and pepper sauce in two meal-prep boxes" },
  puffPuff: { src: `${DIR}/beeslens-puff-puff.webp`, width: 1000, height: 627, alt: "Puff-puff in a wooden bowl with cocktail sticks" },
  cateringSoup: { src: `${DIR}/beeslens-catering-soup-buffet.webp`, width: 1000, height: 667, alt: "Soup being served into a bowl at a catering buffet" },
  fruitPunch: { src: `${DIR}/beeslens-fruit-punch-dispenser.webp`, width: 667, height: 1000, alt: "A glass drinks dispenser of red fruit punch with cucumber and orange slices" },
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
 * The visible CC credits, one record per open-collection picture, mirrored from credits.json
 * (landing-photos.test.ts holds the two byte-for-byte). CC BY-SA 4.0 §3(a)(1)
 * asks for the creator, the title, a link to the licence and a note that the
 * work was modified; a bare list of names is not that (review 1085).
 */
export const PHOTO_CREDITS: PhotoCredit[] = [
  { src: "/marketing/landing/suya-with-pepper-sauce.webp", title: "Suya with pepper sauce", creator: "Bukky658", page: "https://commons.wikimedia.org/wiki/File:Suya_with_pepper_sauce.jpg", license: "CC BY-SA 4.0", licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0" },
  { src: "/marketing/landing/jamaican-rice-and-peas.webp", title: "Jamaican rice and peas 1", creator: "Xaymacan", page: "https://commons.wikimedia.org/wiki/File:Jamaican_rice_and_peas_1.jpg", license: "CC BY-SA 4.0", licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0" },
  { src: "/marketing/landing/chana-masala.webp", title: "Chana Masala - Mohammed - Spice Of Life 2024-05-27", creator: "Andy Li", page: "https://commons.wikimedia.org/wiki/File:Chana_Masala_-_Mohammed_-_Spice_Of_Life_2024-05-27.jpg", license: "CC0", licenseUrl: "https://creativecommons.org/publicdomain/zero/1.0/deed.en" },
  { src: "/marketing/landing/mutton-biryani.webp", title: "A home made plate of mutton biryani served with chicken kassa cooked in the bengali style", creator: "Subhrajyoti07", page: "https://commons.wikimedia.org/wiki/File:A_home_made_plate_of_mutton_biryani_served_with_chicken_kassa_cooked_in_the_bengali_style.jpg", license: "CC BY-SA 4.0", licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0" },
]

export interface OwnPhotoCredit {
  src: string
  title: string
  creator: string
}

/**
 * The owner's own photographs (D16), mirrored from credits.json's "own"
 * records (landing-photos.test.ts holds the two equal). They are credited in
 * one line, OWN_PHOTO_CREDIT, apart from the CC paragraph: they are not a CC
 * work and have no source page or licence link.
 */
export const OWN_PHOTOS: OwnPhotoCredit[] = [
  { src: "/marketing/landing/beeslens-spaghetti-chicken-plantain.webp", title: "Spaghetti with chicken and fried plantain on a tray", creator: "BeesLens" },
  { src: "/marketing/landing/beeslens-jollof-rice-meal-box.webp", title: "Jollof rice with chicken and stewed meat in a takeaway meal box", creator: "BeesLens" },
  { src: "/marketing/landing/beeslens-rice-beans-plantain-stew.webp", title: "White rice, beans and fried plantain on a tray with a bowl of pepper stew", creator: "BeesLens" },
  { src: "/marketing/landing/beeslens-plantain-yam-meal-prep.webp", title: "Fried plantain, yam and pepper sauce in two meal-prep boxes", creator: "BeesLens" },
  { src: "/marketing/landing/beeslens-puff-puff.webp", title: "Puff-puff in a wooden bowl with cocktail sticks", creator: "BeesLens" },
  { src: "/marketing/landing/beeslens-catering-soup-buffet.webp", title: "Soup being served into a bowl at a catering buffet", creator: "BeesLens" },
  { src: "/marketing/landing/beeslens-fruit-punch-dispenser.webp", title: "A glass drinks dispenser of red fruit punch with cucumber and orange slices", creator: "BeesLens" },
]

export const OWN_PHOTO_CREDIT = "Photography: BeesLens"

/** Every file was resized and re-encoded (credits.json `derivation`); the chip thumbnails are also centre-cropped. */
export const PHOTO_DERIVATION_NOTE = "resized, re-encoded and in places cropped for the web"

/**
 * Which pictures stand for which cuisine, matched against a kitchen's own tags
 * (lower-cased). Order within a list is the order kitchens of that cuisine
 * receive them, so two Nigerian kitchens get two different dishes.
 */
const CUISINES: Array<{ words: string[]; photos: LandingPhoto[] }> = [
  { words: ["nigerian"], photos: [PHOTOS.jollofBox, PHOTOS.riceBeansStew, PHOTOS.spaghetti] },
  { words: ["west african", "ghanaian"], photos: [PHOTOS.plantainYam, PHOTOS.jollofBox] },
  { words: ["jollof"], photos: [PHOTOS.jollofBox, PHOTOS.spaghetti] },
  { words: ["grill", "suya", "bbq"], photos: [PHOTOS.suya] },
  { words: ["caribbean", "jamaican"], photos: [PHOTOS.riceAndPeas] },
  { words: ["south asian", "indian", "pakistani", "bangladeshi", "biryani"], photos: [PHOTOS.biryani] },
  { words: ["vegan", "vegetarian"], photos: [PHOTOS.chana] },
  { words: ["dessert", "desserts", "bakery"], photos: [PHOTOS.puffPuff] },
  { words: ["catering"], photos: [PHOTOS.cateringSoup] },
  { words: ["drinks", "juice"], photos: [PHOTOS.fruitPunch] },
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
