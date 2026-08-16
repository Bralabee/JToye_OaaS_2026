// Local type declarations for `jest-axe`.
//
// WHY THIS FILE EXISTS INSTEAD OF `@types/jest-axe`:
// The DefinitelyTyped stub is stuck at 3.5.9 — definitions for jest-axe v3.5.x,
// seven majors behind the v10.0.0 this repo installs — and it declares a
// dependency on `axe-core: ^3.5.5`. Installing it would put a THIRD major of
// axe-core into the tree alongside the pinned 4.13.0 and the 4.10.2 that
// jest-axe nests, which is precisely the drift the direct axe-core pin exists
// to prevent (phase 31 threat T-31-01-04). It was rejected at the 31-01
// package-legitimacy gate and this file is the sanctioned replacement.
//
// jest-axe@10.0.0 ships NO declarations of its own — its published tarball
// contains only index.js, extend-expect.js, package.json, README and LICENSE,
// with zero .d.ts entries — so the whole module has to be declared here, not
// merely the matcher.
//
// This file is deliberately a GLOBAL SCRIPT, not a module: it has no top-level
// import or export. `declare module "jest-axe"` is only an ambient declaration
// for an untyped package in a script file; adding `export {}` would turn it
// into a module augmentation of a module that has no types to augment, and
// would stop resolving. For the same reason `namespace jest` is declared at top
// level rather than inside `declare global` (which is legal only in a module) —
// top level of a script .d.ts already IS global scope.
//
// Frontend TypeScript is checked by `npm run build` (tsc). Jest does not
// type-check, so a green test run is NOT evidence that this file is correct.

declare module "jest-axe" {
  import type {
    AxeResults,
    ElementContext,
    Result,
    RunOptions,
    Spec,
  } from "axe-core"

  /** Options accepted by `configureAxe`: axe run options plus a global ruleset. */
  interface JestAxeConfigureOptions extends RunOptions {
    globalOptions?: Spec
    impactLevels?: string[]
  }

  /** Runs axe against a container and resolves with the full axe result set. */
  export function axe(
    html: ElementContext,
    additionalOptions?: RunOptions
  ): Promise<AxeResults>

  /** Builds a pre-configured `axe` runner (e.g. with a restricted ruleset). */
  export function configureAxe(
    options?: JestAxeConfigureOptions
  ): typeof axe

  /**
   * The matcher object passed to `expect.extend`. `actual` carries the
   * violations array, which is what lets a test assert on specific rule ids
   * rather than only on a count.
   */
  export const toHaveNoViolations: {
    toHaveNoViolations(results: AxeResults): {
      actual: Result[]
      message(): string
      pass: boolean
    }
  }
}

// Merges with the `Matchers` interface declared by @types/jest, which is
// `interface Matchers<R, T = {}>` (node_modules/@types/jest/index.d.ts:801);
// @testing-library/jest-dom merges into the same interface.
//
// The type parameter list is copied from that upstream declaration verbatim and
// is NOT a free choice: TypeScript requires every declaration of a merged
// interface to have identical type parameters, so dropping the unused `T` or
// widening `{}` to `object` breaks the merge. Both lint rules are therefore
// disabled for this one line rather than the code being "corrected".
declare namespace jest {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars, @typescript-eslint/no-empty-object-type
  interface Matchers<R, T = {}> {
    toHaveNoViolations(): R
  }
}
