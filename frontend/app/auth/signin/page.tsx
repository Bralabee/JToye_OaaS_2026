"use client"

import Link from "next/link"
import { signIn } from "next-auth/react"
import { ArrowLeft } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { CompanyLegalLine } from "@/components/platform/company-legal"

/**
 * Operator sign-in. This page is a LANDING DESTINATION, not just transit:
 * every expired dashboard session and every /dashboard deep link ends here,
 * so it must carry the shipped brand (oxblood + cream + amber — public-header
 * wordmark idiom) and must never be a dead end (escape links below the CTA).
 */
export default function SignInPage() {
  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-cream via-white to-cream-100 p-4">
      <Card className="w-full max-w-md border-cream-100 shadow-xl">
        <CardHeader className="space-y-4 text-center pb-6">
          <Link
            href="/"
            aria-label="J'Toye home"
            className="mx-auto flex w-fit items-center gap-2 text-xl font-semibold tracking-tight text-oxblood"
          >
            <span className="inline-flex h-10 w-10 items-center justify-center rounded-xl bg-oxblood text-base font-bold text-white">
              J
            </span>
            <span>J&apos;Toye</span>
          </Link>
          <CardTitle className="text-2xl font-bold text-oxblood">
            Welcome back
          </CardTitle>
          <CardDescription className="text-base text-slate-600">
            Sign in to manage your shop, orders and kitchen.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <Button
            onClick={() => signIn("keycloak", { callbackUrl: "/dashboard" })}
            className="w-full h-12 rounded-full bg-oxblood text-base font-semibold text-white hover:bg-oxblood-700"
            size="lg"
          >
            Sign in with Keycloak
          </Button>
          <p className="text-center text-xs text-slate-500">
            Secure authentication via Keycloak OIDC
          </p>
          <div className="flex items-center justify-center gap-6 border-t border-cream-100 pt-4 text-sm">
            <Link
              href="/"
              className="inline-flex items-center gap-1 text-slate-600 transition-colors hover:text-slate-900"
            >
              <ArrowLeft className="h-4 w-4" aria-hidden="true" />
              Back to J&apos;Toye
            </Link>
            <Link
              href="/shop"
              className="font-medium text-amber-600 transition-colors hover:text-amber-700"
            >
              Browse kitchens
            </Link>
          </div>
        </CardContent>
      </Card>
      <CompanyLegalLine className="mt-6 max-w-md text-center" />
    </div>
  )
}
