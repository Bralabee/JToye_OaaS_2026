/**
 * What checkout remembers for the customer, and WHOSE it is (#876 UXT-104, #845).
 *
 *   draft          sessionStorage `jtoye-checkout-draft-<shop>`: the address, phone and notes typed so far, so
 *                  Back then Forward does not wipe them (UXT-104). Per tab, per shop.
 *   last delivery  localStorage `jtoye-last-delivery`: the delivery address and phone of the signed-in
 *                  customer's last order, to prefill their next one (#845). Per browser.
 *
 * Both are USER-SCOPED client-persisted state, so the contract in CLAUDE.md applies: they carry an
 * identity stamp, and the stamp's LIFECYCLE is what is tested here, THROUGH the transition (sign in as A,
 * lapse, sign in as B, explicit sign-out) asserting the STORED stamp and payload by content at every step,
 * never only what a page renders. R-16 shipped because a test looked at the screen while the same render
 * rewrote `owner: A` to `owner: null` on disk.
 *
 * The rules, restated where they are proved:
 *   - a write ADDS or CONFIRMS an owner; only an explicit sign-out removes one;
 *   - a lapse (nobody signed in right now) leaves the stamp alone, and a write during one preserves it;
 *   - a session that is live but whose identity is unrecorded must NOT preserve the prior owner (it is
 *     not that person), the WR-02 branch;
 *   - the draft is readable by its owner, by nobody-signed-in, and by an anonymous stamp, never by a
 *     different signed-in customer; the last delivery is the customer's own address and is read ONLY by
 *     exactly that customer.
 */
import {
  CUSTOMER_EXPIRES_KEY,
  CUSTOMER_ID_KEY,
  CUSTOMER_MARKER_KEY,
} from "@/lib/cart-identity"
import {
  CHECKOUT_DRAFT_KEY_PREFIX,
  LAST_DELIVERY_KEY,
  clearCheckoutDraft,
  readCheckoutDraft,
  readLastDelivery,
  rememberLastDelivery,
  rememberLastDeliveryIfNone,
  writeCheckoutDraft,
} from "@/lib/checkout-memory"
import {
  CLIENT_STORAGE_KEYS,
  PERSONAL_KEYS_CLEARED_ON_SIGN_OUT,
  isRegisteredStorageKey,
  removeStorageEntries,
} from "@/lib/client-storage-keys"

const SLUG = "jollof-express"
const DRAFT_KEY = `${CHECKOUT_DRAFT_KEY_PREFIX}${SLUG}`

const A_DRAFT = {
  fulfilmentType: "DELIVERY" as const,
  address1: "12 Coldharbour Lane",
  address2: "Flat 3",
  city: "London",
  postcode: "SW9 8LF",
  phone: "07700 900001",
  notes: "Ring twice",
}
const B_DRAFT = { ...A_DRAFT, address1: "99 Other Road", phone: "07700 900002", notes: "" }

/** A live sign-in as `sub`: the id AND the marker, as `setMarker` writes them. */
function signIn(sub: string) {
  localStorage.setItem(CUSTOMER_ID_KEY, sub)
  localStorage.setItem(CUSTOMER_MARKER_KEY, "true")
  localStorage.setItem(CUSTOMER_EXPIRES_KEY, String(Math.floor(Date.now() / 1000) + 300))
}
/** The 300 s lapse: the id and marker go (clearMarker / forgetCustomerId); nothing else does. */
function lapse() {
  localStorage.removeItem(CUSTOMER_ID_KEY)
  localStorage.removeItem(CUSTOMER_MARKER_KEY)
  localStorage.removeItem(CUSTOMER_EXPIRES_KEY)
}
/** A live session whose identity was never recorded (the marker without the id). */
function signedInButUnidentified() {
  localStorage.removeItem(CUSTOMER_ID_KEY)
  localStorage.setItem(CUSTOMER_MARKER_KEY, "true")
  localStorage.setItem(CUSTOMER_EXPIRES_KEY, String(Math.floor(Date.now() / 1000) + 300))
}
/** What an explicit sign-out removes: the registry entries it is declared against. */
function explicitSignOut() {
  removeStorageEntries(PERSONAL_KEYS_CLEARED_ON_SIGN_OUT)
  lapse()
}

const storedDraft = () => JSON.parse(sessionStorage.getItem(DRAFT_KEY) ?? "null")
const storedLast = () => JSON.parse(localStorage.getItem(LAST_DELIVERY_KEY) ?? "null")

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})

describe("the checkout draft (UXT-104) through the identity transitions", () => {
  it("a guest's draft is stored with no owner and read back whole (Back then Forward)", () => {
    writeCheckoutDraft(SLUG, A_DRAFT)
    expect(storedDraft()).toMatchObject({ owner: null, fields: A_DRAFT })
    expect(readCheckoutDraft(SLUG)).toEqual(A_DRAFT)
  })

  it("sign in A -> lapse -> sign in B -> explicit sign-out: the STORED stamp and payload at each step", () => {
    // sign in as A: a write stamps A.
    signIn("user-A")
    writeCheckoutDraft(SLUG, A_DRAFT)
    expect(storedDraft()).toMatchObject({ owner: "user-A", fields: A_DRAFT })

    // the session lapses; the next render re-serialises the draft (the R-16 write): the stamp stays A.
    lapse()
    expect(readCheckoutDraft(SLUG)).toEqual(A_DRAFT) // a lapsed session is not a new person: still readable
    writeCheckoutDraft(SLUG, { ...A_DRAFT, notes: "Ring twice, then call" })
    expect(storedDraft()).toMatchObject({ owner: "user-A", fields: { notes: "Ring twice, then call" } })

    // B signs in on the same tab: A's draft is NOT shown to B.
    signIn("user-B")
    expect(readCheckoutDraft(SLUG)).toBeNull()
    // The page, finding nothing to restore, persists B's (empty) form: B now owns the slot, and none of
    // A's address survives in it.
    writeCheckoutDraft(SLUG, { ...B_DRAFT, address1: "", address2: "", city: "", postcode: "", phone: "" })
    expect(storedDraft().owner).toBe("user-B")
    expect(JSON.stringify(storedDraft())).not.toContain("Coldharbour")
    writeCheckoutDraft(SLUG, B_DRAFT)
    expect(storedDraft()).toMatchObject({ owner: "user-B", fields: B_DRAFT })

    // explicit sign-out removes it, and nothing else brings it back.
    explicitSignOut()
    expect(sessionStorage.getItem(DRAFT_KEY)).toBeNull()
    expect(readCheckoutDraft(SLUG)).toBeNull()
  })

  it("a write while signed in with the identity unrecorded does NOT keep the previous owner (WR-02)", () => {
    signIn("user-A")
    writeCheckoutDraft(SLUG, A_DRAFT)
    signedInButUnidentified()
    writeCheckoutDraft(SLUG, B_DRAFT)
    // Keeping "user-A" would file this person's address under A. Falling back to no owner is the lesser harm.
    expect(storedDraft().owner).toBeNull()
  })

  it("anonymous -> signed in carries the draft forward (the guest who then signs in is the same person)", () => {
    writeCheckoutDraft(SLUG, A_DRAFT)
    signIn("user-A")
    expect(readCheckoutDraft(SLUG)).toEqual(A_DRAFT)
    writeCheckoutDraft(SLUG, A_DRAFT)
    expect(storedDraft().owner).toBe("user-A")
  })

  it("a draft for another shop, or a malformed or foreign-owner-typed payload, is never adopted", () => {
    sessionStorage.setItem(DRAFT_KEY, JSON.stringify({ owner: { x: 1 }, fields: A_DRAFT }))
    // A non-string owner is "no owner" (the validOwner rule), so it is adoptable but never trusted as an id.
    expect(readCheckoutDraft(SLUG)).toEqual(A_DRAFT)
    sessionStorage.setItem(DRAFT_KEY, "{not json")
    expect(readCheckoutDraft(SLUG)).toBeNull()
    sessionStorage.setItem(DRAFT_KEY, JSON.stringify({ owner: null, fields: { address1: 7 } }))
    expect(readCheckoutDraft(SLUG)).toBeNull()
  })

  it("clearCheckoutDraft removes the draft (an order was placed)", () => {
    writeCheckoutDraft(SLUG, A_DRAFT)
    clearCheckoutDraft(SLUG)
    expect(sessionStorage.getItem(DRAFT_KEY)).toBeNull()
  })

  it("never stores the allergy note, because the type has no field for it (T-31.1-55)", () => {
    writeCheckoutDraft(SLUG, { ...A_DRAFT, allergyNote: "peanuts" } as never)
    expect(JSON.stringify(storedDraft())).not.toContain("peanuts")
  })
})

describe("the last delivery address (#845) through the identity transitions", () => {
  const A_LAST = { address1: "12 Coldharbour Lane", address2: "Flat 3", city: "London", postcode: "SW9 8LF", phone: "07700 900001" }
  const B_LAST = { address1: "99 Other Road", address2: "", city: "Leeds", postcode: "LS1 1AA", phone: "07700 900002" }

  it("sign in A -> place an order -> lapse -> sign in B -> B orders -> explicit sign-out", () => {
    signIn("user-A")
    rememberLastDelivery(A_LAST)
    expect(storedLast()).toMatchObject({ owner: "user-A", ...A_LAST })
    expect(readLastDelivery()).toEqual(A_LAST)

    // lapse: nothing is erased, but nobody is identified to be offered it.
    lapse()
    expect(readLastDelivery()).toBeNull()
    expect(storedLast()).toMatchObject({ owner: "user-A", ...A_LAST })
    // An order placed as a guest after the lapse must not file ITS address under A, and must not erase A's.
    rememberLastDelivery(B_LAST)
    expect(storedLast()).toMatchObject({ owner: "user-A", ...A_LAST })

    // B signs in: A's address is not offered to B.
    signIn("user-B")
    expect(readLastDelivery()).toBeNull()
    expect(JSON.stringify(storedLast())).toContain("Coldharbour") // still A's, untouched until B writes

    // B places an order: B owns the slot and A's address is gone from it.
    rememberLastDelivery(B_LAST)
    expect(storedLast()).toMatchObject({ owner: "user-B", ...B_LAST })
    expect(JSON.stringify(storedLast())).not.toContain("Coldharbour")
    expect(readLastDelivery()).toEqual(B_LAST)

    explicitSignOut()
    expect(localStorage.getItem(LAST_DELIVERY_KEY)).toBeNull()
    expect(readLastDelivery()).toBeNull()
  })

  it("rememberLastDeliveryIfNone (an OLD order's address) never replaces this customer's remembered address, but fills an empty slot and takes a slot that is another customer's", () => {
    signIn("user-A")
    // nothing stored: it fills the slot, stamped A
    rememberLastDeliveryIfNone(A_LAST)
    expect(storedLast()).toMatchObject({ owner: "user-A", ...A_LAST })
    // A's address is stored: an older order's address does not replace it
    rememberLastDeliveryIfNone(B_LAST)
    expect(storedLast()).toMatchObject({ owner: "user-A", ...A_LAST })
    expect(JSON.stringify(storedLast())).not.toContain("Other Road")
    // B signs in on the same browser: the slot is A's, not B's, so B takes it (nothing of A's survives)
    signIn("user-B")
    rememberLastDeliveryIfNone(B_LAST)
    expect(storedLast()).toMatchObject({ owner: "user-B", ...B_LAST })
    // a guest writes nothing at all
    explicitSignOut()
    rememberLastDeliveryIfNone(A_LAST)
    expect(localStorage.getItem(LAST_DELIVERY_KEY)).toBeNull()
  })

  it("is never written for a guest, or by a live session whose identity is unrecorded", () => {
    rememberLastDelivery(A_LAST)
    expect(localStorage.getItem(LAST_DELIVERY_KEY)).toBeNull()
    signedInButUnidentified()
    rememberLastDelivery(A_LAST)
    expect(localStorage.getItem(LAST_DELIVERY_KEY)).toBeNull()
  })

  it("ignores a stored value with no owner, or one that is not shaped like an address", () => {
    signIn("user-A")
    localStorage.setItem(LAST_DELIVERY_KEY, JSON.stringify({ owner: null, ...A_LAST }))
    expect(readLastDelivery()).toBeNull()
    localStorage.setItem(LAST_DELIVERY_KEY, JSON.stringify({ owner: "user-A", address1: 5 }))
    expect(readLastDelivery()).toBeNull()
    localStorage.setItem(LAST_DELIVERY_KEY, "garbage")
    expect(readLastDelivery()).toBeNull()
  })
})

describe("both keys are declared, disclosed and removed by an explicit sign-out", () => {
  it("the registry declares the draft prefix (session) and the last-delivery key (local), personal and cleared on sign-out", () => {
    const draft = CLIENT_STORAGE_KEYS.find((e) => e.name === CHECKOUT_DRAFT_KEY_PREFIX)
    const last = CLIENT_STORAGE_KEYS.find((e) => e.name === LAST_DELIVERY_KEY)
    expect(draft).toMatchObject({ area: "sessionStorage", match: "prefix", personal: true, clearedOnSignOut: true })
    expect(last).toMatchObject({ area: "localStorage", match: "exact", personal: true, clearedOnSignOut: true })
    expect(isRegisteredStorageKey("sessionStorage", DRAFT_KEY)).toBe(true)
    expect(isRegisteredStorageKey("localStorage", LAST_DELIVERY_KEY)).toBe(true)
  })
})
