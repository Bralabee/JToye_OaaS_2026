package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jtoye/edge/internal/core"
	"go.uber.org/zap"
)

// stubTokenProvider is a test double for auth.ServiceTokenProvider so the
// WhatsApp handler can be exercised without a live Keycloak.
type stubTokenProvider struct {
	token string
	err   error
}

func (s stubTokenProvider) Token(ctx context.Context) (string, error) {
	return s.token, s.err
}

func newContext(method, path string, body []byte) (*gin.Context, *httptest.ResponseRecorder) {
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	var r io.Reader
	if body != nil {
		r = bytes.NewReader(body)
	}
	c.Request = httptest.NewRequest(method, path, r)
	c.Request.Header.Set("Content-Type", "application/json")
	return c, w
}

func hmacSign(body []byte, secret string) string {
	h := hmac.New(sha256.New, []byte(secret))
	h.Write(body)
	return "sha256=" + hex.EncodeToString(h.Sum(nil))
}

// --- Health uptime nit ---

func TestUptimeSeconds_ZeroWhenUnset(t *testing.T) {
	if got := uptimeSeconds(time.Time{}); got != 0 {
		t.Errorf("expected 0 uptime for zero startedAt, got %d", got)
	}
}

func TestHealth_ReportsRealUptime(t *testing.T) {
	h := &edgeHandlers{startedAt: time.Now().Add(-5 * time.Second)}
	c, w := newContext("GET", "/health", nil)
	h.Health(c)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
	var resp map[string]interface{}
	json.NewDecoder(w.Body).Decode(&resp)
	uptime, ok := resp["uptime"].(float64) // JSON numbers decode to float64
	if !ok {
		t.Fatalf("uptime missing or wrong type: %v", resp["uptime"])
	}
	if uptime < 4 {
		t.Errorf("expected uptime >= 4s, got %v", uptime)
	}
}

// --- SyncBatch panic-safe tenant guard ---

func TestSyncBatch_NonStringTenantReturns400(t *testing.T) {
	logger, _ := zap.NewProduction()
	h := &edgeHandlers{logger: logger}
	c, w := newContext("POST", "/api/v1/sync/batch", []byte(`{"items":[]}`))
	c.Request.Header.Set("Authorization", "Bearer tok")
	c.Set("tenant_id", 12345) // non-string: must not panic

	h.SyncBatch(c)

	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400 for non-string tenant, got %d", w.Code)
	}
}

func TestSyncBatch_MissingTenantReturns400(t *testing.T) {
	logger, _ := zap.NewProduction()
	h := &edgeHandlers{logger: logger}
	c, w := newContext("POST", "/api/v1/sync/batch", []byte(`{"items":[]}`))
	c.Request.Header.Set("Authorization", "Bearer tok")
	// tenant_id not set at all

	h.SyncBatch(c)

	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400 for missing tenant, got %d", w.Code)
	}
}

func TestSyncBatch_MissingTokenReturns401(t *testing.T) {
	logger, _ := zap.NewProduction()
	h := &edgeHandlers{logger: logger}
	c, w := newContext("POST", "/api/v1/sync/batch", []byte(`{"items":[]}`))
	c.Set("tenant_id", "tenant-1")
	// no Authorization header

	h.SyncBatch(c)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 for missing bearer token, got %d", w.Code)
	}
}

// --- WhatsApp HMAC-only public route ---

const whatsAppPayload = `{"entry":[{"changes":[{"value":{"messages":[{"from":"447700900000","type":"text","text":{"body":"2x Cake\n1x Bread"}}]}}]}]}`

// TestWhatsAppWebhook_UnsetSecretReturns503WithRetryAfter covers issue #450
// item 3 (QA F-L3). An unset WHATSAPP_APP_SECRET is a KNOWN, operator-fixable
// state, not an internal error, so it must be 503 + Retry-After rather than the
// 500 this previously returned — and explicitly not 501, which would advertise
// the endpoint as unimplemented.
//
// The status is the only thing that changes. Failing CLOSED is the security
// property and is asserted here directly, not assumed: the test wires a Core
// stub that counts requests and asserts ZERO, so a future edit that moved the
// secret check below any processing would fail this test rather than quietly
// turn a status-code fix into an open door.
func TestWhatsAppWebhook_UnsetSecretReturns503WithRetryAfter(t *testing.T) {
	t.Setenv("WHATSAPP_APP_SECRET", "")

	coreCalls := 0
	coreServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		coreCalls++
		w.WriteHeader(http.StatusOK)
	}))
	defer coreServer.Close()

	logger, _ := zap.NewProduction()
	h := &edgeHandlers{
		logger:           logger,
		coreClient:       core.NewClient(coreServer.URL, logger),
		tokenProvider:    stubTokenProvider{token: "svc-token"},
		whatsAppTenantID: "11111111-1111-1111-1111-111111111111",
		defaultShopID:    "22222222-2222-2222-2222-222222222222",
	}
	c, w := newContext("POST", "/api/v1/webhooks/whatsapp", []byte(whatsAppPayload))

	h.WhatsAppWebhook(c)

	if w.Code != http.StatusServiceUnavailable {
		t.Errorf("unset secret: expected 503 (known unconfigured state), got %d", w.Code)
	}
	if w.Code == http.StatusNotImplemented {
		t.Errorf("unset secret: 501 advertises the endpoint as unimplemented, which is false")
	}

	// Retry-After must be present AND a usable positive delta-seconds value.
	// Asserting only presence would pass on an empty header, which tells a
	// client nothing.
	retryAfter := w.Header().Get("Retry-After")
	if retryAfter == "" {
		t.Fatalf("unset secret: Retry-After header absent; headers = %v", w.Header())
	}
	secs, err := strconv.Atoi(retryAfter)
	if err != nil {
		t.Fatalf("unset secret: Retry-After %q is not delta-seconds: %v", retryAfter, err)
	}
	if secs <= 0 {
		t.Errorf("unset secret: Retry-After must be a positive number of seconds, got %d", secs)
	}

	// STILL FAILS CLOSED — the property that must survive the status change.
	if coreCalls != 0 {
		t.Errorf("unset secret: webhook reached Core %d time(s); it must be refused before any downstream call", coreCalls)
	}
	var body ErrorResponse
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("unset secret: response body is not the documented ErrorResponse shape: %v (body=%s)", err, w.Body.String())
	}
	if body.Error == "" {
		t.Errorf("unset secret: response carries no error message for the operator; body = %s", w.Body.String())
	}
}

func TestWhatsAppWebhook_MissingSignatureReturns401(t *testing.T) {
	t.Setenv("WHATSAPP_APP_SECRET", "shhh")
	logger, _ := zap.NewProduction()
	h := &edgeHandlers{logger: logger}
	c, w := newContext("POST", "/api/v1/webhooks/whatsapp", []byte(whatsAppPayload))
	// no X-Hub-Signature-256 header

	h.WhatsAppWebhook(c)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 for missing signature, got %d", w.Code)
	}
}

func TestWhatsAppWebhook_InvalidSignatureReturns401(t *testing.T) {
	t.Setenv("WHATSAPP_APP_SECRET", "shhh")
	logger, _ := zap.NewProduction()
	h := &edgeHandlers{logger: logger}
	c, w := newContext("POST", "/api/v1/webhooks/whatsapp", []byte(whatsAppPayload))
	c.Request.Header.Set("X-Hub-Signature-256", "sha256=deadbeef")

	h.WhatsAppWebhook(c)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 for invalid signature, got %d", w.Code)
	}
}

func TestWhatsAppWebhook_ValidSignatureButUnconfiguredReturns200(t *testing.T) {
	// Valid HMAC but no tenant / token provider configured: must ACK with 200
	// (not a Meta-retry-triggering error) while logging the misconfiguration.
	secret := "shhh"
	t.Setenv("WHATSAPP_APP_SECRET", secret)
	logger, _ := zap.NewProduction()
	h := &edgeHandlers{logger: logger} // tokenProvider nil, whatsAppTenantID ""
	body := []byte(whatsAppPayload)
	c, w := newContext("POST", "/api/v1/webhooks/whatsapp", body)
	c.Request.Header.Set("X-Hub-Signature-256", hmacSign(body, secret))

	h.WhatsAppWebhook(c)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200 for valid-but-unconfigured webhook, got %d", w.Code)
	}
}

func TestWhatsAppWebhook_HmacOnlyCreatesOrderWithServiceToken(t *testing.T) {
	secret := "shhh"
	t.Setenv("WHATSAPP_APP_SECRET", secret)

	// Mock Core: captures the forwarded auth so we can prove the edge minted
	// and forwarded its own service token + configured tenant (no caller JWT).
	var gotAuth, gotTenant string
	coreServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		gotTenant = r.Header.Get("X-Tenant-Id")
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.Contains(r.URL.Path, "/products/search"):
			json.NewEncoder(w).Encode([]core.ProductSearchResult{{ID: "prod-1", Title: "Match"}})
		case strings.HasSuffix(r.URL.Path, "/orders"):
			json.NewEncoder(w).Encode(core.CreateOrderResponse{ID: "o1", OrderNumber: "ORD-1", Status: "PENDING"})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer coreServer.Close()

	logger, _ := zap.NewProduction()
	h := &edgeHandlers{
		coreClient:       core.NewClient(coreServer.URL, logger),
		logger:           logger,
		defaultShopID:    "shop-1",
		tokenProvider:    stubTokenProvider{token: "service-token-xyz"},
		whatsAppTenantID: "tenant-abc",
	}

	body := []byte(whatsAppPayload)
	c, w := newContext("POST", "/api/v1/webhooks/whatsapp", body)
	// Deliberately NO Authorization header — proves the route is public and
	// authenticated by the HMAC signature alone.
	c.Request.Header.Set("X-Hub-Signature-256", hmacSign(body, secret))

	h.WhatsAppWebhook(c)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
	if gotAuth != "Bearer service-token-xyz" {
		t.Errorf("Core did not receive the edge service token; got Authorization=%q", gotAuth)
	}
	if gotTenant != "tenant-abc" {
		t.Errorf("Core did not receive the configured WhatsApp tenant; got X-Tenant-Id=%q", gotTenant)
	}
}
