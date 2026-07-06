package auth

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"go.uber.org/zap"
)

func tokenServer(t *testing.T, expiresIn int, hits *int32) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(hits, 1)
		if err := r.ParseForm(); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if r.FormValue("grant_type") != "client_credentials" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"access_token": "tok-abc",
			"expires_in":   expiresIn,
		})
	}))
}

func TestToken_FetchesAndCaches(t *testing.T) {
	var hits int32
	srv := tokenServer(t, 300, &hits)
	defer srv.Close()

	logger, _ := zap.NewProduction()
	p := &KeycloakServiceTokenProvider{
		tokenURL:     srv.URL,
		clientID:     "edge",
		clientSecret: "secret",
		httpClient:   srv.Client(),
		logger:       logger,
	}

	tok, err := p.Token(context.Background())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if tok != "tok-abc" {
		t.Errorf("expected tok-abc, got %q", tok)
	}

	// Second call should hit the cache, not the server.
	if _, err := p.Token(context.Background()); err != nil {
		t.Fatalf("unexpected error on cached call: %v", err)
	}
	if got := atomic.LoadInt32(&hits); got != 1 {
		t.Errorf("expected 1 token-endpoint hit (cached), got %d", got)
	}
}

func TestToken_RefreshesWhenExpired(t *testing.T) {
	var hits int32
	// expires_in 0 forces the cached token to be treated as immediately stale.
	srv := tokenServer(t, 0, &hits)
	defer srv.Close()

	p := &KeycloakServiceTokenProvider{
		tokenURL:     srv.URL,
		clientID:     "edge",
		clientSecret: "secret",
		httpClient:   srv.Client(),
	}

	if _, err := p.Token(context.Background()); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if _, err := p.Token(context.Background()); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got := atomic.LoadInt32(&hits); got != 2 {
		t.Errorf("expected 2 hits (token re-fetched after expiry), got %d", got)
	}
}

func TestToken_MissingCredentialsError(t *testing.T) {
	p := &KeycloakServiceTokenProvider{
		tokenURL:   "http://unused.invalid/token",
		httpClient: &http.Client{},
	}
	if _, err := p.Token(context.Background()); err == nil {
		t.Error("expected error when client credentials are not configured")
	}
}

func TestToken_ServerErrorPropagates(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()

	p := &KeycloakServiceTokenProvider{
		tokenURL:     srv.URL,
		clientID:     "edge",
		clientSecret: "wrong",
		httpClient:   srv.Client(),
	}
	if _, err := p.Token(context.Background()); err == nil {
		t.Error("expected error when token endpoint returns non-200")
	}
}

func TestNewKeycloakServiceTokenProvider_BuildsTokenURL(t *testing.T) {
	p := NewKeycloakServiceTokenProvider("http://kc/realms/jtoye/", "id", "sec", nil)
	want := "http://kc/realms/jtoye/protocol/openid-connect/token"
	if p.tokenURL != want {
		t.Errorf("tokenURL = %q, want %q", p.tokenURL, want)
	}
}
