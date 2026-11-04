/**
 * Round-1 review A6: the vendor create-order dialog accepted a one-letter name and a "123" phone, which the
 * server's @PlausibleContact refuses with a 400 the page showed only as a generic toast. The dialog now shares
 * the checkout's plausibility helpers, so the same values are refused as FIELD errors before any request.
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
      return Promise.resolve({ data: { content: [{ id: SHOP_ID, name: "Brixton Kitchen", slug: "brixton" }] } })
    }
    if (url.startsWith("/api/v1/products")) {
      return Promise.resolve({
        data: { content: [{ id: PRODUCT_ID, title: "Jollof Rice", pricePennies: 899, shopId: SHOP_ID, available: true }] },
      })
    }
    return Promise.resolve({ data: { content: [] } })
  })
  mockedApiClient.post.mockResolvedValue({ data: {} })
})

async function fillAndSubmit(name: string, phone: string) {
  const user = userEvent.setup()
  render(<OrdersPage />)
  const triggers = await screen.findAllByRole("button", { name: /create order/i })
  await user.click(triggers[0])
  await screen.findByRole("combobox", { name: /how this order is fulfilled/i })
  await user.click(screen.getByRole("combobox", { name: /shop for this order/i }))
  await user.click(await screen.findByRole("option", { name: /brixton kitchen/i }))
  await user.type(screen.getByLabelText(/customer name/i), name)
  await user.type(screen.getByLabelText(/customer email/i), "caller@example.com")
  if (phone) await user.type(screen.getByLabelText(/customer phone/i), phone)
  await user.click(screen.getByRole("button", { name: /add item/i }))
  await user.click(screen.getByRole("combobox", { name: /product for order item 1/i }))
  await user.click(await screen.findByRole("option", { name: /jollof rice/i }))
  const dialog = screen.getByRole("dialog")
  await user.click(within(dialog).getByRole("button", { name: /^create order$/i }))
}

describe("A6: the create-order dialog refuses what the server refuses, as field errors", () => {
  it("a one-letter name is a field error and sends nothing", async () => {
    await fillAndSubmit("J", "")
    expect(await screen.findByText(/enter the customer's name \(at least two letters\)/i)).toBeInTheDocument()
    expect(mockedApiClient.post).not.toHaveBeenCalled()
  })

  it("an implausible phone is a field error and sends nothing", async () => {
    await fillAndSubmit("Phone Caller", "123")
    expect(await screen.findByText(/enter a valid phone number/i)).toBeInTheDocument()
    expect(mockedApiClient.post).not.toHaveBeenCalled()
  })

  it("control: a plausible name with a blank phone is sent", async () => {
    await fillAndSubmit("Phone Caller", "")
    await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalledTimes(1))
  })
})
