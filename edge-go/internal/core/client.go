package core

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
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
	Status         string `json:"status"`
	ProcessedCount int    `json:"processed_count"`
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
	Status         string `json:"status"`
	ProcessedCount int    `json:"processedCount"`
}

// SyncBatch sends a batch sync request to the Core API
func (c *Client) SyncBatch(ctx context.Context, token, tenantID string, items []map[string]interface{}) (*BatchSyncResponse, error) {
	req := BatchSyncRequest{
		TenantID: tenantID,
		Items:    items,
	}

	var resp *BatchSyncResponse
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
		}

		return resp, nil
	})

	if err != nil {
		return nil, err
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
