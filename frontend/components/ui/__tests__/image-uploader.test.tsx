import {
  validateFileType,
  preflightSizeGate,
  chooseEncoding,
  enforceServerLimit,
  SERVER_MAX_BYTES,
  BROWSER_SAFETY_MAX_BYTES,
  JPEG_QUALITY_LADDER,
} from "@/components/ui/image-uploader"

// These tests exercise the PURE, DOM-free helpers only. jsdom cannot run the
// canvas pipeline (no getContext/toBlob/real Image decode), so the canvas
// orchestration (compressImage) is deliberately not unit-tested here. Together
// these cases encode the compress-then-gate contract: the door gate lets large
// photos through (compression handles them) and the 5MB enforcement is a
// SEPARATE step applied to the already-compressed result.

const MB = 1024 * 1024

describe("validateFileType", () => {
  it("accepts the four supported types", () => {
    expect(validateFileType("image/jpeg")).toBeNull()
    expect(validateFileType("image/png")).toBeNull()
    expect(validateFileType("image/webp")).toBeNull()
    expect(validateFileType("image/gif")).toBeNull()
  })

  it("rejects unsupported types with a message", () => {
    const msg = validateFileType("image/tiff")
    expect(msg).not.toBeNull()
    expect(msg).toContain("Invalid file type")
  })
})

describe("preflightSizeGate", () => {
  it("lets a normal large phone photo (20MB JPEG) through — the core regression this fix closes", () => {
    expect(preflightSizeGate({ type: "image/jpeg", size: 20 * MB })).toBeNull()
  })

  it("rejects an absurdly large file (60MB) with a browser-safety message naming 50MB, never '10MB'", () => {
    const msg = preflightSizeGate({ type: "image/jpeg", size: 60 * MB })
    expect(msg).not.toBeNull()
    expect(msg).toContain("50MB")
    expect(msg).not.toContain("10MB")
  })

  it("rejects a 6MB GIF with a GIF-specific message and passes a 4MB GIF", () => {
    const over = preflightSizeGate({ type: "image/gif", size: 6 * MB })
    expect(over).not.toBeNull()
    expect(over).toContain("GIF")
    expect(preflightSizeGate({ type: "image/gif", size: 4 * MB })).toBeNull()
  })

  it("uses the exported caps, not magic numbers", () => {
    expect(SERVER_MAX_BYTES).toBe(5 * MB)
    expect(BROWSER_SAFETY_MAX_BYTES).toBe(50 * MB)
  })
})

describe("chooseEncoding", () => {
  it("re-encodes a non-transparent PNG as JPEG with the 3-rung quality ladder", () => {
    const plan = chooseEncoding("image/png", false)
    expect(plan.type).toBe("image/jpeg")
    expect(plan.qualities).toEqual([0.85, 0.75, 0.65])
    expect(plan.qualities).toEqual(JPEG_QUALITY_LADDER)
  })

  it("keeps a transparent PNG as lossless PNG with no quality ladder", () => {
    const plan = chooseEncoding("image/png", true)
    expect(plan.type).toBe("image/png")
    expect(plan.qualities).toEqual([])
  })

  it("encodes JPEG and WebP sources as JPEG with the quality ladder", () => {
    expect(chooseEncoding("image/jpeg", false)).toEqual({
      type: "image/jpeg",
      qualities: [0.85, 0.75, 0.65],
    })
    expect(chooseEncoding("image/webp", false)).toEqual({
      type: "image/jpeg",
      qualities: [0.85, 0.75, 0.65],
    })
  })

  it("never re-encodes a GIF", () => {
    expect(chooseEncoding("image/gif", false)).toEqual({
      type: "image/gif",
      qualities: [],
    })
  })
})

describe("enforceServerLimit", () => {
  it("rejects a compressed result still over 5MB with an honest message", () => {
    const msg = enforceServerLimit(6 * MB)
    expect(msg).not.toBeNull()
    expect(msg).toContain("5MB")
  })

  it("passes a compressed result at or under 5MB", () => {
    expect(enforceServerLimit(4 * MB)).toBeNull()
    expect(enforceServerLimit(SERVER_MAX_BYTES)).toBeNull()
  })
})

describe("honest copy guard", () => {
  it("no exported helper message contains the misleading 'Maximum 10MB' copy", () => {
    const messages = [
      validateFileType("image/tiff"),
      preflightSizeGate({ type: "image/gif", size: 6 * MB }),
      preflightSizeGate({ type: "image/jpeg", size: 60 * MB }),
      enforceServerLimit(6 * MB),
    ]
    for (const message of messages) {
      expect(message).not.toBeNull()
      expect(message).not.toContain("Maximum 10MB")
    }
  })
})
