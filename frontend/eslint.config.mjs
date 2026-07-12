import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

/**
 * ESLint v9 flat config (issue #99 do-now).
 *
 * Resurrects frontend linting: `npm run lint` used to be `next lint` (removed in
 * Next 16) while the installed ESLint is v9 (flat-config only) with only a
 * legacy .eslintrc.json — so nothing ran. eslint-config-next@16 ships NATIVE
 * flat-config arrays at the /core-web-vitals and /typescript subpaths; we spread
 * them directly. Do NOT wrap them with FlatCompat — that crashes with a
 * circular-structure error.
 */
const config = [
  {
    // Global ignores (must be the only key in this object to apply globally).
    ignores: [
      ".next/**",
      "node_modules/**",
      "coverage/**",
      "playwright-report/**",
      "test-results/**",
      "next-env.d.ts",
      "public/**",
    ],
  },
  ...nextCoreWebVitals,
  ...nextTypescript,
  {
    // Tests legitimately use `any` and rely on harness globals — relax there.
    files: ["**/__tests__/**", "**/*.test.*", "**/*.spec.*"],
    rules: {
      "@typescript-eslint/no-explicit-any": "off",
      "react-hooks/globals": "off",
    },
  },
  {
    // jest.config.js is a CommonJS module.
    files: ["jest.config.js"],
    rules: {
      "@typescript-eslint/no-require-imports": "off",
    },
  },
];

export default config;
