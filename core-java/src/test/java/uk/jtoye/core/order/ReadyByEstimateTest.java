package uk.jtoye.core.order;

import org.junit.jupiter.api.Test;
import uk.jtoye.core.shop.Shop;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** #825: the one ready-by rule (anchor + prep time), and the cases that must produce NO estimate. */
class ReadyByEstimateTest {

    private static final OffsetDateTime ANCHOR = OffsetDateTime.parse("2026-11-04T12:00:00Z");

    private static Shop shopWithPrep(Integer minutes) {
        Shop shop = new Shop();
        shop.setPrepTimeMinutes(minutes);
        return shop;
    }

    @Test
    void addsThePrepTimeToTheAnchor() {
        assertThat(ReadyByEstimate.of(shopWithPrep(25), ANCHOR, new Order()))
                .isEqualTo(OffsetDateTime.parse("2026-11-04T12:25:00Z"));
    }

    @Test
    void noPrepTimeMeansNoEstimate() {
        assertThat(ReadyByEstimate.of(shopWithPrep(null), ANCHOR, new Order())).isNull();
        assertThat(ReadyByEstimate.of(shopWithPrep(0), ANCHOR, new Order())).isNull();
    }

    @Test
    void aScheduledSlotOrderGetsNoOnDemandEstimate() {
        Order slotOrder = new Order();
        slotOrder.setFulfilmentSlotId(UUID.randomUUID());
        assertThat(ReadyByEstimate.of(shopWithPrep(25), ANCHOR, slotOrder)).isNull();

        Order dated = new Order();
        dated.setScheduledForDate(LocalDate.of(2026, 11, 7));
        assertThat(ReadyByEstimate.of(shopWithPrep(25), ANCHOR, dated)).isNull();
    }

    @Test
    void missingShopOrAnchorMeansNoEstimate() {
        assertThat(ReadyByEstimate.of(null, ANCHOR, new Order())).isNull();
        assertThat(ReadyByEstimate.of(shopWithPrep(25), null, new Order())).isNull();
    }
}
