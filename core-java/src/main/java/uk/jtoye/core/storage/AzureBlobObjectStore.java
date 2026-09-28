package uk.jtoye.core.storage;

import com.azure.storage.blob.BlobServiceClient;

/**
 * The one Azure Blob implementation of {@link BlobObjectStore}.
 *
 * <p>TDD RED skeleton (36-01 Task 1): every operation is inert, so the Azurite tracer compiles and
 * fails on its behavioural assertions. The GREEN commit replaces these bodies.
 */
public final class AzureBlobObjectStore implements BlobObjectStore {

    private final BlobServiceClient client;

    public AzureBlobObjectStore(BlobServiceClient client) {
        this.client = client;
    }

    @Override
    public void put(String container, String key, byte[] bytes, String contentType, String cacheControl) {
    }

    @Override
    public boolean putIfAbsent(String container, String key, byte[] bytes, String contentType, String cacheControl) {
        return true;
    }

    @Override
    public byte[] get(String container, String key) {
        return new byte[0];
    }

    @Override
    public boolean deleteIfExists(String container, String key) {
        return true;
    }

    @Override
    public boolean containerExists(String container) {
        return true;
    }

    @Override
    public ContainerAccess containerAccess(String container) {
        return ContainerAccess.PRIVATE;
    }

    @Override
    public void createContainerIfMissing(String container, ContainerAccess access) {
    }
}
