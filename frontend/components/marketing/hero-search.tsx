"use client"

import { useState, type FormEvent } from "react"
import { useRouter } from "next/navigation"
import { Search } from "lucide-react"

/**
 * Landing hero search (client island inside the Server-Component landing page —
 * app/page.tsx must stay server-rendered so the force-dynamic CSP nonce
 * cascades, the #89 failure mode).
 *
 * This replaces a decorative <Link href="/shop"> that LOOKED like a search box
 * but silently dumped you on the unfiltered shop index — the guide text
 * promised "jollof", "vegan", a postcode, and none of it did anything. It is
 * now a real query: the term goes to /shop?q=… which the discovery page reads
 * and runs against the public shop search (Postgres FTS over shop name, tags,
 * description and address — so a dish, a cuisine or a postcode all resolve).
 *
 * Progressive enhancement: the element is a genuine <form action="/shop"
 * method="get"> with a `q` field, so it submits and lands correctly even if the
 * JS never arrives; when it has, onSubmit upgrades it to a client-side push.
 */
export function HeroSearch() {
  const router = useRouter()
  const [query, setQuery] = useState("")

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const term = query.trim()
    router.push(term ? `/shop?q=${encodeURIComponent(term)}` : "/shop")
  }

  return (
    <form
      role="search"
      action="/shop"
      method="get"
      onSubmit={handleSubmit}
      className="mt-6 flex max-w-xl gap-2.5"
    >
      <label htmlFor="hero-search" className="sr-only">
        Search kitchens, dishes or a postcode
      </label>
      <div className="relative flex-1">
        <Search
          aria-hidden
          className="pointer-events-none absolute left-5 top-1/2 h-4 w-4 -translate-y-1/2 text-oxblood-600"
        />
        <input
          id="hero-search"
          name="q"
          type="search"
          autoComplete="off"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Try “jollof”, “vegan” or your postcode…"
          className="w-full rounded-full border border-cream-100 bg-white py-3 pl-12 pr-4 text-sm text-slate-900 shadow-sm transition-colors placeholder:text-slate-500 hover:border-amber-300 focus:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-200"
        />
      </div>
      <button
        type="submit"
        className="inline-flex items-center rounded-full bg-amber-500 px-5 py-3 text-sm font-bold text-amber-ink shadow-[0_10px_28px_rgba(217,119,6,0.30)] transition-transform hover:-translate-y-0.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300 focus-visible:ring-offset-2"
      >
        Search
      </button>
    </form>
  )
}
