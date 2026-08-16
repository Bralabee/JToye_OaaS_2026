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
import fs from "node:fs"
import path from "node:path"
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

/**
 * The `.env.example` declarations, asserted on their RESOLVED VALUE.
 *
 * WHY PRESENCE IS THE WRONG QUESTION. `VAR=  # explain yourself here` is a line
 * that LOOKS unset and IS NOT: an unquoted dotenv value runs to end-of-line, so
 * both `set -a; source` and `docker compose --env-file` resolve that variable to
 * the literal string "# explain yourself here". Every is-configured guard in the
 * repo then reads TRUE, the value gets baked into the browser bundle, and a
 * privacy notice publishes a comment as a postal address. A grep for the
 * variable name passes on exactly that line, which is why this parses instead.
 *
 * The parser deliberately implements the PERMISSIVE reading — everything after
 * the first `=` is the value, no inline-comment stripping — because that is the
 * reading the tools which actually consume this file use, and a guard should
 * model the consumer that can hurt you rather than the most forgiving one.
 */
function readEnvExample(): string {
  // frontend/lib/__tests__ -> repo root
  return fs.readFileSync(
    path.resolve(__dirname, "../../../.env.example"),
    "utf8"
  )
}

/** Raw right-hand side of an assignment, or null when the key is absent. */
function rawEnvValue(contents: string, key: string): string | null {
  for (const line of contents.split("\n")) {
    if (line.startsWith(`${key}=`)) return line.slice(key.length + 1)
  }
  return null
}

describe(".env.example declares the controller contact by VALUE, not by presence", () => {
  it("finds both keys at all — the control for every assertion below", () => {
    const contents = readEnvExample()
    // Without this, a typo'd key name makes every "is not a comment" assertion
    // below trivially true against a null, and the suite goes green over a file
    // that declares nothing.
    expect(rawEnvValue(contents, "NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE")).not.toBeNull()
    expect(rawEnvValue(contents, "NEXT_PUBLIC_DATA_PROTECTION_EMAIL")).not.toBeNull()
  })

  it("resolves the data-protection contact to a real address", () => {
    const raw = rawEnvValue(readEnvExample(), "NEXT_PUBLIC_DATA_PROTECTION_EMAIL")
    expect(raw).toBe("privacy@olajay.co.uk")
    // Belt and braces on the trap specifically: no `#` anywhere in the resolved
    // value, so it cannot be a comment that a laxer parser would have stripped.
    expect(raw).not.toContain("#")
    expect(raw).toMatch(/^[^\s@]+@[^\s@]+\.[^\s@]+$/)
  })

  it("resolves the registered office to genuinely EMPTY, not to a comment", () => {
    const raw = rawEnvValue(readEnvExample(), "NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE")
    // The owner declined to publish an address. "Declined" must mean the empty
    // string — an explanatory comment parked on the assignment line would make
    // this variable CONFIGURED, and the notice would publish that comment.
    expect(raw).toBe("")
  })

  it("BREAK DIRECTION: the parser catches the comment-as-value shape", () => {
    // The assertions above are all expected-empty or expected-exact, which is
    // the shape that can silently be incapable of failing. Prove the instrument
    // fires against the defect it exists for, in-suite, on every future run.
    const broken = "NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE=  # fill this in later\n"
    const raw = rawEnvValue(broken, "NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE")

    expect(raw).not.toBe("")
    expect(raw).toContain("#")
    // And the consequence, stated as an assertion rather than as prose: a
    // truthiness guard reads this as configured.
    expect(Boolean(raw)).toBe(true)
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
