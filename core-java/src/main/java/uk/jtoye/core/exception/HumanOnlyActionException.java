package uk.jtoye.core.exception;

/**
 * Phase 37-45 (Q7, T-37-107): managing integrator credentials and staff access is for people. A token that
 * was not issued to the dashboard's own Keycloak client ({@code jtoye.access.human-client-ids}) is refused,
 * whatever it is allowed to do elsewhere, so no credential or machine client can mint credentials or grant
 * staff. 403 {@code human-only-action}, distinct from {@code shop-access-denied}: the caller's rank is not the
 * problem, its kind is.
 */
public class HumanOnlyActionException extends RuntimeException {

    public static final String DETAIL =
            "Only a person signed in to the J'Toye dashboard can manage credentials and staff access.";

    public HumanOnlyActionException() {
        super(DETAIL);
    }
}
