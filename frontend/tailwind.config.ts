import type { Config } from "tailwindcss";
import tailwindcssAnimate from "tailwindcss-animate";
// RELATIVE, and it has to be. Tailwind reads this TypeScript config through
// jiti, which does not apply tsconfig compilerOptions.paths — so the `@/` alias
// throws "Cannot find module" here while resolving perfectly everywhere else in
// the app. Measured both directions (phase 35, plan 01, ARM B). This is the
// first repo-local import any config file in this repository has carried, so
// without this note the relative form reads as an oversight rather than a
// requirement. Note also that a `tsc` type-check CANNOT catch a regression
// here: jiti resolution happens at PostCSS init, which tsc never reaches. Only
// a real build does.
import { LAYOUT_WIDTHS } from "./lib/layout-widths";

const config: Config = {
  // Gate every `hover:` utility behind `@media (hover: hover)` (#503).
  //
  // Without this Tailwind emits bare `hover:` rules that apply on touch devices,
  // where a tap LATCHES the hover state until the user taps elsewhere — a button
  // that stays highlighted after being pressed. Measured on the build before this
  // change: 65 `.hover\:` utilities, exactly 1 of them gated, and that one only
  // because a developer hand-wrote the arbitrary variant
  // `[@media(hover:hover)_and_(pointer:fine)]:hover:shadow-md`. About ten such
  // hand-written workarounds exist (app/page.tsx, app/shop/orders/orders-client.tsx)
  // — this makes the other 64 correct by default.
  //
  // Those hand-written sites are deliberately LEFT IN PLACE: they additionally
  // require `pointer: fine`, which is strictly narrower than this flag, so
  // removing them would widen behaviour rather than tidy it.
  //
  // Default in Tailwind v4; a `future` opt-in on the v3.4 line this repo pins.
  future: {
    hoverOnlyWhenSupported: true,
  },
  darkMode: ["class"],
  content: [
    "./pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./components/**/*.{js,ts,jsx,tsx,mdx}",
    "./app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  // The stock shadcn `container` scaffold used to sit here, capping every
  // dashboard surface at 1400px. It is gone, and `corePlugins` below is what
  // makes that removal real — see the note there.
  theme: {
    extend: {
      // The declared horizontal layout contract (phase 35). Values come from
      // lib/layout-widths.ts and are never restated here: a number typed into
      // this file could drift from the module that the app and the Playwright
      // contract spec both read, which is precisely the failure the single
      // declaration exists to prevent. The drift check in
      // lib/__tests__/layout-widths-css.test.ts compares the emitted CSS back
      // against the module and has a recorded fail arm.
      //
      // Three keys, and no `index` key on purpose: the Index tier means "no cap
      // below the Shell cap", so it has nothing to generate. Each key yields one
      // unconditional max-width utility with NO media query attached, which is
      // what makes the caps inert on mobile by construction rather than by
      // assertion.
      maxWidth: {
        ...LAYOUT_WIDTHS,
      },
      colors: {
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        primary: {
          DEFAULT: "hsl(var(--primary))",
          foreground: "hsl(var(--primary-foreground))",
        },
        secondary: {
          DEFAULT: "hsl(var(--secondary))",
          foreground: "hsl(var(--secondary-foreground))",
        },
        destructive: {
          DEFAULT: "hsl(var(--destructive))",
          foreground: "hsl(var(--destructive-foreground))",
        },
        muted: {
          DEFAULT: "hsl(var(--muted))",
          foreground: "hsl(var(--muted-foreground))",
        },
        accent: {
          DEFAULT: "hsl(var(--accent))",
          foreground: "hsl(var(--accent-foreground))",
        },
        popover: {
          DEFAULT: "hsl(var(--popover))",
          foreground: "hsl(var(--popover-foreground))",
        },
        card: {
          DEFAULT: "hsl(var(--card))",
          foreground: "hsl(var(--card-foreground))",
        },
        // J'Toye brand thread — matched to the parent site jtoyedigital.co.uk
        // (oxblood #3A0B0D + Work Sans). amber/orange (Tailwind defaults) stay
        // the appetite accent (sketch 004 winner D).
        oxblood: { DEFAULT: "#3A0B0D", deep: "#1F0F28", 700: "#571417", 600: "#6E1D21" },
        cream: { DEFAULT: "#FBF6F0", 100: "#F4EBE1" },
        gold: "#E8B04B",
        // Ink for text sitting ON the amber appetite accent (amber-500 pills).
        // Tokenised so marketing components never need the raw hex — the
        // palette-discipline gate greps components/marketing for `#rrggbb`.
        "amber-ink": "#3A2400",
      },
      borderRadius: {
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
      },
      keyframes: {
        "accordion-down": {
          from: { height: "0" },
          to: { height: "var(--radix-accordion-content-height)" },
        },
        "accordion-up": {
          from: { height: "var(--radix-accordion-content-height)" },
          to: { height: "0" },
        },
      },
      animation: {
        "accordion-down": "accordion-down 0.2s ease-out",
        "accordion-up": "accordion-up 0.2s ease-out",
      },
    },
  },
  // DELETING THE THEME BLOCK ABOVE IS NOT ENOUGH, and this is the whole reason
  // this key exists. Measured on this tree: with `theme.container` removed but
  // the core plugin left on, the plugin falls back to the DEFAULT screens and
  // emits five media queries — one per breakpoint, each capping at its own
  // breakpoint value — instead of the single 1400px query the tree had before.
  // That is strictly worse than the state being replaced, and it happens
  // silently, because the class keeps working and simply caps somewhere else.
  //
  // The plugin also cannot express this phase's contract even if it were kept:
  // its selector is a hardcoded `.container` (one class for the whole app, and
  // four tiers are needed) and it forces each cap to EQUAL the breakpoint that
  // activates it, so "cap at 1700 starting from 1280" is not sayable. Both
  // properties were read out of the installed plugin source.
  //
  // So the utility is retired by switching the plugin off. The CSS test asserts
  // zero container rules are emitted even with the class name in the scanned
  // content, and that assertion has a recorded fail arm.
  corePlugins: {
    container: false,
  },
  plugins: [tailwindcssAnimate],
};
export default config;
