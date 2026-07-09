package middleware

import (
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"go.uber.org/zap"
)

// defaultJWKSRefreshInterval is the fallback cadence for re-fetching the
// Keycloak JWKS document when JWKS_REFRESH_INTERVAL is unset or invalid.
const defaultJWKSRefreshInterval = 5 * time.Minute

// defaultJWTAudience is the fail-closed expected "aud" claim used when
// EDGE_JWT_AUDIENCE is unset (issue #87 P1-5, threat T-bl2-02). Audience
// enforcement is ALWAYS on — there is no inert path — so a token minted for
// a different Keycloak client is rejected at the edge even in a
// misconfigured deployment. Mirrors the canonical core-api client audience;
// override per-environment via EDGE_JWT_AUDIENCE (see docker-compose).
const defaultJWTAudience = "core-api"

// JWKSResponse represents the response from Keycloak JWKS endpoint
type JWKSResponse struct {
	Keys []JWK `json:"keys"`
}

// JWK represents a JSON Web Key
type JWK struct {
	Kid string `json:"kid"`
	Kty string `json:"kty"`
	Alg string `json:"alg"`
	Use string `json:"use"`
	N   string `json:"n"`
	E   string `json:"e"`
}

// jwksHTTPClient is used for all JWKS fetches. Uses a short overall timeout
// so a wedged Keycloak cannot stall request handling for the default Go
// client's 0 (infinite) timeout.
var jwksHTTPClient = &http.Client{Timeout: 5 * time.Second}

// JWTMiddleware validates JWT tokens from Keycloak
type JWTMiddleware struct {
	jwksURL         string
	issuer          string
	audience        string
	logger          *zap.Logger
	refreshInterval time.Duration

	// mu guards publicKeys and lastRefresh, which are read on every request
	// goroutine and mutated by refreshKeys(). Gin serves requests
	// concurrently, so without this lock a request racing a JWKS refresh
	// triggers "concurrent map read and map write" (a data race that can
	// panic the process).
	mu          sync.RWMutex
	publicKeys  map[string]*rsa.PublicKey
	lastRefresh time.Time
}

// NewJWTMiddleware creates a new JWT middleware. JWKS_REFRESH_INTERVAL
// (parsed with time.ParseDuration, e.g. "30s", "10m") overrides the
// default 5-minute JWKS refresh cadence; invalid values fall back to
// the default with a warning log.
func NewJWTMiddleware(jwksURL, issuer string, logger *zap.Logger) *JWTMiddleware {
	refreshInterval := defaultJWKSRefreshInterval
	// EDGE_JWT_AUDIENCE pins the expected "aud" claim. Enforcement is fail-closed:
	// when unset we fall back to defaultJWTAudience rather than disabling the
	// check, so the edge always rejects tokens minted for a different audience
	// (issue #87 P1-5, threat T-bl2-02).
	audience := os.Getenv("EDGE_JWT_AUDIENCE")
	if audience == "" {
		audience = defaultJWTAudience
	}
	logger.Info("JWT audience validation enabled", zap.String("audience", audience))
	if raw := os.Getenv("JWKS_REFRESH_INTERVAL"); raw != "" {
		if parsed, err := time.ParseDuration(raw); err == nil && parsed > 0 {
			refreshInterval = parsed
			logger.Info("JWKS refresh interval override",
				zap.Duration("interval", refreshInterval))
		} else {
			logger.Warn("Invalid JWKS_REFRESH_INTERVAL; using default",
				zap.String("value", raw),
				zap.Duration("default", refreshInterval))
		}
	}
	return &JWTMiddleware{
		jwksURL:         jwksURL,
		issuer:          issuer,
		audience:        audience,
		logger:          logger,
		publicKeys:      make(map[string]*rsa.PublicKey),
		refreshInterval: refreshInterval,
	}
}

// Validate returns a Gin middleware that validates JWT tokens
func (m *JWTMiddleware) Validate() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing authorization header"})
			return
		}

		tokenString := strings.TrimPrefix(authHeader, "Bearer ")
		if tokenString == authHeader {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid authorization header format"})
			return
		}

		// Parse and validate token.
		// WithLeeway tolerates 30s of clock skew between Keycloak and this
		// node so a mildly out-of-sync pod does not start 401-ing valid
		// tokens on exp / nbf boundaries.
		token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
			// Verify signing method
			if _, ok := token.Method.(*jwt.SigningMethodRSA); !ok {
				return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
			}

			// Get key ID from token header
			kid, ok := token.Header["kid"].(string)
			if !ok {
				return nil, errors.New("missing kid in token header")
			}

			// Refresh keys if the configured interval has elapsed.
			if m.refreshDue() {
				if err := m.refreshKeys(); err != nil {
					m.logger.Error("Failed to refresh JWKS", zap.Error(err))
				}
			}

			// Get public key for this kid
			publicKey, ok := m.getKey(kid)
			if !ok {
				// Try to refresh keys and retry
				if err := m.refreshKeys(); err != nil {
					return nil, fmt.Errorf("failed to refresh keys: %w", err)
				}
				publicKey, ok = m.getKey(kid)
				if !ok {
					return nil, fmt.Errorf("public key not found for kid: %s", kid)
				}
			}

			return publicKey, nil
		}, jwt.WithLeeway(30*time.Second))

		if err != nil {
			m.logger.Warn("JWT validation failed", zap.Error(err))
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
			return
		}

		if !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
			return
		}

		// Extract claims
		claims, ok := token.Claims.(jwt.MapClaims)
		if !ok {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token claims"})
			return
		}

		// Verify issuer
		iss, ok := claims["iss"].(string)
		if !ok || iss != m.issuer {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid issuer"})
			return
		}

		// Verify audience — always on (fail-closed, issue #87 P1-5, threat
		// T-bl2-02). m.audience is never empty (defaults to defaultJWTAudience
		// in NewJWTMiddleware). Runs before the tenant check below, so a token
		// with a wrong/missing aud is rejected as "invalid audience".
		if !hasAudience(claims, m.audience) {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid audience"})
			return
		}

		// Extract tenant ID (priority: tenant_id -> tenantId -> tid)
		var tenantID string
		for _, key := range []string{"tenant_id", "tenantId", "tid"} {
			if val, ok := claims[key].(string); ok && val != "" {
				tenantID = val
				break
			}
		}

		// Reject tokens with no tenant claim. Fail fast here instead of
		// forwarding an empty tenant that Core would reject downstream.
		if tenantID == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing tenant claim"})
			return
		}

		// Store claims and tenant in context
		c.Set("jwt_claims", claims)
		c.Set("tenant_id", tenantID)
		c.Set("user_id", claims["sub"])

		c.Next()
	}
}

// refreshKeys fetches public keys from Keycloak JWKS endpoint
func (m *JWTMiddleware) refreshKeys() error {
	req, err := http.NewRequest(http.MethodGet, m.jwksURL, nil)
	if err != nil {
		return fmt.Errorf("failed to build JWKS request: %w", err)
	}
	resp, err := jwksHTTPClient.Do(req)
	if err != nil {
		return fmt.Errorf("failed to fetch JWKS: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("JWKS endpoint returned status %d", resp.StatusCode)
	}

	var jwks JWKSResponse
	if err := json.NewDecoder(resp.Body).Decode(&jwks); err != nil {
		return fmt.Errorf("failed to decode JWKS: %w", err)
	}

	newKeys := make(map[string]*rsa.PublicKey)
	for _, jwk := range jwks.Keys {
		if jwk.Kty != "RSA" {
			continue
		}

		nBytes, err := base64.RawURLEncoding.DecodeString(jwk.N)
		if err != nil {
			m.logger.Warn("Failed to decode N", zap.String("kid", jwk.Kid), zap.Error(err))
			continue
		}

		eBytes, err := base64.RawURLEncoding.DecodeString(jwk.E)
		if err != nil {
			m.logger.Warn("Failed to decode E", zap.String("kid", jwk.Kid), zap.Error(err))
			continue
		}

		n := new(big.Int).SetBytes(nBytes)
		e := new(big.Int).SetBytes(eBytes)

		publicKey := &rsa.PublicKey{
			N: n,
			E: int(e.Int64()),
		}

		newKeys[jwk.Kid] = publicKey
	}

	m.mu.Lock()
	m.publicKeys = newKeys
	m.lastRefresh = time.Now()
	m.mu.Unlock()
	m.logger.Info("Refreshed JWKS", zap.Int("key_count", len(newKeys)))

	return nil
}

// refreshDue reports whether the JWKS refresh interval has elapsed. Reads
// lastRefresh under the read lock to stay race-free with refreshKeys().
func (m *JWTMiddleware) refreshDue() bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return time.Since(m.lastRefresh) > m.refreshInterval
}

// getKey returns the cached RSA public key for a kid under the read lock.
func (m *JWTMiddleware) getKey(kid string) (*rsa.PublicKey, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	key, ok := m.publicKeys[kid]
	return key, ok
}

// hasAudience reports whether the token's "aud" claim (a string or array of
// strings per RFC 7519) contains the expected audience.
func hasAudience(claims jwt.MapClaims, expected string) bool {
	switch aud := claims["aud"].(type) {
	case string:
		return aud == expected
	case []interface{}:
		for _, a := range aud {
			if s, ok := a.(string); ok && s == expected {
				return true
			}
		}
	}
	return false
}
