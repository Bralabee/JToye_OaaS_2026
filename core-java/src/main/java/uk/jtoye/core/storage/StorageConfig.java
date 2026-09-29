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
import java.util.function.Function;

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

    @Bean
    public BlobServiceClient blobServiceClient(StorageProperties properties) {
        return buildClient(properties, System::getenv);
    }

    /**
     * Validates the shape, then builds the client. {@code env} is the one environment lookup for
     * both the shape rules and the credential, so a test can drive the workload-identity path
     * without the JVM's real environment.
     */
    static BlobServiceClient buildClient(StorageProperties properties, Function<String, String> env) {
        StorageProperties.Blob blob = properties.getBlob();
        // First, before anything is built: a malformed configuration stops the context here (D-02).
        blob.validateShape(env);

        BlobServiceClientBuilder builder = new BlobServiceClientBuilder()
                .retryOptions(new RequestRetryOptions(RetryPolicyType.EXPONENTIAL, blob.getMaxTries(),
                        Duration.ofSeconds(blob.getTryTimeoutSeconds()), null, null, null));

        StorageProperties.AuthMode mode = blob.authModeValue();
        switch (mode) {
            case CONNECTION_STRING -> builder.connectionString(blob.getConnectionString());
            case WORKLOAD_IDENTITY -> {
                // The AKS workload-identity webhook injects these at admission; the application
                // reads them from the environment and never from a config file.
                WorkloadIdentityCredentialBuilder credential = new WorkloadIdentityCredentialBuilder()
                        .clientId(env.apply("AZURE_CLIENT_ID"))
                        .tenantId(env.apply("AZURE_TENANT_ID"))
                        .tokenFilePath(env.apply("AZURE_FEDERATED_TOKEN_FILE"));
                String authorityHost = env.apply("AZURE_AUTHORITY_HOST");
                if (authorityHost != null && !authorityHost.isBlank()) {
                    credential.authorityHost(authorityHost);
                }
                builder.endpoint(blob.getEndpoint()).credential(credential.build());
            }
        }

        // Mode, endpoint HOST and container names only. Never the connection string (T-36-03).
        log.info("Configuring Blob storage client: mode={}, endpointHost={}, publicContainer={}, quarantineContainer={}",
                mode.value, blob.endpointHostForLog(), blob.getPublicContainer(), blob.getQuarantineContainer());
        return builder.buildClient();
    }

    @Bean
    public BlobObjectStore blobObjectStore(BlobServiceClient blobServiceClient) {
        return new AzureBlobObjectStore(blobServiceClient);
    }
}
