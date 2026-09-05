package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * COR-1 (QA-council 20260902-134741) — the shared fulfilment rules, tested once, in isolation.
 *
 * <p>The point of the extraction is that the storefront and the vendor/REST/MCP path give the
 * SAME answer. Testing the rule here rather than twice through two services is what makes that
 * true by construction instead of by coincidence.
 */
class FulfilmentPolicyTest {

    // ---- resolve ----------------------------------------------------------------------------

    @Test
    @DisplayName("COR-1: the fallback is the CALLER's, not a constant — storefront DELIVERY, vendor COLLECTION")
    void fallbackIsPerCaller() {
        assertEquals(FulfilmentType.DELIVERY,
                FulfilmentPolicy.resolve(null, FulfilmentType.DELIVERY));
        assertEquals(FulfilmentType.COLLECTION,
                FulfilmentPolicy.resolve(null, FulfilmentType.COLLECTION));
        assertEquals(FulfilmentType.COLLECTION,
                FulfilmentPolicy.resolve("   ", FulfilmentType.COLLECTION));
    }

    @Test
    @DisplayName("COR-1: an explicit value always beats the fallback, case- and space-insensitively")
    void explicitValueBeatsFallback() {
        assertEquals(FulfilmentType.DELIVERY,
                FulfilmentPolicy.resolve("delivery", FulfilmentType.COLLECTION));
        assertEquals(FulfilmentType.DELIVERY,
                FulfilmentPolicy.resolve("  DeLiVeRy  ", FulfilmentType.COLLECTION));
        assertEquals(FulfilmentType.COLLECTION,
                FulfilmentPolicy.resolve("COLLECTION", FulfilmentType.DELIVERY));
    }

    @Test
    @DisplayName("COR-1: an UNKNOWN value is a 400, never a silent default — silent defaulting is how COR-1 happened")
    void unknownValueThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FulfilmentPolicy.resolve("TELEPORT", FulfilmentType.COLLECTION));
        assertEquals("Invalid fulfilment type: TELEPORT (expected DELIVERY or COLLECTION)",
                e.getMessage());
    }

    /**
     * PR #726 review low (a): the upper-casing must not depend on the JVM's default locale. Under
     * tr-TR a bare {@code toUpperCase()} maps {@code i} to the dotted capital {@code İ}, so
     * {@code "delivery"} becomes {@code "DELİVERY"} and a valid request is a 400 on a Turkish-locale
     * host. RED on the unfixed tree: "Invalid fulfilment type: delivery".
     */
    @Test
    @DisplayName("resolve is locale-independent: under tr-TR 'delivery' still resolves to DELIVERY")
    void resolveIsLocaleIndependent() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.of("tr", "TR"));
        try {
            assertEquals(FulfilmentType.DELIVERY,
                    FulfilmentPolicy.resolve("delivery", FulfilmentType.COLLECTION));
            assertEquals(FulfilmentType.COLLECTION,
                    FulfilmentPolicy.resolve("collection", FulfilmentType.DELIVERY));
        } finally {
            Locale.setDefault(original);
        }
    }

    // ---- requireDeliveryAddress -------------------------------------------------------------

    @Test
    @DisplayName("COR-1: a DELIVERY order without line1/city/postcode is rejected, one part at a time")
    void deliveryRequiresEveryRequiredAddressPart() {
        assertThrows(IllegalArgumentException.class, () -> FulfilmentPolicy.requireDeliveryAddress(
                FulfilmentType.DELIVERY, null, "London", "E1 6AN"));
        assertThrows(IllegalArgumentException.class, () -> FulfilmentPolicy.requireDeliveryAddress(
                FulfilmentType.DELIVERY, "1 High Street", "  ", "E1 6AN"));
        assertThrows(IllegalArgumentException.class, () -> FulfilmentPolicy.requireDeliveryAddress(
                FulfilmentType.DELIVERY, "1 High Street", "London", null));
        // Line 2 is optional — a flat number is not mandatory.
        assertDoesNotThrow(() -> FulfilmentPolicy.requireDeliveryAddress(
                FulfilmentType.DELIVERY, "1 High Street", "London", "E1 6AN"));
    }

    @Test
    @DisplayName("COR-1: a COLLECTION order is never address-checked")
    void collectionIsNeverAddressChecked() {
        assertDoesNotThrow(() -> FulfilmentPolicy.requireDeliveryAddress(
                FulfilmentType.COLLECTION, null, null, null));
    }

    // ---- deliveryFeePennies -----------------------------------------------------------------

    @Test
    @DisplayName("COR-1: COLLECTION is always £0, even when the shop charges a fee")
    void collectionIsAlwaysFree() {
        assertEquals(0L, FulfilmentPolicy.deliveryFeePennies(
                FulfilmentType.COLLECTION, 500L, 399L, 2000L));
    }

    @Test
    @DisplayName("COR-1: DELIVERY charges the shop's fee below the threshold and waives it at or above")
    void deliveryChargesThenWaives() {
        // brixton's live configuration: 399p fee, 2000p free-delivery threshold.
        assertEquals(399L, FulfilmentPolicy.deliveryFeePennies(
                FulfilmentType.DELIVERY, 1999L, 399L, 2000L), "1p below the threshold still pays");
        assertEquals(0L, FulfilmentPolicy.deliveryFeePennies(
                FulfilmentType.DELIVERY, 2000L, 399L, 2000L), "the threshold is inclusive (>=)");
        assertEquals(0L, FulfilmentPolicy.deliveryFeePennies(
                FulfilmentType.DELIVERY, 5000L, 399L, 2000L));
    }

    @Test
    @DisplayName("COR-1: a null threshold means NO waiver configured — it must not read as 'always free'")
    void nullThresholdIsNotAWaiver() {
        assertEquals(399L, FulfilmentPolicy.deliveryFeePennies(
                FulfilmentType.DELIVERY, 100_000L, 399L, null));
    }

    @Test
    @DisplayName("COR-1: a null shop fee is £0 — an API-created shop genuinely has none")
    void nullShopFeeIsZero() {
        assertEquals(0L, FulfilmentPolicy.deliveryFeePennies(
                FulfilmentType.DELIVERY, 100L, null, 2000L));
    }
}
