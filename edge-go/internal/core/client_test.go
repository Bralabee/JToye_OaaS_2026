package core

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sort"
	"sync/atomic"
	"testing"
	"time"

	"go.uber.org/zap"
)

func TestClient_HealthCheck_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/health" {
			t.Errorf("Expected /health path, got %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("OK"))
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	ctx := context.Background()
	err := client.HealthCheck(ctx)

	if err != nil {
		t.Errorf("Expected no error, got: %v", err)
	}
}

func TestClient_HealthCheck_Failure(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	ctx := context.Background()
	err := client.HealthCheck(ctx)

	if err == nil {
		t.Error("Expected error, got nil")
	}
}

func TestClient_HealthCheck_Timeout(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Simulate slow response
		time.Sleep(2 * time.Second)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()

	err := client.HealthCheck(ctx)

	if err == nil {
		t.Error("Expected timeout error, got nil")
	}
}

func TestClient_SyncBatch_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Verify request method
		if r.Method != "POST" {
			t.Errorf("Expected POST, got %s", r.Method)
		}

		// Verify headers
		if r.Header.Get("Content-Type") != "application/json" {
			t.Errorf("Expected Content-Type: application/json")
		}

		authHeader := r.Header.Get("Authorization")
		if authHeader != "Bearer test-token" {
			t.Errorf("Expected Authorization: Bearer test-token, got %s", authHeader)
		}

		tenantHeader := r.Header.Get("X-Tenant-Id")
		if tenantHeader != "tenant-123" {
			t.Errorf("Expected X-Tenant-Id: tenant-123, got %s", tenantHeader)
		}

		// Verify request body against core's LITERAL wire names, not against
		// BatchSyncRequest. Decoding into the client's own struct made this
		// assertion self-consistent: both sides moved together, so it stayed
		// green for months while the edge sent `tenant_id` and core-java read
		// `tenantId` (issue #337). A raw map cannot agree by construction.
		var raw map[string]json.RawMessage
		if err := json.NewDecoder(r.Body).Decode(&raw); err != nil {
			t.Errorf("Failed to decode request: %v", err)
		}

		var tenant string
		if err := json.Unmarshal(raw["tenantId"], &tenant); err != nil {
			t.Errorf("body has no `tenantId` (core-java's BatchSyncRequest field); got keys %v", rawKeys(raw))
		} else if tenant != "tenant-123" {
			t.Errorf("Expected tenantId: tenant-123, got %s", tenant)
		}

		var items []map[string]any
		if err := json.Unmarshal(raw["items"], &items); err != nil {
			t.Errorf("body has no `items`; got keys %v", rawKeys(raw))
		} else if len(items) != 2 {
			t.Errorf("Expected 2 items, got %d", len(items))
		}

		// Respond exactly as core-java does. Hand-written JSON on purpose:
		// core serialises processedCount with default Jackson naming, and the
		// edge publishes processed_count, so encoding the edge's own struct
		// here would once again make the round-trip agree with itself.
		w.WriteHeader(http.StatusAccepted)
		w.Write([]byte(`{"status":"accepted","processedCount":2}`))
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	items := []map[string]interface{}{
		{"id": "1", "name": "Product 1"},
		{"id": "2", "name": "Product 2"},
	}

	ctx := context.Background()
	resp, err := client.SyncBatch(ctx, "test-token", "tenant-123", items)

	if err != nil {
		t.Errorf("Expected no error, got: %v", err)
	}

	if resp.Status != "accepted" {
		t.Errorf("Expected status 'accepted', got %s", resp.Status)
	}

	if resp.ProcessedCount != 2 {
		t.Errorf("Expected processed_count 2, got %d", resp.ProcessedCount)
	}
}

func TestClient_SyncBatch_ServerError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("Internal server error"))
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	items := []map[string]interface{}{
		{"id": "1"},
	}

	ctx := context.Background()
	_, err := client.SyncBatch(ctx, "test-token", "tenant-123", items)

	if err == nil {
		t.Error("Expected error for server error, got nil")
	}
}

func TestClient_SyncBatch_CircuitBreaker(t *testing.T) {
	failCount := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		failCount++
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	items := []map[string]interface{}{{"id": "1"}}
	ctx := context.Background()

	// Make multiple requests to trip the circuit breaker
	for i := 0; i < 5; i++ {
		client.SyncBatch(ctx, "test-token", "tenant-123", items)
		time.Sleep(100 * time.Millisecond)
	}

	// Circuit breaker should be open and preventing requests
	if failCount > 10 {
		t.Logf("Circuit breaker working: prevented excessive requests (failCount: %d)", failCount)
	}
}

func TestClient_SearchProducts_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("Expected GET, got %s", r.Method)
		}
		if !testing.Short() {
			if r.Header.Get("Authorization") != "Bearer test-token" {
				t.Errorf("Expected Authorization header, got %s", r.Header.Get("Authorization"))
			}
			if r.Header.Get("X-Tenant-Id") != "tenant-123" {
				t.Errorf("Expected X-Tenant-Id: tenant-123, got %s", r.Header.Get("X-Tenant-Id"))
			}
		}
		q := r.URL.Query().Get("q")
		if q != "chocolate cake" {
			t.Errorf("Expected query 'chocolate cake', got %q", q)
		}

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode([]ProductSearchResult{
			{ID: "prod-1", SKU: "CAKE-001", Title: "Chocolate Cake"},
		})
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	ctx := context.Background()
	results, err := client.SearchProducts(ctx, "test-token", "tenant-123", "chocolate cake")
	if err != nil {
		t.Errorf("Expected no error, got: %v", err)
	}
	if len(results) != 1 {
		t.Fatalf("Expected 1 result, got %d", len(results))
	}
	if results[0].Title != "Chocolate Cake" {
		t.Errorf("Expected 'Chocolate Cake', got %q", results[0].Title)
	}
}

func TestClient_SearchProducts_NotFound(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode([]ProductSearchResult{})
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	ctx := context.Background()
	results, err := client.SearchProducts(ctx, "test-token", "tenant-123", "nonexistent")
	if err != nil {
		t.Errorf("Expected no error for empty results, got: %v", err)
	}
	if len(results) != 0 {
		t.Errorf("Expected 0 results, got %d", len(results))
	}
}

func TestClient_SearchProducts_ServerError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("internal error"))
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	ctx := context.Background()
	_, err := client.SearchProducts(ctx, "test-token", "tenant-123", "cake")
	if err == nil {
		t.Error("Expected error for server error, got nil")
	}
}

func TestClient_CreateOrder_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("Expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/v1/orders" {
			t.Errorf("Expected /api/v1/orders, got %s", r.URL.Path)
		}
		if r.Header.Get("Content-Type") != "application/json" {
			t.Errorf("Expected Content-Type application/json")
		}

		var req CreateOrderRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			t.Errorf("Failed to decode request: %v", err)
		}
		if req.ShopID != "shop-1" {
			t.Errorf("Expected shopId 'shop-1', got %q", req.ShopID)
		}
		if len(req.Items) != 1 {
			t.Errorf("Expected 1 item, got %d", len(req.Items))
		}

		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(CreateOrderResponse{
			ID:          "order-1",
			OrderNumber: "ORD-001",
			Status:      "DRAFT",
		})
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	ctx := context.Background()
	resp, err := client.CreateOrder(ctx, "test-token", "tenant-123", &CreateOrderRequest{
		ShopID:        "shop-1",
		CustomerPhone: "+447700900000",
		Notes:         "WhatsApp order",
		Items:         []OrderItemRequest{{ProductID: "prod-1", Quantity: 2}},
	})
	if err != nil {
		t.Errorf("Expected no error, got: %v", err)
	}
	if resp.OrderNumber != "ORD-001" {
		t.Errorf("Expected order number 'ORD-001', got %q", resp.OrderNumber)
	}
}

func TestClient_CreateOrder_ServerError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("db error"))
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	ctx := context.Background()
	_, err := client.CreateOrder(ctx, "test-token", "tenant-123", &CreateOrderRequest{
		ShopID: "shop-1",
		Items:  []OrderItemRequest{{ProductID: "prod-1", Quantity: 1}},
	})
	if err == nil {
		t.Error("Expected error for server error, got nil")
	}
}

func TestClient_ForwardWebhook_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("Expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/v1/webhooks/stripe" {
			t.Errorf("Expected /api/v1/webhooks/stripe, got %s", r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer test-token" {
			t.Errorf("Expected Authorization header")
		}
		if r.Header.Get("X-Tenant-Id") != "tenant-123" {
			t.Errorf("Expected X-Tenant-Id header")
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	ctx := context.Background()
	err := client.ForwardWebhook(ctx, "test-token", "tenant-123", "stripe", []byte(`{"type":"payment_intent.succeeded"}`))
	if err != nil {
		t.Errorf("Expected no error, got: %v", err)
	}
}

func TestClient_ForwardWebhook_ServerError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("internal error"))
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	ctx := context.Background()
	err := client.ForwardWebhook(ctx, "test-token", "tenant-123", "stripe", []byte(`{}`))
	if err == nil {
		t.Error("Expected error for server error, got nil")
	}
}

func TestClient_NewClient(t *testing.T) {
	logger, _ := zap.NewProduction()
	client := NewClient("http://localhost:9090", logger)

	if client == nil {
		t.Error("Expected client to be created, got nil")
	}

	if client.baseURL != "http://localhost:9090" {
		t.Errorf("Expected baseURL http://localhost:9090, got %s", client.baseURL)
	}

	if client.client == nil {
		t.Error("Expected HTTP client to be initialized")
	}

	if client.breaker == nil {
		t.Error("Expected circuit breaker to be initialized")
	}
}

// rawKeys lists the top-level keys of a decoded body so a failed wire-name
// assertion says what WAS sent, not just what was missing.
func rawKeys(m map[string]json.RawMessage) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// --- 37-41: core's sync contract passes through the client intact ---------

// coreSyncPartialBody is core's 37-40 wire shape for a batch where one item
// was written and one refused, hand-written as core-java serialises it (absent
// fields omitted, as Jackson NON_NULL does). Encoding the edge's own struct
// here would let the round-trip agree with itself (issue #337).
const coreSyncPartialBody = `{"status":"PARTIAL","processedCount":1,"results":[` +
	`{"index":0,"type":"product","key":"YAM-5KG","outcome":"CREATED","id":"6f1c2d3e-0000-4000-8000-000000000001"},` +
	`{"index":1,"type":"product","key":"PLANTAIN-1","outcome":"REJECTED","code":"SHOP_REQUIRED","message":"shopId is required on every product item"}]}`

// coreShopDeniedProblem is a core 403 refusal body, with an extension member
// (shopId) the edge has no struct for: it must survive byte for byte.
const coreShopDeniedProblem = `{"type":"https://jtoye.uk/errors/shop-access-denied","title":"Forbidden","status":403,` +
	`"detail":"You have no access to this shop","instance":"/api/v1/sync/batch","shopId":"8f14e45f-ceea-467f-a0e6-7c3d1c8a1b2e"}`

func TestClient_SyncBatch_PassesPerItemResultsThrough(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(coreSyncPartialBody))
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	resp, err := client.SyncBatch(context.Background(), "test-token", "tenant-123", []map[string]interface{}{{"type": "product"}, {"type": "product"}})
	if err != nil {
		t.Fatalf("a 200 PARTIAL from core is a success, got error: %v", err)
	}
	if resp.Status != "PARTIAL" || resp.ProcessedCount != 1 {
		t.Errorf("status/processed_count: got %q/%d, want PARTIAL/1", resp.Status, resp.ProcessedCount)
	}
	if resp.HTTPStatus != http.StatusOK {
		t.Errorf("core answered 200, the client reports %d", resp.HTTPStatus)
	}
	want := []SyncItemResult{
		{Index: 0, Type: "product", Key: "YAM-5KG", Outcome: "CREATED", ID: "6f1c2d3e-0000-4000-8000-000000000001"},
		{Index: 1, Type: "product", Key: "PLANTAIN-1", Outcome: "REJECTED", Code: "SHOP_REQUIRED", Message: "shopId is required on every product item"},
	}
	if !reflect.DeepEqual(resp.Results, want) {
		t.Errorf("per-item results were not passed through.\n got: %+v\nwant: %+v", resp.Results, want)
	}
}

func TestClient_SyncBatch_CoreProblemIsReturnedTyped(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusForbidden)
		w.Write([]byte(coreShopDeniedProblem))
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)

	_, err := client.SyncBatch(context.Background(), "test-token", "tenant-123", []map[string]interface{}{{"type": "product"}})
	var pe *CoreProblemError
	if !errors.As(err, &pe) {
		t.Fatalf("a core 403 application/problem+json must come back as *CoreProblemError, got %T: %v", err, err)
	}
	if pe.Status != http.StatusForbidden {
		t.Errorf("status: got %d, want 403", pe.Status)
	}
	if pe.ContentType != "application/problem+json" {
		t.Errorf("content type: got %q, want application/problem+json", pe.ContentType)
	}
	if !bytes.Equal(pe.Body, []byte(coreShopDeniedProblem)) {
		t.Errorf("body is not core's bytes.\n got: %s\nwant: %s", pe.Body, coreShopDeniedProblem)
	}
}

// A 4xx that is NOT problem+json is not a typed refusal core stands behind,
// and a 5xx problem stays internal (T-37-96): both remain generic errors.
func TestClient_SyncBatch_OnlyClientProblemsAreTyped(t *testing.T) {
	cases := []struct {
		name        string
		status      int
		contentType string
	}{
		{"4xx text/plain", http.StatusNotFound, "text/plain"},
		{"5xx problem+json", http.StatusInternalServerError, "application/problem+json"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", tc.contentType)
				w.WriteHeader(tc.status)
				w.Write([]byte(`{"type":"https://jtoye.uk/errors/internal","detail":"stack trace here"}`))
			}))
			defer server.Close()

			logger, _ := zap.NewProduction()
			client := NewClient(server.URL, logger)
			_, err := client.SyncBatch(context.Background(), "test-token", "tenant-123", []map[string]interface{}{{"type": "product"}})
			if err == nil {
				t.Fatal("expected an error")
			}
			var pe *CoreProblemError
			if errors.As(err, &pe) {
				t.Errorf("%s must not be relayed as a typed core problem, got %+v", tc.name, pe)
			}
		})
	}
}

// A refusal core answered on purpose is not a core outage: a run of 4xx
// problems must not open the breaker and shed every tenant's sync for 60 s.
func TestClient_SyncBatch_ProblemRefusalsDoNotOpenTheBreaker(t *testing.T) {
	var hits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if hits.Add(1) <= 15 {
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(http.StatusForbidden)
			w.Write([]byte(coreShopDeniedProblem))
			return
		}
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(coreSyncPartialBody))
	}))
	defer server.Close()

	logger, _ := zap.NewProduction()
	client := NewClient(server.URL, logger)
	items := []map[string]interface{}{{"type": "product"}}
	for i := 0; i < 15; i++ {
		client.SyncBatch(context.Background(), "test-token", "tenant-123", items)
	}
	resp, err := client.SyncBatch(context.Background(), "test-token", "tenant-123", items)
	if err != nil {
		t.Fatalf("after 15 core 403 problems the next healthy batch failed (%v); core was reached %d times — refusals tripped the breaker", err, hits.Load())
	}
	if resp.Status != "PARTIAL" {
		t.Errorf("status: got %q, want PARTIAL", resp.Status)
	}
}
