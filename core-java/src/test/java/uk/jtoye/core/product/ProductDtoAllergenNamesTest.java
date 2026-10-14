package uk.jtoye.core.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.product.dto.ProductDto;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 37-42 (RWO-040 / UXT-040): a product says which allergens it declares in words, not only as
 * an integer.
 *
 * <p>An agent reading {@code list_products} got {@code allergenMask: 65} and had to know the UK FSA
 * 14-bit layout to say "gluten and milk". Copying the 14 names into the MCP server would be a third
 * copy of the catalogue beside {@link AllergenCatalog} and {@code frontend/types/api.ts} (whose parity
 * test binds only those two), so {@code ProductDto.allergens} carries the names, from
 * {@link AllergenCatalog#namesFor(int)}, in catalogue order, beside the unchanged mask.
 *
 * <p>The assertions read the serialised JSON, which is what an agent and the dashboard receive.
 */
class ProductDtoAllergenNamesTest {

    private final ProductMapper mapper = new ProductMapperImpl();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("UXT-040: mask bits 0 and 6 read as [Gluten, Milk], beside the unchanged mask")
    void glutenAndMilkAreNamed() {
        JsonNode dto = wire(product(0b100_0001));

        assertThat(names(dto.path("allergens"))).containsExactly("Gluten", "Milk");
        assertThat(dto.path("allergenMask").asInt()).as("the mask is kept as declared").isEqualTo(65);
    }

    @Test
    @DisplayName("mask 0 is an explicit empty list: the vendor declared none of the 14")
    void zeroMaskIsAnEmptyList() {
        JsonNode allergens = wire(product(0)).path("allergens");

        assertThat(allergens.isArray()).as("present and an array: " + allergens).isTrue();
        assertThat(names(allergens)).isEmpty();
    }

    @Test
    @DisplayName("names follow the catalogue's bit order, not the order anyone typed them")
    void namesAreInCatalogueOrder() {
        JsonNode dto = wire(product((1 << 13) | (1 << 10) | 1));

        assertThat(names(dto.path("allergens"))).containsExactly("Gluten", "Sesame", "Molluscs");
    }

    @Test
    @DisplayName("all 14 bits give exactly the catalogue's names: one source, no third copy")
    void everyBitComesFromTheCatalogue() {
        int all = (1 << AllergenCatalog.BIT_COUNT) - 1;

        assertThat(names(wire(product(all)).path("allergens")))
                .hasSize(14)
                .containsExactlyElementsOf(AllergenCatalog.namesFor(all));
    }

    @Test
    @DisplayName("the may-contain mask is never folded into the declared names (D-16)")
    void mayContainIsNotADeclaration() {
        Product product = product(1);
        product.setMayContainMask(1 << 10);

        assertThat(names(wire(product).path("allergens"))).containsExactly("Gluten");
    }

    private JsonNode wire(Product product) {
        ProductDto dto = mapper.toDto(product);
        return json.readTree(json.writeValueAsString(dto));
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    private static Product product(int mask) {
        Product product = new Product();
        product.setTitle("Probe roll");
        product.setIngredientsText("flour, water, salt");
        product.setAllergenMask(mask);
        product.setPricePennies(250L);
        return product;
    }
}
