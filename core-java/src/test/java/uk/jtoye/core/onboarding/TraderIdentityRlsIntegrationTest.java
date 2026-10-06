package uk.jtoye.core.onboarding;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.jtoye.core.testsupport.TenantJwts.vendorJwt;

/**
 * #789 (P0, D-10/D-11): the tenant's legal entity, the record customers buy FROM, proven against
 * the real posture. Postgres 15 with every migration applied, the application's connection role
 * downgraded to NOSUPERUSER so FORCE row-level security is genuinely enforced, and the real filter
 * chain behind MockMvc.
 *
 * <p>What is proven, one arm each:
 * <ul>
 *   <li>a tenant-wide GROUP_ADMIN saves the identity and reads it back, and the stored row carries
 *       the CALLER's tenant (never one from the request);</li>
 *   <li>a STAFF-rank user may read it but not change it (typed 403, nothing written);</li>
 *   <li>a tenant with no identity gets a typed 404, not an empty 200;</li>
 *   <li>under tenant A's GUC the database itself hides B's row, matches 0 rows on an UPDATE of
 *       it and REFUSES an INSERT stamped with B (SQLSTATE 42501), each beside a positive control
 *       that proves the same statement CAN succeed for A;</li>
 *   <li>the API never crosses tenants, even when the request carries B in the tenant header;</li>
 *   <li>the change is audited in {@code trader_identity_aud}, which A can read and B cannot, and
 *       whose INSERT policy refuses a foreign tenant (the V65 shape).</li>
 * </ul>
 *
 * <p>The class is deliberately NOT {@code @Transactional}: every seed and read runs in its own
 * transaction with the tenant GUC pinned on the SAME connection through {@code Session.doWork}
 * (the {@code GdprErasureReviewRlsIntegrationTest} recipe). Fresh tenants and users per test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class TraderIdentityRlsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String DOWNGRADED_APP_ROLE = "test";
    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);
    private static final String PATH = "/api/v1/trader-identity";
    private static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";
    private static final String NOT_FOUND = "https://jtoye.uk/errors/not-found";
    private static final String VALIDATION = "https://jtoye.uk/errors/validation";
    private static final String NO_IDENTITY_DETAIL = "No trader identity is on file for this tenant";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        // Only a superuser may run ALTER ROLE, so this must happen exactly once per container.
        if (DOWNGRADED.compareAndSet(false, true)) {
            assertThat(postgres.getUsername())
                    .as("the role this test downgrades must be the one it names")
                    .isEqualTo(DOWNGRADED_APP_ROLE);
            jdbc.execute("ALTER ROLE \"" + DOWNGRADED_APP_ROLE + "\" NOSUPERUSER");
        }
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = ?", Boolean.class,
                DOWNGRADED_APP_ROLE))
                .as("PRECONDITION: every assertion below is meaningless if the role still bypasses "
                        + "row-level security")
                .isFalse();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- 1. The tracer: save, read back, tenant is the caller's ----------------------------------

    @Test
    @DisplayName("GROUP_ADMIN PUT saves the legal entity, GET reads the same values, the row carries the caller's tenant")
    void groupAdminSavesAndReadsBack() throws Exception {
        UUID tenant = seedTenant();
        UUID admin = seedGroupAdmin(tenant);

        String saved = mockMvc.perform(put(PATH).with(vendorJwt(admin, tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Mama Ade Foods Ltd", "COMPANY", "12 Market Street", "Unit 4",
                                "Birmingham", "B1 1AA", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value("Mama Ade Foods Ltd"))
                .andExpect(jsonPath("$.entityType").value("COMPANY"))
                .andExpect(jsonPath("$.addressLine1").value("12 Market Street"))
                .andExpect(jsonPath("$.addressLine2").value("Unit 4"))
                .andExpect(jsonPath("$.addressCity").value("Birmingham"))
                .andExpect(jsonPath("$.addressPostcode").value("B1 1AA"))
                .andExpect(jsonPath("$.vatNumber").doesNotExist())
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(saved, "$.id");

        mockMvc.perform(get(PATH).with(vendorJwt(admin, tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.legalName").value("Mama Ade Foods Ltd"))
                .andExpect(jsonPath("$.entityType").value("COMPANY"))
                .andExpect(jsonPath("$.addressLine1").value("12 Market Street"))
                .andExpect(jsonPath("$.addressLine2").value("Unit 4"))
                .andExpect(jsonPath("$.addressCity").value("Birmingham"))
                .andExpect(jsonPath("$.addressPostcode").value("B1 1AA"));

        assertThat(this.<String>inTenant(tenant, c -> queryString(c,
                "SELECT tenant_id::text FROM trader_identity WHERE id = ?::uuid", id)))
                .as("the stored row's tenant is the caller's JWT tenant")
                .isEqualTo(tenant.toString());
    }

    // ---- 2. Only a group admin may change it ---------------------------------------------------

    @Test
    @DisplayName("STAFF PUT is 403 shop-access-denied and writes nothing; STAFF GET is 200")
    void staffMayReadButNotWrite() throws Exception {
        UUID tenant = seedTenant();
        UUID admin = seedGroupAdmin(tenant);
        UUID shop = seedShop(tenant);
        UUID staff = seedGrant(tenant, shop, "STAFF");
        adminPut(admin, tenant, "Original Trader Ltd");

        mockMvc.perform(put(PATH).with(vendorJwt(staff, tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Hijacked Ltd", "COMPANY", "1 Elsewhere", null, "London", "E1 6AN", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(SHOP_ACCESS_DENIED))
                .andExpect(jsonPath("$.requiredRole").value("GROUP_ADMIN"));

        assertThat(this.<String>inTenant(tenant, c -> queryString(c,
                "SELECT legal_name FROM trader_identity WHERE tenant_id = ?::uuid", tenant.toString())))
                .as("the denied PUT changed nothing")
                .isEqualTo("Original Trader Ltd");

        mockMvc.perform(get(PATH).with(vendorJwt(staff, tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value("Original Trader Ltd"));
    }

    // ---- 3. Absent is a typed 404 --------------------------------------------------------------

    @Test
    @DisplayName("GET with no identity on file is a 404 RFC 7807 problem, not an empty 200")
    void getWithNoRowIsTyped404() throws Exception {
        UUID tenant = seedTenant();
        UUID admin = seedGroupAdmin(tenant);

        // The detail is asserted, not just the type: a route that does not exist ALSO answers
        // 404 errors/not-found (NoResourceFoundException), so type + status alone passed on the
        // tree without this endpoint (measured in the RED run). The detail names the missing
        // RECORD, and the same tenant answers 200 once a record exists, so this arm can only
        // pass on a live endpoint that has nothing on file.
        mockMvc.perform(get(PATH).with(vendorJwt(admin, tenant)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(NOT_FOUND))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value(NO_IDENTITY_DETAIL));

        adminPut(admin, tenant, "Now On File Ltd");
        mockMvc.perform(get(PATH).with(vendorJwt(admin, tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value("Now On File Ltd"));
    }

    // ---- 4. The database wall, under A's GUC ----------------------------------------------------

    @Test
    @DisplayName("NOSUPERUSER under A's GUC: A's row visible, B's hidden, UPDATE of B matches 0, INSERT as B refused 42501")
    void databaseRefusesCrossTenantReadAndWrite() throws Exception {
        UUID a = seedTenant();
        UUID b = seedTenant();
        adminPut(seedGroupAdmin(a), a, "Tenant A Trader Ltd");
        adminPut(seedGroupAdmin(b), b, "Tenant B Trader Ltd");

        assertThat(this.<Long>inTenant(a, c -> queryLong(c,
                "SELECT count(*) FROM trader_identity WHERE tenant_id = ?::uuid", a.toString())))
                .as("PRECONDITION: A's session sees A's own row, so the zeros below are the wall, not an empty table")
                .isEqualTo(1L);
        assertThat(this.<Long>inTenant(a, c -> queryLong(c,
                "SELECT count(*) FROM trader_identity WHERE tenant_id = ?::uuid", b.toString())))
                .as("A's session cannot see B's row")
                .isZero();
        assertThat(this.<Long>inTenant(a, c -> queryLong(c, "SELECT count(*) FROM trader_identity")))
                .as("an unfiltered SELECT under A returns A's row only")
                .isEqualTo(1L);

        assertThat(this.<Integer>inTenant(a, c -> executeUpdate(c,
                "UPDATE trader_identity SET legal_name = legal_name WHERE tenant_id = ?::uuid", a.toString())))
                .as("POSITIVE CONTROL: the same UPDATE matches A's own row")
                .isEqualTo(1);
        assertThat(this.<Integer>inTenant(a, c -> executeUpdate(c,
                "UPDATE trader_identity SET legal_name = 'Overwritten by A' WHERE tenant_id = ?::uuid",
                b.toString())))
                .as("an UPDATE of B's row under A's GUC matches zero rows")
                .isZero();
        assertThat(this.<String>inTenant(b, c -> queryString(c,
                "SELECT legal_name FROM trader_identity WHERE tenant_id = ?::uuid", b.toString())))
                .as("B's row is untouched, read under B's own GUC")
                .isEqualTo("Tenant B Trader Ltd");

        UUID c = seedTenant();
        assertThat(this.<Integer>inTenant(c, conn -> executeUpdate(conn, insertSql(), UUID.randomUUID().toString(),
                c.toString())))
                .as("POSITIVE CONTROL: an INSERT stamped with the session's own tenant is accepted")
                .isEqualTo(1);

        // The foreign INSERT targets a tenant with NO identity yet, so UNIQUE(tenant_id) cannot be
        // what refuses it: measured with WITH CHECK opened to true, an INSERT stamped with B (which
        // already has a row) failed 23505 instead, which only the SQLSTATE assertion caught.
        UUID d = seedTenant();
        SQLException refusal = this.<SQLException>inTenant(a, conn -> refusalOf(conn, insertSql(),
                UUID.randomUUID().toString(), d.toString()));
        assertThat((Throwable) refusal)
                .as("an INSERT stamped with another tenant under A's GUC must be REFUSED by the database")
                .isNotNull();
        assertThat(refusal.getSQLState()).as("SQLSTATE (message: %s)", refusal.getMessage())
                .isEqualTo("42501");
        assertThat(refusal.getMessage()).contains("row-level security");
    }

    // ---- 5. The API never crosses tenants --------------------------------------------------------

    @Test
    @DisplayName("API under A (even with B in X-Tenant-Id) reads and writes A's row only; B's row is unchanged")
    void apiNeverCrossesTenants() throws Exception {
        UUID a = seedTenant();
        UUID b = seedTenant();
        UUID adminA = seedGroupAdmin(a);
        adminPut(adminA, a, "Tenant A Trader Ltd");
        adminPut(seedGroupAdmin(b), b, "Tenant B Trader Ltd");

        mockMvc.perform(get(PATH).with(vendorJwt(adminA, a)).header("X-Tenant-Id", b.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value("Tenant A Trader Ltd"));

        mockMvc.perform(put(PATH).with(vendorJwt(adminA, a)).header("X-Tenant-Id", b.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("A Renamed Ltd", "COMPANY", "1 A Street", null, "Leeds", "LS1 1AA", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value("A Renamed Ltd"));

        assertThat(this.<String>inTenant(b, c -> queryString(c,
                "SELECT legal_name FROM trader_identity WHERE tenant_id = ?::uuid", b.toString())))
                .as("B's identity is untouched by A's PUT")
                .isEqualTo("Tenant B Trader Ltd");
        assertThat(this.<String>inTenant(a, c -> queryString(c,
                "SELECT legal_name FROM trader_identity WHERE tenant_id = ?::uuid", a.toString())))
                .isEqualTo("A Renamed Ltd");
    }

    // ---- 6. Audited, and the audit trail is tenant-walled ----------------------------------------

    @Test
    @DisplayName("Each save writes trader_identity_aud; A reads its audit rows, B reads none; a foreign-tenant audit INSERT is refused")
    void auditTrailIsWrittenAndWalled() throws Exception {
        UUID a = seedTenant();
        UUID b = seedTenant();
        UUID adminA = seedGroupAdmin(a);
        String id = adminPut(adminA, a, "Audited Trader Ltd");
        adminPut(adminA, a, "Audited Trader Renamed Ltd");

        assertThat(this.<Long>inTenant(a, c -> queryLong(c,
                "SELECT count(*) FROM trader_identity_aud WHERE id = ?::uuid AND tenant_id = ?::uuid",
                id, a.toString())))
                .as("both saves are in the audit trail, readable under A")
                .isEqualTo(2L);
        assertThat(this.<String>inTenant(a, c -> queryString(c,
                "SELECT legal_name FROM trader_identity_aud WHERE id = ?::uuid ORDER BY rev ASC LIMIT 1", id)))
                .as("the first revision records the first legal name")
                .isEqualTo("Audited Trader Ltd");
        assertThat(this.<Long>inTenant(b, c -> queryLong(c,
                "SELECT count(*) FROM trader_identity_aud WHERE id = ?::uuid", id)))
                .as("B cannot read A's audit trail")
                .isZero();

        Integer rev = inTenant(a, c -> (int) queryLong(c,
                "SELECT max(rev) FROM trader_identity_aud WHERE id = ?::uuid", id));
        SQLException refusal = this.<SQLException>inTenant(a, conn -> refusalOf(conn,
                "INSERT INTO trader_identity_aud (id, rev, revtype, tenant_id, legal_name) "
                        + "VALUES (?::uuid, ?, 0, ?::uuid, 'Forged')",
                UUID.randomUUID().toString(), rev, b.toString()));
        assertThat((Throwable) refusal)
                .as("an audit row stamped with B under A's GUC must be refused")
                .isNotNull();
        assertThat(refusal.getSQLState()).as("SQLSTATE (message: %s)", refusal.getMessage())
                .isEqualTo("42501");
    }

    // ---- 7..11. Field boundaries (PGC-789 boundary) and adjacency (PGC-789 adjacency) ---------------

    @Test
    @DisplayName("legalName: 0 and blank refused, 1 and 255 accepted, 256 refused, 255 inside padding accepted and stored stripped")
    void legalNameBoundaries() throws Exception {
        UUID tenant = seedTenant();
        UUID admin = seedGroupAdmin(tenant);
        String max = "L".repeat(255);

        expectFieldError(admin, tenant, body("", "COMPANY", "1 Street", null, "London", "E1 6AN", null), "legalName");
        expectFieldError(admin, tenant, body("     ", "COMPANY", "1 Street", null, "London", "E1 6AN", null), "legalName");
        expectFieldError(admin, tenant, body(max + "L", "COMPANY", "1 Street", null, "London", "E1 6AN", null), "legalName");
        assertThat(identityCount(tenant)).as("no refused PUT wrote a row").isZero();

        expectSaved(admin, tenant, body("A", "SOLE_TRADER", "1 Street", null, "London", "E1 6AN", null))
                .andExpect(jsonPath("$.legalName").value("A"));
        expectSaved(admin, tenant, body(max, "COMPANY", "1 Street", null, "London", "E1 6AN", null))
                .andExpect(jsonPath("$.legalName").value(max));
        expectSaved(admin, tenant, body("  " + max + "  ", "COMPANY", "1 Street", null, "London", "E1 6AN", null))
                .andExpect(jsonPath("$.legalName").value(max));
        assertThat(this.<String>inTenant(tenant, c -> queryString(c,
                "SELECT legal_name FROM trader_identity WHERE tenant_id = ?::uuid", tenant.toString())))
                .as("the stored name is the stripped value, at the 255 limit")
                .hasSize(255);
    }

    @Test
    @DisplayName("addressPostcode: normalised to upper case with one space; malformed, short, long and blank refused")
    void postcodeBoundaries() throws Exception {
        UUID tenant = seedTenant();
        UUID admin = seedGroupAdmin(tenant);

        expectFieldError(admin, tenant, body("P Ltd", "COMPANY", "1 Street", null, "London", "NOTAPOSTCODE", null), "addressPostcode");
        expectFieldError(admin, tenant, body("P Ltd", "COMPANY", "1 Street", null, "London", "M1 1A", null), "addressPostcode");
        expectFieldError(admin, tenant, body("P Ltd", "COMPANY", "1 Street", null, "London", "SW1A 1AAA", null), "addressPostcode");
        expectFieldError(admin, tenant, body("P Ltd", "COMPANY", "1 Street", null, "London", "", null), "addressPostcode");
        assertThat(identityCount(tenant)).as("no refused PUT wrote a row").isZero();

        expectSaved(admin, tenant, body("P Ltd", "COMPANY", "1 Street", null, "London", "sw1a1aa", null))
                .andExpect(jsonPath("$.addressPostcode").value("SW1A 1AA"));
        expectSaved(admin, tenant, body("P Ltd", "COMPANY", "1 Street", null, "London", "  sw1a   1aa ", null))
                .andExpect(jsonPath("$.addressPostcode").value("SW1A 1AA"));
        expectSaved(admin, tenant, body("P Ltd", "COMPANY", "1 Street", null, "Manchester", "m11ae", null))
                .andExpect(jsonPath("$.addressPostcode").value("M1 1AE"));
        assertThat(this.<String>inTenant(tenant, c -> queryString(c,
                "SELECT address_postcode FROM trader_identity WHERE tenant_id = ?::uuid", tenant.toString())))
                .as("the stored postcode is the canonical form")
                .isEqualTo("M1 1AE");
    }

    @Test
    @DisplayName("vatNumber: absent or GB + 9 or 12 digits accepted (normalised); 8, 10, 13 digits and a non-GB prefix refused")
    void vatNumberBoundaries() throws Exception {
        UUID tenant = seedTenant();
        UUID admin = seedGroupAdmin(tenant);

        for (String bad : new String[] {"GB12345678", "GB1234567890", "GB1234567890123", "FR123456789", "123456789"}) {
            expectFieldError(admin, tenant, body("V Ltd", "COMPANY", "1 Street", null, "London", "E1 6AN", bad), "vatNumber");
        }
        assertThat(identityCount(tenant)).as("no refused PUT wrote a row").isZero();

        expectSaved(admin, tenant, body("V Ltd", "COMPANY", "1 Street", null, "London", "E1 6AN", "GB123456789"))
                .andExpect(jsonPath("$.vatNumber").value("GB123456789"));
        expectSaved(admin, tenant, body("V Ltd", "COMPANY", "1 Street", null, "London", "E1 6AN", "GB123456789012"))
                .andExpect(jsonPath("$.vatNumber").value("GB123456789012"));
        expectSaved(admin, tenant, body("V Ltd", "COMPANY", "1 Street", null, "London", "E1 6AN", "gb 123 4567 89"))
                .andExpect(jsonPath("$.vatNumber").value("GB123456789"));
        expectSaved(admin, tenant, body("V Ltd", "COMPANY", "1 Street", null, "London", "E1 6AN", ""))
                .andExpect(jsonPath("$.vatNumber").value(org.hamcrest.Matchers.nullValue()));
        expectSaved(admin, tenant, body("V Ltd", "COMPANY", "1 Street", null, "London", "E1 6AN", null))
                .andExpect(jsonPath("$.vatNumber").value(org.hamcrest.Matchers.nullValue()));
        assertThat(this.<String>inTenant(tenant, c -> queryString(c,
                "SELECT coalesce(vat_number, '<null>') FROM trader_identity WHERE tenant_id = ?::uuid",
                tenant.toString())))
                .as("an absent VAT number is stored as NULL, never an empty string")
                .isEqualTo("<null>");
    }

    @Test
    @DisplayName("A tenant with three shops enters the entity once; a manager of each shop reads the same row; no shop-level copy exists")
    void threeShopsResolveToOneEntity() throws Exception {
        UUID tenant = seedTenant();
        UUID admin = seedGroupAdmin(tenant);
        UUID[] shops = {seedShop(tenant), seedShop(tenant), seedShop(tenant)};
        String id = adminPut(admin, tenant, "Three Kitchens Ltd");

        for (UUID shop : shops) {
            UUID manager = seedGrant(tenant, shop, "SHOP_MANAGER");
            mockMvc.perform(get(PATH).with(vendorJwt(manager, tenant)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.legalName").value("Three Kitchens Ltd"));
        }
        assertThat(identityCount(tenant)).as("one entity for the whole tenant").isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = 'shops' AND column_name IN ('legal_name','vat_number','entity_type')",
                Long.class))
                .as("no shop-level copy of the legal entity exists in the schema")
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = 'trader_identity' AND column_name = 'legal_name'",
                Long.class))
                .as("POSITIVE CONTROL: the same information_schema query does find the column where it lives")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("PUT twice with the same body: same row id, version advanced, still one row")
    void putIsIdempotentByTenant() throws Exception {
        UUID tenant = seedTenant();
        UUID admin = seedGroupAdmin(tenant);
        String payload = body("Twice Ltd", "PARTNERSHIP", "2 Street", "Floor 1", "York", "YO1 7HH", "GB123456789");

        String first = expectSaved(admin, tenant, payload).andReturn().getResponse().getContentAsString();
        String second = expectSaved(admin, tenant, payload).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(((Number) JsonPath.read(second, "$.version")).longValue())
                .as("the second save is a new revision of the same row")
                .isEqualTo(((Number) JsonPath.read(first, "$.version")).longValue() + 1);
        for (String field : new String[] {"legalName", "entityType", "addressLine1", "addressLine2",
                "addressCity", "addressPostcode", "vatNumber"}) {
            assertThat((Object) JsonPath.read(second, "$." + field)).as(field)
                    .isEqualTo(JsonPath.read(first, "$." + field));
        }
        assertThat(identityCount(tenant)).isEqualTo(1L);
    }

    private void expectFieldError(UUID admin, UUID tenant, String payload, String field) throws Exception {
        mockMvc.perform(put(PATH).with(vendorJwt(admin, tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(VALIDATION))
                .andExpect(jsonPath("$.errors." + field).isNotEmpty());
    }

    private org.springframework.test.web.servlet.ResultActions expectSaved(UUID admin, UUID tenant, String payload)
            throws Exception {
        return mockMvc.perform(put(PATH).with(vendorJwt(admin, tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());
    }

    private long identityCount(UUID tenant) {
        return this.<Long>inTenant(tenant, c -> queryLong(c,
                "SELECT count(*) FROM trader_identity WHERE tenant_id = ?::uuid", tenant.toString()));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private String adminPut(UUID admin, UUID tenant, String legalName) throws Exception {
        MvcResult result = mockMvc.perform(put(PATH).with(vendorJwt(admin, tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(legalName, "COMPANY", "12 Market Street", null, "Birmingham", "B1 1AA", null)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    static String body(String legalName, String entityType, String line1, String line2, String city,
                       String postcode, String vatNumber) {
        return "{"
                + "\"legalName\":" + json(legalName) + ","
                + "\"entityType\":" + json(entityType) + ","
                + "\"addressLine1\":" + json(line1) + ","
                + "\"addressLine2\":" + json(line2) + ","
                + "\"addressCity\":" + json(city) + ","
                + "\"addressPostcode\":" + json(postcode) + ","
                + "\"vatNumber\":" + json(vatNumber)
                + "}";
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String insertSql() {
        return "INSERT INTO trader_identity (id, tenant_id, legal_name, entity_type, address_line1, "
                + "address_city, address_postcode) "
                + "VALUES (?::uuid, ?::uuid, 'Inserted Ltd', 'COMPANY', '1 Street', 'London', 'E1 6AN')";
    }

    private UUID seedTenant() {
        UUID id = UUID.randomUUID();
        // tenants carries no RLS.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "789-" + id);
        return id;
    }

    private UUID seedShop(UUID tenant) {
        UUID id = UUID.randomUUID();
        this.<Integer>inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, 'Test Address', false, 0)",
                id.toString(), tenant.toString(), "shop-" + id, "shop-789-" + id));
        return id;
    }

    /** A deliberate tenant-wide GROUP_ADMIN grant (OPERATOR, so it is honoured under any scoping mode). */
    private UUID seedGroupAdmin(UUID tenant) {
        UUID user = UUID.randomUUID();
        this.<Integer>inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, NULL, 'GROUP_ADMIN', 'OPERATOR')",
                UUID.randomUUID().toString(), tenant.toString(), user.toString()));
        return user;
    }

    /** One per-shop grant: the user is scoped to exactly that shop at that rank. */
    private UUID seedGrant(UUID tenant, UUID shop, String role) {
        UUID user = UUID.randomUUID();
        this.<Integer>inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, 'OPERATOR')",
                UUID.randomUUID().toString(), tenant.toString(), user.toString(), shop.toString(), role));
        return user;
    }

    private interface ConnectionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /** One fresh transaction with the tenant GUC pinned on the Hibernate transaction's own connection. */
    private <T> T inTenant(UUID tenant, ConnectionWork<T> work) {
        return new TransactionTemplate(txManager).execute(s ->
                entityManager.unwrap(Session.class).doReturningWork(connection -> {
                    try (PreparedStatement pin = connection.prepareStatement(
                            "SELECT set_config('app.current_tenant_id', ?, true)")) {
                        pin.setString(1, tenant.toString());
                        pin.execute();
                    }
                    return work.run(connection);
                }));
    }

    private static long queryLong(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String queryString(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static int executeUpdate(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /**
     * Runs a write that is expected to be refused and returns the refusal (null if it succeeded).
     * The statement runs inside a savepoint so the refusal does not poison the transaction, which
     * then commits nothing it should not: a refused statement wrote nothing by definition.
     */
    private static SQLException refusalOf(Connection c, String sql, Object... params) throws SQLException {
        var savepoint = c.setSavepoint();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
            c.rollback(savepoint);
            return null;
        } catch (SQLException e) {
            c.rollback(savepoint);
            return e;
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
