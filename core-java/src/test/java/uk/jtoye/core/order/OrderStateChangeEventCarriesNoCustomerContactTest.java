package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #856 / SW-TND-08: the live kitchen feed carries no customer contact.
 *
 * <p>One {@link OrderStateChangeEvent} is fanned out over SSE ({@code OrderSseService}, event
 * {@code order-state-change}) and STOMP ({@code OrderStateChangeListener}) to EVERY permitted
 * subscriber, STAFF included, with no per-subscriber shaping. That is safe only while the payload
 * holds no customer contact: subscribers refetch {@code /orders/kitchen}, which is minimised per
 * caller. A contact field added to the event later would bypass the #856 rule for every STAFF
 * screen at once, so this guard fails on it.
 *
 * <p>{@link #guard_detectsALeakyEvent} is the permanent break arm: it proves the check can fail.
 */
class OrderStateChangeEventCarriesNoCustomerContactTest {

    private static final List<String> CONTACT_TOKENS = List.of("customer", "email", "phone", "address", "postcode");

    /** A record shaped the way the broadcast must never become. Test-local, never serialised. */
    private record LeakyEvent(UUID orderId, String customerEmail, String customerPhone) {}

    static List<String> contactComponents(Class<?> recordClass) {
        if (!recordClass.isRecord()) {
            throw new IllegalArgumentException(recordClass + " is not a record; the guard reads record components");
        }
        return Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return CONTACT_TOKENS.stream().anyMatch(lower::contains);
                })
                .toList();
    }

    @Test
    @DisplayName("#856: the SSE/STOMP broadcast payload carries no customer contact field")
    void orderStateChangeEvent_carriesNoCustomerContact() {
        assertThat(OrderStateChangeEvent.class.getRecordComponents()).as("the guard reads a real record").isNotEmpty();
        assertThat(contactComponents(OrderStateChangeEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("#856 break arm: the guard names the contact fields of a leaky event")
    void guard_detectsALeakyEvent() {
        assertThat(contactComponents(LeakyEvent.class)).containsExactly("customerEmail", "customerPhone");
    }
}
