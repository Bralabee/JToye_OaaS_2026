#!/bin/bash
# How the 37-52 runtime proof was produced (2026-10-14). Stack-free: nothing here talks to the shared
# compose stack. The spec is e2e/a11y-storefront.spec.ts, UNMODIFIED, pointed at two builds of the
# same frontend that differ only in source revision:
#   GREEN = this branch after 37-52 (next start :3052)
#   RED   = d9661e86, the commit before 37-52 (git archive, node_modules hard-linked, next start :3053)
# Both builds bake NEXT_PUBLIC_API_URL=http://localhost:9352 and serve with
# CORE_API_INTERNAL_URL=http://127.0.0.1:9352, so SSR and the browser read the stub (stub-core.cjs):
# three shops, Mama Ade's Kitchen with twelve dishes, no reviews, no announcements.
#
#   node stub-core.cjs &                                     # :9352
#   (cd frontend && NEXT_PUBLIC_API_URL=http://localhost:9352 npx next build && \
#      CORE_API_INTERNAL_URL=http://127.0.0.1:9352 NEXT_PUBLIC_API_URL=http://localhost:9352 npx next start -p 3052) &
#   git archive d9661e86 frontend | tar -x -C red-tree; cp -al frontend/node_modules red-tree/frontend/
#   (cd red-tree/frontend && same build, next start -p 3053) &
#   PLAYWRIGHT_BASE_URL=http://localhost:3053 npx playwright test e2e/a11y-storefront.spec.ts --project=mobile --grep-invert @stack-free
#       -> pw-red-prefix-build.out   (4 failed: every defect reported, soft assertions)
#   PLAYWRIGHT_BASE_URL=http://localhost:3052 npx playwright test e2e/a11y-storefront.spec.ts --project=mobile
#       -> pw-green-37-52-build.out  (8 passed: 4 live incl. their INSTRUMENT arms, 4 @stack-free)
exit 0
