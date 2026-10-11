package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jtoye/edge/internal/core"
	"go.uber.org/zap"
)

// These tests drive the PRODUCTION router (newRouter) — the same route table
// and middleware order main() ships — over a stub core that accepts every
// batch, with a stub JWT validator standing in for jwtMiddleware.Validate().
// They are the UXT-039 / D-19 contract: one tenant's burst is refused with a
// typed 429 that says how long to wait, and another tenant is unaffected.

// stubValidator stands in for jwtMiddleware.Validate(): the ONLY writer of
// tenant_id on the protected group. It takes the tenant from X-Test-Tenant so
// a test can speak as several tenants without minting JWTs; a request without
// one is refused 401 exactly as the real middleware refuses a token without a
// tenant claim (jwt.go "missing tenant claim").
func stubValidator() gin.HandlerFunc {
	return func(c *gin.Context) {
		tenant := c.GetHeader("X-Test-Tenant")
		if tenant == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing tenant claim"})
			return
		}
		c.Set("tenant_id", tenant)
		c.Next()
	}
}

// fakeClock lets a test move the limiter's time deterministically instead of
// sleeping: the refill arithmetic is asserted exactly.
type fakeClock struct {
	mu  sync.Mutex
	now time.Time
}

func newFakeClock() *fakeClock {
	return &fakeClock{now: time.Date(2026, 10, 11, 21, 0, 0, 0, time.UTC)}
}

func (f *fakeClock) Now() time.Time {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.now
}

func (f *fakeClock) Advance(d time.Duration) {
	f.mu.Lock()
	f.now = f.now.Add(d)
	f.mu.Unlock()
}

// tenantLimitHarness is the production router over a stub core that 202s
// every batch. coreHits counts what actually reached core, so "allowed"
// is measured at the downstream, not inferred from a status.
type tenantLimitHarness struct {
	r        *gin.Engine
	coreHits atomic.Int32
}

func newTenantLimitHarness(t *testing.T, valve gin.HandlerFunc, limits tenantRateLimitConfig, clk clock) *tenantLimitHarness {
	t.Helper()
	h := &tenantLimitHarness{}
	coreStub := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		h.coreHits.Add(1)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"status":"accepted","processedCount":1}`))
	}))
	t.Cleanup(coreStub.Close)

	logger := zap.NewNop()
	handlers := &edgeHandlers{
		coreClient: core.NewClient(coreStub.URL, logger),
		logger:     logger,
		jwksURL:    coreStub.URL,
		startedAt:  time.Now(),
	}
	r, _ := newRouter(routerDeps{
		handlers:     handlers,
		validate:     stubValidator(),
		valve:        valve,
		tenantLimits: limits,
		clock:        clk,
	})
	h.r = r
	return h
}

// sync sends one batch as the given tenant through the whole router.
func (h *tenantLimitHarness) sync(tenant string) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/sync/batch", strings.NewReader(`{"items":[{"id":"1"}]}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("X-Test-Tenant", tenant)
	h.r.ServeHTTP(w, req)
	return w
}

// rateLimitedProblem is the body an integrator can back off from: core's
// RFC 7807 shape (RateLimitInterceptor.writeProblem) with a stable code.
type rateLimitedProblem struct {
	Type              string `json:"type"`
	Title             string `json:"title"`
	Status            int    `json:"status"`
	Detail            string `json:"detail"`
	Code              string `json:"code"`
	RetryAfterSeconds int    `json:"retryAfterSeconds"`
}

const rateLimitedType = "https://jtoye.uk/errors/rate-limited"

// assertTypedRateLimited checks one 429 against the contract: problem+json,
// core's type, a code, and the four headers (core's names) with Retry-After
// at least one second. Every arm reports separately so an untyped 429 shows
// exactly which parts of the contract it misses.
func assertTypedRateLimited(t *testing.T, w *httptest.ResponseRecorder, wantLimit int) {
	t.Helper()
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("status: got %d, want 429 (body %s)", w.Code, w.Body.String())
	}
	if ct := w.Header().Get("Content-Type"); !strings.HasPrefix(ct, "application/problem+json") {
		t.Errorf("Content-Type: got %q, want application/problem+json", ct)
	}
	var p rateLimitedProblem
	if err := json.Unmarshal(w.Body.Bytes(), &p); err != nil {
		t.Fatalf("429 body is not JSON: %v (body %s)", err, w.Body.String())
	}
	if p.Type != rateLimitedType {
		t.Errorf("body.type: got %q, want %q (body %s)", p.Type, rateLimitedType, w.Body.String())
	}
	if p.Status != http.StatusTooManyRequests {
		t.Errorf("body.status: got %d, want 429", p.Status)
	}
	if p.Code != "RATE_LIMITED" {
		t.Errorf("body.code: got %q, want RATE_LIMITED", p.Code)
	}
	if p.Title == "" || p.Detail == "" {
		t.Errorf("body.title/detail must be present: title=%q detail=%q", p.Title, p.Detail)
	}
	if p.RetryAfterSeconds < 1 {
		t.Errorf("body.retryAfterSeconds: got %d, want >= 1", p.RetryAfterSeconds)
	}
	retryAfter, err := strconv.Atoi(w.Header().Get("Retry-After"))
	if err != nil || retryAfter < 1 {
		t.Errorf("Retry-After header: got %q, want an integer >= 1", w.Header().Get("Retry-After"))
	}
	if got := w.Header().Get("X-RateLimit-Limit"); got != strconv.Itoa(wantLimit) {
		t.Errorf("X-RateLimit-Limit: got %q, want %d", got, wantLimit)
	}
	if got := w.Header().Get("X-RateLimit-Remaining"); got != "0" {
		t.Errorf("X-RateLimit-Remaining: got %q, want 0", got)
	}
	reset, err := strconv.ParseInt(w.Header().Get("X-RateLimit-Reset"), 10, 64)
	if err != nil || reset <= 0 {
		t.Errorf("X-RateLimit-Reset header: got %q, want a positive epoch second", w.Header().Get("X-RateLimit-Reset"))
	}
}

// TestTenantRateLimit_OneTenantsBurstIsRefusedTypedWhileAnotherTenantPasses
// is UXT-039 through the production wiring at the PRODUCTION defaults: one
// integrator sends a burst of 60 batches. Exactly the tenant burst must reach
// core, the first refusal must be the typed 429, and a second tenant's next
// request must pass — the one bucket shared by everyone is what blocked every
// vendor's sync.
func TestTenantRateLimit_OneTenantsBurstIsRefusedTypedWhileAnotherTenantPasses(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	valveRPS, valveBurst := valveLimitsFromEnv()
	limits := tenantLimitsFromEnv()
	if limits.Burst >= valveBurst {
		t.Fatalf("the per-tenant burst (%d) must sit below the pre-auth valve burst (%d): the valve is the flood guard, the tenant bucket is the quota", limits.Burst, valveBurst)
	}

	h := newTenantLimitHarness(t, rateLimiter(ctx, valveRPS, valveBurst), limits, newFakeClock())

	// UXT-039: the persona's burst, sized above the pre-change 40-token valve
	// so the old behaviour (the valve empties, everyone is refused) is what
	// fails here, and below the post-change valve so tenant B's pass is the
	// tenant limiter's doing, not luck.
	const flood = 60
	allowed := 0
	var firstRefusal *httptest.ResponseRecorder
	for i := 0; i < flood; i++ {
		w := h.sync("tenant-a")
		switch w.Code {
		case http.StatusAccepted:
			allowed++
		case http.StatusTooManyRequests:
			if firstRefusal == nil {
				firstRefusal = w
			}
		default:
			t.Fatalf("request %d: unexpected status %d (body %s)", i+1, w.Code, w.Body.String())
		}
	}

	if allowed != limits.Burst {
		t.Errorf("tenant A: %d of %d requests reached core, want exactly the per-tenant burst %d", allowed, flood, limits.Burst)
	}
	if int(h.coreHits.Load()) != allowed {
		t.Errorf("core saw %d batches but %d requests were 202: a refusal must happen BEFORE the forward", h.coreHits.Load(), allowed)
	}
	if firstRefusal == nil {
		t.Fatalf("tenant A was never refused across %d requests", flood)
	}
	assertTypedRateLimited(t, firstRefusal, limits.Burst)

	// The cross-tenant arm: tenant B has sent nothing yet.
	wb := h.sync("tenant-b")
	if wb.Code != http.StatusAccepted {
		t.Errorf("tenant B's first request right after tenant A's burst: got %d (body %s), want 202 — tenant A's traffic must not consume tenant B's budget (UXT-039)", wb.Code, wb.Body.String())
	}
}

// TestTenantRateLimit_RefillsLazilyAfterRetryAfter: a refused tenant passes
// again once the Retry-After it was given has elapsed, and not before. The
// clock is faked, so this asserts the refill arithmetic, not a sleep.
func TestTenantRateLimit_RefillsLazilyAfterRetryAfter(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	limits := tenantRateLimitConfig{RPS: 1, Burst: 2, MaxTenants: 10}
	clk := newFakeClock()
	// A generous valve so every refusal here is the tenant bucket's.
	h := newTenantLimitHarness(t, rateLimiter(ctx, 1000, 1000), limits, clk)

	for i := 0; i < limits.Burst; i++ {
		if w := h.sync("tenant-a"); w.Code != http.StatusAccepted {
			t.Fatalf("request %d within the burst: got %d, want 202 (body %s)", i+1, w.Code, w.Body.String())
		}
	}
	w := h.sync("tenant-a")
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("request %d, one past the burst with no time elapsed: got %d, want 429 (body %s)", limits.Burst+1, w.Code, w.Body.String())
	}
	retryAfter, err := strconv.Atoi(w.Header().Get("Retry-After"))
	if err != nil || retryAfter < 1 {
		t.Fatalf("Retry-After: got %q, want an integer >= 1", w.Header().Get("Retry-After"))
	}
	wait := time.Duration(retryAfter) * time.Second

	clk.Advance(wait / 2)
	if w := h.sync("tenant-a"); w.Code != http.StatusTooManyRequests {
		t.Errorf("halfway through Retry-After (%v of %v): got %d, want 429 — the bucket must not refill early", wait/2, wait, w.Code)
	}
	clk.Advance(wait - wait/2)
	if w := h.sync("tenant-a"); w.Code != http.StatusAccepted {
		t.Errorf("after Retry-After (%v) elapsed: got %d, want 202 — lazy refill must restore a token (body %s)", wait, w.Code, w.Body.String())
	}
}

// TestTenantRateLimit_BoundedMapAndOverflowBucket is T-37-63: 10,000 distinct
// tenant ids must never grow the bucket map past the configured cap. Tenants
// beyond the cap share ONE overflow bucket, so across the flood exactly
// cap x burst + burst requests are allowed. Once the held tenants have been
// idle long enough to be full again they are evicted, so two fresh tenants
// each get their own full bucket instead of the shared one.
func TestTenantRateLimit_BoundedMapAndOverflowBucket(t *testing.T) {
	cfg := tenantRateLimitConfig{RPS: 1, Burst: 2, MaxTenants: 100}
	clk := newFakeClock()
	limiter := newTenantLimiter(cfg, clk)

	const flood = 10000
	allowed := 0
	for i := 0; i < flood; i++ {
		if limiter.take("tenant-" + strconv.Itoa(i)).allowed {
			allowed++
		}
	}
	if got := len(limiter.buckets); got > cfg.MaxTenants {
		t.Errorf("bucket map holds %d tenants after %d distinct ids, want at most the cap %d (memory must be bounded)", got, flood, cfg.MaxTenants)
	}
	wantAllowed := cfg.MaxTenants + cfg.Burst
	if allowed != wantAllowed {
		t.Errorf("%d of %d one-request tenants were allowed, want %d: one request each for the %d held tenants plus the overflow bucket's burst of %d shared by everyone else", allowed, flood, wantAllowed, cfg.MaxTenants, cfg.Burst)
	}
	if d := limiter.take("tenant-" + strconv.Itoa(flood+1)); d.allowed || d.limit != cfg.Burst || d.remaining != 0 {
		t.Errorf("a further new tenant while the overflow bucket is spent: got allowed=%v limit=%d remaining=%d, want refused with limit %d remaining 0", d.allowed, d.limit, d.remaining, cfg.Burst)
	}

	// Idle eviction: after burst/RPS seconds every held bucket is full again,
	// so it can be dropped without changing what its tenant would see.
	clk.Advance(time.Duration(cfg.Burst/cfg.RPS) * time.Second)
	fresh := 0
	for _, tenant := range []string{"fresh-a", "fresh-b"} {
		for i := 0; i < cfg.Burst; i++ {
			if limiter.take(tenant).allowed {
				fresh++
			}
		}
	}
	if fresh != 2*cfg.Burst {
		t.Errorf("two fresh tenants after the held ones went idle: %d requests allowed, want %d (each must get its own full bucket, not the shared overflow one)", fresh, 2*cfg.Burst)
	}
	if got := len(limiter.buckets); got > cfg.MaxTenants {
		t.Errorf("bucket map holds %d tenants after eviction, want at most %d", got, cfg.MaxTenants)
	}
}

// TestTenantRateLimit_PublicRoutesAreNotTenantLimited: the tenant buckets
// gate the protected group only. With a tenant bucket spent, the liveness
// probe (exempt from the valve too) and the public webhook route still
// answer — anonymous traffic never touches a tenant bucket and a tenant's
// bucket never touches anonymous traffic.
func TestTenantRateLimit_PublicRoutesAreNotTenantLimited(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	limits := tenantRateLimitConfig{RPS: 1, Burst: 1, MaxTenants: 1}
	h := newTenantLimitHarness(t, rateLimiter(ctx, 1000, 1000), limits, newFakeClock())

	if w := h.sync("tenant-a"); w.Code != http.StatusAccepted {
		t.Fatalf("first sync: got %d, want 202", w.Code)
	}
	if w := h.sync("tenant-a"); w.Code != http.StatusTooManyRequests {
		t.Fatalf("second sync with burst 1: got %d, want 429", w.Code)
	}

	for i := 0; i < 50; i++ {
		w := httptest.NewRecorder()
		h.r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/health", nil))
		if w.Code != http.StatusOK {
			t.Fatalf("/health request %d while a tenant bucket is spent: got %d, want 200", i+1, w.Code)
		}
	}
	// The webhook is public and unconfigured here, so its own answer is the
	// fail-closed 503 — what matters is that it is not a 429.
	w := httptest.NewRecorder()
	h.r.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/v1/webhooks/whatsapp", strings.NewReader("{}")))
	if w.Code == http.StatusTooManyRequests {
		t.Errorf("public webhook route was rate-limited by a tenant bucket: got 429 (body %s)", w.Body.String())
	}
}
