/**
 * Operative sentences from the operator offer, defined once (PR 1080 round 1).
 *
 * /for-operators states them, and /legal/terms and /contact restate them. If
 * each page retyped them, an owner ruling applied to /for-operators would
 * leave the page titled "Terms of use" stating the old wording. So they live
 * here, in a plain module: operator-pitch.tsx is a "use client" module, and a
 * server component importing a constant from it receives a client reference,
 * not the string. The pricing footnotes moved to lib/pricing.ts for the same
 * reason. app/legal/__tests__/operator-copy-substitution.test.tsx proves all
 * three pages import them rather than retype them.
 */

/** What happens after an operator applies. */
export const OPERATOR_APPLICATION_REVIEW_LINE =
  "A person reviews every application, and nothing is charged until a plan is agreed."

/**
 * The vendor-to-vendor confidentiality statement (issue 878, UXT-119 finding
 * F6: "no vendor-to-vendor confidentiality assurance in the UI or legal
 * copy"). It states what the database already enforces tenant by tenant under
 * row-level security: one business's orders, customer records and sales
 * figures are not visible to another business on the platform. It makes no
 * promise about the public shop page and menu, which are public by design, and
 * none about J'Toye staff. Rendered by /for-operators and
 * /legal/merchant-terms.
 */
export const OPERATOR_CONFIDENTIALITY_LINE =
  "Your orders, customer records and sales figures are private to your business: other food businesses on J'Toye cannot see them. Your public shop page and menu are public, because that is how customers find you."

/** What stays the operator's legal responsibility. */
export const OPERATOR_FBO_LINE =
  "You remain the Food Business Operator. Recipe accuracy and cross-contamination are still yours."
