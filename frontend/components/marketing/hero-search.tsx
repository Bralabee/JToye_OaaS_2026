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
      className="mt-7 flex max-w-xl gap-2"
    >
      <label htmlFor="hero-search" className="sr-only">
        Search kitchens, dishes or a postcode
      </label>
      <div className="relative flex-1">
        <Search
          aria-hidden
          className="pointer-events-none absolute left-[18px] top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-brand-700"
        />
        <input
          id="hero-search"
          name="q"
          type="search"
          autoComplete="off"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Try “jollof”, “vegan” or your postcode…"
          // D14: white on the hero's warm-100 ground, separated by tone (no
          // outline, no resting shadow); 16px text, which also stops iOS zooming
          // the page on focus. Focus is a 2px brand-700 outline.
          className="h-[52px] w-full rounded-full bg-white pl-12 pr-4 text-base text-warm-900 placeholder:text-warm-600 focus:outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
        />
      </div>
      <button
        type="submit"
        // D14: the action accent, flat gold-400 with brand-900 type (8.71:1). The
        // orange glow and the lift are gone; press feedback is a 0.97 scale.
        className="inline-flex h-[52px] items-center rounded-full bg-gold-400 px-[22px] text-base font-bold text-brand-900 transition-[background-color,transform] duration-150 ease-[cubic-bezier(0.23,1,0.32,1)] active:scale-[0.97] motion-reduce:transition-none [@media(hover:hover)_and_(pointer:fine)]:hover:bg-gold-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700"
      >
        Search
      </button>
    </form>
  )
}
