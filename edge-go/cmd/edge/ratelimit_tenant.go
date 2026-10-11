package main

import (
	"fmt"
	"math"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

// Per-tenant rate limiting for the protected group (Phase 37 plan 37-25,
// D-19 / RWO-039 / UXT-039).
//
// The edge used to have ONE pre-auth bucket for every route (rateLimiter in
// main.go): a burst from a single integrator — or anonymous traffic that never
// authenticates — emptied it for every vendor's sync. The per-tenant limiter
// sits AFTER jwtMiddleware.Validate() and keys on the tenant_id that
// middleware wrote, so one tenant's traffic can only exhaust that tenant's
// bucket (T-37-62) and the key is never a header the caller wrote (T-37-64).
//
// Hand-rolled, like the valve: no golang.org/x/time dependency (RESEARCH A11,
// T-37-SC). Buckets refill LAZILY on access from a clock — there is no
// goroutine per tenant.

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

// The typed 429 contract, shared by the per-tenant limiter and the pre-auth
// valve. The type URI and the header names are core's
// (RateLimitInterceptor.java), so an integrator handles one shape whichever
// layer refused it.
const (
	rateLimitedProblemType = "https://jtoye.uk/errors/rate-limited"
	rateLimitedCode        = "RATE_LIMITED"

	headerRetryAfter         = "Retry-After"
	headerRateLimitLimit     = "X-RateLimit-Limit"
	headerRateLimitRemaining = "X-RateLimit-Remaining"
	headerRateLimitReset     = "X-RateLimit-Reset"
)

// tenantBucket is one tenant's token bucket. tokens is fractional so a
// partial refill is never rounded away; last is when tokens was last
// brought up to date.
type tenantBucket struct {
	tokens float64
	last   time.Time
}

// rateDecision is the outcome of one take(): what the headers say.
type rateDecision struct {
	allowed   bool
	limit     int
	remaining int
	// retryAfter is how long until one token is available; zero when allowed.
	retryAfter time.Duration
	// resetAt is when the bucket is full again (allowed) or when the next
	// token lands (refused) — the X-RateLimit-Reset epoch second.
	resetAt time.Time
}

// tenantLimiter holds the per-tenant buckets behind one mutex. A request
// holds the lock only for the arithmetic below, never across I/O.
type tenantLimiter struct {
	mu      sync.Mutex
	cfg     tenantRateLimitConfig
	clock   clock
	buckets map[string]*tenantBucket
}

func newTenantLimiter(cfg tenantRateLimitConfig, clk clock) *tenantLimiter {
	if clk == nil {
		clk = realClock{}
	}
	return &tenantLimiter{cfg: cfg, clock: clk, buckets: make(map[string]*tenantBucket)}
}

// refill brings b up to date at now: elapsed time adds RPS tokens per
// second, capped at the burst. A clock that went backwards adds nothing.
func (l *tenantLimiter) refill(b *tenantBucket, now time.Time) {
	elapsed := now.Sub(b.last)
	if elapsed > 0 {
		b.tokens = math.Min(float64(l.cfg.Burst), b.tokens+elapsed.Seconds()*float64(l.cfg.RPS))
	}
	b.last = now
}

// timeToTokens is how long b needs to hold n tokens at the configured rate.
func (l *tenantLimiter) timeToTokens(b *tenantBucket, n float64) time.Duration {
	deficit := n - b.tokens
	if deficit <= 0 {
		return 0
	}
	return time.Duration(math.Ceil(deficit / float64(l.cfg.RPS) * float64(time.Second)))
}

// take spends one token from the bucket keyed by key, creating a full bucket
// on first sight, and reports the decision.
func (l *tenantLimiter) take(key string) rateDecision {
	now := l.clock.Now()
	l.mu.Lock()
	defer l.mu.Unlock()

	b := l.buckets[key]
	if b == nil {
		b = &tenantBucket{tokens: float64(l.cfg.Burst), last: now}
		l.buckets[key] = b
	}
	l.refill(b, now)

	if b.tokens >= 1 {
		b.tokens--
		return rateDecision{
			allowed:   true,
			limit:     l.cfg.Burst,
			remaining: int(b.tokens),
			resetAt:   now.Add(l.timeToTokens(b, float64(l.cfg.Burst))),
		}
	}
	wait := l.timeToTokens(b, 1)
	return rateDecision{allowed: false, limit: l.cfg.Burst, remaining: 0, retryAfter: wait, resetAt: now.Add(wait)}
}

// tenantRateLimiter is the middleware for the protected group. It must run
// AFTER jwtMiddleware.Validate(): the key is c.Get("tenant_id"), which only
// that middleware writes from a validated token (T-37-64). A request that
// somehow carries no tenant id is not exempt — it is keyed on the empty
// string, so it shares one bucket and cannot bypass the limit; the handler
// then refuses it as it does today.
func tenantRateLimiter(cfg tenantRateLimitConfig, clk clock) gin.HandlerFunc {
	limiter := newTenantLimiter(cfg, clk)
	return func(c *gin.Context) {
		v, _ := c.Get("tenant_id")
		tenantID, _ := v.(string)

		d := limiter.take(tenantID)
		if !d.allowed {
			writeRateLimited(c, d)
			return
		}
		setRateLimitHeaders(c, d)
		c.Next()
	}
}

// retryAfterSeconds renders a wait as the whole seconds Retry-After carries,
// never below 1: a client told to wait 0 would retry immediately into the
// same refusal.
func retryAfterSeconds(wait time.Duration) int {
	secs := int(math.Ceil(wait.Seconds()))
	if secs < 1 {
		secs = 1
	}
	return secs
}

// setRateLimitHeaders writes the three X-RateLimit-* headers (core's names).
func setRateLimitHeaders(c *gin.Context, d rateDecision) {
	c.Header(headerRateLimitLimit, strconv.Itoa(d.limit))
	c.Header(headerRateLimitRemaining, strconv.Itoa(d.remaining))
	c.Header(headerRateLimitReset, strconv.FormatInt(d.resetAt.Unix(), 10))
}

// writeRateLimited aborts the request with the typed 429: application/
// problem+json carrying core's type, a stable code, a typed
// retryAfterSeconds, and all four headers. Content-Type is set before the
// JSON render because gin only fills it in when it is empty.
func writeRateLimited(c *gin.Context, d rateDecision) {
	retry := retryAfterSeconds(d.retryAfter)
	c.Header(headerRetryAfter, strconv.Itoa(retry))
	setRateLimitHeaders(c, d)
	c.Header("Content-Type", "application/problem+json; charset=utf-8")
	c.AbortWithStatusJSON(http.StatusTooManyRequests, ProblemDetail{
		Type:              rateLimitedProblemType,
		Title:             "Too Many Requests",
		Status:            http.StatusTooManyRequests,
		Detail:            fmt.Sprintf("Rate limit exceeded. Please try again in %d seconds.", retry),
		Code:              rateLimitedCode,
		RetryAfterSeconds: retry,
	})
}
