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
	"reflect"
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

// --- 37-41: the sync contract reaches the integrator through the edge ------

// coreSyncPartial is core's 37-40 answer for a batch with one item written
// and one refused, exactly as core-java serialises it.
const coreSyncPartial = `{"status":"PARTIAL","processedCount":1,"results":[` +
	`{"index":0,"type":"product","key":"YAM-5KG","outcome":"CREATED","id":"6f1c2d3e-0000-4000-8000-000000000001"},` +
	`{"index":1,"type":"product","key":"PLANTAIN-1","outcome":"REJECTED","code":"SHOP_REQUIRED","message":"shopId is required on every product item"}]}`

const coreShopDenied403 = `{"type":"https://jtoye.uk/errors/shop-access-denied","title":"Forbidden","status":403,` +
	`"detail":"You have no access to this shop","instance":"/api/v1/sync/batch","shopId":"8f14e45f-ceea-467f-a0e6-7c3d1c8a1b2e"}`

// syncThroughEdge drives the real SyncBatch handler and the real core client
// against a stub core at coreURL.
func syncThroughEdge(t *testing.T, coreURL string) *httptest.ResponseRecorder {
	t.Helper()
	logger, _ := zap.NewProduction()
	h := &edgeHandlers{coreClient: core.NewClient(coreURL, logger), logger: logger}
	c, w := newContext("POST", "/api/v1/sync/batch", []byte(`{"items":[{"type":"product","sku":"YAM-5KG"},{"type":"product","sku":"PLANTAIN-1"}]}`))
	c.Request.Header.Set("Authorization", "Bearer tok")
	c.Set("tenant_id", "tenant-1")
	h.SyncBatch(c)
	return w
}

func stubCore(t *testing.T, status int, contentType, body string, headers map[string]string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		for k, v := range headers {
			w.Header().Set(k, v)
		}
		w.Header().Set("Content-Type", contentType)
		w.WriteHeader(status)
		w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	return srv
}

// RWO-017/037 through the edge: core's status, processed count and per-item
// results reach the integrator, with core's own HTTP status.
func TestSyncBatch_PassesCoresPerItemResultsThrough(t *testing.T) {
	srv := stubCore(t, http.StatusOK, "application/json", coreSyncPartial, nil)
	w := syncThroughEdge(t, srv.URL)

	if w.Code != http.StatusOK {
		t.Errorf("core answered 200; the edge answered %d (body %s)", w.Code, w.Body.String())
	}
	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("edge body is not JSON: %v (%s)", err, w.Body.String())
	}
	var core map[string]any
	_ = json.Unmarshal([]byte(coreSyncPartial), &core)

	if got["status"] != "PARTIAL" {
		t.Errorf("status: got %v, want PARTIAL — an integrator told anything else would believe refused items landed", got["status"])
	}
	if got["processed_count"] != float64(1) {
		t.Errorf("processed_count: got %v, want 1", got["processed_count"])
	}
	if !reflect.DeepEqual(got["results"], core["results"]) {
		t.Errorf("per-item results were not passed through.\n edge: %v\n core: %v", got["results"], core["results"])
	}
}

// A core 4xx problem is relayed verbatim: same status, same content type,
// same bytes (extension members included), not an untyped 502.
func TestSyncBatch_RelaysCoreProblemVerbatim(t *testing.T) {
	srv := stubCore(t, http.StatusForbidden, "application/problem+json", coreShopDenied403, nil)
	w := syncThroughEdge(t, srv.URL)

	if w.Code != http.StatusForbidden {
		t.Errorf("core refused 403; the edge answered %d (body %s)", w.Code, w.Body.String())
	}
	if ct := w.Header().Get("Content-Type"); ct != "application/problem+json" {
		t.Errorf("content type: got %q, want core's application/problem+json", ct)
	}
	if !bytes.Equal(w.Body.Bytes(), []byte(coreShopDenied403)) {
		t.Errorf("body is not core's bytes.\n edge: %s\n core: %s", w.Body.String(), coreShopDenied403)
	}
}

// A core 429 keeps its Retry-After, or the integrator is told to back off
// without being told for how long.
func TestSyncBatch_RelaysCoreRateLimitWithRetryAfter(t *testing.T) {
	body := `{"type":"https://jtoye.uk/errors/rate-limited","title":"Too Many Requests","status":429,"code":"RATE_LIMITED","retryAfterSeconds":7}`
	srv := stubCore(t, http.StatusTooManyRequests, "application/problem+json", body, map[string]string{"Retry-After": "7"})
	w := syncThroughEdge(t, srv.URL)

	if w.Code != http.StatusTooManyRequests {
		t.Errorf("core refused 429; the edge answered %d", w.Code)
	}
	if ra := w.Header().Get("Retry-After"); ra != "7" {
		t.Errorf("Retry-After: got %q, want core's 7", ra)
	}
	if !bytes.Equal(w.Body.Bytes(), []byte(body)) {
		t.Errorf("body is not core's bytes.\n edge: %s\n core: %s", w.Body.String(), body)
	}
}

// Unchanged: transport failure and core 5xx stay the edge's generic 502, and
// a 5xx body never leaves the edge (T-37-96).
func TestSyncBatch_CoreFailuresStay502(t *testing.T) {
	down := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	downURL := down.URL
	down.Close()

	cases := []struct {
		name string
		url  string
	}{
		{"core unreachable", downURL},
		{"core 500 problem", stubCore(t, http.StatusInternalServerError, "application/problem+json",
			`{"type":"https://jtoye.uk/errors/internal","detail":"secret internals"}`, nil).URL},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			w := syncThroughEdge(t, tc.url)
			if w.Code != http.StatusBadGateway {
				t.Errorf("got %d, want 502", w.Code)
			}
			if strings.Contains(w.Body.String(), "secret internals") {
				t.Errorf("a core 5xx body leaked to the integrator: %s", w.Body.String())
			}
			var e map[string]string
			if err := json.Unmarshal(w.Body.Bytes(), &e); err != nil || e["error"] != "failed to sync with core API" {
				t.Errorf("body: got %s, want the existing {\"error\":\"failed to sync with core API\"}", w.Body.String())
			}
		})
	}
}
