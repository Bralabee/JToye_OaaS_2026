package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

// Issue #808 (remainder): the pre-auth valve used to be ONE bucket shared by
// every caller, so an anonymous flood emptied it for every vendor's sync. It
// is now keyed on the client address, resolved by core's trusted-proxy rule.

// testValve is the production valve with an explicit rate/burst, trusting no
// proxy, on the real clock.
func testValve(rps, burst int) gin.HandlerFunc {
	return testValveTrusting("", rps, burst)
}

func testValveTrusting(cidrs string, rps, burst int) gin.HandlerFunc {
	resolver, err := newClientIPResolver(cidrs)
	if err != nil {
		panic(err)
	}
	return rateLimiter(tenantRateLimitConfig{RPS: rps, Burst: burst, MaxTenants: 1000}, resolver, realClock{})
}

// syncFromIP sends one authenticated batch as tenant from a socket peer.
func (h *tenantLimitHarness) syncFromIP(tenant, remoteAddr string) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/sync/batch", strings.NewReader(`{"items":[{"id":"1"}]}`))
	req.RemoteAddr = remoteAddr
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("X-Test-Tenant", tenant)
	h.r.ServeHTTP(w, req)
	return w
}

// floodAnonymously sends n anonymous requests to a public route from one
// socket peer (optionally with an X-Forwarded-For) and returns how many were
// refused 429.
func (h *tenantLimitHarness) floodAnonymously(n int, remoteAddr, xff string) int {
	refused := 0
	for i := 0; i < n; i++ {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/webhooks/whatsapp", strings.NewReader("{}"))
		req.RemoteAddr = remoteAddr
		if xff != "" {
			req.Header.Set("X-Forwarded-For", xff)
		}
		h.r.ServeHTTP(w, req)
		if w.Code == http.StatusTooManyRequests {
			refused++
		}
	}
	return refused
}

// TestPreAuthValve_AnonymousFloodFromOneIPDoesNotRefuseAnAuthenticatedTenant is
// the issue's own acceptance: flood the pre-auth valve anonymously, then sync
// as a vendor from a different address.
func TestPreAuthValve_AnonymousFloodFromOneIPDoesNotRefuseAnAuthenticatedTenant(t *testing.T) {
	valveRPS, valveBurst := valveLimitsFromEnv()
	h := newTenantLimitHarness(t, testValve(valveRPS, valveBurst), tenantLimitsFromEnv(), newFakeClock())

	if refused := h.floodAnonymously(valveBurst*2, "203.0.113.9:40000", ""); refused == 0 {
		t.Fatalf("the flood from one address was never refused: the valve does not bound a single source")
	}

	w := h.syncFromIP("tenant-a", "198.51.100.20:51000")
	if w.Code != http.StatusAccepted {
		t.Errorf("an authenticated tenant from another address right after an anonymous flood: got %d (body %s), want 202 (#808)", w.Code, w.Body.String())
	}
}

// TestPreAuthValve_ForgedXForwardedForCannotMintBuckets: with no trusted
// proxy the header is inert, so rotating it does not escape the flooder's own
// bucket (UXT-006, the #88 regression) and does not touch anyone else's.
func TestPreAuthValve_ForgedXForwardedForCannotMintBuckets(t *testing.T) {
	h := newTenantLimitHarness(t, testValve(1, 3), tenantLimitsFromEnv(), newFakeClock())

	refused := 0
	for i := 0; i < 10; i++ {
		refused += h.floodAnonymously(1, "203.0.113.9:40000", fmt.Sprintf("198.51.100.%d", i+1))
	}
	if refused == 0 {
		t.Errorf("rotating X-Forwarded-For from an untrusted peer minted fresh buckets: nothing was refused in 10 requests against burst 3")
	}
}

// TestPreAuthValve_TrustedProxyAttributesTheForwardedClient: behind a trusted
// proxy two clients get their own buckets, so one's flood does not refuse the
// other even though both arrive from the proxy's socket address.
func TestPreAuthValve_TrustedProxyAttributesTheForwardedClient(t *testing.T) {
	h := newTenantLimitHarness(t, testValveTrusting("10.0.0.0/8", 1, 3), tenantLimitsFromEnv(), newFakeClock())
	const proxy = "10.1.2.3:5555"

	if refused := h.floodAnonymously(10, proxy, "203.0.113.9"); refused == 0 {
		t.Fatalf("the forwarded client's flood was never refused")
	}
	if refused := h.floodAnonymously(1, proxy, "203.0.113.77"); refused != 0 {
		t.Errorf("a second client behind the same trusted proxy was refused by the first client's flood")
	}
}

// TestPreAuthValve_ClientCountIsBounded: more distinct clients than the cap
// share one overflow bucket rather than growing the map without bound.
func TestPreAuthValve_ClientCountIsBounded(t *testing.T) {
	cfg := tenantRateLimitConfig{RPS: 1, Burst: 2, MaxTenants: 3}
	limiter := newTenantLimiter(cfg, newFakeClock())
	for i := 0; i < 50; i++ {
		limiter.take(fmt.Sprintf("198.51.100.%d", i))
	}
	if got := len(limiter.buckets); got > cfg.MaxTenants {
		t.Errorf("valve holds %d client buckets, want at most %d", got, cfg.MaxTenants)
	}
}

func TestClientIPResolver(t *testing.T) {
	cases := []struct {
		name, trusted, remote, xff, want string
	}{
		{"untrusted peer ignores the header", "", "203.0.113.9:1", "198.51.100.1", "203.0.113.9"},
		{"no peer port", "", "203.0.113.9", "", "203.0.113.9"},
		{"trusted peer, one hop", "10.0.0.0/8", "10.1.1.1:1", "203.0.113.9", "203.0.113.9"},
		{"rightmost non-trusted hop wins over a forged leftmost", "10.0.0.0/8", "10.1.1.1:1", "6.6.6.6, 203.0.113.9, 10.2.2.2", "203.0.113.9"},
		{"malformed hop falls back to the peer", "10.0.0.0/8", "10.1.1.1:1", "6.6.6.6, not-an-ip", "10.1.1.1"},
		{"every hop trusted falls back to the peer", "10.0.0.0/8", "10.1.1.1:1", "10.2.2.2, 10.3.3.3", "10.1.1.1"},
		{"mapped IPv4 folds to dotted quad", "", "[::ffff:198.51.100.7]:1", "", "198.51.100.7"},
		{"IPv6 canonical", "", "[2001:DB8:0:0:0:0:0:1]:1", "", "2001:db8::1"},
		{"trusted IPv6 proxy", "fd00::/8", "[fd00::5]:1", "2001:db8::9", "2001:db8::9"},
		{"empty peer", "", "", "", "unknown"},
		{"a name is never resolved", "10.0.0.0/8", "10.1.1.1:1", "evil.example", "10.1.1.1"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r, err := newClientIPResolver(tc.trusted)
			if err != nil {
				t.Fatal(err)
			}
			if got := r.resolve(tc.remote, tc.xff); got != tc.want {
				t.Errorf("resolve(%q, %q) = %q, want %q", tc.remote, tc.xff, got, tc.want)
			}
		})
	}
}

func TestClientIPResolver_RefusesTrustEveryone(t *testing.T) {
	for _, bad := range []string{"0.0.0.0/0", "::/0", "10.0.0.0/33", "10.0.0.0/x", "proxy.internal", "10.0.0.0/-1"} {
		if _, err := newClientIPResolver(bad); err == nil {
			t.Errorf("newClientIPResolver(%q): want a startup refusal, got none", bad)
		} else if !strings.Contains(err.Error(), trustedProxiesEnv) {
			t.Errorf("refusal for %q does not name %s: %v", bad, trustedProxiesEnv, err)
		}
	}
	if _, err := newClientIPResolver(" 10.0.0.0/8 , fd00::/8 ,"); err != nil {
		t.Errorf("a valid list with blanks was refused: %v", err)
	}
}
