package uk.jtoye.core.gdpr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.jtoye.core.boot4.GoldenSamples;
import uk.jtoye.core.gdpr.dto.DsarIntakeRequest;
import uk.jtoye.core.testsupport.BootJsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOOT4-08 (Phase 38, plan 38-10): the DSAR acknowledgement a Boot-3.5 pod stored in
 * {@code dsar_request.response_body} still replays after the deploy, through
 * {@link DsarIntakeService} on Boot's Jackson-3 {@code JsonMapper}.
 *
 * <p>{@code dsar_request} keeps its own idempotency (V62): a keyed retry whose insert conflicts
 * answers from the stored row, by deserializing {@code response_body}. The fixture
 * {@code jackson2-golden/responses/DsarIntakeAck.json} is that body as Boot 3.5.16's
 * {@code ObjectMapper} wrote it (38-01, capture commit {@code e12177e1}). This test drives the real
 * replay path, {@code lodge -> replay -> deserialize}, against a JDBC stub that answers like
 * Postgres: the reserve inserts 0 rows (key already taken) and the read returns the Boot-3.5 row.
 *
 * <p>The reverse direction is checked too: the body the service writes for a fresh request is the
 * Boot-3.5 bytes, so an old pod can still replay a row a new pod stored.
 */
class DsarAckCompatibilityTest {

    private static final Path FIXTURE =
            Path.of("src", "test", "resources", "jackson2-golden", "responses", "DsarIntakeAck.json");

    private static final DsarIntakeRequest REQUEST =
            new DsarIntakeRequest("subject@example.test", DsarRequest.RequestType.ACCESS);

    @Test
    @DisplayName("a Boot-3.5 stored DSAR acknowledgement replays unchanged through the service on Boot's Jackson-3 mapper")
    void boot35StoredAckReplaysUnchanged() throws IOException {
        String storedHash = DsarSubjectDigest.sha256Hex(
                DsarSubjectDigest.of(REQUEST.email()) + "|" + REQUEST.requestType().name());
        StubJdbc jdbc = new StubJdbc(0, Map.of(
                "request_hash", storedHash,
                "response_status", 202,
                "response_body", Files.readString(FIXTURE, StandardCharsets.UTF_8)));

        DsarIntakeService.DsarIntakeAck replayed = service(jdbc).lodge(REQUEST, "dsar-boot35-key-3810");

        assertThat(jdbc.reads).as("the replay path read the stored row").isEqualTo(1);
        assertThat(replayed).isEqualTo(GoldenSamples.dsarIntakeAck());
    }

    @Test
    @DisplayName("the acknowledgement the service stores for a fresh request is the Boot-3.5 bytes")
    void freshAckIsStoredInTheBoot35Bytes() throws IOException {
        StubJdbc jdbc = new StubJdbc(1, Map.of());

        service(jdbc).lodge(REQUEST, "dsar-fresh-key-3810");

        assertThat(jdbc.updates).as("one reserve insert").hasSize(1);
        Object[] args = jdbc.updates.get(0);
        assertThat(args[args.length - 1])
                .as("response_body as stored vs the Boot-3.5 bytes")
                .isEqualTo(Files.readString(FIXTURE, StandardCharsets.UTF_8));
    }

    /** The service on Boot's Jackson-3 mapper, the bean the application injects. */
    private static DsarIntakeService service(JdbcTemplate jdbc) {
        return new DsarIntakeService(jdbc, BootJsonMapper.get(), new NoMail());
    }

    /** A JdbcTemplate that answers the two statements the intake issues, and records them. */
    static final class StubJdbc extends JdbcTemplate {
        private final int insertedRows;
        private final Map<String, Object> storedRow;
        final List<Object[]> updates = new ArrayList<>();
        int reads;

        StubJdbc(int insertedRows, Map<String, Object> storedRow) {
            this.insertedRows = insertedRows;
            this.storedRow = storedRow;
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(args);
            return insertedRows;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            reads++;
            return storedRow.isEmpty() ? List.of() : List.of(storedRow);
        }
    }

    /** The verification mail is not under test; a replay sends none and a fresh lodge may. */
    static final class NoMail extends DsarVerificationMailer {
        NoMail() {
            super(null);
        }

        @Override
        public void sendVerification(String recipientEmail, String token,
                                     DsarRequest.RequestType requestType, long ttlHours) {
            // intentionally nothing
        }
    }
}
