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

// itemLinePattern matches a single line like "2x Chocolate Cake",
// "3 bread", or "1 Eggs, Ham, Cheese". The grammar is:
//
//	<qty> [x|X] <one-or-more-space> <product-query>
//
// The entire remainder of the line is treated as the product query,
// so product names containing commas ("Eggs, Ham, Cheese") survive
// intact. Line breaks delimit items.
var itemLinePattern = regexp.MustCompile(`(?i)^\s*(\d+)\s*[xX]?\s+(.+?)\s*$`)

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

// ParseMessage parses a text message into order items.
//
// The grammar is newline-delimited: one item per line, formatted as
// "<qty> [x] <product query>". This preserves product names that
// contain commas (e.g. "Eggs, Ham, Cheese") — the previous regex
// used comma-or-end as the terminator and silently truncated them.
//
// If no line matches the pattern the full trimmed message is returned
// as a single order item with quantity 1.
func ParseMessage(phone, text string) *ParsedOrder {
	order := &ParsedOrder{
		Phone: phone,
		Raw:   text,
	}

	for _, rawLine := range strings.Split(text, "\n") {
		line := strings.TrimSpace(rawLine)
		if line == "" {
			continue
		}
		match := itemLinePattern.FindStringSubmatch(line)
		if match == nil {
			continue
		}
		qty, _ := strconv.Atoi(match[1])
		if qty <= 0 {
			qty = 1
		}
		product := strings.TrimSpace(match[2])
		if product == "" {
			continue
		}
		order.Items = append(order.Items, OrderItem{
			ProductQuery: product,
			Quantity:     qty,
		})
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
