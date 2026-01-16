package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"testing"
)

func TestVerifyWhatsAppSignature(t *testing.T) {
	secret := "test_secret"
	payload := []byte(`{"object":"whatsapp_business_account","entry":[{"id":"12345","changes":[{"value":{"messaging_product":"whatsapp","metadata":{"display_phone_number":"15550000000","phone_number_id":"123456789"},"contacts":[{"profile":{"name":"John Doe"},"wa_id":"1234567890"}],"messages":[{"from":"1234567890","id":"msg_123","timestamp":"1678901234","text":{"body":"Hello world"},"type":"text"}]},"field":"messages"}]}]}`)
	
	h := hmac.New(sha256.New, []byte(secret))
	h.Write(payload)
	expectedSignature := hex.EncodeToString(h.Sum(nil))
	
	t.Run("Valid signature", func(t *testing.T) {
		if !verifyWhatsAppSignature(payload, expectedSignature, secret) {
			t.Errorf("Expected signature to be valid")
		}
	})
	
	t.Run("Valid signature with prefix", func(t *testing.T) {
		if !verifyWhatsAppSignature(payload, "sha256="+expectedSignature, secret) {
			t.Errorf("Expected signature with prefix to be valid")
		}
	})
	
	t.Run("Invalid signature", func(t *testing.T) {
		if verifyWhatsAppSignature(payload, "invalid", secret) {
			t.Errorf("Expected signature to be invalid")
		}
	})
}
