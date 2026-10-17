import { render, screen, waitFor, fireEvent } from "@testing-library/react"
import {
  KdsCookSheetPanel,
  KdsScheduledWindowBadge,
} from "../kds-cook-sheet-panel"
import type { CookSheetDto, FulfilmentSlotDto } from "@/types/api"

const mockGet = jest.fn()
jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockGet(...args),
  },
}))

beforeAll(() => {
  Object.defineProperty(window, "print", { value: jest.fn(), writable: true })
})

beforeEach(() => {
  mockGet.mockReset()
  ;(window.print as jest.Mock).mockClear()
})

const sampleSlots: FulfilmentSlotDto[] = [
  {
    id: "slot-fri",
    shopId: "shop-1",
    serviceDate: "2026-10-23",
    windowStart: "17:00",
    windowEnd: "19:00",
    windowLabel: "Friday 17:00 - 19:00",
    cutoffAt: "2026-10-22T18:00:00Z",
    maxOrders: 25,
    maxUnits: null,
    reservedOrders: 18,
    reservedUnits: 0,
    remainingOrders: 7,
    remainingUnits: null,
    active: true,
  },
]

const sampleCookSheet: CookSheetDto = {
  shopId: "shop-1",
  scheduledForDate: null,
  fulfilmentSlotId: null,
  totalOrders: 14,
  totalUnits: 42,
  items: [
    {
      productId: "prod-jollof",
      productName: "Smoky Party Jollof (2L Cooler)",
      totalQuantity: 28,
      orderCount: 12,
      allergenMask: 2,
      allergenNames: ["Crustaceans"],
    },
    {
      productId: "prod-plantain",
      productName: "Fried Sweet Plantain Tray",
      totalQuantity: 14,
      orderCount: 9,
      allergenMask: 0,
      allergenNames: [],
    },
  ],
  allergyAlerts: [
    {
      orderId: "ord-1",
      orderNumber: "ORD-101",
      customerName: "Adaeze Okafor",
      allergyNote: "Severe peanut allergy — please use separate prep utensils",
      acknowledged: false,
    },
    {
      orderId: "ord-2",
      orderNumber: "ORD-102",
      customerName: "Tunde Bakare",
      allergyNote: "No sesame seeds",
      acknowledged: true,
    },
  ],
}

describe("KdsCookSheetPanel (GAP-GTM-02 / V87)", () => {
  it("loads slots and aggregated cook-sheet items, KPI totals, and allergy alerts", async () => {
    mockGet.mockImplementation((url: string) => {
      if (url === "/api/v1/shops/shop-1/slots") {
        return Promise.resolve({ data: sampleSlots })
      }
      if (url.startsWith("/api/v1/orders/cook-sheet")) {
        return Promise.resolve({ data: sampleCookSheet })
      }
      return Promise.resolve({ data: {} })
    })

    render(<KdsCookSheetPanel shopId="shop-1" shopName="Mama Put Kitchen" />)

    expect(await screen.findByTestId("cook-sheet-items")).toBeInTheDocument()
    expect(screen.getByTestId("cook-sheet-total-orders")).toHaveTextContent("14")
    expect(screen.getByTestId("cook-sheet-total-units")).toHaveTextContent("42")
    expect(screen.getByTestId("cook-sheet-alert-count")).toHaveTextContent("2")

    // Item rows and quantities
    expect(screen.getByText("Smoky Party Jollof (2L Cooler)")).toBeInTheDocument()
    expect(screen.getByText(/28/)).toBeInTheDocument()
    expect(screen.getByText("Crustaceans")).toBeInTheDocument()
    expect(screen.getByText("Fried Sweet Plantain Tray")).toBeInTheDocument()
    expect(screen.getByTestId("cook-sheet-allergen-none")).toHaveTextContent(
      "None of the 14 declared"
    )

    // Allergy alerts banner
    const alertBox = screen.getByTestId("cook-sheet-allergy-alerts")
    expect(alertBox).toHaveTextContent("ORD-101")
    expect(alertBox).toHaveTextContent("Severe peanut allergy")
    expect(alertBox).toHaveTextContent("Unread on ticket")
    expect(alertBox).toHaveTextContent("ORD-102")
    expect(alertBox).toHaveTextContent("Note read")
  })

  it("filters cook-sheet query by date and fulfilment slot and clears filters", async () => {
    mockGet.mockImplementation((url: string) => {
      if (url === "/api/v1/shops/shop-1/slots") {
        return Promise.resolve({ data: sampleSlots })
      }
      if (url.startsWith("/api/v1/orders/cook-sheet")) {
        return Promise.resolve({ data: sampleCookSheet })
      }
      return Promise.resolve({ data: {} })
    })

    render(<KdsCookSheetPanel shopId="shop-1" shopName="Mama Put Kitchen" />)
    await screen.findByTestId("cook-sheet-items")

    const dateInput = screen.getByTestId("cook-sheet-date-input") as HTMLInputElement
    fireEvent.change(dateInput, { target: { value: "2026-10-23" } })

    await waitFor(() => {
      expect(mockGet).toHaveBeenCalledWith(
        "/api/v1/orders/cook-sheet?shopId=shop-1&scheduledForDate=2026-10-23"
      )
    })

    const slotSelect = screen.getByTestId("cook-sheet-slot-select") as HTMLSelectElement
    fireEvent.change(slotSelect, { target: { value: "slot-fri" } })

    await waitFor(() => {
      expect(mockGet).toHaveBeenCalledWith(
        "/api/v1/orders/cook-sheet?shopId=shop-1&scheduledForDate=2026-10-23&fulfilmentSlotId=slot-fri"
      )
    })

    fireEvent.click(screen.getByTestId("cook-sheet-clear-filters"))

    await waitFor(() => {
      expect(dateInput.value).toBe("")
      expect(slotSelect.value).toBe("")
    })
  })

  it("renders empty state when no active prep items match and disables print button", async () => {
    mockGet.mockImplementation((url: string) => {
      if (url === "/api/v1/shops/shop-1/slots") {
        return Promise.resolve({ data: [] })
      }
      return Promise.resolve({
        data: {
          ...sampleCookSheet,
          totalOrders: 0,
          totalUnits: 0,
          items: [],
          allergyAlerts: [],
        },
      })
    })

    render(<KdsCookSheetPanel shopId="shop-1" shopName="Mama Put Kitchen" />)
    expect(await screen.findByTestId("cook-sheet-empty")).toHaveTextContent(
      /No accepted dishes in this batch/i
    )
    expect(screen.getByTestId("cook-sheet-print-btn")).toBeDisabled()
  })

  it("calls window.print when Print cook sheet is clicked on a populated sheet", async () => {
    mockGet.mockImplementation((url: string) => {
      if (url === "/api/v1/shops/shop-1/slots") {
        return Promise.resolve({ data: sampleSlots })
      }
      return Promise.resolve({ data: sampleCookSheet })
    })

    render(<KdsCookSheetPanel shopId="shop-1" shopName="Mama Put Kitchen" />)
    const printBtn = await screen.findByTestId("cook-sheet-print-btn")
    await waitFor(() => expect(printBtn).toBeEnabled())
    fireEvent.click(printBtn)
    expect(window.print).toHaveBeenCalledTimes(1)
  })

  it("surfaces an alert when the cook-sheet request fails", async () => {
    mockGet.mockImplementation((url: string) => {
      if (url === "/api/v1/shops/shop-1/slots") {
        return Promise.resolve({ data: [] })
      }
      return Promise.reject(new Error("500"))
    })

    render(<KdsCookSheetPanel shopId="shop-1" shopName="Mama Put Kitchen" />)
    const alert = await screen.findByTestId("cook-sheet-error")
    expect(alert).toHaveAttribute("role", "alert")
    expect(alert).toHaveTextContent(/Could not load the consolidated cook sheet/i)
  })

  it("downloads a 62mm thermal PPDS label PDF for a dish row (GTM-BLK-06)", async () => {
    const createObjectURLSpy = jest.fn(() => "blob:mock-thermal-label")
    const revokeObjectURLSpy = jest.fn()
    Object.defineProperty(URL, "createObjectURL", { value: createObjectURLSpy, writable: true })
    Object.defineProperty(URL, "revokeObjectURL", { value: revokeObjectURLSpy, writable: true })

    mockGet.mockImplementation((url: string) => {
      if (url === "/api/v1/shops/shop-1/slots") {
        return Promise.resolve({ data: sampleSlots })
      }
      if (url.startsWith("/api/v1/orders/cook-sheet")) {
        return Promise.resolve({ data: sampleCookSheet })
      }
      if (url.startsWith("/api/v1/products/prod-jollof/label")) {
        return Promise.resolve({ data: new Blob(["%PDF-1.4"], { type: "application/pdf" }) })
      }
      return Promise.resolve({ data: {} })
    })

    render(<KdsCookSheetPanel shopId="shop-1" shopName="Mama Put Kitchen" />)
    const labelBtn = await screen.findByTestId("cook-sheet-thermal-label-btn-prod-jollof")
    fireEvent.click(labelBtn)

    await waitFor(() => {
      expect(mockGet).toHaveBeenCalledWith(
        expect.stringMatching(
          /^\/api\/v1\/products\/prod-jollof\/label\?productionDate=\d{4}-\d{2}-\d{2}&format=THERMAL_62MM$/
        ),
        { responseType: "blob" }
      )
    })
    expect(createObjectURLSpy).toHaveBeenCalled()
    expect(revokeObjectURLSpy).toHaveBeenCalledWith("blob:mock-thermal-label")
  })
})

describe("KdsScheduledWindowBadge", () => {
  it("renders nothing when scheduledWindowLabel is absent", () => {
    const { container } = render(
      <KdsScheduledWindowBadge scheduledWindowLabel={null} scheduledForDate="2026-10-23" />
    )
    expect(container).toBeEmptyDOMElement()
  })

  it("renders the window label and date when present", () => {
    render(
      <KdsScheduledWindowBadge
        scheduledWindowLabel="Friday 17:00 - 19:00"
        scheduledForDate="2026-10-23"
      />
    )
    const badge = screen.getByTestId("kds-scheduled-window-badge")
    expect(badge).toHaveTextContent("Friday 17:00 - 19:00")
    expect(badge).toHaveTextContent("(2026-10-23)")
  })
})
