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

  // Only fail if variables are completely missing
  if (missing.length > 0) {
    console.warn('\n⚠️  Environment Configuration Warning!\n');
    console.warn('Missing environment variables (will use defaults):');
    missing.forEach(v => console.warn(`  - ${v}`));
    console.warn('\n📋 For production use:');
    console.warn('  1. Copy frontend/.env.local.example to frontend/.env.local');
    console.warn('  2. Update values as needed');
    console.warn('  3. See docs/ENVIRONMENT_SETUP.md for detailed guide\n');
  }

  // Show warnings but don't fail
  if (warnings.length > 0) {
    console.warn('⚠️  Configuration warnings:');
    warnings.forEach(w => console.warn(`  - ${w}`));
    console.warn('');
  }

  if (missing.length === 0 && warnings.length === 0) {
    console.log('✅ Environment variables validated successfully');
  }
}

/**
 * Log current environment configuration (safe - no secrets)
 */
export function logEnvironmentInfo(): void {
  console.log('\n📝 Environment Configuration:');
  console.log(`  API URL: ${process.env.NEXT_PUBLIC_API_URL}`);
  console.log(`  Keycloak Issuer: ${process.env.KEYCLOAK_ISSUER}`);
  console.log(`  NextAuth URL: ${process.env.NEXTAUTH_URL}`);
  console.log(`  Node ENV: ${process.env.NODE_ENV || 'development'}\n`);
}
