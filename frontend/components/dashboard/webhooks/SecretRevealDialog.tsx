"use client"

import { useState } from "react"
import { Copy, Check } from "lucide-react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useToast } from "@/hooks/use-toast"

/**
 * SecretRevealDialog (COMMS-06, UI-SPEC Interaction + Accessibility Contracts).
 *
 * The signing secret is shown EXACTLY ONCE (create + rotate response) and is
 * never re-fetchable. To reduce accidental loss this dialog is focus-trapped
 * (radix) AND cannot be dismissed by backdrop click, Esc, or the X — only the
 * explicit "I've saved it" button closes it. The secret sits in a readOnly
 * `font-mono` input (`aria-label="Signing secret"`); a Copy button writes the
 * clipboard and announces success via an aria-live toast.
 */

interface SecretRevealDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  secret: string | null
}

export function SecretRevealDialog({
  open,
  onOpenChange,
  secret,
}: SecretRevealDialogProps) {
  const { toast } = useToast()
  const [copied, setCopied] = useState(false)

  // Radix fires onOpenChange(false) for backdrop / Esc / X. For a once-only
  // secret we ignore every implicit close — the secret can ONLY be dismissed
  // via the explicit confirm button below.
  const handleRootOpenChange = (next: boolean) => {
    if (next) onOpenChange(true)
  }

  const copySecret = async () => {
    if (!secret) return
    try {
      await navigator.clipboard.writeText(secret)
      setCopied(true)
      toast({ title: "Copied", description: "Signing secret copied to clipboard." })
      setTimeout(() => setCopied(false), 2000)
    } catch {
      toast({
        variant: "destructive",
        title: "Copy failed",
        description: "Select the secret and copy it manually.",
      })
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleRootOpenChange}>
      <DialogContent
        className="max-w-md max-h-[90vh] overflow-y-auto [&>button]:hidden"
        onInteractOutside={(e) => e.preventDefault()}
        onEscapeKeyDown={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>Copy your signing secret</DialogTitle>
          <DialogDescription>
            Use this secret to verify the HMAC signature on every delivery.
          </DialogDescription>
        </DialogHeader>

        <p
          role="alert"
          className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800"
        >
          This is the only time we&apos;ll show this secret. Store it securely —
          you need it to verify webhook signatures. If you lose it, rotate to
          generate a new one.
        </p>

        <div className="flex items-center gap-2">
          <Input
            readOnly
            aria-label="Signing secret"
            value={secret ?? ""}
            className="font-mono text-xs"
            onFocus={(e) => e.currentTarget.select()}
          />
          <Button
            type="button"
            variant="outline"
            onClick={copySecret}
            aria-label="Copy signing secret"
            className="shrink-0"
          >
            {copied ? (
              <Check className="h-4 w-4 text-emerald-600" />
            ) : (
              <Copy className="h-4 w-4" />
            )}
            <span className="ml-1">{copied ? "Copied" : "Copy"}</span>
          </Button>
        </div>

        <DialogFooter>
          <Button
            type="button"
            onClick={() => onOpenChange(false)}
            className="bg-orange-500 text-white hover:bg-orange-600"
          >
            I&apos;ve saved it
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export default SecretRevealDialog
