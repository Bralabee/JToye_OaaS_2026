/**
 * QA council 20260902-134741 — A11Y-2 (focus restore) + A11Y-16 (aria-modal),
 * on the two shared overlay primitives.
 *
 * WHY THE PRIMITIVE AND NOT THE CALL SITES. Radix's modal `DialogContent`
 * default `onCloseAutoFocus` is `preventDefault()` + focus its own
 * `Dialog.Trigger` (react-dialog dist/index.mjs, DialogContentModal). Every one
 * of the 12 `@/components/ui/dialog` importers is controlled-`open` with ZERO
 * `<DialogTrigger>` in the tree, and the basket drawer is a controlled Sheet
 * with no `<SheetTrigger>`, so that trigger ref is null and focus was measured
 * landing on <body> after every close path (probes/a11y/07, 08). The fix
 * captures `document.activeElement` when the content opens and restores to it
 * on close — ONLY when it is still in the document; otherwise it falls through
 * to Radix's own default so the three `SheetTrigger` consumers are byte-for-byte
 * unchanged.
 *
 * WHY jsdom IS ADMISSIBLE HERE AND NOT FOR THE REST OF CLUSTER G. The defect is
 * pure event plumbing (which handler wins, whether preventDefault was called),
 * not layout, and jsdom implements focus()/activeElement. The browser truth is
 * still owed and is taken by probes 07/08 after the rebuild.
 */
import * as React from "react"
import { render, screen, fireEvent, waitFor, act } from "@testing-library/react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { Sheet, SheetContent, SheetDescription, SheetTitle } from "@/components/ui/sheet"

/** The shape every dashboard dialog has: controlled `open`, no Radix trigger. */
function ControlledDialog({
  onCloseAutoFocus,
}: {
  onCloseAutoFocus?: (event: Event) => void
}) {
  const [open, setOpen] = React.useState(false)
  return (
    <div>
      <button type="button" onClick={() => setOpen(true)}>
        Open it
      </button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent onCloseAutoFocus={onCloseAutoFocus}>
          <DialogTitle>A dialog</DialogTitle>
          <DialogDescription>Body</DialogDescription>
          <button type="button" onClick={() => setOpen(false)}>
            Done
          </button>
        </DialogContent>
      </Dialog>
    </div>
  )
}

/** The cart-drawer shape: controlled Sheet, no SheetTrigger. */
function ControlledSheet() {
  const [open, setOpen] = React.useState(false)
  return (
    <div>
      <button type="button" onClick={() => setOpen(true)}>
        Open basket
      </button>
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent>
          <SheetTitle>Your basket</SheetTitle>
          <SheetDescription>Items</SheetDescription>
          <button type="button" onClick={() => setOpen(false)}>
            Continue shopping
          </button>
        </SheetContent>
      </Sheet>
    </div>
  )
}

/**
 * A dialog that has BOTH a Radix trigger and an external opener, so the
 * fall-through path is observable: when the external opener is gone by the
 * time the dialog closes, Radix's default must still run and focus the
 * trigger. An unguarded restore would swallow that default and leave focus on
 * <body>.
 */
function TriggerAndExternalOpener() {
  const [open, setOpen] = React.useState(false)
  const [hasOpener, setHasOpener] = React.useState(true)
  return (
    <div>
      {hasOpener && (
        <button type="button" onClick={() => setOpen(true)}>
          External opener
        </button>
      )}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogTrigger>Radix trigger</DialogTrigger>
        <DialogContent>
          <DialogTitle>A dialog</DialogTitle>
          <DialogDescription>Body</DialogDescription>
          <button
            type="button"
            onClick={() => {
              setHasOpener(false)
              setOpen(false)
            }}
          >
            Remove opener and close
          </button>
        </DialogContent>
      </Dialog>
    </div>
  )
}

async function openFrom(name: string) {
  const opener = screen.getByRole("button", { name })
  act(() => opener.focus())
  expect(opener).toHaveFocus()
  fireEvent.click(opener)
  const dialog = await screen.findByRole("dialog")
  return { opener, dialog }
}

describe("DialogContent — focus restore to the invoker (A11Y-2)", () => {
  it("returns focus to the button that opened a controlled dialog when Escape closes it", async () => {
    render(<ControlledDialog />)
    const { opener, dialog } = await openFrom("Open it")
    // Radix has moved focus INTO the dialog; the invoker no longer has it.
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true))

    fireEvent.keyDown(document.activeElement as Element, { key: "Escape" })

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
    await waitFor(() => expect(opener).toHaveFocus())
  })

  it("returns focus to the invoker when a button inside the dialog closes it", async () => {
    render(<ControlledDialog />)
    const { opener } = await openFrom("Open it")

    fireEvent.click(screen.getByRole("button", { name: "Done" }))

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
    await waitFor(() => expect(opener).toHaveFocus())
  })

  it("falls through to Radix's own default when the invoker has left the document", async () => {
    render(<TriggerAndExternalOpener />)
    await openFrom("External opener")

    fireEvent.click(screen.getByRole("button", { name: "Remove opener and close" }))

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
    expect(screen.queryByRole("button", { name: "External opener" })).not.toBeInTheDocument()
    // Radix's default (focus the Dialog.Trigger) still ran — the restore did
    // not preventDefault against a detached opener.
    await waitFor(() => expect(screen.getByRole("button", { name: "Radix trigger" })).toHaveFocus())
  })

  it("lets a call site opt out by calling preventDefault in its own onCloseAutoFocus", async () => {
    const optOut = jest.fn((event: Event) => event.preventDefault())
    render(<ControlledDialog onCloseAutoFocus={optOut} />)
    const { opener } = await openFrom("Open it")

    fireEvent.click(screen.getByRole("button", { name: "Done" }))

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
    await waitFor(() => expect(optOut).toHaveBeenCalledTimes(1))
    // The consumer asked for no auto-focus; the primitive honoured it.
    expect(opener).not.toHaveFocus()
  })
})

describe("SheetContent — focus restore to the invoker (A11Y-2, basket drawer shape)", () => {
  it("returns focus to the button that opened a controlled sheet with no SheetTrigger", async () => {
    render(<ControlledSheet />)
    const { opener } = await openFrom("Open basket")

    fireEvent.click(screen.getByRole("button", { name: "Continue shopping" }))

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
    await waitFor(() => expect(opener).toHaveFocus())
  })
})

describe("aria-modal is stated on both primitives (A11Y-16)", () => {
  it("DialogContent renders aria-modal=true", async () => {
    render(<ControlledDialog />)
    const { dialog } = await openFrom("Open it")
    expect(dialog).toHaveAttribute("aria-modal", "true")
  })

  it("SheetContent renders aria-modal=true", async () => {
    render(<ControlledSheet />)
    const { dialog } = await openFrom("Open basket")
    expect(dialog).toHaveAttribute("aria-modal", "true")
  })
})
