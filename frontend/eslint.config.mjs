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
    /**
     * jsx-a11y: the static accessibility layer (31-02 / LGL-02).
     *
     * `eslint-plugin-jsx-a11y@6.10.2` is ALREADY installed transitively via
     * `eslint-config-next@16.2.12`, and `npm run lint` already runs unfiltered
     * on every PR — but `next/core-web-vitals` enables only SIX of its ~34
     * rules, all at `warn`, and `eslint .` does not fail on warnings. Measured
     * with `eslint --print-config app/page.tsx` before this block existed:
     *
     *   alt-text, aria-props, aria-proptypes, aria-unsupported-elements,
     *   role-has-required-aria-props, role-supports-aria-props        (6, all warn)
     *
     * This block turns the plugin's full `recommended` set on at `error`, with
     * the recommended option objects passed verbatim (several differ from the
     * rules' own schema defaults in BOTH directions — `interactive-supports-focus`
     * is stricter, `no-noninteractive-tabindex` more permissive — so a bare
     * "error" would not be the same rule).
     *
     * Why this layer is not redundant with the axe gate: axe accepts a
     * `placeholder` as a control's accessible name (proved by the QA council,
     * A11Y-13) and `jsx-a11y/label-has-associated-control` does not. It costs
     * zero new packages and zero new CI minutes.
     *
     * The plugin is NOT re-declared under `plugins` here — the Next config
     * already registers the `jsx-a11y` namespace, and assigning a second
     * instance to the same name is an ESLint flat-config redefinition error.
     * That is also why `files` mirrors the Next config object's own glob
     * EXACTLY: an unscoped rules block also applies to files Next does not
     * match (`.cjs`, for one), and ESLint then fails config resolution with
     * "could not find plugin jsx-a11y" rather than reporting a lint problem.
     */
    files: ["**/*.{js,jsx,mjs,ts,tsx,mts,cts}"],
    rules: {
      "jsx-a11y/alt-text": "error",
      "jsx-a11y/anchor-has-content": "error",
      "jsx-a11y/anchor-is-valid": "error",
      "jsx-a11y/aria-activedescendant-has-tabindex": "error",
      "jsx-a11y/aria-props": "error",
      "jsx-a11y/aria-proptypes": "error",
      "jsx-a11y/aria-role": "error",
      "jsx-a11y/aria-unsupported-elements": "error",
      "jsx-a11y/autocomplete-valid": "error",
      "jsx-a11y/click-events-have-key-events": "error",
      "jsx-a11y/heading-has-content": "error",
      "jsx-a11y/html-has-lang": "error",
      "jsx-a11y/iframe-has-title": "error",
      "jsx-a11y/img-redundant-alt": "error",
      "jsx-a11y/interactive-supports-focus": [
        "error",
        {
          tabbable: [
            "button",
            "checkbox",
            "link",
            "searchbox",
            "spinbutton",
            "switch",
            "textbox",
          ],
        },
      ],
      // `depth: 3`, not the default 2. A browser computes a <label>'s
      // accessible name from its ENTIRE subtree with no depth limit; the rule's
      // default 2 is a search budget, not a standard. WebhookCreateDialog's
      // event picker is `<label><input/><span><span>{text}` — real, correct,
      // and invisible at depth 2. Raising the budget makes the rule agree with
      // the accessible-name algorithm; it does not make it accept a label that
      // has no text.
      "jsx-a11y/label-has-associated-control": ["error", { depth: 3 }],
      "jsx-a11y/media-has-caption": "error",
      "jsx-a11y/mouse-events-have-key-events": "error",
      // `jsx-a11y/control-has-associated-label` is DELIBERATELY NOT ENABLED,
      // and the reason is measured rather than assumed. It is the only rule in
      // the plugin aimed at "this control's sole accessible name is its
      // placeholder", so it was tried with `input`/`textarea` removed from
      // `ignoreElements` — the shape that would make that defect red the build.
      // Result: 30 errors, and among them `app/shop/shop-discovery-client.tsx:390`,
      // an input that IS correctly named by `<label htmlFor="shop-search">` eight
      // lines above it. The rule does not follow an htmlFor/id association across
      // siblings, so it cannot tell a correctly labelled control from an
      // unlabelled one. Enabling it would not be a stricter gate, it would be a
      // gate that is wrong 30 times; the honest record is here rather than an
      // `off` entry that reads like an oversight.
      "jsx-a11y/no-access-key": "error",
      "jsx-a11y/no-autofocus": "error",
      "jsx-a11y/no-distracting-elements": "error",
      "jsx-a11y/no-interactive-element-to-noninteractive-role": [
        "error",
        { tr: ["none", "presentation"], canvas: ["img"] },
      ],
      "jsx-a11y/no-noninteractive-element-interactions": [
        "error",
        {
          handlers: [
            "onClick",
            "onError",
            "onLoad",
            "onMouseDown",
            "onMouseUp",
            "onKeyPress",
            "onKeyDown",
            "onKeyUp",
          ],
          alert: ["onKeyUp", "onKeyDown", "onKeyPress"],
          body: ["onError", "onLoad"],
          dialog: ["onKeyUp", "onKeyDown", "onKeyPress"],
          iframe: ["onError", "onLoad"],
          img: ["onError", "onLoad"],
        },
      ],
      "jsx-a11y/no-noninteractive-element-to-interactive-role": [
        "error",
        {
          ul: ["listbox", "menu", "menubar", "radiogroup", "tablist", "tree", "treegrid"],
          ol: ["listbox", "menu", "menubar", "radiogroup", "tablist", "tree", "treegrid"],
          li: ["menuitem", "menuitemradio", "menuitemcheckbox", "option", "row", "tab", "treeitem"],
          table: ["grid"],
          td: ["gridcell"],
          fieldset: ["radiogroup", "presentation"],
        },
      ],
      // `region` added to the recommended `["tabpanel"]`. A horizontally
      // scrolling container MUST be focusable or its content is unreachable by
      // keyboard (WCAG 2.1.1) — `dish-scroller.tsx` already does the correct
      // thing: role="region" + aria-label + tabIndex={0} + a visible focus
      // ring. Without this the rule reds the fix and rewards deleting it.
      "jsx-a11y/no-noninteractive-tabindex": [
        "error",
        { tags: [], roles: ["tabpanel", "region"], allowExpressionValues: true },
      ],
      "jsx-a11y/no-redundant-roles": "error",
      "jsx-a11y/no-static-element-interactions": [
        "error",
        {
          allowExpressionValues: true,
          handlers: [
            "onClick",
            "onMouseDown",
            "onMouseUp",
            "onKeyPress",
            "onKeyDown",
            "onKeyUp",
          ],
        },
      ],
      "jsx-a11y/role-has-required-aria-props": "error",
      "jsx-a11y/role-supports-aria-props": "error",
      "jsx-a11y/scope": "error",
      "jsx-a11y/tabindex-no-positive": "error",
    },
  },
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
