"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { Plus } from "lucide-react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { SecretRevealDialog } from "@/components/dashboard/secret-reveal-dialog"
import { CredentialCreateDialog } from "@/components/dashboard/developers/credential-create-dialog"
import { useToast } from "@/hooks/use-toast"
import { fetchMyShops } from "@/lib/shops-api"
import type { CredentialSecret } from "@/lib/credentials-api"
import { DEVELOPERS_COPY } from "@/components/dashboard/developers/developers-copy"
import type { Shop } from "@/types/api"

/**
 * Developers (37-46, UI-SPEC § E1; D-22, D-23, D-24; RWO-035 / UXT-035).
 *
 * A Group admin issues API credentials for a till, a website or an AI agent, so nobody shares a
 * password. Each credential holds the scopes ticked, one role (never Group admin) and one shop or
 * every shop, and its secret is shown exactly once.
 *
 * The gate is the server's (37-45: human caller, then Group admin); this page mirrors it so a
 * person who may not manage credentials is told so rather than shown an empty list.
 *
 * T-37-112: the secret is held in this component's state only while the show-once dialog is open,
 * and dropped in the same handler that closes it. Nothing here writes to storage.
 */


/** What the show-once dialog holds while it is open, and nothing longer. */
interface Reveal {
  secret: string
  clientId: string
  tokenEndpoint: string | null
}

export default function DevelopersPage() {
  const { toast } = useToast()
  const [shops, setShops] = useState<Shop[]>([])
  const [createOpen, setCreateOpen] = useState(false)
  const [reveal, setReveal] = useState<Reveal | null>(null)

  useEffect(() => {
    let live = true
    fetchMyShops()
      .then((mine) => {
        if (live) setShops(mine.shops)
      })
      .catch(() => {})
    return () => {
      live = false
    }
  }, [])

  const onCreated = (created: CredentialSecret) => {
    setCreateOpen(false)
    if (created.secretShown && created.clientSecret) {
      setReveal({
        secret: created.clientSecret,
        clientId: created.clientId,
        tokenEndpoint: created.tokenEndpoint,
      })
    } else {
      toast({
        title: `"${created.name}" was already created`,
        description: "Its secret can't be shown again. Rotate it to get a new one.",
      })
    }
  }

  return (
    <div data-width-tier="index" className="space-y-6">
      <div className="flex flex-col items-start justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-4xl font-semibold text-slate-900">{DEVELOPERS_COPY.title}</h1>
          <p className="mt-2 text-sm text-slate-600">{DEVELOPERS_COPY.subtitle}</p>
        </div>
        <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row sm:items-center">
          <Button asChild variant="link" className="h-11 justify-start px-0 sm:px-4">
            <Link href="/dashboard/webhooks">{DEVELOPERS_COPY.webhooksLink}</Link>
          </Button>
          <Button className="h-11 w-full gap-2 sm:w-auto" onClick={() => setCreateOpen(true)}>
            <Plus className="h-4 w-4" aria-hidden="true" />
            {DEVELOPERS_COPY.create}
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base font-semibold">{DEVELOPERS_COPY.cardTitle}</CardTitle>
        </CardHeader>
        <CardContent />
      </Card>

      <CredentialCreateDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        shops={shops}
        onCreated={onCreated}
        onNotConfigured={() => setCreateOpen(false)}
      />

      <SecretRevealDialog
        open={reveal !== null}
        onOpenChange={(o) => {
          // Dropped in the same handler that closes the dialog (F13, T-37-112).
          if (!o) setReveal(null)
        }}
        title={DEVELOPERS_COPY.revealTitle}
        description={DEVELOPERS_COPY.revealDescription}
        warning={DEVELOPERS_COPY.revealWarning}
        secretLabel="client secret"
        secret={reveal?.secret ?? null}
        clientId={reveal?.clientId ?? null}
        tokenEndpoint={reveal?.tokenEndpoint ?? null}
      />
    </div>
  )
}
