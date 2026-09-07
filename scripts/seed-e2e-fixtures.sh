#!/usr/bin/env bash
# ---------------------------------------------------------------------------------
# seed-e2e-fixtures.sh — establish the dev-DB fixtures the Playwright suite SKIPS without.
#
# WHY THIS EXISTS
#
#   The suite reports "114 passed, 0 failed" while 14 tests SKIP. A skip is not a pass,
#   and nothing in the summary distinguishes them (#420). Measured 2026-08-01, two of the
#   five skip groups were pure fixture gaps that no code change was needed to close:
#
#     vendor-refund-flow "Issue refund button is hidden on a DRAFT order"
#         The dev DB held 91 orders across PENDING/CONFIRMED/PREPARING/COMPLETED/
#         CANCELLED and NOT ONE in DRAFT, so a real gating assertion — the refund
#         control must not be offered on a draft — had never once executed.
#
#     storefront-flows "promotion banner and discount badge render (STFR-06)"
#         The spec opens `mama-ades-kitchen`, which had 0 promotions and 0
#         announcements. (brixton-village-grill has some, which is why the tables look
#         populated; its own promo lapsed on 2026-07-17 and is dead data.)
#
#   Both are seeded here with times RELATIVE TO NOW. The `ac55-fixture-*` rows this
#   project already lost to an absolute `quarantine_expires_at` are the reason that is a
#   rule and not a preference: a fixture written with a fixed date is a scheduled test
#   outage. See scripts/seed-media-review-fixtures.sh, which this script also runs.
#
# WHAT THIS DELIBERATELY DOES NOT DO
#
#   It does NOT fake a captured payment. vendor-refund-flow's other test issues a real
#   partial refund, and RefundService calls Stripe.Refund.create. STRIPE_API_KEY is empty
#   on this stack, so seeding an order as paymentStatus=CAPTURED with an invented
#   payment_reference would push that test PAST its skip and then fail at the Stripe
#   call — strictly worse than skipping, and a green-looking fixture over a broken path.
#   That test stays skipped until real test-mode keys exist. Its skip message already
#   names the remedy.
#
#   It does not touch application code, and it does not weaken any assertion.
#
# CONFIGURATION (GLOBAL_RULE_6 — nothing environment-varying is hardcoded)
#   PG_CONTAINER    default jtoye-postgres     postgres container name
#   POSTGRES_USER   default from .env, else jtoye
#   POSTGRES_DB     default from .env, else jtoye
#   PROMO_SHOP_SLUG default mama-ades-kitchen  MUST match storefront-flows.spec.ts SHOP_SLUG
#   PROMO_DAYS      default 30                 how far ahead the promo window runs
#   CLEAN_RESIDUE   default 0                  1 = delete cross-tenant rows (see below)
#   SKIP_MEDIA      default 0                  1 = do not run seed-media-review-fixtures.sh
#   RESET_ONBOARDING default 1                 0 = preserve a terminal demo-tenant
#                                              onboarding (verification still fails on it)
#
# EXIT CODES — uniform with this repo's other scripts
#   0 = every fixture is present and in the asserted state
#   1 = the seed ran but verification disagreed
#   2 = VOID — could not evaluate (no container, no shop, psql missing). Never treat as 0.
# ---------------------------------------------------------------------------------
set -uo pipefail

PG_CONTAINER="${PG_CONTAINER:-jtoye-postgres}"
PROMO_SHOP_SLUG="${PROMO_SHOP_SLUG:-mama-ades-kitchen}"
PROMO_DAYS="${PROMO_DAYS:-30}"
CLEAN_RESIDUE="${CLEAN_RESIDUE:-0}"
SKIP_MEDIA="${SKIP_MEDIA:-0}"
RESET_ONBOARDING="${RESET_ONBOARDING:-1}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$REPO_ROOT/.env"

env_value() {
  local key="$1" line
  [ -f "$ENV_FILE" ] || return 1
  line=$(grep -E "^${key}=" "$ENV_FILE" | tail -1) || return 1
  [ -n "$line" ] || return 1
  printf '%s' "${line#*=}"
}

PGUSER="${POSTGRES_USER:-$(env_value POSTGRES_USER || echo jtoye)}"
PGDB="${POSTGRES_DB:-$(env_value POSTGRES_DB || echo jtoye)}"

echo "seed-e2e-fixtures  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  container : $PG_CONTAINER"
echo "  database  : $PGDB (user $PGUSER)"

void() { echo "VOID: $*" >&2; exit 2; }

command -v docker >/dev/null 2>&1 || void "docker not on PATH"
docker inspect -f '{{.State.Running}}' "$PG_CONTAINER" >/dev/null 2>&1 \
  || void "container $PG_CONTAINER is not running — start the stack first"

psql_q() {
  docker exec -i "$PG_CONTAINER" psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 -tAc "$1"
}
psql_run() {
  local out rc
  out=$(printf '%s' "$1" | docker exec -i "$PG_CONTAINER" \
        psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 -q 2>&1); rc=$?
  [ "$rc" -eq 0 ] || { echo "$out" >&2; return 1; }
}

# --- Discover the target shop rather than hardcoding ids ---------------------------
read -r SHOP_ID SHOP_TENANT < <(psql_q \
  "select id || ' ' || tenant_id from shops where slug = '$PROMO_SHOP_SLUG' limit 1;")
[ -n "${SHOP_ID:-}" ] || void "no shop with slug '$PROMO_SHOP_SLUG' — is the dev data seeded?"
echo "  promo shop: $PROMO_SHOP_SLUG ($SHOP_ID, tenant $SHOP_TENANT)"

# A DRAFT order must belong to the tenant the VENDOR signs in as. That is the tenant
# owning the dashboard's shops — the same one discovered above.
DRAFT_ORDER_NUMBER="ORD-E2E-DRAFT-FIXTURE"

# --- 1. DRAFT order ----------------------------------------------------------------
# Idempotent on order_number. Deliberately minimal: the spec only opens it and asserts
# the Issue-refund control is ABSENT, so no items or payment state are required.
#
# WHY created_at IS REFRESHED AND NOT ONLY updated_at
#   The dashboard orders list sorts createdAt desc and paginates at PAGE_SIZE = 20
#   (frontend/app/dashboard/orders/page.tsx:221). The spec only ever reads page 1.
#   Measured 2026-08-03 with only updated_at refreshed: this fixture sat at rank 21
#   of 156 orders — ONE ROW past the page — so the spec skipped with "No DRAFT order
#   seeded" while SIX DRAFT orders existed in the table. Every suite run creates
#   orders at checkout, so the fixture drifts down and the test silently stops
#   running. Refreshing created_at returns it to rank 1 on every re-seed, which is
#   what "run the seeder before the suite" has to mean to be worth anything.
#
# NOTE FOR EDITORS: this prose lives OUT here, not inside the heredoc below. That
# heredoc is UNQUOTED (<<SQL), so backticks in it are command substitution. A comment
# mentioning a backticked identifier ran it, printed "command not found", and SILENTLY
# DELETED the phrase from the SQL that was actually sent. Keep prose in shell comments.
draft_sql=$(cat <<SQL
insert into orders
  (id, tenant_id, shop_id, order_number, status, total_amount_pennies,
   item_count, subtotal_pennies, vat_rate, vat_amount_pennies, delivery_fee_pennies,
   fulfilment_type, customer_name, created_at, updated_at)
values
  (gen_random_uuid(), '$SHOP_TENANT', '$SHOP_ID', '$DRAFT_ORDER_NUMBER', 'DRAFT',
   0, 0, 0, 'STANDARD', 0, 0, 'COLLECTION', 'E2E Draft Fixture', now(), now())
on conflict (order_number) do update set
  status     = 'DRAFT',
  created_at = now(),
  updated_at = now();
SQL
)
psql_run "$draft_sql" || void "DRAFT order seed failed"

# --- 2. Promotion + announcement on the shop the spec actually opens ----------------
# Window is now-1day .. now+PROMO_DAYS so it is unambiguously ACTIVE and cannot lapse
# the way brixton-village-grill's 2026-07-17 promo did.
promo_sql=$(cat <<SQL
delete from shop_promotions
 where shop_id = '$SHOP_ID' and label = 'E2E 20% OFF';
insert into shop_promotions
  (id, tenant_id, shop_id, label, discount_percent, discount_type,
   valid_from, valid_until, active, created_at)
values
  (gen_random_uuid(), '$SHOP_TENANT', '$SHOP_ID', 'E2E 20% OFF', 20, 'PERCENTAGE',
   now() - interval '1 day', now() + interval '$PROMO_DAYS days', true, now());

delete from shop_announcements
 where shop_id = '$SHOP_ID' and title = 'E2E launch offer';
insert into shop_announcements
  (id, tenant_id, shop_id, title, body, valid_from, valid_until, active, created_at)
values
  (gen_random_uuid(), '$SHOP_TENANT', '$SHOP_ID', 'E2E launch offer',
   'New this week — 20% off selected dishes.',
   now() - interval '1 day', now() + interval '$PROMO_DAYS days', true, now());
SQL
)
psql_run "$promo_sql" || void "promotion/announcement seed failed"

# --- 3. Demo-tenant onboarding reset (#686) ----------------------------------------
# onboarding-blocked-flow.spec.ts (Phase 21 ONBD-05) deliberately SKIPS when this
# tenant's vendor_onboarding is LIVE or terminal, to avoid mutating a live demo — and
# that skip is UNDECLARED in scripts/gates/e2e-skip-budget.conf, so the suite fails the
# skip-budget gate the moment anyone drives the demo tenant to LIVE (the 35-13 owner
# gate invites exactly that: the reviewer resolves the parked FHRS gate by hand).
# On 2026-08-30 the fix was a MANUAL reset nobody scripted — the "provisioning only a
# human can perform" anti-pattern this repo already paid for once (V64/#647). This
# section is that reset, scripted and idempotent.
#
# SCOPE: SHOP_TENANT only. Another tenant's onboarding row (…0002 is WITHDRAWN on the
# dev DB) is not this spec's concern and a table-wide sweep would destroy state other
# flows may assert.
#
# Shop.published is DELIBERATELY untouched: demo shops are seed-published and the
# storefront specs depend on that. The state machine's "sole writer of Shop.published"
# rule governs application flows; this restores the pre-onboarding FIXTURE state.
# Envers _aud rows keep their history — append-only audit, not fixture state.
#
# RESET_ONBOARDING=0 preserves a terminal state on purpose (e.g. an owner-gate reviewer
# inspecting LIVE). The verification at the bottom still FAILS in that case — opting out
# of the repair is not opting out of the truth.
TERMINAL_STATES="('LIVE','SUSPENDED','REJECTED','WITHDRAWN')"
onb_status=$(psql_q "select status from vendor_onboarding where tenant_id = '$SHOP_TENANT';")
if [ -z "$onb_status" ]; then
  echo "  onboarding: no row for tenant $SHOP_TENANT — create path open, nothing to reset"
elif ! grep -qF "'$onb_status'" <<< "$TERMINAL_STATES"; then
  echo "  onboarding: $onb_status — re-runnable, untouched"
elif [ "$RESET_ONBOARDING" != "1" ]; then
  echo "  onboarding: $onb_status (terminal) — RESET_ONBOARDING=0, PRESERVED (verification will fail)"
else
  onb_reset_sql=$(cat <<SQL
delete from vendor_onboarding_gate g
 using vendor_onboarding o
 where g.onboarding_id = o.id
   and o.tenant_id = '$SHOP_TENANT'
   and o.status in $TERMINAL_STATES;
delete from vendor_onboarding
 where tenant_id = '$SHOP_TENANT'
   and status in $TERMINAL_STATES;
SQL
)
  psql_run "$onb_reset_sql" || void "onboarding reset failed"
  echo "  onboarding: was $onb_status (terminal) — row + gates deleted; ONBD-05 can run again"
fi

# --- 4. Optional: cross-tenant residue ---------------------------------------------
# Rows whose tenant_id does not match their shop's tenant_id. Left over from a
# cross-tenant RLS test; RLS hides them from the app, but they make the tables look
# populated when they effectively are not. Opt-in, and it reports what it removes.
if [ "$CLEAN_RESIDUE" = "1" ]; then
  removed=$(psql_q "
    with gone as (
      delete from shop_promotions p using shops s
       where p.shop_id = s.id and p.tenant_id <> s.tenant_id returning 1)
    select count(*) from gone;")
  removed_a=$(psql_q "
    with gone as (
      delete from shop_announcements a using shops s
       where a.shop_id = s.id and a.tenant_id <> s.tenant_id returning 1)
    select count(*) from gone;")
  echo "  residue   : removed $removed promotion(s), $removed_a announcement(s) whose tenant != shop's tenant"
fi

# --- 4b. ZERO-RATED product (COR-6) ------------------------------------------------
# WHY THIS FIXTURE EXISTS, AND WHY IT IS THE ARMING STEP, NOT A NICETY
#
#   Measured on this dev DB (2026-09-03): `select vat_rate, count(*) from products group by 1`
#   returns STANDARD 22 — every seeded product. So the checkout's VAT preview matched the
#   server's figure by COINCIDENCE, not by correctness: the client hardcoded 20% and every
#   basket happened to be standard-rated. Any assertion that "the preview equals the
#   confirmation" therefore passed on the broken tree and proves nothing on the fixed one.
#
#   COR-6 makes the preview follow the basket's resolved rate. This row is what lets that be
#   FALSIFIED: a pure zero-rated basket where the old hardcoded arithmetic would show a
#   non-zero VAT and the correct answer is GBP 0.00.
#
#   VAT accuracy of the fixture itself (HMRC VAT Notice 709/1, re-read 2026-09-03): COLD
#   takeaway FOOD is zero-rated; hot takeaway food and drink is standard-rated, and soft
#   drinks are standard-rated even when cold. The fixture is therefore a cold food item, not
#   a chilled drink — a plausible menu line whose zero-rating is genuinely correct.
#
#   PRICE: 1200p clears mama-ades-kitchen's 1000p minimum order on a SINGLE unit, so the
#   browser proof can reach the payment step without a second line diluting the basket's
#   rate. It sits below the 2500p free-delivery threshold on purpose, so the delivery fee is
#   also in the combined gross the VAT is derived from — the exact figure Order.calculateTotal
#   truncates once.
#
#   Idempotent on (tenant_id, sku), which is the live unique index idx_products_tenant_sku.
ZERO_VAT_SKU="${ZERO_VAT_SKU:-E2E-ZERO-VAT-001}"
zero_vat_sql=$(cat <<SQL
insert into products
  (id, tenant_id, shop_id, sku, title, description, ingredients_text, allergen_mask,
   price_pennies, vat_rate, category, available, quantity_in_stock, created_at)
values
  (gen_random_uuid(), '$SHOP_TENANT', '$SHOP_ID', '$ZERO_VAT_SKU',
   'Cold Meat Pie (takeaway)',
   'Served cold for takeaway. Zero-rated for VAT (HMRC Notice 709/1).',
   'Wheat flour, beef, onion, potato', 0,
   1200, 'ZERO', 'Mains', true, 999, now())
on conflict (tenant_id, sku) do update set
  vat_rate          = 'ZERO',
  price_pennies     = 1200,
  available         = true,
  quantity_in_stock = 999;
SQL
)
psql_run "$zero_vat_sql" || void "zero-rated product seed failed"

# --- 5. Media review fixtures (delegated, not duplicated) ---------------------------
#   Same shape as psql_run: quiet on success, the child's FULL output on failure. This used to be
#   `>/dev/null 2>&1`, which left a CI log reading only "did not pass — run it directly" with no
#   way to run it directly against a runner that had already been torn down.
if [ "$SKIP_MEDIA" != "1" ]; then
  media_out=$(bash "$REPO_ROOT/scripts/seed-media-review-fixtures.sh" 2>&1); media_rc=$?
  if [ "$media_rc" -eq 0 ]; then
    echo "  media     : OK (seed-media-review-fixtures.sh)"
  else
    echo "$media_out" >&2
    echo "FAIL: seed-media-review-fixtures.sh did not pass (exit $media_rc) — its output is above." >&2
    exit 1
  fi
fi

# --- Verify by the SPECS' OWN predicates, not by row counts ------------------------
# A row in the wrong state would satisfy a count. These mirror what each spec looks for.
#
# THE DRAFT CHECK USED TO BE A ROW COUNT, AND THAT MADE THIS "PASS" A LIE.
#   It asserted `count(*) where status = 'DRAFT' >= 1` — a row existing ANYWHERE in
#   the table. The spec does not read the table; it reads PAGE 1 of the dashboard
#   orders list, sorted createdAt desc, 20 rows. Measured 2026-08-03: 6 DRAFT orders
#   present, newest at rank 21 of 156, spec skipped, and this script printed
#   "can now assert non-vacuously". A structural check green over a dead path — the
#   exact failure this repo keeps re-finding.
#   The check below now asks the question the SPEC asks: is the fixture reachable on
#   the page the spec actually looks at?
ORDERS_PAGE_SIZE="${ORDERS_PAGE_SIZE:-20}"   # mirrors PAGE_SIZE in frontend/app/dashboard/orders/page.tsx
draft=$(psql_q "select count(*) from (
    select status, row_number() over (order by created_at desc) as rank
      from orders where tenant_id = '$SHOP_TENANT'
  ) ranked
  where status = 'DRAFT' and rank <= $ORDERS_PAGE_SIZE;")
promo=$(psql_q "select count(*) from shop_promotions
  where shop_id = '$SHOP_ID' and active
    and valid_from <= now() and valid_until > now();")
ann=$(psql_q "select count(*) from shop_announcements
  where shop_id = '$SHOP_ID' and active
    and (valid_from is null or valid_from <= now())
    and (valid_until is null or valid_until > now());")
# onboarding-blocked-flow's own skip guard, asked of the DB: a LIVE/terminal row for the
# vendor tenant means ONBD-05 will skip UNDECLARED. Expect 0 such rows.
onb_terminal=$(psql_q "select count(*) from vendor_onboarding
  where tenant_id = '$SHOP_TENANT' and status in $TERMINAL_STATES;")
# COR-6: the zero-rated product must be VISIBLE to the storefront, not merely present. The
# public catalogue only returns available rows on a published shop, so an unavailable row would
# satisfy a count and still leave the preview assertion unarmed.
zero_vat=$(psql_q "select count(*) from products p join shops s on s.id = p.shop_id
  where p.sku = '$ZERO_VAT_SKU' and p.vat_rate = 'ZERO' and p.available and s.published;")

echo "  DRAFT orders ON PAGE 1 (top $ORDERS_PAGE_SIZE by created_at)  : $draft  (expect >= 1)"
echo "  ACTIVE, in-window promotions on the shop     : $promo  (expect >= 1)"
echo "  ACTIVE, in-window announcements on the shop  : $ann  (expect >= 1)"
echo "  LIVE/terminal onboarding rows for the tenant : $onb_terminal  (expect 0 — else ONBD-05 skips undeclared)"
echo "  VISIBLE zero-rated products (COR-6 arming)   : $zero_vat  (expect >= 1 — else the VAT-preview assertion is vacuous)"

if [ "$draft" -ge 1 ] && [ "$promo" -ge 1 ] && [ "$ann" -ge 1 ] && [ "$onb_terminal" -eq 0 ] \
   && [ "$zero_vat" -ge 1 ]; then
  echo "PASS: vendor-refund-flow's DRAFT test, storefront-flows' STFR-06 and onboarding-blocked-flow's ONBD-05 can now assert non-vacuously."
  echo "      COR-6: a VISIBLE zero-rated product exists, so 'checkout preview == confirmation'"
  echo "      is falsifiable rather than a coincidence of an all-STANDARD catalogue."
  echo "NOTE: vendor-refund-flow's REFUND test stays skipped by design — it needs real"
  echo "      Stripe test-mode keys (STRIPE_API_KEY), not a fixture."
  exit 0
fi

echo "FAIL: the seed ran but the fixtures are not in the asserted state." >&2
exit 1
