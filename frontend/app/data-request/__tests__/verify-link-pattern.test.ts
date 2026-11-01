/**
 * R1 on #1079 (comment 4436104586), SW-SAM-08: the e2e spec dsar-verify-link.spec.ts must prove the
 * real verification email carries the request type. It found the link with
 * `(?:&type=(?:access|erasure))?`, which also matches a link with NO type, so a mailer that dropped
 * `&type=` would still pass. The spec now uses `verifyLinkPattern(kind)`, and this suite pins the
 * pattern statically (the spec itself needs a running compose stack).
 */
import { verifyLinkPattern } from "../confirm/verify-link"

const BASE = "http://localhost:3000/data-request/confirm"
const TOKEN = "Zm9vYmFy-_0123456789abcdefghijABCDEFGHIJxyz"
const WITHOUT_TYPE = `Open this link:\n${BASE}#token=${TOKEN}\n`
const WITH_ACCESS = `Open this link:\n${BASE}#token=${TOKEN}&type=access\n`
const WITH_ERASURE = `Open this link:\n${BASE}#token=${TOKEN}&type=erasure\n`

/** The pattern the spec used before this fix, kept here as the arm that shows why it was vacuous. */
const OLD_PATTERN = /https?:\/\/[^\s<>"]+#token=[A-Za-z0-9_-]+(?:&type=(?:access|erasure))?/

describe("the DSAR verify-link pattern the e2e spec uses", () => {
  it("the OLD pattern accepted a link with no type (why the spec proved nothing)", () => {
    expect(WITHOUT_TYPE.match(OLD_PATTERN)).not.toBeNull()
  })

  it("the new pattern refuses a link with no type", () => {
    expect(WITHOUT_TYPE.match(verifyLinkPattern("access"))).toBeNull()
    expect(WITHOUT_TYPE.match(verifyLinkPattern("erasure"))).toBeNull()
  })

  it("the new pattern matches the link with the requested type, whole", () => {
    expect(WITH_ACCESS.match(verifyLinkPattern("access"))?.[0]).toBe(`${BASE}#token=${TOKEN}&type=access`)
    expect(WITH_ERASURE.match(verifyLinkPattern("erasure"))?.[0]).toBe(`${BASE}#token=${TOKEN}&type=erasure`)
  })

  it("the new pattern refuses the other kind, and a type with extra letters", () => {
    expect(WITH_ERASURE.match(verifyLinkPattern("access"))).toBeNull()
    expect(WITH_ACCESS.match(verifyLinkPattern("erasure"))).toBeNull()
    expect(`${BASE}#token=${TOKEN}&type=accessx`.match(verifyLinkPattern("access"))).toBeNull()
  })
})
