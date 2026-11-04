/**
 * SW-JRD-07 / SW-SAM-11 (owner ruling 2026-11-04): on /shop/account a customer can change their display
 * name and has a "Change password" link to the sign-in provider's own account page. No new profile
 * store: the name is saved through /api/customer-profile into the sign-in account.
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { AccountClient } from "../account-client"

jest.mock("@/lib/public-api-client")

const fetchMock = jest.fn()
beforeEach(() => {
  fetchMock.mockReset()
  global.fetch = fetchMock as unknown as typeof fetch
})

function reply(status: number, body: unknown) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: async () => body })
}

describe("My account: your details", () => {
  it("shows the current name in a labelled field, and the email stays read-only", () => {
    render(<AccountClient email="ada@example.com" name="Ada Lovelace" />)
    const name = screen.getByLabelText(/^your name$/i) as HTMLInputElement
    expect(name.value).toBe("Ada Lovelace")
    expect(name.readOnly).toBe(false)
    expect((screen.getByLabelText(/email address/i) as HTMLInputElement).readOnly).toBe(true)
  })

  it("saving sends only the name to the profile seam and says it was saved", async () => {
    fetchMock.mockReturnValue(reply(200, { displayName: "Ada King" }))
    render(<AccountClient email="ada@example.com" name="Ada Lovelace" />)
    fireEvent.change(screen.getByLabelText(/^your name$/i), { target: { value: "  Ada King " } })
    fireEvent.click(screen.getByRole("button", { name: /save name/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe("/api/customer-profile")
    expect(init.method).toBe("PUT")
    expect(JSON.parse(init.body)).toEqual({ displayName: "Ada King" })
    expect(await screen.findByRole("status")).toHaveTextContent(/name saved/i)
  })

  it("a blank name is refused in the page and nothing is sent", () => {
    render(<AccountClient email="ada@example.com" name="Ada" />)
    fireEvent.change(screen.getByLabelText(/^your name$/i), { target: { value: "   " } })
    fireEvent.click(screen.getByRole("button", { name: /save name/i }))
    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByRole("alert")).toHaveTextContent(/enter a name/i)
  })

  it("an unchanged name does not enable a pointless save", () => {
    render(<AccountClient email="ada@example.com" name="Ada" />)
    expect(screen.getByRole("button", { name: /save name/i })).toBeDisabled()
  })

  it("when core says name changes are unavailable (503) the page says so and keeps what was typed", async () => {
    fetchMock.mockReturnValue(reply(503, { type: "https://jtoye.uk/errors/profile-update-unavailable" }))
    render(<AccountClient email="ada@example.com" name="Ada" />)
    fireEvent.change(screen.getByLabelText(/^your name$/i), { target: { value: "Ada B" } })
    fireEvent.click(screen.getByRole("button", { name: /save name/i }))

    expect(await screen.findByRole("alert")).toHaveTextContent(/can.t be changed right now/i)
    expect((screen.getByLabelText(/^your name$/i) as HTMLInputElement).value).toBe("Ada B")
  })

  it("offers a Change password link to the sign-in provider's account page", () => {
    render(<AccountClient email="ada@example.com" name="Ada" />)
    const link = screen.getByRole("link", { name: /change password/i })
    expect(link.getAttribute("href")).toMatch(/\/account/)
    expect(link.getAttribute("href")).toMatch(/^https?:\/\//)
  })

  it("a token with no email shows neither the name form nor the password link (nothing to attach them to)", () => {
    render(<AccountClient email={null} name="Ada" />)
    expect(screen.queryByLabelText(/^your name$/i)).toBeNull()
    expect(screen.queryByRole("link", { name: /change password/i })).toBeNull()
  })
})
