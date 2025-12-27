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

// NewClient creates a new Core API client with circuit breaker
func NewClient(baseURL string, logger *zap.Logger) *Client {
	cbSettings := gobreaker.Settings{
		Name:        "CoreAPI",
		MaxRequests: 3,
		Interval:    10 * time.Second,
		Timeout:     60 * time.Second,
		ReadyToTrip: func(counts gobreaker.Counts) bool {
			failureRatio := float64(counts.TotalFailures) / float64(counts.Requests)
			return counts.Requests >= 3 && failureRatio >= 0.6
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

// BatchSyncRequest represents a batch sync request
type BatchSyncRequest struct {
	TenantID string                   `json:"tenant_id"`
	Items    []map[string]interface{} `json:"items"`
}

// BatchSyncResponse represents the response from batch sync
type BatchSyncResponse struct {
	Status       string `json:"status"`
	ProcessedCount int  `json:"processed_count"`
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

		httpReq, err := http.NewRequestWithContext(ctx, "POST", c.baseURL+"/sync/batch", bytes.NewBuffer(payload))
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

		if err := json.NewDecoder(httpResp.Body).Decode(&resp); err != nil {
			return nil, fmt.Errorf("failed to decode response: %w", err)
		}

		return resp, nil
	})

	if err != nil {
		return nil, err
	}

	return resp, nil
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
