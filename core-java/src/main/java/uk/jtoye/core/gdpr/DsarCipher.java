package uk.jtoye.core.gdpr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * RED skeleton (31.1-07): the API shape only, so the failing tests compile and fail on their
 * assertions. Every operation refuses; nothing is encrypted or stored by this class yet.
 */
@Component
public class DsarCipher {

    public enum Purpose {
        SUBJECT_ADDRESS
    }

    public DsarCipher(@Value("${jtoye.gdpr.dsar.encryption-key:}") String hexKey) {
    }

    public byte[] encrypt(Purpose purpose, UUID requestId, String plaintext) {
        throw new UnsupportedOperationException("31.1-07 RED skeleton");
    }

    public String decrypt(Purpose purpose, UUID requestId, byte[] stored) {
        throw new UnsupportedOperationException("31.1-07 RED skeleton");
    }

    String decryptWithLabel(String label, UUID requestId, byte[] stored) {
        throw new UnsupportedOperationException("31.1-07 RED skeleton");
    }
}
