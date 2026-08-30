package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jtoye/edge/internal/core"
	"go.uber.org/zap"
)

func init() {
	gin.SetMode(gin.TestMode)
}

// --- extractBearerToken tests ---

func TestExtractBearerToken_Valid(t *testing.T) {
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest("GET", "/test", nil)
	c.Request.Header.Set("Authorization", "Bearer my-jwt-token")

	token, ok := extractBearerToken(c)
	if !ok {
		t.Error("Expected ok=true for valid Bearer token")
	}
	if token != "my-jwt-token" {
		t.Errorf("Expected 'my-jwt-token', got %q", token)
	}
}

func TestExtractBearerToken_MissingHeader(t *testing.T) {
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest("GET", "/test", nil)

	_, ok := extractBearerToken(c)
	if ok {
		t.Error("Expected ok=false for missing header")
	}
}

func TestExtractBearerToken_NonBearerScheme(t *testing.T) {
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest("GET", "/test", nil)
	c.Request.Header.Set("Authorization", "Basic dXNlcjpwYXNz")

	_, ok := extractBearerToken(c)
	if ok {
		t.Error("Expected ok=false for non-Bearer scheme")
	}
}

func TestExtractBearerToken_EmptyToken(t *testing.T) {
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest("GET", "/test", nil)
	c.Request.Header.Set("Authorization", "Bearer ")

	_, ok := extractBearerToken(c)
	if ok {
		t.Error("Expected ok=false for empty token after Bearer prefix")
	}
}

func TestExtractBearerToken_CaseInsensitive(t *testing.T) {
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest("GET", "/test", nil)
	c.Request.Header.Set("Authorization", "bearer my-token")

	token, ok := extractBearerToken(c)
	if !ok {
		t.Error("Expected ok=true for case-insensitive Bearer")
	}
	if token != "my-token" {
		t.Errorf("Expected 'my-token', got %q", token)
	}
}

func TestExtractBearerToken_BearerOnly(t *testing.T) {
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest("GET", "/test", nil)
	c.Request.Header.Set("Authorization", "Bearer")

	_, ok := extractBearerToken(c)
	if ok {
		t.Error("Expected ok=false when header is just 'Bearer' with no token")
	}
}

// --- Rate limiter tests ---

func TestRateLimiter_AllowsWithinBurst(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	handler := rateLimiter(ctx, 10, 5)

	for i := 0; i < 5; i++ {
		w := httptest.NewRecorder()
		c, _ := gin.CreateTestContext(w)
		c.Request = httptest.NewRequest("GET", "/test", nil)

		handler(c)

		if w.Code == http.StatusTooManyRequests {
			t.Errorf("Request %d should be allowed within burst of 5", i+1)
		}
	}
}

func TestRateLimiter_BlocksAfterBurstExhausted(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	handler := rateLimiter(ctx, 1, 3)

	// Exhaust the burst
	for i := 0; i < 3; i++ {
		w := httptest.NewRecorder()
		c, _ := gin.CreateTestContext(w)
		c.Request = httptest.NewRequest("GET", "/test", nil)
		handler(c)
	}

	// Next request should be blocked
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest("GET", "/test", nil)
	handler(c)

	if w.Code != http.StatusTooManyRequests {
		t.Errorf("Expected 429 after burst exhausted, got %d", w.Code)
	}

	var resp map[string]interface{}
	json.NewDecoder(w.Body).Decode(&resp)
	if resp["error"] != "rate limit exceeded" {
		t.Errorf("Expected 'rate limit exceeded' error, got %v", resp["error"])
	}
}

func TestRateLimiter_RefillsOverTime(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// High RPS so refill happens quickly
	handler := rateLimiter(ctx, 100, 1)

	// Exhaust the single token
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest("GET", "/test", nil)
	handler(c)

	// Wait for refill (100 rps = 10ms per token)
	time.Sleep(50 * time.Millisecond)

	// Should be allowed after refill
	w2 := httptest.NewRecorder()
	c2, _ := gin.CreateTestContext(w2)
	c2.Request = httptest.NewRequest("GET", "/test", nil)
	handler(c2)

	if w2.Code == http.StatusTooManyRequests {
		t.Error("Expected request to succeed after token refill")
	}
}

func TestRateLimiter_ContextCancelStopsRefill(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	handler := rateLimiter(ctx, 100, 1)

	// Exhaust the token
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest("GET", "/test", nil)
	handler(c)

	// Cancel context — refill goroutine should stop
	cancel()
	time.Sleep(50 * time.Millisecond)

	// No refill should happen after cancel
	w2 := httptest.NewRecorder()
	c2, _ := gin.CreateTestContext(w2)
	c2.Request = httptest.NewRequest("GET", "/test", nil)
	handler(c2)

	if w2.Code != http.StatusTooManyRequests {
		t.Error("Expected 429 after context cancellation stops refill")
	}
}

func TestRateLimiter_HealthEndpointExempt(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Deliberately tiny burst: any non-exempt path would trip 429 well before
	// 50 requests, so this loop is a real test of the exemption, not a
	// tolerance check on the token math.
	handler := rateLimiter(ctx, 1, 2)

	for i := 0; i < 50; i++ {
		w := httptest.NewRecorder()
		c, _ := gin.CreateTestContext(w)
		c.Request = httptest.NewRequest("GET", "/health", nil)

		handler(c)

		if w.Code == http.StatusTooManyRequests {
			t.Fatalf("/health request %d was rate-limited (429) — the kubelet liveness probe must never trip the token bucket", i+1)
		}
	}
}

func TestRateLimiter_ReadyEndpointExempt(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	handler := rateLimiter(ctx, 1, 2)

	for i := 0; i < 50; i++ {
		w := httptest.NewRecorder()
		c, _ := gin.CreateTestContext(w)
		c.Request = httptest.NewRequest("GET", "/ready", nil)

		handler(c)

		if w.Code == http.StatusTooManyRequests {
			t.Fatalf("/ready request %d was rate-limited (429) — the kubelet readiness probe must never trip the token bucket", i+1)
		}
	}
}

func TestRateLimiter_BusinessRouteStillLimited(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	handler := rateLimiter(ctx, 1, 2)

	// Exhaust the burst on a real business route.
	for i := 0; i < 2; i++ {
		w := httptest.NewRecorder()
		c, _ := gin.CreateTestContext(w)
		c.Request = httptest.NewRequest("POST", "/api/v1/sync/batch", nil)
		handler(c)
	}

	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest("POST", "/api/v1/sync/batch", nil)
	handler(c)

	if w.Code != http.StatusTooManyRequests {
		t.Errorf("Expected a business route to still be rate-limited after its burst is exhausted, got %d", w.Code)
	}
}

// --- Health endpoint tests ---

func setupRouter(coreServer *httptest.Server, jwksServer *httptest.Server) *gin.Engine {
	logger, _ := zap.NewProduction()
	coreClient := core.NewClient(coreServer.URL, logger)

	r := gin.New()

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"edge":   "OK",
			"uptime": time.Now().Unix(),
		})
	})

	r.GET("/ready", func(c *gin.Context) {
		readyCtx, cancel := context.WithTimeout(c.Request.Context(), 2*time.Second)
		defer cancel()

		coreHealthy := coreClient.HealthCheck(readyCtx) == nil

		jwksHealthy := true
		jwksReq, err := http.NewRequestWithContext(readyCtx, http.MethodGet, jwksServer.URL, nil)
		if err != nil {
			jwksHealthy = false
		} else {
			jwksResp, jerr := (&http.Client{Timeout: 2 * time.Second}).Do(jwksReq)
			if jerr != nil || jwksResp == nil || jwksResp.StatusCode != http.StatusOK {
				jwksHealthy = false
			}
			if jwksResp != nil {
				jwksResp.Body.Close()
			}
		}

		status := gin.H{
			"edge": "OK",
			"core": map[string]bool{"healthy": coreHealthy},
			"jwks": map[string]bool{"healthy": jwksHealthy},
		}

		if !coreHealthy || !jwksHealthy {
			c.JSON(http.StatusServiceUnavailable, status)
			return
		}
		c.JSON(http.StatusOK, status)
	})

	return r
}

func TestHealthEndpoint_ReturnsOK(t *testing.T) {
	coreServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer coreServer.Close()
	jwksServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer jwksServer.Close()

	r := setupRouter(coreServer, jwksServer)
	w := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/health", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("Expected 200, got %d", w.Code)
	}

	var resp map[string]interface{}
	json.NewDecoder(w.Body).Decode(&resp)
	if resp["edge"] != "OK" {
		t.Errorf("Expected edge=OK, got %v", resp["edge"])
	}
}

func TestReadyEndpoint_AllHealthy(t *testing.T) {
	coreServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer coreServer.Close()
	jwksServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer jwksServer.Close()

	r := setupRouter(coreServer, jwksServer)
	w := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/ready", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("Expected 200 when all healthy, got %d", w.Code)
	}
}

func TestReadyEndpoint_CoreDown(t *testing.T) {
	// Core returns 503
	coreServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer coreServer.Close()
	jwksServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer jwksServer.Close()

	r := setupRouter(coreServer, jwksServer)
	w := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/ready", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Errorf("Expected 503 when core is down, got %d", w.Code)
	}
}

func TestReadyEndpoint_JWKSDown(t *testing.T) {
	coreServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer coreServer.Close()
	// JWKS returns 500
	jwksServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer jwksServer.Close()

	r := setupRouter(coreServer, jwksServer)
	w := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/ready", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Errorf("Expected 503 when JWKS is down, got %d", w.Code)
	}
}

func TestReadyEndpoint_BothDown(t *testing.T) {
	coreServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer coreServer.Close()
	jwksServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer jwksServer.Close()

	r := setupRouter(coreServer, jwksServer)
	w := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/ready", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Errorf("Expected 503 when both are down, got %d", w.Code)
	}
}

// --- getEnv / getEnvInt tests ---

func TestGetEnv_DefaultValue(t *testing.T) {
	val := getEnv("JTOYE_TEST_NONEXISTENT_VAR", "fallback")
	if val != "fallback" {
		t.Errorf("Expected 'fallback', got %q", val)
	}
}

func TestGetEnvInt_DefaultValue(t *testing.T) {
	val := getEnvInt("JTOYE_TEST_NONEXISTENT_INT", 42)
	if val != 42 {
		t.Errorf("Expected 42, got %d", val)
	}
}

func TestGetEnvInt_InvalidValue(t *testing.T) {
	t.Setenv("JTOYE_TEST_BAD_INT", "notanumber")
	val := getEnvInt("JTOYE_TEST_BAD_INT", 99)
	if val != 99 {
		t.Errorf("Expected default 99 for non-numeric value, got %d", val)
	}
}
