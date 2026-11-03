import { hasPendingLogin } from "@/lib/customer-auth"

/**
 * SW-JRD-05 / SW-SAM-13: the callback page asks whether THIS tab started the
 * sign-in before it tries an exchange. The answer is the PKCE verifier in
 * sessionStorage, which is per tab, so an email link opened in a new tab must
 * read false and the tab that started the sign-in must read true.
 */
describe("hasPendingLogin", () => {
  beforeEach(() => sessionStorage.clear())

  it("is false in a tab that never started a sign-in", () => {
    expect(hasPendingLogin()).toBe(false)
  })

  it("is true once the PKCE verifier is stored", () => {
    sessionStorage.setItem("jtoye-pkce-verifier", "verifier-abc")
    expect(hasPendingLogin()).toBe(true)
  })

  it("is false, not a throw, when sessionStorage is unavailable", () => {
    const spy = jest.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("SecurityError")
    })
    try {
      expect(hasPendingLogin()).toBe(false)
    } finally {
      spy.mockRestore()
    }
  })
})
