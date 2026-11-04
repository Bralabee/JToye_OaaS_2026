package uk.jtoye.core.finance.dto;

import uk.jtoye.core.finance.EntryKind;
import uk.jtoye.core.finance.PaidOnline;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.order.PaymentStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPQL constructor-target for one CSV export line (Phase 37-11, RWO-027, roadmap SC-2): a ledger row with
 * its order's number and its shop's name resolved DB-side, so the export is one query however many rows.
 *
 * @param orderNumber null for a row with no order (MANUAL)
 * @param shopName    null for a row with no shop (MANUAL, or a legacy order-less row)
 * @param orderPaymentStatus the order's payment status; null for a row with no order (#827)
 */
public record FinanceExportRow(
        UUID id,
        OffsetDateTime createdAt,
        String reference,
        String orderNumber,
        EntryKind entryKind,
        long amountPennies,
        VatRate vatRate,
        String shopName,
        PaymentStatus orderPaymentStatus
) {
    /** #827: whether the row's order was paid online; a row with no order (MANUAL) is not. */
    public boolean paidOnline() {
        return PaidOnline.of(orderPaymentStatus);
    }
}
