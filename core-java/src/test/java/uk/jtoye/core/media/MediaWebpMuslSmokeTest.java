package uk.jtoye.core.media;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave-0 A1 spike — the phase's #1 execution risk (RESEARCH Pitfall 1 /
 * Assumption A1). Proves the musl-native {@code cwebp} from Alpine's
 * {@code libwebp-tools} both EXECs and produces a VALID WebP inside the exact
 * runtime base image ({@code eclipse-temurin:21-jre-alpine}) — the environment
 * where scrimage-webp's BUNDLED glibc {@code cwebp} would fail to exec.
 *
 * <p>This is the GO/NO-GO gate for the Scrimage + {@code libwebp-tools} path
 * (the choice wired in Task 1: {@code apk add libwebp-tools} +
 * {@code -Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin}). If this test could
 * not produce a valid WebP on musl, the recorded fallback is to switch the
 * runtime base image to glibc {@code eclipse-temurin:21-jre} and use scrimage's
 * bundled {@code cwebp} (drop the apk + binary-dir override), then re-run this
 * smoke before proceeding. It PASSES on the Alpine base, so the Scrimage +
 * libwebp-tools path is LOCKED.
 *
 * <p>The complementary proof that scrimage-webp correctly invokes a
 * system/bundled {@code cwebp} from the JVM (the {@code webp.binary.dir}
 * delegation) runs on the glibc dev host in {@code MediaNormalizerTest}. This
 * container test isolates the single unknown: does system {@code cwebp} exec on
 * musl and emit RIFF/WEBP bytes. Maps to VALIDATION IMG-02 Wave-0.
 */
@Testcontainers
@Tag("testcontainers")
class MediaWebpMuslSmokeTest {

    /** RIFF container magic at bytes 0-3 of a valid WebP. */
    private static final byte[] RIFF_MAGIC = {0x52, 0x49, 0x46, 0x46};
    /** WEBP form-type magic at bytes 8-11 of a valid WebP. */
    private static final byte[] WEBP_MAGIC = {0x57, 0x45, 0x42, 0x50};

    /**
     * The runtime base image + the exact {@code apk add libwebp-tools} step from
     * {@code core-java/Dockerfile}. A long-lived idle command lets the test
     * {@code execInContainer} {@code cwebp} directly; the log marker gives a
     * version-independent, deterministic readiness signal (a no-port utility
     * container has no port to wait on).
     */
    private static final ImageFromDockerfile ALPINE_WEBP_IMAGE = new ImageFromDockerfile()
            .withDockerfileFromBuilder(b -> b
                    .from("eclipse-temurin:21-jre-alpine")
                    .run("apk add --no-cache libwebp-tools")
                    .cmd("sh", "-c", "echo CONTAINER_READY && sleep 600")
                    .build());

    @Container
    static final GenericContainer<?> ALPINE = new GenericContainer<>(ALPINE_WEBP_IMAGE)
            .waitingFor(Wait.forLogMessage(".*CONTAINER_READY.*", 1))
            .withStartupTimeout(Duration.ofMinutes(5));

    @Test
    void muslCwebpEncodesValidWebpInsideAlpineRuntimeImage() throws Exception {
        // (1) The binary must exist AND exec on musl — the exact failure mode a
        // glibc-linked bundled binary would hit ("not found" / loader error).
        GenericContainer.ExecResult version = ALPINE.execInContainer("cwebp", "-version");
        assertThat(version.getExitCode())
                .as("cwebp -version must exit 0 on musl (stderr=%s)", version.getStderr())
                .isZero();

        // (2) Round-trip encode a real JPEG to WebP inside the container.
        byte[] jpeg = sampleJpegBytes();
        ALPINE.copyFileToContainer(Transferable.of(jpeg), "/tmp/in.jpg");

        GenericContainer.ExecResult encode = ALPINE.execInContainer(
                "cwebp", "-q", "80", "/tmp/in.jpg", "-o", "/tmp/out.webp");
        assertThat(encode.getExitCode())
                .as("cwebp encode must exit 0 (stdout=%s stderr=%s)",
                        encode.getStdout(), encode.getStderr())
                .isZero();

        // (3) The output must be a VALID WebP (RIFF....WEBP magic), not a loader
        // stub or an empty/error file.
        byte[] webp = ALPINE.copyFileFromContainer("/tmp/out.webp", InputStream::readAllBytes);
        assertThat(webp).as("WebP output must be non-trivial").hasSizeGreaterThan(12);
        assertThat(webp)
                .as("bytes 0-3 must be RIFF")
                .startsWith(RIFF_MAGIC);
        assertThat(Arrays.copyOfRange(webp, 8, 12))
                .as("bytes 8-11 must be WEBP")
                .isEqualTo(WEBP_MAGIC);
    }

    /** A small, real JPEG built on the (glibc) host to feed the container encode. */
    private static byte[] sampleJpegBytes() throws Exception {
        BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 128, 128);
        g.setColor(Color.BLACK);
        g.fillOval(24, 24, 80, 80);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, "jpg", baos)).as("host must have a JPEG writer").isTrue();
        return baos.toByteArray();
    }
}
