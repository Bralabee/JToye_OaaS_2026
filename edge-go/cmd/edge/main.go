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
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jtoye/edge/internal/core"
	"github.com/jtoye/edge/internal/middleware"
	"github.com/jtoye/edge/internal/whatsapp"
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
	defaultShopID := getEnv("WHATSAPP_DEFAULT_SHOP_ID", "")

	// Initialize Core API client with circuit breaker
	coreClient := core.NewClient(coreAPIURL, logger)

	// Initialize JWT middleware
	jwtMiddleware := middleware.NewJWTMiddleware(jwksURL, keycloakIssuer, logger)

	// Setup Gin
	r := gin.Default()

	// Global rate limiter (configurable via env)
	rps := getEnvInt("RATE_LIMIT_RPS", 20)
	burst := getEnvInt("RATE_LIMIT_BURST", 40)
	logger.Info("Rate limiter configured", zap.Int("rps", rps), zap.Int("burst", burst))
	r.Use(rateLimiter(rps, burst))

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

	protected.POST("/api/v1/sync/batch", func(c *gin.Context) {
		var payload struct {
			Items []map[string]interface{} `json:"items"`
		}

		if err := c.ShouldBindJSON(&payload); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body"})
			return
		}

		// Extract tenant and token from context
		tenantID, _ := c.Get("tenant_id")
		token, ok := extractBearerToken(c)
		if !ok {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "missing or malformed bearer token"})
			return
		}

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

	protected.POST("/api/v1/webhooks/whatsapp", func(c *gin.Context) {
		// WhatsApp uses SHA256 HMAC for signature verification
		// The signature is sent in the 'X-Hub-Signature-256' header
		signature := c.GetHeader("X-Hub-Signature-256")
		appSecret := os.Getenv("WHATSAPP_APP_SECRET")

		// Fail-closed: refuse to accept webhooks if the signing secret is
		// not configured. Previously an unset secret would silently skip
		// signature verification, allowing anyone to inject orders.
		if appSecret == "" {
			logger.Error("WHATSAPP_APP_SECRET not configured; refusing webhook")
			c.JSON(http.StatusInternalServerError, gin.H{"error": "webhook signing not configured"})
			return
		}

		if signature == "" {
			logger.Warn("Missing WhatsApp webhook signature")
			c.JSON(http.StatusUnauthorized, gin.H{"error": "missing signature"})
			return
		}

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

		// Parse WhatsApp message into structured order
		parsedOrder, err := whatsapp.ParseWebhook(body)
		if err != nil {
			logger.Error("Failed to parse WhatsApp webhook", zap.Error(err))
			c.Status(http.StatusOK) // Still 200 to prevent retries
			return
		}
		if parsedOrder == nil || len(parsedOrder.Items) == 0 {
			logger.Info("WhatsApp webhook had no order items")
			c.Status(http.StatusOK)
			return
		}

		// Extract auth context
		tenantID, _ := c.Get("tenant_id")
		token, _ := extractBearerToken(c)
		tenantStr := ""
		if tenantID != nil {
			tenantStr = tenantID.(string)
		}

		ctx, cancel := context.WithTimeout(c.Request.Context(), 15*time.Second)
		defer cancel()

		// Resolve product queries to UUIDs via Core API search
		var orderItems []core.OrderItemRequest
		for _, item := range parsedOrder.Items {
			products, err := coreClient.SearchProducts(ctx, token, tenantStr, item.ProductQuery)
			if err != nil {
				logger.Warn("Product search failed", zap.String("query", item.ProductQuery), zap.Error(err))
				continue
			}
			if len(products) == 0 {
				logger.Warn("No product found for query", zap.String("query", item.ProductQuery))
				continue
			}
			// Use first match
			orderItems = append(orderItems, core.OrderItemRequest{
				ProductID: products[0].ID,
				Quantity:  item.Quantity,
			})
		}

		if len(orderItems) == 0 {
			logger.Warn("No products resolved from WhatsApp order", zap.String("phone", parsedOrder.Phone))
			c.Status(http.StatusOK)
			return
		}

		// Create order via Core API
		if defaultShopID == "" {
			logger.Error("WHATSAPP_DEFAULT_SHOP_ID not configured, cannot create order")
			c.Status(http.StatusOK)
			return
		}

		createReq := &core.CreateOrderRequest{
			ShopID:        defaultShopID,
			CustomerPhone: parsedOrder.Phone,
			Notes:         "WhatsApp order: " + parsedOrder.Raw,
			Items:         orderItems,
		}

		orderResp, err := coreClient.CreateOrder(ctx, token, tenantStr, createReq)
		if err != nil {
			logger.Error("Failed to create order from WhatsApp", zap.Error(err))
			c.Status(http.StatusOK) // Still 200 to prevent retries
			return
		}

		logger.Info("WhatsApp order created",
			zap.String("orderNumber", orderResp.OrderNumber),
			zap.String("phone", parsedOrder.Phone),
			zap.Int("items", len(orderItems)))
		c.Status(http.StatusOK)
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
