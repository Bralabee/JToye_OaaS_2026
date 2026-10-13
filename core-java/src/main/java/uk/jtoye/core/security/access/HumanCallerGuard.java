package uk.jtoye.core.security.access;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import uk.jtoye.core.exception.HumanOnlyActionException;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The human-only rule for credential and staff management (Phase 37-45, Q7, T-37-107).
 *
 * <p>An integrator credential is limited exactly like a person (D-23), so a credential granted enough rank,
 * or a declared machine client (an implicit Group admin for the shop gate, CR-03), could otherwise call
 * {@code requireGroupAdmin()} endpoints: issue more credentials, grant staff, invite people. That is an
 * escalation chain: one leaked machine secret becomes every identity of the business. This guard breaks it at
 * the kind of caller, not its rank: the token's {@code azp} (the Keycloak client it was issued to) must be one
 * of {@code jtoye.access.human-client-ids}, the dashboard's authorization-code client (the NextAuth
 * {@code KEYCLOAK_CLIENT_ID}: {@code core-api} in compose and k8s/local, {@code app-config/keycloak.client-id}
 * in staging and production, wired from the same ConfigMap key so the two cannot drift).
 *
 * <p>Fail-closed: a non-JWT principal, a token with no {@code azp}, and an empty allowlist are all refused.
 * Called at the top of every {@code /api/v1/credentials} endpoint and every staff grant, revoke and invite
 * mutation, BEFORE the rank check, so a machine caller learns only that it is a machine.
 *
 * <p>What it does not do: it is not authentication (the token is already validated) and not authorisation of
 * rank (that stays {@code ShopAccessService.requireGroupAdmin()}). Residual: a token minted by the dashboard
 * client's OWN service account (core-api has serviceAccountsEnabled in the dev realm) carries the dashboard
 * azp; it still needs a Group-admin grant, which no service account is given.
 */
@Component
public class HumanCallerGuard {

    private static final Logger log = LoggerFactory.getLogger(HumanCallerGuard.class);

    private final Set<String> humanClientIds;

    public HumanCallerGuard(@Value("${jtoye.access.human-client-ids:core-api}") Collection<String> humanClientIds) {
        this.humanClientIds = humanClientIds == null ? Set.of() : humanClientIds.stream()
                .map(String::strip)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @PostConstruct
    void logPosture() {
        if (humanClientIds.isEmpty()) {
            log.warn("event=human_only_posture clients=[] — jtoye.access.human-client-ids is empty, so NO caller can "
                    + "manage credentials or staff; set ACCESS_HUMAN_CLIENT_IDS to the dashboard's Keycloak client id");
        } else {
            log.info("event=human_only_posture clients={} — only tokens issued to these clients manage credentials and staff",
                    humanClientIds);
        }
    }

    /**
     * Refuses the current request unless its token was issued to a human (dashboard) client.
     *
     * @throws HumanOnlyActionException 403 {@code human-only-action}
     */
    public void requireHuman() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String azp = auth != null && auth.getPrincipal() instanceof Jwt jwt ? jwt.getClaimAsString("azp") : null;
        if (azp != null && humanClientIds.contains(azp)) {
            return;
        }
        log.info("event=human_only_refused azp={}", azp == null ? "<none>" : azp);
        throw new HumanOnlyActionException();
    }
}
