package uk.jtoye.core.storage;

/**
 * Thrown when the object store could not be reached at all (a transport failure such as a refused
 * connection or a timeout), as opposed to the store answering with an error for one object.
 */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message) {
        super(message);
    }

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
