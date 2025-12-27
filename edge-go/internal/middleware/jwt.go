package middleware

import (
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"go.uber.org/zap"
)

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

// JWTMiddleware validates JWT tokens from Keycloak
type JWTMiddleware struct {
	jwksURL     string
	issuer      string
	logger      *zap.Logger
	publicKeys  map[string]*rsa.PublicKey
	lastRefresh time.Time
}

// NewJWTMiddleware creates a new JWT middleware
func NewJWTMiddleware(jwksURL, issuer string, logger *zap.Logger) *JWTMiddleware {
	return &JWTMiddleware{
		jwksURL:    jwksURL,
		issuer:     issuer,
		logger:     logger,
		publicKeys: make(map[string]*rsa.PublicKey),
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

		// Parse and validate token
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

			// Refresh keys if needed (every 5 minutes)
			if time.Since(m.lastRefresh) > 5*time.Minute {
				if err := m.refreshKeys(); err != nil {
					m.logger.Error("Failed to refresh JWKS", zap.Error(err))
				}
			}

			// Get public key for this kid
			publicKey, ok := m.publicKeys[kid]
			if !ok {
				// Try to refresh keys and retry
				if err := m.refreshKeys(); err != nil {
					return nil, fmt.Errorf("failed to refresh keys: %w", err)
				}
				publicKey, ok = m.publicKeys[kid]
				if !ok {
					return nil, fmt.Errorf("public key not found for kid: %s", kid)
				}
			}

			return publicKey, nil
		})

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

		// Extract tenant ID (priority: tenant_id -> tenantId -> tid)
		var tenantID string
		for _, key := range []string{"tenant_id", "tenantId", "tid"} {
			if val, ok := claims[key].(string); ok && val != "" {
				tenantID = val
				break
			}
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
	resp, err := http.Get(m.jwksURL)
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

	m.publicKeys = newKeys
	m.lastRefresh = time.Now()
	m.logger.Info("Refreshed JWKS", zap.Int("key_count", len(newKeys)))

	return nil
}
