# Quick Task 260414-fe3: Frontend Security + Tests (Audit Phase 3)

**Branch:** `fix/frontend-security-and-tests`
**Goal:** Close 8 verified frontend gaps: token storage, kitchen tests, api-client, cart memoization, strict types, next-auth stabilization, server-side auth, version bump.

## Verified findings

| # | File | Issue | Fix |
|---|------|-------|-----|
| 1 | `frontend/lib/customer-auth.ts:28-37,150-155` | JWT access/refresh/id tokens in localStorage — XSS exposure | Move tokens to HttpOnly cookies via a Next.js API route (`/api/customer-auth/*`). Keep a small non-sensitive session marker in localStorage for "am I logged in" UI logic. Refactor all callers (`frontend/app/shop/[slug]/checkout/page.tsx`, any other that imports customer-auth) to use a thin client helper that calls the API route. |
| 2 | `frontend/app/dashboard/kitchen/page.tsx` + `frontend/hooks/use-stomp.ts` | Zero tests for the Milestone 2 flagship feature | Add Jest tests for: (a) status filter logic, (b) audio cue behaviour (mocked AudioContext), (c) mute toggle persistence, (d) reconnection attempt on auth failure. Add Playwright spec (`frontend/e2e/kitchen-flow.spec.ts`) that exercises: page loads, renders mock orders, mute toggle works, filter works. Use `@mswjs` or a simple fetch mock for the STOMP upstream. |
| 3 | `frontend/lib/api-client.ts` | No retry, no tenant header, 401 race on session refresh | Add (a) `X-Tenant-Id` injection from session, (b) `axios-retry`-style retry on 5xx (max 2, exponential backoff), (c) 401 handler that attempts a `getSession()` refresh before redirecting, debounced with a module-level promise so concurrent 401s only trigger one refresh. |
| 4 | `frontend/components/storefront/cart-provider.tsx:112-126` | Context value recreated every render | Wrap the context value in `useMemo([items, addItem, removeItem, updateQuantity, clearCart, itemCount, totalPennies, shopSlug])`. |
| 5 | `frontend/app/dashboard/marketing/page.tsx:205` | `zodResolver(promotionSchema) as any` | Root cause is a generic-inference gap between react-hook-form and zod@v4. Fix by typing the form explicitly: `useForm<z.infer<typeof promotionSchema>>({ resolver: zodResolver(promotionSchema) })` and remove the `as any`. Repeat for any other `as any` in marketing page (grep). |
| 6 | `frontend/package.json:34` | next-auth pinned at `5.0.0-beta.30` | Bump to latest stable `5.0.0-beta.30+` or if a stable 5.x exists as of 2026-04, take that. If no stable, document the beta pin with a comment in README explaining why. Validate with `npm run build` + `npm test`. |
| 7 | `frontend/app/dashboard/layout.tsx:16-20` + `frontend/middleware.ts` | Dashboard auth is client-only; blank flash on expired session | Convert `app/dashboard/layout.tsx` to a Server Component that calls `auth()` and redirects server-side. Keep any client-side logic in a nested client component. |
| 8 | `frontend/package.json:3` | Project version 0.1.0, rest of project is 2.0.0 | Bump to `2.0.0` to match monorepo version. |

## Commit sequence

1. `fix(frontend): move customer oauth tokens to httpOnly cookies`
2. `test(kitchen): unit and e2e coverage for kitchen display`
3. `fix(api-client): add retry, tenant header, debounced session refresh`
4. `perf(cart): memoize cart context value`
5. `fix(marketing): type form explicitly and remove as-any cast`
6. `chore(deps): document or bump next-auth pin`
7. `fix(auth): server-side dashboard auth check`
8. `chore(version): bump frontend package to 2.0.0`

## Test gate after each commit

```bash
cd frontend && npm run build && npm test -- --watchAll=false
```

## Exit criteria

- 8 atomic commits
- Build + tests green after each
- SUMMARY.md with SHAs, files, deviations
- No push, no PR — orchestrator handles
