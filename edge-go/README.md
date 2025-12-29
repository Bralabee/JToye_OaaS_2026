# Edge Gateway Service

The edge-go service is a high-performance API gateway written in Go that provides:
- **JWT Authentication** - OAuth2/OIDC token validation with Keycloak
- **Rate Limiting** - Token bucket algorithm for request throttling
- **Circuit Breaking** - Fault tolerance with automatic failure detection
- **Tenant Isolation** - Multi-tenant request routing with X-Tenant-Id headers

## Architecture

```
Client → Edge Gateway → Core API (Java)
         (Go/Gin)       (Spring Boot)
```

### Components

- **JWT Middleware** (`internal/middleware/jwt.go`) - Validates JWT tokens, extracts tenant context
- **Core Client** (`internal/core/client.go`) - HTTP client with circuit breaker for Core API calls
- **Main Server** (`cmd/edge/main.go`) - Gin HTTP server with health checks and batch sync endpoint

## Configuration

Environment variables:
```bash
# Core API connection
CORE_API_URL=http://localhost:9090

# JWT/OAuth2 settings
JWKS_URL=http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/certs
JWT_ISSUER=http://localhost:8085/realms/jtoye-dev

# Server settings
PORT=8080
```

## Running the Service

### Prerequisites
- Go 1.21+
- Running Core API on port 9090
- Keycloak on port 8085 with jtoye-dev realm configured

### Start Server
```bash
cd edge-go
go run cmd/edge/main.go
```

Server starts on `http://localhost:8080`

## API Endpoints

### Health Check
```bash
GET /health
```

Returns service health status.

### Batch Sync
```bash
POST /api/sync/batch
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "items": [
    {"id": "1", "name": "Product A"},
    {"id": "2", "name": "Product B"}
  ]
}
```

Forwards batch sync requests to Core API with tenant isolation.

## Testing

### Run All Tests
```bash
go test ./... -v
```

### Test Coverage
```bash
go test ./... -cover
```

### Test Components

**JWT Middleware Tests** (`internal/middleware/jwt_test.go`):
- Missing/invalid authorization headers
- Malformed JWT tokens
- Token validation with JWKS
- Tenant ID extraction from claims (tenant_id, tenantId, tid)

**Core Client Tests** (`internal/core/client_test.go`):
- Health check success/failure/timeout
- Batch sync with proper headers and body
- Server error handling
- Circuit breaker behavior (closed → open state transition)

## Security Features

### JWT Validation
- Validates JWT signature using JWKS from Keycloak
- Verifies token issuer, expiration, and claims
- Extracts tenant ID from token claims (supports multiple formats)

### Tenant Isolation
- Extracts tenant_id from JWT claims
- Adds X-Tenant-Id header to Core API requests
- Ensures multi-tenant data separation

### Rate Limiting
- Token bucket algorithm with 100 requests/second limit
- Per-tenant rate limiting support
- Prevents resource exhaustion

### Circuit Breaker
- Detects Core API failures automatically
- Trips open after 3 consecutive failures
- Half-open state after 10-second timeout
- Prevents cascading failures

## Integration with Core API

Edge gateway proxies requests to core-java service:

1. **JWT Validation** - Validates incoming JWT token
2. **Tenant Extraction** - Extracts tenant_id from token claims
3. **Request Forwarding** - Adds X-Tenant-Id header and forwards to Core API
4. **Response Handling** - Returns Core API response to client

### Example Flow
```
1. Client → Edge: POST /api/sync/batch with Bearer token
2. Edge validates JWT, extracts tenant_id = "00000000-0000-0000-0000-000000000001"
3. Edge → Core: POST /api/sync/batch with:
   - Authorization: Bearer <token>
   - X-Tenant-Id: 00000000-0000-0000-0000-000000000001
4. Core API processes request with tenant isolation
5. Edge returns Core API response to client
```

## Monitoring

### Logs
Structured JSON logging with zap:
```json
{"level":"info","ts":1767046042.01,"caller":"core/client.go:36","msg":"Circuit breaker state changed","name":"CoreAPI","from":"closed","to":"open"}
```

### Metrics
- Circuit breaker state changes
- Rate limiter rejections
- JWT validation failures
- Core API response times

## Production Considerations

### Deployment
- Use environment-specific configuration
- Enable TLS/HTTPS in production
- Deploy behind load balancer for HA
- Configure health check endpoints for orchestrator

### Scaling
- Stateless design enables horizontal scaling
- Rate limiter uses in-memory state (consider Redis for distributed rate limiting)
- Circuit breaker per-instance (consider shared state for distributed deployments)

### Security
- Always validate JWT signatures
- Use HTTPS for Core API communication
- Implement request timeouts (currently 30s)
- Monitor for JWT validation failures

## Development

### Project Structure
```
edge-go/
├── cmd/edge/           # Main application entry point
│   └── main.go
├── internal/
│   ├── core/           # Core API client
│   │   ├── client.go
│   │   └── client_test.go
│   └── middleware/     # HTTP middleware
│       ├── jwt.go
│       └── jwt_test.go
├── go.mod              # Go module dependencies
└── README.md
```

### Dependencies
- `gin-gonic/gin` - HTTP web framework
- `golang-jwt/jwt` - JWT parsing and validation
- `MicahParks/keyfunc` - JWKS key management
- `sony/gobreaker` - Circuit breaker implementation
- `uber-go/zap` - Structured logging
- `uber-go/ratelimit` - Rate limiting

## Troubleshooting

### JWT Validation Fails
- Verify JWKS_URL is accessible
- Check token issuer matches JWT_ISSUER
- Ensure token is not expired
- Verify kid in token header matches JWKS key ID

### Circuit Breaker Opens
- Check Core API health: `curl http://localhost:9090/health`
- Review Core API logs for errors
- Verify Core API is running and accessible
- Check network connectivity

### Rate Limit Errors
- Reduce request rate to below 100 req/s
- Implement client-side backoff/retry logic
- Consider increasing rate limit in production

## Test Coverage Summary

✅ **12/12 tests passing (100%)**

- JWT Middleware: 5 tests covering all validation scenarios
- Core Client: 7 tests covering health checks, batch sync, circuit breaker
- Circuit breaker verified: Transitions from closed → open after consecutive failures
- Tenant extraction: Supports tenant_id, tenantId, and tid claim formats
