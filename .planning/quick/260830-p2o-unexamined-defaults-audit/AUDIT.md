# The unexamined-defaults audit — 2026-08-30 (quick-260830-p2o)

The deliberate hunt for the class the 1400px container belonged to: **library defaults
load-bearing on visible surfaces that nobody declared, at states and viewports outside
the instrument envelope.** Run against the live compose stack (4/4 FRESH at `9cfdbe0d`).
Probe scripts and screenshots in the session scratchpad; every claim below is a measured
number or a `git log` fact, and the overflow instrument was shown able to FAIL before any
clean result was recorded (injected 2000px div → over=+560, fires=true).

## Findings — classified

| # | Surface / default | Evidence | Class | Disposition |
|---|---|---|---|---|
| 1 | **Tablet portrait (768×1024): full desktop sidebar** — Tailwind's `md:768` default decides which chrome a tablet gets. Orders table compressed until the Order ID wraps to 3 lines; ~256px of 768 spent on nav | screenshot `grid_dashboard_orders-768.png`; overflow 0 (functional, cramped) | OWNER-CALL | **#699** |
| 2 | **`TOAST_LIMIT = 1`** (`hooks/use-toast.ts:5`, shadcn default): a new toast silently REPLACES the current one | measured live: error toast shown, second toast fired, toast count stayed **1** — the first was displaced. A mutation error (whose only signal is the toast) can vanish before it is read | OWNER-CALL | **#700** |
| 3 | **Dialog widths/density undeclared**: 12 `<DialogContent>` with no width override ride shadcn's **512px `max-w-lg`** (marketing ×2, shops, customers, onboarding ×2, approvals ×3, media, products edit legacy site, SecretReveal); products create was widened to 672px but runs **28 fields through a 1564px form inside an 810px box** | static sweep + measured dialog probe `{width:672, scrollH:1564, viewportH:900, fields:28}`; screenshot `dialog-products-1440.png` | OWNER-CALL | **#701** |
| 4 | **Dashboard products table: titles unclamped** — a 224-char title wraps to **6 lines**, quadrupling its row height | screenshot `longname-dashboard.png`; probe product inserted by SQL and deleted after (`AUDIT-LONGNAME-SKU`, delete verified count 0) | FIX | **#702** |
| 5 | Storefront long-title handling | same 224-char product: card **truncates with ellipsis**, grid intact (`longname-storefront.png`) | N/A — examined, correct | — |
| 6 | Horizontal overflow at the untested viewports | 7 routes × {768×1024, 1024×768}: **over=0 on all 14 probes**, instrument armed first | N/A — examined, clean | — |
| 7 | Small-laptop band (1024×768) overall read | dashboard renders 4 tiles + charts correctly (`grid_dashboard-1024.png`) | N/A | — |
| 8 | Toast auto-dismiss | `ToastProvider` carries no `duration` → Radix's **5000ms** default governs; measured: toast count 0 at +6.5s | RATIFY — works, but the 5s figure is inherited, worth one line in a contract | folded into #700 |
| 9 | `TOAST_REMOVE_DELAY = 1000000` (~16.7min) | governs post-dismiss state cleanup only, not visibility — invisible to users given (8) | N/A | — |
| 10 | Sheet drawer `sm:max-w-sm` (384px, shadcn default) | mobile nav drawer; unremarkable at 390 viewport | RATIFY-someday | — |
| 11 | Vendored ui components never edited since add (`git log --follow` = 1 commit): `checkbox`, `dropdown-menu`, `label`, `sheet`, `skeleton`, `toaster` | pure upstream defaults in production; inventory recorded so the next audit diffs against upstream instead of rediscovering | inventory | — |
| 12 | Already-ratified, confirmed out of scope: palette (#232/#451), Work Sans, width tiers + `/track` 512 (Phase 35 ledger), `hoverOnlyWhenSupported` (#503 — itself a caught member of this class) | — | — | — |

## What was NOT covered (named, not implied)

- Kitchen display with a hostile-length title inside an ORDER (the probe product was never
  ordered); locales/RTL; ultrawide (3440); dense data (200+ orders) beyond what the seed
  holds; motion states (a screenshot cannot verify motion — standing trap).

## The pattern to keep

Three of four real findings are **breakpoint/limit constants a library chose** (`md:768`,
`TOAST_LIMIT 1`, `max-w-lg`). The width contract fixed one member of this class; #699–#701
are the owner-ratification queue for the next three. The audit method (declare-or-ratify,
instrument-armed probes at unused viewports/states) is repeatable — re-run it after any
major vendored-component refresh.
