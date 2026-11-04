package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.order.OrderTimelineRepository.RevisionRow;
import uk.jtoye.core.order.dto.OrderTimelineEventDto;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #857: the rules that turn consecutive audit revisions into the changes a vendor reads. */
class OrderTimelineServiceTest {

    private record Row(Integer rev, Short revtype, String status, String paymentStatus, Boolean voided,
                       String voidReason, String cancelReasonCode, Long revtstmp, String userId,
                       String displayName, String email) implements RevisionRow {
        public Integer getRev() { return rev; }
        public Short getRevtype() { return revtype; }
        public String getStatus() { return status; }
        public String getPaymentStatus() { return paymentStatus; }
        public Boolean getVoided() { return voided; }
        public String getVoidReason() { return voidReason; }
        public String getCancelReasonCode() { return cancelReasonCode; }
        public Long getRevtstmp() { return revtstmp; }
        public String getUserId() { return userId; }
        public String getDisplayName() { return displayName; }
        public String getEmail() { return email; }
    }

    private static Row row(int rev, int revtype, String status, String payment, boolean voided, long at,
                           String userId, String name) {
        return new Row(rev, (short) revtype, status, payment, voided, voided ? "bad batch" : null, null, at,
                userId, name, null);
    }

    @Test
    @DisplayName("an order's life becomes placed, then one STATUS event per change, each with its own actor and time")
    void statusChangesCarryWhoAndWhen() {
        List<OrderTimelineEventDto> events = OrderTimelineService.build(List.of(
                row(1, 0, "PENDING", "NONE", false, 1_000, null, null),
                row(2, 1, "CONFIRMED", "NONE", false, 2_000, "u-ade", "Ade Cook"),
                row(3, 1, "PREPARING", "NONE", false, 3_000, "u-bola", "Bola Cook"),
                row(4, 1, "READY", "NONE", false, 4_000, "u-bola", "Bola Cook")));

        assertThat(events).extracting(OrderTimelineEventDto::kind)
                .containsExactly("PLACED", "STATUS", "STATUS", "STATUS");
        assertThat(events).extracting(OrderTimelineEventDto::status)
                .containsExactly("PENDING", "CONFIRMED", "PREPARING", "READY");
        assertThat(events.get(2).fromStatus()).isEqualTo("CONFIRMED");
        assertThat(events.get(2).actorName()).isEqualTo("Bola Cook");
        assertThat(events.get(2).at()).isEqualTo(Instant.ofEpochMilli(3_000));
        // Nobody was signed in when the customer placed it.
        assertThat(events.get(0).actorType()).isEqualTo("SYSTEM");
        assertThat(events.get(0).actorName()).isNull();
        assertThat(events.get(1).actorType()).isEqualTo("STAFF");
    }

    @Test
    @DisplayName("a revision that changed something else (a note, an allergy acknowledgement) is not a timeline event")
    void unrelatedRevisionsAreSkipped() {
        List<OrderTimelineEventDto> events = OrderTimelineService.build(List.of(
                row(1, 0, "PENDING", "NONE", false, 1_000, null, null),
                row(2, 1, "PENDING", "NONE", false, 2_000, "u-ade", "Ade Cook"),
                row(3, 1, "CONFIRMED", "NONE", false, 3_000, "u-ade", "Ade Cook")));

        assertThat(events).extracting(OrderTimelineEventDto::kind).containsExactly("PLACED", "STATUS");
    }

    @Test
    @DisplayName("a payment change is its own event and keeps the status it happened under; a void names its reason")
    void paymentAndVoid() {
        List<OrderTimelineEventDto> events = OrderTimelineService.build(List.of(
                row(1, 0, "PENDING", "PENDING", false, 1_000, null, null),
                row(2, 1, "PENDING", "CAPTURED", false, 2_000, null, null),
                row(3, 1, "COMPLETED", "CAPTURED", false, 3_000, "u-ade", "Ade Cook"),
                row(4, 1, "COMPLETED", "CAPTURED", true, 4_000, "u-ade", "Ade Cook")));

        assertThat(events).extracting(OrderTimelineEventDto::kind)
                .containsExactly("PLACED", "PAYMENT", "STATUS", "VOIDED");
        assertThat(events.get(1).paymentStatus()).isEqualTo("CAPTURED");
        assertThat(events.get(1).status()).isEqualTo("PENDING");
        assertThat(events.get(3).detail()).isEqualTo("bad batch");
    }

    @Test
    @DisplayName("a cancel carries the recorded reason code; other changes carry none")
    void cancelReason() {
        Row cancelled = new Row(2, (short) 1, "CANCELLED", "NONE", false, null, "SUSPECTED_FAKE", 2_000L,
                "u-ade", "Ade Cook", null);
        List<OrderTimelineEventDto> events = OrderTimelineService.build(List.of(
                row(1, 0, "PENDING", "NONE", false, 1_000, null, null), cancelled));

        assertThat(events.get(1).detail()).isEqualTo("SUSPECTED_FAKE");
        assertThat(events.get(0).detail()).isNull();
    }

    @Test
    @DisplayName("a history that starts mid-life shows the status it first appears with, not 'placed'; a person absent from the directory is STAFF with no name")
    void historyThatStartsLate_andUnnamedActor() {
        List<OrderTimelineEventDto> events = OrderTimelineService.build(List.of(
                new Row(7, (short) 1, "CONFIRMED", "NONE", false, null, null, 5_000L, "u-gone", null, null),
                new Row(8, (short) 1, "PREPARING", "NONE", false, null, null, 6_000L, "u-gone", null, "gone@example.test")));

        assertThat(events.get(0).kind()).isEqualTo("STATUS");
        assertThat(events.get(0).fromStatus()).isNull();
        assertThat(events.get(0).actorType()).isEqualTo("STAFF");
        assertThat(events.get(0).actorName()).isNull();
        // The email is the fallback name when there is no display name.
        assertThat(events.get(1).actorName()).isEqualTo("gone@example.test");
    }

    @Test
    @DisplayName("no history is an empty timeline, not an error")
    void empty() {
        assertThat(OrderTimelineService.build(List.of())).isEmpty();
    }
}
