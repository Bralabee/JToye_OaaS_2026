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
  const invalid: string[] = [];

  for (const envVar of requiredEnvVars) {
    const value = process.env[envVar];

    if (!value || value.trim() === '') {
      missing.push(envVar);
      continue;
    }

    // Validate specific formats
    if (envVar.includes('URL') || envVar.includes('ISSUER')) {
      try {
        new URL(value);
      } catch {
        invalid.push(`${envVar} (invalid URL format: ${value})`);
      }
    }

    // Check for placeholder values
    if (value.includes('CHANGE_ME') || value.includes('your-') && envVar === 'NEXTAUTH_SECRET') {
      invalid.push(`${envVar} (using placeholder value, should be changed)`);
    }
  }

  if (missing.length > 0 || invalid.length > 0) {
    console.error('\n❌ Environment Configuration Error!\n');

    if (missing.length > 0) {
      console.error('Missing required environment variables:');
      missing.forEach(v => console.error(`  - ${v}`));
      console.error('');
    }

    if (invalid.length > 0) {
      console.error('Invalid environment variable values:');
      invalid.forEach(v => console.error(`  - ${v}`));
      console.error('');
    }

    console.error('📋 Setup Instructions:');
    console.error('  1. Copy frontend/.env.local.example to frontend/.env.local');
    console.error('  2. Update values as needed');
    console.error('  3. See docs/ENVIRONMENT_SETUP.md for detailed guide\n');

    throw new Error('Environment validation failed. See logs above.');
  }

  console.log('✅ Environment variables validated successfully');
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
