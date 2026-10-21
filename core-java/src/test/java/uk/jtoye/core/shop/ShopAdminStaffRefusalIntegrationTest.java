package uk.jtoye.core.shop;

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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.ShopGrants;
import uk.jtoye.core.testsupport.StrictScopingGuard;
import uk.jtoye.core.testsupport.TenantJwts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Issue #961 (SW-TND-06): the vendor UI offered a STAFF member Add shop, Edit shop and Delete shop, and
 * whether the API refused was untested. This drives the REAL HTTP stack (MockMvc, the security filter chain,
 * the controllers, the services' {@code ShopAccessService} gates) against a Testcontainers Postgres whose
 * application role is DOWNGRADED TO NOSUPERUSER before any request is made, so FORCE ROW LEVEL SECURITY is
 * genuinely in force underneath the gate (a superuser bypasses RLS and would hide a tenancy hole).
 *
 * <p><strong>Every arm is a (principal, verb, path) triple with a CONTROL arm that succeeds</strong> (a group
 * admin, a realm admin, a shop manager on their own shop), so a refusal cannot be an artefact of a malformed
 * body or a missing fixture: the same body succeeds for the role the model intends. A refusal asserts the
 * typed {@code shop-access-denied} problem (status 403) AND re-reads the database, so a 403 that still
 * mutated would fail too.
 *
 * <p>Fixtures: tenants are inserted once as the superuser, before the downgrade; every later row (shops,
 * grants, onboardings) is written through the RLS policies with the tenant GUC pinned, the same shape
 * {@link ShopGrants} uses. Every body is fully valid (the {@code @Valid}-before-gate ordering trap).
 *
 * <p>Set {@code MATRIX_OUT} to a file path to append one {@code label|status|problem-type} line per request
 * (the before/after table in the PR body is generated from it); unset, it does nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ShopAdminStaffRefusalIntegrationTest {

    private static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";
    private static final int TENANT_POOL = 64;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    /** Tenants seeded as superuser before the downgrade; each test takes fresh ones. */
    private static final Deque<UUID> SPARE_TENANTS = new ArrayDeque<>();
    private static boolean prepared;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private ShopAccessService shopAccessService;

    private Object bootedStrictScoping;

    @BeforeEach
    void prepare() {
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        StrictScopingGuard.set(shopAccessService, true);
        synchronized (SPARE_TENANTS) {
            if (!prepared) {
                for (int i = 0; i < TENANT_POOL; i++) {
                    UUID t = UUID.randomUUID();
                    jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", t, "ps961-" + t);
                    SPARE_TENANTS.push(t);
                }
                // The Testcontainers bootstrap role is a SUPERUSER and bypasses FORCE RLS. From here on every
                // statement, the application's and the fixtures', runs under RLS.
                jdbc.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
                assertThat(jdbc.queryForObject(
                        "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class))
                        .as("instrument: the application role must NOT bypass RLS, or no refusal below proves tenancy")
                        .isFalse();
                prepared = true;
            }
        }
    }

    @AfterEach
    void restoreStrictScoping() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
    }

    // ------------------------------------------------------------------
    // The headline: a STAFF principal on shop A
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#961: STAFF cannot create a shop")
    void staff_cannotCreateShop() throws Exception {
        World w = World.seed(this);
        String name = "Staff Made " + UUID.randomUUID();

        MvcResult create = call("STAFF POST /shops", post("/api/v1/shops").with(w.staffA)
                .contentType(MediaType.APPLICATION_JSON).content(shopJson(name)));

        assertDenied(create, "STAFF POST /shops");
        assertThat(shopsNamed(w.tenant, name)).as("the refused create wrote no shop").isZero();
    }

    @Test
    @DisplayName("#961: STAFF cannot edit the shop they are scoped to, another shop of the tenant, or another tenant's shop")
    void staff_cannotEditAnyShop() throws Exception {
        World w = World.seed(this);
        UUID foreignPublished = seedShop(w.otherTenant, "Foreign Published", true);
        UUID foreignHidden = seedShop(w.otherTenant, "Foreign Hidden", false);

        assertSoftly(s -> {
            try {
                MvcResult own = call("STAFF PUT /shops/{ownShop}", put("/api/v1/shops/" + w.shopA).with(w.staffA)
                        .contentType(MediaType.APPLICATION_JSON).content(shopJson("Hijacked A")));
                MvcResult sibling = call("STAFF PUT /shops/{otherShopSameTenant}",
                        put("/api/v1/shops/" + w.shopB).with(w.staffA)
                                .contentType(MediaType.APPLICATION_JSON).content(shopJson("Hijacked B")));
                MvcResult foreignPub = call("STAFF PUT /shops/{foreignTenantPublished}",
                        put("/api/v1/shops/" + foreignPublished).with(w.staffA)
                                .contentType(MediaType.APPLICATION_JSON).content(shopJson("Hijacked F1")));
                MvcResult foreignHid = call("STAFF PUT /shops/{foreignTenantUnpublished}",
                        put("/api/v1/shops/" + foreignHidden).with(w.staffA)
                                .contentType(MediaType.APPLICATION_JSON).content(shopJson("Hijacked F2")));
                softDenied(s, own, "own shop");
                softDenied(s, sibling, "sibling shop");
                softDenied(s, foreignPub, "foreign published shop");
                softDenied(s, foreignHid, "foreign unpublished shop");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertThat(shopName(w.tenant, w.shopA)).as("refused edit changed nothing (A)").isEqualTo(w.nameA);
        assertThat(shopName(w.tenant, w.shopB)).as("refused edit changed nothing (B)").isEqualTo(w.nameB);
        assertThat(shopName(w.otherTenant, foreignPublished)).isEqualTo("Foreign Published");
        assertThat(shopName(w.otherTenant, foreignHidden)).isEqualTo("Foreign Hidden");
    }

    @Test
    @DisplayName("#961: STAFF cannot delete the shop they are scoped to, another shop of the tenant, or another tenant's shop")
    void staff_cannotDeleteAnyShop() throws Exception {
        World w = World.seed(this);
        UUID foreignPublished = seedShop(w.otherTenant, "Foreign Published", true);
        UUID foreignHidden = seedShop(w.otherTenant, "Foreign Hidden", false);

        assertSoftly(s -> {
            try {
                softDenied(s, call("STAFF DELETE /shops/{ownShop}",
                        delete("/api/v1/shops/" + w.shopA).with(w.staffA)), "own shop");
                softDenied(s, call("STAFF DELETE /shops/{otherShopSameTenant}",
                        delete("/api/v1/shops/" + w.shopB).with(w.staffA)), "sibling shop");
                softDenied(s, call("STAFF DELETE /shops/{foreignTenantPublished}",
                        delete("/api/v1/shops/" + foreignPublished).with(w.staffA)), "foreign published shop");
                softDenied(s, call("STAFF DELETE /shops/{foreignTenantUnpublished}",
                        delete("/api/v1/shops/" + foreignHidden).with(w.staffA)), "foreign unpublished shop");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertThat(shopExists(w.tenant, w.shopA)).as("refused delete removed nothing (A)").isTrue();
        assertThat(shopExists(w.tenant, w.shopB)).as("refused delete removed nothing (B)").isTrue();
        assertThat(shopExists(w.otherTenant, foreignPublished)).isTrue();
        assertThat(shopExists(w.otherTenant, foreignHidden)).isTrue();
    }

    @Test
    @DisplayName("#961: a tenant-wide STAFF grant is refused the same shop mutations on every shop")
    void tenantWideStaff_cannotCreateEditOrDeleteShops() throws Exception {
        World w = World.seed(this);
        String name = "TW Staff Made " + UUID.randomUUID();

        assertSoftly(s -> {
            try {
                softDenied(s, call("STAFF(tenant-wide) POST /shops", post("/api/v1/shops").with(w.staffAll)
                        .contentType(MediaType.APPLICATION_JSON).content(shopJson(name))), "create");
                softDenied(s, call("STAFF(tenant-wide) PUT /shops/{id}", put("/api/v1/shops/" + w.shopB).with(w.staffAll)
                        .contentType(MediaType.APPLICATION_JSON).content(shopJson("TW Hijacked"))), "edit");
                softDenied(s, call("STAFF(tenant-wide) DELETE /shops/{id}",
                        delete("/api/v1/shops/" + w.shopB).with(w.staffAll)), "delete");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertThat(shopsNamed(w.tenant, name)).isZero();
        assertThat(shopName(w.tenant, w.shopB)).isEqualTo(w.nameB);
        assertThat(shopExists(w.tenant, w.shopB)).isTrue();
    }

    @Test
    @DisplayName("#961: STAFF cannot reach the other shop-admin mutations (media, slots, announcements, promotions)")
    void staff_cannotReachOtherShopAdminMutations() throws Exception {
        World w = World.seed(this);
        MockMultipartFile png = new MockMultipartFile("file", "x.png", "image/png", new byte[] {1, 2, 3});
        String slot = "{\"serviceDate\":\"2030-01-02\",\"windowStart\":\"10:00:00\",\"windowEnd\":\"12:00:00\","
                + "\"cutoffAt\":\"2030-01-01T09:00:00Z\",\"maxOrders\":5,\"maxUnits\":20}";
        String announcement = "{\"title\":\"Staff notice\",\"body\":\"x\",\"active\":true,\"shopId\":\"" + w.shopA + "\"}";
        String promotion = "{\"label\":\"Staff promo\",\"discountType\":\"PERCENTAGE\",\"discountPercent\":10,"
                + "\"validFrom\":\"2030-01-01T00:00:00Z\",\"validUntil\":\"2030-02-01T00:00:00Z\","
                + "\"active\":true,\"shopId\":\"" + w.shopA + "\"}";

        assertSoftly(s -> {
            try {
                softDenied(s, call("STAFF POST /shops/{id}/logo",
                        multipart("/api/v1/shops/" + w.shopA + "/logo").file(png).with(w.staffA)), "logo upload");
                softDenied(s, call("STAFF DELETE /shops/{id}/logo",
                        delete("/api/v1/shops/" + w.shopA + "/logo").with(w.staffA)), "logo remove");
                softDenied(s, call("STAFF POST /shops/{id}/banner",
                        multipart("/api/v1/shops/" + w.shopA + "/banner").file(png).with(w.staffA)), "banner upload");
                softDenied(s, call("STAFF DELETE /shops/{id}/banner",
                        delete("/api/v1/shops/" + w.shopA + "/banner").with(w.staffA)), "banner remove");
                softDenied(s, call("STAFF POST /shops/{id}/slots", post("/api/v1/shops/" + w.shopA + "/slots").with(w.staffA)
                        .contentType(MediaType.APPLICATION_JSON).content(slot)), "slot create");
                softDenied(s, call("STAFF DELETE /shops/{id}/slots/{slotId}",
                        delete("/api/v1/shops/" + w.shopA + "/slots/" + UUID.randomUUID()).with(w.staffA)), "slot deactivate");
                softDenied(s, call("STAFF POST /announcements", post("/api/v1/announcements").with(w.staffA)
                        .contentType(MediaType.APPLICATION_JSON).content(announcement)), "announcement create");
                softDenied(s, call("STAFF POST /promotions", post("/api/v1/promotions").with(w.staffA)
                        .contentType(MediaType.APPLICATION_JSON).content(promotion)), "promotion create");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertThat(count("SELECT count(*) FROM shop_announcements WHERE tenant_id = ?", w.tenant))
                .as("a refused announcement create wrote nothing").isZero();
    }

    @Test
    @DisplayName("#961: what STAFF legitimately needs keeps working: own shop read, list, slots read, pause and resume")
    void staff_keepsTheirOperationalAccess() throws Exception {
        World w = World.seed(this);

        assertSoftly(s -> {
            try {
                MvcResult list = call("STAFF GET /shops", get("/api/v1/shops").with(w.staffA));
                s.assertThat(list.getResponse().getStatus()).as("list: %s", body(list)).isEqualTo(200);
                s.assertThat(list.getResponse().getContentAsString())
                        .as("the list is narrowed to the granted shop").contains(w.nameA).doesNotContain(w.nameB);

                MvcResult own = call("STAFF GET /shops/{ownShop}", get("/api/v1/shops/" + w.shopA).with(w.staffA));
                s.assertThat(own.getResponse().getStatus()).as("own read: %s", body(own)).isEqualTo(200);

                MvcResult other = call("STAFF GET /shops/{otherShopSameTenant}",
                        get("/api/v1/shops/" + w.shopB).with(w.staffA));
                s.assertThat(other.getResponse().getStatus()).as("sibling read: %s", body(other)).isEqualTo(403);

                MvcResult slots = call("STAFF GET /shops/{id}/slots",
                        get("/api/v1/shops/" + w.shopA + "/slots").with(w.staffA));
                s.assertThat(slots.getResponse().getStatus()).as("slots read: %s", body(slots)).isEqualTo(200);

                MvcResult pause = call("STAFF PUT /shops/{id}/pause", put("/api/v1/shops/" + w.shopA + "/pause")
                        .with(w.staffA).contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":20}"));
                s.assertThat(pause.getResponse().getStatus()).as("pause: %s", body(pause)).isEqualTo(200);

                MvcResult resume = call("STAFF DELETE /shops/{id}/pause",
                        delete("/api/v1/shops/" + w.shopA + "/pause").with(w.staffA));
                s.assertThat(resume.getResponse().getStatus()).as("resume: %s", body(resume)).isEqualTo(200);

                MvcResult pauseOther = call("STAFF PUT /shops/{otherShopSameTenant}/pause",
                        put("/api/v1/shops/" + w.shopB + "/pause").with(w.staffA)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":20}"));
                s.assertThat(pauseOther.getResponse().getStatus()).as("pause sibling: %s", body(pauseOther)).isEqualTo(403);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    @DisplayName("#961: staff/me names the caller's role on each granted shop, so the dashboard can hide what the API refuses")
    void staffMe_namesTheRoleOnEachGrantedShop() throws Exception {
        World w = World.seed(this);

        JsonNode staff = me(call("STAFF GET /staff/me", get("/api/v1/staff/me").with(w.staffA)));
        JsonNode tenantWide = me(call("STAFF(tenant-wide) GET /staff/me", get("/api/v1/staff/me").with(w.staffAll)));
        JsonNode manager = me(call("SHOP_MANAGER GET /staff/me", get("/api/v1/staff/me").with(w.managerA)));
        JsonNode admin = me(call("GROUP_ADMIN GET /staff/me", get("/api/v1/staff/me").with(w.groupAdmin)));

        assertThat(staff.path("shopRoles").path(w.shopA.toString()).asString()).isEqualTo("STAFF");
        assertThat(staff.path("shopRoles").has(w.shopB.toString())).as("no role on an ungranted shop").isFalse();
        assertThat(tenantWide.path("shopRoles").path(w.shopA.toString()).asString()).isEqualTo("STAFF");
        assertThat(tenantWide.path("shopRoles").path(w.shopB.toString()).asString()).isEqualTo("STAFF");
        assertThat(manager.path("shopRoles").path(w.shopA.toString()).asString()).isEqualTo("SHOP_MANAGER");
        assertThat(admin.path("groupAdmin").asBoolean()).isTrue();
        assertThat(admin.path("shopRoles").isNull() || admin.path("shopRoles").isMissingNode())
                .as("a Group admin is unrestricted: no per-shop map").isTrue();
    }

    private JsonNode me(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------
    // Control arms: the roles the model intends keep working (so a refusal above can fail)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#961 control: a Group admin (operator grant) creates, edits and deletes shops")
    void groupAdmin_createsEditsAndDeletes() throws Exception {
        World w = World.seed(this);
        String name = "GA Made " + UUID.randomUUID();
        UUID victim = seedShop(w.tenant, "GA Victim", false);

        MvcResult create = call("GROUP_ADMIN POST /shops", post("/api/v1/shops").with(w.groupAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(shopJson(name)));
        MvcResult edit = call("GROUP_ADMIN PUT /shops/{id}", put("/api/v1/shops/" + w.shopB).with(w.groupAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(shopJson("GA Renamed")));
        MvcResult del = call("GROUP_ADMIN DELETE /shops/{id}", delete("/api/v1/shops/" + victim).with(w.groupAdmin));

        assertThat(create.getResponse().getStatus()).as("create: %s", body(create)).isEqualTo(201);
        assertThat(edit.getResponse().getStatus()).as("edit: %s", body(edit)).isEqualTo(200);
        assertThat(del.getResponse().getStatus()).as("delete: %s", body(del)).isEqualTo(204);
        assertThat(shopsNamed(w.tenant, name)).isEqualTo(1);
        assertThat(shopName(w.tenant, w.shopB)).isEqualTo("GA Renamed");
        assertThat(shopExists(w.tenant, victim)).isFalse();
    }

    @Test
    @DisplayName("#961 control: a realm admin (the owner bridge) creates, edits and deletes shops")
    void realmAdmin_createsEditsAndDeletes() throws Exception {
        World w = World.seed(this);
        String name = "RA Made " + UUID.randomUUID();
        UUID victim = seedShop(w.tenant, "RA Victim", false);

        MvcResult create = call("OWNER(realm admin) POST /shops", post("/api/v1/shops").with(TenantJwts.adminJwt(w.tenant))
                .contentType(MediaType.APPLICATION_JSON).content(shopJson(name)));
        MvcResult edit = call("OWNER(realm admin) PUT /shops/{id}", put("/api/v1/shops/" + w.shopB)
                .with(TenantJwts.adminJwt(w.tenant)).contentType(MediaType.APPLICATION_JSON).content(shopJson("RA Renamed")));
        MvcResult del = call("OWNER(realm admin) DELETE /shops/{id}",
                delete("/api/v1/shops/" + victim).with(TenantJwts.adminJwt(w.tenant)));

        assertThat(create.getResponse().getStatus()).as("create: %s", body(create)).isEqualTo(201);
        assertThat(edit.getResponse().getStatus()).as("edit: %s", body(edit)).isEqualTo(200);
        assertThat(del.getResponse().getStatus()).as("delete: %s", body(del)).isEqualTo(204);
        assertThat(shopExists(w.tenant, victim)).isFalse();
    }

    @Test
    @DisplayName("#961 control: a shop manager edits their own shop, but not another shop, and cannot create or delete shops")
    void shopManager_editsOwnShopOnly() throws Exception {
        World w = World.seed(this);
        String name = "SM Made " + UUID.randomUUID();

        MvcResult own = call("SHOP_MANAGER PUT /shops/{ownShop}", put("/api/v1/shops/" + w.shopA).with(w.managerA)
                .contentType(MediaType.APPLICATION_JSON).content(shopJson("SM Renamed A")));
        MvcResult sibling = call("SHOP_MANAGER PUT /shops/{otherShopSameTenant}",
                put("/api/v1/shops/" + w.shopB).with(w.managerA)
                        .contentType(MediaType.APPLICATION_JSON).content(shopJson("SM Hijacked B")));
        MvcResult create = call("SHOP_MANAGER POST /shops", post("/api/v1/shops").with(w.managerA)
                .contentType(MediaType.APPLICATION_JSON).content(shopJson(name)));
        MvcResult del = call("SHOP_MANAGER DELETE /shops/{ownShop}", delete("/api/v1/shops/" + w.shopA).with(w.managerA));

        assertThat(own.getResponse().getStatus()).as("own edit: %s", body(own)).isEqualTo(200);
        assertDenied(sibling, "SHOP_MANAGER sibling edit");
        assertDenied(create, "SHOP_MANAGER create");
        assertDenied(del, "SHOP_MANAGER delete");
        assertThat(shopName(w.tenant, w.shopA)).isEqualTo("SM Renamed A");
        assertThat(shopName(w.tenant, w.shopB)).isEqualTo(w.nameB);
        assertThat(shopExists(w.tenant, w.shopA)).isTrue();
        assertThat(shopsNamed(w.tenant, name)).isZero();
    }

    @Test
    @DisplayName("#961 control: a Group admin of one tenant cannot edit or delete another tenant's shop (404, nothing changes)")
    void groupAdmin_cannotCrossTenants() throws Exception {
        World w = World.seed(this);
        UUID foreign = seedShop(w.otherTenant, "Foreign Published", true);

        MvcResult edit = call("GROUP_ADMIN PUT /shops/{foreignTenantShop}", put("/api/v1/shops/" + foreign)
                .with(w.groupAdmin).contentType(MediaType.APPLICATION_JSON).content(shopJson("Cross Tenant")));
        MvcResult del = call("GROUP_ADMIN DELETE /shops/{foreignTenantShop}",
                delete("/api/v1/shops/" + foreign).with(w.groupAdmin));

        assertThat(edit.getResponse().getStatus()).as("edit: %s", body(edit)).isEqualTo(404);
        assertThat(del.getResponse().getStatus()).as("delete: %s", body(del)).isEqualTo(404);
        assertThat(shopName(w.otherTenant, foreign)).isEqualTo("Foreign Published");
    }

    // ------------------------------------------------------------------
    // Publish / go-live: the onboarding state machine is the sole writer of shops.published
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#961: STAFF cannot drive the onboarding lifecycle that publishes or withdraws the shop")
    void staff_cannotDriveOnboarding() throws Exception {
        Onb withdraw = Onb.seed(this);
        Onb companyNumber = Onb.seed(this);
        Onb submit = Onb.seed(this);
        Onb resubmit = Onb.seed(this);
        Onb goLive = Onb.seed(this);
        Onb create = Onb.seedWithoutOnboarding(this);

        assertSoftly(s -> {
            try {
                softDenied(s, call("STAFF POST /onboarding/withdraw",
                        post("/api/v1/onboarding/withdraw").with(withdraw.staff)), "withdraw");
                softDenied(s, call("STAFF POST /onboarding/company-number",
                        post("/api/v1/onboarding/company-number").with(companyNumber.staff)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"companyNumber\":\"12345678\"}")),
                        "company number");
                softDenied(s, call("STAFF POST /onboarding/submit",
                        post("/api/v1/onboarding/submit").with(submit.staff)), "submit");
                softDenied(s, call("STAFF POST /onboarding/resubmit",
                        post("/api/v1/onboarding/resubmit").with(resubmit.staff)), "resubmit");
                softDenied(s, call("STAFF POST /onboarding/go-live",
                        post("/api/v1/onboarding/go-live").with(goLive.staff)), "go live");
                softDenied(s, call("STAFF POST /onboarding", post("/api/v1/onboarding").with(create.staff)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"MARKETPLACE\",\"shopId\":\"" + create.shop + "\"}")), "create");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertThat(onboardingStatus(withdraw)).as("a refused withdraw changed nothing").isEqualTo("DRAFT");
        assertThat(onboardingStatus(submit)).as("a refused submit changed nothing").isEqualTo("DRAFT");
        assertThat(companyNumberOf(companyNumber)).as("a refused company-number edit changed nothing").isNull();
        assertThat(onboardingRows(create)).as("a refused create wrote no onboarding").isZero();
    }

    @Test
    @DisplayName("#961 control: the owner (realm admin) and a Group admin still drive the onboarding lifecycle")
    void owner_stillDrivesOnboarding() throws Exception {
        Onb withdraw = Onb.seed(this);
        Onb companyNumber = Onb.seed(this);
        Onb create = Onb.seedWithoutOnboarding(this);

        MvcResult w = call("OWNER(realm admin) POST /onboarding/withdraw",
                post("/api/v1/onboarding/withdraw").with(TenantJwts.adminJwt(withdraw.tenant)));
        MvcResult c = call("GROUP_ADMIN POST /onboarding/company-number",
                post("/api/v1/onboarding/company-number").with(companyNumber.groupAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"companyNumber\":\"12345678\"}"));
        MvcResult cr = call("GROUP_ADMIN POST /onboarding", post("/api/v1/onboarding").with(create.groupAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"MARKETPLACE\",\"shopId\":\"" + create.shop + "\"}"));

        assertThat(w.getResponse().getStatus()).as("withdraw: %s", body(w)).isEqualTo(200);
        assertThat(c.getResponse().getStatus()).as("company number: %s", body(c)).isEqualTo(200);
        assertThat(cr.getResponse().getStatus()).as("create: %s", body(cr)).isEqualTo(201);
        assertThat(onboardingStatus(withdraw)).isEqualTo("WITHDRAWN");
        assertThat(companyNumberOf(companyNumber)).isEqualTo("12345678");
    }

    // ------------------------------------------------------------------
    // World: one tenant with two shops and one principal per role, plus a second tenant
    // ------------------------------------------------------------------

    private record World(UUID tenant, UUID otherTenant, UUID shopA, UUID shopB, String nameA, String nameB,
                         RequestPostProcessor staffA, RequestPostProcessor staffAll,
                         RequestPostProcessor managerA, RequestPostProcessor groupAdmin) {
        static World seed(ShopAdminStaffRefusalIntegrationTest t) {
            UUID tenant = t.nextTenant();
            UUID other = t.nextTenant();
            String nameA = "Shop A " + UUID.randomUUID();
            String nameB = "Shop B " + UUID.randomUUID();
            UUID shopA = t.seedShop(tenant, nameA, true);
            UUID shopB = t.seedShop(tenant, nameB, true);
            UUID staff = UUID.randomUUID();
            UUID staffAll = UUID.randomUUID();
            UUID manager = UUID.randomUUID();
            UUID admin = UUID.randomUUID();
            ShopGrants.grantOperator(t.jdbc, tenant, staff, shopA, "STAFF", "staff-" + staff + "@example.com");
            ShopGrants.grantOperator(t.jdbc, tenant, staffAll, null, "STAFF", "staffall-" + staffAll + "@example.com");
            ShopGrants.grantOperator(t.jdbc, tenant, manager, shopA, "SHOP_MANAGER", "mgr-" + manager + "@example.com");
            ShopGrants.grantOperator(t.jdbc, tenant, admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");
            return new World(tenant, other, shopA, shopB, nameA, nameB,
                    TenantJwts.vendorJwt(staff, tenant), TenantJwts.vendorJwt(staffAll, tenant),
                    TenantJwts.vendorJwt(manager, tenant), TenantJwts.vendorJwt(admin, tenant));
        }
    }

    /** A tenant with one shop, a STAFF member of it, a Group admin and (optionally) a DRAFT onboarding. */
    private record Onb(UUID tenant, UUID shop, UUID onboarding, RequestPostProcessor staff,
                       RequestPostProcessor groupAdmin) {
        static Onb seed(ShopAdminStaffRefusalIntegrationTest t) {
            Onb o = seedWithoutOnboarding(t);
            UUID id = UUID.randomUUID();
            t.pinned(o.tenant, () -> t.jdbc.update("INSERT INTO vendor_onboarding (id, tenant_id, shop_id, model, status) "
                    + "VALUES (?, ?, ?, 'MARKETPLACE', 'DRAFT')", id, o.tenant, o.shop));
            return new Onb(o.tenant, o.shop, id, o.staff, o.groupAdmin);
        }

        static Onb seedWithoutOnboarding(ShopAdminStaffRefusalIntegrationTest t) {
            UUID tenant = t.nextTenant();
            UUID shop = t.seedShop(tenant, "Onb Shop " + UUID.randomUUID(), false);
            UUID staff = UUID.randomUUID();
            UUID admin = UUID.randomUUID();
            ShopGrants.grantOperator(t.jdbc, tenant, staff, shop, "STAFF", "staff-" + staff + "@example.com");
            ShopGrants.grantOperator(t.jdbc, tenant, admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");
            return new Onb(tenant, shop, null, TenantJwts.vendorJwt(staff, tenant), TenantJwts.vendorJwt(admin, tenant));
        }
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private UUID nextTenant() {
        synchronized (SPARE_TENANTS) {
            UUID t = SPARE_TENANTS.poll();
            if (t == null) {
                throw new IllegalStateException("tenant pool exhausted: raise TENANT_POOL");
            }
            return t;
        }
    }

    /** Runs {@code body} in one transaction with the tenant GUC pinned, so FORCE RLS admits the statements. */
    private <T> T pinned(UUID tenant, Supplier<T> body) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.current_tenant_id', ?, true)", String.class, tenant.toString());
            return body.get();
        });
    }

    private UUID seedShop(UUID tenant, String name, boolean published) {
        UUID id = UUID.randomUUID();
        pinned(tenant, () -> jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                + "VALUES (?, ?, ?, ?, ?, ?, 0)", id, tenant, name, "ps961-" + id, "1 Test Street, London, E1 6AN", published));
        return id;
    }

    private String shopName(UUID tenant, UUID shop) {
        return pinned(tenant, () -> jdbc.query("SELECT name FROM shops WHERE id = ?",
                (rs, i) -> rs.getString(1), shop).stream().findFirst().orElse(null));
    }

    private boolean shopExists(UUID tenant, UUID shop) {
        return shopName(tenant, shop) != null;
    }

    private long shopsNamed(UUID tenant, String name) {
        return pinned(tenant, () -> jdbc.queryForObject(
                "SELECT count(*) FROM shops WHERE tenant_id = ? AND name = ?", Long.class, tenant, name));
    }

    private long count(String sql, UUID tenant) {
        return pinned(tenant, () -> jdbc.queryForObject(sql, Long.class, tenant));
    }

    private String onboardingStatus(Onb o) {
        return pinned(o.tenant, () -> jdbc.queryForObject(
                "SELECT status FROM vendor_onboarding WHERE tenant_id = ?", String.class, o.tenant));
    }

    private String companyNumberOf(Onb o) {
        return pinned(o.tenant, () -> jdbc.queryForObject(
                "SELECT company_number FROM vendor_onboarding WHERE tenant_id = ?", String.class, o.tenant));
    }

    private long onboardingRows(Onb o) {
        return count("SELECT count(*) FROM vendor_onboarding WHERE tenant_id = ?", o.tenant);
    }

    private static String shopJson(String name) {
        return "{\"name\":\"" + name + "\",\"address\":\"1 Test Street, London, E1 6AN\",\"description\":\"d\"}";
    }

    private MvcResult call(String label, RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        recordMatrix(label, result);
        return result;
    }

    /** Appends {@code label|status|problem-type} to the file named by MATRIX_OUT, when set. */
    private void recordMatrix(String label, MvcResult result) throws Exception {
        String out = System.getenv("MATRIX_OUT");
        if (out == null || out.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(out), label + "|" + result.getResponse().getStatus() + "|" + type(result) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertDenied(MvcResult result, String what) throws Exception {
        assertThat(result.getResponse().getStatus()).as("%s status, body: %s", what, body(result)).isEqualTo(403);
        assertThat(type(result)).as("%s problem type, body: %s", what, body(result)).isEqualTo(SHOP_ACCESS_DENIED);
    }

    private void softDenied(org.assertj.core.api.SoftAssertions s, MvcResult result, String what) throws Exception {
        s.assertThat(result.getResponse().getStatus()).as("%s status, body: %s", what, body(result)).isEqualTo(403);
        s.assertThat(type(result)).as("%s problem type, body: %s", what, body(result)).isEqualTo(SHOP_ACCESS_DENIED);
    }

    private String type(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        if (content == null || content.isBlank()) {
            return "<empty body>";
        }
        JsonNode node = jsonMapper.readTree(content);
        return node.path("type").asString();
    }

    private static String body(MvcResult result) {
        try {
            return result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
        } catch (Exception e) {
            return "<unreadable>";
        }
    }
}
