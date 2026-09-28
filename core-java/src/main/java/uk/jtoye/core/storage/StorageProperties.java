package uk.jtoye.core.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

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
        /** The Azurite emulator's fixed development account name (the name, not a credential). */
        static final String EMULATOR_ACCOUNT = "devstoreaccount1";

        /** Injected by the AKS workload-identity webhook at admission, never mapped from a config file. */
        static final List<String> WORKLOAD_IDENTITY_ENV =
                List.of("AZURE_CLIENT_ID", "AZURE_TENANT_ID", "AZURE_FEDERATED_TOKEN_FILE");

        /** A real public-cloud account endpoint: https, a 3-24 character account name, no port, no path. */
        private static final Pattern ACCOUNT_ENDPOINT =
                Pattern.compile("^https://[a-z0-9]{3,24}\\.blob\\.core\\.windows\\.net/?$");

        /** Blob container naming rules: 3-63 chars, lower-case alphanumerics and single hyphens. */
        private static final Pattern CONTAINER_NAME =
                Pattern.compile("^[a-z0-9](?!.*--)[a-z0-9-]{1,61}[a-z0-9]$");

        private static final Set<String> SHORTHAND_KEYS =
                Set.of("usedevelopmentstorage", "developmentstorageproxyuri");

        private static final Set<String> EMULATOR_ACCOUNT_KEYS = Set.of("defaultendpointsprotocol", "accountname",
                "accountkey", "blobendpoint", "queueendpoint", "tableendpoint");

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
         * Refuses every malformed {@code storage.blob.*} configuration with a
         * {@link StorageConfigurationException}, before any network call (decision D-02: a
         * misconfiguration fails at startup, not at the first upload). {@link StorageConfig} runs
         * it first inside the client bean factory, so a bad value stops the context from building.
         *
         * <p>The rules are deliberately PROFILE-AGNOSTIC. A connection string is accepted only in
         * an Azurite emulator form, in every profile and in both modes: k8s/local runs profile
         * {@code prod}, so a profile-based rule would either break it or leave a hole, while
         * "no real account key anywhere" holds everywhere by construction.
         *
         * <p>Messages name the property and its env var, never a connection-string value, an
         * account name taken from one, or a key (threat T-36-03).
         *
         * @param env the environment lookup ({@code System::getenv} at runtime). The three
         *            {@code AZURE_*} workload-identity values are injected by the AKS webhook, so
         *            they are read here and never mapped from a config file.
         */
        public void validateShape(Function<String, String> env) {
            AuthMode mode = AuthMode.parse(authMode);

            if (mode == AuthMode.CONNECTION_STRING && isBlank(connectionString)) {
                throw new StorageConfigurationException("storage.blob.connection-string (STORAGE_CONNECTION_STRING) "
                        + "is required when storage.blob.auth-mode is connection-string");
            }
            // Checked in BOTH modes: in workload-identity mode the application.yml default (the
            // emulator shorthand) is ignored, but a real account key must not be configurable even
            // as an unused value.
            if (!isBlank(connectionString)) {
                validateEmulatorConnectionString(connectionString);
            }

            if (mode == AuthMode.WORKLOAD_IDENTITY) {
                validateWorkloadIdentity(env);
            }

            validateContainerName("storage.blob.public-container (STORAGE_PUBLIC_CONTAINER)", publicContainer);
            validateContainerName("storage.blob.quarantine-container (STORAGE_QUARANTINE_CONTAINER)", quarantineContainer);
            if (publicContainer.equals(quarantineContainer)) {
                throw new StorageConfigurationException("storage.blob.public-container and "
                        + "storage.blob.quarantine-container must differ: quarantined uploads must never share "
                        + "the anonymously readable container");
            }
            validatePublicUrl();

            if (maxTries < 1) {
                throw new StorageConfigurationException("storage.blob.max-tries (STORAGE_MAX_TRIES) must be at least 1, got "
                        + maxTries);
            }
            if (tryTimeoutSeconds < 1) {
                throw new StorageConfigurationException("storage.blob.try-timeout-seconds (STORAGE_TRY_TIMEOUT_SECONDS) "
                        + "must be at least 1, got " + tryTimeoutSeconds);
            }
        }

        /** The parsed auth mode. Throws for an unknown value, so callers may rely on it. */
        AuthMode authModeValue() {
            return AuthMode.parse(authMode);
        }

        private void validateWorkloadIdentity(Function<String, String> env) {
            if (endpoint == null || !ACCOUNT_ENDPOINT.matcher(endpoint).matches()) {
                throw new StorageConfigurationException("storage.blob.endpoint (STORAGE_ENDPOINT) must be "
                        + "https://<account>.blob.core.windows.net in workload-identity mode, got '" + endpoint + "'");
            }
            List<String> missing = new ArrayList<>();
            for (String name : WORKLOAD_IDENTITY_ENV) {
                if (isBlank(env.apply(name))) {
                    missing.add(name);
                }
            }
            if (!missing.isEmpty()) {
                throw new StorageConfigurationException("workload-identity mode needs "
                        + String.join(", ", WORKLOAD_IDENTITY_ENV)
                        + " (injected by the AKS workload-identity webhook when the pod's service account is "
                        + "annotated with the client id and the pod carries azure.workload.identity/use=true); missing: "
                        + String.join(", ", missing));
            }
            if (createContainers) {
                throw new StorageConfigurationException("storage.blob.create-containers (STORAGE_CREATE_CONTAINERS) "
                        + "must be false in workload-identity mode: Set Container ACL cannot be authorized with Entra ID, "
                        + "so the containers are provisioned out of band and only asserted at boot");
            }
        }

        /**
         * Accepts ONLY the two Azurite emulator forms. (1) {@code UseDevelopmentStorage=true} with
         * an optional {@code DevelopmentStorageProxyUri} that is a bare http host (the SDK appends
         * {@code :10000/devstoreaccount1} itself and silently drops a port or path). (2) The
         * {@code AccountName=devstoreaccount1} form Testcontainers' AzuriteContainer emits, with a
         * mandatory plain-http {@code BlobEndpoint} that addresses the emulator account path-style
         * and is not a real Azure host, so the emulator account can never be aimed at a real one.
         * Everything else, a real account name, a SAS, an endpoint suffix, is refused.
         */
        private static void validateEmulatorConnectionString(String raw) {
            Map<String, String> pairs = new LinkedHashMap<>();
            for (String segment : raw.split(";")) {
                String trimmed = segment.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    throw emulatorOnly("it has a segment that is not key=value");
                }
                String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                if (pairs.put(key, trimmed.substring(eq + 1).trim()) != null) {
                    throw emulatorOnly("it repeats the key '" + trimmed.substring(0, eq).trim() + "'");
                }
            }

            if (pairs.containsKey("usedevelopmentstorage")) {
                for (String key : pairs.keySet()) {
                    if (!SHORTHAND_KEYS.contains(key)) {
                        throw emulatorOnly("key '" + key + "' is not allowed with UseDevelopmentStorage");
                    }
                }
                if (!"true".equalsIgnoreCase(pairs.get("usedevelopmentstorage"))) {
                    throw emulatorOnly("UseDevelopmentStorage must be true");
                }
                String proxy = pairs.get("developmentstorageproxyuri");
                if (proxy != null) {
                    validateProxyUri(proxy);
                }
                return;
            }

            if (pairs.containsKey("accountname")) {
                // Compared without echoing the value: a real account name is not a secret, but it
                // identifies which estate a leaked key would open.
                if (!EMULATOR_ACCOUNT.equals(pairs.get("accountname"))) {
                    throw emulatorOnly("AccountName must be the emulator account " + EMULATOR_ACCOUNT);
                }
                for (String key : pairs.keySet()) {
                    if (!EMULATOR_ACCOUNT_KEYS.contains(key)) {
                        throw emulatorOnly("key '" + key + "' is not allowed with the emulator account");
                    }
                }
                String protocol = pairs.get("defaultendpointsprotocol");
                if (protocol != null && !"http".equalsIgnoreCase(protocol)) {
                    throw emulatorOnly("DefaultEndpointsProtocol must be http (the emulator speaks plain http)");
                }
                if (isBlank(pairs.get("accountkey"))) {
                    // The SDK itself rejects AccountName without AccountKey ("Invalid connection
                    // string"); refusing it here gives the operator the property name instead.
                    throw emulatorOnly("the emulator account form needs AccountKey (Azurite's published development key)");
                }
                String blobEndpoint = pairs.get("blobendpoint");
                if (blobEndpoint == null) {
                    throw emulatorOnly("the emulator account needs an explicit BlobEndpoint; without one the SDK "
                            + "targets " + EMULATOR_ACCOUNT + ".blob.core.windows.net in the public cloud");
                }
                validateEmulatorEndpoint("BlobEndpoint", blobEndpoint, true);
                for (String other : List.of("queueendpoint", "tableendpoint")) {
                    if (pairs.containsKey(other)) {
                        validateEmulatorEndpoint(other, pairs.get(other), false);
                    }
                }
                return;
            }

            throw emulatorOnly("only UseDevelopmentStorage=true or AccountName=" + EMULATOR_ACCOUNT + " is accepted");
        }

        private static void validateProxyUri(String proxy) {
            URI uri;
            try {
                uri = new URI(proxy);
            } catch (URISyntaxException e) {
                throw emulatorOnly("DevelopmentStorageProxyUri is not a URI");
            }
            if (!"http".equalsIgnoreCase(uri.getScheme()) || isBlank(uri.getHost()) || uri.getPort() != -1
                    || uri.getRawUserInfo() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    && !"/".equals(uri.getRawPath())) || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw emulatorOnly("DevelopmentStorageProxyUri must be a bare http host such as http://azurite "
                        + "(the SDK appends :10000/devstoreaccount1 itself and silently drops a port or a path)");
            }
        }

        private static void validateEmulatorEndpoint(String name, String value, boolean requireAccountPath) {
            URI uri;
            try {
                uri = new URI(value);
            } catch (URISyntaxException e) {
                throw emulatorOnly(name + " is not a URI");
            }
            String host = uri.getHost();
            if (!"http".equalsIgnoreCase(uri.getScheme()) || isBlank(host)) {
                throw emulatorOnly(name + " must be a plain-http emulator URL");
            }
            if (host.toLowerCase(Locale.ROOT).endsWith(".core.windows.net")) {
                throw emulatorOnly(name + " must not name a real Azure Storage host");
            }
            if (requireAccountPath) {
                String path = uri.getRawPath() == null ? "" : uri.getRawPath();
                if (!path.equals("/" + EMULATOR_ACCOUNT) && !path.startsWith("/" + EMULATOR_ACCOUNT + "/")) {
                    throw emulatorOnly(name + " must address the emulator account path-style (/" + EMULATOR_ACCOUNT + ")");
                }
            }
        }

        private static StorageConfigurationException emulatorOnly(String detail) {
            return new StorageConfigurationException("storage.blob.connection-string (STORAGE_CONNECTION_STRING) is "
                    + "emulator-only (D-02): " + detail + ". A real storage account is reached with "
                    + "auth-mode workload-identity, never a stored key or SAS.");
        }

        private static void validateContainerName(String property, String name) {
            if (name == null || !CONTAINER_NAME.matcher(name).matches()) {
                throw new StorageConfigurationException(property + " '" + name + "' is not a valid Blob container "
                        + "name (3-63 characters: lower-case letters, digits and single hyphens, starting and "
                        + "ending with a letter or digit)");
            }
        }

        private void validatePublicUrl() {
            URI uri = null;
            try {
                uri = isBlank(publicUrl) ? null : new URI(publicUrl.trim());
            } catch (URISyntaxException e) {
                // reported below
            }
            String scheme = uri == null ? null : uri.getScheme();
            if (uri == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || isBlank(uri.getHost())) {
                throw new StorageConfigurationException("storage.blob.public-url (STORAGE_PUBLIC_URL) must be an "
                        + "absolute http(s) URL, got '" + publicUrl + "'");
            }
            if (!publicUrl.trim().endsWith("/" + publicContainer)) {
                throw new StorageConfigurationException("storage.blob.public-url (STORAGE_PUBLIC_URL) must end with /"
                        + publicContainer + " (the public container), got '" + publicUrl + "'");
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

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

    /** The two ways of reaching Blob Storage ({@code storage.blob.auth-mode}, decision D-02). */
    enum AuthMode {
        CONNECTION_STRING("connection-string"),
        WORKLOAD_IDENTITY("workload-identity");

        final String value;

        AuthMode(String value) {
            this.value = value;
        }

        /**
         * Exact match only. The property is String-typed so an unknown value reaches this clear
         * message instead of a Spring binding error.
         */
        static AuthMode parse(String raw) {
            for (AuthMode mode : values()) {
                if (mode.value.equals(raw)) {
                    return mode;
                }
            }
            throw new StorageConfigurationException("storage.blob.auth-mode (STORAGE_AUTH_MODE) must be '"
                    + CONNECTION_STRING.value + "' or '" + WORKLOAD_IDENTITY.value + "', got '" + raw + "'");
        }
    }
}
