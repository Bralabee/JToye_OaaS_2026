/**
 * The Developers page's copy (37-46, UI-SPEC § Copywriting E1 / E2), in one module so the page, its
 * table and their suites share the contract instead of retyping it. (A Next.js page file may export
 * only its page fields, so the copy cannot live in page.tsx.)
 */
export const DEVELOPERS_COPY = {
  title: "Developers",
  subtitle:
    "Connect your own systems to J'Toye with their own credentials, so you never share your password.",
  create: "Create credential",
  webhooksLink: "Webhooks →",
  cardTitle: "API credentials",
  revealTitle: "Copy your client secret",
  revealDescription:
    "Give these to the system that will call J'Toye. It exchanges them for an access token at the token endpoint.",
  revealWarning:
    "This is the only time we'll show this secret. Store it somewhere safe. If you lose it, rotate it to get a new one; the old one stops working.",
} as const
