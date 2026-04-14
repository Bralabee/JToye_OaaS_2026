# J'Toye OaaS - Modern Frontend

Ultra-modern, aesthetically pleasing frontend for the J'Toye Order-as-a-Service platform built with the best tools available.

## 🚀 Tech Stack

- **[Next.js 14](https://nextjs.org/)** - React framework with App Router
- **[TypeScript](https://www.typescriptlang.org/)** - Type safety throughout
- **[Tailwind CSS](https://tailwindcss.com/)** - Utility-first styling
- **[shadcn/ui](https://ui.shadcn.com/)** - Beautiful, accessible UI components
- **[Framer Motion](https://www.framer.com/motion/)** - Smooth animations
- **[NextAuth.js v5](https://next-auth.js.org/)** - Keycloak authentication
- **[Axios](https://axios-http.com/)** - API client with interceptors
- **[React Hook Form](https://react-hook-form.com/)** - Form state management
- **[Zod](https://zod.dev/)** - Schema validation
- **[Lucide Icons](https://lucide.dev/)** - Modern icon set
- **[Radix UI](https://www.radix-ui.com/)** - Unstyled, accessible components

## ✨ Features

### 🎨 Modern UI/UX
- **Glassmorphism effects** and gradient backgrounds
- **Smooth animations** with Framer Motion (fade-in, slide-up, stagger)
- **Responsive design** - works beautifully on mobile, tablet, and desktop
- **Dark mode ready** - full theme support (CSS variables)
- **Micro-interactions** - hover states, transitions, loading spinners

### 🔐 Authentication
- **Keycloak OIDC** integration via NextAuth.js v5
- JWT token handling with automatic refresh
- Tenant-aware authentication (tenant_id from JWT)
- Protected routes via middleware
- Secure session management

### 📊 Dashboard Pages

#### 1. **Dashboard Overview** (`/dashboard`)
- Statistics cards with counts (Shops, Products, Orders, Customers)
- Recent orders table with status badges
- Animated card grid with stagger effect

#### 2. **Shops Management** (`/dashboard/shops`)
- Full CRUD operations
- Data table: Name, Address, Created At, Actions
- Create/Edit dialog with validation
- Delete confirmation
- Empty state handling

#### 3. **Products Catalog** (`/dashboard/products`)
- Full CRUD operations
- Allergen badge system with 14 allergens
- Bitmask UI for allergen selection
- Fields: SKU, Title, Ingredients, Allergens
- Beautiful allergen badges with emoji icons:
  - 🌾 Gluten, 🦐 Crustaceans, 🥚 Eggs, 🐟 Fish
  - 🥜 Peanuts, 🫘 Soybeans, 🥛 Milk, 🌰 Nuts
  - 🥬 Celery, 🌭 Mustard, 🫘 Sesame, 🍷 Sulphites
  - 🌸 Lupin, 🦪 Molluscs

#### 4. **Orders Management** (`/dashboard/orders`)
- Full order lifecycle management
- **State Machine visualization**: DRAFT → PENDING → CONFIRMED → PREPARING → READY → COMPLETED
- Status-based action buttons:
  - Submit (DRAFT → PENDING)
  - Confirm (PENDING → CONFIRMED)
  - Start Preparation (CONFIRMED → PREPARING)
  - Mark Ready (PREPARING → READY)
  - Complete (READY → COMPLETED)
  - Cancel (any state → CANCELLED)
- Color-coded status badges:
  - Gray (DRAFT), Yellow (PENDING), Blue (CONFIRMED)
  - Purple (PREPARING), Green (READY), Emerald (COMPLETED)
  - Red (CANCELLED)
- Create order with shop selection
- Price input in pounds (converted to pennies)

#### 5. **Customers Management** (`/dashboard/customers`)
- Full CRUD operations
- Allergen restriction tracking (same 14 allergens)
- Customer avatar with gradient backgrounds
- Contact information (email, phone)
- Bitmask UI for allergen restrictions

### 🎯 Common Features
- **Loading states** with spinner animations
- **Error handling** with toast notifications
- **Form validation** with Zod schemas
- **Empty states** with helpful messages
- **Responsive tables** with hover effects
- **Type-safe API calls** with TypeScript
- **Automatic JWT injection** via axios interceptors

## 🚀 Getting Started

### Prerequisites
- Node.js 18+ (recommended: 20+)
- Backend API running on `http://localhost:9090`
- Keycloak running on `http://localhost:8085`

### Installation

```bash
# Install dependencies
npm install

# Run development server
npm run dev

# Open browser
open http://localhost:3000
```

### Environment Variables

Create a `.env.local` file:

```env
# Backend API
NEXT_PUBLIC_API_URL=http://localhost:9090

# Keycloak Configuration
KEYCLOAK_CLIENT_ID=core-api
KEYCLOAK_CLIENT_SECRET=
KEYCLOAK_ISSUER=http://localhost:8085/realms/jtoye-dev

# NextAuth
NEXTAUTH_URL=http://localhost:3000
NEXTAUTH_SECRET=your-nextauth-secret-change-in-production
```

### Build for Production

```bash
# Create production build
npm run build

# Start production server
npm start
```

## 🧪 Testing

To test the frontend:

1. Start backend API: `./scripts/run-app.sh` (from project root)
2. Start frontend: `npm run dev`
3. Login with Keycloak test users:
   - `tenant-a-user` / `password`
   - `tenant-b-user` / `password`
4. Verify tenant isolation (each user sees only their data)

## Dependency Notes

### next-auth pinned at `5.0.0-beta.30`

We intentionally pin `next-auth` to `5.0.0-beta.30` in `package.json`. The
`latest` dist-tag of next-auth currently points at the v4 line (`4.24.13`)
which predates the App Router auth() helper and the Keycloak provider
wiring we rely on in `auth.ts`. The v5 line is still tagged `beta` and the
latest beta release is `5.0.0-beta.30` — the version we depend on.

Verified as of 2026-04-14:

```
$ npm view next-auth dist-tags
{ latest: '4.24.13', beta: '5.0.0-beta.30', ... }
```

When v5 goes stable, bump `"next-auth": "^5"` and re-run the test gate.
Do NOT downgrade to v4 — `app/dashboard/layout.tsx` and `middleware.ts`
depend on the v5 `auth()` export shape.

## 📝 License

Part of the J'Toye OaaS project - Enterprise-grade order management platform.
