package uk.jtoye.core.storage;

import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.PublicAccessType;
import com.azure.storage.blob.options.BlobContainerCreateOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;

import java.util.function.Supplier;

/**
 * The one Azure Blob implementation of {@link BlobObjectStore} (Phase 36).
 *
 * <p><b>Exception mapping.</b> A {@link BlobStorageException} means the service ANSWERED (not
 * found, conflict, forbidden, ...) and propagates unchanged, so callers can still tell those cases
 * apart. Any other {@link RuntimeException} is a transport failure — the store could not be
 * reached — and is rethrown as {@link StorageUnavailableException} with the cause preserved; that
 * is what lets the demo seeder abort once instead of timing out on every entry.
 */
public final class AzureBlobObjectStore implements BlobObjectStore {

    private final BlobServiceClient client;

    public AzureBlobObjectStore(BlobServiceClient client) {
        this.client = client;
    }

    @Override
    public void put(String container, String key, byte[] bytes, String contentType, String cacheControl) {
        call("put " + container + "/" + key, () -> {
            blob(container, key).uploadWithResponse(uploadOptions(bytes, contentType, cacheControl), null, Context.NONE);
            return null;
        });
    }

    @Override
    public boolean putIfAbsent(String container, String key, byte[] bytes, String contentType, String cacheControl) {
        return call("putIfAbsent " + container + "/" + key, () -> {
            BlobParallelUploadOptions options = uploadOptions(bytes, contentType, cacheControl)
                    .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*"));
            try {
                blob(container, key).uploadWithResponse(options, null, Context.NONE);
                return true;
            } catch (BlobStorageException e) {
                // If-None-Match: * on an existing blob is refused with 409 BlobAlreadyExists:
                // "already present" is an answer, not a failure.
                if (e.getStatusCode() == 409 && BlobErrorCode.BLOB_ALREADY_EXISTS.equals(e.getErrorCode())) {
                    return false;
                }
                throw e;
            }
        });
    }

    @Override
    public byte[] get(String container, String key) {
        return call("get " + container + "/" + key, () -> blob(container, key).downloadContent().toBytes());
    }

    @Override
    public boolean deleteIfExists(String container, String key) {
        // deleteIfExists, never delete(): a plain delete of a missing blob throws BlobNotFound, which
        // would turn "already gone" into a failure and strand the quarantine sweep's sentinel.
        return call("deleteIfExists " + container + "/" + key, () -> blob(container, key).deleteIfExists());
    }

    @Override
    public boolean containerExists(String container) {
        return call("containerExists " + container, () -> container(container).exists());
    }

    @Override
    public ContainerAccess containerAccess(String container) {
        return call("containerAccess " + container, () -> {
            PublicAccessType access = container(container).getProperties().getBlobPublicAccess();
            if (access == null) {
                return ContainerAccess.PRIVATE;   // the service reports no public access as absent
            }
            if (PublicAccessType.BLOB.equals(access)) {
                return ContainerAccess.BLOB;
            }
            if (PublicAccessType.CONTAINER.equals(access)) {
                return ContainerAccess.CONTAINER;
            }
            throw new IllegalStateException("Unrecognised container access level for " + container + ": " + access);
        });
    }

    @Override
    public void createContainerIfMissing(String container, ContainerAccess access) {
        call("createContainerIfMissing " + container, () -> {
            BlobContainerCreateOptions options = new BlobContainerCreateOptions();
            switch (access) {
                case PRIVATE -> { }   // no access type = private
                case BLOB -> options.setPublicAccessType(PublicAccessType.BLOB);
                case CONTAINER -> options.setPublicAccessType(PublicAccessType.CONTAINER);
            }
            container(container).createIfNotExistsWithResponse(options, null, Context.NONE);
            return null;
        });
    }

    private BlobContainerClient container(String container) {
        return client.getBlobContainerClient(container);
    }

    private BlobClient blob(String container, String key) {
        return container(container).getBlobClient(key);
    }

    private static BlobParallelUploadOptions uploadOptions(byte[] bytes, String contentType, String cacheControl) {
        BlobHttpHeaders headers = new BlobHttpHeaders().setContentType(contentType);
        if (cacheControl != null) {
            headers.setCacheControl(cacheControl);
        }
        return new BlobParallelUploadOptions(BinaryData.fromBytes(bytes)).setHeaders(headers);
    }

    private static <T> T call(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (BlobStorageException e) {
            throw e;   // the service answered: propagate unchanged
        } catch (RuntimeException e) {
            throw new StorageUnavailableException("Object store unreachable during " + operation + ": " + e.getMessage(), e);
        }
    }
}
