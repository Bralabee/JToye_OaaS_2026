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
import uk.jtoye.core.customer.Customer;
import uk.jtoye.core.customer.CustomerRepository;
import uk.jtoye.core.onboarding.TraderIdentityService;
import uk.jtoye.core.onboarding.dto.TraderIdentityDto;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderItem;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.product.AllergenCatalog;
import uk.jtoye.core.review.Review;
import uk.jtoye.core.review.ReviewRepository;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.tenant.keycloak.CustomerAccountDeletionService.PlatformAccountLookup;
import uk.jtoye.core.tenant.keycloak.CustomerRealmUser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
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
 * covers the rows an erasure of the same subject would anonymise — "what you hold about me" and "erase
 * what you hold about me" cannot disagree about who "me" is. Three further stores carry the address
 * and are read by digest the same way: the marketing opt-in and unsubscribe records (V54) and the
 * staff directory (V52).
 *
 * <h2>A superset of the Article 20 export, per tenant</h2>
 *
 * Everything {@code GdprController.DataExportResponse} carries is here — the customer record (name,
 * email, phone, allergen restrictions, notes, timestamps), each order (number, status, name, email,
 * amounts, payment method, notes, allergy note, placed time) and each review (ratings, comment, time) —
 * and more: the phone, delivery address, fulfilment type and items on each order, the allergen set the
 * subject acknowledged at checkout and when, when the shop read their allergy note, review photos and
 * the name shown on the review, consent records, staff-directory entries, the recipient's identity
 * (Article 15(1)(c)) and the platform sign-in account.
 *
 * <h2>Deterministic, so two exports of the same data are byte-identical apart from generatedAt</h2>
 *
 * Vendor sections by recipient legal name (unknown names last) then tenant id; customer records,
 * orders, items and reviews by time then id; shops by name then id; allergen names in catalogue (bit)
 * order; every timestamp printed in UTC. Built as a tree in a fixed key order with the one injected
 * Boot {@link JsonMapper} — the only Jackson touch point of the export (31.1-01 §1).
 */
@Service
public class DsarAccessExportService {

    /** The document's format identifier. A consumer (31.1-17's page) dispatches on it. */
    public static final String FORMAT = "jtoye-dsar-export/1";

    static final String NO_VENDOR_DATA_STATEMENT = "No shop on J'Toye holds personal data linked to this "
            + "email address: we searched the records of every shop on the platform and found none.";
    static final String VENDOR_DATA_STATEMENT = "The shops listed under \"vendors\" hold personal data linked "
            + "to this email address. Each section shows who runs the shop, what it holds, and when.";
    static final String NO_IDENTITY_NOTE = "This shop has not yet given J'Toye its legal business name and "
            + "address. It is identified here by its shop names; we have not guessed a legal name.";

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
    private final CustomerRepository customerRepository;
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

    /** The web app origin, for the retention and privacy pages the "about" section points at. */
    @Value("${notification.email.tracking-base-url:http://localhost:3000}")
    private String appBaseUrl;

    public DsarAccessExportService(GdprService gdprService,
                                   CustomerRepository customerRepository,
                                   OrderRepository orderRepository,
                                   ReviewRepository reviewRepository,
                                   ShopRepository shopRepository,
                                   TraderIdentityService traderIdentityService,
                                   DsarCipher dsarCipher,
                                   JdbcTemplate jdbcTemplate,
                                   JsonMapper jsonMapper) {
        this.gdprService = gdprService;
        this.customerRepository = customerRepository;
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
     * and on the connection (see the class javadoc). Nothing that names the tenant (its shops, its
     * trader identity) is read unless the subject was found, so a tenant that does not hold the subject
     * never appears in the export.
     */
    public Optional<TenantSection> collectForTenant(UUID tenantId, String subjectDigest) {
        GdprService.SubjectMatch match = gdprService.matchSubjectInTenant(tenantId, subjectDigest);
        Consent consent = consentFor(tenantId, subjectDigest);
        List<StaffEntry> staff = staffEntriesFor(tenantId, subjectDigest);
        if (match.isEmpty() && consent.isEmpty() && staff.isEmpty()) {
            return Optional.empty();
        }

        List<Customer> customers = new ArrayList<>();
        Map<UUID, Order> ordersById = new LinkedHashMap<>();
        for (UUID customerId : match.customerIds()) {
            customerRepository.findById(customerId)
                    .filter(c -> tenantId.equals(c.getTenantId()))
                    .ifPresent(customers::add);
            for (Order order : orderRepository.findByCustomerId(customerId)) {
                if (tenantId.equals(order.getTenantId())) {
                    ordersById.put(order.getId(), order);
                }
            }
        }
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
        List<String> shopList = shops.stream().map(Shop::getName).toList();
        Recipient recipient = traderIdentityService.findForTenant(tenantId)
                .map(identity -> Recipient.of(identity, shopList))
                .orElseGet(() -> Recipient.unknown(shopList));

        Map<UUID, String> orderNumbers = new LinkedHashMap<>();
        ordersById.values().forEach(o -> orderNumbers.put(o.getId(), o.getOrderNumber()));

        List<CustomerRecord> customerRecords = customers.stream()
                .sorted(Comparator.comparing(Customer::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(c -> c.getId().toString()))
                .map(c -> new CustomerRecord(c.getName(), c.getEmail(), c.getPhone(),
                        c.getAllergenRestrictions() == null ? null : AllergenCatalog.namesFor(c.getAllergenRestrictions()),
                        c.getNotes(), c.getCreatedAt(), c.getUpdatedAt()))
                .toList();
        List<OrderRecord> orders = ordersById.values().stream()
                .sorted(Comparator.comparing(Order::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(o -> o.getId().toString()))
                .map(o -> toOrderRecord(o, shopNames))
                .toList();
        List<ReviewRecord> reviews = reviewsById.values().stream()
                .sorted(Comparator.comparing(Review::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(r -> r.getId().toString()))
                .map(r -> new ReviewRecord(r.getId(), shopNames.get(r.getShopId()), orderNumbers.get(r.getOrderId()),
                        r.getCustomerName(), r.getCustomerEmail(), r.getFoodRating(), r.getDeliveryRating(),
                        r.getComment(), r.getPhotoUrls() == null ? List.of() : List.copyOf(r.getPhotoUrls()),
                        r.getCreatedAt()))
                .toList();
        return Optional.of(new TenantSection(tenantId, recipient, customerRecords, orders, reviews, consent, staff));
    }

    private static OrderRecord toOrderRecord(Order o, Map<UUID, String> shopNames) {
        List<ItemRecord> items = o.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(i -> i.getId().toString()))
                .map(i -> new ItemRecord(i.getProductName(), i.getQuantity(), i.getUnitPricePennies(),
                        i.getTotalPricePennies()))
                .toList();
        boolean anyAddress = o.getAddressLine1() != null || o.getAddressLine2() != null
                || o.getAddressCity() != null || o.getAddressPostcode() != null;
        Address address = anyAddress
                ? new Address(o.getAddressLine1(), o.getAddressLine2(), o.getAddressCity(), o.getAddressPostcode())
                : null;
        // NULL = not recorded (the order predates V69 or was placed by the shop); [] = acknowledged none.
        List<String> acknowledged = o.getAllergenAckMask() == null
                ? null : AllergenCatalog.namesFor(o.getAllergenAckMask());
        return new OrderRecord(o.getOrderNumber(), shopNames.get(o.getShopId()),
                o.getStatus() == null ? null : o.getStatus().name(), o.getCreatedAt(),
                o.getFulfilmentType() == null ? null : o.getFulfilmentType().name(),
                o.getCustomerName(), o.getCustomerEmail(), o.getCustomerPhone(), address, o.getNotes(),
                o.getAllergyNote(), o.getAllergyNoteAckAt(), acknowledged, o.getAllergenAckAt(),
                o.getPaymentMethod(), o.getPaymentStatus() == null ? null : o.getPaymentStatus().name(),
                new Totals(o.getSubtotalPennies(), o.getVatAmountPennies(), o.getDeliveryFeePennies(),
                        o.getTotalAmountPennies()),
                items);
    }

    /**
     * The V54 consent records for the subject in this tenant, matched by digest over every stored
     * recipient spelling, with an explicit tenant predicate (and FORCE RLS under the caller's pin).
     */
    private Consent consentFor(UUID tenantId, String subjectDigest) {
        List<Unsubscribe> unsubscribed = new ArrayList<>();
        jdbcTemplate.query("SELECT recipient, category, created_at FROM notification_suppression "
                        + "WHERE tenant_id = ? ORDER BY category, created_at, id",
                rs -> {
                    if (matches(rs.getString(1), subjectDigest)) {
                        unsubscribed.add(new Unsubscribe(rs.getString(2), odt(rs.getTimestamp(3))));
                    }
                }, tenantId);
        List<OffsetDateTime> optIns = new ArrayList<>();
        jdbcTemplate.query("SELECT recipient, opted_in_at FROM marketing_opt_in "
                        + "WHERE tenant_id = ? ORDER BY opted_in_at, id",
                rs -> {
                    if (matches(rs.getString(1), subjectDigest)) {
                        optIns.add(odt(rs.getTimestamp(2)));
                    }
                }, tenantId);
        return new Consent(optIns.isEmpty() ? null : optIns.get(0), unsubscribed);
    }

    /** The V52 staff-directory entries carrying the subject's address in this tenant. */
    private List<StaffEntry> staffEntriesFor(UUID tenantId, String subjectDigest) {
        List<StaffEntry> entries = new ArrayList<>();
        jdbcTemplate.query("SELECT email, display_name, last_seen FROM user_directory "
                        + "WHERE tenant_id = ? ORDER BY email, user_id",
                rs -> {
                    if (matches(rs.getString(1), subjectDigest)) {
                        entries.add(new StaffEntry(rs.getString(2), rs.getString(1), odt(rs.getTimestamp(3))));
                    }
                }, tenantId);
        return entries;
    }

    private static boolean matches(String stored, String subjectDigest) {
        return stored != null && DsarSubjectDigest.of(stored).equals(subjectDigest);
    }

    private static OffsetDateTime odt(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }

    // ---- 2. build -----------------------------------------------------------------------------------

    /**
     * The export document, as UTF-8 JSON bytes.
     *
     * @param requestedFor the address the subject verified (their own data; it heads the document)
     * @param sections     one per tenant that holds the subject; empty when none does
     * @param account      what the customer realm holds for the address
     * @param generatedAt  when the document was assembled
     */
    public byte[] buildDocument(String requestedFor, List<TenantSection> sections, PlatformAccountLookup account,
                                OffsetDateTime generatedAt) {
        List<TenantSection> ordered = new ArrayList<>(sections);
        ordered.sort(Comparator
                .comparing((TenantSection s) -> s.recipient().legalName(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(s -> s.tenantId().toString()));

        ObjectNode doc = jsonMapper.createObjectNode();
        doc.put("format", FORMAT);
        doc.put("generatedAt", ts(generatedAt));
        doc.put("requestedFor", requestedFor);

        ObjectNode summary = doc.putObject("summary");
        summary.put("vendorDataHeld", !ordered.isEmpty());
        summary.put("vendorCount", ordered.size());
        summary.put("statement", ordered.isEmpty() ? NO_VENDOR_DATA_STATEMENT : VENDOR_DATA_STATEMENT);

        writeAbout(doc.putObject("about"));
        writePlatformAccount(doc.putObject("platformAccount"), account);

        ArrayNode vendors = doc.putArray("vendors");
        for (TenantSection section : ordered) {
            writeVendor(vendors.addObject(), section);
        }
        return jsonMapper.writeValueAsBytes(doc);
    }

    /** UK GDPR Article 15(1)(a)-(h): purposes, recipients, retention, rights, source. */
    private void writeAbout(ObjectNode about) {
        ArrayNode purposes = about.putArray("purposes");
        purposes.add("To take, prepare, deliver and take payment for the orders you placed with each shop.");
        purposes.add("To show your review on the shop's page, when you chose to leave one.");
        purposes.add("To send you the order and account messages you are entitled to, and marketing only if "
                + "you opted in.");
        purposes.add("To keep the order and tax records UK law requires each shop to keep.");
        purposes.add("To let you sign in and see your orders, when you created a J'Toye account.");
        about.put("recipients", "Each shop listed under \"vendors\" received the data in its section: the "
                + "business named there runs the shop and decides how your order data is used. J'Toye runs "
                + "the platform the shops use and processes the data on their behalf. Card payments are "
                + "handled by our payment provider; your card details are never stored by J'Toye or the shops.");
        about.put("retention", "How long each kind of record is kept is set out in our retention schedule: "
                + appBaseUrl + "/legal/retention");
        ArrayNode rights = about.putArray("rights");
        addRight(rights, "rectification", "If anything here is wrong, ask the shop concerned, or us, to correct "
                + "it. Our privacy notice says how to contact us: " + appBaseUrl + "/legal/privacy");
        addRight(rights, "erasure", "You can ask for your personal data to be erased through the same request "
                + "page you used for this copy, choosing erasure instead of a copy.");
        addRight(rights, "complaint", "You can complain to the Information Commissioner's Office: "
                + "https://ico.org.uk/make-a-complaint/");
        about.put("source", "You provided this data yourself: when you placed orders, left reviews, set your "
                + "message preferences or created an account. Nothing here was bought from or given to us by "
                + "anyone else.");
    }

    private static void addRight(ArrayNode rights, String right, String how) {
        ObjectNode r = rights.addObject();
        r.put("right", right);
        r.put("how", how);
    }

    private void writePlatformAccount(ObjectNode node, PlatformAccountLookup account) {
        String status = account == null ? "NOT_CHECKED" : account.status().name();
        node.put("status", status);
        node.put("note", switch (status) {
            case "FOUND" -> "You have a J'Toye sign-in account with this email address.";
            case "NONE_FOUND" -> "There is no J'Toye sign-in account with this email address.";
            default -> "We could not check for a J'Toye sign-in account in this environment. If you have one, "
                    + "ask us and we will send you its details.";
        });
        ArrayNode accounts = node.putArray("accounts");
        if (account != null) {
            for (CustomerRealmUser user : account.accounts()) {
                ObjectNode u = accounts.addObject();
                u.put("username", user.username());
                u.put("email", user.email());
                u.put("firstName", user.firstName());
                u.put("lastName", user.lastName());
                u.put("createdAt", user.createdTimestamp() == null ? null
                        : ts(Instant.ofEpochMilli(user.createdTimestamp()).atOffset(ZoneOffset.UTC)));
            }
        }
    }

    private void writeVendor(ObjectNode v, TenantSection section) {
        v.put("reference", section.tenantId().toString());

        Recipient rec = section.recipient();
        ObjectNode recipient = v.putObject("recipient");
        recipient.put("legalName", rec.legalName());
        recipient.put("traderIdentityOnFile", rec.identityOnFile());
        recipient.put("entityType", rec.entityType());
        recipient.put("companyNumber", rec.companyNumber());
        recipient.put("vatNumber", rec.vatNumber());
        if (rec.address() == null) {
            recipient.putNull("address");
        } else {
            writeAddress(recipient.putObject("address"), rec.address());
        }
        ArrayNode shops = recipient.putArray("shops");
        rec.shops().forEach(shops::add);
        if (!rec.identityOnFile()) {
            recipient.put("note", NO_IDENTITY_NOTE);
        }

        ArrayNode customers = v.putArray("customerRecords");
        for (CustomerRecord c : section.customers()) {
            ObjectNode cn = customers.addObject();
            cn.put("name", c.name());
            cn.put("email", c.email());
            cn.put("phone", c.phone());
            putNames(cn, "allergenRestrictions", c.allergenRestrictions());
            cn.put("notes", c.notes());
            cn.put("createdAt", ts(c.createdAt()));
            cn.put("updatedAt", ts(c.updatedAt()));
        }

        ArrayNode orders = v.putArray("orders");
        for (OrderRecord o : section.orders()) {
            ObjectNode on = orders.addObject();
            on.put("orderNumber", o.orderNumber());
            on.put("shop", o.shop());
            on.put("status", o.status());
            on.put("placedAt", ts(o.placedAt()));
            on.put("fulfilmentType", o.fulfilmentType());
            on.put("customerName", o.customerName());
            on.put("customerEmail", o.customerEmail());
            on.put("customerPhone", o.customerPhone());
            if (o.deliveryAddress() == null) {
                on.putNull("deliveryAddress");
            } else {
                writeAddress(on.putObject("deliveryAddress"), o.deliveryAddress());
            }
            on.put("notes", o.notes());
            on.put("allergyNote", o.allergyNote());
            on.put("allergyNoteReadByShopAt", ts(o.allergyNoteReadByShopAt()));
            putNames(on, "acknowledgedAllergens", o.acknowledgedAllergens());
            on.put("acknowledgedAt", ts(o.acknowledgedAt()));
            on.put("paymentMethod", o.paymentMethod());
            on.put("paymentStatus", o.paymentStatus());
            ObjectNode totals = on.putObject("totals");
            totals.put("subtotalPennies", o.totals().subtotalPennies());
            totals.put("vatAmountPennies", o.totals().vatAmountPennies());
            totals.put("deliveryFeePennies", o.totals().deliveryFeePennies());
            totals.put("totalAmountPennies", o.totals().totalAmountPennies());
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
            rn.put("orderNumber", r.orderNumber());
            rn.put("name", r.name());
            rn.put("email", r.email());
            rn.put("foodRating", r.foodRating());
            rn.put("deliveryRating", r.deliveryRating());
            rn.put("comment", r.comment());
            ArrayNode photos = rn.putArray("photoUrls");
            r.photoUrls().forEach(photos::add);
            rn.put("createdAt", ts(r.createdAt()));
        }

        ObjectNode prefs = v.putObject("communicationPreferences");
        if (section.consent().marketingOptedInAt() == null) {
            prefs.putNull("marketingOptIn");
        } else {
            prefs.putObject("marketingOptIn").put("optedInAt", ts(section.consent().marketingOptedInAt()));
        }
        ArrayNode unsubscribed = prefs.putArray("unsubscribed");
        for (Unsubscribe u : section.consent().unsubscribed()) {
            ObjectNode un = unsubscribed.addObject();
            un.put("category", u.category());
            un.put("since", ts(u.since()));
        }

        ArrayNode staff = v.putArray("staffDirectory");
        for (StaffEntry s : section.staff()) {
            ObjectNode sn = staff.addObject();
            sn.put("displayName", s.displayName());
            sn.put("email", s.email());
            sn.put("lastSeen", ts(s.lastSeen()));
        }
    }

    private static void writeAddress(ObjectNode node, Address a) {
        node.put("line1", a.line1());
        node.put("line2", a.line2());
        node.put("city", a.city());
        node.put("postcode", a.postcode());
    }

    /** {@code null} stays null ("not recorded"); a list is written as an array, empty included. */
    private static void putNames(ObjectNode node, String field, List<String> names) {
        if (names == null) {
            node.putNull(field);
            return;
        }
        ArrayNode array = node.putArray(field);
        names.forEach(array::add);
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
    public record TenantSection(UUID tenantId, Recipient recipient, List<CustomerRecord> customers,
                                List<OrderRecord> orders, List<ReviewRecord> reviews, Consent consent,
                                List<StaffEntry> staff) {
    }

    /**
     * Article 15(1)(c): who received the data — the trader behind the tenant (31.1-10), and its shops.
     * With no trader identity on file every identity field is null and the shops alone name it.
     */
    public record Recipient(String legalName, boolean identityOnFile, String entityType, String companyNumber,
                            String vatNumber, Address address, List<String> shops) {
        static Recipient of(TraderIdentityDto identity, List<String> shops) {
            return new Recipient(identity.legalName(), true,
                    identity.entityType() == null ? null : identity.entityType().name(),
                    identity.companyNumber(), identity.vatNumber(),
                    new Address(identity.addressLine1(), identity.addressLine2(), identity.addressCity(),
                            identity.addressPostcode()),
                    shops);
        }

        static Recipient unknown(List<String> shops) {
            return new Recipient(null, false, null, null, null, null, shops);
        }
    }

    public record Address(String line1, String line2, String city, String postcode) {
    }

    public record CustomerRecord(String name, String email, String phone, List<String> allergenRestrictions,
                                 String notes, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record OrderRecord(String orderNumber, String shop, String status, OffsetDateTime placedAt,
                              String fulfilmentType, String customerName, String customerEmail,
                              String customerPhone, Address deliveryAddress, String notes, String allergyNote,
                              OffsetDateTime allergyNoteReadByShopAt, List<String> acknowledgedAllergens,
                              OffsetDateTime acknowledgedAt, String paymentMethod, String paymentStatus,
                              Totals totals, List<ItemRecord> items) {
    }

    public record Totals(Long subtotalPennies, Long vatAmountPennies, Long deliveryFeePennies,
                         Long totalAmountPennies) {
    }

    public record ItemRecord(String productName, Integer quantity, Long unitPricePennies,
                             Long totalPricePennies) {
    }

    public record ReviewRecord(UUID id, String shop, String orderNumber, String name, String email,
                               Integer foodRating, Integer deliveryRating, String comment, List<String> photoUrls,
                               OffsetDateTime createdAt) {
    }

    /** The V54 records: when the subject opted in to marketing (null = never), and what they unsubscribed from. */
    public record Consent(OffsetDateTime marketingOptedInAt, List<Unsubscribe> unsubscribed) {
        boolean isEmpty() {
            return marketingOptedInAt == null && unsubscribed.isEmpty();
        }
    }

    public record Unsubscribe(String category, OffsetDateTime since) {
    }

    public record StaffEntry(String displayName, String email, OffsetDateTime lastSeen) {
    }

    /** The readable single-use token and the expiry stamped on its row. */
    public record IssuedToken(String token, OffsetDateTime expiresAt) {
    }
}
