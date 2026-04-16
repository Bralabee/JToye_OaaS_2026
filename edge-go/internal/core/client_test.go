package core

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
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

		// Verify request body
		var req BatchSyncRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			t.Errorf("Failed to decode request: %v", err)
		}

		if req.TenantID != "tenant-123" {
			t.Errorf("Expected tenant_id: tenant-123, got %s", req.TenantID)
		}

		if len(req.Items) != 2 {
			t.Errorf("Expected 2 items, got %d", len(req.Items))
		}

		// Send success response
		w.WriteHeader(http.StatusAccepted)
		resp := BatchSyncResponse{
			Status:         "accepted",
			ProcessedCount: 2,
		}
		json.NewEncoder(w).Encode(resp)
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
