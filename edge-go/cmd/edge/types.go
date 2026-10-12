package main

// Response + request types used by the edge HTTP surface. Extracted into a
// dedicated file so swaggo can discover them when parsing handler doc
// comments. These types MUST stay in package main because the top-level
// swaggo annotations (@title, @version, etc.) live on main.go.

// HealthResponse is returned by GET /health. `uptime` is a Unix-second
// placeholder — it's really the request timestamp. We keep the shape
// stable so scrape targets don't break if we start tracking actual uptime
// later.
type HealthResponse struct {
	// Edge liveness indicator. Always "OK" when the process is up.
	Edge string `json:"edge" example:"OK"`
	// Unix timestamp (seconds) at the moment the probe was served.
	Uptime int64 `json:"uptime" example:"1713484800"`
}

// ComponentHealth is the per-downstream health block inside ReadyResponse.
type ComponentHealth struct {
	// Whether the downstream passed its readiness check.
	Healthy bool `json:"healthy" example:"true"`
}

// ReadyResponse is returned by GET /ready. Shape is stable on both 200 and
// 503 — only the top-level HTTP status flips. Individual component booleans
// show exactly which downstream is unhealthy.
type ReadyResponse struct {
	// Edge self-status. Always "OK" when the process is up.
	Edge string `json:"edge" example:"OK"`
	// Core Java API health (via its /health endpoint).
	Core ComponentHealth `json:"core"`
	// Keycloak JWKS endpoint health.
	JWKS ComponentHealth `json:"jwks"`
}

// SyncBatchRequest is the JSON body for POST /api/v1/sync/batch. Items is an
// opaque list of domain objects the edge has accumulated; they're forwarded
// to Core for persistence.
type SyncBatchRequest struct {
	// Opaque list of sync items. Each map is forwarded to Core as-is.
	Items []map[string]interface{} `json:"items"`
}

// SyncBatchResponse mirrors core.BatchSyncResponse for swaggo documentation
// purposes. Keeping a local type avoids a circular docs import and lets us
// annotate fields with example values tuned for the edge audience.
type SyncBatchResponse struct {
	// Processing status. "accepted" when Core queued the batch.
	Status string `json:"status" example:"accepted"`
	// Number of items Core accepted for processing.
	ProcessedCount int `json:"processed_count" example:"42"`
}

// WebhookAck is the empty-body 200 response returned for webhook endpoints
// that process asynchronously. WhatsApp retries any non-200 response, so we
// always return 200 after receipt — actual processing outcome is logged,
// not surfaced.
type WebhookAck struct {
	// True after receipt. Processing result is available in logs only.
	Accepted bool `json:"accepted" example:"true"`
}

// ErrorResponse is the standard shape for every non-2xx JSON response the
// edge emits. Matches the shape produced by gin.H{"error": "..."} calls.
type ErrorResponse struct {
	// Human-readable error message.
	Error string `json:"error" example:"invalid request body"`
}

// ProblemDetail is the RFC 7807 body (application/problem+json) the edge
// returns for a rate-limit refusal (37-25, D-19). It is core's shape
// (RateLimitInterceptor.writeProblem): the same `type` URI, so an integrator
// backs off from an edge 429 exactly as from a core one, plus a stable `code`
// and a typed `retryAfterSeconds` so nothing has to be mined out of prose.
type ProblemDetail struct {
	// Stable problem type URI. https://jtoye.uk/errors/rate-limited for a 429.
	Type string `json:"type" example:"https://jtoye.uk/errors/rate-limited"`
	// Short human-readable summary of the problem type.
	Title string `json:"title" example:"Too Many Requests"`
	// HTTP status code, repeated in the body.
	Status int `json:"status" example:"429"`
	// Human-readable explanation specific to this occurrence.
	Detail string `json:"detail" example:"Rate limit exceeded. Please try again in 1 seconds."`
	// Stable machine-readable code. RATE_LIMITED for a 429.
	Code string `json:"code" example:"RATE_LIMITED"`
	// Whole seconds to wait before retrying; equals the Retry-After header.
	RetryAfterSeconds int `json:"retryAfterSeconds" example:"1"`
}
