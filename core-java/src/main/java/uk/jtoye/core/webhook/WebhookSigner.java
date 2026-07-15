package uk.jtoye.core.webhook;

import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 webhook signer (Stripe {@code t=,v1=} scheme). STUB — real
 * implementation lands in the GREEN commit.
 */
@Component
public class WebhookSigner {

    public String sign(byte[] rawBody, String secret, long unixTs) {
        return "";
    }

    public boolean verify(byte[] rawBody, String secret, long unixTs, String signatureHeader) {
        return false;
    }
}
