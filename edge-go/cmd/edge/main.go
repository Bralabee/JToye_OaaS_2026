// Package main is the J'Toye edge gateway. It sits between the public
// internet and the core-java API, enforcing authentication, rate limiting,
// and circuit-breaker protection, plus handling async channels (WhatsApp
// webhooks) that Core doesn't front-door directly.
//
// swaggo/swag parses this file's top-level doc comments to generate the
// OpenAPI (Swagger 2.0) spec served at /openapi.json + /docs. See
// .planning/phases/16-go-edge-openapi/16-RESEARCH.md for the rationale on
// picking swag v1 (Swagger 2.0) over swag v2 (OpenAPI 3.x draft).
//
// @title                       J'Toye Edge Gateway API
// @version                     1.0
// @description                 Edge gateway for J'Toye OaaS multi-tenant retail platform. Routes authenticated traffic to core-java with rate limiting + circuit breakers, plus handles WhatsApp order-intake webhooks with HMAC-SHA256 signature verification.
// @termsOfService              https://github.com/jtoye/oaas
// @contact.name                J'Toye Platform Team
// @contact.url                 https://github.com/jtoye/oaas
// @license.name                Proprietary
// @BasePath                    /
// @securityDefinitions.apikey  BearerAuth
// @in                          header
// @name                        Authorization
// @description                 Keycloak-issued JWT. Prefix the value with `Bearer `.
package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jtoye/edge/internal/auth"
	"github.com/jtoye/edge/internal/core"
	"github.com/jtoye/edge/internal/middleware"
	"go.uber.org/zap"
)

// extractBearerToken pulls a Bearer token out of the Authorization header.
// Returns ("", false) if the header is missing, uses a different scheme, or
// carries no token value. Callers must treat the boolean as authoritative —
// never index into the header string directly.
func extractBearerToken(c *gin.Context) (string, bool) {
	authHeader := c.GetHeader("Authorization")
	if authHeader == "" {
		return "", false
	}
	const prefix = "Bearer "
	if len(authHeader) <= len(prefix) {
		return "", false
	}
	if !strings.EqualFold(authHeader[:len(prefix)], prefix) {
		return "", false
	}
	token := strings.TrimSpace(authHeader[len(prefix):])
	if token == "" {
		return "", false
	}
	return token, true
}

// resolveManagementPort normalises and validates EDGE_MANAGEMENT_PORT.
//
// An empty value is legal and means "serve /metrics on the application port" —
// the pre-#442 behaviour, kept as the default so an operator who supplies
// nothing loses no scrape (k8s is exactly that case: it ships no Prometheus,
// and k8s/base/edge-go-deployment.yaml still annotates prometheus.io/port 8080).
//
// Anything else must be a real port. Returning an error rather than logging
// here keeps the function pure and testable; main turns it into a Fatal. That
// is deliberate: a set-but-malformed value already removes /metrics from the
// application router, so limping on would leave the gateway with no scrape
// endpoint at all and no explanation. Fail loudly at boot instead — the same
// fail-fast the Prometheus entrypoint applies to the other half of this pair.
func resolveManagementPort(raw string) (string, error) {
	port := strings.TrimSpace(raw)
	if port == "" {
		return "", nil
	}
	n, err := strconv.Atoi(port)
	if err != nil {
		return "", fmt.Errorf("EDGE_MANAGEMENT_PORT must be an integer 1-65535, got %q: %w", port, err)
	}
	if n < 1 || n > 65535 {
		return "", fmt.Errorf("EDGE_MANAGEMENT_PORT must be an integer 1-65535, got %d", n)
	}
	return port, nil
}

// registerAppMetricsRoute wires the Prometheus scrape endpoint onto the
// APPLICATION router, and only when no management port is configured. It
// reports whether it registered the route, so the caller can log which of the
// two topologies is live.
//
// issue #550 (SEC-02 / C4): the published application port is a named app-tier
// exemption bound on all interfaces, so an unauthenticated /metrics there
// discloses the gateway's route templates to anyone who can reach the host.
// docker-compose.full-stack.yml now supplies EDGE_MANAGEMENT_PORT, which moves
// the route to the management listener below; the Prometheus scrape target is
// moved in the same change (infra/monitoring/prometheus/prometheus.yml.tmpl).
//
// The engine is taken concretely rather than as gin.IRoutes because the caller
// that matters most — the test — asks the router what it registered via
// Routes(), which only *gin.Engine exposes. An interface here would hide the
// one question worth asking.
func registerAppMetricsRoute(r *gin.Engine, managementPort string) bool {
	if managementPort != "" {
		return false
	}
	r.GET("/metrics", metricsHandler())
	return true
}

// newManagementRouter builds the listener that serves ONLY /metrics.
//
// It gets its own gin engine rather than reusing the application one: that
// engine carries the process-wide rate limiter and the request-metrics
// middleware, and a scrape must never be rate-limited (dropped samples make the
// alerting layer flap) nor recorded as application traffic (the scrape would
// appear in the very series it is collecting).
func newManagementRouter() *gin.Engine {
	mgmtRouter := gin.New()
	mgmtRouter.Use(gin.Recovery())
	mgmtRouter.GET("/metrics", metricsHandler())
	return mgmtRouter
}

// rateLimiterExemptPaths are routes the token bucket must never gate.
//
// issue API-4: the limiter previously sat in front of EVERY route, including
// /health and /ready — so a burst of real traffic (or another probe firing
// concurrently) could starve the kubelet's own liveness/readiness checks of
// tokens. A liveness probe that gets 429'd looks identical to a genuinely
// wedged process, so the kubelet restarts a healthy pod; a readiness probe
// that gets 429'd looks like a downstream outage, so the pod is pulled from
// the Service. Both are self-inflicted — the DoS guard causing the exact
// outage it exists to prevent. Probes are exempted from the bucket entirely
// (not merely refunded after the fact) so they never compete with real
// traffic for burst capacity either.
//
// This is a small, closed allow-list keyed on the literal request path
// rather than c.FullPath(): the limiter middleware can be (and is, in
// TestRateLimiter_*) exercised directly against a bare *gin.Context that
// never went through router dispatch, where FullPath() is still empty.
var rateLimiterExemptPaths = map[string]bool{
	"/health": true,
	"/ready":  true,
}

// Defaults of the pre-auth valve, config-declared through RATE_LIMIT_RPS and
// RATE_LIMIT_BURST.
//
// 37-25 (D-19): raised from 20 rps / 40 burst to 200 / 400. The valve was the
// ONLY limit, so it had to be sized as a quota; now the per-tenant limiter
// (ratelimit_tenant.go, 10 rps / 20 burst per tenant) is the quota and the
// valve is only the per-replica flood guard. 400 is twenty tenants at their
// full burst at once; 200 rps is twenty tenants at their full steady rate.
// A smaller valve than that would let one tenant's refused burst — which
// still spends valve tokens before auth — starve the others again.
const (
	defaultRateLimitRPS   = 200
	defaultRateLimitBurst = 400
)

// valveLimitsFromEnv reads the process-wide valve's rate and burst from the
// environment, with the documented defaults (getEnvInt falls back on a
// non-positive or non-numeric value).
func valveLimitsFromEnv() (rps int, burst int) {
	return getEnvInt("RATE_LIMIT_RPS", defaultRateLimitRPS), getEnvInt("RATE_LIMIT_BURST", defaultRateLimitBurst)
}

// rateLimiter is a single, process-wide token-bucket used purely as a coarse
// DoS / overload guard for this edge replica — it is NOT a per-tenant quota.
// The authoritative per-tenant rate limit (e.g. 100 req/min per tenant) lives
// in Core via Bucket4j. Note that with multiple edge replicas the effective
// global ceiling is this limit multiplied by the replica count.
//
// The refill goroutine is tied to the supplied context so it exits cleanly
// on graceful shutdown instead of leaking the ticker + goroutine for the
// process lifetime.
func rateLimiter(ctx context.Context, rps int, burst int) gin.HandlerFunc {
	tokens := make(chan struct{}, burst)
	// fill bucket initially
	for i := 0; i < burst; i++ {
		tokens <- struct{}{}
	}
	// refill goroutine
	ticker := time.NewTicker(time.Second / time.Duration(rps))
	go func() {
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				select {
				case tokens <- struct{}{}:
				default:
				}
			}
		}
	}()

	// One refill tick: the longest a refused caller has to wait for a token,
	// and what Retry-After advertises (rounded up to whole seconds, min 1).
	tick := time.Second / time.Duration(rps)

	return func(c *gin.Context) {
		if rateLimiterExemptPaths[c.Request.URL.Path] {
			c.Next()
			return
		}
		select {
		case <-tokens:
			c.Next()
		default:
			// 37-25 (D-19): the same typed 429 as the per-tenant limiter, so
			// an integrator backs off from either refusal the same way. The
			// bucket is empty by construction here; Reset is when it would
			// be full again at the configured rate.
			now := time.Now()
			writeRateLimited(c, rateDecision{
				allowed:    false,
				limit:      burst,
				remaining:  0,
				retryAfter: tick,
				resetAt:    now.Add(time.Duration(burst) * tick),
			})
		}
	}
}

// routerDeps is everything newRouter needs that main() resolves from the
// environment or constructs from it. The JWT validator and the pre-auth valve
// are passed as handlers rather than built inside, so a test can drive the
// REAL route table and middleware order with a stubbed validator (one that
// sets tenant_id from a header) and a deterministic valve — the wiring under
// test is then the wiring that ships, not a copy of it in the test file.
type routerDeps struct {
	handlers *edgeHandlers
	// validate is jwtMiddleware.Validate() in production. It is the ONLY
	// writer of c.Get("tenant_id") on the protected group.
	validate gin.HandlerFunc
	// valve is the process-wide pre-auth rateLimiter — the DoS guard that
	// gates every non-exempt route before authentication.
	valve gin.HandlerFunc
	// tenantLimits is the per-tenant quota applied on the protected group,
	// after validate has written tenant_id (ratelimit_tenant.go).
	tenantLimits tenantRateLimitConfig
	// clock is the per-tenant limiter's time source: realClock in
	// production, a fake in tests.
	clock clock
	// managementPort, when non-empty, moves /metrics off the application
	// router (issue #550); see registerAppMetricsRoute.
	managementPort string
	// accessLog adds gin.Logger(); main sets it, tests leave it off.
	accessLog bool
}

// newRouter builds the application router. It returns whether /metrics was
// registered on it (false when a management port takes the route), so main
// can log which topology is live.
func newRouter(d routerDeps) (*gin.Engine, bool) {
	r := gin.New()
	if d.accessLog {
		r.Use(gin.Logger())
	}
	r.Use(gin.Recovery())

	// Prometheus instrumentation. Registered before the rate limiter so EVERY
	// request — including those rejected with 429 — is counted with its final
	// status. See metrics.go for the low-cardinality route-template labelling.
	r.Use(prometheusMiddleware())

	// Process-wide DoS guard: a per-replica overload valve, not a per-tenant
	// quota (see rateLimiter()).
	r.Use(d.valve)

	// Public probes. Liveness must not depend on any downstream — a failing
	// /health causes the kubelet to restart the pod, so it must not report
	// DOWN just because Core or Keycloak is having a bad day. Readiness
	// checks downstream and returns 503 when any dep is unhealthy so the
	// pod is pulled from the Service without being restarted.
	r.GET("/health", d.handlers.Health)
	r.GET("/ready", d.handlers.Ready)

	// Prometheus scrape endpoint. Public (no JWT) so Prometheus can scrape it;
	// exposes only aggregate, low-cardinality series (see metrics.go). Absent
	// from this router when a management port is configured (main.go explains).
	metricsOnAppPort := registerAppMetricsRoute(r, d.managementPort)

	// Documentation routes (/openapi.json + /docs) are registered here. The
	// registration is wired up in docs.go (added in task 16-03) via
	// registerDocRoutes(r). Kept public (before the protected group) so
	// partners can fetch the spec without auth.
	registerDocRoutes(r)

	// WhatsApp webhook is a PUBLIC route: Meta authenticates via the HMAC
	// signature (verified in the handler), not a Keycloak JWT. Registered
	// outside the protected group so the real integration can actually call it.
	r.POST("/api/v1/webhooks/whatsapp", d.handlers.WhatsAppWebhook)

	// Protected routes (require JWT). The per-tenant limiter runs AFTER the
	// validator and keys on the tenant_id it wrote — never on a header the
	// caller controls (D-19, T-37-64) — so one tenant's burst is refused
	// with a typed 429 while every other tenant's sync continues (UXT-039).
	protected := r.Group("/")
	protected.Use(d.validate)
	protected.Use(tenantRateLimiter(d.tenantLimits, d.clock))
	protected.POST("/api/v1/sync/batch", d.handlers.SyncBatch)

	return r, metricsOnAppPort
}

func main() {
	// Check for health-check command (used by Docker HEALTHCHECK)
	if len(os.Args) > 1 && os.Args[1] == "health-check" {
		port := getEnv("PORT", "8080")
		resp, err := http.Get("http://localhost:" + port + "/health")
		if err != nil || resp.StatusCode != http.StatusOK {
			os.Exit(1)
		}
		os.Exit(0)
	}

	// Initialize structured logger
	logger, _ := zap.NewProduction()
	defer logger.Sync()

	// Root context cancelled on SIGINT/SIGTERM; used to stop background
	// goroutines (rate limiter refill, future workers) on graceful shutdown.
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	// Configuration from environment
	coreAPIURL := getEnv("CORE_API_URL", "http://localhost:9090")
	// KC_ISSUER_URI is the INTERNAL Keycloak host (reachable in-network) used only
	// to LOCATE the JWKS endpoint. It is NOT reused as the expected 'iss' claim:
	// Keycloak stamps its PUBLIC frontend issuer (KC_HOSTNAME, e.g. localhost:8085),
	// which differs from the internal host in a containerised topology. Validating
	// 'iss' against the internal host rejected every real token ("invalid issuer");
	// JWT_EXPECTED_ISSUER decouples the two (issue #87 follow-up), defaulting to the
	// JWKS host so single-host setups and existing tests are unaffected.
	keycloakIssuer := getEnv("KC_ISSUER_URI", "http://localhost:8085/realms/jtoye-dev")
	jwksURL := keycloakIssuer + "/protocol/openid-connect/certs"
	expectedIssuer := getEnv("JWT_EXPECTED_ISSUER", keycloakIssuer)
	port := getEnv("PORT", "8080")
	defaultShopID := getEnv("WHATSAPP_DEFAULT_SHOP_ID", "")
	// WhatsApp intake is a signature-only public route; these scope the
	// edge->Core service-token call that replaces the (impossible) caller JWT.
	whatsAppTenantID := getEnv("WHATSAPP_DEFAULT_TENANT_ID", "")
	whatsAppClientID := getEnv("WHATSAPP_SERVICE_CLIENT_ID", "")
	whatsAppClientSecret := getEnv("WHATSAPP_SERVICE_CLIENT_SECRET", "")

	// Initialize Core API client with circuit breaker
	coreClient := core.NewClient(coreAPIURL, logger)

	// Initialize JWT middleware
	jwtMiddleware := middleware.NewJWTMiddleware(jwksURL, expectedIssuer, logger)

	// Client-credentials provider used by the public WhatsApp webhook to
	// authenticate to Core (Meta cannot present a Keycloak JWT).
	whatsAppTokenProvider := auth.NewKeycloakServiceTokenProvider(
		keycloakIssuer, whatsAppClientID, whatsAppClientSecret, logger)

	// Handler bundle — holds per-process deps the Gin handlers need. See
	// handlers.go for the actual swaggo-annotated route functions.
	h := &edgeHandlers{
		coreClient:       coreClient,
		logger:           logger,
		jwksURL:          jwksURL,
		defaultShopID:    defaultShopID,
		startedAt:        time.Now(),
		tokenProvider:    whatsAppTokenProvider,
		whatsAppTenantID: whatsAppTenantID,
	}

	// Process-wide DoS guard (configurable via env). This is a per-replica
	// overload valve, not a per-tenant quota — see rateLimiter() and Core's
	// Bucket4j for the authoritative per-tenant limit.
	rps, burst := valveLimitsFromEnv()
	logger.Info("Process-wide DoS-guard rate limiter configured (per-replica, not per-tenant)",
		zap.Int("rps", rps), zap.Int("burst", burst))
	tenantLimits := tenantLimitsFromEnv()
	logger.Info("Per-tenant rate limiter configured (after JWT validation, keyed on tenant_id)",
		zap.Int("rps", tenantLimits.RPS), zap.Int("burst", tenantLimits.Burst), zap.Int("max_tenants", tenantLimits.MaxTenants))

	// Prometheus scrape endpoint topology (see newRouter for the route itself).
	//
	// issue #442 [SEC-02 / F-M7] built the switch; issue #550 [SEC-02 / C4] turns it
	// on for compose. When EDGE_MANAGEMENT_PORT is set, /metrics is served ONLY on
	// the separate management listener (below) and is deliberately absent from the
	// application router, so it is not reachable on the published application port.
	// Mirrors core-java's management.server.port so one mental model covers both
	// runtimes.
	//
	// UNSET => the route stays exactly where it was. That default is still
	// load-bearing for any runtime whose scrape config targets the app port with no
	// credentials; the compose scrape target moved to the management port in the same
	// change that set the variable, so the two can never disagree.
	//
	// /health and /ready deliberately stay on the main port either way: the kubelet
	// probes target them there, and moving them would fail every rollout.
	managementPort, err := resolveManagementPort(os.Getenv("EDGE_MANAGEMENT_PORT"))
	if err != nil {
		logger.Fatal("Invalid EDGE_MANAGEMENT_PORT", zap.Error(err))
	}

	// Setup Gin. The wiring lives in newRouter so the tests can drive the
	// production route table and middleware order with a stubbed JWT validator.
	r, metricsOnAppPort := newRouter(routerDeps{
		handlers:       h,
		validate:       jwtMiddleware.Validate(),
		valve:          rateLimiter(ctx, rps, burst),
		tenantLimits:   tenantLimits,
		clock:          realClock{},
		managementPort: managementPort,
		accessLog:      true,
	})
	if metricsOnAppPort {
		logger.Info("/metrics is served on the APPLICATION port — set EDGE_MANAGEMENT_PORT to move it to a separate listener",
			zap.String("port", port))
	}

	logger.Info("Edge service starting", zap.String("port", port), zap.String("core_api", coreAPIURL))

	srv := &http.Server{
		Addr:    ":" + port,
		Handler: r,
	}

	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("Failed to start server", zap.Error(err))
		}
	}()

	// issue #442: the management listener. Serves ONLY /metrics, and only when
	// EDGE_MANAGEMENT_PORT is set — inert when it is not, so a runtime that
	// supplies nothing keeps the pre-#442 topology. See newManagementRouter for
	// why it does not reuse `r`.
	//
	// The port is deliberately NOT published to the host in
	// docker-compose.full-stack.yml: Prometheus reaches it over the compose
	// network, and publishing it would recreate the exposure #550 is closing.
	var mgmtSrv *http.Server
	if managementPort != "" {
		mgmtSrv = &http.Server{
			Addr:    ":" + managementPort,
			Handler: newManagementRouter(),
		}
		logger.Info("Management listener starting — /metrics is served here ONLY, not on the application port",
			zap.String("management_port", managementPort))
		go func() {
			if err := mgmtSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
				logger.Fatal("Failed to start management server", zap.Error(err))
			}
		}()
	}

	// Block until the root context is cancelled (SIGINT/SIGTERM).
	<-ctx.Done()
	logger.Info("Shutdown signal received, draining connections")

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("HTTP server shutdown error", zap.Error(err))
	}
	if mgmtSrv != nil {
		if err := mgmtSrv.Shutdown(shutdownCtx); err != nil {
			logger.Error("Management server shutdown error", zap.Error(err))
		}
	}
	// cancel() is already deferred above, which stops the rate limiter ticker.
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if value := os.Getenv(key); value != "" {
		if parsed, err := strconv.Atoi(value); err == nil && parsed > 0 {
			return parsed
		}
	}
	return defaultValue
}

func verifyWhatsAppSignature(payload []byte, signature string, secret string) bool {
	// Remove 'sha256=' prefix if present
	actualSignature := signature
	if strings.HasPrefix(signature, "sha256=") {
		actualSignature = signature[7:]
	}

	h := hmac.New(sha256.New, []byte(secret))
	h.Write(payload)
	expectedSignature := hex.EncodeToString(h.Sum(nil))

	return hmac.Equal([]byte(actualSignature), []byte(expectedSignature))
}
