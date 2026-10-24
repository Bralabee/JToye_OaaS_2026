import { fireEvent, screen } from "@testing-library/react"

/**
 * #959 (SW-ADE-01): a new product must name its shop, so a test that creates one through the
 * dashboard form has to pick one the way a vendor does. These helpers give the fake API a shop
 * list and pick from it by its visible name.
 */
export const TEST_SHOP = {
  id: "5a5a5a5a-1111-4111-8111-111111111111",
  name: "Peckham Kitchen",
  published: true,
}

/** A fake `apiClient.get`: the shops endpoint answers with TEST_SHOP, everything else with `content`. */
export function getWithShop(content: unknown[]) {
  return (url: string) => {
    const rows = String(url).startsWith("/api/v1/shops") ? [TEST_SHOP] : content
    return Promise.resolve({
      data: { content: rows, totalElements: rows.length, totalPages: 1 },
    })
  }
}

/** Choose TEST_SHOP in the open create form's Shop Assignment select, once its option has loaded. */
export async function chooseTestShop() {
  await screen.findByRole("option", { name: TEST_SHOP.name })
  fireEvent.change(screen.getByLabelText("Shop Assignment"), { target: { value: TEST_SHOP.id } })
}
