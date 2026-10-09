/**
 * ConfirmActionDialog, generalised for the whole dashboard (37-06, UI-SPEC
 * § Component Inventory and § Color "Contrast contract").
 *
 * - The cancel button says what cancelling keeps ("Keep access", "Keep endpoint"):
 *   a required `cancelLabel`, so no bare "Cancel" survives in this component.
 * - A non-destructive confirm is the `Button` default variant (orange-700 =
 *   `--primary`, 5.18:1 with white text), replacing the orange-500 literal that
 *   measured 2.80:1 and failed WCAG 1.4.3.
 * - A destructive confirm stays the destructive variant.
 * - The pending state ("Working…", cannot close mid-flight) is preserved.
 *
 * Imported through the OLD webhooks path on purpose: that path must keep working
 * as a re-export for the webhooks pages.
 */
import { act, fireEvent, render, screen, within } from "@testing-library/react"
import { ConfirmActionDialog } from "@/components/dashboard/webhooks/ConfirmActionDialog"

function renderDialog(props: Partial<Parameters<typeof ConfirmActionDialog>[0]> = {}) {
  const onOpenChange = jest.fn()
  const onConfirm = jest.fn()
  render(
    <ConfirmActionDialog
      open
      onOpenChange={onOpenChange}
      title="Remove Staff at Peckham Kitchen for Sam Cook?"
      description="After this they'll have no access to this business."
      confirmLabel="Remove access"
      cancelLabel="Keep access"
      onConfirm={onConfirm}
      {...props}
    />
  )
  return { onOpenChange, onConfirm, dialog: screen.getByRole("dialog") }
}

describe("ConfirmActionDialog", () => {
  it("labels the cancel button with what cancelling keeps, never a bare 'Cancel'", () => {
    const { dialog, onOpenChange } = renderDialog()

    const keep = within(dialog).getByRole("button", { name: "Keep access" })
    expect(within(dialog).queryByRole("button", { name: /^cancel$/i })).toBeNull()

    fireEvent.click(keep)
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it("renders a non-destructive confirm as the default (accent) Button, not orange-500", () => {
    const { dialog } = renderDialog({ confirmLabel: "Rotate secret", cancelLabel: "Keep current secret" })

    const confirm = within(dialog).getByRole("button", { name: "Rotate secret" })
    expect(confirm).toHaveClass("bg-primary")
    expect(confirm).not.toHaveClass("bg-orange-500")
  })

  it("renders a destructive confirm as the destructive Button", () => {
    const { dialog } = renderDialog({ destructive: true })

    const confirm = within(dialog).getByRole("button", { name: "Remove access" })
    expect(confirm).toHaveClass("bg-destructive")
    expect(confirm).not.toHaveClass("bg-primary")
  })

  it("shows 'Working…' and cannot be dismissed while the action is in flight", async () => {
    let release: () => void = () => {}
    const onConfirm = jest.fn(() => new Promise<void>((resolve) => (release = resolve)))
    const { dialog, onOpenChange } = renderDialog({ onConfirm })

    fireEvent.click(within(dialog).getByRole("button", { name: "Remove access" }))

    const working = within(dialog).getByRole("button", { name: "Working…" })
    expect(working).toBeDisabled()
    expect(within(dialog).getByRole("button", { name: "Keep access" })).toBeDisabled()
    fireEvent.keyDown(dialog, { key: "Escape" })
    expect(onOpenChange).not.toHaveBeenCalled()

    await act(async () => release())
    expect(within(dialog).getByRole("button", { name: "Remove access" })).toBeEnabled()
  })
})
