package uk.jtoye.core.storage;

/**
 * Thrown when the {@code storage.blob.*} configuration is invalid, so the application fails at
 * startup rather than at the first upload (Phase 36, decision D-02).
 */
public class StorageConfigurationException extends RuntimeException {

    public StorageConfigurationException(String message) {
        super(message);
    }

    public StorageConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
