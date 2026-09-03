/**
 * COR-1 (QA-council 20260902-134741, adjudication A8 + owner ruling E-1) — the vendor
 * create-order dialog must SAY how the order is fulfilled.
 *
 * <p>Before this change the dialog's Zod schema was {shopId, customerName, customerEmail,
 * customerPhone} and the markup carried no fulfilment or address control anywhere. Every order it
 * created therefore took the backend's V45 entity default and persisted as DELIVERY with a £0
 * delivery fee and no address — 4 of 60 live orders on the dev runtime. Downstream that produced
 * a delivery kitchen ticket with nowhere to deliver to and a READY email promising a delivery.
 *
 * <p>These tests assert the CONTROL and the request BODY, not a screenshot: what matters is what
 * reaches POST /api/v1/orders, because that is what the server persists.
 */
import { render, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import OrdersPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

jest.mock("@/hooks/use-order-events", () => ({
  useOrderEvents: jest.fn(),
}))

const SHOP_ID = "11111111-2222-3333-4444-555555555555"
const PRODUCT_ID = "0b6cbcf6-3535-49a0-a839-3f382e3ba9a7"

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/orders")) {
      return Promise.resolve({ data: { content: [], totalPages: 0, totalElements: 0 } })
    }
    if (url.startsWith("/api/v1/shops")) {
      return Promise.resolve({
        data: { content: [{ id: SHOP_ID, name: "Brixton Kitchen", slug: "brixton" }] },
      })
    }
    if (url.startsWith("/api/v1/products")) {
      return Promise.resolve({
        data: { content: [{ id: PRODUCT_ID, title: "Jollof Rice", pricePennies: 899 }] },
      })
    }
    return Promise.resolve({ data: { content: [] } })
  })
  mockedApiClient.post.mockResolvedValue({ data: {} })
})

// The page renders TWO "Create Order" triggers (page header + empty-state card); either opens
// the same dialog, so the first is taken deliberately rather than by a brittle name tweak.
async function openCreateDialog(user: ReturnType<typeof userEvent.setup>) {
  render(<OrdersPage />)
  const triggers = await screen.findAllByRole("button", { name: /create order/i })
  await user.click(triggers[0])
  const control = await screen.findByRole("combobox", { name: /how this order is fulfilled/i })
  return control
}

describe("COR-1: the vendor create-order dialog carries a fulfilment control", () => {
  it("renders a fulfilment control that opens on Collection", async () => {
    const user = userEvent.setup()
    const control = await openCreateDialog(user)

    // The control exists at all — this is the assertion that fails on the pre-COR-1 tree,
    // where the dialog had no fulfilment affordance of any kind.
    expect(control).toBeInTheDocument()
    expect(control).toHaveTextContent(/collection/i)
  })

  it("hides the delivery address block until DELIVERY is chosen, then shows it", async () => {
    const user = userEvent.setup()
    const control = await openCreateDialog(user)

    // A collection ticket must not ask for an address — that is the whole point of the default.
    expect(screen.queryByLabelText(/address line 1/i)).not.toBeInTheDocument()

    await user.click(control)
    await user.click(await screen.findByRole("option", { name: /delivery/i }))

    await waitFor(() => {
      expect(screen.getByLabelText(/address line 1/i)).toBeInTheDocument()
    })
    expect(screen.getByLabelText(/^city$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/postcode/i)).toBeInTheDocument()
  })

  it("blocks submission of a DELIVERY order with no address — mirroring the server's 400", async () => {
    const user = userEvent.setup()
    const control = await openCreateDialog(user)

    await user.type(screen.getByLabelText(/customer name/i), "Phone Caller")
    await user.type(screen.getByLabelText(/customer email/i), "caller@example.com")
    await user.click(control)
    await user.click(await screen.findByRole("option", { name: /delivery/i }))
    await screen.findByLabelText(/address line 1/i)

    const dialog = screen.getByRole("dialog")
    await user.click(within(dialog).getByRole("button", { name: /^create order$/i }))

    expect(await screen.findByText(/address line 1 is required for a delivery order/i))
      .toBeInTheDocument()
    // Nothing may reach the money endpoint.
    expect(mockedApiClient.post).not.toHaveBeenCalled()
  })
})
