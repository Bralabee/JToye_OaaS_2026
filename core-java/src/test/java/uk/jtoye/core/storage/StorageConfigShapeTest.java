package uk.jtoye.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Shape rules for {@code storage.blob.*} (Phase 36, decision D-02): every misconfiguration must be
 * refused while the Blob client bean is built, before any network call, and never at the first
 * upload. One test per rule, plus the valid shapes each runtime actually uses.
 *
 * <p>The environment lookup is an in-memory map, so the workload-identity cases never depend on
 * the JVM's real environment.
 *
 * <p>The emulator-only rule is PROFILE-AGNOSTIC on purpose: k8s/local runs profile {@code prod}
 * (k8s/base/core-java-deployment.yaml), so a "refuse connection strings under prod" rule would
 * break k8s/local, while "refuse any non-emulator connection string everywhere" is strictly
 * stronger for D-02.
 */
class StorageConfigShapeTest {

    /**
     * A value that stands for a real account key. It must never appear in a message. Built at run
     * time from a short piece so no secret-shaped literal sits in the source (gitleaks
     * generic-api-key flagged the original literal; its commit is fingerprinted in .gitleaksignore).
     */
    private static final String REAL_KEY = "Rk9P".repeat(10) + "==";

    private static StorageProperties.Blob emulator(String connectionString) {
        StorageProperties.Blob blob = new StorageProperties().getBlob();
        blob.setAuthMode("connection-string");
        blob.setConnectionString(connectionString);
        return blob;
    }

    private static StorageProperties.Blob workloadIdentity(String endpoint) {
        StorageProperties.Blob blob = new StorageProperties().getBlob();
        blob.setAuthMode("workload-identity");
        blob.setEndpoint(endpoint);
        return blob;
    }

    private static Map<String, String> fullWorkloadEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("AZURE_CLIENT_ID", "00000000-0000-0000-0000-00000000c11e");
        env.put("AZURE_TENANT_ID", "00000000-0000-0000-0000-0000000007e7");
        env.put("AZURE_FEDERATED_TOKEN_FILE", "/var/run/secrets/azure/tokens/azure-identity-token");
        return env;
    }

    private static final Function<String, String> NO_ENV = name -> null;

    private static StorageConfigurationException refused(StorageProperties.Blob blob, Function<String, String> env) {
        return assertThrows(StorageConfigurationException.class, () -> blob.validateShape(env));
    }

    // ---- connection-string mode: the emulator forms that are accepted -------------------------

    @Test
    @DisplayName("connection-string: the bare emulator shorthand is valid")
    void bareEmulatorShorthandIsValid() {
        assertDoesNotThrow(() -> emulator("UseDevelopmentStorage=true").validateShape(NO_ENV));
    }

    @Test
    @DisplayName("connection-string: the emulator shorthand with an in-network proxy host is valid (compose)")
    void emulatorWithProxyIsValid() {
        assertDoesNotThrow(() -> emulator("UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite")
                .validateShape(NO_ENV));
    }

    @Test
    @DisplayName("connection-string: the devstoreaccount1 form Testcontainers' AzuriteContainer emits is valid")
    void testcontainersFormIsValid() {
        String testcontainersForm = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=eA==;"
                + "BlobEndpoint=http://localhost:32768/devstoreaccount1;"
                + "QueueEndpoint=http://localhost:32769/devstoreaccount1;"
                + "TableEndpoint=http://localhost:32770/devstoreaccount1";
        assertDoesNotThrow(() -> emulator(testcontainersForm).validateShape(NO_ENV));
    }

    @Test
    @DisplayName("connection-string: devstoreaccount1 with only a key and an endpoint is valid (the closed-port test's form)")
    void minimalEmulatorAccountFormIsValid() {
        assertDoesNotThrow(() -> emulator("AccountName=devstoreaccount1;AccountKey=eA==;BlobEndpoint=http://127.0.0.1:45678/devstoreaccount1")
                .validateShape(NO_ENV));
    }

    @Test
    @DisplayName("connection-string: devstoreaccount1 without AccountKey is refused here, not later by the SDK")
    void emulatorAccountWithoutKeyIsRefused() {
        StorageConfigurationException e = refused(emulator(
                "AccountName=devstoreaccount1;BlobEndpoint=http://127.0.0.1:45678/devstoreaccount1"), NO_ENV);
        assertThat(e.getMessage()).contains("emulator-only").contains("AccountKey");
    }

    // ---- connection-string mode: everything else is refused -----------------------------------

    @Test
    @DisplayName("connection-string: a real account name is refused as emulator-only, and the key is never echoed")
    void realAccountIsRefusedWithoutEchoingTheKey() {
        StorageConfigurationException e = refused(emulator(
                "DefaultEndpointsProtocol=https;AccountName=jtoyestgmedia;AccountKey=" + REAL_KEY
                        + ";EndpointSuffix=core.windows.net"), NO_ENV);
        assertThat(e.getMessage()).contains("emulator-only").contains("STORAGE_CONNECTION_STRING")
                .doesNotContain(REAL_KEY).doesNotContain("jtoyestgmedia");
    }

    @Test
    @DisplayName("connection-string: a shared access signature is refused as emulator-only")
    void sharedAccessSignatureIsRefused() {
        StorageConfigurationException e = refused(emulator(
                "BlobEndpoint=https://jtoyestgmedia.blob.core.windows.net;SharedAccessSignature=sv=2026-06-06&sig=abc"), NO_ENV);
        assertThat(e.getMessage()).contains("emulator-only").doesNotContain("sig=abc");
    }

    @Test
    @DisplayName("connection-string: devstoreaccount1 aimed at a real *.blob.core.windows.net host is refused")
    void emulatorAccountAimedAtRealHostIsRefused() {
        StorageConfigurationException e = refused(emulator(
                "AccountName=devstoreaccount1;AccountKey=eA==;BlobEndpoint=http://jtoyestgmedia.blob.core.windows.net/devstoreaccount1"),
                NO_ENV);
        assertThat(e.getMessage()).contains("emulator-only");
    }

    @Test
    @DisplayName("connection-string: devstoreaccount1 over https is refused (the emulator speaks plain http)")
    void emulatorAccountOverHttpsIsRefused() {
        StorageConfigurationException e = refused(emulator(
                "AccountName=devstoreaccount1;AccountKey=eA==;BlobEndpoint=https://10.0.0.5:10000/devstoreaccount1"), NO_ENV);
        assertThat(e.getMessage()).contains("emulator-only");
    }

    @Test
    @DisplayName("connection-string: devstoreaccount1 with no BlobEndpoint is refused (the SDK would target the public cloud)")
    void emulatorAccountWithoutEndpointIsRefused() {
        StorageConfigurationException e = refused(emulator("AccountName=devstoreaccount1;AccountKey=eA=="), NO_ENV);
        assertThat(e.getMessage()).contains("emulator-only").contains("BlobEndpoint");
    }

    @ParameterizedTest(name = "DevelopmentStorageProxyUri={0} is refused")
    @ValueSource(strings = {"https://azurite", "http://azurite:10000", "http://azurite/path"})
    @DisplayName("connection-string: a proxy URI that is not a bare http host is refused")
    void malformedProxyIsRefused(String proxy) {
        StorageConfigurationException e = refused(emulator(
                "UseDevelopmentStorage=true;DevelopmentStorageProxyUri=" + proxy), NO_ENV);
        assertThat(e.getMessage()).contains("DevelopmentStorageProxyUri");
    }

    @Test
    @DisplayName("connection-string: a blank string is refused, naming STORAGE_CONNECTION_STRING")
    void blankConnectionStringIsRefused() {
        StorageConfigurationException e = refused(emulator("  "), NO_ENV);
        assertThat(e.getMessage()).contains("STORAGE_CONNECTION_STRING").contains("storage.blob.connection-string");
    }

    // ---- auth-mode -----------------------------------------------------------------------------

    @ParameterizedTest(name = "auth-mode ''{0}'' is refused")
    @ValueSource(strings = {"bogus", "", "Connection-String"})
    @DisplayName("auth-mode: anything but the two values is refused, naming both")
    void unknownAuthModeIsRefused(String mode) {
        StorageProperties.Blob blob = emulator("UseDevelopmentStorage=true");
        blob.setAuthMode(mode);
        StorageConfigurationException e = refused(blob, NO_ENV);
        assertThat(e.getMessage()).contains("connection-string").contains("workload-identity")
                .contains("STORAGE_AUTH_MODE");
    }

    // ---- workload-identity mode ----------------------------------------------------------------

    @Test
    @DisplayName("workload-identity: an https account endpoint with the three webhook env values is valid")
    void workloadIdentityIsValid() {
        Map<String, String> env = fullWorkloadEnv();
        assertDoesNotThrow(() -> workloadIdentity("https://jtoyestgmedia.blob.core.windows.net").validateShape(env::get));
        assertDoesNotThrow(() -> workloadIdentity("https://jtoyeprodmedia.blob.core.windows.net/").validateShape(env::get));
    }

    @ParameterizedTest(name = "endpoint {0} is refused")
    @ValueSource(strings = {"http://jtoyestgmedia.blob.core.windows.net", "https://example.com", "",
            "https://jtoyestgmedia.blob.core.windows.net/jtoye-images"})
    @DisplayName("workload-identity: an endpoint that is not https://<account>.blob.core.windows.net is refused")
    void workloadIdentityBadEndpointIsRefused(String endpoint) {
        Map<String, String> env = fullWorkloadEnv();
        StorageConfigurationException e = refused(workloadIdentity(endpoint), env::get);
        assertThat(e.getMessage()).contains("STORAGE_ENDPOINT");
    }

    @ParameterizedTest(name = "missing {0} is refused and named")
    @ValueSource(strings = {"AZURE_CLIENT_ID", "AZURE_TENANT_ID", "AZURE_FEDERATED_TOKEN_FILE"})
    @DisplayName("workload-identity: each webhook-injected env value is required and named when missing")
    void workloadIdentityMissingEnvIsRefused(String missing) {
        Map<String, String> env = fullWorkloadEnv();
        env.put(missing, " ");
        StorageConfigurationException e = refused(workloadIdentity("https://jtoyestgmedia.blob.core.windows.net"), env::get);
        assertThat(e.getMessage()).contains("missing: " + missing);
    }

    @Test
    @DisplayName("workload-identity: create-containers is refused (Set Container ACL cannot use Entra ID)")
    void workloadIdentityCreateContainersIsRefused() {
        Map<String, String> env = fullWorkloadEnv();
        StorageProperties.Blob blob = workloadIdentity("https://jtoyestgmedia.blob.core.windows.net");
        blob.setCreateContainers(true);
        StorageConfigurationException e = refused(blob, env::get);
        assertThat(e.getMessage()).contains("Set Container ACL cannot be authorized with Entra ID")
                .contains("STORAGE_CREATE_CONTAINERS");
    }

    @Test
    @DisplayName("workload-identity: a real-account connection string alongside is still refused (no key anywhere)")
    void workloadIdentityWithRealKeyAlongsideIsRefused() {
        Map<String, String> env = fullWorkloadEnv();
        StorageProperties.Blob blob = workloadIdentity("https://jtoyestgmedia.blob.core.windows.net");
        blob.setConnectionString("DefaultEndpointsProtocol=https;AccountName=jtoyestgmedia;AccountKey=" + REAL_KEY);
        StorageConfigurationException e = refused(blob, env::get);
        assertThat(e.getMessage()).contains("emulator-only").doesNotContain(REAL_KEY);
    }

    // ---- containers, public URL, retry budget --------------------------------------------------

    @ParameterizedTest(name = "public container ''{0}'' is refused")
    @ValueSource(strings = {"Jtoye_Images", "a", "ab--cd", "-jtoye", "jtoye-"})
    @DisplayName("containers: a name outside the Blob naming rules is refused")
    void invalidContainerNameIsRefused(String name) {
        StorageProperties.Blob blob = emulator("UseDevelopmentStorage=true");
        blob.setPublicContainer(name);
        blob.setPublicUrl("http://localhost:10000/devstoreaccount1/" + name);
        StorageConfigurationException e = refused(blob, NO_ENV);
        assertThat(e.getMessage()).contains("storage.blob.public-container");
    }

    @Test
    @DisplayName("containers: an invalid quarantine container name is refused")
    void invalidQuarantineNameIsRefused() {
        StorageProperties.Blob blob = emulator("UseDevelopmentStorage=true");
        blob.setQuarantineContainer("Quarantine");
        StorageConfigurationException e = refused(blob, NO_ENV);
        assertThat(e.getMessage()).contains("storage.blob.quarantine-container");
    }

    @Test
    @DisplayName("containers: the public and quarantine containers must differ")
    void sameContainerTwiceIsRefused() {
        StorageProperties.Blob blob = emulator("UseDevelopmentStorage=true");
        blob.setQuarantineContainer(blob.getPublicContainer());
        StorageConfigurationException e = refused(blob, NO_ENV);
        assertThat(e.getMessage()).contains("must differ");
    }

    @Test
    @DisplayName("public-url: a URL not ending in /<public-container> is refused")
    void publicUrlForAnotherContainerIsRefused() {
        StorageProperties.Blob blob = emulator("UseDevelopmentStorage=true");
        blob.setPublicUrl("http://localhost:10000/devstoreaccount1/other");
        StorageConfigurationException e = refused(blob, NO_ENV);
        assertThat(e.getMessage()).contains("storage.blob.public-url").contains("/jtoye-images");
    }

    @ParameterizedTest(name = "public-url ''{0}'' is refused")
    @ValueSource(strings = {"/devstoreaccount1/jtoye-images", "ftp://h/jtoye-images", "jtoye-images", ""})
    @DisplayName("public-url: a URL that is not absolute http(s) is refused")
    void relativePublicUrlIsRefused(String url) {
        StorageProperties.Blob blob = emulator("UseDevelopmentStorage=true");
        blob.setPublicUrl(url);
        StorageConfigurationException e = refused(blob, NO_ENV);
        assertThat(e.getMessage()).contains("storage.blob.public-url");
    }

    @Test
    @DisplayName("public-url: a different host than the endpoint is allowed (split horizon)")
    void splitHorizonPublicUrlIsValid() {
        Map<String, String> env = fullWorkloadEnv();
        StorageProperties.Blob blob = workloadIdentity("https://jtoyestgmedia.blob.core.windows.net");
        blob.setPublicUrl("https://media.example.co.uk/jtoye-images");
        assertDoesNotThrow(() -> blob.validateShape(env::get));
    }

    @Test
    @DisplayName("retry: max-tries below 1 is refused")
    void zeroMaxTriesIsRefused() {
        StorageProperties.Blob blob = emulator("UseDevelopmentStorage=true");
        blob.setMaxTries(0);
        StorageConfigurationException e = refused(blob, NO_ENV);
        assertThat(e.getMessage()).contains("storage.blob.max-tries");
    }

    @Test
    @DisplayName("retry: try-timeout-seconds below 1 is refused")
    void zeroTryTimeoutIsRefused() {
        StorageProperties.Blob blob = emulator("UseDevelopmentStorage=true");
        blob.setTryTimeoutSeconds(0);
        StorageConfigurationException e = refused(blob, NO_ENV);
        assertThat(e.getMessage()).contains("storage.blob.try-timeout-seconds");
    }

    // ---- the rule runs inside the bean factory, before the client is built -------------------

    @Test
    @DisplayName("StorageConfig refuses a non-emulator connection string while building the client bean")
    void beanFactoryRunsTheShapeRules() {
        StorageProperties properties = new StorageProperties();
        properties.getBlob().setAuthMode("connection-string");
        properties.getBlob().setConnectionString("DefaultEndpointsProtocol=https;AccountName=jtoyestgmedia;AccountKey=" + REAL_KEY);
        StorageConfigurationException e = assertThrows(StorageConfigurationException.class,
                () -> new StorageConfig().blobServiceClient(properties));
        assertThat(e.getMessage()).contains("emulator-only").doesNotContain(REAL_KEY);
    }
}
