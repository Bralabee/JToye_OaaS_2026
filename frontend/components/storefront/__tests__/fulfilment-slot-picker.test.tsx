import { render, screen, fireEvent } from "@testing-library/react"
import {
  FulfilmentSlotPicker,
  describeSlotCheckoutError,
  isCheckoutBlockedByAvailability,
  SLOT_REQUIRED_PROBLEM_TYPE,
  SLOT_CLOSED_PROBLEM_TYPE,
  SLOT_CUTOFF_PASSED_PROBLEM_TYPE,
  SLOT_FULL_PROBLEM_TYPE,
} from "../fulfilment-slot-picker"
import {
  buildGuestOrderIntent,
  guestOrderIntentSignature,
  type GuestOrderFields,
} from "@/lib/checkout-idempotency"
import type { FulfilmentSlotDto } from "@/types/api"

const sampleSlots: FulfilmentSlotDto[] = [
  {
    id: "slot-open",
    shopId: "shop-1",
    serviceDate: "2026-10-23",
    windowStart: "17:00",
    windowEnd: "19:00",
    windowLabel: "Friday 17:00 - 19:00",
    cutoffAt: "2026-10-22T18:00:00Z",
    maxOrders: 20,
    maxUnits: null,
    reservedOrders: 8,
    reservedUnits: 0,
    remainingOrders: 12,
    remainingUnits: null,
    active: true,
  },
  {
    id: "slot-low",
    shopId: "shop-1",
    serviceDate: "2026-10-24",
    windowStart: "12:00",
    windowEnd: "15:00",
    windowLabel: "Saturday 12:00 - 15:00",
    cutoffAt: "2026-10-23T18:00:00Z",
    maxOrders: 20,
    maxUnits: null,
    reservedOrders: 17,
    reservedUnits: 0,
    remainingOrders: 3,
    remainingUnits: null,
    active: true,
  },
  {
    id: "slot-soldout",
    shopId: "shop-1",
    serviceDate: "2026-10-25",
    windowStart: "13:00",
    windowEnd: "16:00",
    windowLabel: "Sunday 13:00 - 16:00",
    cutoffAt: "2026-10-24T18:00:00Z",
    maxOrders: 15,
    maxUnits: null,
    reservedOrders: 15,
    reservedUnits: 0,
    remainingOrders: 0,
    remainingUnits: null,
    active: true,
  },
]

describe("FulfilmentSlotPicker (GAP-GTM-03 / V87)", () => {
  it("renders nothing when timingMode is IMMEDIATE_ONLY or undefined", () => {
    const { container: c1 } = render(
      <FulfilmentSlotPicker
        timingMode="IMMEDIATE_ONLY"
        slots={sampleSlots}
        loading={false}
        error={null}
        selectedSlotId={null}
        onSelectSlot={jest.fn()}
      />
    )
    expect(c1).toBeEmptyDOMElement()

    const { container: c2 } = render(
      <FulfilmentSlotPicker
        timingMode={undefined}
        slots={sampleSlots}
        loading={false}
        error={null}
        selectedSlotId={null}
        onSelectSlot={jest.fn()}
      />
    )
    expect(c2).toBeEmptyDOMElement()
  })

  it("renders Required badge, slot options, remaining capacity pills, and disables sold-out slots in SCHEDULED_SLOTS_ONLY mode", () => {
    const onSelectSlot = jest.fn()
    render(
      <FulfilmentSlotPicker
        timingMode="SCHEDULED_SLOTS_ONLY"
        slots={sampleSlots}
        loading={false}
        error={null}
        selectedSlotId="slot-open"
        onSelectSlot={onSelectSlot}
      />
    )

    expect(screen.getByTestId("slot-picker-required-badge")).toHaveTextContent("Required")
    expect(screen.queryByTestId("fulfilment-slot-immediate")).not.toBeInTheDocument()

    // Capacity pills
    expect(screen.getByTestId("slot-capacity-slot-open")).toHaveTextContent("12 of 20 available")
    expect(screen.getByTestId("slot-capacity-slot-low")).toHaveTextContent("Only 3 left")
    expect(screen.getByTestId("slot-capacity-slot-soldout")).toHaveTextContent("Sold out")

    // Sold-out slot is disabled and does not invoke callback
    const soldOutBtn = screen.getByTestId("fulfilment-slot-option-slot-soldout")
    expect(soldOutBtn).toBeDisabled()
    fireEvent.click(soldOutBtn)
    expect(onSelectSlot).not.toHaveBeenCalled()

    // Low-capacity open slot is clickable
    fireEvent.click(screen.getByTestId("fulfilment-slot-option-slot-low"))
    expect(onSelectSlot).toHaveBeenCalledWith("slot-low")
  })

  it("offers 'Order for now (ASAP)' option alongside scheduled slots in HYBRID_IMMEDIATE_AND_SLOTS mode", () => {
    const onSelectSlot = jest.fn()
    render(
      <FulfilmentSlotPicker
        timingMode="HYBRID_IMMEDIATE_AND_SLOTS"
        slots={sampleSlots}
        loading={false}
        error={null}
        selectedSlotId="slot-open"
        onSelectSlot={onSelectSlot}
      />
    )

    expect(screen.queryByTestId("slot-picker-required-badge")).not.toBeInTheDocument()
    const asapBtn = screen.getByTestId("fulfilment-slot-immediate")
    expect(asapBtn).toBeInTheDocument()

    fireEvent.click(asapBtn)
    expect(onSelectSlot).toHaveBeenCalledWith(null)
  })

  it("maps V87 RFC 9457 slot problem types via describeSlotCheckoutError", () => {
    expect(describeSlotCheckoutError({ type: SLOT_REQUIRED_PROBLEM_TYPE })).toMatch(
      /requires a scheduled fulfilment slot/i
    )
    expect(describeSlotCheckoutError({ type: SLOT_CLOSED_PROBLEM_TYPE })).toMatch(
      /window is now closed/i
    )
    expect(describeSlotCheckoutError({ type: SLOT_CUTOFF_PASSED_PROBLEM_TYPE })).toMatch(
      /cutoff for that fulfilment window has passed/i
    )
    expect(describeSlotCheckoutError({ type: SLOT_FULL_PROBLEM_TYPE })).toMatch(
      /window just sold out/i
    )
    expect(describeSlotCheckoutError({ type: "https://jtoye.uk/errors/other" })).toBeNull()
  })

  it("permits scheduled slot pre-orders when shop is closed (openNow false) while blocking immediate ASAP orders and paused shops", () => {
    // Paused always blocks regardless of timingMode or slot selection
    expect(
      isCheckoutBlockedByAvailability({
        availabilityKind: "paused",
        timingMode: "SCHEDULED_SLOTS_ONLY",
        selectedSlotId: "slot-open",
      })
    ).toBe(true)

    // Closed blocks IMMEDIATE_ONLY and HYBRID without a selected slot
    expect(
      isCheckoutBlockedByAvailability({
        availabilityKind: "closed",
        timingMode: "IMMEDIATE_ONLY",
        selectedSlotId: null,
      })
    ).toBe(true)
    expect(
      isCheckoutBlockedByAvailability({
        availabilityKind: "closed",
        timingMode: "HYBRID_IMMEDIATE_AND_SLOTS",
        selectedSlotId: null,
      })
    ).toBe(true)

    // Closed permits SCHEDULED_SLOTS_ONLY and HYBRID with a selected slot
    expect(
      isCheckoutBlockedByAvailability({
        availabilityKind: "closed",
        timingMode: "SCHEDULED_SLOTS_ONLY",
        selectedSlotId: "slot-open",
      })
    ).toBe(false)
    expect(
      isCheckoutBlockedByAvailability({
        availabilityKind: "closed",
        timingMode: "HYBRID_IMMEDIATE_AND_SLOTS",
        selectedSlotId: "slot-open",
      })
    ).toBe(false)

    // Open or unknown availability never blocks
    expect(
      isCheckoutBlockedByAvailability({
        availabilityKind: "open",
        timingMode: "IMMEDIATE_ONLY",
        selectedSlotId: null,
      })
    ).toBe(false)
  })
})

describe("checkout-idempotency with fulfilmentSlotId (V87)", () => {
  const baseFields: GuestOrderFields = {
    customerName: "Adaeze Okafor",
    customerEmail: "adaeze@example.com",
    customerPhone: "07700900123",
    notes: "",
    fulfilmentType: "COLLECTION",
    address1: "",
    address2: "",
    city: "",
    postcode: "",
    items: [{ productId: "prod-1", quantity: 2, expectedUnitPricePennies: 1200 }],
    acknowledgedAllergenMask: 0,
    expectedTotalPennies: 2400,
  }

  it("omits fulfilmentSlotId when null or blank so immediate orders fingerprint identically", () => {
    const withoutField = buildGuestOrderIntent(baseFields)
    const withNull = buildGuestOrderIntent({ ...baseFields, fulfilmentSlotId: null })
    const withBlank = buildGuestOrderIntent({ ...baseFields, fulfilmentSlotId: "   " })

    expect(withoutField.fulfilmentSlotId).toBeUndefined()
    expect(guestOrderIntentSignature(withNull)).toBe(guestOrderIntentSignature(withoutField))
    expect(guestOrderIntentSignature(withBlank)).toBe(guestOrderIntentSignature(withoutField))
  })

  it("includes trimmed fulfilmentSlotId in the intent and changes signature when slot changes", () => {
    const slotA = buildGuestOrderIntent({ ...baseFields, fulfilmentSlotId: " slot-fri " })
    const slotB = buildGuestOrderIntent({ ...baseFields, fulfilmentSlotId: "slot-sat" })

    expect(slotA.fulfilmentSlotId).toBe("slot-fri")
    expect(guestOrderIntentSignature(slotA)).not.toBe(guestOrderIntentSignature(slotB))
  })
})
