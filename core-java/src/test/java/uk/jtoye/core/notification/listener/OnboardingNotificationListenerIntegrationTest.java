package uk.jtoye.core.notification.listener;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.onboarding.OnboardingState;
import uk.jtoye.core.onboarding.OnboardingStateChangeEvent;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.MailhogAssertions;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COMMS-01 landing proof for the previously-DEAD onboarding channel: Phase 21
 * emitted onboarding-stall events to an UNBOUND exchange (discarded). Now that
 * {@code onboarding.notifications} is bound, an onboarding stall dispatched
 * through {@link OnboardingNotificationListener} lands a VENDOR email in Mailhog
 * (vendor-only, D-04 — there is no J'Toye platform operator).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingNotificationListenerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("payment.outbox.flush-interval-ms", () -> "86400000");
        registry.add("payment.outbox.resurrect-interval-ms", () -> "86400000");
        registry.add("notification.email.enabled", () -> "true");
        registry.add("spring.mail.host", () -> System.getProperty("mailhog.smtp-host", "localhost"));
        registry.add("spring.mail.port", () -> System.getProperty("mailhog.smtp-port", "1025"));
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        registry.add("notification.unsubscribe.signing-secret", () -> "integration-test-secret");
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static boolean downgraded = false;

    @Autowired private OnboardingNotificationListener listener;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private final MailhogAssertions mailhog = MailhogAssertions.atDefault();
    private TransactionTemplate txTemplate;

    private UUID tenantId;
    private UUID shopId;
    private String vendorEmail;

    @BeforeEach
    void seed() {
        Assumptions.assumeTrue(mailhog.isReachable(),
                "Mailhog (:8025) not reachable — skipping the onboarding landing proof");
        mailhog.clear();

        txTemplate = new TransactionTemplate(transactionManager);
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        vendorEmail = "onboard-vendor-" + UUID.randomUUID() + "@shop.test";

        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, contact_email, created_at) VALUES (?, ?, ?, now()) "
                        + "ON CONFLICT (id) DO NOTHING",
                tenantId, "Tenant OnboardNotif " + tenantId.toString().substring(0, 8), vendorEmail);

        if (!downgraded) {
            jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
            downgraded = true;
        }

        inTenantTx(() -> jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, name, slug, published, delivery_fee_pennies, created_at) "
                        + "VALUES (?, ?, ?, ?, false, 0, now())",
                shopId, tenantId, "OnboardNotif Shop " + shopId.toString().substring(0, 8),
                "onbnotif-shop-" + shopId.toString().substring(0, 8)));
    }

    private void inTenantTx(Runnable work) {
        TenantContext.set(tenantId);
        try {
            txTemplate.executeWithoutResult(status -> work.run());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("an onboarding stall lands a VENDOR email in Mailhog (the dead channel is now bound) — vendor-only")
    void onboardingStall_landsVendorEmail() {
        OnboardingStateChangeEvent event = new OnboardingStateChangeEvent(
                UUID.randomUUID(), tenantId, shopId, OnboardingState.VERIFYING,
                "Manual review required", OffsetDateTime.now());

        listener.handleOnboardingNotification(event);

        mailhog.awaitMessage(vendorEmail, "onboarding", TIMEOUT);
        assertThat(mailhog.messagesTo(vendorEmail, null))
                .as("exactly one onboarding email to the vendor (onboarding is vendor-only)")
                .hasSize(1);
    }
}
