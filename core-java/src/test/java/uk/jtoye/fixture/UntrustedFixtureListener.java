package uk.jtoye.fixture;

import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.time.Year;

/**
 * A class-level {@code @RabbitListener} whose {@code @RabbitHandler} payload type is deliberately
 * outside {@code RabbitMQConfig.TRUSTED_PAYLOAD_PACKAGES} — the positive control proving the 27-05
 * D-03 guard can actually fail.
 *
 * <p><b>Why it lives in {@code uk.jtoye.fixture} and not beside the test.</b> The guard scans
 * {@code uk.jtoye.core} on the classpath, which includes test classes. Declared under that root,
 * this fixture made the production guard fail on itself — a rule firing on its own definition.
 * Outside the scanned root it still serves as the control (the test invokes the same discovery
 * helper on it directly) without corrupting the real scan.
 *
 * <p>Never registered as a bean; the queue name is fictional and nothing consumes it.
 */
@RabbitListener(queues = "test.untrusted.fixture")
@SuppressWarnings("unused")
public class UntrustedFixtureListener {

    @RabbitHandler
    public void onUntrusted(Year payload) {
        // no-op fixture
    }
}
