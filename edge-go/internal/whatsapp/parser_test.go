package whatsapp

import (
	"testing"
)

func TestParseMessage_MultipleItems(t *testing.T) {
	order := ParseMessage("+447700900000", "2x Chocolate Cake, 1x Sourdough Bread")
	if len(order.Items) != 2 {
		t.Fatalf("expected 2 items, got %d", len(order.Items))
	}
	if order.Items[0].Quantity != 2 || order.Items[0].ProductQuery != "Chocolate Cake" {
		t.Errorf("item 0: got %d x %s", order.Items[0].Quantity, order.Items[0].ProductQuery)
	}
	if order.Items[1].Quantity != 1 || order.Items[1].ProductQuery != "Sourdough Bread" {
		t.Errorf("item 1: got %d x %s", order.Items[1].Quantity, order.Items[1].ProductQuery)
	}
}

func TestParseMessage_SingleItem(t *testing.T) {
	order := ParseMessage("+447700900000", "3 pastries")
	if len(order.Items) != 1 {
		t.Fatalf("expected 1 item, got %d", len(order.Items))
	}
	if order.Items[0].Quantity != 3 || order.Items[0].ProductQuery != "pastries" {
		t.Errorf("got %d x %s", order.Items[0].Quantity, order.Items[0].ProductQuery)
	}
}

func TestParseMessage_FreeText(t *testing.T) {
	order := ParseMessage("+447700900000", "I want some cake please")
	if len(order.Items) != 1 {
		t.Fatalf("expected 1 fallback item, got %d", len(order.Items))
	}
	if order.Items[0].ProductQuery != "I want some cake please" {
		t.Errorf("expected raw text, got %s", order.Items[0].ProductQuery)
	}
}

func TestParseMessage_PhonePreserved(t *testing.T) {
	order := ParseMessage("+447700123456", "1x cake")
	if order.Phone != "+447700123456" {
		t.Errorf("phone: got %s", order.Phone)
	}
}

func TestParseWebhook_ValidPayload(t *testing.T) {
	payload := []byte(`{
		"entry": [{
			"changes": [{
				"value": {
					"messages": [{
						"from": "447700900000",
						"type": "text",
						"text": {"body": "2x Cake, 1x Bread"}
					}]
				}
			}]
		}]
	}`)

	order, err := ParseWebhook(payload)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if order == nil {
		t.Fatal("expected order, got nil")
	}
	if order.Phone != "447700900000" {
		t.Errorf("phone: got %s", order.Phone)
	}
	if len(order.Items) != 2 {
		t.Fatalf("expected 2 items, got %d", len(order.Items))
	}
}

func TestParseWebhook_NoTextMessage(t *testing.T) {
	payload := []byte(`{"entry": [{"changes": [{"value": {"messages": [{"from": "123", "type": "image"}]}}]}]}`)
	order, err := ParseWebhook(payload)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if order != nil {
		t.Error("expected nil for non-text message")
	}
}
