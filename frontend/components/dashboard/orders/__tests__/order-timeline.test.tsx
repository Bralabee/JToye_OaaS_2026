/**
 * #857: a vendor could not show who prepared an order or when. The detail page now carries an
 * activity timeline read from GET /orders/{id}/timeline, and a CSV of it for a records request.
 *
 * Asserted (network faked): who and when per step, in order; SYSTEM and unnamed staff worded
 * honestly; the recorded cancel reason shown by its label; a failed read says so with a retry and
 * does not blank the page; the CSV neutralises spreadsheet formulas in a name.
 */
import { act, fireEvent, render, screen, within } from "@testing-library/react"
import { OrderTimeline, timelineCsv } from "@/components/dashboard/orders/order-timeline"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

const EVENTS = [
  { kind: "PLACED", fromStatus: null, status: "PENDING", paymentStatus: null, detail: null, at: "2026-11-03T18:00:00Z", actorType: "SYSTEM", actorName: null },
  { kind: "STATUS", fromStatus: "PENDING", status: "CONFIRMED", paymentStatus: null, detail: null, at: "2026-11-03T18:02:00Z", actorType: "STAFF", actorName: "Ade Cook" },
  { kind: "STATUS", fromStatus: "CONFIRMED", status: "PREPARING", paymentStatus: null, detail: null, at: "2026-11-03T18:05:00Z", actorType: "STAFF", actorName: "Bola Cook" },
  { kind: "PAYMENT", fromStatus: null, status: "PREPARING", paymentStatus: "CAPTURED", detail: null, at: "2026-11-03T18:06:00Z", actorType: "SYSTEM", actorName: null },
  { kind: "STATUS", fromStatus: "PREPARING", status: "CANCELLED", paymentStatus: null, detail: "SUSPECTED_FAKE", at: "2026-11-03T18:30:00Z", actorType: "STAFF", actorName: null },
]

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockResolvedValue({ data: EVENTS })
})

describe("OrderTimeline (issue #857)", () => {
  it("lists each step with who took it and when, oldest first", async () => {
    render(<OrderTimeline orderId="o1" orderNumber="ORD-1" refreshKey="a" />)

    const list = await screen.findByRole("list", { name: /order activity/i })
    const items = within(list).getAllByRole("listitem")
    expect(items).toHaveLength(5)
    expect(mockedApiClient.get).toHaveBeenCalledWith("/api/v1/orders/o1/timeline")

    expect(items[0]).toHaveTextContent("Order placed")
    expect(items[0]).toHaveTextContent("Customer or automatic")
    expect(items[1]).toHaveTextContent("Accepted")
    expect(items[1]).toHaveTextContent("Ade Cook")
    expect(items[2]).toHaveTextContent("Started preparing")
    expect(items[2]).toHaveTextContent("Bola Cook")
    // A real UK date and time, not an ISO string.
    expect(items[1]).toHaveTextContent("3 November 2026, 18:02")
    expect(items[1].querySelector("time")).toHaveAttribute("datetime", "2026-11-03T18:02:00Z")
  })

  it("words a payment change, an unnamed team member and a recorded cancel reason", async () => {
    render(<OrderTimeline orderId="o1" orderNumber="ORD-1" refreshKey="a" />)
    const items = within(await screen.findByRole("list", { name: /order activity/i })).getAllByRole("listitem")

    expect(items[3]).toHaveTextContent("Payment captured")
    expect(items[4]).toHaveTextContent("Cancelled")
    expect(items[4]).toHaveTextContent("A team member")
    expect(items[4]).toHaveTextContent("Looks like a fake or test order")
  })

  it("a history with nothing recorded says so", async () => {
    mockedApiClient.get.mockResolvedValue({ data: [] })
    render(<OrderTimeline orderId="o1" orderNumber="ORD-1" refreshKey="a" />)
    expect(await screen.findByText(/no activity recorded/i)).toBeInTheDocument()
  })

  it("a failed read shows an inline alert with Retry, and Retry reads again", async () => {
    mockedApiClient.get.mockRejectedValueOnce({ response: { status: 500 } })
    render(<OrderTimeline orderId="o1" orderNumber="ORD-1" refreshKey="a" />)

    expect(await screen.findByRole("alert")).toHaveTextContent(/couldn't load the activity/i)
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /retry/i }))
    })
    expect(await screen.findByRole("list", { name: /order activity/i })).toBeInTheDocument()
    expect(mockedApiClient.get).toHaveBeenCalledTimes(2)
  })

  it("reads again when the order changes underneath it (refreshKey)", async () => {
    const { rerender } = render(<OrderTimeline orderId="o1" orderNumber="ORD-1" refreshKey="a" />)
    await screen.findByRole("list", { name: /order activity/i })
    rerender(<OrderTimeline orderId="o1" orderNumber="ORD-1" refreshKey="b" />)
    await screen.findByRole("list", { name: /order activity/i })
    expect(mockedApiClient.get).toHaveBeenCalledTimes(2)
  })

  it("Download CSV hands the browser a file named for the order", async () => {
    const created: Blob[] = []
    const names: string[] = []
    URL.createObjectURL = jest.fn((b: Blob) => (created.push(b), "blob:t")) as unknown as typeof URL.createObjectURL
    URL.revokeObjectURL = jest.fn()
    const spy = jest.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      names.push(this.download)
    })
    render(<OrderTimeline orderId="o1" orderNumber="ORD-1" refreshKey="a" />)
    await screen.findByRole("list", { name: /order activity/i })

    fireEvent.click(screen.getByRole("button", { name: /download csv/i }))

    expect(names).toEqual(["order-ORD-1-activity.csv"])
    expect(created).toHaveLength(1)
    spy.mockRestore()
  })
})

describe("timelineCsv", () => {
  it("has a header, one row per event, and neutralises formulas and quotes", () => {
    const csv = timelineCsv([
      { ...EVENTS[1], actorName: "=HYPERLINK(\"http://evil\")" },
      { ...EVENTS[2], actorName: "Bola, \"B\" Cook" },
    ] as never)
    const lines = csv.trimEnd().split("\r\n")
    expect(lines[0]).toBe("time,event,status,by,detail")
    expect(lines).toHaveLength(3)
    // A leading = is defused with an apostrophe, then RFC 4180-quoted.
    expect(lines[1]).toContain(`"'=HYPERLINK(""http://evil"")"`)
    expect(lines[2]).toContain(`"Bola, ""B"" Cook"`)
    expect(lines[1].startsWith("2026-11-03T18:02:00Z,")).toBe(true)
  })
})
