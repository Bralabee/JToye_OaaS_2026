import type { Config } from "tailwindcss";

/**
 * Tailwind theme extension for the Warm Editorial design system.
 *
 * Every semantic token in `app/globals.css` is mirrored here as an `hsl(var(--...))`
 * reference so utility classes like `bg-surface-canvas` or `text-ink-secondary`
 * resolve correctly in both light and dark modes.
 *
 * Legacy shadcn aliases (primary, secondary, muted, accent, destructive, border,
 * input, ring, background, foreground, card, popover) are preserved until wave 5,
 * per DESIGN-SPEC.md §13. This keeps existing components working during migration.
 */
const config: Config = {
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
      fontFamily: {
        display: ["var(--font-display)", "Georgia", "Cambria", "Times New Roman", "serif"],
        sans: ["var(--font-sans)", "SF Pro Text", "Segoe UI", "system-ui", "sans-serif"],
        mono: ["var(--font-mono)", "SF Mono", "Menlo", "ui-monospace", "monospace"],
      },
      fontSize: {
        // Fluid scale — spec §3.2. First tuple entry is the clamp, second holds
        // line-height + tracking so Tailwind emits a single rule per utility.
        "display-2xl": ["var(--text-display-2xl)", { lineHeight: "1.02", letterSpacing: "-0.02em" }],
        "display-xl": ["var(--text-display-xl)", { lineHeight: "1.04", letterSpacing: "-0.02em" }],
        "display-lg": ["var(--text-display-lg)", { lineHeight: "1.08", letterSpacing: "-0.02em" }],
        "heading-xl": ["var(--text-heading-xl)", { lineHeight: "1.15", letterSpacing: "-0.01em" }],
        "heading-lg": ["var(--text-heading-lg)", { lineHeight: "1.2", letterSpacing: "-0.01em" }],
        "heading-md": ["var(--text-heading-md)", { lineHeight: "1.3", letterSpacing: "-0.005em" }],
        "heading-sm": ["var(--text-heading-sm)", { lineHeight: "1.4", letterSpacing: "0" }],
        "body-lg": ["var(--text-body-lg)", { lineHeight: "1.6", letterSpacing: "0" }],
        body: ["var(--text-body)", { lineHeight: "1.55", letterSpacing: "0" }],
        "body-sm": ["var(--text-body-sm)", { lineHeight: "1.5", letterSpacing: "0" }],
        caption: ["var(--text-caption)", { lineHeight: "1.4", letterSpacing: "0.01em" }],
        overline: ["var(--text-overline)", { lineHeight: "1.3", letterSpacing: "0.08em" }],
        "mono-sm": ["var(--text-mono-sm)", { lineHeight: "1.4", letterSpacing: "0" }],
        "mono-md": ["var(--text-mono)", { lineHeight: "1.45", letterSpacing: "0" }],
        "mono-lg": ["var(--text-mono-lg)", { lineHeight: "1.2", letterSpacing: "0" }],
      },
      letterSpacing: {
        tighter: "-0.02em",
        tight: "-0.01em",
        normal: "0em",
        wide: "0.01em",
        wider: "0.04em",
        widest: "0.08em",
      },
      lineHeight: {
        display: "1.04",
        tight: "1.15",
        snug: "1.3",
        normal: "1.55",
        relaxed: "1.7",
      },
      colors: {
        // ----- Legacy shadcn aliases (preserved for back-compat) ----------
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

        // ----- Warm Editorial semantic tokens -----------------------------
        surface: {
          canvas: "hsl(var(--surface-canvas))",
          card: "hsl(var(--surface-card))",
          popover: "hsl(var(--surface-popover))",
          subtle: "hsl(var(--surface-subtle))",
          muted: "hsl(var(--surface-muted))",
          strong: "hsl(var(--surface-strong))",
          inverse: "hsl(var(--surface-inverse))",
        },
        ink: {
          primary: "hsl(var(--ink-primary))",
          secondary: "hsl(var(--ink-secondary))",
          tertiary: "hsl(var(--ink-tertiary))",
          quaternary: "hsl(var(--ink-quaternary))",
          "on-brand": "hsl(var(--ink-on-brand))",
          "on-danger": "hsl(var(--ink-on-danger))",
          "on-warning": "hsl(var(--ink-on-warning))",
          "on-success": "hsl(var(--ink-on-success))",
          "on-accent": "hsl(var(--ink-on-accent))",
        },
        brand: {
          primary: "hsl(var(--brand-primary))",
          "primary-hover": "hsl(var(--brand-primary-hover))",
          "primary-press": "hsl(var(--brand-primary-press))",
          "primary-subtle": "hsl(var(--brand-primary-subtle))",
          secondary: "hsl(var(--brand-secondary))",
          "secondary-hover": "hsl(var(--brand-secondary-hover))",
        },
        "accent-editorial": {
          DEFAULT: "hsl(var(--accent-editorial))",
          subtle: "hsl(var(--accent-subtle))",
        },
        success: {
          DEFAULT: "hsl(var(--semantic-success))",
          subtle: "hsl(var(--semantic-success-subtle))",
        },
        warning: {
          DEFAULT: "hsl(var(--semantic-warning))",
          subtle: "hsl(var(--semantic-warning-subtle))",
        },
        danger: {
          DEFAULT: "hsl(var(--semantic-danger))",
          subtle: "hsl(var(--semantic-danger-subtle))",
        },
        info: {
          DEFAULT: "hsl(var(--semantic-info))",
          subtle: "hsl(var(--semantic-info-subtle))",
        },
        "border-tone": {
          subtle: "hsl(var(--border-subtle))",
          DEFAULT: "hsl(var(--border-default))",
          strong: "hsl(var(--border-strong))",
          focus: "hsl(var(--border-focus))",
        },
      },
      borderRadius: {
        // Legacy aliases (--radius = 10px == new md)
        lg: "var(--radius-lg)",
        md: "var(--radius-md)",
        sm: "var(--radius-sm)",
        // Full Warm Editorial radius scale (spec §5.1)
        none: "var(--radius-none)",
        xs: "var(--radius-xs)",
        xl: "var(--radius-xl)",
        "2xl": "var(--radius-2xl)",
        pill: "var(--radius-pill)",
      },
      boxShadow: {
        hairline: "var(--shadow-hairline)",
        subtle: "var(--shadow-subtle)",
        lift: "var(--shadow-lift)",
        float: "var(--shadow-float)",
        bloom: "var(--shadow-bloom)",
      },
      spacing: {
        // Supplemental 4px-grid tokens (spec §4.1).
        // Tailwind defaults already cover 0.5, 1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24.
        // 32 (128px) is the only addition beyond Tailwind's default spacing scale.
        32: "8rem",
      },
      maxWidth: {
        "prose-narrow": "var(--w-prose-narrow)",
        prose: "var(--w-prose)",
        content: "var(--w-content)",
        wide: "var(--w-wide)",
      },
      transitionDuration: {
        instant: "80ms",
        fast: "120ms",
        DEFAULT: "180ms",
        moderate: "240ms",
        slow: "320ms",
        slowest: "480ms",
      },
      transitionTimingFunction: {
        standard: "cubic-bezier(0.4, 0, 0.2, 1)",
        emphasized: "cubic-bezier(0.3, 0, 0, 1)",
        decelerate: "cubic-bezier(0, 0, 0.2, 1)",
        accelerate: "cubic-bezier(0.4, 0, 1, 1)",
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
  // eslint-disable-next-line @typescript-eslint/no-require-imports -- Tailwind config must run as CJS at build time; ESM import breaks Tailwind's plugin resolution in Next 16.
  plugins: [require("tailwindcss-animate")],
};
export default config;
