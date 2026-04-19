// ESLint 9 flat config.
//
// Replaces the legacy .eslintrc.json (which relied on `next lint`, removed in
// Next.js 16). eslint-config-next v16 ships flat-config-native exports, so we
// can consume its core-web-vitals + typescript configs directly and add local
// overrides below.
//
// Run via the package.json "lint" script: `npm run lint`.

import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

export default [
  ...nextCoreWebVitals,
  ...nextTypescript,
  {
    ignores: [
      ".next/**",
      "node_modules/**",
      "coverage/**",
      "playwright-report/**",
      "test-results/**",
      "public/**",
      "next-env.d.ts",
    ],
  },
  // CJS-only config files legitimately use require() — they bootstrap Jest and
  // Tailwind outside the Next.js module graph, so ESM imports don't apply.
  {
    files: ["jest.config.js", "jest.setup.js"],
    rules: {
      "@typescript-eslint/no-require-imports": "off",
    },
  },
];
