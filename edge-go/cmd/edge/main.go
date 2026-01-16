package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jtoye/edge/internal/core"
	"github.com/jtoye/edge/internal/middleware"
	"go.uber.org/zap"
)

// Simple token bucket rate limiter middleware
func rateLimiter(rps int, burst int) gin.HandlerFunc {
	tokens := make(chan struct{}, burst)
	// fill bucket initially
	for i := 0; i < burst; i++ {
		tokens <- struct{}{}
	}
	// refill goroutine
	ticker := time.NewTicker(time.Second / time.Duration(rps))
	go func() {
		for range ticker.C {
			select {
			case tokens <- struct{}{}:
			default:
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

	// Configuration from environment
	coreAPIURL := getEnv("CORE_API_URL", "http://localhost:9090")
	keycloakIssuer := getEnv("KC_ISSUER_URI", "http://localhost:8085/realms/jtoye-dev")
	jwksURL := keycloakIssuer + "/protocol/openid-connect/certs"
	port := getEnv("PORT", "8080")

	// Initialize Core API client with circuit breaker
	coreClient := core.NewClient(coreAPIURL, logger)

	// Initialize JWT middleware
	jwtMiddleware := middleware.NewJWTMiddleware(jwksURL, keycloakIssuer, logger)

	// Setup Gin
	r := gin.Default()

	// Global rate limiter
	r.Use(rateLimiter(20, 40))

	// Public health endpoint
	r.GET("/health", func(c *gin.Context) {
		// Check Core API health
		ctx, cancel := context.WithTimeout(c.Request.Context(), 2*time.Second)
		defer cancel()

		coreHealthy := coreClient.HealthCheck(ctx) == nil

		status := gin.H{
			"edge":   "OK",
			"core":   map[string]bool{"healthy": coreHealthy},
			"uptime": time.Now().Unix(), // Placeholder
		}

		if !coreHealthy {
			c.JSON(http.StatusServiceUnavailable, status)
			return
		}

		c.JSON(http.StatusOK, status)
	})

	// Protected routes (require JWT)
	protected := r.Group("/")
	protected.Use(jwtMiddleware.Validate())

	protected.POST("/sync/batch", func(c *gin.Context) {
		var payload struct {
			Items []map[string]interface{} `json:"items"`
		}

		if err := c.ShouldBindJSON(&payload); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body"})
			return
		}

		// Extract tenant and token from context
		tenantID, _ := c.Get("tenant_id")
		token := c.GetHeader("Authorization")[7:] // Strip "Bearer "

		if tenantID == "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": "tenant_id missing from JWT"})
			return
		}

		// Forward to Core API with circuit breaker
		ctx, cancel := context.WithTimeout(c.Request.Context(), 30*time.Second)
		defer cancel()

		resp, err := coreClient.SyncBatch(ctx, token, tenantID.(string), payload.Items)
		if err != nil {
			logger.Error("Batch sync failed", zap.Error(err))
			c.JSON(http.StatusBadGateway, gin.H{"error": "failed to sync with core API"})
			return
		}

		c.JSON(http.StatusAccepted, resp)
	})

	protected.POST("/webhooks/whatsapp", func(c *gin.Context) {
		// WhatsApp uses SHA256 HMAC for signature verification
		// The signature is sent in the 'X-Hub-Signature-256' header
		signature := c.GetHeader("X-Hub-Signature-256")
		appSecret := os.Getenv("WHATSAPP_APP_SECRET")

		if appSecret != "" && signature != "" {
			body, err := io.ReadAll(c.Request.Body)
			if err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to read request body"})
				return
			}
			// Restore body for further processing
			c.Request.Body = io.NopCloser(bytes.NewBuffer(body))

			if !verifyWhatsAppSignature(body, signature, appSecret) {
				logger.Warn("Invalid WhatsApp webhook signature", zap.String("signature", signature))
				c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid signature"})
				return
			}
		} else if appSecret != "" {
			logger.Warn("Missing WhatsApp webhook signature")
			c.JSON(http.StatusUnauthorized, gin.H{"error": "missing signature"})
			return
		}

		// TODO: Process webhook and forward to Core API
		logger.Info("WhatsApp webhook received and verified")
		c.Status(http.StatusNoContent)
	})

	logger.Info("Edge service starting", zap.String("port", port), zap.String("core_api", coreAPIURL))

	if err := r.Run(":" + port); err != nil {
		logger.Fatal("Failed to start server", zap.Error(err))
	}
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
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

