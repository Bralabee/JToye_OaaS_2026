# Phase 19 — Deferred / Out-of-Scope Items

Discoveries logged during execution that are outside the current plan's scope
(pre-existing, unrelated files). Do NOT fix inside the plan that found them.

## 19-03

- **Raw `tsc --noEmit` flags jest-dom matchers (`toBeInTheDocument`, `toHaveClass`,
  `toHaveAttribute`) as type errors across ~9 pre-existing test files**
  (`app/auth/signin/__tests__`, `app/dashboard/__tests__`,
  `app/dashboard/kitchen/__tests__`, `app/dashboard/onboarding/__tests__`,
  `app/dashboard/products/__tests__`, etc.).
  - **Pre-existing:** the same usages exist at base commit `8b13745`; not
    introduced by 19-03. None of the erroring files are touched by this plan.
  - **Root cause:** `tsconfig.json` has no `compilerOptions.types` entry and no
    ambient reference to `@testing-library/jest-dom`; the matcher augmentation
    is only imported at runtime via `jest.setup.js`, so a raw `tsc` run does not
    see it. Jest runs green; the project's actual gate (`next build`) is
    unaffected (CI on `main` is green).
  - **Scope:** repo-wide test-tooling config, not a Phase 19 concern. If desired,
    add `"types": ["jest", "@testing-library/jest-dom"]` (or a
    `types/jest-dom.d.ts` with `/// <reference types="@testing-library/jest-dom" />`)
    in a dedicated tooling change. All 19-03 source + test files are type-clean.
