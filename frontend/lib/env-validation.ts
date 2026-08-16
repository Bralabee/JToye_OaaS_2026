/**
 * Environment Variable Validation
 *
 * Validates that all required environment variables are present and properly configured.
 * This runs at server startup to fail fast if configuration is missing.
 */

interface EnvVars {
  // Backend API
  NEXT_PUBLIC_API_URL: string;

  // Keycloak Configuration
  KEYCLOAK_CLIENT_ID: string;
  KEYCLOAK_CLIENT_SECRET: string;
  KEYCLOAK_ISSUER: string;
  NEXT_PUBLIC_KEYCLOAK_URL: string;

  // NextAuth
  NEXTAUTH_URL: string;
  NEXTAUTH_SECRET: string;

  // Onboarding blocker UX (ONBD-05 / ONBD-03) — config-injected support channel +
  // review SLA copy. Non-secret, build-time NEXT_PUBLIC_* values so the onboarding
  // page never hardcodes a support mailto/URL or an "N days" literal (GLOBAL_RULE_6).
  NEXT_PUBLIC_SUPPORT_EMAIL: string;
  NEXT_PUBLIC_SUPPORT_URL: string;
  NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS: string;

  // Controller contact detail for the published legal surfaces (LGL-01, phase 31).
  // UK GDPR Art. 13(1)(a)-(b) requires the controller's identity AND contact details
  // in a privacy notice, so these are not cosmetic — but they are business facts that
  // vary by deployment (white-label operators are a different entity entirely), so
  // they are config rather than literals. NEXT_PUBLIC_* and therefore BUILD args:
  // a value supplied only at runtime inlines as the empty string.
  //
  // Classified OPTIONAL for the same reason as the support pair above and NOT because
  // they are unimportant: `resolveControllerContact()` in lib/company.ts drops the
  // whole block when nothing is configured, so an absence renders an honest page and
  // earns an operator warning — never a blank line where an address belongs, and
  // never a boot failure.
  NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE: string;
  NEXT_PUBLIC_DATA_PROTECTION_EMAIL: string;

  // Shop-list paging (#282). How many shops `fetchMyShops()` asks for per request
  // while it pages the caller's full list. A tuning knob that varies with the
  // deployment's shop-per-tenant profile, so it is config, not a literal
  // (GLOBAL_RULE_6) — but it is BROWSER-side and safely defaulted, so it is
  // deliberately in neither `requiredEnvVars` nor `optionalEnvVars`: the
  // server-startup validator never reads it, and its absence is not a
  // misconfiguration worth an operator signal. See `resolveShopsPageSize`.
  NEXT_PUBLIC_SHOPS_PAGE_SIZE: string;

  // Kitchen-display order paging (#485, call site kitchen/page.tsx:229). Same
  // classification and the same reasons as NEXT_PUBLIC_SHOPS_PAGE_SIZE above:
  // browser-side, safely defaulted, so it is in neither required nor optional.
  // See `resolveKitchenOrdersPageSize`.
  NEXT_PUBLIC_KITCHEN_ORDERS_PAGE_SIZE: string;

  // NOTE (#485): the product picker deliberately has NO env knob beside these two.
  // Its page size is pinned to the server's own `max-page-size: 100` clamp, so the
  // value cannot vary by environment: a larger one is a no-op on the wire and a
  // smaller one only costs extra requests. See PRODUCTS_PAGE_SIZE in
  // `lib/products-api.ts` for the full reasoning.
}

const requiredEnvVars: (keyof EnvVars)[] = [
  'NEXT_PUBLIC_API_URL',
  'KEYCLOAK_CLIENT_ID',
  'KEYCLOAK_CLIENT_SECRET',
  'KEYCLOAK_ISSUER',
  'NEXT_PUBLIC_KEYCLOAK_URL',
  'NEXTAUTH_URL',
  'NEXTAUTH_SECRET',
];

// WR-03: optional-with-fallback. The onboarding UI degrades gracefully when these
// are absent — resolveSupportChannel() returns { href: null } so the page renders
// plain "contact your account manager" copy instead of a dead link, and a missing
// SLA falls back to a genericised "a reviewer is looking at these now" line. So a
// missing value is a soft misconfiguration (worth an operator signal), NOT a boot
// failure — keeping them out of requiredEnvVars is what makes that classification true.
const optionalEnvVars: (keyof EnvVars)[] = [
  'NEXT_PUBLIC_SUPPORT_EMAIL',
  'NEXT_PUBLIC_SUPPORT_URL',
  'NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS',
  'NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE',
  'NEXT_PUBLIC_DATA_PROTECTION_EMAIL',
];

/**
 * A resolved, config-injected support channel for the onboarding UI.
 * `href`/`label` are null when neither NEXT_PUBLIC_SUPPORT_URL nor
 * NEXT_PUBLIC_SUPPORT_EMAIL is configured, so callers degrade to plain copy
 * instead of rendering a dead link.
 */
export interface SupportChannel {
  href: string | null;
  label: string | null;
}

/**
 * Resolve the support channel from the config-injected values (GLOBAL_RULE_6 —
 * no hardcoded support mailto/URL in a component). Prefers an explicit help URL,
 * falling back to a `mailto:` built from the support email. The `mailto:` scheme
 * lives here (not in page.tsx) so the vendor page carries no hardcoded link literal.
 */
export function resolveSupportChannel(email?: string, url?: string): SupportChannel {
  const trimmedUrl = url?.trim();
  const trimmedEmail = email?.trim();
  if (trimmedUrl) return { href: trimmedUrl, label: trimmedUrl };
  if (trimmedEmail) return { href: `mailto:${trimmedEmail}`, label: trimmedEmail };
  return { href: null, label: null };
}

/**
 * Shops-per-request default when `NEXT_PUBLIC_SHOPS_PAGE_SIZE` is unset (#282).
 * Matches the page size the pre-pagination `fetchMyShops` used, so a deployment
 * that sets nothing issues exactly the same first request it always did — the
 * change is that it now follows the list past that page instead of truncating.
 */
export const DEFAULT_SHOPS_PAGE_SIZE = 200;

/**
 * Resolve the shop-list page size from config (#282, GLOBAL_RULE_6 — no page-size
 * literal in the fetch). Anything that is not a positive integer (unset, blank,
 * `not-a-number`, `0`, `-5`, `50.5`) falls back to {@link DEFAULT_SHOPS_PAGE_SIZE}
 * rather than producing a request the API would reject, because a misconfigured
 * knob must not be able to break the shop switcher.
 *
 * The server may still clamp an over-large page size; the caller pages until the
 * API says there is no more, so a clamp costs extra requests, never lost shops.
 */
export function resolveShopsPageSize(raw?: string): number {
  const parsed = Number(raw?.trim());
  return Number.isInteger(parsed) && parsed > 0 ? parsed : DEFAULT_SHOPS_PAGE_SIZE;
}

/**
 * Orders-per-request default for the kitchen board when
 * `NEXT_PUBLIC_KITCHEN_ORDERS_PAGE_SIZE` is unset (#485).
 *
 * 100 is not a guess and not "the literal that was already there" — it is the
 * MAXIMUM this API will serve. Measured against the live core-java on 2026-08-04
 * for a shop with 125 orders:
 *
 *     GET /api/v1/orders?shopId=…&size=100 -> content 100, size 100, totalPages 2, last false
 *     GET /api/v1/orders?shopId=…&size=500 -> content 100, size 100, totalPages 2, last false
 *
 * Asking for more returns the same 100 rows, so raising the number cannot recover
 * the tail — only following `last`/`totalPages` can. Hence the default sits at the
 * clamp (fewest requests per board load) and the fix is paging, not a bigger fetch.
 */
export const DEFAULT_KITCHEN_ORDERS_PAGE_SIZE = 100;

/**
 * Resolve the kitchen board's orders page size from config (#485, GLOBAL_RULE_6).
 * Same validation contract as {@link resolveShopsPageSize}: anything that is not a
 * positive integer falls back to the default rather than producing a request the
 * API would reject, because a misconfigured knob must not be able to blank the KDS.
 */
export function resolveKitchenOrdersPageSize(raw?: string): number {
  const parsed = Number(raw?.trim());
  return Number.isInteger(parsed) && parsed > 0
    ? parsed
    : DEFAULT_KITCHEN_ORDERS_PAGE_SIZE;
}

export function validateEnvironment(): void {
  const missing: string[] = [];
  const missingOptional: string[] = [];
  const warnings: string[] = [];

  for (const envVar of requiredEnvVars) {
    const value = process.env[envVar];

    if (!value || value.trim() === '') {
      missing.push(envVar);
      continue;
    }

    // Validate specific formats (non-blocking)
    if (envVar.includes('URL') || envVar.includes('ISSUER')) {
      try {
        new URL(value);
      } catch {
        warnings.push(`${envVar} has invalid URL format: ${value}`);
      }
    }

    // Check for placeholder values (non-blocking)
    if (value.includes('CHANGE_ME') || (value.includes('your-') && envVar === 'NEXTAUTH_SECRET')) {
      warnings.push(`${envVar} is using a placeholder value - should be changed in production`);
    }
  }

  // WR-03: optional onboarding config — absent values are a soft misconfiguration
  // (UI uses graceful fallbacks), collected separately so they never masquerade as
  // a missing REQUIRED var.
  for (const envVar of optionalEnvVars) {
    const value = process.env[envVar];
    if (!value || value.trim() === '') {
      missingOptional.push(envVar);
    }
  }

  // WR-03: production is no longer silent. A missing REQUIRED var used to produce
  // ZERO operator signal in prod because the old code returned before this check.
  // Emit a real console.error (a genuine operator signal) — but do NOT throw: the
  // non-fatal-at-boot contract stays intact so a partial misconfig can't hard-crash
  // the server. Optional-var absences log at warn level (UI still works via fallbacks).
  if (process.env.NODE_ENV === 'production') {
    if (missing.length > 0) {
      console.error(
        `[ERROR] Missing required environment variables in production: ${missing.join(', ')}`
      );
    }
    if (missingOptional.length > 0) {
      console.warn(
        `[WARN] Missing optional onboarding config (UI uses graceful fallbacks): ${missingOptional.join(', ')}`
      );
    }
    return;
  }

  // Development: verbose guidance. Split REQUIRED (must fix) from optional onboarding
  // config (safe to omit — the UI degrades gracefully).
  if (missing.length > 0) {
    console.warn('\n[WARN] Environment Configuration Warning!\n');
    console.warn('Missing REQUIRED environment variables (will use defaults):');
    missing.forEach(v => console.warn(`  - ${v}`));
    console.warn('\n[INFO] For production use:');
    console.warn('  1. Copy frontend/.env.local.example to frontend/.env.local');
    console.warn('  2. Update values as needed');
    console.warn('  3. See docs/ENVIRONMENT_SETUP.md for detailed guide\n');
  }

  if (missingOptional.length > 0) {
    console.warn('[INFO] Missing optional onboarding config (UI uses graceful fallbacks):');
    missingOptional.forEach(v => console.warn(`  - ${v}`));
    console.warn('');
  }

  // Show warnings but don't fail
  if (warnings.length > 0) {
    console.warn('[WARN] Configuration warnings:');
    warnings.forEach(w => console.warn(`  - ${w}`));
    console.warn('');
  }

  if (missing.length === 0 && missingOptional.length === 0 && warnings.length === 0) {
    console.log('[OK] Environment variables validated successfully');
  }
}

/**
 * Log current environment configuration (safe - no secrets)
 */
export function logEnvironmentInfo(): void {
  if (process.env.NODE_ENV === 'production') return;
  console.log('\n[INFO] Environment Configuration:');
  console.log(`  API URL: ${process.env.NEXT_PUBLIC_API_URL}`);
  console.log(`  Keycloak Issuer: ${process.env.KEYCLOAK_ISSUER}`);
  console.log(`  NextAuth URL: ${process.env.NEXTAUTH_URL}`);
  console.log(`  Node ENV: ${process.env.NODE_ENV || 'development'}\n`);
}
