package uk.jtoye.core.storage;

import com.azure.identity.WorkloadIdentityCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RetryPolicyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Builds the Azure Blob client behind {@link StorageService} (Phase 36).
 *
 * <p>{@code storage.blob.auth-mode} is the ONE switch between the two ways of reaching Blob
 * (decision D-02):
 * <ul>
 *   <li>{@code connection-string} — the Azurite emulator locally and in the nightly;</li>
 *   <li>{@code workload-identity} — AKS Workload Identity in staging/production. The credential is
 *       an explicit {@code WorkloadIdentityCredential}, never {@code DefaultAzureCredential}: the
 *       latter also probes the instance-metadata endpoint, which the 443-only NetworkPolicy
 *       blocks, so a misconfiguration would surface as a timeout instead of a clear error.</li>
 * </ul>
 * Any other value fails here, while the context is being built, not at the first upload.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {
    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    static final String MODE_CONNECTION_STRING = "connection-string";
    static final String MODE_WORKLOAD_IDENTITY = "workload-identity";

    @Bean
    public BlobServiceClient blobServiceClient(StorageProperties properties) {
        StorageProperties.Blob blob = properties.getBlob();
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder()
                .retryOptions(new RequestRetryOptions(RetryPolicyType.EXPONENTIAL, blob.getMaxTries(),
                        Duration.ofSeconds(blob.getTryTimeoutSeconds()), null, null, null));

        String mode = blob.getAuthMode();
        if (MODE_CONNECTION_STRING.equals(mode)) {
            builder.connectionString(blob.getConnectionString());
        } else if (MODE_WORKLOAD_IDENTITY.equals(mode)) {
            // The AKS workload-identity webhook injects these at admission; the application reads
            // them from the environment and never from a config file.
            WorkloadIdentityCredentialBuilder credential = new WorkloadIdentityCredentialBuilder()
                    .clientId(System.getenv("AZURE_CLIENT_ID"))
                    .tenantId(System.getenv("AZURE_TENANT_ID"))
                    .tokenFilePath(System.getenv("AZURE_FEDERATED_TOKEN_FILE"));
            String authorityHost = System.getenv("AZURE_AUTHORITY_HOST");
            if (authorityHost != null && !authorityHost.isBlank()) {
                credential.authorityHost(authorityHost);
            }
            builder.endpoint(blob.getEndpoint()).credential(credential.build());
        } else {
            throw new StorageConfigurationException("storage.blob.auth-mode must be '"
                    + MODE_CONNECTION_STRING + "' or '" + MODE_WORKLOAD_IDENTITY + "', got '" + mode + "'");
        }

        // Mode, endpoint HOST and container names only. Never the connection string (T-36-03).
        log.info("Configuring Blob storage client: mode={}, endpointHost={}, publicContainer={}, quarantineContainer={}",
                mode, blob.endpointHostForLog(), blob.getPublicContainer(), blob.getQuarantineContainer());
        return builder.buildClient();
    }

    @Bean
    public BlobObjectStore blobObjectStore(BlobServiceClient blobServiceClient) {
        return new AzureBlobObjectStore(blobServiceClient);
    }
}
