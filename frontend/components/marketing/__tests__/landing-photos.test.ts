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
import {
  PHOTOS,
  PHOTO_CREDITS,
  PHOTO_DERIVATION_NOTE,
  OWN_PHOTOS,
  OWN_PHOTO_CREDIT,
  assignExamplePhotos,
} from "@/components/marketing/landing-photos"
import type { PublicShop } from "@/types/storefront"

const PUBLIC = path.resolve(__dirname, "../../../public")
const CREDITS = JSON.parse(
  fs.readFileSync(path.join(PUBLIC, "marketing/landing/credits.json"), "utf8")
) as Array<{ kind: string; src: string; width: number; height: number; title: string; creator: string; license: string; licenseUrl: string | null; page: string | null; derivation: string }>
// D16: the ledger holds two kinds of picture. The owner's own BeesLens
// photographs ("own") and the credited open-collection pictures that remain
// where no own photograph exists yet ("open-collection").
const OPEN = CREDITS.filter((c) => c.kind === "open-collection")
const OWN = CREDITS.filter((c) => c.kind === "own")

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

/** Every RIFF chunk's fourcc in a WebP, walked from offset 12 (each chunk is padded to an even size). */
function riffChunks(file: string): string[] {
  const b = fs.readFileSync(file)
  if (b.toString("ascii", 0, 4) !== "RIFF" || b.toString("ascii", 8, 12) !== "WEBP") {
    throw new Error(`VOID: ${file} is not a RIFF WebP`)
  }
  const out: string[] = []
  let off = 12
  while (off + 8 <= b.length) {
    const size = b.readUInt32LE(off + 4)
    out.push(b.toString("ascii", off, off + 4))
    off += 8 + size + (size & 1)
  }
  return out
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

  it("has a credit record for every picture, a picture for every credit record, and no file on disk outside both", () => {
    expect(photos.length).toBe(11)
    expect(new Set(CREDITS.map((c) => c.src))).toEqual(new Set(photos.map((p) => p.src)))
    // D16: a retired picture's file must leave with its record, so the files
    // on disk are held to the same set (an orphan WebP fails here).
    const onDisk = fs
      .readdirSync(path.join(PUBLIC, "marketing/landing"))
      .filter((f) => f.endsWith(".webp"))
      .map((f) => `/marketing/landing/${f}`)
    // Control: the directory read found the files.
    expect(onDisk.length).toBeGreaterThan(0)
    expect(new Set(onDisk)).toEqual(new Set(photos.map((p) => p.src)))
  })

  it("marks every credit record with its kind, own or open-collection (D16)", () => {
    for (const c of CREDITS) expect(`${c.src} ${c.kind}`).toMatch(/ (open-collection|own)$/)
    expect(OPEN.length + OWN.length).toBe(CREDITS.length)
    // Open-collection pictures remain only where no own photograph exists yet.
    expect(OPEN.map((c) => c.src).sort()).toEqual(
      [PHOTOS.suya, PHOTOS.riceAndPeas, PHOTOS.biryani, PHOTOS.chana].map((p) => p.src).sort()
    )
    expect(OWN.length).toBe(7)
  })

  it("declares each picture at its real pixel size, read from the file", () => {
    for (const p of photos) {
      const [w, h] = webpSize(path.join(PUBLIC, p.src))
      expect(`${p.src} ${w}x${h}`).toBe(`${p.src} ${p.width}x${p.height}`)
    }
  })

  it("carries every open-collection record's title, creator, source page and licence link, byte for byte (review 1085)", () => {
    expect(PHOTO_CREDITS.length).toBe(OPEN.length)
    for (const c of OPEN) {
      const got = PHOTO_CREDITS.find((p) => p.src === c.src)
      expect(got).toEqual({ src: c.src, title: c.title, creator: c.creator, page: c.page, license: c.license, licenseUrl: c.licenseUrl })
    }
    // Every file was resized and re-encoded (credits.json derivation), so the page must say so.
    for (const c of OPEN) expect(c.derivation).toMatch(/resized/)
    expect(PHOTO_DERIVATION_NOTE).toMatch(/resized/)
    // Control: the ledger is not simply a superset of any record.
    expect(PHOTO_CREDITS.find((p) => p.creator === "Nobody In Particular")).toBeUndefined()
  })

  it("mirrors every own record in OWN_PHOTOS and credits them in one line (D16)", () => {
    expect(OWN_PHOTO_CREDIT).toBe("Photography: BeesLens")
    expect(OWN_PHOTOS.length).toBe(OWN.length)
    for (const c of OWN) {
      expect(OWN_PHOTOS.find((p) => p.src === c.src)).toEqual({ src: c.src, title: c.title, creator: c.creator })
      expect(c.creator).toBe("BeesLens")
      expect(c.license).toBe("© BeesLens Photography, used with permission")
      expect(c.page).toBeNull()
      expect(c.licenseUrl).toBeNull()
      expect(c.derivation).toMatch(/metadata stripped/)
      // The record's title is the picture's alt text, so the ledger names what the page shows.
      expect(Object.values(PHOTOS).find((p) => p.src === c.src)?.alt).toBe(c.title)
    }
    // Control: an open-collection record is not an own one.
    expect(OWN_PHOTOS.find((p) => p.src === OPEN[0].src)).toBeUndefined()
  })

  it("ships every own photograph with no EXIF or XMP chunk (the camera's Artist and serial number stay out)", () => {
    for (const p of OWN_PHOTOS) {
      const chunks = riffChunks(path.join(PUBLIC, p.src))
      // Control: the walk found the image data, so an empty walk cannot pass.
      expect(chunks.some((c) => c === "VP8 " || c === "VP8L" || c === "VP8X")).toBe(true)
      expect(`${p.src} ${chunks.join(",")}`).not.toMatch(/EXIF|XMP /)
    }
  })
})

describe("assignExamplePhotos", () => {
  it("gives two kitchens of one cuisine two different dishes", () => {
    const m = assignExamplePhotos([shop("mama-ades-kitchen", "Nigerian, West African"), shop("peckham-jollof-co", "Nigerian, Grill")])
    expect(m.get("mama-ades-kitchen")!.photo).toBe(PHOTOS.jollofBox)
    expect(m.get("peckham-jollof-co")!.photo).toBe(PHOTOS.riceBeansStew)
    expect(m.get("peckham-jollof-co")!.cuisine).toBe("Nigerian")
  })

  it("never captions a dish as \"Jollof\" unless the picture is jollof (review 1103 round 1)", () => {
    const m = assignExamplePhotos([shop("j-a", "Jollof"), shop("j-b", "Jollof"), shop("j-c", "Jollof")])
    for (const slug of ["j-a", "j-b", "j-c"]) {
      expect(`${slug} ${m.get(slug)!.photo.src}`).toBe(`${slug} ${PHOTOS.jollofBox.src}`)
    }
  })

  it("assigns by slug, so re-ordering the row never moves a picture", () => {
    const a = [shop("b-kitchen", "Nigerian"), shop("a-kitchen", "Nigerian"), shop("c-grill", "Grill")]
    const forward = assignExamplePhotos(a)
    const reversed = assignExamplePhotos([...a].reverse())
    for (const s of a) expect(reversed.get(s.slug)).toEqual(forward.get(s.slug))
    expect(forward.get("a-kitchen")!.photo).toBe(PHOTOS.jollofBox)
  })

  it("pictures catering and drinks kitchens with the owner's buffet and fruit punch photographs (D16)", () => {
    const m = assignExamplePhotos([shop("k-cater", "Catering, Halal"), shop("k-juice", "Juice"), shop("k-drinks", "Halal, drinks")])
    expect(m.get("k-cater")).toEqual({ cuisine: "Catering", photo: PHOTOS.cateringSoup })
    expect(m.get("k-juice")).toEqual({ cuisine: "Juice", photo: PHOTOS.fruitPunch })
    expect(m.get("k-drinks")).toEqual({ cuisine: "drinks", photo: PHOTOS.fruitPunch })
    // Control: the photographs exist, so the equalities above are not undefined === undefined.
    expect(PHOTOS.cateringSoup.src).toContain("beeslens-catering-soup-buffet")
    expect(PHOTOS.fruitPunch.src).toContain("beeslens-fruit-punch-dispenser")
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
