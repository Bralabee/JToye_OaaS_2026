package uk.jtoye.gatefixtures;

import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.webhook.WebhookDeliveryService;

/**
 * The COMPLIANT control for {@code ControllerRepositoryInjectionGateTest}. See {@code README.md}.
 *
 * <p>Structurally identical to {@link ControllerInjectingRepositoryFixture} — same package, same
 * annotation, same injection style — differing in exactly one respect: it depends on a
 * {@code @Transactional} service instead of a repository.
 *
 * <p>Its job is to prove the detector DISCRIMINATES. Without it, "the gate returns no violations
 * for the main tree" is consistent with a detector that returns no violations for anything, and a
 * detector that can never fire is the failure mode this whole gate exists to prevent. The two
 * fixtures together pin both directions on the same instrument.
 */
@RestController
public class CompliantControllerFixture {

    private final WebhookDeliveryService deliveryService;

    public CompliantControllerFixture(WebhookDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /** Referenced so the field is not dead code to a compiler warning; never called. */
    public WebhookDeliveryService serviceForGateFixtureOnly() {
        return deliveryService;
    }
}
