package whatsapp

import (
	"encoding/json"
	"regexp"
	"strconv"
	"strings"
)

// CloudAPIWebhook represents the WhatsApp Cloud API webhook payload
type CloudAPIWebhook struct {
	Entry []struct {
		Changes []struct {
			Value struct {
				Messages []struct {
					From string `json:"from"`
					Text struct {
						Body string `json:"body"`
					} `json:"text"`
					Type string `json:"type"`
				} `json:"messages"`
			} `json:"value"`
		} `json:"changes"`
	} `json:"entry"`
}

// OrderItem represents a parsed order line item
type OrderItem struct {
	ProductQuery string `json:"productQuery"`
	Quantity     int    `json:"quantity"`
}

// ParsedOrder represents an order extracted from a WhatsApp message
type ParsedOrder struct {
	Phone string      `json:"phone"`
	Items []OrderItem `json:"items"`
	Raw   string      `json:"raw"`
}

// itemPattern matches patterns like "2x Chocolate Cake", "3 bread", "1x item"
var itemPattern = regexp.MustCompile(`(?i)(\d+)\s*[xX]?\s+(.+?)(?:,|$)`)

// ParseWebhook extracts messages from a WhatsApp Cloud API webhook payload
func ParseWebhook(body []byte) (*ParsedOrder, error) {
	var webhook CloudAPIWebhook
	if err := json.Unmarshal(body, &webhook); err != nil {
		return nil, err
	}

	for _, entry := range webhook.Entry {
		for _, change := range entry.Changes {
			for _, msg := range change.Value.Messages {
				if msg.Type == "text" && msg.Text.Body != "" {
					return ParseMessage(msg.From, msg.Text.Body), nil
				}
			}
		}
	}

	return nil, nil
}

// ParseMessage parses a text message into order items
// Supports: "2x Chocolate Cake, 1x Bread" or "2 cakes, 3 pastries"
func ParseMessage(phone, text string) *ParsedOrder {
	order := &ParsedOrder{
		Phone: phone,
		Raw:   text,
	}

	matches := itemPattern.FindAllStringSubmatch(text, -1)
	for _, match := range matches {
		qty, _ := strconv.Atoi(match[1])
		if qty <= 0 {
			qty = 1
		}
		product := strings.TrimSpace(match[2])
		if product != "" {
			order.Items = append(order.Items, OrderItem{
				ProductQuery: product,
				Quantity:     qty,
			})
		}
	}

	// If no pattern matched, treat the whole message as a single item query
	if len(order.Items) == 0 && strings.TrimSpace(text) != "" {
		order.Items = append(order.Items, OrderItem{
			ProductQuery: strings.TrimSpace(text),
			Quantity:     1,
		})
	}

	return order
}
