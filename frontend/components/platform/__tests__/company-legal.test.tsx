import { render } from "@testing-library/react"
import { CompanyLegalLine } from "@/components/platform/company-legal"
import { getCompanyInfo } from "@/lib/company"

describe("CompanyLegalLine", () => {
  it("renders the platform operator registration with the verified company number", () => {
    const { container } = render(<CompanyLegalLine />)
    const text = container.textContent ?? ""
    expect(text).toContain("J'Toye Digital Ltd")
    expect(text).toContain("registered in England & Wales")
    expect(text).toContain("company no. 16471464")
    // de-orphans /legal (link-graph guard) + gives an in-app path to the full page
    expect(container.querySelector('a[href="/legal"]')).not.toBeNull()
  })

  it("omits the registered office when it is not configured", () => {
    const { container } = render(<CompanyLegalLine />)
    expect(container.textContent ?? "").not.toContain("Registered office:")
  })
})

describe("getCompanyInfo", () => {
  it("defaults to the verified J'Toye Digital Ltd identity", () => {
    const c = getCompanyInfo()
    expect(c.legalName).toBe("J'Toye Digital Ltd")
    expect(c.companyNumber).toBe("16471464")
    expect(c.registrationJurisdiction).toBe("England & Wales")
    expect(c.registeredOffice).toBe("")
  })
})
