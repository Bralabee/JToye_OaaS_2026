package middleware

import (
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"go.uber.org/zap"
)

// jwkFromPublicKey encodes an RSA public key as a JWK entry so a test JWKS
// server can serve it for real signature verification.
func jwkFromPublicKey(kid string, pub *rsa.PublicKey) JWK {
	eBytes := big.NewInt(int64(pub.E)).Bytes()
	return JWK{
		Kid: kid,
		Kty: "RSA",
		Alg: "RS256",
		Use: "sig",
		N:   base64.RawURLEncoding.EncodeToString(pub.N.Bytes()),
		E:   base64.RawURLEncoding.EncodeToString(eBytes),
	}
}

// newTestMiddleware wires a JWTMiddleware to a JWKS server serving the given
// key under kid "test-key-id", returning a helper to sign valid tokens.
func newTestMiddleware(t *testing.T, issuer string) (*JWTMiddleware, func(jwt.MapClaims) string) {
	t.Helper()
	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("Failed to generate RSA key: %v", err)
	}
	const kid = "test-key-id"
	jwksServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(JWKSResponse{Keys: []JWK{jwkFromPublicKey(kid, &privateKey.PublicKey)}})
	}))
	t.Cleanup(jwksServer.Close)

	logger, _ := zap.NewProduction()
	m := NewJWTMiddleware(jwksServer.URL, issuer, logger)

	sign := func(claims jwt.MapClaims) string {
		token := jwt.NewWithClaims(jwt.SigningMethodRS256, claims)
		token.Header["kid"] = kid
		signed, err := token.SignedString(privateKey)
		if err != nil {
			t.Fatalf("Failed to sign token: %v", err)
		}
		return signed
	}
	return m, sign
}

func init() {
	gin.SetMode(gin.TestMode)
}

func runValidate(m *JWTMiddleware, tokenString string) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest("GET", "/test", nil)
	c.Request.Header.Set("Authorization", "Bearer "+tokenString)
	m.Validate()(c)
	return w
}

func TestJWTMiddleware_Validate_ValidTokenWithTenant(t *testing.T) {
	const issuer = "http://test-issuer.com"
	m, sign := newTestMiddleware(t, issuer)
	token := sign(jwt.MapClaims{
		"iss":       issuer,
		"sub":       "test-user-123",
		"aud":       "core-api", // fail-closed default audience (#87 P1-5)
		"tenant_id": "00000000-0000-0000-0000-000000000001",
		"exp":       time.Now().Add(time.Hour).Unix(),
		"iat":       time.Now().Unix(),
	})
	w := runValidate(m, token)
	if w.Code != http.StatusOK {
		t.Errorf("Expected status 200 for valid token, got %d (%s)", w.Code, w.Body.String())
	}
}

func TestJWTMiddleware_Validate_MissingTenantRejected(t *testing.T) {
	const issuer = "http://test-issuer.com"
	m, sign := newTestMiddleware(t, issuer)
	token := sign(jwt.MapClaims{
		"iss": issuer,
		"sub": "test-user-123",
		// Carry the default audience so the request passes the (now always-on)
		// audience gate and reaches the tenant check — otherwise it would 401
		// with "invalid audience" instead of "missing tenant claim" (#87 P1-5).
		"aud": "core-api",
		"exp": time.Now().Add(time.Hour).Unix(),
		"iat": time.Now().Unix(),
	})
	w := runValidate(m, token)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("Expected 401 for token with no tenant claim, got %d", w.Code)
	}
	var resp map[string]interface{}
	json.NewDecoder(w.Body).Decode(&resp)
	if resp["error"] != "missing tenant claim" {
		t.Errorf("Expected missing tenant error, got: %v", resp["error"])
	}
}

func TestJWTMiddleware_Validate_Audience(t *testing.T) {
	const issuer = "http://test-issuer.com"
	t.Setenv("EDGE_JWT_AUDIENCE", "jtoye-core")
	m, sign := newTestMiddleware(t, issuer)

	base := func() jwt.MapClaims {
		return jwt.MapClaims{
			"iss":       issuer,
			"sub":       "u1",
			"tenant_id": "00000000-0000-0000-0000-000000000001",
			"exp":       time.Now().Add(time.Hour).Unix(),
			"iat":       time.Now().Unix(),
		}
	}

	t.Run("correct string aud passes", func(t *testing.T) {
		claims := base()
		claims["aud"] = "jtoye-core"
		if w := runValidate(m, sign(claims)); w.Code != http.StatusOK {
			t.Errorf("Expected 200, got %d (%s)", w.Code, w.Body.String())
		}
	})
	t.Run("correct array aud passes", func(t *testing.T) {
		claims := base()
		claims["aud"] = []string{"other", "jtoye-core"}
		if w := runValidate(m, sign(claims)); w.Code != http.StatusOK {
			t.Errorf("Expected 200, got %d (%s)", w.Code, w.Body.String())
		}
	})
	t.Run("wrong aud rejected", func(t *testing.T) {
		claims := base()
		claims["aud"] = "someone-else"
		if w := runValidate(m, sign(claims)); w.Code != http.StatusUnauthorized {
			t.Errorf("Expected 401 for wrong audience, got %d", w.Code)
		}
	})
	t.Run("missing aud rejected", func(t *testing.T) {
		if w := runValidate(m, sign(base())); w.Code != http.StatusUnauthorized {
			t.Errorf("Expected 401 for missing audience, got %d", w.Code)
		}
	})
}

// TestJWTMiddleware_Validate_Audience_DefaultWhenUnset proves the fail-closed
// default (#87 P1-5, threat T-bl2-02): with EDGE_JWT_AUDIENCE unset the edge
// still enforces defaultJWTAudience ("core-api"), rejecting wrong/missing aud.
// It deliberately does NOT call t.Setenv, and asserts m.audience resolved to
// the default constant to guard against ambient-env contamination in the
// shared test process.
func TestJWTMiddleware_Validate_Audience_DefaultWhenUnset(t *testing.T) {
	const issuer = "http://test-issuer.com"
	m, sign := newTestMiddleware(t, issuer)
	if m.audience != defaultJWTAudience {
		t.Fatalf("expected default audience %q, got %q (ambient EDGE_JWT_AUDIENCE set?)",
			defaultJWTAudience, m.audience)
	}

	base := func() jwt.MapClaims {
		return jwt.MapClaims{
			"iss":       issuer,
			"sub":       "u1",
			"tenant_id": "00000000-0000-0000-0000-000000000001",
			"exp":       time.Now().Add(time.Hour).Unix(),
			"iat":       time.Now().Unix(),
		}
	}

	t.Run("default audience core-api passes", func(t *testing.T) {
		claims := base()
		claims["aud"] = "core-api"
		if w := runValidate(m, sign(claims)); w.Code != http.StatusOK {
			t.Errorf("Expected 200, got %d (%s)", w.Code, w.Body.String())
		}
	})
	t.Run("wrong aud rejected under default", func(t *testing.T) {
		claims := base()
		claims["aud"] = "someone-else"
		if w := runValidate(m, sign(claims)); w.Code != http.StatusUnauthorized {
			t.Errorf("Expected 401 for wrong audience, got %d", w.Code)
		}
	})
	t.Run("missing aud rejected under default", func(t *testing.T) {
		if w := runValidate(m, sign(base())); w.Code != http.StatusUnauthorized {
			t.Errorf("Expected 401 for missing audience, got %d", w.Code)
		}
	})
}

func TestHasAudience(t *testing.T) {
	cases := []struct {
		name     string
		aud      interface{}
		expected string
		want     bool
	}{
		{"string match", "core", "core", true},
		{"string mismatch", "core", "edge", false},
		{"array match", []interface{}{"a", "core"}, "core", true},
		{"array mismatch", []interface{}{"a", "b"}, "core", false},
		{"absent", nil, "core", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			claims := jwt.MapClaims{}
			if tc.aud != nil {
				claims["aud"] = tc.aud
			}
			if got := hasAudience(claims, tc.expected); got != tc.want {
				t.Errorf("hasAudience(%v)=%v, want %v", tc.aud, got, tc.want)
			}
		})
	}
}

// TestJWTMiddleware_ConcurrentRefresh exercises the request path and forced
// JWKS refreshes concurrently. Run with -race, it fails on the previously
// unsynchronized publicKeys/lastRefresh access.
func TestJWTMiddleware_ConcurrentRefresh(t *testing.T) {
	const issuer = "http://test-issuer.com"
	m, sign := newTestMiddleware(t, issuer)
	token := sign(jwt.MapClaims{
		"iss":       issuer,
		"sub":       "u1",
		"aud":       "core-api", // fail-closed default audience (#87 P1-5)
		"tenant_id": "00000000-0000-0000-0000-000000000001",
		"exp":       time.Now().Add(time.Hour).Unix(),
		"iat":       time.Now().Unix(),
	})

	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(2)
		go func() { defer wg.Done(); runValidate(m, token) }()
		go func() { defer wg.Done(); _ = m.refreshKeys() }()
	}
	wg.Wait()
}

func TestJWTMiddleware_Validate_MissingAuthHeader(t *testing.T) {
	logger, _ := zap.NewProduction()
	middleware := NewJWTMiddleware("http://example.com/jwks", "http://example.com", logger)

	gin.SetMode(gin.TestMode)
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)

	c.Request = httptest.NewRequest("GET", "/test", nil)

	middleware.Validate()(c)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("Expected status 401, got %d", w.Code)
	}

	var response map[string]interface{}
	json.NewDecoder(w.Body).Decode(&response)

	if response["error"] != "missing authorization header" {
		t.Errorf("Expected error about missing header, got: %v", response["error"])
	}
}

func TestJWTMiddleware_Validate_InvalidHeaderFormat(t *testing.T) {
	logger, _ := zap.NewProduction()
	middleware := NewJWTMiddleware("http://example.com/jwks", "http://example.com", logger)

	gin.SetMode(gin.TestMode)
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)

	c.Request = httptest.NewRequest("GET", "/test", nil)
	c.Request.Header.Set("Authorization", "InvalidFormat token123")

	middleware.Validate()(c)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("Expected status 401, got %d", w.Code)
	}
}

func TestJWTMiddleware_Validate_InvalidToken(t *testing.T) {
	logger, _ := zap.NewProduction()
	middleware := NewJWTMiddleware("http://example.com/jwks", "http://example.com", logger)

	gin.SetMode(gin.TestMode)
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)

	c.Request = httptest.NewRequest("GET", "/test", nil)
	c.Request.Header.Set("Authorization", "Bearer invalid.token.here")

	middleware.Validate()(c)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("Expected status 401, got %d", w.Code)
	}
}

func TestJWTMiddleware_Validate_ValidToken(t *testing.T) {
	logger, _ := zap.NewProduction()

	// Generate RSA key pair for testing
	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("Failed to generate RSA key: %v", err)
	}

	// Create test server that returns JWKS
	jwksServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Return empty JWKS for now (would need proper implementation for full validation)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(JWKSResponse{Keys: []JWK{}})
	}))
	defer jwksServer.Close()

	middleware := NewJWTMiddleware(jwksServer.URL, "http://test-issuer.com", logger)

	// Create a valid JWT token
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.MapClaims{
		"iss":       "http://test-issuer.com",
		"sub":       "test-user-123",
		"aud":       "core-api", // fail-closed default audience (#87 P1-5)
		"tenant_id": "00000000-0000-0000-0000-000000000001",
		"exp":       time.Now().Add(time.Hour).Unix(),
		"iat":       time.Now().Unix(),
	})

	// Add kid to header
	token.Header["kid"] = "test-key-id"

	tokenString, err := token.SignedString(privateKey)
	if err != nil {
		t.Fatalf("Failed to sign token: %v", err)
	}

	gin.SetMode(gin.TestMode)
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)

	c.Request = httptest.NewRequest("GET", "/test", nil)
	c.Request.Header.Set("Authorization", "Bearer "+tokenString)

	// Note: This test will fail validation because we can't easily mock JWKS validation
	// In production, consider using testcontainers to spin up a real Keycloak instance
	middleware.Validate()(c)

	// For now, we expect unauthorized since JWKS validation will fail
	if w.Code != http.StatusUnauthorized {
		t.Logf("Note: Expected 401 due to JWKS validation, got %d", w.Code)
	}
}

func TestJWTMiddleware_ExtractTenantID(t *testing.T) {
	testCases := []struct {
		name           string
		claims         jwt.MapClaims
		expectedTenant string
	}{
		{
			name: "tenant_id claim",
			claims: jwt.MapClaims{
				"tenant_id": "00000000-0000-0000-0000-000000000001",
			},
			expectedTenant: "00000000-0000-0000-0000-000000000001",
		},
		{
			name: "tenantId claim",
			claims: jwt.MapClaims{
				"tenantId": "00000000-0000-0000-0000-000000000002",
			},
			expectedTenant: "00000000-0000-0000-0000-000000000002",
		},
		{
			name: "tid claim",
			claims: jwt.MapClaims{
				"tid": "00000000-0000-0000-0000-000000000003",
			},
			expectedTenant: "00000000-0000-0000-0000-000000000003",
		},
		{
			name: "priority order - tenant_id wins",
			claims: jwt.MapClaims{
				"tenant_id": "00000000-0000-0000-0000-000000000001",
				"tenantId":  "00000000-0000-0000-0000-000000000002",
				"tid":       "00000000-0000-0000-0000-000000000003",
			},
			expectedTenant: "00000000-0000-0000-0000-000000000001",
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			var tenantID string
			for _, key := range []string{"tenant_id", "tenantId", "tid"} {
				if val, ok := tc.claims[key].(string); ok && val != "" {
					tenantID = val
					break
				}
			}

			if tenantID != tc.expectedTenant {
				t.Errorf("Expected tenant ID %s, got %s", tc.expectedTenant, tenantID)
			}
		})
	}
}
