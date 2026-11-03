/**
 * Issue #856 / SW-TND-08 (owner ruling 2026-11-03): for a caller whose role on a
 * customer's shop is STAFF, GET /customers returns the customer's name and phone
 * but `email: null`. The rule is enforced in the API; this page must render that
 * absence cleanly: no empty Mail line, no "undefined", no "null".
 *
 * The CONTROL row (a customer that still carries an email) proves the Mail-icon
 * selector can match, so its absence in the minimised row means something.
 */
import { render, screen } from "@testing-library/react"
import CustomersPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

const base = {
  tenantId: "00000000-0000-0000-0000-000000000001",
  allergenRestrictions: 0,
  createdAt: "2026-11-03T10:00:00Z",
  updatedAt: "2026-11-03T10:00:00Z",
}

function rowOf(name: string): HTMLElement {
  const row = screen.getByText(name).closest("tr")
  if (!row) throw new Error(`no table row for ${name}`)
  return row as HTMLElement
}

function mailIcons(row: HTMLElement): Element[] {
  return Array.from(row.querySelectorAll("svg")).filter((svg) =>
    (svg.getAttribute("class") ?? "").includes("lucide-mail")
  )
}

describe("Customers page — a STAFF-minimised customer has no email (#856)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it("renders name and phone, and no Mail line, for a customer whose email is null", async () => {
    mockedApiClient.get.mockResolvedValue({
      data: {
        content: [
          {
            ...base,
            id: "c-minimised",
            name: "Minimised Customer",
            email: null,
            phone: "07700 900111",
          },
          {
            ...base,
            id: "c-control",
            name: "Control Customer",
            email: "control@example.test",
            phone: "07700 900222",
          },
        ],
        totalElements: 2,
        totalPages: 1,
      },
    })

    render(<CustomersPage />)

    await screen.findByText("Minimised Customer")
    const minimised = rowOf("Minimised Customer")
    const control = rowOf("Control Customer")

    // CONTROL: the selector matches a real Mail icon.
    expect(mailIcons(control)).toHaveLength(1)
    expect(control).toHaveTextContent("control@example.test")

    // The minimised row keeps name and phone, and carries no Mail line.
    expect(minimised).toHaveTextContent("Minimised Customer")
    expect(minimised).toHaveTextContent("07700 900111")
    expect(mailIcons(minimised)).toHaveLength(0)
    expect(minimised.textContent).not.toMatch(/undefined|null/)

    // #856 review R0-2: the server refuses to edit a customer the caller sees minimised (it has
    // never been shown the email it would overwrite), so the row offers no Edit; the control does.
    expect(
      screen.getByRole("button", { name: "Edit customer Control Customer" })
    ).toBeInTheDocument()
    expect(
      screen.queryByRole("button", { name: "Edit customer Minimised Customer" })
    ).not.toBeInTheDocument()
  })
})
