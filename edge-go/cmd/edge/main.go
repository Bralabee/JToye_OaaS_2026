package main

import (
	"context"
	"net/http"
	"os"
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
	// Initialize structured logger
	logger, _ := zap.NewProduction()
	defer logger.Sync()

	// Configuration from environment
	coreAPIURL := getEnv("CORE_API_URL", "http://localhost:8080")
	keycloakIssuer := getEnv("KC_ISSUER_URI", "http://localhost:8081/realms/jtoye-dev")
	jwksURL := keycloakIssuer + "/protocol/openid-connect/certs"
	port := getEnv("PORT", "8090")

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
		// TODO: Implement WhatsApp webhook signature verification
		// TODO: Process webhook and forward to Core API
		logger.Info("WhatsApp webhook received")
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

