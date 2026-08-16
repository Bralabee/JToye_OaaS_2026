# Deferred items — Phase 31

Out-of-scope discoveries logged rather than fixed, per the executor scope boundary.

---

## DEF-31-11-01 — JSX transform silently deletes a space after an inline element

**Found by:** plan 31-11, while reading rendered output during a break arm.
**Status:** FIXED on the two pages 31-11 owns. **Not fixed elsewhere** — out of scope.

### The mechanism, measured with a four-arm control

When the JSXText node that **follows** an inline element contains an HTML entity
anywhere within it, the transform drops that text node's **leading space**. The entity
does not need to be adjacent — in the real case it was `&apos;` several words later.

| Arm | Shape | Rendered HTML |
|---|---|---|
| control | inline element, no entity in the following text | `</code> so your` — space kept |
| **break** | inline element, `&apos;` later in the same text node | `</code>so your` — **SPACE LOST** |
| control | `&apos;` only *before* the inline element | `</code> so your` — space kept |
| control | explicit `{" "}` at the boundary, entity present | `</code> so your` — space kept |

### Why it matters and why nothing caught it

The **source retains the space**, so code review cannot see the defect; it exists only in
the delivered HTML. It shipped three run-together phrases into a legal page
(`js.stripe.comso your card details`, `<shop>there is one item`,
`Clearing site datain your browser`) and no gate in this repository noticed.

It is **systematically reachable**: this project's own `react/no-unescaped-entities`
lint rule *requires* `&apos;` in JSX text, so any paragraph that mixes an inline
element with an apostrophe can hit it.

### Prevalence elsewhere (approximate, instrument validated)

A static scan of `frontend/app` and `frontend/components` (`.tsx`, tests excluded) for
the boundary shape, checking whether the following JSXText run contains an entity:

```
APPROX suspect boundaries: 3 across 2 files
  2  components/marketing/business-model-guide.tsx
  1  components/marketing/competitive-teardown.tsx
```

**The scanner was controlled before the number was trusted**: run against a fixture
holding one bad variant and two good ones (no-entity, and explicit `{" "}`), it
reported exactly `1`. Positive control fires, both negative controls stay silent.

The count is **approximate** — it is a line-based heuristic, not a JSX parser, so treat
it as a magnitude and confirm each hit by rendering. Both hits are marketing components,
neither owned by this phase.

### Recommended fix

Explicit `{" "}` at the boundary (proven to survive in the fourth arm). The durable
guard is a rendered-output assertion, not a source grep — 31-11 added one to both of its
pages:

```ts
el.innerHTML.match(/<\/(?:code|span|a|strong|em|b|i)>[A-Za-z]\w*/g)
```

A repo-wide version of that assertion would be the right home for this, but it belongs
to whoever owns those marketing surfaces rather than to this phase.
