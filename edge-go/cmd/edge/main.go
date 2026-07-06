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

	return func(c *gin.Context) {
		select {
		case <-tokens:
			c.Next()
		default:
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{"error": "rate limit exceeded"})
		}
	}
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
	keycloakIssuer := getEnv("KC_ISSUER_URI", "http://localhost:8085/realms/jtoye-dev")
	jwksURL := keycloakIssuer + "/protocol/openid-connect/certs"
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
	jwtMiddleware := middleware.NewJWTMiddleware(jwksURL, keycloakIssuer, logger)

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

	// Setup Gin
	r := gin.Default()

	// Process-wide DoS guard (configurable via env). This is a per-replica
	// overload valve, not a per-tenant quota — see rateLimiter() and Core's
	// Bucket4j for the authoritative per-tenant limit.
	rps := getEnvInt("RATE_LIMIT_RPS", 20)
	burst := getEnvInt("RATE_LIMIT_BURST", 40)
	logger.Info("Process-wide DoS-guard rate limiter configured (per-replica, not per-tenant)",
		zap.Int("rps", rps), zap.Int("burst", burst))
	r.Use(rateLimiter(ctx, rps, burst))

	// Public probes. Liveness must not depend on any downstream — a failing
	// /health causes the kubelet to restart the pod, so it must not report
	// DOWN just because Core or Keycloak is having a bad day. Readiness
	// checks downstream and returns 503 when any dep is unhealthy so the
	// pod is pulled from the Service without being restarted.
	r.GET("/health", h.Health)
	r.GET("/ready", h.Ready)

	// Documentation routes (/openapi.json + /docs) are registered here. The
	// registration is wired up in docs.go (added in task 16-03) via
	// registerDocRoutes(r). Kept public (before the protected group) so
	// partners can fetch the spec without auth.
	registerDocRoutes(r)

	// WhatsApp webhook is a PUBLIC route: Meta authenticates via the HMAC
	// signature (verified in the handler), not a Keycloak JWT. Registered
	// outside the protected group so the real integration can actually call it.
	r.POST("/api/v1/webhooks/whatsapp", h.WhatsAppWebhook)

	// Protected routes (require JWT)
	protected := r.Group("/")
	protected.Use(jwtMiddleware.Validate())
	protected.POST("/api/v1/sync/batch", h.SyncBatch)

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

	// Block until the root context is cancelled (SIGINT/SIGTERM).
	<-ctx.Done()
	logger.Info("Shutdown signal received, draining connections")

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("HTTP server shutdown error", zap.Error(err))
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
