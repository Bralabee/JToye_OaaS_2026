package uk.jtoye.core.common.contact;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #841 / UXT-072: contact details on an order are plausible, and a vendor/API/MCP order names a way to reach the
 * customer unless it says it is a walk-in.
 *
 * <p>Judged through the same Bean Validation the controllers' {@code @Valid} runs, so the refusal is the typed
 * 400 {@code https://jtoye.uk/errors/validation} with the field name as the key of {@code errors}. Every refusal
 * arm sits beside an accepting control for the same field, so a validator that refuses everything cannot pass.
 */
class OrderContactValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void start() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stop() {
        factory.close();
    }

    // ------------------------------------------------------------------ storefront (guest) orders

    private static GuestOrderRequest guest(String name, String email, String phone) {
        GuestOrderRequest r = new GuestOrderRequest();
        r.setCustomerName(name);
        r.setCustomerEmail(email);
        r.setCustomerPhone(phone);
        r.setFulfilmentType("COLLECTION");
        GuestOrderItemRequest item = new GuestOrderItemRequest();
        item.setProductId(UUID.randomUUID());
        item.setQuantity(1);
        r.setItems(List.of(item));
        return r;
    }

    private static Set<String> fieldsRefused(Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a plausible guest order is accepted (control)")
    void plausibleGuestOrderIsAccepted() {
        assertThat(fieldsRefused(guest("Ravi Shah", "ravi@example.com", "07700 900123"))).isEmpty();
    }

    @ParameterizedTest(name = "guest phone {0} is refused")
    @ValueSource(strings = {"x", "no", "call me", "0", "1", "12", "0000000000", "07700 90012", "077009001234567",
            "+44 7700", "+0123456789", "phone: 07700900123", "<script>"})
    void implausibleGuestPhoneIsRefused(String phone) {
        assertThat(fieldsRefused(guest("Ravi Shah", "ravi@example.com", phone))).containsExactly("customerPhone");
    }

    @ParameterizedTest(name = "guest phone {0} is accepted")
    @ValueSource(strings = {"07700900123", "07700 900 123", "(020) 7946 0958", "020-7946-0958", "+447700900123",
            "+44 7700 900123", "+44 (0)7700 900123", "00447700900123", "+353 85 123 4567", "+1 415 555 2671"})
    void plausibleGuestPhoneIsAccepted(String phone) {
        assertThat(fieldsRefused(guest("Ravi Shah", "ravi@example.com", phone))).isEmpty();
    }

    @ParameterizedTest(name = "guest email {0} is refused")
    @ValueSource(strings = {"name@example", "name@", "@example.com", "name example@x.com", "a@b", "a@b.c", "plain",
            "a@@b.com", "a@b..com"})
    void implausibleGuestEmailIsRefused(String email) {
        assertThat(fieldsRefused(guest("Ravi Shah", email, "07700900123"))).containsExactly("customerEmail");
    }

    @ParameterizedTest(name = "guest email {0} is accepted")
    @ValueSource(strings = {"ravi@example.com", "ravi.shah+orders@mail.example.co.uk", "o'brien@example.org"})
    void plausibleGuestEmailIsAccepted(String email) {
        assertThat(fieldsRefused(guest("Ravi Shah", email, "07700900123"))).isEmpty();
    }

    @ParameterizedTest(name = "guest name \"{0}\" is refused")
    @ValueSource(strings = {"x", "0", "1", "12345", "--", "?!", " a "})
    void implausibleGuestNameIsRefused(String name) {
        assertThat(fieldsRefused(guest(name, "ravi@example.com", "07700900123"))).containsExactly("customerName");
    }

    @ParameterizedTest(name = "guest name \"{0}\" is accepted")
    @ValueSource(strings = {"Li", "Ng", "Ravi Shah", "O'Brien", "Zoë Müller", "李雷", "Mary-Jane Watson-Parker", "Jo"})
    void plausibleGuestNameIsAccepted(String name) {
        assertThat(fieldsRefused(guest(name, "ravi@example.com", "07700900123"))).isEmpty();
    }

    // ------------------------------------------------------------------ vendor / API / MCP orders

    private static CreateOrderRequest vendor() {
        CreateOrderRequest r = new CreateOrderRequest();
        r.setShopId(UUID.randomUUID());
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(UUID.randomUUID());
        item.setQuantity(1);
        r.setItems(List.of(item));
        return r;
    }

    @Test
    @DisplayName("an order with no customer link and no contact is refused with a typed validation error")
    void orderWithNoContactIsRefused() {
        assertThat(fieldsRefused(vendor())).containsExactly("contactPresentOrWalkIn");
    }

    @ParameterizedTest(name = "name/phone/email blank ({0}) still counts as no contact")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankContactFieldsCountAsNone(String blank) {
        CreateOrderRequest r = vendor();
        r.setCustomerName(blank);
        r.setCustomerPhone(blank);
        r.setCustomerEmail(blank);
        assertThat(fieldsRefused(r)).containsExactly("contactPresentOrWalkIn");
    }

    @Test
    @DisplayName("any one way to reach the customer is enough")
    void anyOneContactIsEnough() {
        CreateOrderRequest byName = vendor();
        byName.setCustomerName("Walk-in Ade");
        CreateOrderRequest byPhone = vendor();
        byPhone.setCustomerPhone("07700 900123");
        CreateOrderRequest byEmail = vendor();
        byEmail.setCustomerEmail("ade@example.com");
        CreateOrderRequest byLink = vendor();
        byLink.setCustomerId(UUID.randomUUID());

        for (CreateOrderRequest r : List.of(byName, byPhone, byEmail, byLink)) {
            assertThat(fieldsRefused(r)).isEmpty();
        }
    }

    @Test
    @DisplayName("an explicit walk-in needs no contact; walkIn=false does not waive it")
    void explicitWalkInNeedsNoContact() {
        CreateOrderRequest walkIn = vendor();
        walkIn.setWalkIn(true);
        assertThat(fieldsRefused(walkIn)).isEmpty();

        CreateOrderRequest notWalkIn = vendor();
        notWalkIn.setWalkIn(false);
        assertThat(fieldsRefused(notWalkIn)).containsExactly("contactPresentOrWalkIn");
    }

    @Test
    @DisplayName("a contact detail that IS given must be plausible, walk-in or not")
    void givenContactMustBePlausible() {
        CreateOrderRequest r = vendor();
        r.setWalkIn(true);
        r.setCustomerName("x");
        r.setCustomerPhone("call me");
        r.setCustomerEmail("name@example");

        assertThat(fieldsRefused(r)).containsExactlyInAnyOrder("customerName", "customerPhone", "customerEmail");
    }

    @Test
    @DisplayName("the dashboard's own shape (name + email, optional phone left blank) is still accepted")
    void dashboardShapeIsStillAccepted() {
        CreateOrderRequest r = vendor();
        r.setCustomerName("Ade Obi");
        r.setCustomerEmail("ade@example.com");
        r.setCustomerPhone("");

        assertThat(fieldsRefused(r)).isEmpty();
    }

    @Test
    @DisplayName("the walk-in rule is not leaked into the published request body as a property")
    void ruleIsNotASerialisedProperty() throws Exception {
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        CreateOrderRequest r = vendor();
        r.setCustomerName("Ade Obi");

        String json = mapper.writeValueAsString(r);

        assertThat(json).doesNotContain("contactPresentOrWalkIn").doesNotContain("walkIn");
    }
}
