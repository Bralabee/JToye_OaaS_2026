package uk.jtoye.core.gdpr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.jtoye.core.onboarding.TraderIdentityService;
import uk.jtoye.core.onboarding.dto.TraderIdentityDto;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.review.Review;
import uk.jtoye.core.review.ReviewRepository;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Assembles, stores and issues the Article 15 export (Phase 31.1, D-01, issue #778).
 *
 * <h2>Three steps, three different callers' transactions</h2>
 *
 * <ol>
 *   <li>{@link #collectForTenant} reads ONE tenant's data about the subject. It has no transaction of
 *       its own: {@link DsarFanoutWorker} calls it inside the per-tenant wrapper it already uses for
 *       erasure — {@code TenantContext.set}, its own {@code TransactionTemplate}, the GUC pinned,
 *       {@code SystemPrincipal.asSystem}, {@code TenantContext.clear()} in a {@code finally}. The
 *       cross-tenant reach is that loop and that pin, nothing here: every read below carries an
 *       explicit tenant predicate as well, and under FORCE row-level security an unpinned call sees
 *       nothing at all.</li>
 *   <li>{@link #buildDocument} turns the collected sections into ONE JSON document. Pure: no database,
 *       no clock other than the {@code generatedAt} it is handed.</li>
 *   <li>{@link #storeAndIssueToken} encrypts the document and stores it with the SHA-256 of a fresh
 *       single-use token, replacing any previous export for the same request.</li>
 * </ol>
 *
 * <h2>The subject is found exactly as the erasure finds them</h2>
 *
 * {@link GdprService#matchSubjectInTenant} is the one matcher (31.1-02): every {@code customers} row and
 * every stored spelling of the address whose DSAR digest equals the request's. The export therefore
 * covers exactly the rows an erasure of the same subject would anonymise — "what you hold about me" and
 * "erase what you hold about me" cannot disagree about who "me" is.
 *
 * <p>This class is the ONLY Jackson touch point of the export: one injected Boot {@link JsonMapper}
 * (31.1-01 §1), used to build a tree in a fixed key order.
 */
@Service
public class DsarAccessExportService {

    /** The document's format identifier. A consumer (31.1-17's page) dispatches on it. */
    public static final String FORMAT = "jtoye-dsar-export/1";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final String UPSERT_SQL = """
            INSERT INTO dsar_access_export (id, dsar_request_id, token_sha256, payload_ciphertext, expires_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (dsar_request_id) DO UPDATE
               SET token_sha256       = EXCLUDED.token_sha256,
                   payload_ciphertext = EXCLUDED.payload_ciphertext,
                   expires_at         = EXCLUDED.expires_at,
                   created_at         = now(),
                   consumed_at        = NULL,
                   purged_at          = NULL
            """;

    private final GdprService gdprService;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;
    private final ShopRepository shopRepository;
    private final TraderIdentityService traderIdentityService;
    private final DsarCipher dsarCipher;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    /** How long an emailed link lives. Seven days (A5): cheap to change, recorded for the owner. */
    @Value("${jtoye.gdpr.dsar.export-link-ttl-hours:168}")
    private long exportLinkTtlHours;

    public DsarAccessExportService(GdprService gdprService,
                                   OrderRepository orderRepository,
                                   ReviewRepository reviewRepository,
                                   ShopRepository shopRepository,
                                   TraderIdentityService traderIdentityService,
                                   DsarCipher dsarCipher,
                                   JdbcTemplate jdbcTemplate,
                                   JsonMapper jsonMapper) {
        this.gdprService = gdprService;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
        this.shopRepository = shopRepository;
        this.traderIdentityService = traderIdentityService;
        this.dsarCipher = dsarCipher;
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    /** The link lifetime in hours, quoted to the subject from the value that stamps the expiry. */
    public long exportLinkTtlHours() {
        return exportLinkTtlHours;
    }

    // ---- 1. collect ---------------------------------------------------------------------------------

    /**
     * What ONE tenant holds about the subject, or empty when it holds nothing.
     *
     * <p>Must run inside the caller's transaction with {@code tenantId} pinned in {@code TenantContext}
     * and on the connection (see the class javadoc). An empty match returns before anything else is
     * read, so a tenant that does not hold the subject is never named in the export.
     */
    public Optional<TenantSection> collectForTenant(UUID tenantId, String subjectDigest) {
        GdprService.SubjectMatch match = gdprService.matchSubjectInTenant(tenantId, subjectDigest);
        if (match.isEmpty()) {
            return Optional.empty();
        }

        Map<UUID, Order> ordersById = new LinkedHashMap<>();
        for (String spelling : match.emailSpellings()) {
            for (Order order : orderRepository.findByTenantIdAndCustomerEmail(tenantId, spelling)) {
                ordersById.put(order.getId(), order);
            }
        }
        Map<UUID, Review> reviewsById = new LinkedHashMap<>();
        for (String spelling : match.emailSpellings()) {
            for (Review review : reviewRepository.findByTenantIdAndCustomerEmail(tenantId, spelling)) {
                reviewsById.put(review.getId(), review);
            }
        }

        List<Shop> shops = shopRepository.findByTenantId(tenantId, Pageable.unpaged(Sort.by("name", "id")))
                .getContent();
        Map<UUID, String> shopNames = new LinkedHashMap<>();
        shops.forEach(s -> shopNames.put(s.getId(), s.getName()));
        Optional<TraderIdentityDto> identity = traderIdentityService.findForTenant(tenantId);
        Recipient recipient = new Recipient(identity.map(TraderIdentityDto::legalName).orElse(null),
                shops.stream().map(Shop::getName).toList());

        List<OrderRecord> orders = ordersById.values().stream()
                .map(o -> toOrderRecord(o, shopNames))
                .toList();
        List<ReviewRecord> reviews = reviewsById.values().stream()
                .map(r -> new ReviewRecord(r.getId(), shopNames.get(r.getShopId()), r.getCustomerName(),
                        r.getFoodRating(), r.getDeliveryRating(), r.getComment(), r.getCreatedAt()))
                .toList();
        return Optional.of(new TenantSection(tenantId, recipient, orders, reviews));
    }

    private static OrderRecord toOrderRecord(Order o, Map<UUID, String> shopNames) {
        List<ItemRecord> items = o.getItems().stream()
                .map(i -> new ItemRecord(i.getProductName(), i.getQuantity(), i.getUnitPricePennies(),
                        i.getTotalPricePennies()))
                .toList();
        return new OrderRecord(o.getOrderNumber(), shopNames.get(o.getShopId()), o.getStatus().name(),
                o.getCreatedAt(), o.getCustomerName(), o.getCustomerEmail(), o.getCustomerPhone(), items);
    }

    // ---- 2. build -----------------------------------------------------------------------------------

    /**
     * The export document, as UTF-8 JSON bytes. Vendor sections are ordered by the recipient's legal
     * name, then by tenant id, so the order never depends on the order tenants were visited in.
     *
     * @param requestedFor the address the subject verified (their own data; it heads the document)
     * @param sections     one per tenant that holds the subject; empty when none does
     * @param generatedAt  when the document was assembled
     */
    public byte[] buildDocument(String requestedFor, List<TenantSection> sections, OffsetDateTime generatedAt) {
        ObjectNode doc = jsonMapper.createObjectNode();
        doc.put("format", FORMAT);
        doc.put("generatedAt", ts(generatedAt));
        doc.put("requestedFor", requestedFor);

        List<TenantSection> ordered = new ArrayList<>(sections);
        ordered.sort(Comparator
                .comparing((TenantSection s) -> s.recipient().legalName(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(s -> s.tenantId().toString()));

        ArrayNode vendors = doc.putArray("vendors");
        for (TenantSection section : ordered) {
            ObjectNode v = vendors.addObject();
            v.put("reference", section.tenantId().toString());
            ObjectNode recipient = v.putObject("recipient");
            recipient.put("legalName", section.recipient().legalName());
            ArrayNode shops = recipient.putArray("shops");
            section.recipient().shops().forEach(shops::add);

            ArrayNode orders = v.putArray("orders");
            for (OrderRecord o : section.orders()) {
                ObjectNode on = orders.addObject();
                on.put("orderNumber", o.orderNumber());
                on.put("shop", o.shop());
                on.put("status", o.status());
                on.put("placedAt", ts(o.placedAt()));
                on.put("customerName", o.customerName());
                on.put("customerEmail", o.customerEmail());
                on.put("customerPhone", o.customerPhone());
                ArrayNode items = on.putArray("items");
                for (ItemRecord i : o.items()) {
                    ObjectNode in = items.addObject();
                    in.put("productName", i.productName());
                    in.put("quantity", i.quantity());
                    in.put("unitPricePennies", i.unitPricePennies());
                    in.put("totalPricePennies", i.totalPricePennies());
                }
            }

            ArrayNode reviews = v.putArray("reviews");
            for (ReviewRecord r : section.reviews()) {
                ObjectNode rn = reviews.addObject();
                rn.put("id", r.id().toString());
                rn.put("shop", r.shop());
                rn.put("name", r.name());
                rn.put("foodRating", r.foodRating());
                rn.put("deliveryRating", r.deliveryRating());
                rn.put("comment", r.comment());
                rn.put("createdAt", ts(r.createdAt()));
            }
        }
        return jsonMapper.writeValueAsBytes(doc);
    }

    /** UTC ISO-8601, so the same instant always prints the same way whatever the session zone. */
    private static String ts(OffsetDateTime t) {
        return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).format(TIMESTAMP);
    }

    // ---- 3. store and issue ---------------------------------------------------------------------------

    /**
     * Encrypt and store the document for {@code requestId}, replacing any earlier export for it, and
     * return the readable token. Only the token's SHA-256 is stored; the readable token leaves this
     * method once, to be emailed.
     */
    @Transactional
    public IssuedToken storeAndIssueToken(UUID requestId, byte[] json) {
        byte[] raw = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(exportLinkTtlHours);
        byte[] payload = dsarCipher.encrypt(DsarCipher.Purpose.ACCESS_EXPORT, requestId,
                new String(json, StandardCharsets.UTF_8));
        jdbcTemplate.update(UPSERT_SQL, UUID.randomUUID(), requestId, sha256Hex(token), payload, expiresAt);
        return new IssuedToken(token, expiresAt);
    }

    static String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ---- the collected shape --------------------------------------------------------------------------

    /** What one tenant holds about the subject. */
    public record TenantSection(UUID tenantId, Recipient recipient, List<OrderRecord> orders,
                                List<ReviewRecord> reviews) {
    }

    /** Article 15(1)(c): who received the data — the trader behind the tenant, and its shops. */
    public record Recipient(String legalName, List<String> shops) {
    }

    public record OrderRecord(String orderNumber, String shop, String status, OffsetDateTime placedAt,
                              String customerName, String customerEmail, String customerPhone,
                              List<ItemRecord> items) {
    }

    public record ItemRecord(String productName, Integer quantity, Long unitPricePennies,
                             Long totalPricePennies) {
    }

    public record ReviewRecord(UUID id, String shop, String name, Integer foodRating, Integer deliveryRating,
                               String comment, OffsetDateTime createdAt) {
    }

    /** The readable single-use token and the expiry stamped on its row. */
    public record IssuedToken(String token, OffsetDateTime expiresAt) {
    }
}
