package core

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"net/http"
	"time"

	"github.com/sony/gobreaker"
	"go.uber.org/zap"
)

// Client represents a client for the Core Java API
type Client struct {
	baseURL string
	client  *http.Client
	breaker *gobreaker.CircuitBreaker
	logger  *zap.Logger
}

// NewClient creates a new Core API client with circuit breaker.
//
// ReadyToTrip requires a minimum of 10 observed requests before it will
// ever open the breaker. This prevents cold-start trips: without this
// threshold, a single early failure in a fresh pod (1/1 failures =
// 100% ratio) would immediately open the breaker and shed load for
// 60s even though Core is healthy.
//
// The counter window is 30s (gobreaker auto-resets counts on each
// Interval tick while in the closed state), which is long enough to
// see a handful of real requests under normal load.
func NewClient(baseURL string, logger *zap.Logger) *Client {
	cbSettings := gobreaker.Settings{
		Name:        "CoreAPI",
		MaxRequests: 3,
		Interval:    30 * time.Second,
		Timeout:     60 * time.Second,
		ReadyToTrip: func(counts gobreaker.Counts) bool {
			if counts.Requests < 10 {
				return false
			}
			failureRatio := float64(counts.TotalFailures) / float64(counts.Requests)
			return failureRatio >= 0.6
		},
		OnStateChange: func(name string, from gobreaker.State, to gobreaker.State) {
			logger.Info("Circuit breaker state changed",
				zap.String("name", name),
				zap.String("from", from.String()),
				zap.String("to", to.String()),
			)
		},
	}

	return &Client{
		baseURL: baseURL,
		client: &http.Client{
			Timeout: 30 * time.Second,
		},
		breaker: gobreaker.NewCircuitBreaker(cbSettings),
		logger:  logger,
	}
}

// BatchSyncRequest is the body sent to core's POST /api/v1/sync/batch.
// Field names are CORE's (camelCase) — this struct never leaves the edge in
// any other direction, so it is free to speak core's dialect exactly.
type BatchSyncRequest struct {
	TenantID string                   `json:"tenantId"`
	Items    []map[string]interface{} `json:"items"`
}

// BatchSyncResponse is the EDGE-facing shape, returned verbatim to the
// gateway's own callers and documented as SyncBatchResponse in
// edge-go/docs/swagger.json. Its snake_case names are a published contract
// and are deliberately NOT changed to match core.
type BatchSyncResponse struct {
	Status         string           `json:"status"`
	ProcessedCount int              `json:"processed_count"`
	Results        []SyncItemResult `json:"results"`

	// HTTPStatus is the success status core answered with (200, or 202
	// from an older core). Not part of the body.
	HTTPStatus int `json:"-"`
}

// SyncItemResult is the outcome of one batch item, exactly as core reports
// it (core-java uk.jtoye.core.sync.dto.SyncItemResult). Every name is a
// single word, so core's wire names and the edge's published names are the
// same and one type serves both directions.
type SyncItemResult struct {
	Index   int    `json:"index"`
	Type    string `json:"type"`
	Key     string `json:"key"`
	Outcome string `json:"outcome"`
	ID      string `json:"id,omitempty"`
	Code    string `json:"code,omitempty"`
	Message string `json:"message,omitempty"`
}

// CoreProblemError is a 4xx application/problem+json refusal from core: a
// typed answer core gives its own callers (shop-access-denied, validation,
// rate-limited, ...). The edge relays it verbatim instead of flattening it
// into an untyped 502, so an integrator syncing through the edge reads the
// same type URI, status and extension members as one calling core directly.
//
// Only 4xx is relayed. A 5xx problem stays an ordinary error and the edge's
// generic 502, because a server-side failure body is internal (T-37-96).
type CoreProblemError struct {
	// Status is core's HTTP status (400..499).
	Status int
	// ContentType is core's Content-Type header, verbatim.
	ContentType string
	// RetryAfter is core's Retry-After header, verbatim ("" when absent).
	RetryAfter string
	// Body is core's response body, byte for byte.
	Body []byte
}

// maxProblemBodyBytes bounds how much of a core refusal the edge buffers to
// relay. Core's problem bodies are a few hundred bytes; anything larger is
// not one the edge will vouch for and falls back to the generic error.
const maxProblemBodyBytes = 64 << 10

// isClientProblem reports whether a core response is a typed refusal the
// edge relays: a 4xx whose media type is application/problem+json
// (parameters such as charset allowed).
func isClientProblem(resp *http.Response) bool {
	if resp.StatusCode < 400 || resp.StatusCode > 499 {
		return false
	}
	mt, _, err := mime.ParseMediaType(resp.Header.Get("Content-Type"))
	return err == nil && mt == "application/problem+json"
}

func (e *CoreProblemError) Error() string {
	return fmt.Sprintf("core refused the request: %d %s", e.Status, e.ContentType)
}

// coreBatchSyncResponse is core's wire shape for the same payload.
//
// Two types rather than one because the two contracts genuinely differ:
// core-java serialises uk.jtoye.core.sync.dto.BatchSyncResponse with default
// Jackson naming (`processedCount`), while the edge publishes
// `processed_count`. Decoding core's body straight into BatchSyncResponse
// silently produced ProcessedCount=0 on every successful batch — Go's
// encoding/json case-insensitive match does not bridge an underscore, and it
// reports no error for a field it cannot place. Found by the edge↔core
// contract gate (issue #337); the pre-existing client test could not see it
// because its stub core encoded the edge's own struct.
type coreBatchSyncResponse struct {
	Status         string           `json:"status"`
	ProcessedCount int              `json:"processedCount"`
	Results        []SyncItemResult `json:"results"`
}

// SyncBatch sends a batch sync request to the Core API
func (c *Client) SyncBatch(ctx context.Context, token, tenantID string, items []map[string]interface{}) (*BatchSyncResponse, error) {
	req := BatchSyncRequest{
		TenantID: tenantID,
		Items:    items,
	}

	var resp *BatchSyncResponse
	var problem *CoreProblemError
	_, err := c.breaker.Execute(func() (interface{}, error) {
		payload, err := json.Marshal(req)
		if err != nil {
			return nil, fmt.Errorf("failed to marshal request: %w", err)
		}

		httpReq, err := http.NewRequestWithContext(ctx, "POST", c.baseURL+"/api/v1/sync/batch", bytes.NewBuffer(payload))
		if err != nil {
			return nil, fmt.Errorf("failed to create request: %w", err)
		}

		httpReq.Header.Set("Content-Type", "application/json")
		httpReq.Header.Set("Authorization", "Bearer "+token)
		httpReq.Header.Set("X-Tenant-Id", tenantID)

		httpResp, err := c.client.Do(httpReq)
		if err != nil {
			c.logger.Error("Request to Core API failed", zap.Error(err))
			return nil, fmt.Errorf("request failed: %w", err)
		}
		defer httpResp.Body.Close()

		if httpResp.StatusCode >= 500 {
			// Server error - circuit breaker should trip
			body, _ := io.ReadAll(httpResp.Body)
			c.logger.Error("Core API server error", zap.Int("status", httpResp.StatusCode), zap.String("body", string(body)))
			return nil, fmt.Errorf("server error: %d", httpResp.StatusCode)
		}

		if isClientProblem(httpResp) {
			body, err := io.ReadAll(io.LimitReader(httpResp.Body, maxProblemBodyBytes+1))
			if err != nil {
				return nil, fmt.Errorf("failed to read core problem (status %d): %w", httpResp.StatusCode, err)
			}
			if len(body) > maxProblemBodyBytes {
				return nil, fmt.Errorf("core problem (status %d) exceeds %d bytes; not relayed", httpResp.StatusCode, maxProblemBodyBytes)
			}
			problem = &CoreProblemError{
				Status:      httpResp.StatusCode,
				ContentType: httpResp.Header.Get("Content-Type"),
				RetryAfter:  httpResp.Header.Get("Retry-After"),
				Body:        body,
			}
			// A refusal core answered on purpose is core WORKING, so it is
			// returned outside the breaker: counted as a failure, a run of
			// one integrator's 403s would open the breaker and shed every
			// tenant's sync for 60 s.
			return nil, nil
		}

		if httpResp.StatusCode != http.StatusOK && httpResp.StatusCode != http.StatusAccepted {
			body, _ := io.ReadAll(httpResp.Body)
			return nil, fmt.Errorf("unexpected status %d: %s", httpResp.StatusCode, string(body))
		}

		var wire coreBatchSyncResponse
		if err := json.NewDecoder(httpResp.Body).Decode(&wire); err != nil {
			return nil, fmt.Errorf("failed to decode response: %w", err)
		}

		// Translate core's camelCase wire shape into the edge's published
		// snake_case shape. One assignment per field, so a new core field
		// cannot be silently dropped here without the contract gate noticing.
		resp = &BatchSyncResponse{
			Status:         wire.Status,
			ProcessedCount: wire.ProcessedCount,
			Results:        wire.Results,
			HTTPStatus:     httpResp.StatusCode,
		}

		return resp, nil
	})

	if err != nil {
		return nil, err
	}
	if problem != nil {
		return nil, problem
	}

	return resp, nil
}

// ForwardWebhook sends a raw webhook payload to Core API
func (c *Client) ForwardWebhook(ctx context.Context, token, tenantID string, source string, payload []byte) error {
	_, err := c.breaker.Execute(func() (interface{}, error) {
		httpReq, err := http.NewRequestWithContext(ctx, "POST", c.baseURL+"/api/v1/webhooks/"+source, bytes.NewBuffer(payload))
		if err != nil {
			return nil, fmt.Errorf("failed to create request: %w", err)
		}

		httpReq.Header.Set("Content-Type", "application/json")
		if token != "" {
			httpReq.Header.Set("Authorization", "Bearer "+token)
		}
		if tenantID != "" {
			httpReq.Header.Set("X-Tenant-Id", tenantID)
		}

		httpResp, err := c.client.Do(httpReq)
		if err != nil {
			c.logger.Error("Webhook forward failed", zap.String("source", source), zap.Error(err))
			return nil, fmt.Errorf("request failed: %w", err)
		}
		defer httpResp.Body.Close()

		if httpResp.StatusCode >= 500 {
			body, _ := io.ReadAll(httpResp.Body)
			return nil, fmt.Errorf("server error: %d: %s", httpResp.StatusCode, string(body))
		}

		if httpResp.StatusCode >= 400 {
			body, _ := io.ReadAll(httpResp.Body)
			c.logger.Warn("Webhook forward rejected", zap.Int("status", httpResp.StatusCode), zap.String("body", string(body)))
		}

		return nil, nil
	})
	return err
}

// HealthCheck checks if the Core API is healthy
func (c *Client) HealthCheck(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, "GET", c.baseURL+"/health", nil)
	if err != nil {
		return err
	}

	resp, err := c.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("health check failed with status: %d", resp.StatusCode)
	}

	return nil
}
