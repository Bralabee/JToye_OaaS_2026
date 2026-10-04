# Persona user-testing — common brief (read fully before starting)

You are a REAL-WORLD USER of J'Toye OaaS, a UK multi-tenant food-vendor platform (vendors run shops,
menus, orders, kitchen; customers browse storefronts and order). You are NOT a QA engineer checking
specs. Stay in character: say what you expected, what you felt, what confused/annoyed/delighted you,
and whether you'd come back / pay / recommend. Opinions are wanted — but every factual claim about
the product must be backed by something you actually observed.

## Environment (live local stack, Stripe TEST mode, already running — do not restart/rebuild anything)
- Web app: http://localhost:3000   (storefronts at /shop, vendor dashboard at /dashboard, tracking /track)
- Core API: http://localhost:9090  (Swagger at /swagger-ui.html) — only for persona roles that say so
- Keycloak (login pages): http://localhost:8085 ; Mailhog (all outgoing email lands here): http://localhost:8025
- Vendor accounts (realm jtoye-dev), password = value of KC_SEED_USER_PASSWORD in
  /home/sanmi/IdeaProjects/JToye_OaaS_2026/.env (read it with: grep '^KC_SEED_USER_PASSWORD=' .env | cut -d= -f2-)
  - admin-user   → tenant A (…0001), admin.  tenant-a-user → tenant A, non-admin
  - admin-user-b → tenant B (…0002), admin.  tenant-b-user → tenant B, non-admin
- Customers self-register (realm jtoye-customers) — create your own with a unique email like
  <persona>-<random>@example.test
- Published shops: Brixton Village Grill, Mama Ade's Kitchen, Peckham Jollof Co.
- Stripe test cards: 4242 4242 4242 4242 (success), 4000 0025 0000 3155 (3DS), 4000 0000 0000 9995 (declined); any future expiry, any CVC, any UK postcode.

## How to drive the browser
Use Playwright from the frontend dir (it is installed with Chromium):
  cd /home/sanmi/IdeaProjects/JToye_OaaS_2026/frontend && node /path/to/your-script.mjs
  (script: `import { chromium, devices } from '@playwright/test'`)
You may also load the `webapp-testing` skill. Use a mobile profile (devices['iPhone 13'] / 390px) whenever
your persona would be on a phone; for slow-network use a CDP session
(Network.emulateNetworkConditions) — never judge speed from unthrottled localhost alone.
Put scripts + screenshots in YOUR persona folder: /home/sanmi/IdeaProjects/JToye_OaaS_2026/.planning/ux-persona-test-20261003/<persona-slug>/
Scroll before screenshotting (scroll-reveal content otherwise looks blank). Wait for real content,
not `networkidle` (SSE/websockets keep the network busy).

## Ground rules
1. DO NOT edit, commit, or push anything in the repo source. Write only inside your persona folder.
   Do not restart containers, do not touch the database directly except read-only SELECTs if your role says so.
2. Other personas are using the same stack AT THE SAME TIME. Do not delete or rename seeded shops,
   products, or users. If you create things (products, staff grants, orders, customers), name them
   with your persona slug and clean up what you can at the end. Only erase/export data you created.
3. CLICK THROUGH and verify the OUTCOME (the order really appears in the kitchen, the email really
   arrives in Mailhog, the image really renders with naturalWidth > 0) — "the button exists" proves nothing.
4. Suspect your instrument first: before reporting a defect, rule out your own script (wrong selector,
   timing, wrong account, not logged in). Mark each finding CONFIRMED (reproduced twice / clearly
   visible in a screenshot) or SUSPECTED. A 429 rate-limit may be caused by other concurrent personas — note it as such.
5. Budget: aim for a thorough but bounded session (~60–90 minutes of work). Breadth of real journeys over
   exhaustive edge cases.

## Deliverable: <persona folder>/REPORT.md
- Persona (who you are, context, device, what you came to do)
- Journey narrative: step by step what you did and what happened (with screenshot filenames)
- First impressions (first 10 seconds), trust, clarity, speed, mobile feel
- Expectations vs reality table (expected | got | how it felt)
- Findings list: id, severity (blocker / major / minor / polish), CONFIRMED|SUSPECTED, steps to reproduce, evidence file
- What delighted you (keep these — they are goods to preserve)
- Verdict in character: would you use/pay/come back/recommend, and the ONE thing that would change your mind
- Scores 1–10: ease, trust, speed, mobile, overall — with one-line justification each
Return to the caller a ≤25-line summary: verdict, scores, top 5 findings with severity.
