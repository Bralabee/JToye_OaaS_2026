---
quick_id: 261103-b3m
status: complete
---
# Summary: 261103-b3m

- SW-CLR-17: `lib/shop-slug-gate.ts` + `middleware.ts`. Measured on next dev: 200 with any of the
  three loading.tsx boundaries present, 404 only with all removed. With the gate, on the production
  build: missing kitchen 404 (same "Kitchen not found" page, noindex), live kitchen, basket and
  /shop/signin 200. Retired slugs get a real 301 from the same check. Jest 14/14; two break arms red
  (4 and 1), green restored.
- SW-JRD-12: `POST /public/orders/{orderNumber}/lookup` (`OrderLookupRequest`), `no-store`; GET
  deprecated. `PublicOrderLookupControllerTest` 5/5; no-store break arm red. On the real database
  through the branch image on :9095: 200 with no-store, wrong email 404, blank 400, GET still 200,
  main's core 404 on the POST. Frontend: `lib/order-lookup.ts`; /track and the order page post.
  The 7 tracking suites: red with main's pages (42 failed), green on the fix. Browser on the
  production build: one POST to /lookup, the email in no API URL, the legacy ?email= stripped from
  the address bar, the tracker rendered.
- SW-JRD-09: `components/storefront/collect-from.tsx`; no Delivery row on Collection in either
  summary. Checkout suites 168/168; main's checkout page reds 3.
- SW-SAM-03: `/data-request` (+ `lib/dsar-intake.ts`, shared with /shop/account); privacy notice
  links it in the Emails section and in both branches of "How to make a request". Jest 6 new + 1
  privacy test (red on main's notice). Browser: privacy notice, then the form, then erasure for an
  example.com address, then 202 and the confirmation copy; Mailhog received "Confirm your request
  to erase your data".
- SW-MRC-10: dish card photo column `max-w-[35%]`; basket line reflows below 12rem of container;
  header pill and wordmark yield below 12rem of row, and the menu sheet gains Sign in; hours rows
  wrap; footer words may break. Measured 320px / 200%: document 480px wide before, 320 after on
  kitchen, basket and checkout; 100% text unchanged (320). New e2e red on main (:3000), green on
  the branch build (:3214) with its instrument arm; the existing 130% test green on both.
- Full Jest 302 suites / 3600 pass; storefront Java + OpenAPI snapshot tests pass; next build rc 0;
  eslint 0 errors (3 warnings, all pre-existing on main); doc metrics gates rc 0.
