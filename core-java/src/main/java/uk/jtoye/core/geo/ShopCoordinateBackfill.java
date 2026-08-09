package uk.jtoye.core.geo;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.security.TenantContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fills in {@code shops.latitude}/{@code shops.longitude} for rows that already exist without
 * them. #460 link 3, the half a code change alone does not reach.
 *
 * <p>33-05's write path geocodes every shop created or updated <em>from now on</em>. It does
 * nothing for the rows already in the database, and on the dev volume that was every single
 * one of them (measured 2026-08-08, control arm CA-1: 5 shops, 0 with a latitude). This is a
 * <strong>data migration</strong>, and it is written in Java rather than in Flyway on purpose.
 *
 * <h2>Why not a migration</h2>
 *
 * <p>{@code shops} is {@code ENABLE ROW LEVEL SECURITY} + {@code FORCE}, and FORCE applies to
 * the table owner too. A bare {@code UPDATE shops SET latitude = … WHERE latitude IS NULL} with
 * no tenant GUC set matches <strong>zero rows and reports success</strong>. That exact defect is
 * recorded three times in this repository's own migration history — V25, V44 and V57 — which is
 * why the tenant loop here pins {@code app.current_tenant_id} per tenant, and why
 * {@code ShopCoordinateBackfillIntegrationTest} asserts the RETURNED COUNT rather than an exit
 * status in both directions.
 *
 * <h2>Where the tenant pin lives, and how to break it correctly</h2>
 *
 * <p>{@link #run()} owns the pin: it calls {@code TenantContext.set(tenantId)} around each
 * tenant's unit of work. {@link #backfillTenant(UUID)} does <em>not</em> set it — it derives
 * the {@code set_config} from whatever {@code TenantContext} holds. So clearing
 * {@code TenantContext} and calling {@code backfillTenant} directly removes the pin
 * <strong>at the layer this class actually uses</strong>, which is the only break that proves
 * anything. (The recorded trap: a tenant pin can sit under a global aspect, so breaking a
 * lower-level helper "works" while the aspect quietly re-applies the pin. This class touches
 * the {@code EntityManager} directly rather than a Spring Data repository, so
 * {@code TenantSetLocalAspect} never fires here and the explicit pin is the sole mechanism.)
 *
 * <h2>Three counters, not one</h2>
 *
 * <p>{@code updated}, {@code notGeocoded} and {@code refused} are different facts and collapsing
 * them hides the miss rate. A shop whose postcode is not in the reference table is
 * {@code notGeocoded} — normal, permanent for Northern Ireland, and NOT an error. A shop whose
 * write RLS refused is {@code refused} — a missing tenant pin, which is a defect. A single
 * "nothing happened" number cannot tell those apart, and they need opposite responses.
 *
 * <h2>Notes for a reviewer</h2>
 *
 * <ul>
 *   <li><strong>The write is a bulk JPQL UPDATE, so it does NOT generate {@code shops_aud}
 *       revisions</strong> — and that is a deliberate trade, recorded here so the absence is
 *       not read as a bug and nobody "repairs" it back into a managed-entity flush. A bulk
 *       update returns the affected ROW COUNT and never throws when it affects zero, which is
 *       exactly what the RLS proof needs: under FORCE RLS with no tenant pin the statement
 *       matches nothing and reports 0, a fact the caller can count. A managed-entity flush
 *       raises a stale-state exception instead, so the same proof would have to be inferred
 *       from a swallowed exception — weaker evidence, and it poisons the persistence context.
 *       Envers still records the {@code address} edits this coordinate is DERIVED from, so the
 *       audit trail can still answer why a coordinate is what it is.</li>
 *   <li><strong>One transaction per shop</strong>, deliberately. A row the RLS wall refuses
 *       affects only its own statement; the loop continues and the remaining shops are still
 *       backfilled.</li>
 *   <li><strong>Idempotent.</strong> Only rows with a NULL coordinate are candidates, so a
 *       second execution updates zero rows and says so.</li>
 *   <li><strong>Never {@code (0,0)}.</strong> {@link PostcodeGeocoder} returns an empty
 *       {@code Optional} for anything it cannot resolve; there is no sentinel value anywhere on
 *       this path. A shop at Null Island would be nearer the origin than any real GB shop and
 *       would therefore become the nearest kitchen to every customer on the platform.</li>
 *   <li><strong>Runs at {@code ApplicationReadyEvent}</strong>, which Spring Boot publishes
 *       strictly after every {@code ApplicationRunner}. That is what guarantees
 *       {@code PostcodeCentroidImporter} has loaded the reference table and (in dev)
 *       {@code DemoDataSeeder} has written its rows before this reads them — neither runner
 *       declares an {@code @Order}, so relying on their relative order would be a race.</li>
 * </ul>
 */
@Service
public class ShopCoordinateBackfill {

    private static final Logger log = LoggerFactory.getLogger(ShopCoordinateBackfill.class);

    private final EntityManager entityManager;
    private final PostcodeGeocoder postcodeGeocoder;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;

    public ShopCoordinateBackfill(
            EntityManager entityManager,
            PostcodeGeocoder postcodeGeocoder,
            PlatformTransactionManager transactionManager,
            // Declared by 33-02 in application.yml as ${COORDINATE_BACKFILL_ENABLED:true}.
            // The literal here is a resolution fallback for contexts that do not load the main
            // application.yml, NOT a second declaration of the default — application.yml is the
            // authority and PostcodeCentroidImportIntegrationTest asserts its resolved value.
            @Value("${jtoye.geo.coordinate-backfill.enabled:true}") boolean enabled) {
        this.entityManager = entityManager;
        this.postcodeGeocoder = postcodeGeocoder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    /**
     * What one backfill pass did. Three separate facts — see the class comment.
     *
     * @param updated     rows whose coordinate was written
     * @param notGeocoded shops visited whose address did not resolve (expected, not an error)
     * @param refused     shops whose write the RLS wall refused — a missing tenant pin
     */
    public record BackfillReport(long updated, long notGeocoded, long refused) {

        static final BackfillReport EMPTY = new BackfillReport(0, 0, 0);

        BackfillReport plus(BackfillReport other) {
            return new BackfillReport(
                    updated + other.updated,
                    notGeocoded + other.notGeocoded,
                    refused + other.refused);
        }
    }

    /**
     * Startup hook. Published after every {@code ApplicationRunner}, so the reference table is
     * loaded and (in dev) the demo rows are seeded before this runs.
     *
     * <p>Never allowed to take the application down: a backfill that fails is a data gap, and a
     * data gap is not worth refusing to serve traffic over. It is logged at ERROR and the next
     * boot retries, because the candidate set is defined by the data, not by a marker.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("event=shop_coordinate_backfill_disabled "
                    + "(jtoye.geo.coordinate-backfill.enabled=false) — existing shops with NULL "
                    + "coordinates will stay absent from distance-ranked results");
            return;
        }
        try {
            run();
        } catch (RuntimeException e) {
            log.error("event=shop_coordinate_backfill_failed — startup continues, next boot retries: {}",
                    e.toString(), e);
        }
    }

    /**
     * Backfill every tenant. Enumerates the {@code tenants} registry, which carries no RLS and
     * is the platform's source of tenant identity, then pins each tenant in turn.
     */
    public BackfillReport run() {
        List<UUID> tenantIds = listTenantIds();
        BackfillReport total = BackfillReport.EMPTY;

        for (UUID tenantId : tenantIds) {
            // THE PIN. Removing this line — and only this line — is the correct break arm.
            TenantContext.set(tenantId);
            try {
                BackfillReport tenantReport = backfillTenant(tenantId);
                total = total.plus(tenantReport);
                if (tenantReport.updated() > 0 || tenantReport.notGeocoded() > 0
                        || tenantReport.refused() > 0) {
                    log.info("event=shop_coordinate_backfill_tenant tenant={} updated={} "
                                    + "notGeocoded={} refused={}",
                            tenantId, tenantReport.updated(), tenantReport.notGeocoded(),
                            tenantReport.refused());
                }
            } catch (RuntimeException e) {
                log.error("event=shop_coordinate_backfill_tenant_failed tenant={} — continuing: {}",
                        tenantId, e.toString());
            } finally {
                TenantContext.clear();
            }
        }

        log.info("event=shop_coordinate_backfill_complete tenants={} updated={} notGeocoded={} refused={}",
                tenantIds.size(), total.updated(), total.notGeocoded(), total.refused());
        if (total.refused() > 0) {
            log.error("event=shop_coordinate_backfill_rls_refused count={} — the tenant GUC was not "
                    + "pinned for these writes; the rows are unchanged", total.refused());
        }
        return total;
    }

    /**
     * One tenant's unit of work, assuming the caller has pinned the tenant.
     *
     * <p>Deliberately does NOT call {@code TenantContext.set}: the pin is
     * {@link #run()}'s responsibility, so this method can be invoked with the context cleared
     * to prove the pin is load-bearing. With no pin the candidate SELECT still returns the
     * tenant's PUBLISHED shops (the {@code shops_public_read} policy OR-permits
     * {@code published = true}) and every write is then refused by {@code shops_rls_policy} —
     * which is why {@code refused} exists as a separate counter, and why a zero from this
     * method is not automatically a clean result.
     */
    public BackfillReport backfillTenant(UUID tenantId) {
        List<Candidate> candidates = loadCandidates(tenantId);

        long updated = 0;
        long notGeocoded = 0;
        long refused = 0;

        for (Candidate candidate : candidates) {
            Optional<PostcodeGeocoder.Coordinate> located = postcodeGeocoder.locate(candidate.address());
            if (located.isEmpty()) {
                notGeocoded++;
                continue;
            }
            if (applyCoordinate(candidate.id(), located.get())) {
                updated++;
            } else {
                refused++;
            }
        }
        return new BackfillReport(updated, notGeocoded, refused);
    }

    /** Shops of this tenant still missing a coordinate. Id + address only — no entity graph. */
    private List<Candidate> loadCandidates(UUID tenantId) {
        List<Object[]> rows = transactionTemplate.execute(status -> {
            pinTenantFromContext();
            return entityManager.createQuery(
                            "SELECT s.id, s.address FROM Shop s "
                                    + "WHERE s.tenantId = :tenantId "
                                    + "AND (s.latitude IS NULL OR s.longitude IS NULL)",
                            Object[].class)
                    .setParameter("tenantId", tenantId)
                    .getResultList();
        });
        List<Candidate> candidates = new ArrayList<>();
        if (rows != null) {
            for (Object[] row : rows) {
                candidates.add(new Candidate((UUID) row[0], (String) row[1]));
            }
        }
        return candidates;
    }

    /**
     * Write one shop's coordinate.
     *
     * <p>The {@code latitude IS NULL} limb in the WHERE clause is what makes the whole backfill
     * idempotent: a second pass matches nothing and returns 0 without needing a marker table or
     * a "has it run?" flag.
     *
     * @return {@code true} if the row was written, {@code false} if the statement affected zero
     *         rows — which under FORCE RLS with no tenant pin is exactly what happens. Reads the
     *         ROW COUNT, never an exit status: the recorded V25/V44/V57 defect is a bare UPDATE
     *         that matches nothing and reports success.
     */
    private boolean applyCoordinate(UUID shopId, PostcodeGeocoder.Coordinate coordinate) {
        Integer affected = transactionTemplate.execute(status -> {
            pinTenantFromContext();
            return entityManager.createQuery(
                            "UPDATE Shop s SET s.latitude = :latitude, s.longitude = :longitude "
                                    + "WHERE s.id = :shopId "
                                    + "AND (s.latitude IS NULL OR s.longitude IS NULL)")
                    .setParameter("latitude", coordinate.latitude())
                    .setParameter("longitude", coordinate.longitude())
                    .setParameter("shopId", shopId)
                    .executeUpdate();
        });
        if (affected == null || affected == 0) {
            log.warn("event=shop_coordinate_write_refused shop={} rows=0 — the row was not "
                    + "written (no tenant pin, or the row is not this tenant's)", shopId);
            return false;
        }
        return true;
    }

    /**
     * Pin {@code app.current_tenant_id} for the current transaction, from
     * {@code TenantContext}. Transaction-local ({@code is_local = true}), so it is released
     * with the transaction and cannot leak onto the next borrower of the pooled connection.
     *
     * <p>Derived from {@code TenantContext} rather than from a parameter on purpose: that makes
     * {@code TenantContext} the single layer at which this class's pin can be broken, and the
     * integration test breaks it exactly there.
     */
    private void pinTenantFromContext() {
        Optional<UUID> tenantId = TenantContext.get();
        if (tenantId.isEmpty()) {
            return;
        }
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement(
                    "SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.get().toString());
                stmt.execute();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private List<UUID> listTenantIds() {
        List<UUID> ids = transactionTemplate.execute(status ->
                entityManager.createNativeQuery("SELECT id FROM tenants").getResultList());
        return ids == null ? List.of() : ids;
    }

    private record Candidate(UUID id, String address) {
    }
}
