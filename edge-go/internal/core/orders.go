package core

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
)

// ProductSearchResult represents a product returned from Core API search
type ProductSearchResult struct {
	ID    string `json:"id"`
	SKU   string `json:"sku"`
	Title string `json:"title"`
}

// OrderItemRequest represents a line item in a create order request
type OrderItemRequest struct {
	ProductID string `json:"productId"`
	Quantity  int    `json:"quantity"`
}

// CreateOrderRequest represents the Core API order creation payload
type CreateOrderRequest struct {
	ShopID        string             `json:"shopId"`
	CustomerPhone string             `json:"customerPhone"`
	Notes         string             `json:"notes"`
	Items         []OrderItemRequest `json:"items"`
}

// CreateOrderResponse represents the Core API order creation response
type CreateOrderResponse struct {
	ID          string `json:"id"`
	OrderNumber string `json:"orderNumber"`
	Status      string `json:"status"`
}

// SearchProducts searches for products by query string via Core API
func (c *Client) SearchProducts(ctx context.Context, token, tenantID, query string) ([]ProductSearchResult, error) {
	var results []ProductSearchResult
	_, err := c.breaker.Execute(func() (interface{}, error) {
		reqURL := fmt.Sprintf("%s/api/v1/products/search?q=%s", c.baseURL, url.QueryEscape(query))
		httpReq, err := http.NewRequestWithContext(ctx, "GET", reqURL, nil)
		if err != nil {
			return nil, fmt.Errorf("failed to create request: %w", err)
		}

		httpReq.Header.Set("Authorization", "Bearer "+token)
		httpReq.Header.Set("X-Tenant-Id", tenantID)

		httpResp, err := c.client.Do(httpReq)
		if err != nil {
			return nil, fmt.Errorf("request failed: %w", err)
		}
		defer httpResp.Body.Close()

		if httpResp.StatusCode >= 400 {
			body, _ := io.ReadAll(httpResp.Body)
			return nil, fmt.Errorf("search failed with status %d: %s", httpResp.StatusCode, string(body))
		}

		if err := json.NewDecoder(httpResp.Body).Decode(&results); err != nil {
			return nil, fmt.Errorf("failed to decode response: %w", err)
		}

		return results, nil
	})

	return results, err
}

// CreateOrder creates an order via Core API
func (c *Client) CreateOrder(ctx context.Context, token, tenantID string, req *CreateOrderRequest) (*CreateOrderResponse, error) {
	var resp CreateOrderResponse
	_, err := c.breaker.Execute(func() (interface{}, error) {
		payload, err := json.Marshal(req)
		if err != nil {
			return nil, fmt.Errorf("failed to marshal request: %w", err)
		}

		httpReq, err := http.NewRequestWithContext(ctx, "POST", c.baseURL+"/api/v1/orders", bytes.NewBuffer(payload))
		if err != nil {
			return nil, fmt.Errorf("failed to create request: %w", err)
		}

		httpReq.Header.Set("Content-Type", "application/json")
		httpReq.Header.Set("Authorization", "Bearer "+token)
		httpReq.Header.Set("X-Tenant-Id", tenantID)

		httpResp, err := c.client.Do(httpReq)
		if err != nil {
			return nil, fmt.Errorf("request failed: %w", err)
		}
		defer httpResp.Body.Close()

		if httpResp.StatusCode >= 400 {
			body, _ := io.ReadAll(httpResp.Body)
			return nil, fmt.Errorf("order creation failed with status %d: %s", httpResp.StatusCode, string(body))
		}

		if err := json.NewDecoder(httpResp.Body).Decode(&resp); err != nil {
			return nil, fmt.Errorf("failed to decode response: %w", err)
		}

		return &resp, nil
	})

	if err != nil {
		return nil, err
	}

	return &resp, nil
}
