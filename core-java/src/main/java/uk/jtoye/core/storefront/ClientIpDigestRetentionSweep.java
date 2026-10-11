package uk.jtoye.core.storefront;

import org.springframework.stereotype.Component;

/**
 * RED scaffold (Phase 37-28, D-27): compiles so the failing retention test can run; sweeps nothing
 * yet. The GREEN commit replaces this body.
 */
@Component
public class ClientIpDigestRetentionSweep {

    /** @return how many digests were nulled across all tenants */
    public int sweep() {
        return 0;
    }
}
