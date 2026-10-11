package uk.jtoye.core.customer;

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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.ShopGrants;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * WINDOWS #29, owner ruling 2026-10-09 (option B): under D-06 strict scoping every
 * {@code /api/v1/customers} operation requires the caller to hold an honoured grant — the same
 * predicate {@code GET /api/v1/staff/me} reports, so the API refuses exactly the people the dashboard
 * already shows "You don't have access yet / no longer".
 *
 * <p>37-15 measured the gap on the rebuilt runtime: an ungranted tenant user (fresh, or revoked)
 * read all of a tenant's customers with GET 200 and created one with POST 201. These arms drive the
 * REAL HTTP stack (MockMvc, the security filter chain, {@code @PreAuthorize}, the controller, the
 * idempotency store) against a Testcontainers Postgres with the real Flyway schema and RLS.
 *
 * <p><strong>The instrument is the problem TYPE, not the status.</strong> A {@code @PreAuthorize}
 * refusal is also a 403 but with the generic forbidden type, so every refusal asserts
 * {@code https://jtoye.uk/errors/shop-access-denied}; a status-only assertion could pass for the
 * wrong reason. Every refusal also asserts the response carries none of the seeded customer's data.
 *
 * <p>Every POST/PUT body is fully valid (the D-04 ordering trap: {@code @Valid} runs before the
 * gate, so an invalid body would 400 and mask the refusal). Every arm sets its strict-scoping
 * posture explicitly and runs in its own random tenant; the booted posture is captured and restored
 * through {@link StrictScopingGuard}, never reset to a literal.
 *
 * <p><strong>Option E is asserted here (owner ruling 2026-10-10, quick 261010-ppb):</strong> reads
 * keep the any-grant gate, create and update need SHOP_MANAGER on any shop (or Group admin), and
 * delete needs Group admin. The "option E:" arms assert the typed refusal's {@code requiredRole} as
 * well as its type, and re-read the table by SQL after every refused write. The floor runs before
 * the Idempotency-Key branch, so a STAFF caller replaying a manager's key is refused, never served
 * the stored 201.
 *
 * <p>Option C (per-shop narrowing of which customers a granted user sees) remains a separate future
 * ruling (deferred to Phase 39) and is deliberately NOT asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class CustomerAccessGateIntegrationTest {

    private static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";

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
    @Autowired private ShopAccessService shopAccessService;

    /** The value the context booted with: restored after every test, never a literal (37-02). */
    private Object bootedStrictScoping;

    @BeforeEach
    void captureStrictScoping() {
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
    }

    @AfterEach
    void restoreStrictScoping() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
    }

    // ------------------------------------------------------------------
    // Refusals: ungranted, revoked, de-honoured, replay
    // ------------------------------------------------------------------

    @Test
    @DisplayName("WINDOWS #29: an ungranted user is refused the customer list, and staff/me agrees they have no access")
    void ungranted_list_403() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID ungranted = UUID.randomUUID();

        MvcResult list = mockMvc.perform(get("/api/v1/customers").with(vendor(f.tenant, ungranted)))
                .andReturn();
        assertShopAccessDenied(list, f.customerEmail);

        JsonNode me = staffMe(f.tenant, ungranted);
        assertNoAccess(me);
    }

    @Test
    @DisplayName("WINDOWS #29: an ungranted user gets the same refusal for an existing and a non-existent customer id")
    void ungranted_getById_403() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID ungranted = UUID.randomUUID();

        MvcResult existing = mockMvc.perform(get("/api/v1/customers/" + f.customerId)
                        .with(vendor(f.tenant, ungranted)))
                .andReturn();
        MvcResult missing = mockMvc.perform(get("/api/v1/customers/" + UUID.randomUUID())
                        .with(vendor(f.tenant, ungranted)))
                .andReturn();

        assertShopAccessDenied(existing, f.customerEmail);
        assertShopAccessDenied(missing, f.customerEmail);
        assertThat(missing.getResponse().getStatus())
                .as("no existence oracle: the gate runs before the lookup")
                .isEqualTo(existing.getResponse().getStatus());
        assertThat(type(missing)).as("no existence oracle (type)").isEqualTo(type(existing));
    }

    @Test
    @DisplayName("WINDOWS #29: an ungranted user's valid create is refused and writes nothing")
    void ungranted_create_403_writesNothing() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID ungranted = UUID.randomUUID();
        String email = "ungranted-create-" + UUID.randomUUID() + "@example.com";

        MvcResult create = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, ungranted))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson("Refused Create", email)))
                .andReturn();

        assertShopAccessDenied(create, f.customerEmail);
        assertThat(countCustomersWithEmail(f.tenant, email)).as("the refused create wrote no row").isZero();

        // The keyed path too: with a FRESH Idempotency-Key the create runs inside
        // IdempotencyService.execute, which commits on its own. A gate placed after execute would
        // still answer 403 but leave the customer written, so the row count is the instrument here.
        String keyedEmail = "ungranted-keyed-create-" + UUID.randomUUID() + "@example.com";
        MvcResult keyed = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, ungranted))
                        .header("Idempotency-Key", "windows29-fresh-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson("Refused Keyed Create", keyedEmail)))
                .andReturn();

        assertShopAccessDenied(keyed, keyedEmail);
        assertThat(countCustomersWithEmail(f.tenant, keyedEmail))
                .as("the refused keyed create wrote no row (the gate runs before idempotencyService.execute)")
                .isZero();
    }

    @Test
    @DisplayName("WINDOWS #29: an ungranted user's valid update is refused and changes nothing")
    void ungranted_update_403_changesNothing() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID ungranted = UUID.randomUUID();

        MvcResult update = mockMvc.perform(put("/api/v1/customers/" + f.customerId).with(vendor(f.tenant, ungranted))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson("Renamed By Ungranted", f.customerEmail)))
                .andReturn();

        assertShopAccessDenied(update, f.customerEmail);
        assertThat(customerName(f.customerId)).as("the refused update changed nothing").isEqualTo(f.customerName);
    }

    @Test
    @DisplayName("WINDOWS #29: an ungranted user's delete is refused and the row survives")
    void ungranted_delete_403_deletesNothing() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID ungranted = UUID.randomUUID();

        MvcResult del = mockMvc.perform(delete("/api/v1/customers/" + f.customerId).with(vendor(f.tenant, ungranted)))
                .andReturn();

        assertShopAccessDenied(del, f.customerEmail);
        assertThat(customerExists(f.customerId)).as("the refused delete removed nothing").isTrue();
    }

    @Test
    @DisplayName("WINDOWS #29: revoking a user's last grant refuses their next customer request with the same token (D-08)")
    void revokedLastGrant_nextRequest_403() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID manager = UUID.randomUUID();
        UUID grantId = ShopGrants.grantOperator(jdbc, f.tenant, manager, f.shopId, "STAFF",
                "staff-" + manager + "@example.com");

        MvcResult before = mockMvc.perform(get("/api/v1/customers").with(vendor(f.tenant, manager))).andReturn();
        assertThat(before.getResponse().getStatus()).as("positive control before the revoke: %s", body(before))
                .isEqualTo(200);
        assertThat(before.getResponse().getContentAsString()).contains(f.customerEmail);

        MvcResult revoke = mockMvc.perform(delete("/api/v1/staff/" + grantId)
                        .with(realmAdmin(f.tenant, UUID.randomUUID())))
                .andReturn();
        assertThat(revoke.getResponse().getStatus()).as("realm admin revoke: %s", body(revoke)).isEqualTo(204);

        MvcResult after = mockMvc.perform(get("/api/v1/customers").with(vendor(f.tenant, manager))).andReturn();
        assertShopAccessDenied(after, f.customerEmail);
    }

    @Test
    @DisplayName("WINDOWS #29: a de-honoured JIT Group admin whose row still exists is refused, and staff/me agrees")
    void deHonouredJitGroupAdmin_403() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID operatorAdmin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, operatorAdmin, null, "GROUP_ADMIN",
                "admin-" + operatorAdmin + "@example.com");
        UUID jitUser = UUID.randomUUID();
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source, created_at) "
                        + "VALUES (?, ?, ?, NULL, 'GROUP_ADMIN', 'JIT', ?)",
                UUID.randomUUID(), f.tenant, jitUser, java.time.OffsetDateTime.now().minusDays(1));
        assertThat(shopStaffRows(f.tenant, jitUser))
                .as("instrument: the JIT row exists, so a refusal is the honour ladder, not a missing row")
                .isEqualTo(1L);

        MvcResult list = mockMvc.perform(get("/api/v1/customers").with(vendor(f.tenant, jitUser))).andReturn();
        assertShopAccessDenied(list, f.customerEmail);

        assertNoAccess(staffMe(f.tenant, jitUser));
    }

    @Test
    @DisplayName("WINDOWS #29: an ungranted user replaying a known Idempotency-Key is refused, never served the stored 201")
    void ungrantedReplayOfKnownIdempotencyKey_403_notStored201() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID granted = UUID.randomUUID();
        // Option E (2026-10-10): the creator must be a shop manager to create at all; the arm's
        // intent (an ungranted replay is refused) is unchanged.
        ShopGrants.grantOperator(jdbc, f.tenant, granted, f.shopId, "SHOP_MANAGER",
                "manager-" + granted + "@example.com");
        String key = "windows29-" + UUID.randomUUID();
        String email = "replayed-" + UUID.randomUUID() + "@example.com";
        String body = customerJson("Replay Target", email);

        MvcResult first = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, granted))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(first.getResponse().getStatus()).as("granted create: %s", body(first)).isEqualTo(201);
        String createdId = jsonMapper.readTree(first.getResponse().getContentAsString()).path("id").asString();
        assertThat(createdId).isNotBlank();

        MvcResult replay = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, granted))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(replay.getResponse().getStatus()).as("the legitimate replay still works: %s", body(replay))
                .isEqualTo(201);
        assertThat(jsonMapper.readTree(replay.getResponse().getContentAsString()).path("id").asString())
                .as("the legitimate replay returns the same customer").isEqualTo(createdId);

        UUID ungranted = UUID.randomUUID();
        MvcResult stolen = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, ungranted))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertShopAccessDenied(stolen, email);
        assertThat(stolen.getResponse().getContentAsString())
                .as("the refused replay must not carry the stored customer id")
                .doesNotContain(createdId);
    }

    // ------------------------------------------------------------------
    // Option E (owner ruling 2026-10-10): reads any grant, create/update SHOP_MANAGER, delete Group
    // admin; then the controls that the gate admits exactly who staff/me says has access
    // ------------------------------------------------------------------

    @Test
    @DisplayName("option E: STAFF of one shop still reads the customer list and a customer by id")
    void staffOfOneShop_readsSucceed() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID staff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, staff, f.shopId, "STAFF", "staff-" + staff + "@example.com");

        MvcResult list = mockMvc.perform(get("/api/v1/customers").with(vendor(f.tenant, staff))).andReturn();
        assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(200);
        assertThat(list.getResponse().getContentAsString()).contains(f.customerEmail);

        MvcResult one = mockMvc.perform(get("/api/v1/customers/" + f.customerId).with(vendor(f.tenant, staff)))
                .andReturn();
        assertThat(one.getResponse().getStatus()).as(body(one)).isEqualTo(200);
        assertThat(one.getResponse().getContentAsString()).contains(f.customerEmail);
    }

    @Test
    @DisplayName("option E: STAFF of one shop cannot create a customer, unkeyed or with a fresh key, and nothing is written")
    void staffOfOneShop_create_403_writesNothing() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID staff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, staff, f.shopId, "STAFF", "staff-" + staff + "@example.com");

        String email = "staff-create-" + UUID.randomUUID() + "@example.com";
        MvcResult create = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, staff))
                        .contentType(MediaType.APPLICATION_JSON).content(customerJson("Staff Create", email)))
                .andReturn();
        assertShopAccessDenied(create, email, "SHOP_MANAGER");
        assertThat(countCustomersWithEmail(f.tenant, email)).as("the refused unkeyed create wrote no row").isZero();

        // The keyed path: with a FRESH key the create would run inside IdempotencyService.execute,
        // which commits on its own, so a floor placed after execute would still answer 403 but leave
        // the row written. The row count is the instrument.
        String keyedEmail = "staff-keyed-create-" + UUID.randomUUID() + "@example.com";
        MvcResult keyed = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, staff))
                        .header("Idempotency-Key", "optionE-fresh-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(customerJson("Staff Keyed", keyedEmail)))
                .andReturn();
        assertShopAccessDenied(keyed, keyedEmail, "SHOP_MANAGER");
        assertThat(countCustomersWithEmail(f.tenant, keyedEmail))
                .as("the refused keyed create wrote no row (the floor runs before idempotencyService.execute)")
                .isZero();
    }

    @Test
    @DisplayName("option E: STAFF of one shop cannot update a customer, and the row is unchanged")
    void staffOfOneShop_update_403_changesNothing() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID staff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, staff, f.shopId, "STAFF", "staff-" + staff + "@example.com");

        MvcResult update = mockMvc.perform(put("/api/v1/customers/" + f.customerId).with(vendor(f.tenant, staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson("Renamed By Staff", f.customerEmail)))
                .andReturn();
        assertShopAccessDenied(update, "Renamed By Staff", "SHOP_MANAGER");
        assertThat(customerName(f.customerId)).as("the refused update changed nothing").isEqualTo(f.customerName);
    }

    @Test
    @DisplayName("option E: STAFF of one shop cannot delete a customer, and the row survives")
    void staffOfOneShop_delete_403_rowSurvives() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID staff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, staff, f.shopId, "STAFF", "staff-" + staff + "@example.com");

        MvcResult del = mockMvc.perform(delete("/api/v1/customers/" + f.customerId).with(vendor(f.tenant, staff)))
                .andReturn();
        assertShopAccessDenied(del, f.customerEmail, "GROUP_ADMIN");
        assertThat(customerExists(f.customerId)).as("the refused delete removed nothing").isTrue();
    }

    @Test
    @DisplayName("option E: a tenant-wide STAFF grant cannot create a customer")
    void tenantWideStaff_create_403() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID staff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, staff, null, "STAFF", "tw-staff-" + staff + "@example.com");

        String email = "tw-staff-create-" + UUID.randomUUID() + "@example.com";
        MvcResult create = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, staff))
                        .contentType(MediaType.APPLICATION_JSON).content(customerJson("TW Staff Create", email)))
                .andReturn();
        assertShopAccessDenied(create, email, "SHOP_MANAGER");
        assertThat(countCustomersWithEmail(f.tenant, email)).as("the refused create wrote no row").isZero();
    }

    @Test
    @DisplayName("option E: a SHOP_MANAGER of one shop creates and updates a customer")
    void shopManagerOfOneShop_createAndUpdate_succeed() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID manager = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, manager, f.shopId, "SHOP_MANAGER",
                "manager-" + manager + "@example.com");

        String email = "manager-create-" + UUID.randomUUID() + "@example.com";
        MvcResult create = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, manager))
                        .contentType(MediaType.APPLICATION_JSON).content(customerJson("Manager Created", email)))
                .andReturn();
        assertThat(create.getResponse().getStatus()).as(body(create)).isEqualTo(201);
        assertThat(countCustomersWithEmail(f.tenant, email)).isEqualTo(1L);

        MvcResult update = mockMvc.perform(put("/api/v1/customers/" + f.customerId).with(vendor(f.tenant, manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson("Renamed By Manager", f.customerEmail)))
                .andReturn();
        assertThat(update.getResponse().getStatus()).as(body(update)).isEqualTo(200);
        assertThat(customerName(f.customerId)).as("the update reached the row").isEqualTo("Renamed By Manager");
    }

    @Test
    @DisplayName("option E: a SHOP_MANAGER of one shop cannot delete a customer, and the row survives")
    void shopManagerOfOneShop_delete_403_rowSurvives() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID manager = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, manager, f.shopId, "SHOP_MANAGER",
                "manager-" + manager + "@example.com");

        MvcResult del = mockMvc.perform(delete("/api/v1/customers/" + f.customerId).with(vendor(f.tenant, manager)))
                .andReturn();
        assertShopAccessDenied(del, f.customerEmail, "GROUP_ADMIN");
        assertThat(customerExists(f.customerId)).as("the refused delete removed nothing").isTrue();
    }

    @Test
    @DisplayName("option E: an operator tenant-wide Group admin deletes a customer")
    void operatorGroupAdmin_delete_204() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");

        MvcResult del = mockMvc.perform(delete("/api/v1/customers/" + f.customerId).with(vendor(f.tenant, admin)))
                .andReturn();
        assertThat(del.getResponse().getStatus()).as(body(del)).isEqualTo(204);
        assertThat(customerExists(f.customerId)).as("the delete removed the row").isFalse();
    }

    @Test
    @DisplayName("option E: a tenant-wide SHOP_MANAGER in a tenant with no shops creates a customer")
    void tenantWideShopManagerWithNoShops_create_201() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, false);
        UUID manager = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, manager, null, "SHOP_MANAGER",
                "tw-manager-" + manager + "@example.com");

        String email = "tw-manager-create-" + UUID.randomUUID() + "@example.com";
        MvcResult create = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, manager))
                        .contentType(MediaType.APPLICATION_JSON).content(customerJson("TW Manager Created", email)))
                .andReturn();
        assertThat(create.getResponse().getStatus()).as(body(create)).isEqualTo(201);
        assertThat(countCustomersWithEmail(f.tenant, email)).isEqualTo(1L);
    }

    @Test
    @DisplayName("option E: a de-honoured JIT Group admin whose row still exists cannot create or delete a customer")
    void deHonouredJitGroupAdmin_createAndDelete_403() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID operatorAdmin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, operatorAdmin, null, "GROUP_ADMIN",
                "admin-" + operatorAdmin + "@example.com");
        UUID jitUser = UUID.randomUUID();
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source, created_at) "
                        + "VALUES (?, ?, ?, NULL, 'GROUP_ADMIN', 'JIT', ?)",
                UUID.randomUUID(), f.tenant, jitUser, java.time.OffsetDateTime.now().minusDays(1));
        assertThat(shopStaffRows(f.tenant, jitUser))
                .as("instrument: the JIT row exists, so a refusal is the honour ladder, not a missing row")
                .isEqualTo(1L);

        String email = "jit-create-" + UUID.randomUUID() + "@example.com";
        MvcResult create = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, jitUser))
                        .contentType(MediaType.APPLICATION_JSON).content(customerJson("JIT Create", email)))
                .andReturn();
        assertShopAccessDenied(create, email, "SHOP_MANAGER");
        assertThat(countCustomersWithEmail(f.tenant, email)).as("the refused create wrote no row").isZero();

        MvcResult del = mockMvc.perform(delete("/api/v1/customers/" + f.customerId).with(vendor(f.tenant, jitUser)))
                .andReturn();
        assertShopAccessDenied(del, f.customerEmail, "GROUP_ADMIN");
        assertThat(customerExists(f.customerId)).as("the refused delete removed nothing").isTrue();
    }

    @Test
    @DisplayName("option E: STAFF replaying a shop manager's Idempotency-Key is refused, never served the stored 201")
    void staffReplayOfShopManagerKey_403_notStored201() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID manager = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, manager, f.shopId, "SHOP_MANAGER",
                "manager-" + manager + "@example.com");
        UUID staff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, staff, f.shopId, "STAFF", "staff-" + staff + "@example.com");
        String key = "optionE-replay-" + UUID.randomUUID();
        String email = "manager-keyed-" + UUID.randomUUID() + "@example.com";
        String body = customerJson("Manager Keyed", email);

        MvcResult first = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, manager))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(first.getResponse().getStatus()).as("manager create: %s", body(first)).isEqualTo(201);
        String createdId = jsonMapper.readTree(first.getResponse().getContentAsString()).path("id").asString();
        assertThat(createdId).isNotBlank();

        MvcResult replay = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, manager))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(replay.getResponse().getStatus()).as("the manager's own replay: %s", body(replay)).isEqualTo(201);
        assertThat(jsonMapper.readTree(replay.getResponse().getContentAsString()).path("id").asString())
                .as("the manager's replay returns the same customer").isEqualTo(createdId);

        MvcResult stolen = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, staff))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertShopAccessDenied(stolen, email, "SHOP_MANAGER");
        assertThat(stolen.getResponse().getContentAsString())
                .as("the refused replay must not carry the stored customer id")
                .doesNotContain(createdId);
    }

    @Test
    @DisplayName("WINDOWS #29: a tenant-wide STAFF grant in a tenant with no shops is admitted (staff/me parity)")
    void tenantWideStaffWithNoShops_isAdmitted() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, false);
        UUID staff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, staff, null, "STAFF", "tw-staff-" + staff + "@example.com");

        MvcResult list = mockMvc.perform(get("/api/v1/customers").with(vendor(f.tenant, staff))).andReturn();
        assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(200);
        assertThat(list.getResponse().getContentAsString()).contains(f.customerEmail);

        JsonNode me = staffMe(f.tenant, staff);
        assertThat(me.path("groupAdmin").asBoolean()).as(me.toString()).isFalse();
        assertThat(me.path("tenantWideRole").asString()).as(me.toString()).isEqualTo("STAFF");
        assertThat(me.path("grantedShopIds").isArray()).as(me.toString()).isTrue();
        assertThat(me.path("grantedShopIds").size()).as("the tenant has no shops: %s", me).isZero();
    }

    @Test
    @DisplayName("WINDOWS #29: a realm admin with no shop_staff row is admitted")
    void realmAdmin_isAdmitted() throws Exception {
        StrictScopingGuard.set(shopAccessService, true);
        Fixture f = Fixture.seed(jdbc, true);
        UUID admin = UUID.randomUUID();
        assertThat(shopStaffRows(f.tenant, admin)).isZero();

        MvcResult list = mockMvc.perform(get("/api/v1/customers").with(realmAdmin(f.tenant, admin))).andReturn();
        assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(200);
        assertThat(list.getResponse().getContentAsString()).contains(f.customerEmail);
    }

    @Test
    @DisplayName("WINDOWS #29: with strict scoping OFF an ungranted user is unchanged and the gate writes no grant")
    void strictOff_ungrantedUnchanged_andNoJitRow() throws Exception {
        StrictScopingGuard.set(shopAccessService, false);
        Fixture f = Fixture.seed(jdbc, true);
        UUID ungranted = UUID.randomUUID();

        MvcResult list = mockMvc.perform(get("/api/v1/customers").with(vendor(f.tenant, ungranted))).andReturn();
        assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(200);
        assertThat(list.getResponse().getContentAsString()).contains(f.customerEmail);

        String email = "strict-off-" + UUID.randomUUID() + "@example.com";
        MvcResult create = mockMvc.perform(post("/api/v1/customers").with(vendor(f.tenant, ungranted))
                        .contentType(MediaType.APPLICATION_JSON).content(customerJson("Strict Off", email)))
                .andReturn();
        assertThat(create.getResponse().getStatus()).as(body(create)).isEqualTo(201);

        assertThat(shopStaffRows(f.tenant, ungranted))
                .as("the gate must not JIT-provision a grant for a customer caller (the count query sees "
                        + "a row when one exists: deHonouredJitGroupAdmin_403)")
                .isZero();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** One random tenant, optionally one shop, and one seeded customer with a unique email. */
    private record Fixture(UUID tenant, UUID shopId, UUID customerId, String customerName, String customerEmail) {
        static Fixture seed(JdbcTemplate jdbc, boolean withShop) {
            UUID tenant = UUID.randomUUID();
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                    tenant, "windows29-" + tenant);
            UUID shopId = null;
            if (withShop) {
                shopId = UUID.randomUUID();
                jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                                + "VALUES (?, ?, ?, ?, ?, true, 0)",
                        shopId, tenant, "W29 Shop " + shopId, "w29-" + shopId, "1 Test Street, London, E1 6AN");
            }
            UUID customerId = UUID.randomUUID();
            String name = "Seeded Customer " + customerId;
            String email = "seeded-" + customerId + "@example.com";
            jdbc.update("INSERT INTO customers (id, tenant_id, name, email, phone, allergen_restrictions, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 0, NOW(), NOW())",
                    customerId, tenant, name, email, "07700900000");
            return new Fixture(tenant, shopId, customerId, name, email);
        }
    }

    private UUID seedCustomer(UUID tenant, String name, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, tenant_id, name, email, phone, allergen_restrictions, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0, NOW(), NOW())",
                id, tenant, name, email, "07700900001");
        return id;
    }

    private static RequestPostProcessor vendor(UUID tenant, UUID sub) {
        return principal(tenant, sub, false);
    }

    private static RequestPostProcessor realmAdmin(UUID tenant, UUID sub) {
        return principal(tenant, sub, true);
    }

    private static RequestPostProcessor principal(UUID tenant, UUID sub, boolean realmAdmin) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("SCOPE_customers:write"));
        if (realmAdmin) {
            authorities.add(new SimpleGrantedAuthority("ROLE_admin"));
        }
        return jwt().jwt(j -> j.subject(sub.toString())
                        .claim("tenant_id", tenant.toString())
                        .claim("email", "user-" + sub + "@example.com"))
                .authorities(authorities);
    }

    private static String customerJson(String name, String email) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"phone\":\"07700900123\"}";
    }

    private JsonNode staffMe(UUID tenant, UUID sub) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/v1/staff/me").with(vendor(tenant, sub))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(me.getResponse().getStatus()).as(body(me)).isEqualTo(200);
        return jsonMapper.readTree(me.getResponse().getContentAsString());
    }

    /** staff/me's "no access": not a Group admin, no shops, no tenant-wide role. */
    private static void assertNoAccess(JsonNode me) {
        assertThat(me.path("groupAdmin").asBoolean()).as("staff/me groupAdmin: %s", me).isFalse();
        assertThat(me.path("grantedShopIds").isArray()).as("staff/me grantedShopIds: %s", me).isTrue();
        assertThat(me.path("grantedShopIds").size()).as("staff/me grantedShopIds: %s", me).isZero();
        JsonNode role = me.path("tenantWideRole");
        assertThat(role.isNull() || role.isMissingNode()).as("staff/me tenantWideRole: %s", me).isTrue();
    }

    private void assertShopAccessDenied(MvcResult result, String mustNotContain) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as("status, body: %s", content).isEqualTo(403);
        assertThat(type(result)).as("problem type, body: %s", content).isEqualTo(SHOP_ACCESS_DENIED);
        assertThat(content).as("a refusal leaks no customer data").doesNotContain(mustNotContain);
    }

    /** The typed refusal plus its machine-readable floor: the instrument for every option E arm. */
    private void assertShopAccessDenied(MvcResult result, String mustNotContain, String requiredRole)
            throws Exception {
        assertShopAccessDenied(result, mustNotContain);
        String content = result.getResponse().getContentAsString();
        assertThat(jsonMapper.readTree(content).path("requiredRole").asString())
                .as("requiredRole, body: %s", content).isEqualTo(requiredRole);
    }

    private String type(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        if (content == null || content.isBlank()) {
            return "<empty body>";
        }
        return jsonMapper.readTree(content).path("type").asString();
    }

    private long countCustomersWithEmail(UUID tenant, String email) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM customers WHERE tenant_id = ? AND email = ?",
                Long.class, tenant, email);
        return n == null ? 0 : n;
    }

    private String customerName(UUID id) {
        return jdbc.queryForObject("SELECT name FROM customers WHERE id = ?", String.class, id);
    }

    private boolean customerExists(UUID id) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ?", Long.class, id);
        return n != null && n > 0;
    }

    private long shopStaffRows(UUID tenant, UUID user) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM shop_staff WHERE tenant_id = ? AND user_id = ?",
                Long.class, tenant, user);
        return n == null ? 0 : n;
    }

    private static String body(MvcResult result) {
        try {
            return result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
        } catch (Exception e) {
            return "<unreadable body: " + e + ">";
        }
    }
}
