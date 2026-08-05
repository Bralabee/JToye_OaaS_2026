package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

// These tests cover the ROUTING DECISION, which metrics_test.go cannot see:
// that file builds its own router and calls r.GET("/metrics", metricsHandler())
// directly, so it proves the handler works and says nothing about whether the
// application router registers it. Issue #550 is entirely about that decision —
// the handler was never in question — so a green metrics_test.go was, and would
// have stayed, compatible with an unauthenticated /metrics on the public port.

// routePaths returns the "METHOD PATH" set a gin engine has registered.
// Asserting on the engine's own registry (rather than on a request's status)
// is what makes "the route is ABSENT" distinguishable from "the route is
// present but this particular request happened to 404".
func routePaths(r *gin.Engine) []string {
	paths := make([]string, 0, len(r.Routes()))
	for _, ri := range r.Routes() {
		paths = append(paths, ri.Method+" "+ri.Path)
	}
	return paths
}

func hasRoute(r *gin.Engine, methodAndPath string) bool {
	for _, p := range routePaths(r) {
		if p == methodAndPath {
			return true
		}
	}
	return false
}

// TestRegisterAppMetricsRoute_HonoursManagementPort is the #550 acceptance
// criterion expressed as a unit test: with a management port configured,
// /metrics must NOT be on the application router; with none, it must be.
func TestRegisterAppMetricsRoute_HonoursManagementPort(t *testing.T) {
	t.Parallel()
	gin.SetMode(gin.TestMode)

	cases := []struct {
		name           string
		managementPort string
		wantOnAppRoute bool
	}{
		{
			name:           "unset keeps /metrics on the application router (pre-#442 topology, still used by k8s)",
			managementPort: "",
			wantOnAppRoute: true,
		},
		{
			name:           "set removes /metrics from the application router (compose topology after #550)",
			managementPort: "9101",
			wantOnAppRoute: false,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			r := gin.New()
			// A second route so an empty router cannot masquerade as a pass:
			// if registration silently wired nothing at all, /health would be
			// missing too and the "present" case would fail loudly.
			r.GET("/health", func(c *gin.Context) { c.Status(http.StatusOK) })

			registered := registerAppMetricsRoute(r, tc.managementPort)

			if registered != tc.wantOnAppRoute {
				t.Errorf("case %q: registerAppMetricsRoute returned %v, want %v",
					tc.name, registered, tc.wantOnAppRoute)
			}
			if got := hasRoute(r, "GET /metrics"); got != tc.wantOnAppRoute {
				t.Errorf("case %q: EDGE_MANAGEMENT_PORT=%q -> application router has GET /metrics = %v, want %v (routes: %v)",
					tc.name, tc.managementPort, got, tc.wantOnAppRoute, routePaths(r))
			}
			// /health and /ready must stay on the application port in BOTH
			// topologies — the kubelet probes target them there.
			if !hasRoute(r, "GET /health") {
				t.Errorf("case %q: GET /health disappeared from the application router (routes: %v)",
					tc.name, routePaths(r))
			}
		})
	}
}

// TestManagementRouter_ServesOnlyMetrics proves the second listener exposes the
// scrape endpoint and nothing else — moving /metrics off the public port is
// worth nothing if the management port quietly re-exposes the rest of the API.
func TestManagementRouter_ServesOnlyMetrics(t *testing.T) {
	t.Parallel()
	gin.SetMode(gin.TestMode)

	mgmt := newManagementRouter()

	if got := routePaths(mgmt); len(got) != 1 || got[0] != "GET /metrics" {
		t.Fatalf("management router must expose exactly [GET /metrics], got %v", got)
	}

	resp := httptest.NewRecorder()
	mgmt.ServeHTTP(resp, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	if resp.Code != http.StatusOK {
		t.Errorf("management /metrics: got %d, want 200", resp.Code)
	}
	// go_goroutines comes from the default registry's Go collector, so this
	// holds whether or not any request has flowed through the middleware yet.
	if !strings.Contains(resp.Body.String(), "go_goroutines") {
		t.Errorf("management /metrics did not serve Prometheus exposition text, got:\n%s", resp.Body.String())
	}

	for _, path := range []string{"/health", "/ready", "/api/v1/sync/batch"} {
		other := httptest.NewRecorder()
		mgmt.ServeHTTP(other, httptest.NewRequest(http.MethodGet, path, nil))
		if other.Code != http.StatusNotFound {
			t.Errorf("management router served %s with %d, want 404 — it must serve /metrics only", path, other.Code)
		}
	}
}

// TestResolveManagementPort covers the config layer: unset is legal, a real
// port passes through, and anything else is rejected at boot rather than
// surfacing later as a listener that never bound.
func TestResolveManagementPort(t *testing.T) {
	t.Parallel()

	cases := []struct {
		name    string
		raw     string
		want    string
		wantErr bool
	}{
		{name: "unset means serve on the application port", raw: "", want: ""},
		{name: "whitespace only is treated as unset, not as a port", raw: "  ", want: ""},
		{name: "a real port passes through", raw: "9101", want: "9101"},
		{name: "surrounding whitespace is trimmed, not fatal", raw: " 9101\n", want: "9101"},
		{name: "port 0 is rejected", raw: "0", wantErr: true},
		{name: "above the port range is rejected", raw: "65536", wantErr: true},
		{name: "non-numeric is rejected", raw: "metrics", wantErr: true},
		{name: "a host:port pair is rejected", raw: ":9101", wantErr: true},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			got, err := resolveManagementPort(tc.raw)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("case %q: resolveManagementPort(%q) returned nil error, want a rejection", tc.name, tc.raw)
				}
				if !strings.Contains(err.Error(), "EDGE_MANAGEMENT_PORT") {
					t.Errorf("case %q: error does not name the variable an operator must fix: %v", tc.name, err)
				}
				return
			}
			if err != nil {
				t.Fatalf("case %q: resolveManagementPort(%q) errored unexpectedly: %v", tc.name, tc.raw, err)
			}
			if got != tc.want {
				t.Errorf("case %q: resolveManagementPort(%q) = %q, want %q", tc.name, tc.raw, got, tc.want)
			}
		})
	}
}
