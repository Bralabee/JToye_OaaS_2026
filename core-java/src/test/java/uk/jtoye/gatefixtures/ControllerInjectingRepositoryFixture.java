package uk.jtoye.gatefixtures;

import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.webhook.WebhookDeliveryRepository;

/**
 * DELIBERATELY BROKEN — do not fix, do not copy. See {@code README.md} in this package.
 *
 * <p>A faithful reproduction of the issue #444 defect: a {@code @RestController} that injects a
 * Spring Data repository directly, with no {@code @Transactional} anywhere on the path. Under
 * FORCE RLS that reads as "no data" rather than as an error, which is why it survived an entire
 * milestone unnoticed.
 *
 * <p>Its only consumer is
 * {@code uk.jtoye.core.architecture.ControllerRepositoryInjectionGateTest}, which hands this class
 * to the gate's detector directly so the gate is proven CAPABLE of failing on every CI run — not
 * merely observed passing over a tree that happens to be clean.
 *
 * <p>It carries BOTH shapes of the defect on purpose:
 * <ul>
 *   <li>a declared <b>field</b> of a repository type (covers {@code @Autowired} field injection and
 *       the constructor-assigned {@code private final} convention this codebase uses), and</li>
 *   <li>a <b>constructor parameter</b> of a repository type (covers a repository that is injected
 *       but stashed in a lambda/collection rather than a field of its own).</li>
 * </ul>
 */
@RestController
public class ControllerInjectingRepositoryFixture {

    private final WebhookDeliveryRepository deliveryRepository;

    public ControllerInjectingRepositoryFixture(WebhookDeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    /** Referenced so the field is not dead code to a compiler warning; never called. */
    public long countForGateFixtureOnly() {
        return deliveryRepository.count();
    }
}
