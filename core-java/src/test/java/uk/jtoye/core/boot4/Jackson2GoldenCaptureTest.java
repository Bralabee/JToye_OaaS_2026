package uk.jtoye.core.boot4;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import uk.jtoye.core.common.idempotency.IdempotencyService;
import uk.jtoye.core.config.CacheConfig;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.gdpr.DsarIntakeService;
import uk.jtoye.core.media.MediaAssetService;
import uk.jtoye.core.onboarding.OnboardingEventPublisher;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.payment.PaymentEventPublisher;
import uk.jtoye.core.payment.RefundEventPublisher;
import uk.jtoye.core.security.ProblemDetailAuthenticationEntryPoint;
import uk.jtoye.core.webhook.WebhookFanoutListener;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ONE-SHOT generator of the {@code jackson2-golden} fixtures (Phase 38, plan 38-01). It runs on
 * the Boot 3.5.16 / Jackson 2 tree only, and is deleted once its output is committed: the
 * README names the commit that ran it, and re-running it on a Boot-4 tree would re-capture the
 * fixtures under Jackson 3 and defeat their purpose.
 *
 * <p>Gated by {@code JTOYE_GOLDEN_CAPTURE=true} so a normal test run never writes. Untagged, on
 * the H2 {@code test} profile, which sets no {@code spring.jackson.*} key, so the injected
 * {@link ObjectMapper} is the same auto-configured bean production injects.
 *
 * <p><b>Production serializers only.</b>
 * <ul>
 *   <li>JSON: the mapper is asserted to be the very instance held by every writer whose bytes are
 *       captured (IdempotencyService, the four outbox publishers, MediaAssetService,
 *       WebhookFanoutListener, DsarIntakeService, ProblemDetailAuthenticationEntryPoint), and
 *       idempotency hashes are computed by IdempotencyService's own {@code serialize} and
 *       {@code sha256Hex}.</li>
 *   <li>AMQP: {@code new RabbitMQConfig().jsonMessageConverter()}, the bean method itself.</li>
 *   <li>Redis: {@link CacheConfig#jsonRedisSerializer()}, the serializer the cache manager uses.</li>
 * </ul>
 * Every file is UTF-8 with no trailing newline. Nothing is appended: each table is rewritten whole
 * from this run, so a second run is byte-identical (the determinism check).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "JTOYE_GOLDEN_CAPTURE", matches = "true")
class Jackson2GoldenCaptureTest {

    private static final Path ROOT = GoldenFixturesIntegrityTest.ROOT;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private ApplicationContext context;

    private IdempotencyService idempotencyTarget;

    /** endpoint id -> sha256 of the request fingerprint, rewritten whole on every run. */
    private final Map<String, String> requestHashes = new TreeMap<>();

    @Test
    @DisplayName("capture every Jackson-2 golden fixture from the Boot 3.5 production serializers")
    void capture() throws Exception {
        // IdempotencyService is @Transactional, so the bean is a CGLIB proxy whose own fields are
        // empty; its private serialize/sha256Hex must run on the target the proxy delegates to.
        idempotencyTarget = AopTestUtils.getUltimateTargetObject(idempotencyService);
        for (Class<?> writer : List.of(IdempotencyService.class, OrderEventPublisher.class,
                PaymentEventPublisher.class, RefundEventPublisher.class, OnboardingEventPublisher.class,
                MediaAssetService.class, WebhookFanoutListener.class, DsarIntakeService.class,
                ProblemDetailAuthenticationEntryPoint.class)) {
            assertThat(mapperHeldBy(context.getBean(writer)))
                    .as("%s must serialize with the exact ObjectMapper bean this capture uses", writer.getSimpleName())
                    .isSameAs(objectMapper);
        }

        captureRequests();
        captureResponses();
        Map<String, Object> events = events();
        captureOutbox(events);
        captureAmqp(events);
        captureCache();

        writeRequestHashes();
        writeManifest();
    }

    // ------------------------------------------------------------ families

    private void captureRequests() throws Exception {
        captureRequest("orders.create", GoldenSamples.createOrderRequest());
        captureRequest("customers.create", GoldenSamples.createCustomerRequest());
        captureRequest("media.upload", GoldenSamples.mediaUploadRequest());
        captureRequest("media.reprocess", GoldenSamples.redriveRequest());
        captureRequest("webhooks.replay", GoldenSamples.replayRequest());
        captureRequest("storefront.guest-order", GoldenSamples.guestCheckoutIdentity());
        captureRequest("storefront.guest-order.legacy", GoldenSamples.guestOrderRequest());
    }

    /** Both the stored idempotency response bodies and the public wire shapes. */
    private void captureResponses() throws Exception {
        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("OrderDto", GoldenSamples.orderDto());
        responses.put("CustomerDto", GoldenSamples.customerDto());
        responses.put("MediaAcceptDto", GoldenSamples.mediaAcceptDto());
        responses.put("WebhookDeliveryView", GoldenSamples.webhookDeliveryView());
        responses.put("ProductDto", GoldenSamples.productDto());
        responses.put("ShopDto", GoldenSamples.shopDto());
        responses.put("DsarIntakeAck", GoldenSamples.dsarIntakeAck());
        responses.put("WebhookEventEnvelope", GoldenSamples.webhookEventEnvelope());
        responses.put("ProblemDetail-401", GoldenSamples.problemDetail401());
        for (Map.Entry<String, Object> e : responses.entrySet()) {
            write(Path.of("responses", e.getKey() + ".json"), utf8(objectMapper.writeValueAsString(e.getValue())));
        }
    }

    private static Map<String, Object> events() {
        Map<String, Object> events = new LinkedHashMap<>();
        events.put("OrderStateChangeEvent", GoldenSamples.orderStateChangeEvent());
        events.put("OrderStateChangeEvent-offset", GoldenSamples.orderStateChangeEventOffset());
        events.put("PaymentEvent", GoldenSamples.paymentEvent());
        events.put("RefundEvent", GoldenSamples.refundEvent());
        events.put("OnboardingStateChangeEvent", GoldenSamples.onboardingStateChangeEvent());
        events.put("MediaProcessingEvent", GoldenSamples.mediaProcessingEvent());
        return events;
    }

    /** Outbox payload rows: {@code objectMapper.writeValueAsString(event)}, exactly as the publishers do. */
    private void captureOutbox(Map<String, Object> events) throws Exception {
        for (Map.Entry<String, Object> e : events.entrySet()) {
            write(Path.of("outbox", e.getKey() + ".json"), utf8(objectMapper.writeValueAsString(e.getValue())));
        }
    }

    /**
     * AMQP message body + properties from the production converter bean method. The context's
     * RabbitTemplate (Boot-configured with the MessageConverter bean) must produce the same bytes,
     * which proves these are the bytes the publishers actually put on the wire.
     */
    private void captureAmqp(Map<String, Object> events) throws IOException {
        MessageConverter converter = new RabbitMQConfig().jsonMessageConverter();
        MessageConverter live = context.getBean(RabbitTemplate.class).getMessageConverter();
        for (Map.Entry<String, Object> e : events.entrySet()) {
            Message message = converter.toMessage(e.getValue(), new MessageProperties());
            assertThat(live.toMessage(e.getValue(), new MessageProperties()).getBody())
                    .as("the context RabbitTemplate's converter must write the same %s body", e.getKey())
                    .isEqualTo(message.getBody());
            write(Path.of("amqp", e.getKey() + ".body"), message.getBody());
            MessageProperties props = message.getMessageProperties();
            List<String> rows = new ArrayList<>();
            rows.add("contentType\t" + props.getContentType());
            rows.add("contentEncoding\t" + props.getContentEncoding());
            new TreeMap<>(props.getHeaders()).forEach((name, value) -> rows.add(name + "\t" + value));
            write(Path.of("amqp", e.getKey() + ".headers.tsv"), utf8(String.join("\n", rows)));
        }
    }

    /** Redis cache values through the serializer the cache manager is built with. */
    private static void captureCache() throws IOException {
        RedisSerializer<Object> serializer = CacheConfig.jsonRedisSerializer();
        write(Path.of("cache", "products-ProductDto.bin"), serializer.serialize(GoldenSamples.productDto()));
        write(Path.of("cache", "shops-ShopDto.bin"), serializer.serialize(GoldenSamples.shopDto()));
        write(Path.of("cache", "shopMembership-Membership.bin"), serializer.serialize(GoldenSamples.membership()));
    }

    private void captureRequest(String endpointId, Object requestBody) throws Exception {
        String json = (String) invoke(idempotencyTarget, "serialize", new Class<?>[]{Object.class}, requestBody);
        byte[] bytes = utf8(json);
        write(Path.of("idempotency", endpointId + ".request.json"), bytes);
        String hash = (String) invoke(null, "sha256Hex", new Class<?>[]{String.class}, json);
        assertThat(hash).as("IdempotencyService.sha256Hex must equal the sha256 of the written bytes")
                .isEqualTo(GoldenFixturesIntegrityTest.sha256Hex(bytes));
        requestHashes.put(endpointId, hash);
    }

    // ------------------------------------------------------------ tables

    private void writeRequestHashes() throws IOException {
        StringBuilder tsv = new StringBuilder();
        requestHashes.forEach((id, hash) -> tsv.append(id).append('\t').append(hash).append('\n'));
        write(Path.of("idempotency", "request-hashes.tsv"), utf8(tsv.toString()));
    }

    /** MANIFEST.tsv from a sorted walk of the tree (MANIFEST.tsv and README.md excluded). */
    private static void writeManifest() throws IOException {
        StringBuilder tsv = new StringBuilder();
        for (Path fixture : GoldenFixturesIntegrityTest.fixtures()) {
            byte[] bytes = Files.readAllBytes(fixture);
            String relative = ROOT.relativize(fixture).toString().replace('\\', '/');
            tsv.append(relative).append('\t').append(GoldenFixturesIntegrityTest.sha256Hex(bytes))
                    .append('\t').append(bytes.length).append('\n');
        }
        Files.write(ROOT.resolve("MANIFEST.tsv"), utf8(tsv.toString()));
    }

    // ------------------------------------------------------------ helpers

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void write(Path relative, byte[] bytes) throws IOException {
        Path target = ROOT.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    /** The single ObjectMapper-typed field of a (possibly proxied) bean. */
    private static Object mapperHeldBy(Object bean) throws ReflectiveOperationException {
        Object target = AopTestUtils.getUltimateTargetObject(bean);
        List<Field> mappers = new ArrayList<>();
        for (Field f : target.getClass().getDeclaredFields()) {
            if (ObjectMapper.class.isAssignableFrom(f.getType())) {
                mappers.add(f);
            }
        }
        assertThat(mappers).as("%s should hold exactly one ObjectMapper field", target.getClass().getName()).hasSize(1);
        Field f = mappers.get(0);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args)
            throws ReflectiveOperationException {
        Method m = IdempotencyService.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }
}
