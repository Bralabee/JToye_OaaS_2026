import { render, screen, waitFor, fireEvent } from "@testing-library/react"
import { ShopFulfilmentSlotsDialog } from "../shop-fulfilment-slots-dialog"
import type { FulfilmentSlotDto, Shop, WhatsAppPreOrderLinkDto } from "@/types/api"

const mockGet = jest.fn()
const mockPost = jest.fn()
const mockDelete = jest.fn()

jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockGet(...args),
    post: (...args: unknown[]) => mockPost(...args),
    delete: (...args: unknown[]) => mockDelete(...args),
  },
}))

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

const sampleShop: Shop = {
  id: "shop-peckham",
  tenantId: "tenant-1",
  name: "Mama Put Peckham",
  address: "10 Rye Lane, London SE15 5BS",
  slug: "mama-put-peckham",
  description: null,
  logoUrl: null,
  bannerUrl: null,
  phone: null,
  email: null,
  latitude: null,
  longitude: null,
  openingHours: null,
  deliveryInfo: null,
  minimumOrderPennies: 1000,
  published: true,
  tags: null,
  fulfilmentTimingMode: "HYBRID_IMMEDIATE_AND_SLOTS",
  settlementPolicyMode: "SPLIT_DEPOSIT_VENDOR_MOR",
  depositBps: 5000,
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z",
}

const existingSlot: FulfilmentSlotDto = {
  id: "slot-fri",
  shopId: "shop-peckham",
  serviceDate: "2026-10-23",
  windowStart: "17:00",
  windowEnd: "19:00",
  windowLabel: "2026-10-23 17:00–19:00",
  cutoffAt: "2026-10-22T18:00:00Z",
  maxOrders: 25,
  maxUnits: 60,
  reservedOrders: 12,
  reservedUnits: 28,
  remainingOrders: 13,
  remainingUnits: 32,
  active: true,
}

const sampleWaLink: WhatsAppPreOrderLinkDto = {
  shopId: "shop-peckham",
  shopName: "Mama Put Peckham",
  shopSlug: "mama-put-peckham",
  slotId: "slot-fri",
  serviceDate: "2026-10-23",
  windowLabel: "2026-10-23 17:00–19:00",
  cutoffAt: "2026-10-22T18:00:00Z",
  storefrontPath: "/shop/mama-put-peckham/checkout?slotId=slot-fri",
  prefilledMessage:
    "Order from Mama Put Peckham for 2026-10-23 17:00–19:00 (13 slots left): /shop/mama-put-peckham/checkout?slotId=slot-fri",
  waMeUrl:
    "https://wa.me/?text=Order%20from%20Mama%20Put%20Peckham",
}

describe("ShopFulfilmentSlotsDialog (ADR-0007 / GAP-GTM-02 & GAP-GTM-06)", () => {
  beforeEach(() => {
    mockGet.mockReset()
    mockPost.mockReset()
    mockDelete.mockReset()
  })

  it("loads existing scheduled slots, generates a WhatsApp pre-order broadcast link, creates a new slot, and deactivates a slot", async () => {
    mockGet.mockImplementation((url: string) => {
      if (url === "/api/v1/shops/shop-peckham/slots") {
        return Promise.resolve({ data: [existingSlot] })
      }
      if (url === "/api/v1/shops/shop-peckham/whatsapp-preorder-link?slotId=slot-fri") {
        return Promise.resolve({ data: sampleWaLink })
      }
      return Promise.resolve({ data: {} })
    })

    const createdSlot: FulfilmentSlotDto = {
      ...existingSlot,
      id: "slot-sat",
      serviceDate: "2026-10-24",
      windowStart: "14:00",
      windowEnd: "17:00",
      windowLabel: "2026-10-24 14:00–17:00",
      reservedOrders: 0,
      remainingOrders: 30,
      maxOrders: 30,
    }
    mockPost.mockResolvedValue({ data: createdSlot })
    mockDelete.mockResolvedValue({ data: { ...existingSlot, active: false } })

    render(
      <ShopFulfilmentSlotsDialog
        open={true}
        onOpenChange={jest.fn()}
        shop={sampleShop}
      />
    )

    // Existing slot renders
    expect(await screen.findByTestId("slot-row-slot-fri")).toBeInTheDocument()
    expect(screen.getByText(/12\/25 orders/)).toBeInTheDocument()

    // Click WhatsApp share link generator on the existing slot
    fireEvent.click(screen.getByTestId("slot-whatsapp-btn-slot-fri"))
    const waPanel = await screen.findByTestId("whatsapp-preorder-link-panel")
    expect(waPanel).toHaveTextContent("/shop/mama-put-peckham/checkout?slotId=slot-fri")

    // Create a new slot
    fireEvent.change(screen.getByLabelText("Service date"), {
      target: { value: "2026-10-24" },
    })
    fireEvent.change(screen.getByLabelText("Window start (UK)"), {
      target: { value: "14:00" },
    })
    fireEvent.change(screen.getByLabelText("Window end (UK)"), {
      target: { value: "17:00" },
    })
    fireEvent.change(screen.getByLabelText("Pre-order cutoff (date & time)"), {
      target: { value: "2026-10-23T18:00" },
    })
    fireEvent.change(screen.getByLabelText("Max orders (optional)"), {
      target: { value: "30" },
    })
    fireEvent.click(screen.getByTestId("create-slot-submit-btn"))

    await waitFor(() => {
      expect(mockPost).toHaveBeenCalledWith(
        "/api/v1/shops/shop-peckham/slots",
        expect.objectContaining({
          serviceDate: "2026-10-24",
          windowStart: "14:00",
          windowEnd: "17:00",
          maxOrders: 30,
        })
      )
    })

    // Deactivate the first slot
    fireEvent.click(screen.getByTestId("slot-deactivate-btn-slot-fri"))
    await waitFor(() => {
      expect(mockDelete).toHaveBeenCalledWith("/api/v1/shops/shop-peckham/slots/slot-fri")
    })
  })
})
