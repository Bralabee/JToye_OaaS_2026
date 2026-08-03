import type { Config } from "tailwindcss";
import tailwindcssAnimate from "tailwindcss-animate";

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
  theme: {
    container: {
      center: true,
      padding: "2rem",
      screens: {
        "2xl": "1400px",
      },
    },
    extend: {
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
  plugins: [tailwindcssAnimate],
};
export default config;
