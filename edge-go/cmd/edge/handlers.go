package main

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jtoye/edge/internal/auth"
	"github.com/jtoye/edge/internal/core"
	"github.com/jtoye/edge/internal/whatsapp"
	"go.uber.org/zap"
)

// edgeHandlers bundles the dependencies each Gin handler needs. Previously
// these were captured by anonymous closures inside main(); extracting them
// to methods on a struct lets swaggo parse doc comments (anonymous funcs
// are invisible to swaggo) AND makes the handlers unit-testable in
// isolation. Behavior is byte-identical to the pre-refactor code.
type edgeHandlers struct {
	coreClient    *core.Client
	logger        *zap.Logger
	jwksURL       string
	defaultShopID string

	// startedAt is captured at process start so /health can report a real
	// uptime instead of a wall-clock timestamp.
	startedAt time.Time

	// WhatsApp intake is a signature-only public route (Meta cannot present a
	// Keycloak JWT). tokenProvider mints a client-credentials service token
	// for edge->Core calls, and whatsAppTenantID scopes the order to the
	// configured vendor tenant.
	tokenProvider    auth.ServiceTokenProvider
	whatsAppTenantID string
}

// Health godoc
// @Summary     Liveness probe
// @Description Returns 200 whenever the edge process is alive. Does not
// @Description touch any downstream dependency, so it never fails because
// @Description Core or Keycloak is having a bad day. Used by kubelet as
// @Description the liveness probe — a failing /health causes the pod to
// @Description be restarted.
// @Tags        health
// @Produce     json
// @Success     200 {object} HealthResponse
// @Router      /health [get]
func (h *edgeHandlers) Health(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"edge": "OK",
		// Real uptime in seconds since process start (startedAt is set in
		// main()); zero when unset so the field is always present.
		"uptime": uptimeSeconds(h.startedAt),
	})
}

// uptimeSeconds returns whole seconds elapsed since startedAt, or 0 when
// startedAt is the zero value (not yet initialised).
func uptimeSeconds(startedAt time.Time) int64 {
	if startedAt.IsZero() {
		return 0
	}
	return int64(time.Since(startedAt).Seconds())
}

// Ready godoc
// @Summary     Readiness probe
// @Description Checks downstream dependencies (Core API /health + Keycloak
// @Description JWKS). Returns 503 with a per-component health map when any
// @Description dependency is unhealthy so kubelet pulls the pod out of the
// @Description Service endpoint set without restarting it.
// @Tags        health
// @Produce     json
// @Success     200 {object} ReadyResponse "All downstream dependencies healthy"
// @Failure     503 {object} ReadyResponse "One or more dependencies unhealthy"
// @Router      /ready [get]
func (h *edgeHandlers) Ready(c *gin.Context) {
	readyCtx, cancel := context.WithTimeout(c.Request.Context(), 2*time.Second)
	defer cancel()

	coreHealthy := h.coreClient.HealthCheck(readyCtx) == nil

	jwksHealthy := true
	jwksReq, err := http.NewRequestWithContext(readyCtx, http.MethodGet, h.jwksURL, nil)
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
}

// SyncBatch godoc
// @Summary     Batch-sync edge-collected items into Core
// @Description Forwards an opaque list of domain items to the Core Java
// @Description API's /api/v1/sync/batch endpoint, wrapped in a circuit
// @Description breaker so edge degrades gracefully when Core is down. The
// @Description caller must present a Keycloak JWT — the tenant_id claim
// @Description is extracted and used to scope the write.
// @Tags        sync
// @Security    BearerAuth
// @Accept      json
// @Produce     json
// @Param       payload body     SyncBatchRequest  true "Batch payload"
// @Success     202     {object} SyncBatchResponse "Batch accepted by Core"
// @Failure     400     {object} ErrorResponse     "Invalid body or missing tenant_id claim"
// @Failure     401     {object} ErrorResponse     "Missing or malformed Bearer token"
// @Failure     502     {object} ErrorResponse     "Core API unreachable or tripped the circuit breaker"
// @Router      /api/v1/sync/batch [post]
func (h *edgeHandlers) SyncBatch(c *gin.Context) {
	var payload struct {
		Items []map[string]interface{} `json:"items"`
	}

	if err := c.ShouldBindJSON(&payload); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body"})
		return
	}

	// Extract tenant and token from context. The JWT middleware always sets
	// tenant_id as a string, but use the comma-ok form so a misconfiguration
	// (missing/non-string value) returns 400 instead of panicking.
	tenantVal, _ := c.Get("tenant_id")
	tenantID, ok := tenantVal.(string)
	token, hasToken := extractBearerToken(c)
	if !hasToken {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "missing or malformed bearer token"})
		return
	}

	if !ok || tenantID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "tenant_id missing from JWT"})
		return
	}

	// Forward to Core API with circuit breaker
	ctx, cancel := context.WithTimeout(c.Request.Context(), 30*time.Second)
	defer cancel()

	resp, err := h.coreClient.SyncBatch(ctx, token, tenantID, payload.Items)
	if err != nil {
		h.logger.Error("Batch sync failed", zap.Error(err))
		c.JSON(http.StatusBadGateway, gin.H{"error": "failed to sync with core API"})
		return
	}

	c.JSON(http.StatusAccepted, resp)
}

// WhatsAppWebhook godoc
// @Summary     WhatsApp order-intake webhook
// @Description Accepts WhatsApp message-events, verifies the HMAC-SHA256
// @Description signature in `X-Hub-Signature-256` against
// @Description `WHATSAPP_APP_SECRET`, parses the message body into an
// @Description order, resolves each product-name query to a product UUID
// @Description via the Core search endpoint, and creates an order scoped
// @Description to the vendor identified by `WHATSAPP_DEFAULT_SHOP_ID`. The
// @Description endpoint is fail-closed: if the secret is not configured
// @Description or the signature is invalid/absent the webhook is rejected.
// @Description
// @Description Processing outcomes (bad payload, ambiguous product, missing
// @Description default shop, Core error) always return HTTP 200 to prevent
// @Description WhatsApp from entering its 3-day exponential retry loop.
// @Description Real error signals are in the structured logs.
// @Description
// @Description This is a PUBLIC route: Meta authenticates via the HMAC
// @Description signature alone (it cannot present a Keycloak JWT). The edge
// @Description mints its own client-credentials service token for the
// @Description edge->Core call and scopes the order to WHATSAPP_DEFAULT_TENANT_ID.
// @Tags        webhooks
// @Accept      json
// @Produce     json
// @Param       X-Hub-Signature-256 header   string      true  "HMAC-SHA256 of the raw body, prefixed with 'sha256='"
// @Param       payload             body     object      true  "WhatsApp message-event payload (shape defined by Meta)"
// @Success     200 {object} WebhookAck    "Webhook received (processing outcome in logs)"
// @Failure     401 {object} ErrorResponse "Missing or invalid HMAC signature"
// @Failure     500 {object} ErrorResponse "WHATSAPP_APP_SECRET not configured"
// @Router      /api/v1/webhooks/whatsapp [post]
func (h *edgeHandlers) WhatsAppWebhook(c *gin.Context) {
	// WhatsApp uses SHA256 HMAC for signature verification
	// The signature is sent in the 'X-Hub-Signature-256' header
	signature := c.GetHeader("X-Hub-Signature-256")
	appSecret := getEnv("WHATSAPP_APP_SECRET", "")

	// Fail-closed: refuse to accept webhooks if the signing secret is
	// not configured. Previously an unset secret would silently skip
	// signature verification, allowing anyone to inject orders.
	if appSecret == "" {
		h.logger.Error("WHATSAPP_APP_SECRET not configured; refusing webhook")
		c.JSON(http.StatusInternalServerError, gin.H{"error": "webhook signing not configured"})
		return
	}

	if signature == "" {
		h.logger.Warn("Missing WhatsApp webhook signature")
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
		h.logger.Warn("Invalid WhatsApp webhook signature", zap.String("signature", signature))
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid signature"})
		return
	}

	// Parse WhatsApp message into structured order
	parsedOrder, err := whatsapp.ParseWebhook(body)
	if err != nil {
		h.logger.Error("Failed to parse WhatsApp webhook", zap.Error(err))
		c.Status(http.StatusOK) // Still 200 to prevent retries
		return
	}
	if parsedOrder == nil || len(parsedOrder.Items) == 0 {
		h.logger.Info("WhatsApp webhook had no order items")
		c.Status(http.StatusOK)
		return
	}

	// This is a signature-only public route: Meta authenticates via the HMAC
	// signature verified above, not a Keycloak JWT. Core still requires an
	// authenticated, tenant-scoped Bearer token, so mint a client-credentials
	// service token and scope the order to the configured WhatsApp tenant.
	ctx, cancel := context.WithTimeout(c.Request.Context(), 15*time.Second)
	defer cancel()

	if h.whatsAppTenantID == "" || h.tokenProvider == nil {
		h.logger.Error("WhatsApp intake not configured (tenant/service token); dropping order")
		c.Status(http.StatusOK) // 200 to avoid Meta's 3-day retry storm; failure is in logs
		return
	}
	tenantStr := h.whatsAppTenantID
	token, err := h.tokenProvider.Token(ctx)
	if err != nil {
		h.logger.Error("Failed to acquire service token for WhatsApp order", zap.Error(err))
		c.Status(http.StatusOK) // 200 to avoid Meta's 3-day retry storm; failure is in logs
		return
	}

	// Resolve product queries to UUIDs via Core API search.
	// Require a confident match: either a single search hit, or an
	// exact (case-insensitive) name match within a multi-hit result.
	// Ambiguous queries are skipped with a warning instead of silently
	// binding to products[0] — which previously let "bread" pick an
	// arbitrary bread-adjacent SKU.
	var orderItems []core.OrderItemRequest
	for _, item := range parsedOrder.Items {
		products, err := h.coreClient.SearchProducts(ctx, token, tenantStr, item.ProductQuery)
		if err != nil {
			h.logger.Warn("Product search failed", zap.String("query", item.ProductQuery), zap.Error(err))
			continue
		}
		if len(products) == 0 {
			h.logger.Warn("No product found for query", zap.String("query", item.ProductQuery))
			continue
		}

		var matched *core.ProductSearchResult
		if len(products) == 1 {
			matched = &products[0]
		} else {
			// Prefer an exact case-insensitive title equality.
			query := strings.TrimSpace(item.ProductQuery)
			for i := range products {
				if strings.EqualFold(strings.TrimSpace(products[i].Title), query) {
					matched = &products[i]
					break
				}
			}
		}
		if matched == nil {
			h.logger.Warn("Ambiguous product query; skipping",
				zap.String("query", item.ProductQuery),
				zap.Int("candidates", len(products)))
			continue
		}

		orderItems = append(orderItems, core.OrderItemRequest{
			ProductID: matched.ID,
			Quantity:  item.Quantity,
		})
	}

	if len(orderItems) == 0 {
		h.logger.Warn("No products resolved from WhatsApp order", zap.String("phone", parsedOrder.Phone))
		c.Status(http.StatusOK)
		return
	}

	// Create order via Core API
	if h.defaultShopID == "" {
		h.logger.Error("WHATSAPP_DEFAULT_SHOP_ID not configured, cannot create order")
		c.Status(http.StatusOK)
		return
	}

	createReq := &core.CreateOrderRequest{
		ShopID:        h.defaultShopID,
		CustomerPhone: parsedOrder.Phone,
		Notes:         "WhatsApp order: " + parsedOrder.Raw,
		Items:         orderItems,
	}

	orderResp, err := h.coreClient.CreateOrder(ctx, token, tenantStr, createReq)
	if err != nil {
		h.logger.Error("Failed to create order from WhatsApp", zap.Error(err))
		c.Status(http.StatusOK) // Still 200 to prevent retries
		return
	}

	h.logger.Info("WhatsApp order created",
		zap.String("orderNumber", orderResp.OrderNumber),
		zap.String("phone", parsedOrder.Phone),
		zap.Int("items", len(orderItems)))
	c.Status(http.StatusOK)
}
