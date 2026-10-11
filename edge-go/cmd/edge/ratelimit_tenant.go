package main

import "time"

// Per-tenant rate limiting for the protected group (Phase 37 plan 37-25,
// D-19 / RWO-039 / UXT-039).
//
// The edge used to have ONE pre-auth bucket for every route (rateLimiter in
// main.go): a burst from a single integrator — or anonymous traffic that never
// authenticates — emptied it for every vendor's sync. The per-tenant limiter
// sits AFTER jwtMiddleware.Validate() and keys on the tenant_id that
// middleware wrote, so one tenant's traffic can only exhaust that tenant's
// bucket (T-37-62) and the key is never a header the caller wrote (T-37-64).

// clock is the time source the limiter reads. Production passes realClock;
// tests pass a fake so refill arithmetic is asserted exactly, not by sleeping.
type clock interface {
	Now() time.Time
}

type realClock struct{}

func (realClock) Now() time.Time { return time.Now() }

// tenantRateLimitConfig is the per-tenant limit, config-declared through
// RATE_LIMIT_TENANT_RPS / RATE_LIMIT_TENANT_BURST / RATE_LIMIT_TENANT_MAX
// (never literals at the use site).
type tenantRateLimitConfig struct {
	// RPS is the steady refill rate of ONE tenant's bucket, tokens per second.
	RPS int
	// Burst is the capacity of one tenant's bucket: how many requests a quiet
	// tenant may send at once before the refill rate applies. Also the
	// capacity of the shared overflow bucket.
	Burst int
	// MaxTenants bounds how many per-tenant buckets are held in memory.
	// Tenants beyond the bound share one overflow bucket (T-37-63).
	MaxTenants int
}

// Defaults, chosen so the edge tenant limit sits ABOVE core's authoritative
// per-tenant quota (100/min + burst 20 in RateLimitInterceptor) and BELOW the
// pre-auth valve: it protects the replica's valve from one tenant, it does
// not replace core's quota.
const (
	defaultTenantRateLimitRPS   = 10
	defaultTenantRateLimitBurst = 20
	defaultTenantRateLimitMax   = 10000
)

// tenantLimitsFromEnv reads the per-tenant limit from the environment, with
// the documented defaults. getEnvInt rejects non-positive values, so a
// misconfigured 0 falls back to the default rather than refusing everything.
func tenantLimitsFromEnv() tenantRateLimitConfig {
	return tenantRateLimitConfig{
		RPS:        getEnvInt("RATE_LIMIT_TENANT_RPS", defaultTenantRateLimitRPS),
		Burst:      getEnvInt("RATE_LIMIT_TENANT_BURST", defaultTenantRateLimitBurst),
		MaxTenants: getEnvInt("RATE_LIMIT_TENANT_MAX", defaultTenantRateLimitMax),
	}
}
