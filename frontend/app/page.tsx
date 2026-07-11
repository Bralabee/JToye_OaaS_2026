import type { Metadata } from "next"
import Link from "next/link"
import {
  UtensilsCrossed,
  Store,
  Search,
  ShoppingBag,
  MapPin,
  CheckCircle2,
  ArrowRight,
} from "lucide-react"
import { PublicShell } from "@/components/public/public-shell"

export const metadata: Metadata = {
  title: "J'Toye — Order from local kitchens, or run your own",
  description:
    "Order food from independent local kitchens in minutes, or run your own food business end-to-end — take orders, manage your kitchen, and go live in a day.",
}

const steps = [
  {
    icon: Search,
    title: "Browse",
    body: "Find independent kitchens near you and explore their menus.",
  },
  {
    icon: ShoppingBag,
    title: "Order & pay",
    body: "Add to your basket and check out securely — delivery or collection.",
  },
  {
    icon: MapPin,
    title: "Track live",
    body: "Follow your order from the kitchen to your door in real time.",
  },
]

const trustMarkers = [
  "UK food-hygiene verified",
  "Allergen info on every item",
  "No app to download",
]

/**
 * Public landing page (Surface A, UIX-01). Replaces the old blind redirect to
 * the dashboard — even signed-in vendors land here and reach the dashboard via
 * the header, never an auto-redirect.
 *
 * Server Component (no client directive) so the root layout's force-dynamic
 * CSP nonce cascades through (the #89 failure mode). Gradient-forward art
 * direction, no stock-photo dependency, no serif.
 */
export default function Home() {
  return (
    <PublicShell>
      {/* Split-persona hero */}
      <section className="relative overflow-hidden bg-slate-50">
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0 bg-gradient-to-br from-orange-400 via-orange-500 to-rose-500 opacity-10"
        />
        <div className="relative mx-auto max-w-5xl px-4 sm:px-6 lg:px-8 py-16 md:py-24">
          <div className="max-w-2xl">
            <h1 className="text-4xl sm:text-5xl md:text-6xl font-bold leading-[1.05] tracking-tight text-slate-900">
              Order from local kitchens. Or run yours.
            </h1>
            <p className="mt-4 max-w-xl text-lg text-slate-600">
              J&apos;Toye connects hungry customers with independent food
              businesses — order in minutes, or take your kitchen online and go
              live in a day.
            </p>
          </div>

          {/* Two equal persona doors */}
          <div className="mt-10 grid grid-cols-1 gap-4 sm:grid-cols-2 sm:gap-6">
            <Link
              href="/shop"
              className="group flex flex-col rounded-2xl bg-orange-500 p-6 text-white shadow-md transition-all hover:-translate-y-0.5 hover:shadow-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-orange-300 focus-visible:ring-offset-2"
            >
              <UtensilsCrossed className="h-8 w-8" />
              <span className="mt-4 text-lg font-bold">Order food near you</span>
              <span className="mt-1 text-sm text-orange-50">
                Browse independent kitchens and order in minutes.
              </span>
              <span className="mt-4 inline-flex items-center gap-1 text-sm font-semibold">
                Browse shops
                <ArrowRight className="h-4 w-4 transition-transform group-hover:translate-x-0.5" />
              </span>
            </Link>

            <Link
              href="/for-operators"
              className="group flex flex-col rounded-2xl bg-slate-900 p-6 text-white shadow-md transition-all hover:-translate-y-0.5 hover:shadow-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-400 focus-visible:ring-offset-2"
            >
              <Store className="h-8 w-8 text-orange-400" />
              <span className="mt-4 text-lg font-bold">
                Run your food business
              </span>
              <span className="mt-1 text-sm text-slate-300">
                Take orders, manage your kitchen, go live in a day.
              </span>
              <span className="mt-4 inline-flex items-center gap-1 text-sm font-semibold text-orange-400">
                Learn more
                <ArrowRight className="h-4 w-4 transition-transform group-hover:translate-x-0.5" />
              </span>
            </Link>
          </div>
        </div>
      </section>

      {/* How it works */}
      <section className="bg-white py-20">
        <div className="mx-auto max-w-5xl px-4 sm:px-6 lg:px-8">
          <h2 className="text-4xl font-semibold leading-tight text-slate-900">
            How it works
          </h2>
          <div className="mt-10 grid grid-cols-1 gap-8 sm:grid-cols-3">
            {steps.map((step) => (
              <div key={step.title} className="flex flex-col">
                <span className="inline-flex h-12 w-12 items-center justify-center rounded-full bg-orange-100 text-orange-600">
                  <step.icon className="h-6 w-6" />
                </span>
                <h3 className="mt-4 text-lg font-semibold text-slate-900">
                  {step.title}
                </h3>
                <p className="mt-2 text-sm text-slate-600">{step.body}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Trust strip */}
      <section className="border-t border-slate-200 bg-slate-50 py-12">
        <div className="mx-auto max-w-5xl px-4 sm:px-6 lg:px-8">
          <div className="flex flex-wrap gap-3">
            {trustMarkers.map((marker) => (
              <span
                key={marker}
                className="inline-flex items-center gap-2 rounded-full bg-white px-4 py-2 text-sm text-slate-700 shadow-sm ring-1 ring-slate-200"
              >
                <CheckCircle2 className="h-4 w-4 text-emerald-500" />
                {marker}
              </span>
            ))}
          </div>
        </div>
      </section>
    </PublicShell>
  )
}
