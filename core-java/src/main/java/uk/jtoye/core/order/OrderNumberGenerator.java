package uk.jtoye.core.order;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The single order-number generator (COR-5, QA-council 20260902-134741).
 *
 * <p>Format, unchanged: {@code ORD-<tenant8>-<yyyyMMdd>-<suffix8>}. The format is deliberately
 * NOT altered here — see {@code ORDER_NUMBER_GENERATION_REPORT.md} and adjudication A11: this
 * identifier is on an unauthenticated endpoint, in every notification email subject and in every
 * tracking deep-link, so a format change is a HIGH-blast-radius edit bought for a risk that report
 * already assessed as low and accepted.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The generator was a byte-identical private method in TWO services
 * ({@code OrderService} and {@code PublicStorefrontService}). Two copies of a rule that mints a
 * customer-visible identifier can drift, and — more immediately — neither copy had a seam, so
 * collision behaviour could only ever be reasoned about, never exercised. Extracting it gives one
 * definition and one fault-injection point.
 *
 * <h2>The suffix supplier is the seam, and it is the point</h2>
 *
 * <p>{@link #OrderNumberGenerator(Supplier)} takes the suffix source, so a test can pin it and
 * force a collision deterministically instead of waiting for a 1-in-4.3-billion event. The
 * production bean uses {@link #randomSuffix()}.
 *
 * <h2>What this class deliberately does NOT do (adjudication A10)</h2>
 *
 * <ul>
 *   <li><b>No {@code existsByOrderNumber} pre-check.</b> It cannot work: {@code orders} is
 *       ENABLE + FORCE RLS and the application connects as the DML-only {@code jtoye_runtime}
 *       role, so the query is blind to the very cross-tenant row the global
 *       {@code uk_orders_order_number} constraint would reject; on the dev runtime both tenants
 *       share the prefix {@code 00000000}, so that is not hypothetical; and even with perfect
 *       visibility the check is a TOCTOU race the constraint would still have to catch. A
 *       pre-check would therefore add a query, a false sense of safety, and no guarantee.</li>
 *   <li><b>No retry loop.</b> Deferred, with a reason. Once a flush raises 23505 the Hibernate
 *       EntityManager is rollback-only and the Postgres transaction is aborted, so a retry inside
 *       the same transaction cannot work; making it work needs {@code Propagation.REQUIRES_NEW} at
 *       the retry boundary (the reasoning is already written down in this repo at
 *       {@code StockService}), which would move the transaction boundary of the entire money path
 *       — including issue #538's persist-before-pay ordering and the in-transaction outbox write.
 *       That is a HIGH-tier change for a hazard measured at ~1.2e-4 per tenant-day at 1,000
 *       orders/day.</li>
 * </ul>
 *
 * <p>What happens on a collision today, measured rather than assumed: Postgres raises 23505,
 * Spring translates it to {@code DataIntegrityViolationException}, and
 * {@code GlobalExceptionHandler} answers <b>HTTP 409 "Duplicate Entry"</b> — not a 500. On the
 * storefront path the whole transaction rolls back with it, so a retry carrying the same
 * idempotency key finds no persisted order and mints a fresh number: the failure is
 * self-recovering. The residual defect is the unhelpful message, not a lost order.
 */
@Component
public class OrderNumberGenerator {

    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.BASIC_ISO_DATE;

    private final Supplier<String> suffixSupplier;

    /** Production constructor — a fresh random suffix per call. */
    @Autowired
    public OrderNumberGenerator() {
        this(OrderNumberGenerator::randomSuffix);
    }

    /**
     * Test seam. Pin the supplier to force a deterministic collision, or to assert exactly how
     * many suffixes a single order costs.
     */
    public OrderNumberGenerator(Supplier<String> suffixSupplier) {
        this.suffixSupplier = suffixSupplier;
    }

    /**
     * Mint an order number for a tenant. Pure apart from the supplier and the clock.
     *
     * @param tenantId the tenant whose first 8 hex characters form the prefix
     * @return {@code ORD-<tenant8>-<yyyyMMdd>-<suffix8>}, upper-case
     */
    public String generate(UUID tenantId) {
        // First 8 characters of the tenant UUID (compact, and the value support reads).
        String tenantPrefix = tenantId.toString().replace("-", "").substring(0, 8).toUpperCase();
        // Date for sorting/filtering (YYYYMMDD).
        String datePart = LocalDate.now().format(DATE_PART);
        return String.format("ORD-%s-%s-%s", tenantPrefix, datePart, suffixSupplier.get());
    }

    /** 8 upper-case hex characters drawn from a fresh UUID — the historical suffix source. */
    public static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
