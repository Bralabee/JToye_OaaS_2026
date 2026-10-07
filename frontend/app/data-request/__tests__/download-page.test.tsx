/**
 * The page the Article 15 email links to (#778, D-01, 31.1-17).
 *
 * The link is `{base}#token=<43 chars>`. The token is a single-use bearer
 * credential for a personal-data document, so the contract this file holds is
 * about WHEN it is spent, not only what is shown:
 *
 *   - the token is read from the URL FRAGMENT and removed from the address bar
 *     on load (history, a shared screenshot, a copied URL);
 *   - NOTHING is sent until the person presses "Show my data" — a mail
 *     scanner or link prefetcher that renders the page must not spend it
 *     (T-31.1-61). This is asserted as zero POSTs before the press;
 *   - the export is rendered as text (T-31.1-66): content that looks like HTML
 *     stays text;
 *   - every refusal (used, expired, not recognised) is ONE message with a way
 *     to ask for a new copy, never raw JSON.
 *
 * publicApiClient is mocked; the backend half is DsarExportDownloadIntegrationTest.
 */
import { StrictMode } from "react"
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react"
import DownloadPage, { metadata } from "../download/page"
import { DownloadClient } from "../download/download-client"
import publicApiClient from "@/lib/public-api-client"

jest.mock("@/lib/public-api-client")
const mockedPost = publicApiClient.post as jest.Mock

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

const TOKEN = "Abc123-tokenvalue_that_must_never_render_xyz"
const PATH = "/data-request/download"

const DOCUMENT = {
  format: "jtoye-dsar-export/1",
  generatedAt: "2026-10-06T19:52:11Z",
  requestedFor: "grace@example.test",
  summary: {
    vendorDataHeld: true,
    vendorCount: 1,
    statement: "The shops listed below hold personal data linked to this email address.",
  },
  about: {
    purposes: ["To take, prepare, deliver and take payment for your orders."],
    recipients: "Each shop listed received the data in its section.",
    retention: "See our retention schedule.",
    rights: [{ right: "complaint", how: "You can complain to the ICO." }],
    source: "You provided this data yourself.",
  },
  platformAccount: {
    status: "FOUND",
    note: "You have a J'Toye sign-in account with this email address.",
    accounts: [{ username: "grace.persona", email: "grace@example.test" }],
  },
  vendors: [
    {
      reference: "50e20a40-404b-49f3-a5c4-ce6a0c319059",
      recipient: {
        legalName: "Mama Ade's Kitchen Ltd",
        traderIdentityOnFile: true,
        shops: ["Mama Ade's Peckham"],
      },
      customerRecords: [{ name: "Grace Customer", phone: "07700900111" }],
      orders: [
        {
          orderNumber: "ORD-87660AEA",
          shop: "Mama Ade's Peckham",
          status: "COMPLETED",
          placedAt: "2026-09-01T12:00:00Z",
          notes: "<img src=x onerror=alert(1)>",
          totals: { totalAmountPennies: 1250 },
          items: [{ productName: "Egusi Soup", quantity: 2, totalPricePennies: 1000 }],
        },
      ],
      reviews: [{ shop: "Mama Ade's Peckham", comment: "lovely puff puff", foodRating: 5 }],
      communicationPreferences: { marketingOptIn: null, unsubscribed: [] },
      staffDirectory: [],
    },
  ],
}
const DOCUMENT_TEXT = JSON.stringify(DOCUMENT, null, 2)

function openWithHash(hash: string) {
  window.history.replaceState(null, "", `${PATH}${hash}`)
}

beforeEach(() => {
  jest.clearAllMocks()
  openWithHash(`#token=${TOKEN}`)
})

describe("the download page's metadata", () => {
  it("is noindex,nofollow and titled for the subject", () => {
    const robots = (metadata.robots ?? {}) as { index?: boolean; follow?: boolean }
    expect(robots.index).toBe(false)
    expect(robots.follow).toBe(false)
    expect(metadata.title).toBe("Your data — J'Toye")
  })

  it("renders inside the public shell with the client island", () => {
    render(<DownloadPage />)
    expect(screen.getByRole("button", { name: /show my data/i })).toBeInTheDocument()
  })
})

describe("the token is spent only by an explicit press", () => {
  it("offers 'Show my data' and sends nothing before it is pressed", async () => {
    render(<DownloadClient />)
    expect(await screen.findByRole("button", { name: /show my data/i })).toBeInTheDocument()
    // Give any effect-driven request every chance to fire.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20))
    })
    expect(mockedPost).toHaveBeenCalledTimes(0)
  })

  it("removes the token from the address bar on load", async () => {
    render(<DownloadClient />)
    await screen.findByRole("button", { name: /show my data/i })
    expect(window.location.hash).toBe("")
    expect(window.location.href).not.toContain(TOKEN)
    expect(window.location.pathname).toBe(PATH)
  })

  it("POSTs the token in the body exactly once when pressed, even if pressed twice", async () => {
    let resolve!: (v: unknown) => void
    mockedPost.mockReturnValue(new Promise((r) => (resolve = r)))
    render(<DownloadClient />)
    const button = await screen.findByRole("button", { name: /show my data/i })
    fireEvent.click(button)
    fireEvent.click(button)
    expect(mockedPost).toHaveBeenCalledTimes(1)
    expect(mockedPost.mock.calls[0][0]).toBe("/api/v1/public/gdpr/dsar/export")
    expect(mockedPost.mock.calls[0][1]).toEqual({ token: TOKEN })
    await act(async () => {
      resolve({ status: 200, data: DOCUMENT_TEXT })
    })
  })

  it("never renders the token into the page", async () => {
    mockedPost.mockResolvedValue({ status: 200, data: DOCUMENT_TEXT })
    const { container } = render(<DownloadClient />)
    fireEvent.click(await screen.findByRole("button", { name: /show my data/i }))
    await screen.findByRole("heading", { name: /mama ade's kitchen ltd/i })
    expect(container.innerHTML).not.toContain(TOKEN)
  })
})

describe("a successful download", () => {
  async function download() {
    mockedPost.mockResolvedValue({ status: 200, data: DOCUMENT_TEXT })
    const view = render(<DownloadClient />)
    fireEvent.click(await screen.findByRole("button", { name: /show my data/i }))
    await screen.findByRole("heading", { name: /mama ade's kitchen ltd/i })
    return view
  }

  it("renders a section per shop with its orders, reviews and details", async () => {
    await download()
    expect(screen.getByText("ORD-87660AEA")).toBeInTheDocument()
    expect(screen.getByText(/lovely puff puff/)).toBeInTheDocument()
    expect(screen.getByText("Grace Customer")).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: /^orders$/i })).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: /^reviews$/i })).toBeInTheDocument()
    expect(screen.getByText(/you have a j'toye sign-in account/i)).toBeInTheDocument()
    expect(screen.getByText("£12.50")).toBeInTheDocument()
  })

  it("renders export content as text, never as HTML", async () => {
    const { container } = await download()
    expect(container.querySelector("img")).toBeNull()
    expect(screen.getByText("<img src=x onerror=alert(1)>")).toBeInTheDocument()
  })

  it("offers 'Save as file' that saves the same JSON it received", async () => {
    const created: Blob[] = []
    const originalCreate = URL.createObjectURL
    const originalRevoke = URL.revokeObjectURL
    URL.createObjectURL = jest.fn((b: Blob) => {
      created.push(b)
      return "blob:mock"
    }) as unknown as typeof URL.createObjectURL
    URL.revokeObjectURL = jest.fn()
    const clickSpy = jest.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {})
    try {
      await download()
      fireEvent.click(screen.getByRole("button", { name: /save as file/i }))
      expect(created).toHaveLength(1)
      expect(created[0].type).toBe("application/json")
      const saved = await new Promise<string>((resolve) => {
        const reader = new FileReader()
        reader.onload = () => resolve(reader.result as string)
        reader.readAsText(created[0])
      })
      expect(saved).toBe(DOCUMENT_TEXT)
      expect(clickSpy).toHaveBeenCalledTimes(1)
    } finally {
      URL.createObjectURL = originalCreate
      URL.revokeObjectURL = originalRevoke
      clickSpy.mockRestore()
    }
  })
})

describe("an unavailable link", () => {
  it("shows one friendly message with a route to a new copy, never raw JSON", async () => {
    mockedPost.mockRejectedValue({
      response: {
        status: 404,
        data: {
          type: "https://jtoye.uk/errors/dsar-export-unavailable",
          code: "DSAR_EXPORT_UNAVAILABLE",
          detail: "This download link has already been used, has expired, or was not recognised.",
        },
      },
    })
    const { container } = render(<DownloadClient />)
    fireEvent.click(await screen.findByRole("button", { name: /show my data/i }))
    expect(await screen.findByRole("heading", { name: /this link can't be used/i })).toBeInTheDocument()
    expect(screen.getByText(/already been used, has expired, or was not recognised/i)).toBeInTheDocument()
    expect(screen.getByRole("link", { name: /request a new copy/i })).toHaveAttribute("href", "/shop/account")
    expect(screen.getByRole("link", { name: /privacy notice/i })).toHaveAttribute("href", "/legal/privacy")
    expect(container.textContent).not.toContain("dsar-export-unavailable")
    expect(container.textContent).not.toContain("{")
  })

  it("shows the same message, without any request, when the link carries no token", async () => {
    openWithHash("")
    render(<DownloadClient />)
    expect(await screen.findByRole("heading", { name: /this link can't be used/i })).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /show my data/i })).toBeNull()
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(0))
  })
})

describe("under React Strict Mode (next dev double-runs mount effects)", () => {
  // Strict Mode mounts, unmounts and re-mounts, so the read-once effect runs
  // twice. The first run strips the fragment; the second must not then
  // overwrite the token it already holds with null, or a valid link reads as
  // unavailable on `next dev` (the start-dev.sh hybrid runtime).
  it("keeps the token read on the first run and spends it on the press", async () => {
    mockedPost.mockResolvedValue({ status: 200, data: DOCUMENT_TEXT })
    render(
      <StrictMode>
        <DownloadClient />
      </StrictMode>
    )
    fireEvent.click(await screen.findByRole("button", { name: /show my data/i }))
    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    expect(mockedPost.mock.calls[0][1]).toEqual({ token: TOKEN })
    expect(window.location.hash).toBe("")
  })
})
