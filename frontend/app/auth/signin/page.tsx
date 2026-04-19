"use client"

import { signIn } from "next-auth/react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Store } from "lucide-react"

export default function SignInPage() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-900 dark:to-slate-800 p-4">
      <Card className="w-full max-w-md shadow-2xl">
        <CardHeader className="space-y-3 text-center pb-8">
          <div className="mx-auto bg-primary/10 w-16 h-16 rounded-2xl flex items-center justify-center mb-2">
            <Store className="w-8 h-8 text-primary" />
          </div>
          <CardTitle className="text-3xl font-bold">J&apos;Toye OaaS</CardTitle>
          <CardDescription className="text-base">
            Sign in to access your multi-tenant order management system
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <Button
            onClick={() => signIn("keycloak", { callbackUrl: "/dashboard" })}
            className="w-full h-12 text-base font-medium"
            size="lg"
          >
            Sign in with Keycloak
          </Button>
          <p className="text-xs text-center text-muted-foreground">
            Secure authentication via Keycloak OIDC
          </p>
        </CardContent>
      </Card>
    </div>
  )
}
