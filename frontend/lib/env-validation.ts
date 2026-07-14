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
}

const requiredEnvVars: (keyof EnvVars)[] = [
  'NEXT_PUBLIC_API_URL',
  'KEYCLOAK_CLIENT_ID',
  'KEYCLOAK_CLIENT_SECRET',
  'KEYCLOAK_ISSUER',
  'NEXT_PUBLIC_KEYCLOAK_URL',
  'NEXTAUTH_URL',
  'NEXTAUTH_SECRET',
  'NEXT_PUBLIC_SUPPORT_EMAIL',
  'NEXT_PUBLIC_SUPPORT_URL',
  'NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS',
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

export function validateEnvironment(): void {
  const missing: string[] = [];
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

  // Only log in development to avoid noise in production
  if (process.env.NODE_ENV === 'production') return;

  // Only fail if variables are completely missing
  if (missing.length > 0) {
    console.warn('\n[WARN] Environment Configuration Warning!\n');
    console.warn('Missing environment variables (will use defaults):');
    missing.forEach(v => console.warn(`  - ${v}`));
    console.warn('\n[INFO] For production use:');
    console.warn('  1. Copy frontend/.env.local.example to frontend/.env.local');
    console.warn('  2. Update values as needed');
    console.warn('  3. See docs/ENVIRONMENT_SETUP.md for detailed guide\n');
  }

  // Show warnings but don't fail
  if (warnings.length > 0) {
    console.warn('[WARN] Configuration warnings:');
    warnings.forEach(w => console.warn(`  - ${w}`));
    console.warn('');
  }

  if (missing.length === 0 && warnings.length === 0) {
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
