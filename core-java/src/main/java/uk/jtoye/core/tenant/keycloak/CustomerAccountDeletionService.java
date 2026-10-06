package uk.jtoye.core.tenant.keycloak;

import org.springframework.stereotype.Service;

/** 31.1-11 RED skeleton. */
@Service
public class CustomerAccountDeletionService {

    /** Outcome of one deletion attempt. */
    public enum AccountDeletionResult {
        DELETED,
        NONE_FOUND,
        NOT_CONFIGURED,
        FAILED
    }

    /** 31.1-11 RED skeleton. */
    public AccountDeletionResult deleteCustomerAccount(String verifiedEmail) {
        throw new UnsupportedOperationException("31.1-11 RED skeleton");
    }
}
