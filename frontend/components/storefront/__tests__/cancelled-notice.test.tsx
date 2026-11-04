/**
 * PR #1124 review round 1: the call prompt is mid-sentence, so it is lower case. It rendered
 * "If this was unexpected, Call Mama's Kitchen on 0121 496 0000." on every cancelled order of a shop with a phone.
 */
import { render, screen } from "@testing-library/react"
import { CancelledNotice } from "@/components/storefront/cancelled-notice"

describe("CancelledNotice call prompt", () => {
  it("reads 'If this was unexpected, call <shop> on <phone>.' with a lower-case call", () => {
    render(<CancelledNotice reason="The shop could not make or deliver this order." phone="0121 496 0000" shopName="Mama's Kitchen" />)
    const notice = screen.getByTestId("cancelled-notice")
    expect(notice).toHaveTextContent("If this was unexpected, call Mama's Kitchen on 0121 496 0000.")
    expect(notice.textContent).not.toMatch(/unexpected, Call/)
    expect(screen.getByRole("link")).toHaveTextContent("call Mama's Kitchen on 0121 496 0000")
    expect(screen.getByRole("link")).toHaveAttribute("href", "tel:01214960000")
  })

  it("keeps the 'please contact the shop' sentence when the shop has no phone", () => {
    render(<CancelledNotice reason={null} phone={null} shopName="Mama's Kitchen" />)
    expect(screen.getByTestId("cancelled-notice")).toHaveTextContent("If this was unexpected, please contact the shop.")
  })
})
