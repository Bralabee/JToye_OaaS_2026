package uk.jtoye.core.storage;

import com.azure.storage.blob.BlobServiceClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Assumption A1 of 36-RESEARCH, proven rather than assumed: the Workload Identity credential works
 * with {@code msal4j-persistence-extension} and the {@code net.java.dev.jna} group excluded from
 * azure-identity (core-java/build.gradle.kts), and JNA is genuinely absent from what ships.
 *
 * <p><b>Why a separate class loader.</b> The TEST classpath is not the shipped one: Testcontainers'
 * docker-java transport brings {@code net.java.dev.jna:jna} onto it, so {@code Class.forName} in
 * this JVM finds JNA even though the application jar has none (measured: 0 jna lines in
 * {@code runtimeClasspath}, one {@code jna:5.13.0} under docker-java-transport-zerodep in
 * {@code testRuntimeClasspath}). {@code tasks.test} therefore hands this test the resolved
 * production {@code runtimeClasspath}, and every A1 assertion runs in a loader made of ONLY those
 * jars over the platform loader.
 *
 * <p>It can fail: remove the jna-group exclusion from build.gradle.kts and
 * {@link #jnaIsNotOnTheProductionRuntimeClasspath()} goes red. The control arm
 * {@link #theProbeCanSeeAJnaLinkageFailure()} shows the token-path assertion is able to see a JNA
 * linkage error when one exists.
 */
class WorkloadIdentityCredentialBuildTest {

    private static final String PRODUCTION_CLASSPATH_PROPERTY = "jtoye.productionRuntimeClasspath";
    private static final String CLIENT_ID = "00000000-0000-0000-0000-00000000c11e";
    private static final String TENANT_ID = "00000000-0000-0000-0000-0000000007e7";

    private static URLClassLoader production;

    @BeforeAll
    static void productionRuntimeLoader() throws MalformedURLException {
        String classpath = System.getProperty(PRODUCTION_CLASSPATH_PROPERTY);
        // Fail closed: without the property this class could only test the test classpath.
        assertThat(classpath).as("-D" + PRODUCTION_CLASSPATH_PROPERTY
                + " is set by core-java/build.gradle.kts tasks.test; run this class through Gradle").isNotBlank();
        List<URL> urls = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        production = new URLClassLoader("production-runtime", urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader());
    }

    @AfterAll
    static void close() throws Exception {
        if (production != null) {
            production.close();
        }
    }

    @Test
    @DisplayName("JNA is not on the production runtime classpath, while azure-identity is (positive control)")
    void jnaIsNotOnTheProductionRuntimeClasspath() throws Exception {
        assertThat(Class.forName("com.azure.identity.WorkloadIdentityCredentialBuilder", false, production).getClassLoader())
                .as("the loader must hold the production jars, or the absence below proves nothing")
                .isSameAs(production);
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.sun.jna.Native", false, production));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.sun.jna.platform.win32.Crypt32Util", false, production));
        assertThat(Arrays.stream(production.getURLs()).map(URL::getPath))
                .noneMatch(path -> path.contains("/net.java.dev.jna/") || path.matches(".*/jna(-platform)?-[0-9.]+\\.jar"))
                .noneMatch(path -> path.contains("msal4j-persistence-extension"));
    }

    @Test
    @DisplayName("WorkloadIdentityCredential builds from the production classpath alone")
    void credentialBuildsFromTheProductionClasspath(@TempDir Path dir) throws Exception {
        Object credential = workloadIdentityCredential(tokenFile(dir), null);

        assertThat(credential).isNotNull();
        assertThat(credential.getClass().getName()).isEqualTo("com.azure.identity.WorkloadIdentityCredential");
        assertThat(credential.getClass().getClassLoader()).isSameAs(production);
    }

    @Test
    @DisplayName("a token request walks the whole credential path to a network failure, never a linkage error")
    void tokenPathHasNoLinkageError(@TempDir Path dir) throws Exception {
        Object credential = workloadIdentityCredential(tokenFile(dir), "https://127.0.0.1:" + closedPort() + "/");

        Throwable failure = assertTimeoutPreemptively(Duration.ofSeconds(90), () -> requestToken(credential));

        assertThat(failure).as("the closed authority port must make the request fail").isNotNull();
        assertThat(causalChain(failure)).noneMatch(t -> t instanceof LinkageError);
    }

    @Test
    @DisplayName("control arm: a JNA-dependent credential in the same loader DOES surface a linkage error")
    void theProbeCanSeeAJnaLinkageFailure() throws Exception {
        Class<?> builderClass = Class.forName("com.azure.identity.AzurePowerShellCredentialBuilder", true, production);
        Object builder = builderClass.getConstructor().newInstance();
        Object credential = builderClass.getMethod("build").invoke(builder);

        Throwable failure = assertTimeoutPreemptively(Duration.ofSeconds(90), () -> requestToken(credential));

        assertThat(causalChain(failure)).anyMatch(t -> t instanceof NoClassDefFoundError
                && String.valueOf(t.getMessage()).contains("com/sun/jna"));
    }

    @Test
    @DisplayName("StorageConfig builds a workload-identity client from the env lookup alone, aimed at the endpoint")
    void storageConfigBuildsTheWorkloadIdentityClient(@TempDir Path dir) throws Exception {
        Map<String, String> env = Map.of(
                "AZURE_CLIENT_ID", CLIENT_ID,
                "AZURE_TENANT_ID", TENANT_ID,
                "AZURE_FEDERATED_TOKEN_FILE", tokenFile(dir).toString());
        StorageProperties properties = new StorageProperties();
        properties.getBlob().setAuthMode("workload-identity");
        properties.getBlob().setEndpoint("https://jtoyestgmedia.blob.core.windows.net");
        // application.yml's emulator default stays set in this mode; it is ignored, not refused.
        properties.getBlob().setConnectionString("UseDevelopmentStorage=true");

        BlobServiceClient client = StorageConfig.buildClient(properties, env::get);

        assertThat(client.getAccountUrl()).isEqualTo("https://jtoyestgmedia.blob.core.windows.net");
        assertThat(client.getAccountName()).isEqualTo("jtoyestgmedia");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static Path tokenFile(Path dir) throws Exception {
        return Files.writeString(dir.resolve("azure-identity-token"), "not-a-real-token");
    }

    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Builds a WorkloadIdentityCredential by reflection, entirely inside the production loader. */
    private static Object workloadIdentityCredential(Path tokenFile, String authorityHost) throws Exception {
        Class<?> builderClass = Class.forName("com.azure.identity.WorkloadIdentityCredentialBuilder", true, production);
        Object builder = builderClass.getConstructor().newInstance();
        builderClass.getMethod("clientId", String.class).invoke(builder, CLIENT_ID);
        builderClass.getMethod("tenantId", String.class).invoke(builder, TENANT_ID);
        builderClass.getMethod("tokenFilePath", String.class).invoke(builder, tokenFile.toString());
        if (authorityHost != null) {
            // A token request against a closed authority port: no instance-discovery round trip
            // and no retries, so the request fails once, fast, on the network.
            builderClass.getMethod("authorityHost", String.class).invoke(builder, authorityHost);
            builderClass.getMethod("disableInstanceDiscovery").invoke(builder);
            builderClass.getMethod("maxRetry", int.class).invoke(builder, 0);
        }
        return builderClass.getMethod("build").invoke(builder);
    }

    /** Calls {@code getTokenSync} inside the production loader; returns what it threw, or null. */
    private static Throwable requestToken(Object credential) throws Exception {
        Class<?> contextClass = Class.forName("com.azure.core.credential.TokenRequestContext", true, production);
        Object context = contextClass.getConstructor().newInstance();
        contextClass.getMethod("addScopes", String[].class)
                .invoke(context, (Object) new String[]{"https://storage.azure.com/.default"});
        Method getTokenSync = credential.getClass().getMethod("getTokenSync", contextClass);
        try {
            getTokenSync.invoke(credential, context);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    private static List<Throwable> causalChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = failure; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
            chain.addAll(Arrays.asList(t.getSuppressed()));
        }
        return chain;
    }
}
