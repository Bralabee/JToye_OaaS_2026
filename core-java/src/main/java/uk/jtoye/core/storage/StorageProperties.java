package uk.jtoye.core.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;
import java.util.Locale;

@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    private Blob blob = new Blob();
    private long maxFileSizeBytes = 5_242_880; // 5MB
    private List<String> allowedContentTypes = List.of("image/jpeg", "image/png", "image/webp", "image/gif");

    public Blob getBlob() { return blob; }
    public void setBlob(Blob blob) { this.blob = blob; }
    public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
    public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }
    public List<String> getAllowedContentTypes() { return allowedContentTypes; }
    public void setAllowedContentTypes(List<String> allowedContentTypes) { this.allowedContentTypes = allowedContentTypes; }

    /**
     * {@code storage.blob.*} (Phase 36). One {@code auth-mode} switch selects how the Blob client is
     * built: {@code connection-string} (the Azurite emulator locally and in the nightly) or
     * {@code workload-identity} (AKS Workload Identity in staging/production, decision D-02).
     *
     * <p>{@code publicUrl} is the BROWSER origin of the public container and is deliberately
     * independent of the endpoint the SDK talks to (split horizon: core-java reaches Azurite at an
     * in-network host while the browser loads images from {@code localhost}). Persisted image URLs
     * are always composed from it, never from the SDK's own blob URL.
     */
    public static class Blob {
        private String authMode = "connection-string";
        private String connectionString = "";
        private String endpoint = "";
        private String publicContainer = "jtoye-images";
        private String quarantineContainer = "jtoye-quarantine";
        private String publicUrl = "http://localhost:10000/devstoreaccount1/jtoye-images";
        private boolean createContainers = false;
        private int maxTries = 3;
        private int tryTimeoutSeconds = 30;

        public String getAuthMode() { return authMode; }
        public void setAuthMode(String authMode) { this.authMode = authMode; }
        public String getConnectionString() { return connectionString; }
        public void setConnectionString(String connectionString) { this.connectionString = connectionString; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getPublicContainer() { return publicContainer; }
        public void setPublicContainer(String publicContainer) { this.publicContainer = publicContainer; }
        public String getQuarantineContainer() { return quarantineContainer; }
        public void setQuarantineContainer(String quarantineContainer) { this.quarantineContainer = quarantineContainer; }
        public String getPublicUrl() { return publicUrl; }
        public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
        public boolean isCreateContainers() { return createContainers; }
        public void setCreateContainers(boolean createContainers) { this.createContainers = createContainers; }
        public int getMaxTries() { return maxTries; }
        public void setMaxTries(int maxTries) { this.maxTries = maxTries; }
        public int getTryTimeoutSeconds() { return tryTimeoutSeconds; }
        public void setTryTimeoutSeconds(int tryTimeoutSeconds) { this.tryTimeoutSeconds = tryTimeoutSeconds; }

        /**
         * The HOST of the endpoint the client will talk to, for logging only. It never returns the
         * connection string or any part of it other than a host name (threat T-36-03): a
         * connection string can carry an account key.
         *
         * <p>In connection-string mode the host comes from {@code DevelopmentStorageProxyUri}, else
         * {@code BlobEndpoint}, else {@code 127.0.0.1} for the bare emulator shorthand, else the
         * account's default blob host. In workload-identity mode it is the endpoint's host.
         */
        public String endpointHostForLog() {
            if (!"connection-string".equals(authMode)) {
                return hostOf(endpoint);
            }
            String proxy = connectionStringValue("DevelopmentStorageProxyUri");
            if (proxy != null) {
                return hostOf(proxy);
            }
            String blobEndpoint = connectionStringValue("BlobEndpoint");
            if (blobEndpoint != null) {
                return hostOf(blobEndpoint);
            }
            if ("true".equalsIgnoreCase(connectionStringValue("UseDevelopmentStorage"))) {
                return "127.0.0.1";
            }
            String account = connectionStringValue("AccountName");
            if (account != null && account.matches("[a-z0-9]{3,24}")) {
                return account + ".blob.core.windows.net";
            }
            return "<unknown>";
        }

        private String connectionStringValue(String name) {
            if (connectionString == null) {
                return null;
            }
            String wanted = name.toLowerCase(Locale.ROOT) + "=";
            for (String part : connectionString.split(";")) {
                String trimmed = part.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith(wanted)) {
                    return trimmed.substring(wanted.length());
                }
            }
            return null;
        }

        private static String hostOf(String url) {
            if (url == null || url.isBlank()) {
                return "<unset>";
            }
            try {
                String host = URI.create(url.trim()).getHost();
                return host != null ? host : "<unparseable>";
            } catch (IllegalArgumentException e) {
                return "<unparseable>";
            }
        }
    }
}
