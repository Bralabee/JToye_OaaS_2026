/**
 * The generalised show-once dialog (37-46, UI-SPEC § Component Inventory and § E1 "Show once").
 *
 * One component shows two kinds of secret exactly once: a webhook signing secret (COMMS-06) and an
 * API credential's client secret (D-22). What it must do in both cases:
 *
 * - name the secret it holds ("Copy client secret", "Copy signing secret"), and, for a credential,
 *   show the client ID with its own "Copy client ID" button and the token endpoint;
 * - refuse every implicit close (Esc, a click outside, the corner X) — the secret cannot be fetched
 *   again, so the only way out is the explicit "I've saved it";
 * - make that exit the `Button` default variant (orange-700, 5.18:1), not the orange-500 literal
 *   that measured 2.80:1 (UI-SPEC § Color, contrast contract).
 *
 * The parent owns the secret (component state, dropped on close); the page suites prove it leaves
 * the DOM and both storages after "I've saved it" (F13).
 */
import { useState } from "react"
import { act, fireEvent, render, screen, within } from "@testing-library/react"
import { SecretRevealDialog } from "@/components/dashboard/secret-reveal-dialog"
import { SecretRevealDialog as WebhooksPathDialog } from "@/components/dashboard/webhooks/SecretRevealDialog"

jest.mock("@/hooks/use-toast", () => {
  const toast = jest.fn()
  return { useToast: () => ({ toast }) }
})

const SECRET = "s3cr3t-Value-for-the-dialog-test-0001"
const CLIENT_ID = "jtoye-int-till-system-7f3a"
const TOKEN_ENDPOINT = "https://id.example.test/realms/jtoye/protocol/openid-connect/token"
const WARNING =
  "This is the only time we'll show this secret. Store it somewhere safe. If you lose it, rotate it to get a new one; the old one stops working."

/** A controlled host, so "does it close?" is answered by what the dialog itself does. */
function CredentialHost({ onClose }: { onClose?: () => void }) {
  const [open, setOpen] = useState(true)
  return (
    <SecretRevealDialog
      open={open}
      onOpenChange={(o) => {
        setOpen(o)
        if (!o) onClose?.()
      }}
      title="Copy your client secret"
      description="Give these to the system that will call J'Toye."
      warning={WARNING}
      secretLabel="client secret"
      secret={SECRET}
      clientId={CLIENT_ID}
      tokenEndpoint={TOKEN_ENDPOINT}
    />
  )
}

beforeEach(() => {
  Object.assign(navigator, { clipboard: { writeText: jest.fn().mockResolvedValue(undefined) } })
})

describe("SecretRevealDialog (generalised, 37-46)", () => {
  it("shows the client ID, the client secret and the token endpoint, each named", () => {
    render(<CredentialHost />)
    const dialog = screen.getByRole("dialog")

    expect(within(dialog).getByRole("heading", { name: "Copy your client secret" })).toBeInTheDocument()
    expect(within(dialog).getByLabelText("Client ID")).toHaveValue(CLIENT_ID)
    expect(within(dialog).getByLabelText("Client secret")).toHaveValue(SECRET)
    expect(within(dialog).getByText(TOKEN_ENDPOINT)).toBeInTheDocument()
    expect(within(dialog).getByText(WARNING)).toBeInTheDocument()

    expect(within(dialog).getByRole("button", { name: "Copy client ID" })).toBeInTheDocument()
    expect(within(dialog).getByRole("button", { name: "Copy client secret" })).toBeInTheDocument()
    // No bare "Copy": every copy button says what it copies.
    expect(within(dialog).queryByRole("button", { name: /^copy$/i })).toBeNull()
  })

  it("copies the client ID and the secret with their own buttons", async () => {
    render(<CredentialHost />)
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Copy client ID" }))
    })
    expect(navigator.clipboard.writeText).toHaveBeenLastCalledWith(CLIENT_ID)
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Copy client secret" }))
    })
    expect(navigator.clipboard.writeText).toHaveBeenLastCalledWith(SECRET)
  })

  it("cannot be dismissed by Esc, by a click outside, or by a corner X", () => {
    const onClose = jest.fn()
    render(<CredentialHost onClose={onClose} />)
    const dialog = screen.getByRole("dialog")

    fireEvent.keyDown(dialog, { key: "Escape" })
    expect(screen.getByRole("dialog")).toBeInTheDocument()

    // Radix decides "outside" on pointerdown outside the content.
    fireEvent.pointerDown(document.body)
    expect(screen.getByRole("dialog")).toBeInTheDocument()

    // The corner X is not offered at all.
    expect(within(dialog).queryByRole("button", { name: /close/i })).toBeNull()
    expect(onClose).not.toHaveBeenCalled()
  })

  it("closes only through 'I've saved it', which is the default (accent) Button", () => {
    const onClose = jest.fn()
    render(<CredentialHost onClose={onClose} />)

    const exit = screen.getByRole("button", { name: "I've saved it" })
    expect(exit).toHaveClass("bg-primary")
    expect(exit).not.toHaveClass("bg-orange-500")

    fireEvent.click(exit)
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole("dialog")).toBeNull()
  })

  it("keeps the webhook form at the old path: a signing secret, no client ID", () => {
    render(
      <WebhooksPathDialog
        open
        onOpenChange={() => {}}
        title="Copy your signing secret"
        description="Use this secret to verify the HMAC signature on every delivery."
        warning="This is the only time we'll show this secret."
        secretLabel="signing secret"
        secret={SECRET}
      />
    )
    const dialog = screen.getByRole("dialog")
    expect(within(dialog).getByLabelText("Signing secret")).toHaveValue(SECRET)
    expect(within(dialog).getByRole("button", { name: "Copy signing secret" })).toBeInTheDocument()
    expect(within(dialog).queryByRole("button", { name: "Copy client ID" })).toBeNull()
    expect(within(dialog).queryByLabelText("Client ID")).toBeNull()
    expect(within(dialog).getByRole("button", { name: "I've saved it" })).toHaveClass("bg-primary")
  })
})
