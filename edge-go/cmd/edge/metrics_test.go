package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

// TestMetricsEndpoint_ExposesHTTPRequestsTotal proves that a request flowing
// through prometheusMiddleware is recorded and surfaced by the /metrics handler
// as Prometheus exposition text — the endpoint the re-enabled edge-go scrape
// job in prometheus.yml depends on.
func TestMetricsEndpoint_ExposesHTTPRequestsTotal(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(prometheusMiddleware())
	r.GET("/ping", func(c *gin.Context) {
		c.String(http.StatusOK, "pong")
	})
	r.GET("/metrics", metricsHandler())

	// Drive a request through the middleware so a series is recorded.
	pingResp := httptest.NewRecorder()
	r.ServeHTTP(pingResp, httptest.NewRequest(http.MethodGet, "/ping", nil))
	if pingResp.Code != http.StatusOK {
		t.Fatalf("expected 200 from /ping, got %d", pingResp.Code)
	}

	// Scrape /metrics and assert the counter series is exposed.
	metricsResp := httptest.NewRecorder()
	r.ServeHTTP(metricsResp, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	if metricsResp.Code != http.StatusOK {
		t.Fatalf("expected 200 from /metrics, got %d", metricsResp.Code)
	}

	body := metricsResp.Body.String()
	if !strings.Contains(body, "http_requests_total") {
		t.Errorf("expected /metrics body to contain http_requests_total, got:\n%s", body)
	}
	// The route label must be the matched TEMPLATE (never a raw ID-bearing path).
	if !strings.Contains(body, `route="/ping"`) {
		t.Errorf("expected a series labelled route=\"/ping\", got:\n%s", body)
	}
}

// TestMetricsMiddleware_UnmatchedRouteIsBounded proves an unmatched path does
// not leak into the route label as a raw path — it collapses to "unmatched",
// bounding cardinality.
func TestMetricsMiddleware_UnmatchedRouteIsBounded(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(prometheusMiddleware())
	r.GET("/metrics", metricsHandler())

	// A path with no registered route + an ID-shaped segment.
	unmatchedResp := httptest.NewRecorder()
	r.ServeHTTP(unmatchedResp, httptest.NewRequest(http.MethodGet, "/api/v1/orders/abc-123-secret", nil))
	if unmatchedResp.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for unmatched route, got %d", unmatchedResp.Code)
	}

	metricsResp := httptest.NewRecorder()
	r.ServeHTTP(metricsResp, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	body := metricsResp.Body.String()
	if !strings.Contains(body, `route="unmatched"`) {
		t.Errorf("expected unmatched route to collapse to route=\"unmatched\", got:\n%s", body)
	}
	if strings.Contains(body, "abc-123-secret") {
		t.Errorf("raw ID-bearing path leaked into metrics labels:\n%s", body)
	}
}
