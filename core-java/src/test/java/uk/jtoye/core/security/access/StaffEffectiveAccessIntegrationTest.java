package uk.jtoye.core.security.access;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.JwtRolesAndScopesConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.ShopGrants;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * D-09 (Phase 37-05, RWO-003): "who can do what" is a fact the SERVER computes, read from the same
 * decision {@link ShopAccessService} enforces with — never reconstructed from raw grant rows.
 *
 * <p>{@code GET /api/v1/staff} carries {@code people[]}: every {@code user_directory} row of the
 * tenant plus every grant holder that has no directory row, each with an {@code effectiveAccess}.
 * The arms here are chosen so that a list which derived the level from {@code shop_staff} rows
 * would answer differently from enforcement:
 * <ul>
 *   <li>an ungranted user who has only READ (called {@code staff/me}) is listed, as {@code NONE}
 *       — before 37-05 the directory upsert ran on write paths only, so such a user never
 *       appeared at all;</li>
 *   <li>a JIT tenant-wide GROUP_ADMIN row reads GROUP_ADMIN only for the tenant's oldest JIT admin
 *       (the D-06 bootstrap), and NONE for a younger one, although both rows say GROUP_ADMIN;</li>
 *   <li>a directory write that fails never fails the request that triggered it (T-37-11).</li>
 * </ul>
 *
 * <p>Runs on whatever strict-scoping value the context booted with (the D-06 default, ON); it never
 * touches the switch. Each test has its own random tenant. The Testcontainers role is a SUPERUSER,
 * so the direct SQL reads below see every row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class StaffEffectiveAccessIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    /** A spy over the real repository: every call goes to the database unless a test stubs it. */
    @MockitoSpyBean private UserDirectoryRepository userDirectoryRepository;

    private UUID tenantId;
    private UUID shopA;
    private UUID shopB;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                tenantId, "37-05 effective access " + tenantId);
        shopA = seedShop("A");
        shopB = seedShop("B");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- Task 1: the people list and the read-path directory touch ----------------------------

    @Test
    @DisplayName("D-09: a signed-in user with no grant appears in people[] with effectiveAccess NONE")
    void signedInUngrantedUser_isListed_asNone() throws Exception {
        UUID admin = operatorGroupAdmin();
        UUID ungranted = UUID.randomUUID();

        MvcResult me = mockMvc.perform(get("/api/v1/staff/me").with(vendorJwt(ungranted))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(me.getResponse().getStatus()).as(body(me)).isEqualTo(200);

        MvcResult list = staffList(vendorJwt(admin));
        JsonNode person = person(list, ungranted);
        assertThat(person).as("the ungranted reader is listed: %s", body(list)).isNotNull();
        assertThat(person.path("effectiveAccess").path("level").asString())
                .as("computed level for an ungranted user: %s", body(list))
                .isEqualTo("NONE");
        assertThat(person.path("maskedEmail").asString())
                .as("the email is masked exactly as the directory picker masks it (WR-10)")
                .isEqualTo("u***@example.com");

        // Additive change: the existing fields are still there.
        JsonNode root = json(list);
        assertThat(root.path("directory").isArray()).as(body(list)).isTrue();
        assertThat(root.path("grants").isArray()).as(body(list)).isTrue();
        assertThat(root.path("directory").toString()).as("the directory still lists the reader")
                .contains(ungranted.toString());
    }

    @Test
    @DisplayName("T-37-11: a directory write that fails does not fail the request that triggered it")
    void directoryWriteFailure_doesNotFailTheRequest() throws Exception {
        UUID reader = UUID.randomUUID();
        // A genuine database failure, not a thrown stub: the statement aborts the transaction the
        // write runs in, exactly as a constraint or connection fault would.
        doAnswer(invocation -> {
            jdbc.execute("SELECT 1 / 0");
            return 0;
        }).when(userDirectoryRepository).upsertSeen(any(), eq(reader), any(), any(), any());

        MvcResult me = mockMvc.perform(get("/api/v1/staff/me").with(vendorJwt(reader))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        verify(userDirectoryRepository, atLeastOnce()).upsertSeen(any(), eq(reader), any(), any(), any());
        assertThat(me.getResponse().getStatus())
                .as("a failed directory write is best-effort: %s", body(me))
                .isEqualTo(200);
        assertThat(json(me).path("userId").asString()).as(body(me)).isEqualTo(reader.toString());
        assertThat(directoryRows(reader)).as("the failed write left no row").isZero();
    }

    @Test
    @DisplayName("D-09: an OPERATOR tenant-wide GROUP_ADMIN reads level GROUP_ADMIN on all shops, not bootstrap")
    void operatorGroupAdmin_isGroupAdmin() throws Exception {
        UUID admin = operatorGroupAdmin();

        MvcResult list = staffList(vendorJwt(admin));
        JsonNode self = person(list, admin);
        assertThat(self).as("the admin is listed: %s", body(list)).isNotNull();
        JsonNode access = self.path("effectiveAccess");

        assertThat(access.path("level").asString()).as(access.toString()).isEqualTo("GROUP_ADMIN");
        assertThat(access.path("allShops").asBoolean()).as(access.toString()).isTrue();
        assertThat(access.path("bootstrapAdmin").asBoolean()).as(access.toString()).isFalse();
    }

    @Test
    @DisplayName("D-06/D-09: with only JIT admins, the oldest reads GROUP_ADMIN (bootstrap) and a younger one NONE")
    void jitAdmins_bootstrapIsGroupAdmin_youngerIsNone() throws Exception {
        UUID oldest = UUID.randomUUID();
        UUID younger = UUID.randomUUID();
        OffsetDateTime base = OffsetDateTime.now().minusDays(10);
        seedJitGroupAdmin(oldest, base);
        seedJitGroupAdmin(younger, base.plusDays(1));
        UUID realmAdmin = UUID.randomUUID();   // reads the list without adding a shop_staff row

        MvcResult list = staffList(realmAdminJwt(realmAdmin));

        JsonNode bootstrap = person(list, oldest);
        assertThat(bootstrap).as("a grant holder with no directory row is listed: %s", body(list)).isNotNull();
        assertThat(bootstrap.path("effectiveAccess").path("level").asString()).as(body(list))
                .isEqualTo("GROUP_ADMIN");
        assertThat(bootstrap.path("effectiveAccess").path("bootstrapAdmin").asBoolean()).as(body(list))
                .isTrue();
        assertThat(bootstrap.path("maskedEmail").isNull())
                .as("no directory row, so no email to show: %s", bootstrap)
                .isTrue();

        JsonNode second = person(list, younger);
        assertThat(second).as(body(list)).isNotNull();
        assertThat(second.path("effectiveAccess").path("level").asString())
                .as("a younger JIT admin is de-honoured, whatever its row says: %s", body(list))
                .isEqualTo("NONE");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private UUID operatorGroupAdmin() {
        UUID admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");
        return admin;
    }

    private UUID seedShop(String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, true, 0, 0, 0)",
                id, tenantId, "Effective Access " + label + " " + id, "ea-" + id, "1 Test Street, London, E1 6AN");
        return id;
    }

    private void seedJitGroupAdmin(UUID user, OffsetDateTime createdAt) {
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source, created_at) "
                        + "VALUES (?, ?, ?, NULL, 'GROUP_ADMIN', 'JIT', ?)",
                UUID.randomUUID(), tenantId, user, createdAt);
    }

    private MvcResult staffList(RequestPostProcessor caller) throws Exception {
        MvcResult list = mockMvc.perform(get("/api/v1/staff").with(caller).accept(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(list.getResponse().getStatus()).as("staff list: %s", body(list)).isEqualTo(200);
        return list;
    }

    /** The people[] entry for {@code userId}, or null when the list does not carry one. */
    private JsonNode person(MvcResult list, UUID userId) throws Exception {
        for (JsonNode p : json(list).path("people")) {
            if (userId.toString().equals(p.path("userId").asString())) {
                return p;
            }
        }
        return null;
    }

    private long directoryRows(UUID user) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM user_directory WHERE tenant_id = ? AND user_id = ?",
                Long.class, tenantId, user);
        return n == null ? 0 : n;
    }

    private RequestPostProcessor vendorJwt(UUID sub) {
        return jwt().jwt(j -> j.subject(sub.toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("email", "user-" + sub + "@example.com")
                        .claim("name", "Vendor " + sub)
                        .claim("realm_access", Map.of("roles", List.of("user")))
                        .claim("scope", "catalog:read catalog:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    private RequestPostProcessor realmAdminJwt(UUID sub) {
        return jwt().jwt(j -> j.subject(sub.toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("email", "admin-" + sub + "@example.com")
                        .claim("name", "Realm Admin " + sub)
                        .claim("realm_access", Map.of("roles", List.of("admin")))
                        .claim("scope", "catalog:read catalog:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String body(MvcResult result) {
        try {
            return result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
        } catch (Exception e) {
            return "<unreadable body: " + e + ">";
        }
    }
}
