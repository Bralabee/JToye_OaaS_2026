// Consolidate pass-1 + pass-2 persona findings into one deduplicated catalogue.
// Reads only. Writes only into .planning/ux-persona-test-20261003-pass2/consolidated/.
// Every input finding must land in exactly one cluster or the goods list; asserts that.
"use strict";
const fs = require("fs");
const path = require("path");

const REPO = "/home/sanmi/IdeaProjects/JToye_OaaS_2026";
const P1DIR = ".planning/ux-persona-test-20261003";
const P2DIR = ".planning/ux-persona-test-20261003-pass2";
const OUT = path.join(REPO, P2DIR, "consolidated");
fs.mkdirSync(OUT, { recursive: true });

const SEV_RANK = { blocker: 4, major: 3, minor: 2, polish: 1, positive: 0 };
function die(msg) { process.stderr.write(msg + "\n"); process.exit(1); }

// ---------------------------------------------------------------- STEP 1: pass-1 normalise
const P1_AREA = {
  "ADE-01": "vendor-onboarding", "ADE-02": "media", "ADE-03": "storefront", "ADE-04": "catalogue",
  "ADE-05": "catalogue", "ADE-06": "media", "ADE-07": "content", "ADE-08": "vendor-onboarding",
  "ADE-09": "allergens", "ADE-10": "vendor-onboarding", "ADE-11": "vendor-onboarding",
  "ADE-12": "catalogue", "ADE-13": "staff-scoping", "ADE-14": "vendor-onboarding",
  "ADE-15": "accessibility", "ADE-16": "vendor-onboarding", "ADE-17": "storefront",
  "J-01": "checkout", "J-02": "performance", "J-03": "tracking", "J-04": "accounts", "J-05": "checkout",
  "J-06": "notifications", "J-07": "tracking", "J-08": "discovery", "J-09": "accounts", "J-10": "accounts",
  "J-11": "catalogue", "J-12": "discovery", "J-13": "content", "J-14": "tracking", "J-15": "accessibility",
  "P05-1": "allergens", "P05-2": "allergens", "P05-3": "discovery", "P05-4": "allergens",
  "P05-5": "allergens", "P05-6": "allergens", "P05-7": "allergens", "P05-8": "allergens",
  "P05-9": "allergens", "P05-10": "allergens", "P05-11": "accessibility", "P05-12": "checkout",
  "F-01": "privacy", "F-02": "privacy", "F-03": "checkout", "F-04": "orders", "F-05": "privacy",
  "F-06": "checkout", "F-07": "checkout", "F-08": "checkout", "F-09": "content", "F-10": "checkout",
  "F-11": "notifications",
  "F1": "security", "F2": "webhooks", "F3": "tracking", "F4": "security", "F5": "security",
  "F6": "legal-compliance",
  "C-01": "content", "C-02": "finance", "C-03": "finance", "C-04": "legal-compliance", "C-05": "content",
  "C-06": "content", "C-07": "vendor-onboarding", "C-08": "content", "C-09": "content", "C-10": "catalogue",
  "C-11": "legal-compliance", "C-12": "content", "C-13": "content", "C-14": "storefront", "C-15": "content",
};

function normSev(s) {
  s = s.replace(/\*/g, "").trim().toLowerCase();
  for (const k of ["blocker", "major", "minor", "polish", "positive"]) if (s.startsWith(k)) return k;
  die(`unmapped severity: ${s}`);
}

function parsePass1() {
  const rows = [];
  const dirs = fs.readdirSync(path.join(REPO, P1DIR)).sort();
  for (const d of dirs) {
    const rep = path.join(REPO, P1DIR, d, "REPORT.md");
    if (!fs.existsSync(rep)) continue;
    const text = fs.readFileSync(rep, "utf8");
    const m = text.match(/^## Findings\s*$([\s\S]*?)(?=^## |(?![\s\S]))/m);
    if (!m) die(`no Findings section in ${rep}`);
    for (const line of m[1].split("\n")) {
      if (!line.startsWith("|")) continue;
      const cells = line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((c) => c.trim());
      if (cells.length < 5 || !/^(ADE|J|P05|F|C)-?\d+$/.test(cells[0])) continue;
      const [fid, sev, status, finding] = cells;
      const evidence = cells.slice(4).join(" | ");
      if (!(fid in P1_AREA)) die(`no area for ${fid}`);
      rows.push({
        id: fid, title: finding, severity: normSev(sev), severityAsReported: sev.replace(/\*/g, ""),
        status: /SUSPECTED/i.test(status) ? "SUSPECTED" : "CONFIRMED", statusAsReported: status,
        area: P1_AREA[fid], surface: "", steps: [], expected: "", actual: finding, realWorldImpact: "",
        evidence: [evidence], reconfirms: [], persona: d, source: "pass-1",
      });
    }
  }
  return rows;
}

const p1 = parsePass1();
if (new Set(p1.map((r) => r.id)).size !== p1.length) die("duplicate pass-1 ids");
fs.writeFileSync(path.join(OUT, "pass1-findings.json"), JSON.stringify(p1, null, 2) + "\n");

const p2 = [];
for (const d of fs.readdirSync(path.join(REPO, P2DIR)).sort()) {
  const f = path.join(REPO, P2DIR, d, "findings.json");
  if (!fs.existsSync(f)) continue;
  for (const e of JSON.parse(fs.readFileSync(f, "utf8"))) p2.push({ ...e, persona: d, source: "pass-2" });
}
if (new Set(p2.map((r) => r.id)).size !== p2.length) die("duplicate pass-2 ids");

const COORD = {
  id: "COORD-01", persona: "coordinator", source: "coordinator",
  title: "The local compose stack can never exercise the card-payment path",
  severity: "major", status: "CONFIRMED", area: "checkout",
  surface: "docker-compose.full-stack.yml; frontend/Dockerfile; /shop/{slug}/checkout",
  steps: [
    "Start the stack with docker compose -f docker-compose.full-stack.yml up with the repo .env",
    "docker exec the core-java container and print STRIPE_API_KEY",
    "grep the frontend container's /app/.next/static for pk_test",
    "Open any shop's checkout",
  ],
  expected: "With test-mode keys in .env, core can create PaymentIntents and the browser bundle carries the publishable key, so the card path can be exercised locally",
  actual: "core-java reads STRIPE_API_KEY (docker-compose.full-stack.yml:368) but .env provides STRIPE_SECRET_KEY, so it is empty in the running container. NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY is inlined at BUILD time (frontend/app/shop/[slug]/checkout/page.tsx:107-108) but frontend/Dockerfile declares no ARG for it, so the runtime environment value at compose line 528 can never reach the browser; no pk_test in /app/.next/static. Every shop is cash-only.",
  realWorldImpact: "Every persona test and local E2E run is cash-only; the money path (capture, 3DS, decline, refund) is unexercisable locally, so card defects can only surface in staging or production.",
  evidence: ["docker-compose.full-stack.yml:368", "docker-compose.full-stack.yml:528", ".env:91-92 (STRIPE_PUBLISHABLE_KEY / STRIPE_SECRET_KEY names)", "frontend/Dockerfile (no ARG NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY)", "frontend/app/shop/[slug]/checkout/page.tsx:107-108"],
  reconfirms: [],
};

const ALL = {};
for (const r of [...p1, ...p2, COORD]) {
  if (ALL[r.id]) die(`id collision ${r.id}`);
  ALL[r.id] = r;
}

// ---------------------------------------------------------------- homes / sub-themes
const H29 = "Phase 29 – Deployable Staging, With Its Own Monitoring";
const H30 = "Phase 30 – The Money Path, Executed";
const H31 = "Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)";
const H32 = "Phase 32 – Production Cutover + First Tenant";
const H33 = "Phase 33 – The Consumer Product (CUST-02/CUST-04 still open)";
const H34 = "Phase 34 – Rendering + Test Truthfulness (complete: #507 remainder)";
const N = "NEW: Phase 37 – Real-world operations readiness (persona findings)";
const SUB = {
  A: "37-A Kitchen & order operations",
  B: "37-B Multi-site, staff access & finance",
  C: "37-C Checkout integrity & customer trust/retention",
  D: "37-D Abuse resistance",
  E: "37-E Integrator surface (API, MCP, webhooks, sync)",
  F: "37-F Accessibility",
  G: "37-G Catalogue & shop-admin correctness",
};
const n37 = (k) => `${N} · ${SUB[k]}`;

const CL = [];
// keyword-argument emulation for the Python-style call sites below
let primary, repro, impact, bundle;
function C(key, pri, title, area, typ, members, home, match, why, ...rest) {
  const kw = { primary, repro, impact, bundle };
  const kwVals = Object.values(kw).filter((v) => v !== undefined);
  const pos = rest.filter((v) => !kwVals.some((k) => k === v));
  const [code = null, verified = false] = pos;
  if (pos.length > 2) die(`${key}: unexpected positional args ${JSON.stringify(pos)}`);
  CL.push({ key, pri, title, area, typ, members, home, match, why, code, verified: !!verified,
    repro: kw.repro || null, impact: kw.impact || null, bundle: kw.bundle || null, primary: kw.primary || null });
  primary = repro = impact = bundle = undefined;
}
const M = (kind, issues = [], note = "") => ({ classification: kind, issues: [...issues], note });
const NONE = M("none");

// =============================== P0 ===============================
C("erasure-noop", "P0", "DSAR erasure is marked completed while nothing is erased for storefront customers",
  "privacy", "bug", ["P2-GRA-01"], H31,
  M("regression-of-closed", [84], "#84 closed with 'erasure removes guest-order PII'; the 31-09 DSAR fan-out path scans only `customers` rows, which storefront orders never create. Related: #764, #771 (same GdprService, closed 2026-09-30)."),
  "Data-protection failure: Article 17 requests report success while every storefront customer's PII and public review survive.",
  "core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java:449-458 (eraseSubjectByDigest iterates customerRepository.findIdAndEmailByTenantId only)", true)

C("dsar-access-undelivered", "P0", "A verified DSAR access request is never fulfilled (ACCESS delivery not implemented)",
  "privacy", "bug", ["P2-GRA-02"], H31, NONE,
  "Data-protection failure: the statutory one-month Article 15 deadline is missed for every requester after they were told it will be actioned.",
  "DsarFanoutWorker logs 'ACCESS delivery is not implemented in plan 31-09' (member cites the log line)", false)

C("revoke-escalation", "P0", "Revoking a manager's last shop grant silently makes him tenant-wide Group admin",
  "staff-scoping", "bug", ["P2-KEM-01"], n37("B"),
  M("related", [285, 499], "#285 (bulk revoke of JIT rows) and #499 (grant upsert) touch the same rows; neither describes the escalation."),
  "Privilege escalation: an owner's revoke produces MORE access (all shops, staff management), while the Staff page shows no row at all.",
  "core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:49-62,104-106,178 (strict-scoping OFF: an ungranted tenant user is an implicit tenant-wide GROUP_ADMIN)", true)

C("default-admin", "P0", "Every staff login is a tenant-wide Group admin by default (JIT provisioning, strict-scoping off)",
  "staff-scoping", "bug", ["ADE-13", "P2-TUN-05", "P2-KEM-02"], n37("B"),
  M("related", [285], "#285 is UX for cleaning up JIT rows; the default itself is untracked."),
  "Privilege escalation by default: a kitchen login can reprice the menu, add an outbound webhook streaming all orders, grant staff and edit customers.",
  "core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:59-62,104-106,178 (D-04 JIT auto-provision; jtoye.access.strict-scoping defaults false)", true)

C("cross-shop-review", "P0", "A buyer of one shop can publish a 5-star review on a different shop of the same tenant",
  "legal-compliance", "bug", ["P2-REG-01"], n37("D"), NONE,
  "Cross-shop fake reviews feed public ratings and JSON-LD aggregateRating (CPR/DMCC fake-review exposure).",
  "core-java/src/main/java/uk/jtoye/core/review/ReviewService.java:70-95 (checks email, COMPLETED, duplicate; never order.shopId == shop.id)", true)

C("xff-ratelimit-bypass", "P0", "The public rate limiter trusts any X-Forwarded-For value, so rotating it defeats the limit",
  "security", "bug", ["P2-KYL-02"], n37("D"),
  M("regression-of-closed", [88], "#88 closed by adding an IP-keyed limiter for /public/**; the IP is taken from the first client-controlled XFF hop, so the fix is bypassable."),
  "Abuse control bypass: the only throttle on fake guest orders and review spam can be reset per request.",
  "core-java/src/main/java/uk/jtoye/core/security/ClientIpResolver.java:32,47 (first X-Forwarded-For hop, no trusted-proxy allow-list)", true)

C("basket-not-revalidated", "P0", "Checkout never re-validates the stored basket: stale prices, removed and sold-out items surface only as a charge or a bare error",
  "checkout", "bug", ["P2-FUN-03", "P2-CHA-01", "P2-FUN-09", "P2-CHA-03", "P2-CHA-04", "P2-CHA-07", "F-10"], n37("C"),
  NONE, "Price charged differs from price shown (£11.50 shown, £12.00 charged; £188.50 shown, £198.50 charged) with no notice.",
  "frontend cart/checkout use the price stored in localStorage at add time (member P2-CHA-01 cites cart page, checkout lines and button); server reprices silently", false,
  primary="P2-CHA-01")

C("promo-not-applied", "P0", "An advertised '20% OFF' promotion is displayed on the storefront but never applied to the order",
  "legal-compliance", "bug", ["P2-KYL-07", "P2-REG-05"], n37("C"), NONE,
  "Price charged differs from the price advertised (misleading pricing under CPR 2008 / DMCC 2024).",
  "core-java/.../storefront/PublicStorefrontService.java:231,246 read promotions for display only; order/OrderService.java has no promotion reference (rg rc=1)", true,
  primary="P2-REG-05")

C("allergen-ack-not-stored", "P0", "The customer's allergen acknowledgement is never sent to or stored by the server",
  "allergens", "bug", ["P2-REG-02"], H31,
  M("related", [427], "#427 (allergen evidence chain epic) does not cover the acknowledgement record."),
  "Legal/safety: there is no evidence of what the customer acknowledged, and API/MCP orders bypass the client-only gate.",
  "core-java/src/main/java/uk/jtoye/core/storefront/dto/GuestOrderRequest.java has no acknowledgement field (rg -i acknowledg rc=1; control: 11 fields matched)", true)

C("allergen-record-customer", "P0", "The customer never sees the allergen set recorded on their order, and it can differ from what they acknowledged",
  "allergens", "bug", ["P05-2", "P2-REG-04", "P2-CHA-02", "P05-9"], H31,
  M("related", [427], "#427 is the allergen evidence-chain epic; it does not cover the customer-facing record."),
  "User safety + legal: the order (and kitchen ticket) records a different allergen set from the one acknowledged, and no confirmation, email or tracking page shows it.",
  "checkout allergen panel fetched once on mount and never re-checked (member P2-CHA-02); confirmation/emails/track carry no allergen fields", false,
  primary="P2-CHA-02")

C("kitchen-screen-missing-note", "P0", "The kitchen screen ticket hides the customer's note (e.g. 'severe peanut allergy') and the fulfilment type",
  "kitchen", "bug", ["P2-TUN-03", "P2-FUN-02", "P2-TUN-11"], n37("A"), NONE,
  "User safety: a written allergy instruction never reaches the cook on screen (only the printed ticket has it).",
  "frontend/app/dashboard/kitchen/page.tsx renders no order.notes (rg -uu 'notes' rc=1 on the file and the directory; control 'Kitchen' = 12 hits); kitchen-ticket.tsx (print) does", true,
  primary="P2-TUN-03")

C("ingredients-vs-allergens-unwarned", "P0", "A product whose ingredients name an allergen (e.g. 'butter (MILK)') with no box ticked saves and shows as 'No allergens'",
  "allergens", "bug", ["ADE-09"], H31,
  M("related", [427], "#427 (ingredient/allergen evidence chain) is the long-term home; the save-time warning is not tracked."),
  "User safety: the platform publishes 'No allergens' for a dish whose own ingredient text names MILK, with no warning to the vendor.",
  "31-04 OrderAllergenAggregator reconciles at ORDER time only; nothing reconciles at product save (not verified in code)", false,
  repro={"steps": ["Sign in as a vendor, Products → Add product", "Ingredients: 'rice, butter (MILK), pepper'; tick no allergen", "Save, then open the dish on the storefront"],
         "expected": "Save warns that MILK is named in the ingredients but not declared; the storefront never says 'No allergens' for it",
         "actual": "Saves silently; product shows 'No allergens'"},
  impact="A milk-allergic customer is told a dish containing butter is allergen-free.")

C("hygiene-claim-unbacked", "P0", "Shops go live with a failed FSA match, self-approved by the vendor, while the site claims 'UK food-hygiene verified'",
  "vendor-onboarding", "content/legal", ["ADE-08", "C-07", "P2-REG-09", "ADE-14"], H33,
  M("related", [453], "#453 records that MANUAL_REVIEW has no adjudicator; this cluster is the public claim and the live-while-unverified state."),
  "Legal breach (misleading claim to consumers) and safety: 'verified' is shown for shops whose hygiene check failed, and no FHRS rating is displayed.",
  null, false, primary="P2-REG-09", impact="The landing trust strip promises 'UK food-hygiene verified' while shops whose FSA match failed are live, approved only by their own admin; the platform may be listing an unregistered food business and shows no FHRS rating.")

C("seller-identity-missing", "P0", "Customers are never given the seller's legal identity or any way to contact the shop",
  "legal-compliance", "content/legal", ["P2-REG-06", "P05-1"], H31, NONE,
  "Legal breach (E-Commerce Regs 2002 reg 6 / CCR 2013 trader identity, address, cancellation info) and safety: every 'ask the kitchen' message is a dead end because all shops publish phone:null, email:null.",
  null, false, primary="P2-REG-06")

C("stripe-on-cash-checkout", "P0", "Stripe JS and fraud cookies load on a cash-only checkout, contradicting the cookie policy",
  "privacy", "bug", ["F-01", "P2-REG-13"], H31, NONE,
  "Legal breach (PECR): non-essential third-party cookies (__stripe_mid, 1 year) are set on a page that takes no payment.",
  "frontend/app/shop/[slug]/checkout/page.tsx:7 imports from '@stripe/stripe-js' (default entry injects js.stripe.com on import; the KEY guard at :107-108 cannot stop it)", true,
  repro={"steps": ["Fresh browser, open any shop, add an item, go to /checkout", "Inspect network and cookies"],
         "expected": "No third-party requests or cookies on a cash-only checkout",
         "actual": "js.stripe.com and m.stripe.network load, m.stripe.com/6 is POSTed, __stripe_mid (1 year), __stripe_sid and m cookies are set; the page says 'No payment is taken online'"},
  impact="Every cash customer is tracked by Stripe without consent, contrary to the published cookie policy.")

C("stock-tracking-silently-off", "P0", "Updating a product without quantityInStock silently turns stock tracking off",
  "catalogue", "bug", ["P2-RAV-03"], n37("E"), NONE,
  "Silent data corruption: stock becomes unlimited on a routine PUT, so sold-out items can be oversold.",
  "PUT /api/v1/products/{id} maps a missing quantityInStock to null (=untracked); no PATCH (member cites)", false)

C("sync-orphan-products", "P0", "Products created by /sync/batch belong to no shop, are orderable at any shop, and record the wrong allergens",
  "allergens", "bug", ["P2-RAV-05"], n37("E"),
  M("same", [727], "#727 calls this latent (no producer). Pass 2 reproduced it live and found the safety consequence: an order for a duplicate-titled orphan records allergenMask 0 where the real dish declares 256. Ask to re-prioritise."),
  "User safety: an order line records no allergens for a dish whose real declaration has them.",
  "core-java/src/main/java/uk/jtoye/core/sync/SyncService.java upsertProduct never sets shopId (per #727)", false)

C("completed-order-deletable", "P0", "A completed, paid order can be deleted, leaving ledger rows that point at nothing",
  "finance", "bug", ["P2-KEM-07"], n37("B"), NONE,
  "Silent data corruption of financial records: the ledger lists sales whose orders return 404.",
  "core-java/src/main/java/uk/jtoye/core/order/OrderService.java:599-608 (deleteOrder checks only SHOP_MANAGER; no status guard)", true)

C("platform-trading-disclosure", "P0", "The platform's own registered office is not published anywhere on the site",
  "legal-compliance", "content/legal", ["C-11"], H31, NONE,
  "Legal breach: the Companies (Trading Disclosures) Regulations require the registered office on the website; /legal/accessibility even says it is not published.",
  "frontend/Dockerfile:81 declares ARG NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE (31-08); it is set in no runtime (possibly config-only)", true,
  repro={"steps": ["Open /legal, /legal/privacy and /legal/accessibility"],
         "expected": "Company name, number, registered office (and ICO registration reference) shown",
         "actual": "Name and number only; 'Registered office address not published'; no ICO number"},
  impact="A regulator or B2B buyer finds a statutory disclosure missing; procurement questionnaires fail.")

// =============================== P1 ===============================
C("new-orders-invisible", "P1", "A new order makes no sound and never reaches the kitchen screen until someone confirms it on another page",
  "kitchen", "bug", ["P2-FUN-01", "P2-TUN-01"], n37("A"), NONE,
  "Blocks the core vendor journey: orders sit unseen as PENDING while the board says 'No active orders'.",
  "frontend/app/dashboard/kitchen/page.tsx lists only CONFIRMED/PREPARING/READY; the only beep fires on CONFIRMED (:496-499, member cites)", false,
  primary="P2-FUN-01")

C("kds-session-lapse", "P1", "When the all-day kitchen tablet's session lapses the board silently becomes a sign-in page",
  "kitchen", "bug", ["P2-TUN-06"], n37("A"), NONE,
  "Blocks the core vendor journey: every order after the 5-minute token expiry is never seen.", null, false)

C("cannot-stop-orders", "P1", "A vendor cannot pause or stop taking orders: no pause switch, no holiday closure, and free-text hours fail open and cannot be cleared",
  "orders", "gap/enhancement", ["P2-FUN-06", "P2-CHA-10", "P2-KEM-11"], n37("A"), NONE,
  "Blocks the core vendor journey: an overwhelmed or closed kitchen keeps receiving orders.",
  "PublicStorefrontService.validateShopIsOpen treats unparseable/empty hours as open (member P2-KEM-11, SUSPECTED); update mapper ignores null hours (P2-CHA-10)", false,
  primary="P2-FUN-06")

C("fulfilment-settings", "P1", "Vendors cannot set a delivery fee, free-delivery threshold or collection-only: orders are charged £0 delivery and collection-only shops take deliveries",
  "vendor-onboarding", "gap/enhancement", ["P2-KEM-10", "P2-CHA-08", "ADE-16"], n37("G"), NONE,
  "Blocks the core vendor journey: every new-site delivery loses its fee and collection-only kitchens receive orders they cannot fulfil.",
  "ShopDto / CreateShopRequest have no deliveryFeePennies or freeDeliveryThreshold (member P2-KEM-10)", false, primary="P2-KEM-10")

C("second-shop-cannot-go-live", "P1", "A second or third shop can never go live, while onboarding says 'Your storefront is live'",
  "vendor-onboarding", "gap/enhancement", ["P2-KEM-03", "ADE-10"], H33,
  M("same", [452], "#452 gap 1 (no 2nd-shop onboarding path). New: the onboarding page tells owner and manager 'Your storefront is live' while all shops are Draft, and the vendor has no draft preview (ADE-10)."),
  "Blocks the multi-site vendor journey entirely.", null, false)

C("no-staff-invite", "P1", "There is no way to invite a staff member: they must self-register, then auto-become Group admin",
  "accounts", "gap/enhancement", ["P2-KEM-14"], H33,
  M("same", [452], "#452 gap 2 (no staff invite). New: combined with the JIT default, the only onboarding route for staff grants full admin."),
  "Blocks the vendor's staffing journey.", null, false)

C("menu-import-and-copy", "P1", "CSV import ignores the selected shop and hides imported items from every storefront; a menu cannot be copied to another site",
  "catalogue", "bug", ["P2-KEM-09", "C-10"], n37("B"), NONE,
  "Blocks multi-site setup: imported items appear on no storefront, silently.",
  "BulkImport creates shopId null; storefront getShopProducts filters shop_id = this shop (member P2-KEM-09)", false, primary="P2-KEM-09")

C("finance-not-shop-scoped", "P1", "Per-shop dashboard and finance show the whole business's takings, and site managers get 'No financial data yet' instead of their shop's numbers",
  "finance", "bug", ["P2-KEM-05", "P2-KEM-08"], n37("B"), NONE,
  "Blocks the multi-site owner's core journey: per-site takings are wrong while the banner says they are scoped.",
  "GET /api/v1/financial-transactions/summary ignores shopId and from; ledger rows carry no shop (member P2-KEM-05)", false, primary="P2-KEM-05")

C("slug-regenerates", "P1", "Every shop update regenerates the public URL slug, so shared links and QR codes break",
  "storefront", "bug", ["ADE-03"], n37("G"), NONE,
  "Blocks the customer's route to the shop: any edit (even opening hours) changes the public link.",
  "core-java/src/main/java/uk/jtoye/core/shop/ShopService.java:268-270 (regenerates slug whenever request slug is blank; the form never sends slug)", true,
  repro={"steps": ["Dashboard → Shops → Edit an existing shop", "Change only the opening hours or description, Save", "Compare the slug before and after"],
         "expected": "Slug unchanged unless the vendor edits it",
         "actual": "New slug each save (a823cee3 → 7cd132a5 → 85a6253f); old link 404 (SUSPECTED once published)"},
  impact="Printed QR codes, Instagram links and search results go dead after any edit.")

C("confirmation-lost", "P1", "The order confirmation is rendered in place at /checkout: off-screen, unannounced, and lost on refresh",
  "checkout", "bug", ["F-03", "P2-GRA-04", "P2-MAR-01"], n37("C"),
  M("related", [409], "#409 fixed the confirmation never appearing; it still has no route of its own."),
  "Blocks the core customer journey: the customer cannot tell the order succeeded and may re-order.", null, false,
  primary="P2-MAR-01")

C("delivery-anywhere", "P1", "Delivery is accepted to any UK postcode with no radius check at checkout",
  "checkout", "bug", ["J-01", "P2-KYL-05"], H33,
  M("same", [460], "#460 'no delivery radius'. Phase 33 added radius DISCOVERY; checkout still accepts Belfast/Manchester/300+ miles for a London shop."),
  "Blocks fulfilment: orders are taken that the kitchen cannot deliver.", null, false, primary="P2-KYL-05")

C("vendor-no-way-in", "P1", "A prospective vendor has no way in: 'Start your application' dead-ends at a login, and no sales or support contact exists",
  "vendor-onboarding", "gap/enhancement", ["ADE-01", "C-05"], H32,
  M("related", [102], "#102 covers the tenant lifecycle backend; the public application/contact route is untracked."),
  "Blocks vendor acquisition: both prospects gave up at this step.", null, false,
  repro={"steps": ["Open / or /for-operators on a phone", "Tap 'Start your application'", "Look for a phone, sales email or demo booking anywhere"],
         "expected": "An application form or a contact route that reaches a person",
         "actual": "Keycloak 'Sign in to your account' with no register or back link; the only email on the site is privacy@ on a non-brand domain"},
  impact="Vendors who want to sign up cannot; evaluators walk away citing 'nobody to ring at 8pm Saturday'.")

C("commercial-terms-absent", "P1", "No merchant terms, pricing page, VAT basis or card-fee figure: /legal/terms, /pricing, /contact and /about all 404",
  "legal-compliance", "content/legal", ["C-04", "C-09"], H32, NONE,
  "Blocks vendor signing: there is no contract to sign or price basis to agree to.", null, false,
  repro={"steps": ["Request /legal/terms, /pricing, /contact, /about", "Read the terms card on /for-operators"],
         "expected": "Merchant terms, a pricing page with VAT basis, term and card fees",
         "actual": "All four return 404; prices labelled 'PRICING TEST', no VAT basis, 'card fees shown transparently' with no figure"},
  impact="A buyer cannot sign; procurement stops.")

C("no-subscription-billing", "P1", "The published £39/location subscription has no billing built and no billing UI",
  "finance", "gap/enhancement", ["C-02"], H30,
  M("same", [102], "#102 'cannot bill'; Phase 30 PAY-02 closes its remainder."),
  "Blocks the first paying tenant: the platform cannot collect its own fee.", null, false,
  repro={"steps": ["Read /for-operators pricing", "Look for billing in the vendor dashboard"], "expected": "A subscription the vendor can start", "actual": "None; /competitive admits 'no SaaS subscription billing built'"},
  impact="No revenue can be collected from vendors.")

C("no-payout-surface", "P1", "Vendors have no payments or payouts surface (/dashboard/payments returns 404)",
  "finance", "gap/enhancement", ["C-03"], H30,
  M("same", [102], "#102 'cannot pay-out'; Phase 30 PAY-03."),
  "Blocks the vendor's core money journey: a vendor cannot see or receive card takings.", null, false,
  repro={"steps": ["As a vendor open /dashboard/payments and /dashboard/payments/connect"], "expected": "Connect onboarding status and payouts", "actual": "404; Connect reachable only via TenantAdminController POST"},
  impact="Vendors cannot be paid for card orders.")

C("no-developer-credentials", "P1", "A vendor has no way to give a developer API credentials; the only path is the owner's password plus the confidential core-api client secret",
  "api-mcp", "gap/enhancement", ["P2-RAV-01"], n37("E"),
  M("related", [206], "#206 delivered scoped client scopes; there is still no per-tenant issuance or rotation surface."),
  "Blocks the integrator journey and forces credential sharing of a full admin token.", null, false)

C("mcp-orders-stay-draft", "P1", "An order taken by an AI agent through MCP stays DRAFT and the kitchen never sees it",
  "api-mcp", "bug", ["P2-RAV-04"], n37("E"), NONE,
  "Blocks the agent ordering journey: the customer is told the order exists, the kitchen never cooks it.", null, false)

C("sync-drops-items", "P1", "/sync/batch has no stock or availability field and silently skips unknown items while reporting SUCCESS",
  "catalogue", "gap/enhancement", ["P2-RAV-06"], n37("E"),
  M("related", [727], "Same endpoint as #727; the silent drop is a separate defect."),
  "Blocks EPOS integration; dropped items vanish without trace.", null, false)

C("webhook-events-lost", "P1", "Webhook endpoints auto-pause after ~46 s of failures and retries stop after 5 attempts, with nobody told",
  "webhooks", "bug", ["P2-RAV-10"], n37("E"),
  M("same", [587], "#587 (127 s window before events are lost). New: auto-pause trips at ~46 s, only 5 of 8 attempts run, and no email/notification is sent."),
  "Integrations silently lose events after a brief receiver outage.", null, false)

C("edge-shared-limiter", "P1", "Anonymous traffic can exhaust the edge gateway's single process-wide rate limit and block every vendor's sync; its 429 is untyped",
  "performance", "bug", ["P2-RAV-12"], n37("D"),
  M("related", [413], "#413 typed Core's 429; the edge 429 is still hand-rolled and the bucket is shared before auth."),
  "Blocks every vendor's integrations at once (cross-tenant denial of service).", null, false)

C("mcp-list-products-wrong", "P1", "Asked for one shop's menu, an AI agent gets every product in the business with allergens only as an integer",
  "api-mcp", "bug", ["P2-RAV-14"], n37("E"), NONE,
  "Blocks correct agent ordering: unavailable, other-shop and orphan items are offered, and allergens are undecoded.", null, false)

C("fake-order-flood", "P1", "Anyone can place many fake cash orders in seconds with throwaway contact details",
  "orders", "gap/enhancement", ["P2-KYL-01"], n37("D"),
  M("related", [461], "#461 (payment links before production) removes the zero-cost order; until then nothing bounds it."),
  "Blocks normal trading: a flood of fake orders lands on the kitchen looking real.", null, false)

C("quantity-unbounded", "P1", "Item quantity has no upper bound: a £19 billion order is accepted and shown on the dashboard",
  "orders", "bug", ["P2-KYL-03"], n37("D"), NONE,
  "Absurd orders reach the kitchen and pollute revenue figures.",
  "core-java/src/main/java/uk/jtoye/core/storefront/dto/GuestOrderItemRequest.java:13 (@Min(1) only, no @Max)", true)

C("allergy-note-unstructured", "P1", "An allergy request is a generic free-text note: no alert to the vendor, no acknowledgement, never echoed to the customer",
  "allergens", "gap/enhancement", ["P05-8", "P2-REG-10"], H31, NONE,
  "Blocks the allergic customer's journey: a request to confirm goes to COMPLETED with no flag or reply.", null, false, primary="P2-REG-10")

C("review-full-name-public", "P1", "Reviews publish the reviewer's full checkout name with no notice, policy or moderation",
  "privacy", "bug", ["P2-GRA-07", "P2-REG-12"], n37("C"), NONE,
  "Data-protection risk: full names are published (and in the public API) without transparency; reachable today via the API.", null, false,
  primary="P2-GRA-07")

C("local-stack-no-card-path", "P1", "The local compose stack can never exercise the card-payment path (Stripe env-var names and build-time key mismatch)",
  "checkout", "env/tooling", ["COORD-01"], H30,
  M("related", [461, 538, 61], "#461/#538/#61 all assume keys can be supplied; none records that compose drops them."),
  "Blocks testing of the core money journey: every local run, persona test and E2E is cash-only.",
  "docker-compose.full-stack.yml:368 (STRIPE_API_KEY) vs .env STRIPE_SECRET_KEY; frontend/Dockerfile has no ARG NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY; checkout/page.tsx:107-108 inlines it at build", true)

C("vat-rate-not-settable", "P1", "Products have no VAT-rate choice; everything is booked as Standard 20%",
  "finance", "bug", ["P2-KEM-15"], n37("B"),
  M("related", [81], "#81 fixed the hardcoded STANDARD in the ledger code; the vendor still has no way to set a product's rate, so the outcome persists."),
  "Every zero-rated (cold takeaway) item is booked at 20% VAT in the vendor's records.", null, false)

C("vat-registration-unknown", "P1", "'VAT (incl. 20%)' is shown for every vendor, with no VAT-registration status or number captured",
  "finance", "content/legal", ["P2-REG-08"], n37("B"), NONE,
  "Likely legal exposure for non-VAT-registered vendors (pending legal confirmation; P0 if confirmed).", null, false)

C("allergen-menu-surfaces", "P1", "Menu cards show allergens as an unnamed count, 'Add' works without seeing them, and 'none declared' is never stated",
  "allergens", "bug", ["P05-4", "P2-MAR-11", "P05-5", "P05-11"], H31, NONE,
  "Blocks the allergic customer's journey: she must open every dish, and a missing section reads as reassurance.", null, false,
  repro={"steps": ["Open /shop/brixton-village-grill", "Read a card's allergen badge (also with a screen reader)", "Tap 'Add' on a card; open Peri Peri Chicken's detail; repeat at 200% zoom"],
         "expected": "Named allergens on the card with an accessible name; an explicit 'No allergens declared' for empty items; allergens visible before Add",
         "actual": "Card shows '△2' read as 'Halal Spicy £8.50 2'; Tab reaches Add before details; no allergen section for nothing-declared items; at 200% the box is below the modal fold"},
  impact="An allergic customer adds dishes without seeing what is in them.")

// =============================== P2 ===============================
C("double-tap-skips-to-ready", "P2", "A double-tap on 'Start Preparing' jumps the order to READY and emails the customer 'Ready!' with no undo",
  "kitchen", "bug", ["P2-TUN-02"], n37("A"), NONE, "Major trust friction: customers walk over for food that is not ready.")
C("mute-survives-signout", "P2", "Kitchen mute survives sign-out and the next person's icon shows sound on while new orders are silent",
  "kitchen", "bug", ["P2-TUN-07"], n37("A"), NONE, "Major friction: the next shift misses audible alerts.",
  "useState initialised from localStorage 'kds-muted' (member cites); kds-muted is listed in /legal/cookies", false)
C("cancel-unguarded", "P2", "Cancelling an order is one unconfirmed tap with no reason, next to Confirm on small phone buttons",
  "orders", "bug", ["P2-FUN-05", "P2-FUN-12"], n37("A"), NONE, "Major friction: accidental cancels, and the customer is told nothing useful.", primary="P2-FUN-05")
C("pending-forever", "P2", "Unanswered orders stay Pending forever and the customer is never told",
  "orders", "gap/enhancement", ["P2-FUN-14"], n37("A"), NONE, "Major trust friction: customers wait indefinitely for orders nobody accepted.")
C("order-search", "P2", "Orders and Customers have no search, so an order cannot be found by customer name at the counter",
  "orders", "gap/enhancement", ["P2-FUN-07"], n37("A"), NONE, "Major friction at the counter during a rush.")
C("kds-order-number-truncated", "P2", "Every kitchen ticket cuts the order number to 'ORD-…', so tickets look identical",
  "kitchen", "bug", ["P2-TUN-04", "P2-KEM-12"], n37("A"), NONE, "Major friction: staff cannot match tickets to bags or customers.", primary="P2-TUN-04")
C("kds-tablet-layout", "P2", "The kitchen board is not built for a wall tablet: wasted space, small text, newest-first ordering and very tall tickets",
  "kitchen", "bug", ["P2-FUN-13", "P2-TUN-12", "P2-TUN-08", "P2-CHA-11"], n37("A"),
  M("related", [699], "#699 (tablet gets the desktop sidebar)."), "Major friction: the longest-waiting ticket sinks below the fold.", primary="P2-TUN-08")
C("no-ready-time", "P2", "No ready-by or delivery time exists anywhere: tickets, confirmation, tracking or emails",
  "tracking", "gap/enhancement", ["J-03", "P2-FUN-10", "P2-GRA-17"], n37("C"),
  M("related", [458], "#458 asks for profile-based, auto-populating tracking; ETA is not tracked."), "Major trust gap: customers do not know when to walk over.", primary="P2-FUN-10")
C("order-comms-thin", "P2", "Order confirmation and every status email omit the shop, items, total and address, and come from inconsistent senders",
  "notifications", "bug", ["J-06", "J-07", "P2-FUN-15", "P2-CHA-17", "P2-GRA-14", "P2-REG-07", "F-11", "P2-GRA-19"], n37("C"),
  M("related", [649], "#649 (olajay.co.uk expiry) touches the two-domain sender/contact split."),
  "Major trust gap: the customer has no written record of price or contents, and the privacy notice's 'shop is named on your confirmation' is false.", primary="P2-CHA-17")
C("finance-reporting", "P2", "Finance has no 'today', no date range, no export and no cash/card split",
  "finance", "gap/enhancement", ["P2-FUN-08", "P2-KEM-06"], n37("B"), NONE, "Major friction: end-of-night cash-up and the accountant's month-end cannot be done.", primary="P2-KEM-06")
C("cash-never-paid", "P2", "A completed cash order stays 'Payment status NONE / Unpaid' while counted as revenue",
  "finance", "bug", ["P2-REG-11"], H30,
  M("related", [461], "#461 removes pay-on-collection; until then the record is wrong."), "Major trust gap in the vendor's records.")
C("payment-method-late", "P2", "Cash-only is revealed only at the bottom of checkout, and the Place order button shows a card icon",
  "checkout", "bug", ["J-05", "P2-FUN-16", "P05-12"], H30,
  M("related", [461], "#461 replaces pay-on-collection with payment links."), "Major friction: customers expecting card pay abandon at the last step.",
  repro={"steps": ["Browse /shop, a shop menu and the basket", "Go to checkout and scroll to the bottom"],
         "expected": "Payment method stated on the shop card/menu/basket; a cash-appropriate button",
         "actual": "Nothing until 'Pay on collection — cash to the shop' at the bottom; card icon on 'Place order'"},
  impact="Card-expecting customers abandon at the final step.")
C("add-before-hydration", "P2", "Menu 'Add' buttons are visible but ignore taps until hydration (4.2 s on 4G, 14 s on Slow 3G)",
  "performance", "bug", ["J-02"], H34,
  M("related", [507], "#507 (client pages fetch on mount)."), "Major friction: first taps are silently lost on mobile.",
  repro={"steps": ["iPhone 13 profile, 4G throttle (and Slow 3G)", "Open a shop link fresh and tap Add as soon as it shows"],
         "expected": "First tap adds (or the button is visibly inactive)", "actual": "Taps ignored until 4.2 s (4G) / 14.1 s and 7 taps (Slow 3G)"},
  impact="Impatient mobile customers think the site is broken.")
C("banner-overwritten-by-logo", "P2", "Saving a shop overwrites its banner with the logo URL",
  "media", "bug", ["ADE-02"], n37("G"), NONE, "Major trust friction: the storefront banner is silently replaced (recoverable by re-upload).",
  "frontend/components/ui/image-uploader.tsx:397-399 (product.imageUrl || product.logoUrl || product.bannerUrl picks logoUrl from the ShopDto)", true,
  repro={"steps": ["Shop with a logo: Edit shop → upload a banner", "Click Update Shop", "Read the shop's bannerUrl"],
         "expected": "bannerUrl = the uploaded banner", "actual": "bannerUrl = logoUrl (reproduced twice)"},
  impact="The vendor's banner disappears unnoticed until a customer sees it.")
C("product-delete-refused", "P2", "Products cannot be deleted once they have a photo or any order (even cancelled): misleading 409",
  "catalogue", "bug", ["ADE-04", "P2-CHA-19"], n37("G"), NONE, "Major friction: test and retired items stay in the catalogue forever; the error claims a false order reference.",
  "409 raised on product_media_product_id_fkey and order_items FK, mapped to 'referenced by an existing order' (member ADE-04)", false, primary="P2-CHA-19")
C("shop-delete-orphans-products", "P2", "Deleting a shop orphans its products, and editing an orphan silently moves it to 'All Shops'",
  "catalogue", "bug", ["ADE-05"], n37("G"),
  M("related", [727], "Same null-shop product semantics as #727."), "Major friction and data drift in the catalogue.",
  repro={"steps": ["Create a shop with products", "Delete the shop (204)", "List products; GET one; edit one"],
         "expected": "Delete refused or products archived with the shop", "actual": "Products still listed, GET returns 404 'Shop not found', editing moves them to All Shops"},
  impact="Ghost products accumulate and can reappear on the wrong scope.")
C("image-processing-stuck", "P2", "The image dialog stays on 'Processing…' although the server has the image ACTIVE within ~6 s",
  "media", "bug", ["ADE-06"], n37("G"), NONE, "Major friction: the upload looks broken.",
  repro={"steps": ["Add product → upload a photo", "Wait in the dialog"], "expected": "Image appears when ACTIVE", "actual": "'Processing…' for 30 s, 30 s and >120 s; only reopening shows it"},
  impact="Vendors re-upload or give up on photos.")
C("publish-checkbox-broken", "P2", "The 'Publish to storefront' checkbox is ignored, or throws away the whole shop edit with a raw developer error",
  "vendor-onboarding", "bug", ["ADE-11", "P2-KEM-04"], n37("G"), NONE, "Major friction: edits are lost and the error tells the vendor to call a non-existent support line.", primary="P2-KEM-04")
C("internal-pages-public", "P2", "Internal strategy pages are public, expose a repo path, and contradict the landing page (incl. a 'payouts: Full' claim)",
  "content", "content/legal", ["ADE-07", "C-06", "C-01", "C-15"], H32, NONE, "Major trust damage with prospects: both evaluators' trust fell sharply here.",
  repro={"steps": ["Open /business-model-guide and /competitive from the footer", "Compare with / and /for-operators"],
         "expected": "No internal strategy on public pages; consistent claims", "actual": "'Do not claim: … self-service tenancy', 'RAISED £0', repo path DOCS/ANALYSIS/…; 'payouts: Full' vs 'not production settlement'; /competitive absent from sitemap"},
  impact="Prospects read the platform's own 'do not claim' list and walk away.")
C("demo-data-visible", "P2", "Test and demo data is visible to customers and vendors (E2E 20% OFF promo, weeks-old test orders, a real person's name and email)",
  "content", "env/tooling", ["C-12"], H32, NONE, "Major trust damage; a real person's PII in demo data is a data-protection concern.",
  repro={"steps": ["Open /shop/mama-ades-kitchen", "Sign in as admin-user and open the dashboard overview"], "expected": "No test artefacts", "actual": "'E2E launch offer — E2E 20% OFF' banner; Pending test orders 11–27 days old; a real name + Hotmail address; a '<b>x</b>' customer"},
  impact="Evaluators read the product as unfinished; real PII is exposed in demos.")
C("no-self-serve-account", "P2", "There is no account page: data access and erasure are mailto-only although a backend intake exists",
  "privacy", "gap/enhancement", ["F-02", "P2-GRA-03"], H31, NONE, "Major friction: exercising data rights is far harder than giving the data.", primary="P2-GRA-03")
C("dsar-verify-link", "P2", "The DSAR confirmation link does not open on compose and shows raw JSON when it does",
  "privacy", "bug", ["P2-GRA-13"], H31, NONE, "Major friction: a non-technical requester cannot confirm, and on the canonical dev runtime no DSAR can be verified.",
  "DSAR_VERIFY_BASE_URL unset in compose (set in k8s, member cites)", false)
C("cookie-policy-inaccurate", "P2", "The cookie policy omits keys that hold the customer's email and id and survive sign-out",
  "privacy", "content/legal", ["F-05"], H31, NONE, "PECR transparency gap: stored personal data is undisclosed.",
  repro={"steps": ["Place an order signed in, sign out", "Inspect localStorage and compare with /legal/cookies"], "expected": "Every stored key disclosed; personal keys cleared on sign-out", "actual": "jtoye-guest-orders (order no. + email) written for signed-in orders and survives sign-out; jtoye-customer-last-signin (customer UUID) not listed"},
  impact="A shared device keeps a previous customer's email; the policy misstates storage.")
C("contact-validation", "P2", "Order contact details are barely validated, and API/MCP orders need no customer contact at all",
  "orders", "bug", ["F-07", "P2-KYL-06", "P2-RAV-16"], n37("D"), NONE, "Major friction: vendors cannot reach customers, and junk orders look real.", primary="P2-KYL-06")
C("no-bulk-reject", "P2", "Vendors have no bulk-reject and no fraud signal for junk orders",
  "kitchen", "gap/enhancement", ["P2-KYL-04"], n37("D"), NONE, "Major friction under attack: 25 fake orders = 25 manual cancels.")
C("refresh-token-race", "P2", "On a slow network, returning after 5 minutes signs the customer out (parallel refreshes burn the single-use token)",
  "accounts", "bug", ["P2-CHA-05"], n37("C"),
  M("regression-of-closed", [465], "#465 closed the 300-s session cliff; under latency the parallel probes reproduce it (3/3)."),
  "Major friction: customers are signed out mid-checkout on poor connections.")
C("no-order-ahead", "P2", "Customers cannot order ahead for a time, and a closed shop takes no pre-orders",
  "checkout", "gap/enhancement", ["P2-CHA-09"], n37("C"), NONE, "Major gap versus competitors (lunch pre-orders).")
C("no-reorder", "P2", "No 'order again' and no remembered address: a weekly order takes 10 taps and 85 keystrokes",
  "orders", "gap/enhancement", ["P2-GRA-05"], n37("C"), NONE, "Major retention friction for regulars.")
C("no-review-flow", "P2", "Customers have no way to leave a review; the only path is an unauthenticated API call with an email in the URL",
  "storefront", "gap/enhancement", ["P2-GRA-06", "P2-GRA-18"], n37("C"), NONE, "Major gap: reviews exist publicly but cannot be left legitimately.", primary="P2-GRA-06")
C("reset-link-expiry", "P2", "Password-reset links expire after 5 minutes",
  "accounts", "bug", ["P2-GRA-08"], n37("C"), NONE, "Major friction for less-confident users.", "Keycloak jtoye-customers actionTokenGeneratedByUserLifespan = 300 (member cites)", false)
C("keycloak-a11y", "P2", "Keycloak sign-in and registration fall below the storefront's accessibility bar and hide password rules until failure",
  "accessibility", "bug", ["P2-MAR-06", "P2-GRA-16"], H33,
  M("related", [545], "#545 (stock Keycloak theme on both realms)."), "Accessibility failure on the account journey (WCAG A/AA).", primary="P2-MAR-06")
C("large-text-breaks", "P2", "At large text sizes the basket, tracker and shop header truncate or overflow",
  "accessibility", "bug", ["P2-GRA-09", "P2-GRA-10", "P2-GRA-11"], n37("F"), NONE, "Accessibility failure (WCAG 1.4.4/1.4.10) for older customers.", primary="P2-GRA-09")
C("tracking-a11y", "P2", "Tracking pages are silent and unlabelled for screen readers (status changes, current step, copy button, lookup result, field name, contrast)",
  "accessibility", "bug", ["P2-MAR-02", "P2-MAR-03", "P2-MAR-08", "P2-MAR-13", "P2-MAR-14", "P2-MAR-17"], n37("F"), NONE,
  "Accessibility failure on the post-order journey (WCAG A/AA).", primary="P2-MAR-02")
C("shop-card-no-name", "P2", "Shop cards on the kitchen list have no accessible name ('link, link, link')",
  "accessibility", "bug", ["P2-MAR-04"], n37("F"), NONE, "Accessibility failure (WCAG 4.1.2) that axe cannot see.")
C("add-focus-and-announce", "P2", "Pressing Add/Remove drops focus to the page body and the basket announcement doubles the count without naming the item",
  "accessibility", "bug", ["P2-MAR-05", "P2-MAR-07"], n37("F"),
  M("related", [272], "#272 fixed '1 items'; the live region now reads '1 1 item in basket'."), "Accessibility failure on the core add-to-basket action.", primary="P2-MAR-05")
C("generic-page-titles", "P2", "Basket, checkout, confirmation and tracking all share the title 'J'Toye — Discover Local Vendors'",
  "accessibility", "bug", ["P2-MAR-09"], n37("F"), NONE, "Accessibility failure (WCAG 2.4.2).")
C("cookie-banner-overlay", "P2", "On mobile the cookie banner covers content and keyboard focus and is the 32nd tab stop",
  "accessibility", "bug", ["ADE-15", "J-15", "P2-MAR-12"], n37("F"), NONE, "Accessibility failure (WCAG 2.4.11 focus not obscured).", primary="P2-MAR-12")
C("delivery-tracking-says-collection", "P2", "A delivery customer's tracking page says 'Ready for collection'",
  "tracking", "bug", ["P2-FUN-04"], n37("C"),
  M("related", [502], "#502 fixed the READY email; the tracking timeline still uses collection copy."), "Major trust friction: delivery customers are told to come in.")
C("kitchen-pii-overexposed", "P2", "A kitchen hand sees far more customer personal data than needed to cook",
  "privacy", "bug", ["P2-TUN-10"], n37("B"), NONE, "Data-minimisation gap: kitchen payload and landing page carry email, phone, address.")
C("no-preparation-audit", "P2", "A vendor cannot show who prepared an order or when: no timeline, no export",
  "orders", "gap/enhancement", ["P2-REG-03"], n37("A"), NONE, "Food-safety due-diligence gap for an EHO visit.")
C("slow-86-and-reprice", "P2", "86ing an item or changing a price takes four taps and ~30 s in a long form",
  "catalogue", "gap/enhancement", ["P2-FUN-11"], n37("A"), NONE, "Major friction during service.")
C("verified-email-guard", "P2", "Nothing asserts at deploy time that customer email verification is on; dev runs with it off",
  "accounts", "env/tooling", ["J-04"], H29,
  M("related", [462], "#462 (no verified contact channel)."), "A misconfigured environment would expose another person's order history via My Orders.",
  "core-java/src/main/resources/application.yml:281 (default true) vs application-dev.yml:36 (false) — coordinator-verified in pass 1", false,
  repro={"steps": ["On a dev-profile stack, register a customer with someone else's email (no verification)", "Open My Orders"],
         "expected": "Registration requires a verified email; a deploy-time check fails if require-verified-email=false outside dev",
         "actual": "Another person's guest orders listed (dev profile only; prod default true)"},
  impact="One wrong env var in any deployed environment exposes order histories.")
C("allergen-basket-attribution", "P2", "The basket shows no allergens and checkout's combined set has no per-item attribution",
  "allergens", "gap/enhancement", ["P05-6", "P05-10"], H31, NONE, "Major friction for allergic customers deciding which item to drop.",
  repro={"steps": ["Add two dishes with different allergens", "Open the basket, then checkout"], "expected": "Allergens per line in basket and checkout", "actual": "Basket: none; checkout: one combined set"},
  impact="A parent cannot tell which dish to remove to make the order safe.")
C("may-contain-and-label-detail", "P2", "No 'may contain' field exists, label use-by is computed at download time, and records use US date format",
  "allergens", "gap/enhancement", ["P05-7", "P2-REG-14"], H31,
  M("related", [427, 82], "#427 epic; #82 (PPDS label format, closed)."), "Weak evidence and missing cross-contact information.", primary="P2-REG-14")
C("no-allergen-filter", "P2", "There is no allergen filter, and searching 'peanut' or 'gluten free' returns nothing",
  "discovery", "gap/enhancement", ["P05-3"], n37("C"), NONE, "Major friction for allergic customers.",
  repro={"steps": ["Open /shop", "Search 'peanut', 'gluten free', 'sesame'"], "expected": "Filter or exclude by allergen", "actual": "Only cuisine chips; 'No kitchens found'"},
  impact="Allergic customers must open every dish of every shop.")
C("vendor-whatsapp", "P2", "A vendor cannot connect their own WhatsApp; WhatsApp ordering is one platform-wide setting",
  "api-mcp", "gap/enhancement", ["P2-RAV-18"], n37("E"),
  M("same", [208], "#208 slice 1 (tenant routing of a WhatsApp business number). New: the webhook returns 503 'signing not configured' and is env-configured for one tenant."), "Major gap for the WhatsApp-first vendor cohort.")
C("mcp-hosted-connectors", "P2", "The MCP server cannot be added to hosted ChatGPT/Claude connectors (no OAuth discovery, 5-minute tokens)",
  "api-mcp", "gap/enhancement", ["P2-RAV-07"], n37("E"), M("related", [203], "#203 built the MCP server."), "Major gap for the agent channel.")
C("webhook-payload-thin", "P2", "Order webhooks carry only ids and status, with no items, totals or customer",
  "webhooks", "gap/enhancement", ["P2-RAV-08"], n37("E"), M("related", [205], "#205 built outbound webhooks."), "Major friction: receivers must call back for everything.")
C("api-orders-accept-unavailable", "P2", "Vendor API and MCP orders accept products marked unavailable",
  "orders", "bug", ["P2-RAV-02"], n37("E"), NONE, "Major friction: the kitchen receives orders for sold-out items.",
  "OrderService.createOrder checks shop membership and stock but never 'available' (member cites)", false)
C("finance-404-as-empty-and-nav", "P3", "Scoped users see nav items they cannot use, and a 403 renders as 'No endpoints yet' / 'storefront is live'",
  "staff-scoping", "bug", ["P2-KEM-16"], n37("B"), M("related", [762], "#762 (failed load rendered as empty state)."),
  "Minor: misleading states for scoped staff.", bundle="dashboard-polish")

// =============================== P3 ===============================
C("kds-live-while-api-down", "P3", "With the API unreachable but the socket up, the board says 'Live' for ~50 s while dropping an order",
  "kitchen", "bug", ["P2-TUN-09"], n37("A"), M("related", [106], "#106 (KDS offline UX) handled hard offline only."), "Minor: a narrow failure window.")
C("revoked-kds-stale", "P3", "After access is removed the kitchen keeps showing that shop's tickets (with PII and live buttons) and blames the connection",
  "kitchen", "bug", ["P2-KEM-13"], n37("B"), M("related", [627], "#627 (STOMP grant checked only at SUBSCRIBE); here the API correctly 403s but the screen is not cleared."),
  "Minor: stale PII stays on screen after revoke.")
C("orders-table-responsive", "P3", "The orders table is clipped on tablet and the phone's first screen is an explainer, not orders",
  "orders", "bug", ["P2-KEM-17"], n37("A"), M("related", [699], "#699 tablet chrome."), "Minor friction.", bundle="dashboard-polish")
C("customer-cannot-cancel", "P3", "A customer cannot cancel an order", "orders", "gap/enhancement", ["F-04"], n37("C"), NONE, "Minor gap.",
  repro={"steps": ["Place an order, open My Orders / tracking"], "expected": "Cancel while PENDING", "actual": "No cancel control"}, impact="Customers phone (and cannot, see seller contact) or no-show.")
C("network-error-message", "P3", "A network failure at Place order says only 'Failed to place order. Please try again.'",
  "checkout", "bug", ["F-06"], n37("C"), NONE, "Minor friction.", bundle="checkout-polish",
  repro={"steps": ["Go offline, tap Place order"], "expected": "'You're offline' and 'it may already be placed' guidance", "actual": "Generic failure text"}, impact="Customers fear a double order.")
C("back-forward-wipes-form", "P3", "Back then Forward wipes the checkout address, phone and notes", "checkout", "bug", ["F-08"], n37("C"), NONE, "Minor friction.",
  bundle="checkout-polish", repro={"steps": ["Fill checkout, press Back then Forward"], "expected": "Fields kept", "actual": "Basket kept, address/phone/notes wiped"}, impact="Re-typing on a phone.")
C("checkout-validation-order", "P3", "Empty checkout submit skips the address and takes two rounds, with no error summary",
  "checkout", "bug", ["P2-MAR-10"], n37("C"), NONE, "Minor friction.", bundle="checkout-polish")
C("basket-device-local", "P3", "The basket is device-local: not restored on sign-in and not shared across devices",
  "accounts", "gap/enhancement", ["J-09", "P2-CHA-06"], n37("C"), M("related", [459], "#459 (basket survives sign-out) fixed the opposite direction."), "Minor friction.", primary="P2-CHA-06")
C("signout-icon", "P3", "Sign-out is a tiny unlabelled one-tap icon next to a person icon that does nothing",
  "accounts", "bug", ["J-10", "P2-GRA-12"], n37("C"), NONE, "Minor friction (unlabelled control).", bundle="storefront-polish", primary="P2-GRA-12")
C("no-item-customisation", "P3", "Menu items cannot be customised (no modifiers)", "catalogue", "gap/enhancement", ["J-11"], n37("C"), NONE, "Minor gap versus aggregators.",
  repro={"steps": ["Open any dish"], "expected": "Options/modifiers (spice, sides)", "actual": "None"}, impact="Customers use notes or go elsewhere.")
C("shop-list-no-photos", "P3", "The shop list uses gradients and initials instead of food photos", "discovery", "gap/enhancement", ["J-12"], H33,
  M("related", [546], "#546 look-and-feel review."), "Polish.", bundle="storefront-polish",
  repro={"steps": ["Open /shop"], "expected": "A food photo per shop", "actual": "Gradient + initials"}, impact="Weaker appetite appeal than competitors.")
C("storefront-copy-polish", "P3", "Storefront copy noise: duplicated dishes and alt text, descriptions repeating names, 'Co..', 'Draft' filter, status naming mismatch",
  "content", "bug", ["J-13", "P2-MAR-16", "F-09"], n37("C"), NONE, "Polish.", bundle="storefront-polish", primary="P2-MAR-16")
C("hydration-error-418", "P3", "React hydration error #418 on /shop/orders and /track, and 'Auto-refreshing' on finished orders",
  "tracking", "bug", ["J-14", "P2-GRA-20"], n37("C"), NONE, "Polish.", bundle="storefront-polish", primary="P2-GRA-20")
C("search-result-messaging", "P3", "Postcode search cannot tell invalid, out-of-area and no-kitchens apart, and its result count may not be announced",
  "discovery", "bug", ["J-08", "P2-MAR-18"], H33, M("related", [619, 460], "#619 (closed) postcode search; #460 locality."), "Minor friction.", bundle="storefront-polish", primary="P2-MAR-18")
C("marketing-consent", "P3", "There is no marketing-consent choice or preferences page, and /unsubscribe says 'contact the vendor'",
  "notifications", "gap/enhancement", ["P2-GRA-15"], H31, M("related", [592], "#592 (one-click unsubscribe unwired in k8s)."), "Minor transparency gap (no marketing is sent).")
C("a11y-statement-stale", "P3", "The accessibility statement is stale (claims no skip link, cites WCAG 2.1, excludes basket/confirmation/tracking)",
  "legal-compliance", "content/legal", ["P2-MAR-15"], H31, NONE, "Minor: published statement misdescribes the product.", bundle="public-content")
C("sku-required", "P3", "SKU is required when adding a product (jargon for a stall holder)", "catalogue", "bug", ["ADE-12"], n37("G"), NONE, "Polish.",
  bundle="dashboard-polish", repro={"steps": ["Add product without a SKU"], "expected": "Optional or auto-generated", "actual": "Required"}, impact="Friction for small vendors.")
C("soft-404", "P3", "An unknown shop link returns a soft 404 (HTTP 200 'Shop not found')", "storefront", "bug", ["ADE-17"], n37("C"), NONE, "Polish (SEO).",
  bundle="storefront-polish", repro={"steps": ["GET /shop/does-not-exist"], "expected": "HTTP 404", "actual": "HTTP 200 'Shop not found'"}, impact="Search engines index dead pages.")
C("staff-list-polish", "P3", "Staff list masks emails so same-domain staff are indistinguishable; products table has no Shop column",
  "staff-scoping", "bug", ["P2-KEM-18"], n37("B"), NONE, "Polish.", bundle="dashboard-polish")
C("info-disclosure-403-and-tenant-uuid", "P3", "Another shop's order returns 403 not 404, and public image URLs embed the tenant UUID",
  "security", "bug", ["P2-KEM-19", "F5"], n37("D"), NONE, "Minor information disclosure (existence/ownership mapping).", primary="P2-KEM-19")
C("public-content-gaps", "P3", "Public pages: no vendor-to-vendor confidentiality statement; positioning excludes non-London, non-West-African operators",
  "content", "content/legal", ["F6", "C-08"], H32, NONE, "Minor trust/positioning gaps.", bundle="public-content",
  repro={"steps": ["Read /legal and /for-operators"], "expected": "A confidentiality assurance; inclusive positioning", "actual": "None; 'ONE LONDON CLUSTER… Nigerian and West African'"}, impact="Vendors outside the niche self-exclude; rivals' data safety is unstated.")
C("marketing-features-gap", "P3", "No email campaigns, loyalty or vouchers", "content", "gap/enhancement", ["C-13"], N, NONE, "Minor gap versus competitors.",
  repro={"steps": ["Open /dashboard/marketing"], "expected": "Campaigns, loyalty, vouchers", "actual": "Promotions and announcements only"}, impact="Weaker retention offer.")
C("custom-domain-gap", "P3", "No custom storefront domain (SUSPECTED)", "storefront", "gap/enhancement", ["C-14"], N, NONE, "Minor gap.",
  repro={"steps": ["Look for a domain setting in shop edit"], "expected": "Custom domain", "actual": "Only /shop/slug"}, impact="Brand-conscious vendors hesitate.")
C("webhook-polish", "P3", "Webhooks have no test event, no request/response body, no delete, and do not follow redirects (Apps Script fails)",
  "webhooks", "bug", ["P2-RAV-11", "P2-RAV-09"], n37("E"), NONE, "Minor integrator friction (redirect part SUSPECTED).", bundle="integrator-polish", primary="P2-RAV-11")
C("api-contract-polish", "P3", "API/MCP contract polish: MCP drops typed error details, OpenAPI hygiene problems, replay indistinguishable from fresh",
  "api-mcp", "bug", ["P2-RAV-13", "P2-RAV-15", "P2-RAV-17"], n37("E"), NONE, "Minor integrator friction.", bundle="integrator-polish", primary="P2-RAV-15")


// ---------------------------------------------------------------- issue tracker snapshot (fetched once)
const ISSUES = JSON.parse(fs.readFileSync(path.join(__dirname, "issues-snapshot.json"), "utf8"));
for (const c of CL) for (const n of c.match.issues) {
  const iss = ISSUES.find((x) => x.number === n);
  if (!iss) die(`${c.key}: issue #${n} not in tracker snapshot`);
  if (c.match.classification === "same" && iss.state !== "OPEN") die(`${c.key}: 'same' must be an OPEN issue (#${n} is ${iss.state})`);
  if (c.match.classification === "regression-of-closed" && iss.state !== "CLOSED") die(`${c.key}: regression target #${n} is not CLOSED`);
}

// ---------------------------------------------------------------- cross-cutting themes
const THEMES = [
  ["The browser's copy of the order is trusted at the moment of commitment",
    ["basket-not-revalidated", "allergen-record-customer", "allergen-ack-not-stored", "promo-not-applied", "confirmation-lost"],
    "Price, availability and the allergen panel are captured into localStorage or on mount and never re-checked; the server then silently reprices, refuses one line at a time, or records a different allergen set, and the acknowledgement itself never leaves the browser. One server-quoted 'review your order' step (price, availability, allergens, minimum, promotion) with the quote id carried on submit fixes the family."],
  ["'Say ≠ data': the UI asserts things the data does not back",
    ["hygiene-claim-unbacked", "second-shop-cannot-go-live", "finance-not-shop-scoped", "finance-404-as-empty-and-nav", "fulfilment-settings", "delivery-tracking-says-collection", "order-comms-thin", "stripe-on-cash-checkout", "cookie-policy-inaccurate", "a11y-statement-stale", "kds-live-while-api-down", "revoke-escalation", "erasure-noop", "internal-pages-public", "product-delete-refused"],
    "'UK food-hygiene verified', 'Your storefront is live', 'scoped to this shop', 'No financial data yet' (really 403), '£2.99 delivery' (charged £0), 'Ready for collection' (delivery), 'shop named on your confirmation', 'no cookies until payment', 'Live', 'no row' on Staff (really Group admin), DSAR 'completed' (nothing erased), 'payouts: Full', 'referenced by an existing order'. Each is a separate bug; the systemic fix is a review rule that every status claim reads from the same source the action uses, plus a test that asserts the data, not the rendered string."],
  ["Implicit tenant-wide admin is the default identity",
    ["default-admin", "revoke-escalation", "no-staff-invite", "kitchen-pii-overexposed", "finance-404-as-empty-and-nav", "staff-list-polish"],
    "With strict-scoping off, any ungranted tenant user is GROUP_ADMIN; revoking the last grant re-creates that state; the only staff onboarding is self-registration. Flip the default (ungranted = no access, explicit invite carries the grant) and most staff-scoping clusters collapse."],
  ["No operator, no human channel",
    ["hygiene-claim-unbacked", "vendor-no-way-in", "seller-identity-missing", "dsar-access-undelivered", "no-developer-credentials", "vendor-whatsapp", "no-review-flow", "demo-data-visible", "commercial-terms-absent"],
    "The deliberate no-platform-operator decision leaves every two-actor step with nobody on the other side: FSA manual review is self-approved, prospects and customers have no one to contact, DSAR access has no fulfiller, API credentials and WhatsApp numbers exist only as realm-import or env config, test reviews and ledger rows cannot be removed. Either name an operator role (support/compliance) or design each step to be completable by the vendor with evidence."],
  ["The order lifecycle has no PENDING→kitchen bridge and no time model",
    ["new-orders-invisible", "mcp-orders-stay-draft", "pending-forever", "no-ready-time", "no-order-ahead", "cannot-stop-orders", "kds-session-lapse", "mute-survives-signout", "double-tap-skips-to-ready"],
    "New orders wait as PENDING on a page the kitchen does not watch, with no alert; MCP orders stay DRAFT; nothing expires an unanswered order; there is no promised time, no scheduled time and no 'pause'. A single state-and-time model for the kitchen (arrival alert, accept-with-ETA, auto-reject timeout, pause) addresses the cluster."],
  ["Validation lives in one entry point, not in the domain",
    ["api-orders-accept-unavailable", "contact-validation", "quantity-unbounded", "sync-orphan-products", "sync-drops-items", "cross-shop-review", "menu-import-and-copy", "shop-delete-orphans-products", "stock-tracking-silently-off", "allergen-ack-not-stored"],
    "The storefront path checks availability, contact details and the allergen gate; the vendor API, MCP, sync and CSV import do not, and 'no shop' products mean 'all shops' in the spec, 'no storefront' in the UI and 'any order' in the API. Move each invariant into the domain service every writer calls."],
  ["The customer leaves with no durable record",
    ["order-comms-thin", "allergen-record-customer", "confirmation-lost", "no-ready-time", "seller-identity-missing", "no-self-serve-account"],
    "Confirmation lives only in component state at /checkout; emails carry no shop, items, total or allergens; tracking has no items or time; there is no account page. After a silent price change or an allergen dispute the customer has nothing in writing."],
  ["Local and dev settings hide production defects",
    ["local-stack-no-card-path", "dsar-verify-link", "verified-email-guard", "demo-data-visible"],
    "Compose drops the Stripe keys (wrong var name, no build ARG), so every local and persona run is cash-only; DSAR verify links point at an unset base URL; dev disables email verification; seed/E2E data shows on public pages. The money path and DSAR confirmation have never been exercised locally."],
];

// ---------------------------------------------------------------- cleanup (from every report's cleanup section)
const CLEANUP = [
  ["Pass 1 — Ade (01)", [
    "Tenant-A products c49a371c-c7f3-42a3-bb9e-5f901db98e3a (ADE-SUYA) and 0254c57e-f3b1-4664-aa8f-04a88e73a244 (ADE-JOL) + their media assets: available=false, shopId=null, cannot be deleted ({{product-delete-refused}}). Shop d46fe43f-… already deleted."]],
  ["Pass 1 — Jordan (04)", ["Pending cash orders ORD-00000000-20261003-3FD79CC4, -B090D3F3, -6CC30687 (customer cannot cancel).", "Customer jordan-60809@example.test."]],
  ["Pass 1 — Priya (05)", ["ORD-00000000-20261003-8839B69F at Brixton Village Grill, PENDING, guest priya-05-9250@example.test."]],
  ["Pass 1 — Sam (06)", ["Customer sam-cadcb314@example.test.", "Six cash orders at Mama Ade's: ORD-00000000-20261003-{BB9D0024, 57EB73A4, 27082946, 40C5FC61, 7BF67717, 888A1F58} — cancel from the vendor side. No DSAR lodged."]],
  ["Pass 1 — Claire (08)", ["Not created by the persona, but flagged: public 'E2E launch offer — E2E 20% OFF' promo on Mama Ade's, Pending test orders 11–27 days old, a real person's name + Hotmail address on the dashboard, a `<b>x</b>` test customer ({{demo-data-visible}})."]],
  ["Pass 1 — Dele (07)", ["None: his two webhook subscriptions were created and deleted."]],
  ["Pass 2 — Funmi (11)", ["None outstanding: 9 orders Completed/Cancelled; Sweet Potato Fries available again; Peri Peri Chicken back to £9.00 (verified on the public API)."]],
  ["Pass 2 — Kemi (12)", ["3 ledger rows that cannot be deleted (£48.00 revenue, £7.99 VAT) for deleted orders …85C353C4, …24C38E33, …AE561258 (no ledger delete).", "Bayo (tenant-b-user) is back to one auto-created JIT GROUP_ADMIN row (new row id)."]],
  ["Pass 2 — Tunde (13)", ["None outstanding: 12 orders COMPLETED, TUN-TEST-1 deleted, webhook 41331306… revoked, test customer deleted, self-granted STAFF row revoked."]],
  ["Pass 2 — Grace (14)", ["Customer grace-p2-65308@example.test (jtoye-customers) still active — no self-serve close and the erasure did nothing.", "Public review b2e8cff0-3b9b-40b1-ba42-d7de4b218a2f on Mama Ade's ('…persona test P2-GRA') — no delete path; operator removal needed.", "Two dsar_request rows: ACCESS outstanding; ERASURE marked completed with nothing erased.", "Orders ORD-00000000-20261003-45B3ECD7 (COMPLETED) and -ECFB3041 (CANCELLED) remain."]],
  ["Pass 2 — Marcus (15)", ["Customer marcus-15-7731@example.test (no self-serve delete).", "Orders ORD-00000000-20261003-50F52C87 and -F82D641A cancelled via the vendor API."]],
  ["Pass 2 — Kyle (16)", ["16 kyle-p2 orders remain as CANCELLED (Kyle reports no vendor delete in the UI; DELETE /api/v1/orders/{id} exists per P2-KEM-07 but was not used)."]],
  ["Pass 2 — Bola (17)", ["Order ORD-00000000-20261003-94ED4703 (id 8dfc7130-570b-4dbc-aa7a-b6705a79886c), COMPLETED, 'TEST PURCHASE (persona 17)'.", "Public 5★ review e1ca2396-a94c-4094-8cdf-59a0f3c5ae86 on Brixton Village Grill ('bola-17 cross-shop probe') — gives Brixton a public rating and JSON-LD aggregateRating; operator removal needed."]],
  ["Pass 2 — Ravi (18)", ["Webhook subscription 156f721d-7f6f-426d-84a7-1ea248730d6f (https://example.com/ravi-gsheet-hook) REVOKED but listed (no delete endpoint) + its 2 FAILED delivery rows (30-day retention).", "Outbox/webhook events already emitted for his 8 deleted orders."]],
  ["Pass 2 — Nkechi (19)", ["11 chaos-p2 products (CHAOS-P2-SUYA, -PUFF, -ZOBO, -X1..X8) set available=false; deletion refused (409, referenced by cancelled orders).", "Peckham Jollof Co. openingHours is now {} instead of null (same behaviour; restoring null needs a DB write).", "6 orders CANCELLED (ORD-…-1E4A123A, -D2F46664, -DA806F54, -39B2CE9E, -DF8F5148, -25D31E04)."]],
];

const P1_GOODS_NARRATIVE = [
  ["01-new-vendor-ade", "Landing headline and food-first look; automatic geocoding of the shop postcode; photo pipeline (4000x3000 → light WebP); lovely mobile storefront; honest 'what we don't do' boundaries."],
  ["04-hungry-customer-jordan", "Postcode search with real distances; menu photos, dietary chips, allergen panel; sticky basket bar; basket survives sign-up and guest orders appear in My Orders; sign-out clears the basket on a shared phone."],
  ["05-allergy-customer-priya", "Privacy notice's allergen section; vendor sees the note verbatim with per-line chips snapshotted at order time."],
  ["06-impatient-privacy-sam", "Plain-English validation next to each field; forgiving postcode/phone formats; basket survives a lapsed session with silent token refresh; honest legal pages (item-by-item cookie list, retention schedule); sign-out clears basket and customer cookies."],
  ["07-curious-rival-tenant-dele", "Order tracking bound to number + email; consistent RFC 7807 errors."],
  ["08-sceptical-evaluator-claire", "Candid boundary statements; privacy notice; CSV import with template + photo scan; VAT ledger."],
];

// ---------------------------------------------------------------- goods
const GOODS = [
  ["Order placement cannot double-order (idempotency under taps, tabs, offline, lost responses)",
    ["P2-CHA-12", "P2-CHA-13", "P2-RAV-20"],
    "Sam (pass 1): triple-tap, two tabs, offline retry and lost-response retry each gave exactly one order; 'Add' becomes a stepper so frantic taps add one."],
  ["Server-authoritative pricing and order validation",
    ["P2-KYL-P2", "P2-KYL-P3", "P2-FUN-19", "P2-CHA-14", "P2-RAV-22", "P2-CHA-16"], "CAVEAT: the availability check holds on the STOREFRONT path only; vendor API and MCP orders accept unavailable products (cluster api-orders-accept-unavailable). Extend it, do not copy it."],
  ["Allergen safety chain (order-time snapshot, kitchen banner, checkout gate, PPDS label)",
    ["P2-REG-15", "P2-REG-16", "P2-CHA-15", "P2-TUN-15", "P2-MAR-P2"],
    "Priya (pass 1): the gate refuses without the tick, role=alert, focus moved, keyboard-operable; bold allergen words + named chips; 'We do not store your allergies'. Ade: 14 plain-English allergen boxes and a per-product label."],
  ["Tenant and shop isolation", ["F1", "F4", "P2-KEM-20", "P2-KEM-21", "P2-KEM-22"],
    "Dele: cross-tenant reads return 404 (not 403); consistent RFC 7807. Claire: clear shop-scoped staff roles."],
  ["Order-tracking privacy", ["F3", "P2-KYL-P5", "P2-GRA-22"], "Jordan: a real guest checkout, with tracking by order number + email."],
  ["Security hygiene (SSRF, XSS, webhook secrets)", ["F2", "P2-KYL-P1", "P2-RAV-21"], "Dele: SSRF check runs on the RESOLVED IP."],
  ["Kitchen live operations", ["P2-FUN-17", "P2-FUN-18", "P2-FUN-20", "P2-TUN-13", "P2-TUN-14"], "Claire: per-shop kitchen banner and 'Print all'."],
  ["Transparent pricing and payment wording", ["P2-REG-17", "P2-GRA-21"],
    "Jordan: honest, consistent fees; unit counts and totals agree on every surface. Ade: the price stated plainly."],
  ["Step-by-step notifications", ["P2-GRA-23"], "Jordan: orders placed instantly and the email arrives within seconds."],
  ["Review gating (with a caveat)", ["P2-REG-18", "P2-KYL-P4"],
    "CAVEAT: P2-KYL-P4 says you cannot review a shop you never ordered from, but P2-REG-01 shows that holds only ACROSS tenants; within one tenant a buyer of shop A can review shop B. Preserve every gate listed; fix the shop check (cluster cross-shop-review)."],
  ["Accessibility foundations", ["P2-MAR-P1", "P2-MAR-P3", "P2-GRA-24"], "Priya: visible focus rings, a skip link, no horizontal scroll at 200%."],
  ["API contract fidelity", ["P2-RAV-19"], "Claire: storefront JSON-LD and meta; ~0.94 s throttled-mobile LCP."],
  ["UK-time correctness across midnight", ["P2-CHA-18"], ""],
];
// which fix clusters put which goods at risk (regression guards)
const GUARDS = [
  ["basket-not-revalidated", "Order placement cannot double-order; Server-authoritative pricing — the re-validation step must reuse the same Idempotency-Key and must not move price authority to the client."],
  ["allergen-ack-not-stored", "Allergen safety chain — keep the refuse-not-disable gate, role=alert, focus move; add the server record without weakening the client gate."],
  ["allergen-record-customer", "Allergen safety chain — the order-time snapshot (V63) stays the source; the customer view reads it, never a live join."],
  ["default-admin", "Tenant and shop isolation — keep the last-admin lockout guard (P2-KEM-22) and next-request revoke (P2-KEM-21) while changing the JIT default."],
  ["revoke-escalation", "Tenant and shop isolation — as above."],
  ["new-orders-invisible", "Kitchen live operations — keep live list updates in 1–5 s, big bump buttons and the 38-minute stay-live behaviour."],
  ["kds-session-lapse", "Kitchen live operations — sign-out on a shared tablet must still really end the session (P2-TUN-14)."],
  ["xff-ratelimit-bypass", "Order-tracking privacy — tracking stays bound to number + email; idempotency keys remain non-abusable."],
  ["cross-shop-review", "Review gating — keep completed-order, matching-email and one-per-order gates."],
  ["confirmation-lost", "Order placement cannot double-order — a confirmation route must not re-POST on refresh."],
  ["mcp-list-products-wrong", "API contract fidelity — any MCP/OpenAPI change must keep the published contract matching live responses."],
  ["stripe-on-cash-checkout", "Transparent payment wording; Order placement idempotency — lazy-loading Stripe must not change the card path's single-submit behaviour."],
];

// ---------------------------------------------------------------- assemble + reconcile
const PRI_ORDER = { P0: 0, P1: 1, P2: 2, P3: 3 };
CL.sort((a, b) => PRI_ORDER[a.pri] - PRI_ORDER[b.pri]); // Array.prototype.sort is stable

const seen = {};
const bump = (id) => { seen[id] = (seen[id] || 0) + 1; };
CL.forEach((c) => c.members.forEach(bump));
GOODS.forEach(([, ids]) => ids.forEach(bump));
const errors = [];
for (const id of Object.keys(ALL)) if (seen[id] !== 1) errors.push(`${id}: placed ${seen[id] || 0} times`);
for (const id of Object.keys(seen)) if (!ALL[id]) errors.push(`${id}: referenced but not an input finding`);
GOODS.forEach(([, ids]) => ids.forEach((id) => { if (ALL[id] && ALL[id].severity !== "positive") errors.push(`${id}: in goods but ${ALL[id].severity}`); }));
CL.forEach((c) => c.members.forEach((id) => { if (ALL[id] && ALL[id].severity === "positive") errors.push(`${id}: positive in cluster ${c.key}`); }));
if (new Set(CL.map((c) => c.key)).size !== CL.length) errors.push("duplicate cluster keys");
for (const c of CL) {
  if (c.primary && !c.members.includes(c.primary)) errors.push(`${c.key}: primary ${c.primary} not a member`);
  if (c.match.classification === "same" && c.bundle) errors.push(`${c.key}: same-match cannot be bundled`);
}
if (errors.length) die(errors.join("\n"));

function evPath(f) {
  if (f.source === "pass-1") return [`${P1DIR}/${f.persona}/ (refs: ${f.evidence[0].replace(/`/g, "")})`];
  if (f.source === "coordinator") return [...f.evidence];
  return (f.evidence || []).map((e) => `${P2DIR}/${f.persona}/${e}`);
}
const short = (p) => (p === "coordinator" ? "Coord" : `${p.split("-").pop().replace(/^./, (x) => x.toUpperCase())}(${/^1\d-/.test(p) ? "P2" : "P1"})`);

const KEY2ID = {};
const catalogue = CL.map((c, i) => {
  const uxt = `UXT-${String(i + 1).padStart(3, "0")}`;
  KEY2ID[c.key] = uxt;
  const mem = c.members.map((id) => ALL[id]);
  const sev = mem.reduce((a, f) => (SEV_RANK[f.severity] > SEV_RANK[a] ? f.severity : a), "polish");
  const status = mem.some((f) => f.status === "CONFIRMED") ? "CONFIRMED" : "SUSPECTED";
  const prim = c.primary ? ALL[c.primary] : (mem.find((f) => f.source !== "pass-1") || mem[0]);
  const rp = c.repro ? { ...c.repro } : { steps: prim.steps || [], expected: prim.expected || "", actual: prim.actual || "" };
  if (!rp.steps.length) die(`${c.key}: no repro steps (pass-1-only cluster needs an override)`);
  const imp = c.impact || prim.realWorldImpact || "";
  if (!imp) die(`${c.key}: no impact`);
  const sm = c.home.match(/· (37-[A-G].*)$/);
  return {
    id: uxt, slug: c.key, title: c.title, area: c.area, type: c.typ, severity: sev, priority: c.pri,
    priorityJustification: c.why, status,
    members: mem.map((f) => ({ id: f.id, persona: f.persona, source: f.source, severity: f.severity, status: f.status, title: f.title })),
    personas: [...new Set(mem.map((f) => f.persona))].sort(),
    repro: rp, reproFrom: c.repro ? "override (pass-1 / coordinator narrative)" : prim.id,
    realWorldImpact: imp, evidence: mem.flatMap(evPath),
    suspectedCodeLocation: c.code ? { location: c.code, verified: c.verified } : null,
    existingIssue: c.match, proposedHome: c.home, subTheme: sm ? sm[1] : null, p3Bundle: c.bundle,
  };
});
fs.writeFileSync(path.join(OUT, "catalogue.json"), JSON.stringify(catalogue, null, 2) + "\n");
const R = (k) => { if (!KEY2ID[k]) die(`unknown cluster key ${k}`); return KEY2ID[k]; };

// ---------------------------------------------------------------- counts
const countBy = (arr, f) => arr.reduce((m, x) => { const k = f(x); m[k] = (m[k] || 0) + 1; return m; }, {});
const byPri = countBy(catalogue, (c) => c.priority);
const byType = countBy(catalogue, (c) => c.type);
const byArea = countBy(catalogue, (c) => c.area);
const byHome = countBy(catalogue, (c) => (c.subTheme ? c.subTheme : c.proposedHome));
const memberCount = catalogue.reduce((n, c) => n + c.members.length, 0);
const goodsCount = GOODS.reduce((n, g) => n + g[1].length, 0);
const p1ByPersona = countBy(p1, (r) => r.persona);
const p2ByPersona = countBy(p2, (r) => r.persona);
const p1Pos = p1.filter((r) => r.severity === "positive").length;
const p2Pos = p2.filter((r) => r.severity === "positive").length;
const tbl = (obj, h1) => [`| ${h1} | n |`, "|---|---:|", ...Object.entries(obj).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([k, v]) => `| ${k} | ${v} |`)].join("\n");
const fmtMatch = (m) => m.classification === "none" ? "none" : `${m.classification} ${m.issues.map((n) => "#" + n).join(", ")}`;
const homeShort = (c) => (c.subTheme ? c.subTheme : c.proposedHome);

// ---------------------------------------------------------------- issue plan
const sameClusters = catalogue.filter((c) => c.existingIssue.classification === "same");
const commentTargets = {};
sameClusters.forEach((c) => c.existingIssue.issues.forEach((n) => { (commentTargets[n] = commentTargets[n] || []).push(c); }));
const toFile = catalogue.filter((c) => c.existingIssue.classification !== "same");
const bundles = {};
toFile.filter((c) => c.p3Bundle).forEach((c) => { (bundles[c.p3Bundle] = bundles[c.p3Bundle] || []).push(c); });
const singles = toFile.filter((c) => !c.p3Bundle);
const nNewIssues = singles.length + Object.keys(bundles).length;
const nComments = Object.keys(commentTargets).length;

const TYPE_LABEL = { "bug": "bug", "gap/enhancement": "enhancement", "content/legal": "compliance", "env/tooling": "tech-debt" };
const AREA_LABEL = { security: "security", "staff-scoping": "security", privacy: "compliance", "legal-compliance": "compliance", allergens: "compliance", accessibility: "accessibility" };
const labelsFor = (cs) => {
  const s = new Set(["ux-persona-test"]);
  const pri = cs.map((c) => c.priority).sort()[0];
  s.add(pri);
  cs.forEach((c) => { s.add(TYPE_LABEL[c.type]); if (AREA_LABEL[c.area]) s.add(AREA_LABEL[c.area]); });
  return [...s];
};
const relatedLine = (c) => {
  const m = c.existingIssue;
  if (m.classification === "none") return "";
  const verb = m.classification === "regression-of-closed" ? "Regression of closed" : "Related to";
  return `${verb} ${m.issues.map((n) => "#" + n).join(", ")}: ${m.note}`;
};
function issueBody(c) {
  const lines = [];
  lines.push(`**${c.id} · ${c.priority} · ${c.severity} · ${c.status}** — ${c.priorityJustification}`);
  lines.push("");
  lines.push("**Steps**");
  c.repro.steps.forEach((s, i) => lines.push(`${i + 1}. ${s}`));
  lines.push(`**Expected:** ${c.repro.expected}`);
  lines.push(`**Actual:** ${c.repro.actual}`);
  lines.push(`**Real-world impact:** ${c.realWorldImpact}`);
  lines.push(`**Found by:** ${c.members.map((m) => `${m.id} (${short(m.persona)})`).join(", ")}`);
  if (c.suspectedCodeLocation) lines.push(`**Suspected location (${c.suspectedCodeLocation.verified ? "verified in source" : "cited by tester, not verified"}):** ${c.suspectedCodeLocation.location}`);
  const rl = relatedLine(c);
  if (rl) lines.push(`**${rl.split(":")[0]}:**${rl.slice(rl.indexOf(":") + 1)}`);
  lines.push(`**Evidence:** ${c.evidence.slice(0, 4).map((e) => "`" + e + "`").join(", ")}${c.evidence.length > 4 ? ` (+${c.evidence.length - 4} more in catalogue.json)` : ""}`);
  lines.push(`**Proposed home:** ${c.proposedHome}`);
  return lines.join("\n");
}

let plan = [];
plan.push("# Issue plan — persona user-testing 2026-10-03 (pass 1 + pass 2)");
plan.push("");
plan.push("Nothing here has been filed. This is the exact set to file once approved. Source of truth: `catalogue.json`.");
plan.push("No AI-attribution lines in any body (owner ruling 2026-08-30).");
plan.push("");
plan.push("## Totals");
plan.push("");
plan.push(`- Clusters: **${catalogue.length}**`);
plan.push(`- Covered by an existing OPEN issue ("same"): **${sameClusters.length} clusters → ${nComments} comments** on ${Object.keys(commentTargets).map((n) => "#" + n).join(", ")}`);
plan.push(`- To file: **${toFile.length} clusters → ${nNewIssues} new issues** (${singles.length} single-cluster issues + ${Object.keys(bundles).length} P3 polish bundles covering ${toFile.length - singles.length} clusters)`);
plan.push(`- Plus **1 epic** tracking issue (below), so **${nNewIssues + 1} new issues + ${nComments} comments** in total.`);
plan.push("");
plan.push("New label: **`ux-persona-test`** (colour suggestion `#5319e7`, description: \"Found by persona user-testing (2026-10-03 passes 1–2)\"). Existing labels reused: `P0`–`P3`, `bug`, `enhancement`, `compliance`, `security`, `accessibility`, `tech-debt`.");
plan.push("");
plan.push("Filing order: P0 first (they gate a first real tenant), then P1, then the epic body's links are filled in.");
plan.push("");
plan.push("## A. Comments on existing open issues");
plan.push("");
for (const [n, cs] of Object.entries(commentTargets)) {
  const iss = ISSUES.find((x) => x.number === Number(n));
  plan.push(`### #${n} — ${iss ? iss.title : "(title not found)"}`);
  plan.push("");
  plan.push("Comment body:");
  plan.push("");
  plan.push("> **Reproduced by persona user-testing (2026-10-03).**");
  for (const c of cs) {
    plan.push(`> - **${c.id} (${c.priority})** ${c.title}. ${c.existingIssue.note}`);
    plan.push(`>   Repro: ${c.repro.steps.join(" → ")}. Actual: ${c.repro.actual}`);
    plan.push(`>   Found by ${c.members.map((m) => m.id).join(", ")}. Evidence: ${c.evidence.slice(0, 3).map((e) => "`" + e + "`").join(", ")}.`);
  }
  plan.push("> Label suggestion: add `ux-persona-test`.");
  plan.push("");
}
plan.push("## B. New issues (one per cluster)");
plan.push("");
plan.push("| # | Cluster | P | Title | Labels | Links |");
plan.push("|---:|---|---|---|---|---|");
let k = 0;
for (const c of singles) {
  k++;
  plan.push(`| ${k} | ${c.id} | ${c.priority} | ${c.title} | ${labelsFor([c]).join(", ")} | ${fmtMatch(c.existingIssue)} |`);
}
for (const [b, cs] of Object.entries(bundles)) {
  k++;
  plan.push(`| ${k} | ${cs.map((c) => c.id).join(" + ")} | P3 | Polish bundle: ${b} (${cs.length} clusters) | ${labelsFor(cs).join(", ")} | ${cs.map((c) => fmtMatch(c.existingIssue)).filter((x) => x !== "none").join("; ") || "none"} |`);
}
plan.push("");
plan.push("### Issue bodies");
plan.push("");
for (const c of singles) {
  plan.push(`#### [${c.id}] ${c.title}`);
  plan.push("");
  plan.push(`Title: \`${c.title}\` · Labels: ${labelsFor([c]).join(", ")}`);
  plan.push("");
  plan.push(issueBody(c));
  plan.push("");
}
plan.push("## C. P3 polish bundles (one issue per surface)");
plan.push("");
for (const [b, cs] of Object.entries(bundles)) {
  plan.push(`#### Polish bundle: ${b}`);
  plan.push("");
  plan.push(`Title: \`Persona polish (${b}): ${cs.length} small defects\` · Labels: ${labelsFor(cs).join(", ")}`);
  plan.push("");
  for (const c of cs) {
    plan.push(`- [ ] **${c.id}** ${c.title} — ${c.repro.actual.slice(0, 220)}${c.repro.actual.length > 220 ? "…" : ""} (${c.members.map((m) => m.id).join(", ")})${c.existingIssue.classification !== "none" ? ` · ${fmtMatch(c.existingIssue)}` : ""}`);
  }
  plan.push("");
}
plan.push("## D. Epic tracking issue");
plan.push("");
plan.push("Title: `[UXT-EPIC] Persona user-testing 2026-10-03: real-world operations readiness (" + catalogue.length + " clusters)` · Labels: ux-persona-test, enhancement");
plan.push("");
plan.push("Body:");
plan.push("");
plan.push("```markdown");
plan.push(`Two passes of persona user-testing (pass 1: 6 personas; pass 2: 9 personas) produced ${p1.length + p2.length + 1} findings (${p1.length} pass-1 rows, ${p2.length} pass-2 entries, 1 coordinator observation). Deduplicated into ${catalogue.length} clusters plus ${goodsCount} positives kept as regression guards. Full catalogue: \`.planning/ux-persona-test-20261003-pass2/consolidated/CATALOGUE.md\`.`);
plan.push("");
plan.push(`Counts: P0 ${byPri.P0 || 0} · P1 ${byPri.P1 || 0} · P2 ${byPri.P2 || 0} · P3 ${byPri.P3 || 0}. Proposed home for most: NEW Phase 37 – Real-world operations readiness (sub-themes 37-A..37-G); legal/allergen/privacy gaps reopen Phase 31 as gap-closure; money items go to Phase 30.`);
plan.push("");
plan.push("### P0 (fix before any real tenant)");
catalogue.filter((c) => c.priority === "P0").forEach((c) => plan.push(`- [ ] ${c.id} ${c.title} — ${c.existingIssue.classification === "same" ? "#" + c.existingIssue.issues[0] + " (comment)" : "#TBD"}`));
plan.push("### P1");
catalogue.filter((c) => c.priority === "P1").forEach((c) => plan.push(`- [ ] ${c.id} ${c.title} — ${c.existingIssue.classification === "same" ? "#" + c.existingIssue.issues[0] + " (comment)" : "#TBD"}`));
plan.push("### P2");
catalogue.filter((c) => c.priority === "P2").forEach((c) => plan.push(`- [ ] ${c.id} ${c.title} — ${c.existingIssue.classification === "same" ? "#" + c.existingIssue.issues[0] + " (comment)" : "#TBD"}`));
plan.push("### P3");
const p3Singles = catalogue.filter((c) => c.priority === "P3" && !c.p3Bundle);
p3Singles.forEach((c) => plan.push(`- [ ] ${c.id} ${c.title} — #TBD`));
Object.entries(bundles).forEach(([b, cs]) => plan.push(`- [ ] Polish bundle ${b}: ${cs.map((c) => c.id).join(", ")} — #TBD`));
plan.push("");
plan.push("### Systemic themes (root causes behind many clusters)");
THEMES.forEach(([t, keys]) => plan.push(`- ${t} (${keys.map(R).join(", ")})`));
plan.push("");
plan.push("### Comments posted on existing issues");
Object.entries(commentTargets).forEach(([n, cs]) => plan.push(`- #${n}: ${cs.map((c) => c.id).join(", ")}`));
plan.push("```");
plan.push("");
fs.writeFileSync(path.join(OUT, "issue-plan.md"), plan.join("\n"));

// ---------------------------------------------------------------- CATALOGUE.md
let md = [];
md.push("# Persona user-testing — consolidated finding catalogue (passes 1 + 2, 2026-10-03)");
md.push("");
md.push(`Built ${new Date().toLocaleDateString("en-CA")} by \`consolidate.cjs\` from the two passes' reports and findings files; machine-readable form in \`catalogue.json\`, pass-1 rows normalised in \`pass1-findings.json\`, positives in \`goods-to-preserve.md\`, filing plan in \`issue-plan.md\`. Issue tracker matched read-only against ${ISSUES.length} issues (open + closed), fetched once.`);
md.push("");
md.push("## Reconciliation (every input lands exactly once)");
md.push("");
md.push("| Input | Count |");
md.push("|---|---:|");
Object.entries(p1ByPersona).forEach(([p, n]) => md.push(`| pass-1 table rows — ${p} | ${n} |`));
md.push(`| **pass-1 total** (personas 02, 03 have no report) | **${p1.length}** |`);
Object.entries(p2ByPersona).forEach(([p, n]) => md.push(`| pass-2 findings.json — ${p} | ${n} |`));
md.push(`| **pass-2 total** | **${p2.length}** |`);
md.push(`| coordinator (COORD-01) | 1 |`);
md.push(`| **All inputs** | **${p1.length + p2.length + 1}** |`);
md.push("");
md.push("| Placed in | Count |");
md.push("|---|---:|");
md.push(`| cluster memberships (${catalogue.length} clusters) | ${memberCount} |`);
md.push(`| goods-to-preserve (positives: ${p1Pos} pass-1 + ${p2Pos} pass-2) | ${goodsCount} |`);
md.push(`| **Total placed** | **${memberCount + goodsCount}** |`);
md.push("");
md.push(`Check: ${memberCount} + ${goodsCount} = ${memberCount + goodsCount} = ${p1.length} + ${p2.length} + 1. The build script fails (exit 1) if any input id is placed 0 or 2+ times, if a positive lands in a cluster, or if a non-positive lands in goods. Re-verify with: \`jq '[.[].members|length]|add' catalogue.json\` and \`jq length pass1-findings.json\`.`);
md.push("");
md.push("## Counts");
md.push("");
md.push(`**By priority:** P0 ${byPri.P0 || 0} · P1 ${byPri.P1 || 0} · P2 ${byPri.P2 || 0} · P3 ${byPri.P3 || 0} (clusters).`);
md.push("");
md.push("Priority rule: P0 = in production would cause legal breach, user-safety harm, privilege escalation, data-protection failure or silent data corruption (incl. price charged ≠ price shown); P1 = blocks a core vendor/customer journey; P2 = major friction/trust; P3 = minor/polish.");
md.push("");
md.push(tbl(byType, "Type"));
md.push("");
md.push(tbl(byArea, "Area"));
md.push("");
md.push(tbl(byHome, "Proposed home"));
md.push("");
md.push(`**Existing-issue matches:** same ${catalogue.filter((c) => c.existingIssue.classification === "same").length} · related ${catalogue.filter((c) => c.existingIssue.classification === "related").length} · regression-of-closed ${catalogue.filter((c) => c.existingIssue.classification === "regression-of-closed").length} · none ${catalogue.filter((c) => c.existingIssue.classification === "none").length}.`);
md.push("");
md.push("## Cross-cutting themes (systemic root causes)");
md.push("");
THEMES.forEach(([t, keys, body], i) => {
  md.push(`${i + 1}. **${t}** — ${body} Clusters: ${keys.map(R).join(", ")}.`);
  md.push("");
});
md.push("## All clusters");
md.push("");
md.push("| ID | P | Sev | Title | Personas | Existing match | Proposed home |");
md.push("|---|---|---|---|---|---|---|");
catalogue.forEach((c) => md.push(`| ${c.id} | ${c.priority} | ${c.severity} | ${c.title} | ${c.personas.map(short).join(", ")} | ${fmtMatch(c.existingIssue)} | ${homeShort(c)} |`));
md.push("");
md.push("## P0 and P1 detail");
md.push("");
catalogue.filter((c) => c.priority === "P0" || c.priority === "P1").forEach((c) => {
  md.push(`### ${c.id} [${c.priority}] ${c.title}`);
  md.push("");
  md.push(`- **Why ${c.priority}:** ${c.priorityJustification}`);
  md.push(`- **Members:** ${c.members.map((m) => `${m.id} (${short(m.persona)}, ${m.severity}, ${m.status})`).join("; ")}`);
  md.push(`- **Repro:** ${c.repro.steps.join(" → ")}`);
  md.push(`- **Expected:** ${c.repro.expected}`);
  md.push(`- **Actual:** ${c.repro.actual}`);
  md.push(`- **Impact:** ${c.realWorldImpact}`);
  if (c.suspectedCodeLocation) md.push(`- **Code (${c.suspectedCodeLocation.verified ? "verified" : "unverified"}):** ${c.suspectedCodeLocation.location}`);
  md.push(`- **Existing issue:** ${fmtMatch(c.existingIssue)}${c.existingIssue.note ? " — " + c.existingIssue.note : ""}`);
  md.push(`- **Home:** ${c.proposedHome}`);
  md.push("");
});
md.push("## Proposed Phase 37 — Real-world operations readiness (persona findings)");
md.push("");
md.push("Clusters whose goal no existing phase genuinely covers. Grouped into sub-themes so each can be planned as a wave:");
md.push("");
Object.values(SUB).forEach((s) => {
  const cs = catalogue.filter((c) => c.subTheme === s);
  md.push(`- **${s}** (${cs.length}): ${cs.map((c) => `${c.id}`).join(", ")}`);
});
const unsub = catalogue.filter((c) => c.proposedHome === N);
md.push(`- **Unassigned to a sub-theme (feature gaps)** (${unsub.length}): ${unsub.map((c) => c.id).join(", ")}`);
md.push("");
md.push("Existing phases receiving clusters: Phase 30 (money path), Phase 31 (complete — reopen as gap-closure for legal/allergen/privacy), Phase 32 (GTM content, vendor way-in), Phase 33 (#452/#453/#460/#545), Phase 34 (#507), Phase 29 (deploy-time guard).");
md.push("");
md.push("## Test data needing operator cleanup");
md.push("");
CLEANUP.forEach(([who, items]) => { md.push(`**${who}**`); items.forEach((x) => md.push(`- ${x.replace(/\{\{([a-z0-9-]+)\}\}/g, (_, kk) => R(kk))}`)); md.push(""); });
fs.writeFileSync(path.join(OUT, "CATALOGUE.md"), md.join("\n"));

// ---------------------------------------------------------------- goods-to-preserve.md
let g = [];
g.push("# Goods to preserve — persona user-testing 2026-10-03 (passes 1 + 2)");
g.push("");
g.push(`Every positive finding (${goodsCount}: ${p1Pos} pass-1 table rows + ${p2Pos} pass-2 entries), deduplicated into themes. These are regression guards for the fixes in \`catalogue.json\`: a fix that breaks one of these is a regression even if its own test is green (Incremental Betterment Doctrine). Never file these as issues.`);
g.push("");
GOODS.forEach(([t, ids, note]) => {
  g.push(`## ${t}`);
  g.push("");
  ids.forEach((id) => g.push(`- **${id}** (${short(ALL[id].persona)}): ${ALL[id].title}`));
  if (note) g.push(`- _Also (pass-1 narrative):_ ${note}`);
  g.push("");
});
g.push("## Pass-1 'Goods to preserve' narrative (not table rows; listed for completeness)");
g.push("");
P1_GOODS_NARRATIVE.forEach(([p, t]) => g.push(`- **${short(p)}**: ${t}`));
g.push("");
g.push("## Fixes that put these goods at risk");
g.push("");
g.push("| Cluster | Guard |");
g.push("|---|---|");
GUARDS.forEach(([key, txt]) => g.push(`| ${R(key)} ${catalogue.find((c) => c.key === key).title} | ${txt} |`));
g.push("");
fs.writeFileSync(path.join(OUT, "goods-to-preserve.md"), g.join("\n"));

process.stdout.write(JSON.stringify({ clusters: catalogue.length, pass1: p1.length, pass2: p2.length, coord: 1, members: memberCount, goods: goodsCount, byPri, newIssues: nNewIssues, comments: nComments, commentTargets: Object.keys(commentTargets), sameClusters: sameClusters.length, bundles: Object.fromEntries(Object.entries(bundles).map(([b, cs]) => [b, cs.length])) }, null, 1) + "\n");
