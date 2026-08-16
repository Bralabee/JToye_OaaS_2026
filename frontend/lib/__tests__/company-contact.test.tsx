/**
 * Controller contact fallback, BOTH directions (LGL-01, phase 31).
 *
 * WHICH DIRECTION MATTERS. The configured arm is the easy one and it is not the
 * reason this file exists. The UNSET arm is: UK GDPR Art. 13(1)(a)-(b) requires
 * the controller's identity and contact details in a privacy notice, and the
 * failure mode this repo is actually exposed to is a page that renders the term
 * "Registered office" with nothing after it. That single defect is both a broken
 * page and an Art. 13 failure at once — and because it LOOKS like a rendering
 * bug it gets triaged as one, so the legal half never gets fixed.
 *
 * WHY THE UNSET ARM IS NOT VACUOUS TODAY. On the tree as it stands,
 * NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE is supplied by nothing, so the unset arm
 * is the ONLY arm reachable from a plain `render()`. Asserting only that would
 * be a check that has never been observed doing anything: it would pass just as
 * happily against a resolver that returned null unconditionally. So the
 * configured arm is driven by writing the variable into process.env before the
 * call, which is also the assertion that proves getCompanyInfo() reads the
 * environment at CALL time rather than at module load — the property the whole
 * build-arg design depends on.
 *
 * The empty-label assertion is deliberately written as "the term does not appear
 * anywhere in the rendered text", not as "the <dd> is empty". A <dd> that is
 * missing and a <dd> that is present-but-blank produce the same empty string
 * from a naive query, so querying the value cannot tell the two apart; querying
 * the TERM can.
 */
import { render, screen } from "@testing-library/react"
import "@testing-library/jest-dom"
import {
  getCompanyInfo,
  resolveControllerContact,
  type ControllerContact,
} from "@/lib/company"

const OFFICE_VAR = "NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE"
const DPO_VAR = "NEXT_PUBLIC_DATA_PROTECTION_EMAIL"

const SAMPLE_OFFICE = "Unit 4, 12 Example Street, Birmingham B1 1AA"
const SAMPLE_EMAIL = "privacy@example.test"

const savedOffice = process.env[OFFICE_VAR]
const savedDpo = process.env[DPO_VAR]

afterEach(() => {
  if (savedOffice === undefined) delete process.env[OFFICE_VAR]
  else process.env[OFFICE_VAR] = savedOffice
  if (savedDpo === undefined) delete process.env[DPO_VAR]
  else process.env[DPO_VAR] = savedDpo
})

/**
 * The consumer under test, written exactly the way a policy page must consume
 * the resolver: the heading is INSIDE the `anyRoute` guard, so an unconfigured
 * deployment drops the term along with the value instead of orphaning it.
 */
function ContactBlock({ contact }: { contact: ControllerContact }) {
  if (!contact.anyRoute) {
    return <p>Contact us through the routes listed on this page.</p>
  }
  return (
    <dl>
      {contact.postal ? (
        <div>
          <dt>Registered office</dt>
          <dd>{contact.postal}</dd>
        </div>
      ) : null}
      {contact.emailHref ? (
        <div>
          <dt>Data protection contact</dt>
          <dd>
            <a href={contact.emailHref}>{contact.email}</a>
          </dd>
        </div>
      ) : null}
    </dl>
  )
}

describe("resolveControllerContact — UNSET (the arm that matters)", () => {
  it("returns no routes when neither value is configured", () => {
    delete process.env[OFFICE_VAR]
    delete process.env[DPO_VAR]

    const contact = resolveControllerContact(getCompanyInfo())
    expect(contact.postal).toBeNull()
    expect(contact.email).toBeNull()
    expect(contact.emailHref).toBeNull()
    expect(contact.anyRoute).toBe(false)
  })

  it("treats a whitespace-only value as unconfigured", () => {
    // A build arg passed as `--build-arg VAR=" "` is the shape that defeats a
    // bare truthiness check: non-empty, so `||` keeps it, and it renders as a
    // blank line under a real heading. Trimming is what makes the guard honest.
    process.env[OFFICE_VAR] = "   "
    process.env[DPO_VAR] = "\t"

    const contact = resolveControllerContact(getCompanyInfo())
    expect(contact.anyRoute).toBe(false)
  })

  it("emits NO empty label — the term is absent, not merely valueless", () => {
    delete process.env[OFFICE_VAR]
    delete process.env[DPO_VAR]

    const { container } = render(
      <ContactBlock contact={resolveControllerContact(getCompanyInfo())} />
    )

    // NON-VACUITY CONTROL, asserted first: the component really rendered, so the
    // absences below are observations about the markup and not about a tree that
    // never mounted. Without this, "no Registered office term" and "nothing
    // rendered at all" are the same measurement.
    expect(
      screen.getByText(/contact us through the routes listed on this page/i)
    ).toBeInTheDocument()

    expect(container.textContent).not.toContain("Registered office")
    expect(container.textContent).not.toContain("Data protection contact")
    // No orphaned container either: an empty <dl> is still a labelled region to
    // a screen reader, announced with nothing inside it.
    expect(container.querySelector("dl")).toBeNull()
    expect(container.querySelectorAll("dt")).toHaveLength(0)
  })
})

describe("resolveControllerContact — CONFIGURED", () => {
  it("reads both values from the environment at call time", () => {
    process.env[OFFICE_VAR] = SAMPLE_OFFICE
    process.env[DPO_VAR] = SAMPLE_EMAIL

    const info = getCompanyInfo()
    expect(info.registeredOffice).toBe(SAMPLE_OFFICE)
    expect(info.dataProtectionEmail).toBe(SAMPLE_EMAIL)

    const contact = resolveControllerContact(info)
    expect(contact.postal).toBe(SAMPLE_OFFICE)
    expect(contact.email).toBe(SAMPLE_EMAIL)
    expect(contact.emailHref).toBe(`mailto:${SAMPLE_EMAIL}`)
    expect(contact.anyRoute).toBe(true)
  })

  it("renders the block with a term AND a value for each configured route", () => {
    process.env[OFFICE_VAR] = SAMPLE_OFFICE
    process.env[DPO_VAR] = SAMPLE_EMAIL

    const { container } = render(
      <ContactBlock contact={resolveControllerContact(getCompanyInfo())} />
    )

    expect(screen.getByText("Registered office")).toBeInTheDocument()
    expect(screen.getByText(SAMPLE_OFFICE)).toBeInTheDocument()
    expect(screen.getByText("Data protection contact")).toBeInTheDocument()
    expect(
      container.querySelector(`a[href="mailto:${SAMPLE_EMAIL}"]`)
    ).not.toBeNull()

    // Every term has a non-empty value. This is the assertion that would catch a
    // regression where the guard moved off the term and onto the value only.
    const terms = Array.from(container.querySelectorAll("dt"))
    expect(terms).toHaveLength(2)
    for (const dt of terms) {
      const dd = dt.nextElementSibling
      expect(dd?.tagName).toBe("DD")
      expect((dd?.textContent ?? "").trim().length).toBeGreaterThan(0)
    }
  })

  it("publishes one route when only one is configured", () => {
    // The realistic partial state: an address is declined as residential but a
    // data-protection inbox exists. The notice must still carry the route that
    // does exist rather than falling back to "nothing configured".
    delete process.env[OFFICE_VAR]
    process.env[DPO_VAR] = SAMPLE_EMAIL

    const { container } = render(
      <ContactBlock contact={resolveControllerContact(getCompanyInfo())} />
    )

    expect(screen.getByText("Data protection contact")).toBeInTheDocument()
    expect(container.textContent).not.toContain("Registered office")
    expect(container.querySelectorAll("dt")).toHaveLength(1)
  })
})

describe("company identity is not silently displaced by the new fields", () => {
  it("keeps the ACTIVE company number and never the dissolved namesake", () => {
    const info = getCompanyInfo()
    expect(info.companyNumber).toBe("16471464")
    // Asserting the ACTIVE number is PRESENT is measurably vacuous here: with the
    // dissolved number substituted, a presence check still passes because the
    // correct number appears elsewhere. Absence is the assertion that can fail.
    expect(JSON.stringify(info)).not.toContain("13434105")
  })
})
