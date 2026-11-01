/**
 * The DSAR verification link as the email carries it (SW-SAM-08): the web confirm page, the token
 * in the fragment, then the request type. The e2e spec `dsar-verify-link.spec.ts` finds the link
 * with this pattern, so a mailer that dropped or encoded `&type=` fails the spec instead of matching
 * a type-less link (R1 on #1079). Pinned statically by `__tests__/verify-link-pattern.test.ts`.
 */
export type VerifyLinkKind = "access" | "erasure"

export function verifyLinkPattern(kind: VerifyLinkKind): RegExp {
  return new RegExp(`https?:\\/\\/[^\\s<>"]+#token=[A-Za-z0-9_-]+&type=${kind}(?![A-Za-z0-9_-])`)
}
