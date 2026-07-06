// Package auth provides service-to-service authentication helpers for the
// edge gateway. The WhatsApp webhook is a signature-only public route (Meta
// cannot present a Keycloak JWT), yet Core still requires an authenticated,
// tenant-scoped Bearer token on every call. The edge therefore mints its own
// Keycloak client-credentials token and forwards it to Core — keeping the
// existing RLS/tenant contract intact.
package auth

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"go.uber.org/zap"
)

// ServiceTokenProvider returns a valid Bearer token for edge->Core calls.
// Implementations must be safe for concurrent use.
type ServiceTokenProvider interface {
	Token(ctx context.Context) (string, error)
}

// tokenResponse models the subset of the Keycloak token endpoint response we
// consume.
type tokenResponse struct {
	AccessToken string `json:"access_token"`
	ExpiresIn   int    `json:"expires_in"`
}

// KeycloakServiceTokenProvider fetches and caches a client-credentials token
// from Keycloak. The token is reused until shortly before it expires, then
// refreshed on demand. Concurrent callers share a single cached token.
type KeycloakServiceTokenProvider struct {
	tokenURL     string
	clientID     string
	clientSecret string
	httpClient   *http.Client
	logger       *zap.Logger

	mu        sync.Mutex
	cached    string
	expiresAt time.Time
}

// refreshSkew refreshes the cached token this long before its real expiry so
// an in-flight request never uses a token that expires mid-call.
const refreshSkew = 30 * time.Second

// NewKeycloakServiceTokenProvider builds a provider targeting the given
// Keycloak issuer's token endpoint.
func NewKeycloakServiceTokenProvider(issuer, clientID, clientSecret string, logger *zap.Logger) *KeycloakServiceTokenProvider {
	return &KeycloakServiceTokenProvider{
		tokenURL:     strings.TrimRight(issuer, "/") + "/protocol/openid-connect/token",
		clientID:     clientID,
		clientSecret: clientSecret,
		httpClient:   &http.Client{Timeout: 5 * time.Second},
		logger:       logger,
	}
}

// Token returns a cached token when still valid, otherwise fetches a fresh one.
func (p *KeycloakServiceTokenProvider) Token(ctx context.Context) (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.cached != "" && time.Now().Before(p.expiresAt) {
		return p.cached, nil
	}

	if p.clientID == "" || p.clientSecret == "" {
		return "", fmt.Errorf("service token client credentials not configured")
	}

	form := url.Values{}
	form.Set("grant_type", "client_credentials")
	form.Set("client_id", p.clientID)
	form.Set("client_secret", p.clientSecret)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.tokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", fmt.Errorf("failed to build token request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := p.httpClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("failed to fetch service token: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("token endpoint returned status %d: %s", resp.StatusCode, string(body))
	}

	var tr tokenResponse
	if err := json.NewDecoder(resp.Body).Decode(&tr); err != nil {
		return "", fmt.Errorf("failed to decode token response: %w", err)
	}
	if tr.AccessToken == "" {
		return "", fmt.Errorf("token endpoint returned empty access_token")
	}

	p.cached = tr.AccessToken
	// expires_in is seconds; refresh a little early to avoid mid-call expiry.
	ttl := time.Duration(tr.ExpiresIn)*time.Second - refreshSkew
	if ttl <= 0 {
		ttl = time.Duration(tr.ExpiresIn) * time.Second
	}
	p.expiresAt = time.Now().Add(ttl)
	if p.logger != nil {
		p.logger.Info("Acquired Keycloak service token", zap.Int("expires_in", tr.ExpiresIn))
	}
	return p.cached, nil
}
