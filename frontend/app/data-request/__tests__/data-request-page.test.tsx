/**
 * SW-SAM-03 (swarm epic #964): a guest can make a data request online, without an account.
 * The form lodges at the public intake with the typed address and type, one request per press, the
 * same key on a retry and a new key when the request changes, and never shows what the intake said.
 */
import { render, screen, fireEvent, waitFor } from "@testing-library/react"
import { DataRequestClient, DATA_REQUEST_COPY } from "@/app/data-request/data-request-client"
import { metadata } from "@/app/data-request/page"

const mockPost = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { post: (...args: unknown[]) => mockPost(...args) },
}))

beforeEach(() => mockPost.mockReset())

function fill(email: string, type: "copy" | "erase" = "copy") {
  fireEvent.change(screen.getByLabelText(DATA_REQUEST_COPY.emailLabel), { target: { value: email } })
  fireEvent.click(
    screen.getByRole("radio", {
      name: new RegExp(type === "copy" ? DATA_REQUEST_COPY.access.label : DATA_REQUEST_COPY.erasure.label),
    })
  )
}

const submit = () => fireEvent.click(screen.getByRole("button", { name: DATA_REQUEST_COPY.submit }))

describe("/data-request (guest data request)", () => {
  it("is a public, indexable page with its own title and canonical", () => {
    expect(metadata.title).toBe("Make a data request — J'Toye")
    expect(metadata.alternates?.canonical).toBe("/data-request")
    expect(metadata.robots).toBeUndefined()
  })

  it("lodges an ACCESS request for the typed address at the public intake, with an Idempotency-Key", async () => {
    mockPost.mockResolvedValue({ status: 202, data: { message: "opaque" } })
    render(<DataRequestClient />)
    fill("  guest@example.com ")
    submit()

    await waitFor(() => expect(mockPost).toHaveBeenCalledTimes(1))
    const [path, body, config] = mockPost.mock.calls[0]
    expect(path).toBe("/api/v1/public/gdpr/dsar")
    expect(body).toEqual({ email: "guest@example.com", requestType: "ACCESS" })
    expect(config.headers["Idempotency-Key"]).toMatch(/^[0-9a-f-]{36}$/)
    const status = await screen.findByRole("status")
    expect(status).toHaveTextContent(DATA_REQUEST_COPY.lodgedHeading)
    expect(status).toHaveTextContent("guest@example.com")
    expect(status).not.toHaveTextContent("opaque")
  })

  it("lodges an ERASURE request when erasure is chosen", async () => {
    mockPost.mockResolvedValue({ status: 202, data: {} })
    render(<DataRequestClient />)
    fill("guest@example.com", "erase")
    submit()
    await waitFor(() => expect(mockPost).toHaveBeenCalledTimes(1))
    expect(mockPost.mock.calls[0][1]).toEqual({ email: "guest@example.com", requestType: "ERASURE" })
  })

  it("refuses an address that is not one, without calling the intake", async () => {
    render(<DataRequestClient />)
    fill("not-an-address")
    submit()
    expect(await screen.findByText(DATA_REQUEST_COPY.invalidEmail)).toBeInTheDocument()
    expect(screen.getByLabelText(DATA_REQUEST_COPY.emailLabel)).toHaveAttribute("aria-invalid", "true")
    expect(mockPost).not.toHaveBeenCalled()
  })

  it("a retry after a failure re-sends the SAME key; a changed address gets a NEW one", async () => {
    mockPost.mockRejectedValueOnce({ response: { status: 503 } })
    render(<DataRequestClient />)
    fill("guest@example.com")
    submit()
    expect(await screen.findByRole("alert")).toBeInTheDocument()

    mockPost.mockRejectedValueOnce({ response: { status: 503 } })
    submit()
    await waitFor(() => expect(mockPost).toHaveBeenCalledTimes(2))
    const first = mockPost.mock.calls[0][2].headers["Idempotency-Key"]
    expect(mockPost.mock.calls[1][2].headers["Idempotency-Key"]).toBe(first)

    mockPost.mockResolvedValueOnce({ status: 202, data: {} })
    fill("other@example.com")
    submit()
    await waitFor(() => expect(mockPost).toHaveBeenCalledTimes(3))
    expect(mockPost.mock.calls[2][2].headers["Idempotency-Key"]).not.toBe(first)
  })

  it("says to wait on a 429", async () => {
    mockPost.mockRejectedValue({ response: { status: 429 } })
    render(<DataRequestClient />)
    fill("guest@example.com")
    submit()
    expect(await screen.findByRole("alert")).toHaveTextContent(/too many requests/i)
  })
})
