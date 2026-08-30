/**
 * QA-council F2 sweep (byte-identical latent sibling of FEB-1): the customers
 * list must discriminate a load FAILURE from a genuine empty CRM. Before this
 * fix, `fetchCustomers`'s catch block only toasted and left `customers` at its
 * initial `[]`, so a 429 rendered the identical "No customers yet / Add
 * Customer" empty state a vendor with zero customers sees.
 */
import { render, screen, waitFor } from "@testing-library/react"
import CustomersPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

describe("Customers page — load-failure vs genuine-empty (F2 sweep)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it("shows the error panel, not the empty state, when the initial load 429s", async () => {
    mockedApiClient.get.mockRejectedValue({
      response: { status: 429, headers: {}, data: {} },
    })

    render(<CustomersPage />)

    expect(await screen.findByTestId("load-error-panel")).toBeInTheDocument()
    expect(screen.queryByText("No customers yet")).not.toBeInTheDocument()
  })

  it("CONTROL: a genuine 200 with zero rows still shows the real empty state", async () => {
    mockedApiClient.get.mockResolvedValue({
      data: { content: [], totalElements: 0, totalPages: 0 },
    })

    render(<CustomersPage />)

    await waitFor(() => {
      expect(screen.getByText("No customers yet")).toBeInTheDocument()
    })
    expect(screen.queryByTestId("load-error-panel")).not.toBeInTheDocument()
  })
})
