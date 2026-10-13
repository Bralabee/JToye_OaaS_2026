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
  emptyTitle: "No API credentials yet",
  emptyBody:
    "Create a credential to let your till, website or an AI agent read your menu or place orders, without sharing your password.",
  notConfigured: "API credentials aren't available on this installation yet.",
  notGroupAdmin: "Only a Group admin can manage API credentials.",
  rotateTitle: (name: string) => `Rotate the secret for "${name}"?`,
  rotateBody:
    "The current secret stops working immediately. Anything using it will fail until you give it the new secret.",
  deleteTitle: (name: string) => `Delete "${name}"?`,
  deleteBody:
    "It stops working immediately and can't be restored. To connect again you'd create a new credential.",
} as const
