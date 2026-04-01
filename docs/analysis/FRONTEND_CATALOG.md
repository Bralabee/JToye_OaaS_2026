# Frontend Module -- Complete Catalog

> **Generated**: 2026-04-01  
> **Module**: frontend (Next.js 14, TypeScript, Tailwind CSS)

---

## Configuration

- **Framework**: Next.js 14.2.35 (standalone output mode)
- **React**: 18
- **Auth**: NextAuth.js 5.0.0-beta.30 (Keycloak provider)
- **Styling**: Tailwind CSS 3.4.1 + shadcn/ui (new-york style, slate base)
- **Forms**: react-hook-form 7.69.0 + Zod 4.2.1
- **Animation**: Framer Motion 12.23.26
- **HTTP**: Axios 1.13.2 with Bearer token interceptor
- **Icons**: lucide-react 0.562.0
- **Testing**: Jest 29.7.0 + Testing Library

---

## Authentication Flow

1. NextAuth.js v5 configured with Keycloak OIDC provider
2. Middleware protects `/dashboard/*` routes
3. Unauthenticated users redirected to `/auth/signin`
4. JWT tokens (access, refresh, id) stored in session
5. Axios interceptor auto-injects Bearer token on API requests
6. 401 responses trigger redirect to sign-in

### Keycloak Integration
- Client ID: `core-api`
- Issuer: configurable via `KEYCLOAK_ISSUER`
- Profile mapping: sub -> id, name/preferred_username -> name

---

## Page Structure

```
app/
├── layout.tsx              # Root layout (Inter font, Providers wrapper)
├── page.tsx                # Redirects to /dashboard
├── api/auth/[...nextauth]/route.ts  # NextAuth API route
├── auth/signin/page.tsx    # Keycloak sign-in card
└── dashboard/
    ├── layout.tsx          # Auth-gated layout (sidebar + main)
    ├── page.tsx            # Dashboard home (stats + recent orders)
    ├── shops/page.tsx      # Shop CRUD table
    ├── products/page.tsx   # Product CRUD with allergen management
    ├── orders/page.tsx     # Order CRUD + state flow visualization
    ├── customers/page.tsx  # Customer CRUD with allergen restrictions
    └── test/page.tsx       # Debug page (session info, API test)
```

---

## Dashboard Pages

### Dashboard Home
- 4 animated stat cards (shops, products, orders, customers counts)
- Recent orders table (10 items, sorted by createdAt desc)
- Status badges with color coding per order status

### Shops Page
- Table: Name, Address, Created, Actions (edit/delete)
- Create/edit dialog with name + address fields

### Products Page
- Table: SKU, Title (with ingredients preview), Allergens (badges), Actions
- Create/edit dialog with allergen bitmask toggle checkboxes
- 14 allergen types displayed as emoji badges

### Orders Page
- Status flow visualization bar (DRAFT through COMPLETED)
- Table: ID, Customer, Status, Total (GBP), Created, Actions
- State transition buttons (Submit, Confirm, Start Prep, Mark Ready, Complete, Cancel)
- Create dialog: shop selector, customer fields, dynamic item list with product picker

### Customers Page
- Table: Name, Contact, Allergen Restrictions, Created, Actions
- Create/edit with allergen restriction bitmask management

---

## Components

### Custom Components
- `Providers` -- SessionProvider + Toaster wrapper
- `Sidebar` -- Fixed navigation (Dashboard, Shops, Products, Orders, Customers, Sign Out)

### shadcn/ui Components (11)
badge, button, card, dialog, dropdown-menu, input, label, select, table, toast, toaster

---

## API Integration

Base URL: `NEXT_PUBLIC_API_URL` (default: `http://localhost:9090`)

All pages fetch with `?size=100&sort=createdAt,desc` (no pagination UI).

### Request Interceptor
```
getSession() -> session.accessToken -> Authorization: Bearer {token}
```

### Response Interceptor
```
401 -> redirect to /auth/signin
```

---

## Type System

```
types/
├── api.ts          # Shop, Product, Order, Customer, PageResponse<T>, ALLERGENS constant
└── next-auth.d.ts  # Session/JWT type extensions (accessToken, refreshToken, tenantId)
```

### Allergen Utilities (in api.ts)
- `hasAllergen(mask, bit)` -- check if allergen bit is set
- `toggleAllergen(mask, bit)` -- flip allergen bit
- `getAllergenNames(mask)` -- get array of allergen names from mask

---

## Tests (5 suites)

| Suite | File | Tests |
|-------|------|-------|
| Sign-in page | `auth/signin/__tests__/page.test.tsx` | Renders, Keycloak button, signIn call |
| Dashboard | `dashboard/__tests__/page.test.tsx` | Loading, heading, stats, orders table |
| Products | `dashboard/products/__tests__/page.test.tsx` | Loading, table, allergens, dialogs |
| API client | `lib/__tests__/api-client.test.ts` | Base URL, headers, interceptors |
| Allergen utils | `types/__tests__/api.test.ts` | hasAllergen, toggleAllergen, getAllergenNames |

---

## Environment Variables

| Variable | Purpose |
|----------|---------|
| `NEXT_PUBLIC_API_URL` | Backend API endpoint |
| `KEYCLOAK_CLIENT_ID` | Keycloak client identifier |
| `KEYCLOAK_CLIENT_SECRET` | Keycloak client secret |
| `KEYCLOAK_ISSUER` | Keycloak realm issuer URL |
| `NEXT_PUBLIC_KEYCLOAK_URL` | Public Keycloak URL (for auth redirects) |
| `NEXTAUTH_URL` | NextAuth base URL |
| `NEXTAUTH_SECRET` | Session encryption secret |

Validated at startup via `instrumentation.ts` (Next.js instrumentation hook).

---

## Docker

- Multi-stage: node:20-alpine (deps -> builder -> runner)
- Standalone output mode
- Non-root user: `nextjs` (UID 1001)
- Health check: HTTP GET /api/health
- Port: 3000
