/**
 * The landing's dish photographs (design D10, D13, D14). Two contracts:
 *
 *  1. Every picture the page can show is a real file with a credit record
 *     (public/marketing/landing/credits.json), at the pixel size the code
 *     declares — next/image reserves the box from that declaration (CLS).
 *  2. Example photos are assigned to kitchens by cuisine, in SLUG order, so two
 *     kitchens of one cuisine get two dishes and the located re-ordering never
 *     moves a picture from one kitchen to another.
 */
import fs from "fs"
import path from "path"
import { PHOTOS, PHOTO_CREDIT_LINE, assignExamplePhotos } from "@/components/marketing/landing-photos"
import type { PublicShop } from "@/types/storefront"

const PUBLIC = path.resolve(__dirname, "../../../public")
const CREDITS = JSON.parse(
  fs.readFileSync(path.join(PUBLIC, "marketing/landing/credits.json"), "utf8")
) as Array<{ src: string; width: number; height: number; creator: string; license: string }>

/** Width and height read from the WebP header itself (VP8 / VP8L / VP8X). */
function webpSize(file: string): [number, number] {
  const b = fs.readFileSync(file)
  const kind = b.toString("ascii", 12, 16)
  if (kind === "VP8X") return [b.readUIntLE(24, 3) + 1, b.readUIntLE(27, 3) + 1]
  if (kind === "VP8 ") return [b.readUInt16LE(26) & 0x3fff, b.readUInt16LE(28) & 0x3fff]
  if (kind === "VP8L") {
    const v = b.readUInt32LE(21)
    return [(v & 0x3fff) + 1, ((v >> 14) & 0x3fff) + 1]
  }
  throw new Error(`VOID: ${file} is not a WebP this reader understands (${kind})`)
}

function shop(slug: string, tags: string | null, bannerUrl: string | null = null): PublicShop {
  return {
    slug, name: slug, description: null, address: null, logoUrl: null, bannerUrl, phone: null,
    email: null, latitude: null, longitude: null, openingHours: null, deliveryInfo: null,
    minimumOrderPennies: null, deliveryFeePennies: null, freeDeliveryThresholdPennies: null, tags,
  }
}

describe("landing photos — files, sizes and credits", () => {
  const photos = Object.values(PHOTOS)

  it("has a credit record for every picture, and a picture for every credit record", () => {
    expect(photos.length).toBe(8)
    expect(new Set(CREDITS.map((c) => c.src))).toEqual(new Set(photos.map((p) => p.src)))
  })

  it("declares each picture at its real pixel size, read from the file", () => {
    for (const p of photos) {
      const [w, h] = webpSize(path.join(PUBLIC, p.src))
      expect(`${p.src} ${w}x${h}`).toBe(`${p.src} ${p.width}x${p.height}`)
    }
  })

  it("names every credited creator in the visible credit line", () => {
    for (const c of CREDITS) expect(PHOTO_CREDIT_LINE).toContain(c.creator)
    // Control: the line is not simply a superset of any string.
    expect(PHOTO_CREDIT_LINE).not.toContain("Nobody In Particular")
  })
})

describe("assignExamplePhotos", () => {
  it("gives two kitchens of one cuisine two different dishes", () => {
    const m = assignExamplePhotos([shop("mama-ades-kitchen", "Nigerian, West African"), shop("peckham-jollof-co", "Nigerian, Grill")])
    expect(m.get("mama-ades-kitchen")!.photo).toBe(PHOTOS.egusi)
    expect(m.get("peckham-jollof-co")!.photo).toBe(PHOTOS.jollofDodo)
    expect(m.get("peckham-jollof-co")!.cuisine).toBe("Nigerian")
  })

  it("assigns by slug, so re-ordering the row never moves a picture", () => {
    const a = [shop("b-kitchen", "Nigerian"), shop("a-kitchen", "Nigerian"), shop("c-grill", "Grill")]
    const forward = assignExamplePhotos(a)
    const reversed = assignExamplePhotos([...a].reverse())
    for (const s of a) expect(reversed.get(s.slug)).toEqual(forward.get(s.slug))
    expect(forward.get("a-kitchen")!.photo).toBe(PHOTOS.egusi)
  })

  it("keeps the cuisine as the kitchen spelled it, from its first recognised tag", () => {
    const m = assignExamplePhotos([shop("x", "Halal, grill, Nigerian")])
    expect(m.get("x")).toEqual({ cuisine: "grill", photo: PHOTOS.suya })
  })

  it("gives a kitchen with its own banner, or no recognised cuisine, no example photo", () => {
    const m = assignExamplePhotos([shop("own", "Nigerian", "/b.webp"), shop("none", "Halal"), shop("null", null)])
    expect(m.size).toBe(0)
  })
})
