/**
 * The two operative /for-operators sentences are IMPORTED, never retyped
 * (PR 1080 round 1, comment 4436097590).
 *
 * "A person reviews every application..." and "You remain the Food Business
 * Operator..." are printed on /for-operators, /legal/terms and /contact. When
 * the owner rules on the operator page, a page titled "Terms of use" must not
 * keep stating the old wording. Proof by substitution, as
 * operator-pitch-pricing.test.tsx does for prices: the module is mocked with
 * strings no page carries, and every page must print the substitute and none
 * the original. A retyped copy still prints the original and fails here.
 */
import { render } from "@testing-library/react"
import "@testing-library/jest-dom"

const SUB_REVIEW = "SUBSTITUTE-REVIEW-LINE: an owner-ruled application sentence."
const SUB_FBO = "SUBSTITUTE-FBO-LINE: an owner-ruled operator duty sentence."

jest.mock("@/lib/operator-copy", () => ({
  OPERATOR_APPLICATION_REVIEW_LINE: "SUBSTITUTE-REVIEW-LINE: an owner-ruled application sentence.",
  OPERATOR_FBO_LINE: "SUBSTITUTE-FBO-LINE: an owner-ruled operator duty sentence.",
}))

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogout: jest.fn(),
}))

import TermsPage from "@/app/legal/terms/page"
import ContactPage from "@/app/contact/page"
import { OperatorPitch } from "@/components/marketing/operator-pitch"

const ORIGINAL_REVIEW = /a person reviews every application/i
const ORIGINAL_FBO = /you remain the food business operator/i

describe("operator sentences come from lib/operator-copy", () => {
  it("/legal/terms prints both substitutes and neither original", () => {
    const { container } = render(<TermsPage />)
    const text = container.textContent ?? ""
    expect(text).toContain(SUB_REVIEW)
    expect(text).toContain(SUB_FBO)
    expect(text).not.toMatch(ORIGINAL_REVIEW)
    expect(text).not.toMatch(ORIGINAL_FBO)
  })

  it("/contact prints the review substitute and not the original", () => {
    const { container } = render(<ContactPage />)
    const text = container.textContent ?? ""
    expect(text).toContain(SUB_REVIEW)
    expect(text).not.toMatch(ORIGINAL_REVIEW)
  })

  it("/for-operators prints both substitutes and neither original", () => {
    const { container } = render(<OperatorPitch />)
    const text = container.textContent ?? ""
    expect(text).toContain(SUB_REVIEW)
    expect(text).toContain(SUB_FBO)
    expect(text).not.toMatch(ORIGINAL_REVIEW)
    expect(text).not.toMatch(ORIGINAL_FBO)
  })
})
