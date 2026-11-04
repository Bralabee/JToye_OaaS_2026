/**
 * SW-PRY-07 (swarm epic #964): the checkout's allergen panel tells a customer with a serious allergy to
 * "tell the kitchen before you order" and gave no way to. The persona's shop had no phone stored, so
 * nothing anywhere said how.
 *
 * The panel now takes the kitchen's contact (the shop's phone and email) and prints it beside that
 * sentence, as links; when none is stored it says so plainly and says what the page does offer. The
 * legally operative sentences themselves are not touched (order-allergen-panel.test.tsx pins them).
 * Absence is paired with presence in this file.
 */
import { render, screen, within } from "@testing-library/react"

import { OrderAllergenPanel, kitchenContactCopy } from "@/components/storefront/order-allergen-panel"

function panel(props: Partial<React.ComponentProps<typeof OrderAllergenPanel>> = {}) {
  return render(
    <OrderAllergenPanel
      vendorName="Mama Ade's Kitchen"
      allergenNames={["Peanuts"]}
      allergenFlags={[]}
      acknowledged={false}
      onAcknowledgedChange={() => {}}
      {...props}
    />
  )
}

describe("the way to ask the kitchen first", () => {
  it("prints the phone and the email as links when the shop has both", () => {
    panel({ kitchenContact: { phone: "020 7946 0000", email: "hello@mama-ade.example" } })
    const contact = screen.getByTestId("allergen-kitchen-contact")
    expect(contact).toHaveTextContent("To ask the kitchen before you order")
    const phone = within(contact).getByRole("link", { name: "020 7946 0000" })
    expect(phone).toHaveAttribute("href", "tel:02079460000")
    const email = within(contact).getByRole("link", { name: "hello@mama-ade.example" })
    expect(email).toHaveAttribute("href", "mailto:hello@mama-ade.example")
  })

  it("a phone alone, or an email alone, is printed without the other", () => {
    const first = panel({ kitchenContact: { phone: "020 7946 0000", email: null } })
    expect(within(screen.getByTestId("allergen-kitchen-contact")).queryByRole("link", { name: /@/ })).toBeNull()
    expect(within(screen.getByTestId("allergen-kitchen-contact")).getByRole("link", { name: "020 7946 0000" })).toBeInTheDocument()
    first.unmount()
    panel({ kitchenContact: { phone: null, email: "hello@mama-ade.example" } })
    expect(within(screen.getByTestId("allergen-kitchen-contact")).queryByRole("link", { name: /020/ })).toBeNull()
    expect(within(screen.getByTestId("allergen-kitchen-contact")).getByRole("link", { name: "hello@mama-ade.example" })).toBeInTheDocument()
  })

  it("with no contact stored it says so, and says what this page does offer instead", () => {
    panel({ kitchenContact: { phone: null, email: null } })
    const contact = screen.getByTestId("allergen-kitchen-contact")
    expect(contact).toHaveTextContent(/has not published a phone number or email address/i)
    expect(contact).toHaveTextContent(/allergy or dietary note/i)
    expect(within(contact).queryByRole("link")).toBeNull()
    // Blank strings are not contact details.
    expect(kitchenContactCopy("Mama Ade's Kitchen", { phone: "  ", email: "" }).kind).toBe("none")
  })

  it("is shown in all three states of the panel (declared, declared none, not recorded)", () => {
    const contact = { phone: "020 7946 0000", email: null }
    for (const allergenNames of [["Peanuts"], [], null]) {
      const view = panel({ allergenNames, kitchenContact: contact })
      expect(screen.getByTestId("allergen-kitchen-contact")).toBeInTheDocument()
      view.unmount()
    }
  })

  it("CONTROL: with no contact prop (the shop has not loaded) the panel claims nothing either way", () => {
    panel()
    expect(screen.getByTestId("order-allergen-panel")).toBeInTheDocument()
    expect(screen.queryByTestId("allergen-kitchen-contact")).toBeNull()
  })
})
