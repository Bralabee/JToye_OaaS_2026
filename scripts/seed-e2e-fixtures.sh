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

# --- 3. Optional: cross-tenant residue ---------------------------------------------
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

# --- 4. Media review fixtures (delegated, not duplicated) ---------------------------
if [ "$SKIP_MEDIA" != "1" ]; then
  if bash "$REPO_ROOT/scripts/seed-media-review-fixtures.sh" >/dev/null 2>&1; then
    echo "  media     : OK (seed-media-review-fixtures.sh)"
  else
    echo "FAIL: seed-media-review-fixtures.sh did not pass — run it directly for detail." >&2
    exit 1
  fi
fi

# --- Verify by the SPECS' OWN predicates, not by row counts ------------------------
# A row in the wrong state would satisfy a count. These mirror what each spec looks for.
draft=$(psql_q "select count(*) from orders
  where tenant_id = '$SHOP_TENANT' and status = 'DRAFT';")
promo=$(psql_q "select count(*) from shop_promotions
  where shop_id = '$SHOP_ID' and active
    and valid_from <= now() and valid_until > now();")
ann=$(psql_q "select count(*) from shop_announcements
  where shop_id = '$SHOP_ID' and active
    and (valid_from is null or valid_from <= now())
    and (valid_until is null or valid_until > now());")

echo "  DRAFT orders for the vendor tenant           : $draft  (expect >= 1)"
echo "  ACTIVE, in-window promotions on the shop     : $promo  (expect >= 1)"
echo "  ACTIVE, in-window announcements on the shop  : $ann  (expect >= 1)"

if [ "$draft" -ge 1 ] && [ "$promo" -ge 1 ] && [ "$ann" -ge 1 ]; then
  echo "PASS: vendor-refund-flow's DRAFT test and storefront-flows' STFR-06 can now assert non-vacuously."
  echo "NOTE: vendor-refund-flow's REFUND test stays skipped by design — it needs real"
  echo "      Stripe test-mode keys (STRIPE_API_KEY), not a fixture."
  exit 0
fi

echo "FAIL: the seed ran but the fixtures are not in the asserted state." >&2
exit 1
