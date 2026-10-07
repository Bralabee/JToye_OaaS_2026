/**
 * #789 (31.1-10): the vendor declares the legal entity customers buy from.
 *
 * What is asserted, and why each can fail:
 * - the six fields render with labels, and REQUIRED is programmatic (aria-required), not a
 *   visual asterisk only; optional fields carry no aria-required (a form that stamped it on
 *   everything would fail the optional arm);
 * - an existing identity (GET 200) loads INTO the inputs, by value;
 * - GET 404 is "nothing on file yet": an empty form and NO error message;
 * - a server field error (400 errors.addressPostcode) is rendered under that field in a
 *   role="alert", the input is aria-invalid with aria-describedby resolving to the message,
 *   and focus moves to it — the error state is induced first, because these attributes are
 *   conditional and absent on a clean form (the products-form A11Y-7 lesson);
 * - the client refuses a malformed postcode before any PUT;
 * - a successful PUT shows a saved confirmation and RE-READS the identity (a second GET);
 * - the onboarding page mounts the form.
 */
import { render, screen, waitFor, fireEvent } from "@testing-library/react"
import { TraderIdentityForm } from "../trader-identity-form"
import OnboardingPage from "@/app/dashboard/onboarding/page"
import apiClient from "@/lib/api-client"
import type { TraderIdentity } from "@/types/api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

const URL = "/api/v1/trader-identity"

const identity: TraderIdentity = {
  id: "ti-1",
  legalName: "Mama Ade Foods Ltd",
  entityType: "COMPANY",
  addressLine1: "12 Market Street",
  addressLine2: "Unit 4",
  addressCity: "Birmingham",
  addressPostcode: "B1 1AA",
  vatNumber: "GB123456789",
  companyNumber: "01234567",
  version: 0,
  updatedAt: "2026-10-06T12:00:00Z",
}

const notFound = { response: { status: 404, data: { type: "https://jtoye.uk/errors/not-found" } } }

function routeGet(impl: () => Promise<unknown>) {
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.startsWith(URL)) return impl() as Promise<never>
    return Promise.resolve({ data: {} }) as Promise<never>
  })
}

/**
 * Render and wait until the initial GET has settled: Save is disabled while the identity
 * loads (saving over values not yet shown would overwrite the stored identity), so a click
 * before that is ignored. Measured: two arms clicked during the load and saw nothing happen.
 */
async function renderLoaded() {
  render(<TraderIdentityForm />)
  await screen.findByRole("heading", { name: "Business details shown to customers" })
  await waitFor(() => expect(screen.getByRole("button", { name: /save business details/i })).toBeEnabled())
}

async function fillValidForm() {
  fireEvent.change(screen.getByLabelText(/^legal name/i), { target: { value: "Mama Ade Foods Ltd" } })
  fireEvent.change(screen.getByLabelText(/business type/i), { target: { value: "COMPANY" } })
  fireEvent.change(screen.getByLabelText(/^address line 1/i), { target: { value: "12 Market Street" } })
  fireEvent.change(screen.getByLabelText(/^town or city/i), { target: { value: "Birmingham" } })
  fireEvent.change(screen.getByLabelText(/^postcode/i), { target: { value: "B1 1AA" } })
}

describe("TraderIdentityForm (#789)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it("renders the heading, the explanation and the six fields; required is programmatic", async () => {
    routeGet(() => Promise.reject(notFound))
    render(<TraderIdentityForm />)

    expect(
      await screen.findByRole("heading", { name: "Business details shown to customers" })
    ).toBeInTheDocument()
    expect(screen.getByText(/customers see your legal name and address before they order/i)).toBeInTheDocument()

    for (const label of [/^legal name/i, /business type/i, /^address line 1/i, /^town or city/i, /^postcode/i]) {
      expect(screen.getByLabelText(label)).toHaveAttribute("aria-required", "true")
    }
    for (const label of [/^address line 2/i, /^vat number/i]) {
      expect(screen.getByLabelText(label)).not.toHaveAttribute("aria-required")
    }
    // Control: a clean form flags nothing invalid.
    expect(document.querySelectorAll('[aria-invalid="true"]')).toHaveLength(0)
  })

  it("loads an existing identity (GET 200) into the fields", async () => {
    routeGet(() => Promise.resolve({ data: identity }))
    render(<TraderIdentityForm />)

    await waitFor(() => expect(screen.getByLabelText(/^legal name/i)).toHaveValue("Mama Ade Foods Ltd"))
    expect(screen.getByLabelText(/business type/i)).toHaveValue("COMPANY")
    expect(screen.getByLabelText(/^address line 1/i)).toHaveValue("12 Market Street")
    expect(screen.getByLabelText(/^address line 2/i)).toHaveValue("Unit 4")
    expect(screen.getByLabelText(/^town or city/i)).toHaveValue("Birmingham")
    expect(screen.getByLabelText(/^postcode/i)).toHaveValue("B1 1AA")
    expect(screen.getByLabelText(/^vat number/i)).toHaveValue("GB123456789")
    expect(screen.getByText(/01234567/)).toBeInTheDocument()
  })

  it("GET 404 renders an empty form, not an error", async () => {
    routeGet(() => Promise.reject(notFound))
    render(<TraderIdentityForm />)

    await screen.findByRole("heading", { name: "Business details shown to customers" })
    await waitFor(() => expect(mockedApiClient.get).toHaveBeenCalledWith(URL))
    expect(screen.getByLabelText(/^legal name/i)).toHaveValue("")
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
    expect(screen.getByRole("button", { name: /save business details/i })).toBeEnabled()
  })

  it("renders the server's postcode error under the field: role=alert, aria-invalid, aria-describedby, focus", async () => {
    routeGet(() => Promise.reject(notFound))
    mockedApiClient.put.mockRejectedValue({
      response: {
        status: 400,
        data: {
          type: "https://jtoye.uk/errors/validation",
          errors: { addressPostcode: "addressPostcode must be a valid UK postcode, for example SW1A 1AA" },
        },
      },
    })
    await renderLoaded()

    const postcode = screen.getByLabelText(/^postcode/i)
    expect(postcode).not.toHaveAttribute("aria-invalid")
    await fillValidForm()
    fireEvent.click(screen.getByRole("button", { name: /save business details/i }))

    const message = await screen.findByText("addressPostcode must be a valid UK postcode, for example SW1A 1AA")
    expect(message).toHaveAttribute("role", "alert")
    expect(postcode).toHaveAttribute("aria-invalid", "true")
    const describedBy = postcode.getAttribute("aria-describedby")
    expect(describedBy).toBeTruthy()
    expect(document.getElementById(describedBy as string)).toHaveTextContent(/valid UK postcode/)
    await waitFor(() => expect(postcode).toHaveFocus())
    // Only the field the server named is flagged.
    expect(document.querySelectorAll('[aria-invalid="true"]')).toHaveLength(1)
  })

  it("refuses a malformed postcode in the browser before any PUT", async () => {
    routeGet(() => Promise.reject(notFound))
    await renderLoaded()

    await fillValidForm()
    fireEvent.change(screen.getByLabelText(/^postcode/i), { target: { value: "NOTAPOSTCODE" } })
    fireEvent.click(screen.getByRole("button", { name: /save business details/i }))

    expect(await screen.findByText(/enter a uk postcode/i)).toHaveAttribute("role", "alert")
    expect(screen.getByLabelText(/^postcode/i)).toHaveAttribute("aria-invalid", "true")
    expect(mockedApiClient.put).not.toHaveBeenCalled()
  })

  it("a successful PUT shows a saved confirmation and re-reads the identity", async () => {
    let calls = 0
    routeGet(() => {
      calls += 1
      return calls === 1 ? Promise.reject(notFound) : Promise.resolve({ data: { ...identity, version: 1 } })
    })
    mockedApiClient.put.mockResolvedValue({ data: identity })
    await renderLoaded()

    await fillValidForm()
    fireEvent.change(screen.getByLabelText(/^vat number/i), { target: { value: "gb 123 4567 89" } })
    fireEvent.click(screen.getByRole("button", { name: /save business details/i }))

    // The status region is persistent (present, empty, from first render) so assistive tech
    // announces the change; wait for its TEXT rather than its existence.
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(/business details saved/i))
    expect(mockedApiClient.put).toHaveBeenCalledWith(
      URL,
      expect.objectContaining({
        legalName: "Mama Ade Foods Ltd",
        entityType: "COMPANY",
        addressLine1: "12 Market Street",
        addressCity: "Birmingham",
        addressPostcode: "B1 1AA",
        vatNumber: "gb 123 4567 89",
      })
    )
    await waitFor(() => expect(calls).toBe(2))
    await waitFor(() => expect(screen.getByLabelText(/^vat number/i)).toHaveValue("GB123456789"))
  })

  it("is mounted on the onboarding page", async () => {
    mockedApiClient.get.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/onboarding/me")) {
        return Promise.resolve({
          data: {
            id: "onb-1", status: "DRAFT", model: "MARKETPLACE", shopId: "shop-1", companyNumber: null,
            submittedAt: null, approvedAt: null, wentLiveAt: null, rejectionReason: null,
            reviewPending: false, gates: [],
          },
        }) as Promise<never>
      }
      if (url.startsWith(URL)) return Promise.reject(notFound) as Promise<never>
      return Promise.resolve({ data: { content: [] } }) as Promise<never>
    })
    render(<OnboardingPage />)
    expect(
      await screen.findByRole("heading", { name: "Business details shown to customers" })
    ).toBeInTheDocument()
  })
})
