export const BRAND = {
  name: "J'Toye",
  product: "OaaS",
  fullName: "J'Toye OaaS",
  tagline: "Every shop. Every order. One kitchen.",
  shortTagline: "Every shop. Every order.",
  description:
    "Multi-tenant platform for food vendors — manage shops, products, orders, and kitchen displays from one place.",
  marks: {
    icon: "/brand/mark.svg",
    iconDark: "/brand/mark-dark.svg",
    wordmark: "/brand/wordmark.svg",
    wordmarkWithProduct: "/brand/wordmark-with-oaas.svg",
    og: "/brand/og-default.svg",
    ogStorefront: "/brand/og-storefront.svg",
  },
} as const;

export type Brand = typeof BRAND;
