/**
 * What J'Toye emails a customer, said ONCE (#871, D-04, 31.1-26).
 *
 * The platform sends no marketing, so no marketing-consent machinery is built (D-04 deferred it).
 * The privacy notice states that instead, and /unsubscribe — which used to tell the reader to
 * "contact the vendor", whose contact details were never published — repeats it and points at two
 * real routes. Both pages render these constants, so the two statements cannot drift apart.
 *
 * WHAT "ONLY" COVERS, enumerated from the senders in the tree on 2026-10-07:
 *   - order emails (EmailNotificationService / the notification dispatch ORDERS and FINANCIAL
 *     categories: placed, status changes, refunds and payments);
 *   - data-request emails (DsarVerificationMailer: the confirm link; DsarOutcomeMailer: the copy of
 *     your data, and the note that an erasure was carried out);
 *   - sign-in account emails the customer asks for: the jtoye-customers realm has
 *     resetPasswordAllowed with SMTP configured, so a password reset is an email we send. Leaving it
 *     out would make "only" false for every customer who resets a password.
 * MARKETING is a notification category, but ConsentGate refuses it without a recorded opt-in and no
 * surface records one, so nothing in that category is ever sent. If that changes, this statement
 * and the privacy notice change with it.
 */
export const EMAIL_SCOPE_STATEMENT =
  "J'Toye only emails you about your orders, about data requests you make, and about your sign-in " +
  "account when you ask us to (for example, to reset your password)."

export const NO_MARKETING_STATEMENT = "We do not send marketing emails."

/** The privacy notice's section on emails. Its id is derived from the heading "Emails". */
export const EMAILS_SECTION_HREF = "/legal/privacy#emails"

/** The signed-in customer's data-rights page (31.1-26 Task 1). */
export const ACCOUNT_HREF = "/shop/account"
