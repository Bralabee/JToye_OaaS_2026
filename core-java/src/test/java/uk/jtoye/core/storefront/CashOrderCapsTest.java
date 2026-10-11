package uk.jtoye.core.storefront;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.jtoye.core.exception.OrderCapExceededException;
import uk.jtoye.core.order.OrderRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 37-27 (D-18): the identity normaliser and both sides of each cap, over a mocked repository.
 * {@code CashOrderCapIntegrationTest} proves the same rules through the endpoint and the real indexes;
 * this class pins the spellings and the boundaries cheaply.
 */
class CashOrderCapsTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SHOP = UUID.randomUUID();
    private static final String PER_EMAIL_KEY = "jtoye.storefront.cash-caps.per-email";
    private static final String PER_PHONE_KEY = "jtoye.storefront.cash-caps.per-phone";
    private static final String PER_IP_KEY = "jtoye.storefront.cash-caps.per-ip";

    private final OrderRepository orderRepository = mock(OrderRepository.class);

    private CashOrderCaps caps(int perEmail, int perPhone, int perIp) {
        return new CashOrderCaps(orderRepository, perEmail, perPhone, perIp);
    }

    @Nested
    @DisplayName("phone normalisation: digits only, a UK leading 0 becomes 44, so every spelling of one number is one key")
    class PhoneNormalisation {

        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource(delimiter = '|', value = {
                "07700 900123          | 447700900123",
                "+44 7700 900123       | 447700900123",
                "+447700900123         | 447700900123",
                "0044 7700 900123      | 447700900123",
                "(07700) 900-123       | 447700900123",
                " 07700900123          | 447700900123",
                "+1 415 555 0100       | 14155550100",
                "020 7946 0958         | 442079460958",
        })
        void everySpellingOfOneNumberNormalisesToOneKey(String spelling, String expected) {
            assertThat(CashOrderCaps.normalisePhone(spelling)).isEqualTo(expected);
        }

        @Test
        void aNumberWithNoDigits_isNull_andNeverMatchesAnything() {
            assertThat(CashOrderCaps.normalisePhone("   ")).isNull();
            assertThat(CashOrderCaps.normalisePhone("n/a")).isNull();
            assertThat(CashOrderCaps.normalisePhone(null)).isNull();
        }

        @Test
        void aLoneZero_isNotFoldedIntoACountryCode() {
            assertThat(CashOrderCaps.normalisePhone("0")).isEqualTo("0");
        }
    }

    @Nested
    @DisplayName("email normalisation: trimmed and lower-cased")
    class EmailNormalisation {

        @Test
        void caseAndSurroundingSpaceAreFolded() {
            assertThat(CashOrderCaps.normaliseEmail("  Cap.Buyer@Example.COM ")).isEqualTo("cap.buyer@example.com");
            assertThat(CashOrderCaps.normaliseEmail("   ")).isNull();
            assertThat(CashOrderCaps.normaliseEmail(null)).isNull();
        }
    }

    @Nested
    @DisplayName("the email axis: below the cap passes, at the cap refuses naming EMAIL and the cap")
    class EmailAxis {

        @Test
        void twoOpenOrders_underACapOfThree_pass() {
            when(orderRepository.countOpenCashOrdersByEmail(TENANT, SHOP, "a@x.com")).thenReturn(2L);
            when(orderRepository.findOpenCashOrderPhones(any(), any())).thenReturn(List.of());
            assertThatCode(() -> caps(3, 3, 5).checkEmailAndPhone(TENANT, SHOP, " A@X.com ", "07700 900123"))
                    .doesNotThrowAnyException();
        }

        @Test
        void threeOpenOrders_atACapOfThree_areRefusedOnEmail() {
            when(orderRepository.countOpenCashOrdersByEmail(TENANT, SHOP, "a@x.com")).thenReturn(3L);
            assertThatThrownBy(() -> caps(3, 3, 5).checkEmailAndPhone(TENANT, SHOP, "A@X.com", "07700 900123"))
                    .isInstanceOf(OrderCapExceededException.class)
                    .satisfies(ex -> {
                        OrderCapExceededException cap = (OrderCapExceededException) ex;
                        assertThat(cap.getAxis()).isEqualTo(OrderCapExceededException.Axis.EMAIL);
                        assertThat(cap.getCap()).isEqualTo(3);
                    });
        }
    }

    @Nested
    @DisplayName("the phone axis: the open phones of the shop are normalised and counted; below passes, at the cap refuses naming PHONE")
    class PhoneAxis {

        @Test
        void twoOpenOrdersOnTheSameNumberInTwoSpellings_underACapOfThree_pass() {
            when(orderRepository.countOpenCashOrdersByEmail(any(), any(), any())).thenReturn(0L);
            when(orderRepository.findOpenCashOrderPhones(eq(TENANT), eq(SHOP)))
                    .thenReturn(List.of("07700 900123", "+44 7700 900123", "07700 900999"));
            assertThatCode(() -> caps(3, 3, 5).checkEmailAndPhone(TENANT, SHOP, "fresh@x.com", "(07700) 900-123"))
                    .doesNotThrowAnyException();
        }

        @Test
        void threeOpenOrdersOnTheSameNumberInThreeSpellings_atACapOfThree_areRefusedOnPhone() {
            when(orderRepository.countOpenCashOrdersByEmail(any(), any(), any())).thenReturn(0L);
            when(orderRepository.findOpenCashOrderPhones(eq(TENANT), eq(SHOP)))
                    .thenReturn(List.of("07700 900123", "+44 7700 900123", "0044 7700 900 123", "07700 900999"));
            assertThatThrownBy(() -> caps(3, 3, 5).checkEmailAndPhone(TENANT, SHOP, "fresh@x.com", "+447700900123"))
                    .isInstanceOf(OrderCapExceededException.class)
                    .satisfies(ex -> {
                        OrderCapExceededException cap = (OrderCapExceededException) ex;
                        assertThat(cap.getAxis()).isEqualTo(OrderCapExceededException.Axis.PHONE);
                        assertThat(cap.getCap()).isEqualTo(3);
                    });
        }

        @Test
        void theEmailAxisIsJudgedFirst_soAFullEmailNamesEmailEvenWhenThePhoneIsFullToo() {
            when(orderRepository.countOpenCashOrdersByEmail(TENANT, SHOP, "full@x.com")).thenReturn(3L);
            when(orderRepository.findOpenCashOrderPhones(eq(TENANT), eq(SHOP)))
                    .thenReturn(List.of("07700 900123", "07700 900123", "07700 900123"));
            assertThatThrownBy(() -> caps(3, 3, 5).checkEmailAndPhone(TENANT, SHOP, "full@x.com", "07700 900123"))
                    .isInstanceOf(OrderCapExceededException.class)
                    .extracting(ex -> ((OrderCapExceededException) ex).getAxis())
                    .isEqualTo(OrderCapExceededException.Axis.EMAIL);
        }
    }

    @Nested
    @DisplayName("configuration: a cap below 1 refuses to start, naming its key")
    class Configuration {

        @Test
        void aZeroEmailCap_refusesToStartNamingTheKey() {
            assertThatThrownBy(() -> caps(0, 3, 5)).isInstanceOf(IllegalStateException.class).hasMessageContaining(PER_EMAIL_KEY);
        }

        @Test
        void aZeroPhoneCap_refusesToStartNamingTheKey() {
            assertThatThrownBy(() -> caps(3, 0, 5)).isInstanceOf(IllegalStateException.class).hasMessageContaining(PER_PHONE_KEY);
        }

        @Test
        void aNegativeIpCap_refusesToStartNamingTheKey() {
            assertThatThrownBy(() -> caps(3, 3, -1)).isInstanceOf(IllegalStateException.class).hasMessageContaining(PER_IP_KEY);
        }

        @Test
        void theThreeCapsAreReadable() {
            CashOrderCaps caps = caps(3, 4, 5);
            assertThat(caps.perEmail()).isEqualTo(3);
            assertThat(caps.perPhone()).isEqualTo(4);
            assertThat(caps.perIp()).isEqualTo(5);
        }
    }
}
