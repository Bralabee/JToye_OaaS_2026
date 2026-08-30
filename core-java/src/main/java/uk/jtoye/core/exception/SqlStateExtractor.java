package uk.jtoye.core.exception;

import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLException;
import java.util.Optional;

/**
 * QA-council cluster P2 (API-2/FE-4) — shared SQLState extraction for a
 * {@link org.springframework.dao.DataIntegrityViolationException}, walking the exception's
 * cause chain to the nested {@link SQLException} exactly once rather than re-implementing the
 * walk at every call site ({@code GlobalExceptionHandler}'s 23502/23505 discrimination, and each
 * service's delete-path 23503 translation to {@link ResourceInUseException}).
 *
 * <p>SQLState is the authoritative signal (23502 not-null, 23503 foreign-key, 23505 unique).
 * Hibernate's own {@code ConstraintViolationException.getKind()} is NOT a substitute: as of
 * Hibernate 6.6.53 {@code ConstraintKind} distinguishes only {@code UNIQUE} vs {@code OTHER}, so
 * it cannot tell a not-null violation from a foreign-key violation apart — it is exposed here
 * ({@link #constraintKind}) purely as a corroborating fallback for callers that need one when the
 * driver fails to populate a SQLState at all, never as the primary signal.
 */
public final class SqlStateExtractor {

    private SqlStateExtractor() {
    }

    /** Walks the cause chain for the nested {@link SQLException} and returns its SQLState. */
    public static Optional<String> sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sqlEx && sqlEx.getSQLState() != null) {
                return Optional.of(sqlEx.getSQLState());
            }
        }
        return Optional.empty();
    }

    /** Corroborating signal ONLY — see class Javadoc. Never used as the primary discriminator. */
    public static Optional<ConstraintViolationException.ConstraintKind> constraintKind(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                return Optional.ofNullable(cve.getKind());
            }
        }
        return Optional.empty();
    }

    /** The DB-reported constraint name, where the driver names one (often absent for 23502). */
    public static Optional<String> constraintName(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                return Optional.of(cve.getConstraintName());
            }
        }
        return Optional.empty();
    }
}
