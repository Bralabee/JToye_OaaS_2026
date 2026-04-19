import Link from "next/link"
import { Button } from "@/components/ui/button"
import { BRAND } from "@/lib/brand"
import { ArrowRight, Store, ShieldCheck, Utensils } from "lucide-react"

// Landing splash — static, prerenders at build time. Separate surfaces
// for the two audiences of the platform: customers (Browse shops) and
// vendors (Vendor sign-in). Authenticated vendors can navigate to the
// dashboard directly.
export default function Home() {
  return (
    <main className="min-h-screen bg-surface-canvas text-ink-primary">
      {/* Hero */}
      <section className="relative overflow-hidden">
        <div className="relative mx-auto max-w-[72rem] px-6 pb-20 pt-24 sm:pt-32 lg:px-10 lg:pb-28 lg:pt-40">
          {/* Subtle brand watermark — top-right */}
          <img
            src={BRAND.marks.icon}
            alt=""
            aria-hidden="true"
            className="pointer-events-none absolute right-6 top-10 h-16 w-16 text-brand-primary opacity-10 lg:h-24 lg:w-24"
          />

          <div className="max-w-[52rem]">
            <p className="font-sans text-overline uppercase tracking-[0.16em] text-ink-tertiary text-caption font-medium">
              {BRAND.fullName}
            </p>
            <h1 className="mt-6 font-display tracking-tight text-ink-primary text-4xl leading-[1.05] sm:text-5xl lg:text-6xl xl:text-7xl">
              Every shop. Every order.
              <span className="block text-brand-primary">One kitchen.</span>
            </h1>
            <p className="mt-6 max-w-prose font-sans text-lg text-ink-secondary sm:text-xl">
              The multi-tenant platform for food vendors. Run the shop, the menu, the kitchen,
              and the customer experience from a single, production-grade home.
            </p>

            <div className="mt-10 flex flex-wrap items-center gap-3">
              <Button asChild variant="primary" size="lg">
                <Link href="/shop">
                  Browse shops
                  <ArrowRight className="ml-2 h-4 w-4" strokeWidth={1.75} />
                </Link>
              </Button>
              <Button asChild variant="secondary" size="lg">
                <Link href="/auth/signin">Vendor sign-in</Link>
              </Button>
            </div>
          </div>
        </div>
      </section>

      {/* Feature strip */}
      <section className="border-t border-subtle bg-surface-subtle">
        <div className="mx-auto grid max-w-[72rem] gap-10 px-6 py-16 sm:grid-cols-3 lg:gap-16 lg:px-10 lg:py-20">
          <FeatureBlock
            icon={<Store className="h-5 w-5" strokeWidth={1.5} />}
            title="Storefronts"
            body="Public vendor pages with menus, ordering, and payments — live in minutes."
          />
          <FeatureBlock
            icon={<Utensils className="h-5 w-5" strokeWidth={1.5} />}
            title="Kitchen Display"
            body="Real-time ticket flow from order confirm to ready-for-collection."
          />
          <FeatureBlock
            icon={<ShieldCheck className="h-5 w-5" strokeWidth={1.5} />}
            title="Trust by default"
            body="Multi-tenant isolation, audit trails, and security built in at every layer."
          />
        </div>
      </section>

      {/* Quiet footer */}
      <footer className="border-t border-subtle">
        <div className="mx-auto flex max-w-[72rem] flex-wrap items-center justify-between gap-4 px-6 py-8 lg:px-10">
          <p className="font-sans text-sm text-ink-tertiary">
            © {new Date().getFullYear()} {BRAND.fullName}
          </p>
          <div className="flex items-center gap-5 font-sans text-sm text-ink-secondary">
            <Link href="/shop" className="hover:text-ink-primary">Browse</Link>
            <Link href="/track" className="hover:text-ink-primary">Track an order</Link>
            <Link href="/auth/signin" className="hover:text-ink-primary">Sign in</Link>
          </div>
        </div>
      </footer>
    </main>
  )
}

function FeatureBlock({
  icon,
  title,
  body,
}: {
  icon: React.ReactNode
  title: string
  body: string
}) {
  return (
    <div>
      <div className="inline-flex h-10 w-10 items-center justify-center rounded-lg bg-brand-primary/10 text-brand-primary">
        {icon}
      </div>
      <h2 className="mt-4 font-display text-xl tracking-tight text-ink-primary">{title}</h2>
      <p className="mt-2 max-w-prose font-sans text-sm text-ink-secondary leading-relaxed">
        {body}
      </p>
    </div>
  )
}
