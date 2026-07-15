package uk.jtoye.core.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeUtility;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared Mailhog assertion helper for the Phase 22 email-landing integration
 * tests (COMMS-01/02). Mailhog captures every SMTP message the dev/E2E stack
 * sends ({@code :1025} SMTP, {@code :8025} HTTP API) so a test can prove a real
 * email reached a specific recipient with a specific subject, rather than only
 * asserting a mock was invoked.
 *
 * <p>Talks to the Mailhog v2 messages API
 * ({@code GET http://<host>:8025/api/v2/messages}) with a poll-until-timeout
 * loop, because SMTP ingestion is asynchronous to the sending call. The Mailhog
 * host/port is overridable via the {@code mailhog.http-url} system property so
 * the same helper works against compose ({@code localhost:8025}) or any other
 * reachable Mailhog.
 *
 * <p>Not a Spring bean — a plain test utility instantiated per test class.
 */
public final class MailhogAssertions {

    private static final String DEFAULT_BASE_URL = "http://localhost:8025";

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public MailhogAssertions(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Mailhog at the {@code mailhog.http-url} system property, else {@code http://localhost:8025}. */
    public static MailhogAssertions atDefault() {
        return new MailhogAssertions(System.getProperty("mailhog.http-url", DEFAULT_BASE_URL));
    }

    /** True when the Mailhog HTTP API answers — lets a test skip gracefully when the dev stack is absent. */
    public boolean isReachable() {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/v2/messages?limit=1"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Delete every captured message (Mailhog v1 API) so each test starts clean. */
    public void clear() {
        try {
            http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/messages"))
                    .timeout(Duration.ofSeconds(3)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to clear Mailhog at " + baseUrl, e);
        }
    }

    /** All captured messages, as simplified {recipient, subject} pairs. */
    public List<Captured> allMessages() {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/v2/messages?limit=200"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Mailhog returned HTTP " + resp.statusCode());
            }
            List<Captured> out = new ArrayList<>();
            JsonNode items = mapper.readTree(resp.body()).path("items");
            for (JsonNode item : items) {
                JsonNode headers = item.path("Content").path("Headers");
                String subject = firstHeader(headers, "Subject");
                for (JsonNode to : headers.path("To")) {
                    // "To" header may be a comma-joined list of addresses.
                    for (String addr : to.asText().split(",")) {
                        out.add(new Captured(addr.trim(), subject));
                    }
                }
            }
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read Mailhog messages at " + baseUrl, e);
        }
    }

    /** Messages to {@code recipient} whose subject contains {@code subjectSubstring} (both case-sensitive on address). */
    public List<Captured> messagesTo(String recipient, String subjectSubstring) {
        List<Captured> out = new ArrayList<>();
        for (Captured c : allMessages()) {
            if (c.recipient().equalsIgnoreCase(recipient)
                    && (subjectSubstring == null || c.subject().contains(subjectSubstring))) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Poll until AT LEAST one message to {@code recipient} with a subject
     * containing {@code subjectSubstring} is captured, or fail after
     * {@code timeout}. Returns the matches.
     */
    public List<Captured> awaitMessage(String recipient, String subjectSubstring, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        List<Captured> matches = List.of();
        while (System.currentTimeMillis() < deadline) {
            matches = messagesTo(recipient, subjectSubstring);
            if (!matches.isEmpty()) {
                return matches;
            }
            sleep(150);
        }
        fail("Mailhog never received a message to '" + recipient + "' with subject containing '"
                + subjectSubstring + "' within " + timeout);
        return matches; // unreachable
    }

    /** Assert NO message was sent to {@code recipient} (used to prove the vendor path does not duplicate the customer). */
    public void assertNoMessageTo(String recipient) {
        List<Captured> matches = messagesTo(recipient, null);
        assertTrue(matches.isEmpty(),
                "expected NO message to '" + recipient + "' but found " + matches.size());
    }

    private static String firstHeader(JsonNode headers, String name) {
        JsonNode arr = headers.path(name);
        String raw = arr.isArray() && arr.size() > 0 ? arr.get(0).asText() : "";
        // Mailhog stores the RAW header. A subject containing non-ASCII (e.g. the
        // em-dash in "Order X — an update") is RFC 2047 word-encoded, so a plain
        // substring match on the raw value would miss (Q-encoding turns spaces
        // into '_'). Decode back to the human subject before matching.
        try {
            return MimeUtility.decodeText(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A captured Mailhog message, reduced to the two fields the tests assert on. */
    public record Captured(String recipient, String subject) {
    }
}
