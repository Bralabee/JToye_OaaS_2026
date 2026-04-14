/**
 * Unit tests for the Kitchen Display page. We mock useStomp and apiClient so
 * the page renders synchronously and we can exercise the interactive bits:
 *  - shops load, first shop selected by default
 *  - orders render once fetched
 *  - mute toggle persists to localStorage and flips icon
 *  - empty state renders when there are no active orders
 */

import { render, screen, waitFor, fireEvent, act } from "@testing-library/react"
import KitchenPage from "../page"

// Mock useStomp — we want the page's render logic, not a real WS
jest.mock("@/hooks/use-stomp", () => ({
  useStomp: jest.fn(() => ({ connected: true, reconnecting: false })),
}))

// Mock the toast hook — no-op
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

// Mock apiClient — hand-rolled so tests control the data
const mockGet = jest.fn()
const mockPost = jest.fn()
jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockGet(...args),
    post: (...args: unknown[]) => mockPost(...args),
  },
}))

// Radix Select is hard to exercise in jsdom (portal + focus logic). Replace
// with a plain select so the test can interact freely.
jest.mock("@/components/ui/select", () => {
  const React = jest.requireActual("react")
  const Select = ({ children, value, onValueChange }: {
    children: React.ReactNode
    value: string
    onValueChange: (v: string) => void
  }) => (
    <select
      data-testid="shop-select"
      value={value}
      onChange={(e) => onValueChange(e.target.value)}
    >
      {children}
    </select>
  )
  const passthrough = ({ children }: { children?: React.ReactNode }) =>
    <>{children}</>
  const SelectItem = ({ value, children }: { value: string; children: React.ReactNode }) =>
    <option value={value}>{children}</option>
  return {
    Select,
    SelectTrigger: passthrough,
    SelectValue: passthrough,
    SelectContent: passthrough,
    SelectItem,
  }
})

// AudioContext mock so playBeep() does not explode the test runner
beforeAll(() => {
  ;(global as unknown as { AudioContext: unknown }).AudioContext = jest
    .fn()
    .mockImplementation(() => ({
      createOscillator: () => ({
        connect: jest.fn(),
        start: jest.fn(),
        stop: jest.fn(),
        type: "sine",
        frequency: { setValueAtTime: jest.fn(), value: 0 },
      }),
      createGain: () => ({
        connect: jest.fn(),
        gain: {
          setValueAtTime: jest.fn(),
          exponentialRampToValueAtTime: jest.fn(),
        },
      }),
      destination: {},
      currentTime: 0,
      close: jest.fn(),
    }))
})

const shopsPayload = {
  content: [
    {
      id: "shop-1",
      tenantId: "tenant-1",
      name: "Test Shop",
      address: "1 Main St",
      slug: "test",
      description: null,
      logoUrl: null,
      bannerUrl: null,
      phone: null,
      email: null,
      latitude: null,
      longitude: null,
      openingHours: null,
      deliveryInfo: null,
      minimumOrderPennies: 0,
      published: true,
      tags: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    },
  ],
}

function ordersPayload(statuses: string[]) {
  return {
    content: statuses.map((status, i) => ({
      id: `order-${i}`,
      tenantId: "tenant-1",
      shopId: "shop-1",
      status,
      totalAmountPennies: 1000,
      itemCount: 2,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    })),
  }
}

function orderDetailPayload(id: string, status: string) {
  return {
    id,
    tenantId: "tenant-1",
    shopId: "shop-1",
    orderNumber: `ORD-${id}`,
    status,
    customerName: "Alice",
    totalAmountPennies: 1000,
    items: [
      {
        id: "it-1",
        productId: "p-1",
        productName: "Burger",
        quantity: 2,
        unitPricePennies: 500,
        totalPricePennies: 1000,
        createdAt: new Date().toISOString(),
      },
    ],
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }
}

function stubApi(activeStatuses: string[]) {
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/shops")) {
      return Promise.resolve({ data: shopsPayload })
    }
    if (url.startsWith("/api/v1/orders?")) {
      return Promise.resolve({ data: ordersPayload(activeStatuses) })
    }
    const match = url.match(/\/api\/v1\/orders\/(.*)\/detail/)
    if (match) {
      const id = match[1]
      const idx = Number(id.replace("order-", ""))
      return Promise.resolve({ data: orderDetailPayload(id, activeStatuses[idx]) })
    }
    return Promise.resolve({ data: {} })
  })
  mockPost.mockReset()
  mockPost.mockResolvedValue({ data: {} })
}

describe("KitchenPage", () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it("renders the empty state when no active orders are present", async () => {
    stubApi([])
    render(<KitchenPage />)
    expect(
      await screen.findByText(/Kitchen Display/i)
    ).toBeInTheDocument()
    expect(await screen.findByText(/No active orders/i)).toBeInTheDocument()
  })

  it("renders order cards for fetched active orders", async () => {
    stubApi(["CONFIRMED", "PREPARING"])
    render(<KitchenPage />)
    await waitFor(() => {
      expect(mockGet).toHaveBeenCalledWith(
        expect.stringContaining("/api/v1/shops")
      )
    })
    // Customer name appears on each card
    const alice = await screen.findAllByText(/Alice/)
    expect(alice.length).toBeGreaterThanOrEqual(1)
    // Bump buttons show the current action label
    expect(screen.getAllByRole("button").some((b) => /Start Preparing|Mark Ready/.test(b.textContent || ""))).toBe(true)
  })

  it("mute toggle persists state to localStorage", async () => {
    stubApi([])
    render(<KitchenPage />)
    // Wait for initial shops load to finish and mute button to render
    const muteButton = await screen.findByTitle(/Mute alerts|Unmute alerts/)
    expect(localStorage.getItem("kds-muted")).toBeNull()

    act(() => {
      fireEvent.click(muteButton)
    })
    expect(localStorage.getItem("kds-muted")).toBe("true")

    act(() => {
      fireEvent.click(muteButton)
    })
    expect(localStorage.getItem("kds-muted")).toBe("false")
  })

  it("reads initial mute state from localStorage on mount", async () => {
    localStorage.setItem("kds-muted", "true")
    stubApi([])
    render(<KitchenPage />)
    // When muted=true the button title is "Unmute alerts"
    expect(await screen.findByTitle(/Unmute alerts/)).toBeInTheDocument()
  })
})
