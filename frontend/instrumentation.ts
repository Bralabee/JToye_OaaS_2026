/**
 * Next.js Instrumentation Hook
 *
 * This runs once when the Next.js server starts up (before any requests).
 * Perfect place for environment validation.
 *
 * See: https://nextjs.org/docs/app/building-your-application/optimizing/instrumentation
 */

export async function register() {
  // Only run on server
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    const { validateEnvironment, logEnvironmentInfo } = await import('./lib/env-validation');

    try {
      validateEnvironment();
      logEnvironmentInfo();
    } catch (error) {
      // Fail fast - don't start the server with invalid config
      console.error(error);
      process.exit(1);
    }
  }
}
