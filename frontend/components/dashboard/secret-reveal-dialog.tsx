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
 * SecretRevealDialog: the dashboard's one show-once secret dialog (COMMS-06 for a webhook signing
 * secret; 37-46 / UI-SPEC § E1 for an API credential's client secret).
 *
 * The secret is returned exactly once (create and rotate responses) and can never be fetched
 * again, so this dialog refuses every implicit close: Esc and a click outside are prevented, and the
 * corner X is not rendered at all (`showCloseButton={false}`). The only way out is the explicit
 * "I've saved it", the `Button` default variant (orange-700, 5.18:1). It replaced an orange-500
 * literal that measured 2.80:1 (UI-SPEC § Color), which also moves the Webhooks page's button.
 *
 * THE SECRET LIVES IN THE PARENT'S COMPONENT STATE ONLY (T-37-112, F13). This component keeps no
 * copy, writes nothing to storage, and its content unmounts on close; the parent drops the value
 * in the same `onOpenChange(false)`.
 *
 * Every copy button names what it copies ("Copy client ID", "Copy client secret", "Copy signing
 * secret"), so no bare "Copy" sits next to two values.
 */

export interface SecretRevealDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** e.g. "Copy your client secret", "Copy your signing secret". */
  title: string
  /** One sentence under the title: what the secret is for. */
  description: string
  /** The show-once warning, verbatim from the UI-SPEC copy for the surface. */
  warning: string
  /** Lower-case noun for the secret: "client secret", "signing secret". */
  secretLabel: string
  secret: string | null
  /** A credential's client ID; when set, it is shown with its own "Copy client ID" button. */
  clientId?: string | null
  /** A credential's token endpoint, where the client ID and secret are exchanged for a token. */
  tokenEndpoint?: string | null
}

/** "client secret" → "Client secret": the input's accessible name. */
function sentenceCase(s: string): string {
  return s.length === 0 ? s : s[0].toUpperCase() + s.slice(1)
}

function CopyField({
  id,
  label,
  value,
  copyName,
  copied,
  onCopy,
}: {
  id: string
  label: string
  value: string
  copyName: string
  copied: boolean
  onCopy: () => void
}) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="text-sm font-semibold text-slate-900">
        {label}
      </label>
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <Input
          id={id}
          readOnly
          value={value}
          className="h-11 font-mono text-sm"
          onFocus={(e) => e.currentTarget.select()}
        />
        <Button
          type="button"
          variant="outline"
          onClick={onCopy}
          className="h-11 shrink-0"
        >
          {copied ? (
            <Check className="mr-1 h-4 w-4 text-emerald-700" aria-hidden="true" />
          ) : (
            <Copy className="mr-1 h-4 w-4" aria-hidden="true" />
          )}
          {copyName}
        </Button>
      </div>
    </div>
  )
}

export function SecretRevealDialog({
  open,
  onOpenChange,
  title,
  description,
  warning,
  secretLabel,
  secret,
  clientId,
  tokenEndpoint,
}: SecretRevealDialogProps) {
  const { toast } = useToast()
  const [copied, setCopied] = useState<"clientId" | "secret" | null>(null)

  // Radix fires onOpenChange(false) for backdrop / Esc. For a once-only secret every implicit
  // close is ignored: only the explicit button below closes it.
  const handleRootOpenChange = (next: boolean) => {
    if (next) onOpenChange(true)
  }

  const copy = async (which: "clientId" | "secret", value: string, what: string) => {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(which)
      // The toast names WHAT was copied, never the value.
      toast({ title: "Copied", description: `${sentenceCase(what)} copied to clipboard.` })
      setTimeout(() => setCopied((c) => (c === which ? null : c)), 2000)
    } catch {
      toast({
        variant: "destructive",
        title: "Copy failed",
        description: `Select the ${what} and copy it manually.`,
      })
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleRootOpenChange}>
      <DialogContent
        showCloseButton={false}
        className="max-h-[90vh] max-w-lg overflow-y-auto"
        onInteractOutside={(e) => e.preventDefault()}
        onEscapeKeyDown={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>

        <p
          role="alert"
          className="rounded-md border border-amber-200 bg-amber-50 px-4 py-2 text-sm text-amber-900"
        >
          {warning}
        </p>

        {clientId && (
          <CopyField
            id="secret-reveal-client-id"
            label="Client ID"
            value={clientId}
            copyName={copied === "clientId" ? "Copied" : "Copy client ID"}
            copied={copied === "clientId"}
            onCopy={() => copy("clientId", clientId, "client ID")}
          />
        )}

        <CopyField
          id="secret-reveal-secret"
          label={sentenceCase(secretLabel)}
          value={secret ?? ""}
          copyName={copied === "secret" ? "Copied" : `Copy ${secretLabel}`}
          copied={copied === "secret"}
          onCopy={() => {
            if (secret) void copy("secret", secret, secretLabel)
          }}
        />

        {tokenEndpoint && (
          <div className="space-y-1.5">
            <p className="text-sm font-semibold text-slate-900">Token endpoint</p>
            <p className="font-mono text-sm text-slate-700 [overflow-wrap:anywhere]">{tokenEndpoint}</p>
          </div>
        )}

        <DialogFooter>
          <Button type="button" className="h-11" onClick={() => onOpenChange(false)}>
            I&apos;ve saved it
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export default SecretRevealDialog
