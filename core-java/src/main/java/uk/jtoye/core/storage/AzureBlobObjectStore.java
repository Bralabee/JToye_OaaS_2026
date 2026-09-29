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

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The one Azure Blob implementation of {@link BlobObjectStore} (Phase 36).
 *
 * <p><b>Exception mapping.</b> A {@link BlobStorageException} means the service ANSWERED (not
 * found, conflict, forbidden, ...) and propagates unchanged, so callers can still tell those cases
 * apart. A TRANSPORT failure — the store could not be reached: an {@link IOException} (refused
 * connection, unknown host, TLS failure, the JDK and OkHttp timeouts) or a
 * {@link TimeoutException} (the Netty client's read/response timeouts, the per-try timeout,
 * Reactor's blocking-read timeout) anywhere in the cause chain — is rethrown as
 * {@link StorageUnavailableException} with the cause preserved; that is what lets the demo seeder
 * abort once instead of timing out on every entry.
 *
 * <p>Everything else propagates UNCHANGED (code review WR-06): an SDK
 * {@code IllegalArgumentException} for an invalid blob name, a {@code NullPointerException}, an
 * identity failure. Those are programming or configuration errors, not an outage. Reporting them
 * as "object store unreachable" sent an operator to the network instead of the bug, and made the
 * demo seeder abandon every remaining image instead of skipping the one bad entry. An
 * unrecognised container access level is a {@link StorageConfigurationException}.
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
            throw new StorageConfigurationException("Unrecognised container access level for " + container + ": " + access);
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

    /** Package-private for {@code AzureBlobObjectStoreTest}; see the class Javadoc for the mapping. */
    static <T> T call(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (BlobStorageException e) {
            throw e;   // the service answered: propagate unchanged
        } catch (RuntimeException e) {
            if (isTransportFailure(e)) {
                throw new StorageUnavailableException("Object store unreachable during " + operation + ": " + e.getMessage(), e);
            }
            throw e;   // a programming, validation or identity error: not an outage, so not reported as one
        }
    }

    /**
     * True when the cause chain carries an {@link IOException} or a {@link TimeoutException}: the
     * request never got an answer from the store. Causes are walked with an identity set, so a
     * cyclic chain cannot loop.
     */
    static boolean isTransportFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof IOException || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
