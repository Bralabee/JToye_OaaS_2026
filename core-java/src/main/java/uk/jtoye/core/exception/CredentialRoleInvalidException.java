package uk.jtoye.core.exception;

import java.util.List;

/**
 * Phase 37-45 (D-23, Q7): a credential holds STAFF or SHOP_MANAGER, never GROUP_ADMIN. With GROUP_ADMIN a
 * machine identity could reach every Group-admin endpoint (T-37-107). 400 {@code credential-role-invalid},
 * naming the allowed roles.
 */
public class CredentialRoleInvalidException extends RuntimeException {

    public static final List<String> ALLOWED_ROLES = List.of("STAFF", "SHOP_MANAGER");

    public CredentialRoleInvalidException() {
        super("A credential can hold STAFF or SHOP_MANAGER, never GROUP_ADMIN.");
    }
}
