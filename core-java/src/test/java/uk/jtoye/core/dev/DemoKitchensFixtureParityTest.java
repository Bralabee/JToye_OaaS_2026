package uk.jtoye.core.dev;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.product.IngredientMarkupParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The staging demo kitchens ARE the dev demo kitchens (quick 261102-r6u, owner decisions D-01 as ruled
 * and D-05, design D17). {@code scripts/fixtures/demo-kitchens.json} is the only source the staging
 * seed reads ({@code scripts/staging-seed.sh --stage demo-kitchens}); this test holds it equal to
 * {@link DemoDataSeeder}'s own curated shops and menus, read from the seeder's data and never from a
 * second literal copy, so a price, a dish or an allergen changed on one side and not the other fails
 * here rather than drifting silently between dev and staging.
 *
 * <p>Pure file and POJO assertions: no Spring context, no database.
 */
class DemoKitchensFixtureParityTest {

    private static JsonNode fixture;

    @BeforeAll
    static void readFixture() throws Exception {
        Path file = repoRoot().resolve("scripts/fixtures/demo-kitchens.json");
        assertThat(Files.exists(file)).as("the fixture exists at " + file).isTrue();
        fixture = JsonMapper.builder().build().readTree(Files.readString(file));
    }

    @Test
    @DisplayName("three kitchens, 21 products, schemaVersion 1, in the seeder's order")
    void shapeAndOrder() {
        assertThat(fixture.get("schemaVersion").asInt()).isEqualTo(1);
        List<String> slugs = new ArrayList<>();
        int products = 0;
        for (JsonNode k : fixture.get("kitchens")) {
            slugs.add(k.get("shop").get("slug").asString());
            products += k.get("menu").size();
        }
        assertThat(slugs).containsExactlyElementsOf(
                DemoDataSeeder.CURATED_SHOPS.stream().map(DemoDataSeeder.CuratedShop::slug).toList());
        assertThat(slugs).hasSize(3);
        assertThat(products).isEqualTo(21);
    }

    @Test
    @DisplayName("each shop equals the seeder's curated shop, field by field")
    void shopsEqualTheSeeder() {
        int i = 0;
        for (DemoDataSeeder.CuratedShop c : DemoDataSeeder.CURATED_SHOPS) {
            JsonNode k = fixture.get("kitchens").get(i++);
            JsonNode s = k.get("shop");
            assertThat(s.get("name").asString()).isEqualTo(c.name());
            assertThat(s.get("slug").asString()).isEqualTo(c.slug());
            assertThat(s.get("address").asString()).isEqualTo(c.address());
            assertThat(s.get("description").asString()).isEqualTo(c.description());
            assertThat(s.get("tags").asString()).isEqualTo(c.tags());
            assertThat(s.get("logoUrl").asString()).isEqualTo(c.logoUrl());
            assertThat(s.get("email").asString()).isEqualTo(DemoDataSeeder.curatedShopEmail(c.slug()));
            assertThat(s.get("deliveryFeePennies").asLong()).as(c.slug() + " delivery fee").isEqualTo(c.deliveryFeePennies());
            assertThat(s.get("freeDeliveryThresholdPennies").asLong()).isEqualTo(c.freeDeliveryThresholdPennies());
            assertThat(s.get("minimumOrderPennies").asLong()).isEqualTo(DemoDataSeeder.CURATED_MINIMUM_ORDER_PENNIES);
            // D-01 as ruled: one demo tenant per kitchen, never deliverable usernames.
            assertThat(k.get("tenantName").asString()).isEqualTo("J'Toye Demo — " + c.name());
            assertThat(k.get("vendorUsername").asString()).isEqualTo("vendor@" + c.slug() + ".example.com");
        }
    }

    @Test
    @DisplayName("each seller of record reads '<kitchen name> (demo)' (D-05) at the seeder's split address")
    void traderIsTheDemoSeller() {
        int i = 0;
        for (DemoDataSeeder.CuratedShop c : DemoDataSeeder.CURATED_SHOPS) {
            JsonNode t = fixture.get("kitchens").get(i++).get("trader");
            DemoDataSeeder.SeededAddress a = DemoDataSeeder.SeededAddress.split(c.address());
            assertThat(t.get("legalName").asString()).isEqualTo(c.name() + " (demo)");
            assertThat(t.get("entityType").asString()).isEqualTo("SOLE_TRADER");
            assertThat(t.get("addressLine1").asString()).isEqualTo(a.line1());
            assertThat(text(t.get("addressLine2"))).isEqualTo(a.line2());
            assertThat(t.get("addressCity").asString()).isEqualTo(a.city());
            assertThat(t.get("addressPostcode").asString()).isEqualTo(a.postcode());
            assertThat(t.get("vatNumber").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("each menu equals the seeder's MenuItems in order, with its durability and derived description")
    void menusEqualTheSeeder() {
        int i = 0;
        for (DemoDataSeeder.CuratedShop c : DemoDataSeeder.CURATED_SHOPS) {
            JsonNode menu = fixture.get("kitchens").get(i++).get("menu");
            List<DemoDataSeeder.MenuItem> items = DemoDataSeeder.curatedMenusBySlug().get(c.slug());
            assertThat(menu.size()).as(c.slug() + " menu size").isEqualTo(items.size());
            for (int j = 0; j < items.size(); j++) {
                DemoDataSeeder.MenuItem m = items.get(j);
                JsonNode p = menu.get(j);
                String at = c.slug() + " item " + j + " (" + m.sku() + ")";
                assertThat(p.get("sku").asString()).as(at).isEqualTo(m.sku());
                assertThat(p.get("title").asString()).as(at).isEqualTo(m.title());
                assertThat(p.get("category").asString()).as(at).isEqualTo(m.category());
                assertThat(p.get("pricePennies").asLong()).as(at + " price").isEqualTo(m.pricePennies());
                assertThat(p.get("ingredientsText").asString()).as(at).isEqualTo(m.ingredients());
                assertThat(p.get("featured").asBoolean()).as(at).isEqualTo(m.featured());
                assertThat(text(p.get("dietaryTags"))).as(at).isEqualTo(m.dietaryTags());
                assertThat(p.get("allergenMask").asInt()).as(at + " allergens").isEqualTo(m.allergenMask());
                assertThat(p.get("durabilityType").asString()).as(at).isEqualTo(DemoDataSeeder.CURATED_DURABILITY_TYPE);
                assertThat(p.get("shelfLifeDays").asInt()).as(at).isEqualTo(DemoDataSeeder.CURATED_SHELF_LIFE_DAYS);
                assertThat(p.get("description").asString()).as(at)
                        .isEqualTo(m.title() + " — " + IngredientMarkupParser.parse(m.ingredients()).plainText());
            }
        }
    }

    /** A JSON null reads as Java null (Jackson 3 renders a NullNode as "" through asString). */
    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("Could not locate repo root (settings.gradle.kts) above "
                    + Path.of("").toAbsolutePath());
        }
        return dir;
    }
}
