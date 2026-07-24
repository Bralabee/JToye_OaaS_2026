// Radar-chart palette for the competitive teardown (/competitive).
//
// Recharts fill/stroke are SVG presentation props that need literal colour
// strings — Tailwind utility classes can't reach them — so these values live
// HERE, outside components/marketing, deliberately. They mirror the design
// tokens (slate-700 / orange-500 / slate-600) and keep the marketing surface
// itself free of raw hex (enforced by __tests__/palette-discipline.test.ts).
export const TEARDOWN_CHART = {
  flipdish: "#334155", // slate-700
  jtoye: "#f97316", // orange-500
  axisTick: "#475569", // slate-600
} as const
