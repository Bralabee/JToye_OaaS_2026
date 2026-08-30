# The Horizontal Layout Contract

**Four content-width tiers, the peer measurement behind each number, every deliberate exception,
and an honest statement of what is and is not watching them.**

Machine-readable companion: [`layout-tiers.tsv`](layout-tiers.tsv) — the surface-to-tier manifest
the gate reads. That file is the enforcement; this file is the explanation. They are deliberately
separate: a manifest cannot carry a reason, and a paragraph cannot red a pull request.

---

## 1. Why this document exists

Phase 35 was opened on a complaint about wasted screen space. The finding underneath it was
narrower and more useful than the complaint:

> No document in this repository stated a content-width standard. The 1400px cap every dashboard
> page inherited was the stock shadcn/ui `container` scaffold block, shipped verbatim in
> `frontend/tailwind.config.ts` and never chosen. The finding was never *"1400 is the wrong
> number"* — it was that nobody could say where the number came from.

Three properties of that inherited block are worth naming, because each is a distinct defect and a
replacement number alone fixes none of them:

1. **It was never decided.** It arrived with a scaffold. No commit, comment or document argued for it.
2. **It was applied once and inherited everywhere.** A single call site — the band element in
   `frontend/components/dashboard/dashboard-shell.tsx` — wrapped **every** route under
   `/dashboard`, so a resource index with eight columns, an order-detail read, a sequential wizard
   and a marketing page all rendered inside the same box. Those are four different content types
   with four different right answers.

   *No route count is quoted here on purpose.* Several places in this tree assert "21 dashboard
   routes"; measured at the time of writing, `app/dashboard` holds **18** `page.tsx` routes, with
   no dashboard route group elsewhere. The figure is inherited rather than measured — the same
   defect as the 1400 — and repeating it in the document that exists to stop that happening would
   be self-defeating. The load-bearing claim needs no count: it is **one** band element, and the
   inheritance is total.
3. **Nothing watched it.** A number with no declared home and no gate erodes silently, which is
   exactly how it survived the life of the product.

So the fix is not a different literal in the same place. It is a declared contract whose numbers
arrive with the measurement that justifies them, a vocabulary the DOM can be queried on, and a gate
that fails a build. This document is the first of those three, and the only one a person reads.

---

## 2. The four tiers

| Tier | Value | Applies to | Peer measurement behind the number |
|---|---|---|---|
| **Shell** | **1700px** | the dashboard chrome — one band, inherited by every authenticated route | Stripe Dashboard `--Chrome-maxWidth` **1690** (measured); Square docs shell **1720**; Square design-site body **1680** |
| **Index** | **fluid to the Shell cap** — no further cap | resource indexes: products, orders, customers, shops, finance, marketing, kitchen, staff, webhooks, the approvals queue, the media review queue, the dashboard home | Shopify Polaris prescribes a **full-width page** for "lists of data that have many columns"; IBM Carbon ships `--full-width` as an escape from its own grid; GitLab offers a fluid layout preference; Lightspeed's app shell is uncapped outright |
| **Detail** | **1100px** | order detail, the product-import wizard, vendor onboarding | Linear's detail ladder tops out at **1136**; Square's content ladder tops out at **1016**; Lightspeed's content column is **1100** |
| **Marketing** | **1280px** | the landing page, `/for-operators`, `/business-model-guide`, `/competitive`, the four `/legal/*` policy routes, the public `/shop` directory, and both shared public rails | Stripe's marketing pages cap at **1264** |
| *Prose* | *68ch — unchanged* | body copy inside a policy page | already correct in the tree before this phase; explicitly out of scope |

**Prose is not a fifth tier and must not become one.** It is a typographic measure (45–75 characters
a line), held on the reading columns *inside* the policy band, and it is independent of whichever
tier the band around it takes. Widening the band does not widen the prose — which is the whole
reason the band could be widened at all.

### 2.1 The convergent cluster — why the Shell is 1700 and not 1400

Three independently measured application shells sit **within 40px of each other**: Stripe's own
dashboard at 1690, Square's docs shell at 1720, Square's design-site body at 1680. That band is
roughly **88% of a 1920px screen and two thirds of a 2560px one**.

That last figure is the finding, not a coincidence worth glossing. The original complaint asked for
"at least two thirds of any screen's width". The measured industry ceiling for a fixed application
cap turns out to be the same number. The instinct is not a preference the product is indulging; it
reproduces what the closest measurable peers already do.

1700 sits inside that cluster rather than at either edge. It is not any one product's number, which
is deliberate — matching Stripe's 1690 exactly would imply a precision the evidence does not carry.

### 2.2 The Index tier is a rule, and the rule is an absence

Data-dense resource indexes are the one case where the documented advice is to abandon the cap
entirely rather than raise it. Polaris, Carbon, GitLab and Lightspeed all say some version of the
same thing (§2's table). So the mature pattern is **not one width** — it is a cap for chrome plus an
opt-out for tables.

An Index-tier surface therefore applies **no max-width class at all**. It takes whatever the Shell
band allows, and adds nothing.

**But it still declares itself.** Every Index surface carries `data-width-tier="index"` in the DOM.
"Uncapped" implemented as an absence is a contract no assertion can distinguish from a forgotten
cap: a spec measuring an index page at the shell width cannot tell a deliberate Index tier from
someone who simply forgot. The attribute turns the tier into a declaration a test can find and
falsify. This is **ORCH-01/03's sibling decision ORCH-03 (orchestrator decision, 2026-08-29)**, and
it is load-bearing rather than decorative — the branch-parity and manifest assertions both read it.

#### The delivery-log exception — tiered by content shape, not route shape

`frontend/app/dashboard/webhooks/[id]/page.tsx` is an `[id]` route and is tiered **Index**, not
Detail. Every other `[id]` route in the dashboard is Detail, so this looks wrong and will be
"corrected" back by the next reader unless it is written down here.

The reason to open that page is the **delivery log** — a wide, multi-column, timestamp-heavy table
with its own horizontal scroll region. Capping it to 1100 would make it scroll horizontally *more*
than it does today, which is a strict regression on the page's primary job. Polaris's own rule is
"full-width for lists of data that have many columns", and this is one.

**Tier by content shape, not route shape.** This is precisely the case that proves a tier has to be
*declared* rather than inferred, because route-shape inference gets it wrong.

### 2.3 What is NOT evidence — recorded so it is not over-claimed later

The peer research self-corrected mid-task and struck one value it could not verify. Two limits on
the evidence above survive that correction and belong in this document rather than in a planning
archive:

- **Square's *Market* design system has no public spec** (site, GitHub repo and npm package all 404)
  and the **Square Dashboard is Cloudflare-challenged**. Every Square figure quoted here is from a
  **substitute surface** — the docs shell and the design site — not the POS back-office itself.
- **Toast yields no number from any route.** Buffet is a client-rendered SPA, the npm scope is empty
  against a working control, the GitHub org holds migration stubs and the back-office 403s.

The closest **true** peers this repository could measure are **Stripe, Lightspeed, GitLab and
Shopify**. Anyone strengthening or challenging these numbers should start there, and should not
cite Square or Toast as closest-domain evidence.

---

## 3. THE CEILING RULE — a tier is a ceiling, not a target

**A tier is the widest a surface may be, never the width it must become.** A surface that is already
narrower for a stated ergonomic reason keeps its measure and is recorded as an exception. It does
**not** get a tier attribute, because an attribute would claim an assignment nobody made.

This is the rule that stops the contract being applied mechanically into a regression, and it is the
single most likely thing for a future reader to get wrong: the contract reads like a set of targets
and it is not.

**Demonstrated rather than asserted.** The Stripe Connect outcome panel was measured at **672px at
1440, 1920 and 2560 — in the pre-phase tree, in an intermediate tree, and in the shipped tree.** It
does not follow the band and it did not move when the tier landed around it.

---

## 4. Exceptions and deliberate non-changes

Everything in this section was **examined and left alone**. None of it is an omission. An unstated
boundary reads as covered, which is why surfaces where nothing ships wrong are the ones most worth
writing down — silence about them is indistinguishable from never having looked.

Every row below also appears in [`layout-tiers.tsv`](layout-tiers.tsv) as an explicit `N/A` claim,
so the gate asserts these surfaces declare **no** tier. The manifest holds the enforcement; this
table holds the reason.

### 4.1 Dashboard

| Surface | Measure | Why it keeps it |
|---|---|---|
| `app/dashboard/payments/connect/connect-outcome.tsx` | 672px | The Stripe Connect return/refresh outcome — three paragraphs, shared by both routes. Measured unchanged across all three tree states (§3) |
| Every Radix dialog (`components/ui/dialog.tsx` and its callers) | 672px and similar | **Portal-rendered, so the page tier cannot reach them anyway.** Their width is a modal-ergonomics decision, not a layout-contract one. Measured: **five such caps across four index-tiered pages** (orders carries two). A file-scoped no-cap gate would red on every one of them, which is why the Index check is scoped to the **declaring element's own opening tag** rather than to the file |
| Table-cell truncation clamps; empty-state copy measures | — | Character-measure decisions, not layout-contract ones |
| `app/dashboard/media/review/page.tsx` | — | A thin wrapper; its tier is declared by the component it renders. Recorded so the page's own empty tier set reads as delegation rather than as a gap |

### 4.2 Public and storefront — nothing ships wrong on any of these

Each is the ceiling rule reached from the public side: already narrower than the Detail tier for a
stated ergonomic reason.

| Surface | Measure | Why it keeps it |
|---|---|---|
| `app/shop/[slug]/cart/page.tsx` | 672px | A linear basket-review step |
| `app/shop/[slug]/checkout/page.tsx` | 672px | Address, payment and confirm — sequential checkout steps |
| `app/shop/[slug]/orders/[orderNumber]/page.tsx` | 512px | A customer receipt |
| `app/shop/orders/orders-client.tsx` | 512px | A phone-first order-history list |
| `app/track/page.tsx` | 512px | A two-field guest lookup form |
| `app/unsubscribe/unsubscribe-content.tsx` | 512px | A one-click opt-out panel |
| `app/legal/page.tsx` — the `/legal` index | 768px | **A deliberate reading column.** A definition list of company facts plus a policy list of **four** links (measured; earlier notes in this phase said five and six). Widening a four-item list to 1280 produces a mostly-empty band — the defect this phase exists to remove, reintroduced on a different page. Examined by ORCH-06 and left |

### 4.3 The public `/shop` directory stays Marketing, and does not go fluid

**ORCH-01 (orchestrator decision, 2026-08-29).** The Index tier means the **dashboard** routes only.
`/shop` keeps the Marketing width. Three reasons:

1. It is a customer-facing **card browse** surface, not a dense table, so the Polaris "many columns"
   justification does not apply to it.
2. Going fluid turns a zero-file change into roughly three files.
3. It is a **public, indexed** surface, so the change would force SEO and CLS re-measurement that
   nothing about the complaint required.

This one is flagged **owner-visible if wrong**: it is a one-line difference, and it is surfaced here
rather than buried so it can be looked at deliberately.

### 4.4 The `/shop/[slug]` family — 896px, held together, widening deferred

The storefront menu renders at 896px across `shop-detail-client.tsx`, its loading skeleton and its
not-found page. Widening it to the Detail tier was considered and **deferred**: 896 is within
prose-measure territory for a scannable list of dish rows, the surface is absent from the phase's
measured baseline, and the change is many sites in one file — the highest-churn edit available for
the least-evidenced gain.

**They have no tier assignment, and that is the point.** A tier attribute would claim an assignment
nobody made. They are instead bound to each other as a **parity family** in the manifest, so all
three must agree on the same band token.

That binding is not hypothetical. Before this phase the skeleton rendered at 1280 while the content
replacing it rendered at 896: **the page narrowed by 384px the moment real content arrived.** That
was fixed, and the fix is now guarded — because when the fix was first made, reverting it produced no
red anywhere in the repository.

### 4.5 Two surfaces that carry an active trap

- **`components/public/public-shell.tsx` — DO NOT ADD A CAP HERE.** Its `<main>` is deliberately
  width-free so children own their own bands. Two independent reasons, both measured: `/shop` is a
  **separate layout tree**, and the policy-page component **nests inside** this one, so a cap here
  would silently narrow surfaces this phase never examined. The concrete consequence: the
  unsubscribe flow's no-horizontal-overflow assertion at 375px is at risk only if this shell gains a
  cap.
- **`app/shop/[slug]/not-found.tsx`** — the third member of the 896px parity family. A check
  hard-coded to compare only the skeleton and the client would leave this file free to drift while
  reporting the route as covered.

---

## 5. THE DISPLACED-GOODS RECORD — what this contract makes narrower

**A width standard that documents only its widenings is not a standard.** This is the one number a
future reader is most likely to challenge, so it is stated plainly, with the measurement rather than
with arithmetic.

The Detail tier makes three live dashboard surfaces — **order detail, vendor onboarding and the
product-import wizard** — narrower than they are on `main` today. Measured in a real browser, against
stylesheets generated from each tree's own committed Tailwind config:

| Viewport | On `main` today | Without the Detail tier | **Shipped** | Δ vs today | Δ vs untiered |
|---|---|---|---|---|---|
| 1440 | 1120 | 1120 | **1100** | **−20** | −20 |
| 1920 | 1336 | 1600 | **1100** | **−236** | **−500** |
| 2560 | 1336 | 1636 | **1100** | **−236** | **−536** |

**Read the middle column before judging the narrowing.** The choice was never "1336 or 1100". Without
the Detail tier these three surfaces would have inherited the Shell band and shipped at **1600 and
1636** — 264 to 300px *wider* than today, and further from the peer cluster in the wrong direction.
The real range is "1636 or 1100", and 1100 is the defensible end of it.

**One correction worth keeping**, because it is the kind of number that gets copied forward: at 1920
the untiered value is **1600, not 1636**, because the 1700px Shell cap does not begin to bind until
roughly **1956px** of viewport. So the jump the Detail tier prevents is **500px at 1920** and 536px
at 2560 — not "about 500" at both.

**The justification has to carry that 236px.** These are reading and form surfaces, not data grids.
Detail and reading columns cluster tightly at **1016–1136px** across the closest measurable peers,
and prose-measure guidance is why. A 1336px order detail is already past that cluster; 1100 sits
inside it, level with Lightspeed and 36px under Linear. The order detail is a labelled key–value read
plus a line-item list; onboarding is a sequential gate form; the import wizard is a tabbed upload
form. None of the three gains from width.

**Do not describe 1440 as unchanged.** 1440 is the common laptop width, and all three surfaces lose
**20px** there. It is small, it is real, and it is recorded rather than left to be tripped over.

**If the narrowing is ever rejected, the remedy is `DETAIL_MAX_PX` in `frontend/lib/layout-widths.ts`
— one number, one place. Do NOT respond by removing the tier**, which would silently hand these
surfaces the 1600–1636px Shell band instead.

---

## 6. How a tier is applied — two shapes, and which to use is not taste

**IN PLACE — preferred.** The surface already has a band element carrying auto margins and a
max-width. Swap the max-width class for the tier's map entry and add the tier attribute to that
**same** element. No new DOM node, so nothing in the page's existing layout, motion, scroll-reveal,
CLS or bounding-box behaviour has a new element to notice. On animated surfaces this is not a
preference but a requirement — an inserted wrapper is exactly the shape that moves those
measurements.

**WRAPPER — only where no band element exists.** `ContentTier` in
`frontend/components/layout/content-tier.tsx` adds a DOM node, which is the shape that *can* move
things. It is the fallback, never the default.

**Every page-level render branch carries the tier — not just the loaded one.** A page with loading,
error and loaded returns has three roots. An untiered branch renders at the Shell content box, so
the page visibly jumps as the request settles. Nested sub-components inside a tiered branch must
**not** carry a tier: that is a cap inside a cap.

**Import the vocabulary; never write a tier class literal.** `WIDTH_TIER_CLASS` is the only supported
route, for tests as much as for components — a test that restates a literal keeps passing after the
vocabulary changes, which is the same drift hazard as a component doing it.

**Note one merge behaviour, measured rather than assumed:** `tailwind-merge` does not recognise the
tier keys as members of its `max-w` conflict group, so it will **not** resolve a caller's `max-w-*`
against a tier class — both survive, and the surface ends up with two live caps. If a surface needs
to override a tier, remove the tier rather than layering over it.

---

## 7. Where the numbers live, where the class names live, and why those are different directories

**This is the single most expensive thing for a newcomer to rediscover, and its failure is silent.**

| What | Where | Why there |
|---|---|---|
| The **numbers** (`SHELL_MAX_PX`, `DETAIL_MAX_PX`, `MARKETING_MAX_PX`, the `WidthTier` union) | `frontend/lib/layout-widths.ts` | Three different loaders read this file — jiti (Tailwind's TypeScript config reader), webpack (Next) and esbuild (Playwright). It therefore takes **no imports at all**, and it must be imported by a **relative** specifier: the `@/` alias does not resolve under jiti |
| The **class-name literals** | `frontend/components/layout/content-tier.tsx` | **Tailwind's content globs cover `pages/`, `components/` and `app/` — not `lib/`** |

**The consequence of getting that wrong.** A utility class name written in `lib/` is never generated.
The failure mode is: the class is present in the markup, the build is clean, no test complains, and
**the element renders with no cap at all**. Measured in both directions during this phase — including
once by accident, when a measurement harness reported a surface as uncapped purely because the class
it was testing had never been scanned into the stylesheet.

So: numbers in `lib/`, strings assembled from them in `components/`, and the tier classes appear as
literals in exactly one file in the tree.

---

## 8. Coverage — stated without overstatement

**This section is deliberately unflattering.** A document that claims an instrument is watching a
tier is worse than one that admits none is, because the first stops anyone looking.

### 8.1 Blocking every pull request

- **All seven assertions of the static contract gate**, `scripts/check-layout-width-contract.sh`,
  wired into the Operational Contracts job. It is **static by construction** — it reads TypeScript,
  one Tailwind config and one TSV out of the checkout, starts no browser, runs no build, touches no
  database and makes no network call — so it says the same thing on a hosted runner as it does
  locally, and the job carries no conditional. Between them the seven assertions cover: the tier
  literals appearing exactly once; the retired `container` token staying gone; both halves of the
  Tailwind retirement; the parity family agreeing; the config reading the module rather than
  restating numbers; the manifest and the tree agreeing in **both** directions; and the Index tier's
  no-cap property at the declaring element.
- **The Marketing tier's rendered band width**, measured in a real browser at 1440, 1920 and 2560 on
  `/`, `/for-operators`, `/business-model-guide` and `/legal/privacy` — the `@stack-free` half of
  `frontend/e2e/layout-width-contract.spec.ts`, run as its own step in the public-surfaces E2E job
  in `.github/workflows/ci-cd.yaml`. It can be per-PR because those routes need only the frontend,
  with no backend and no login.

**Adjacent, and not the same claim.** `frontend/e2e/public-layout.spec.ts` and
`frontend/e2e/public-a11y.spec.ts` also run per-PR, over a wider route set — `/`, `/shop`,
`/for-operators`, `/track`, `/business-model-guide`, `/competitive` and all five `/legal` routes —
and they block a merge. But they assert **no horizontal overflow and WCAG 2.1 AA conformance**, not
band width: measured with a control, neither spec references the tier attribute at all. So a gross
regression on `/competitive` or `/shop` would red per-PR; a wrong band width on those two routes
would not.

### 8.2 Covered by a spec that no current tree executes

**The Shell, Index and Detail tiers' rendered band width, and the landing route's desktop CLS arm.**

Those assertions exist, are correct, have recorded fail directions — and **run nowhere**. The
dashboard tiers need a Keycloak vendor login against a live stack, which the per-PR job does not have
and must not acquire; their only lane is the full-suite nightly run, and **issue #683 records that
lane as dark**. It is the project's only full-suite E2E instrument, so while it is red no merge has
full-suite evidence on any tree.

**The honest phrasing is "covered by a spec that no current tree executes". It is never "covered
nightly".** #683 is named here so the claim expires visibly: when that lane is fixed, this paragraph
becomes wrong and can be corrected.

**Same root cause, separately tracked: #686.** The end-to-end skip-budget gate is wired only into
that same dark lane, so it currently fires nowhere either.

**What the per-PR substitutes do and do not prove.** The static gate and the component-level jsdom
suites prove a tier is **declared** and that its class is **applied**. Neither can measure a rendered
width, and neither proves the contract's *value* is right — a suite that reads the tier from the
vocabulary module asserts application, never the number. The instruments for the number are the peer
evidence recorded beside each constant and the static gate's single-home property.

### 8.3 One accessibility rule the per-PR gate cannot evaluate at all

Widening the Index tier changes **when** a dashboard table's horizontal scroll region overflows, and
an unfocusable scroll region is a serious axe violation (`scrollable-region-focusable`) that this
repository has already had to fix once.

**The per-PR accessibility gate cannot see that rule on these surfaces.** It is runtime-geometric —
axe compares an element's scroll width against its client width — and **jsdom reports zero for both**.
Measured, not assumed: a deliberately overflowing element in jsdom reports `scrollWidth=0` and
`clientWidth=0`, so the comparison the rule depends on can never be true there. The browser-based
per-PR accessibility spec is real, but it scans **public** routes; the dashboard is authenticated and
outside it.

So the scroll-region rule on the widened dashboard surfaces is covered by neither per-PR instrument.
The structural affordance (a focusable region with an accessible name) is asserted in jsdom and does
block a merge; whether that region actually overflows at a given width is not checked anywhere that
currently runs.

---

## 9. Where this is enforced

| Artefact | Role |
|---|---|
| [`docs/architecture/layout-tiers.tsv`](layout-tiers.tsv) | The surface-to-tier manifest. Carries `N/A` and parity rows as well as tier rows, so "examined and left alone" is expressible |
| `scripts/check-layout-width-contract.sh` | The gate. Seven assertions over the manifest and the tree; exits 0 / 1 / 2, failing **closed** on an empty or malformed manifest |
| `frontend/lib/layout-widths.ts` | The numbers, each with its peer measurement in its own docblock |
| `frontend/components/layout/content-tier.tsx` | The class vocabulary and the wrapper shape — the only file allowed to spell a tier class literal |
| `frontend/e2e/layout-width-contract.spec.ts` | The browser instrument. Marketing half blocking per-PR; dashboard half per §8.2 |

**If you change a tier value**, change it in `frontend/lib/layout-widths.ts` and nowhere else, and
update the peer justification in the same docblock. A number that loses its justification is how the
1400 survived unexamined for the life of the project — and the point of this whole exercise is that
it cannot happen twice.

---

*This document deliberately carries no `file:line` citations. Line numbers drift, and a citation that
points at the wrong line is worse than none: a reader follows it, sees something unrelated, and
concludes the document is right and their own understanding is wrong. Line references recorded during
this phase's own planning had already drifted by the time this was written.*
