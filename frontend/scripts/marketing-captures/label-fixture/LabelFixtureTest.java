package uk.jtoye.core.product;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * THROWAWAY, never committed under core-java/. Renders one real 62 mm PPDS label with the
 * backend's own ProductLabelService for a FICTIONAL product, using mocked repositories (no
 * database, no container, nothing shared). See ./README.md.
 */
class LabelFixtureTest {
    @Test
    void writeLabel() throws Exception {
        UUID tenantId = UUID.randomUUID(), productId = UUID.randomUUID(), shopId = UUID.randomUUID();
        TenantContext.set(tenantId);
        ProductRepository products = Mockito.mock(ProductRepository.class);
        ShopRepository shops = Mockito.mock(ShopRepository.class);
        ShopAccessService access = Mockito.mock(ShopAccessService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);

        Product p = new Product();
        Field id = Product.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(p, productId);
        p.setTenantId(tenantId);
        p.setSku("JOLLOF-CHK");
        p.setTitle("Jollof rice with chicken");
        p.setIngredientsText("Long-grain rice, tomato, red pepper, onion, chicken thigh, vegetable stock (**celery**), thyme");
        p.setAllergenMask(1 << 8);
        p.setPricePennies(950L);
        p.setShelfLifeDays(2);
        p.setDurabilityType("USE_BY");
        p.setShopId(shopId);
        Shop s = new Shop();
        s.setName("Adaeze's Kitchen Ltd");
        s.setAddress("Example Row, Peckham, London");

        Mockito.when(products.findById(productId)).thenReturn(Optional.of(p));
        Mockito.when(shops.findByIdAndTenantId(shopId, tenantId)).thenReturn(Optional.of(s));

        byte[] pdf = new ProductLabelService(products, shops, access, clock)
                .generateLabel(productId, LocalDate.of(2026, 10, 23), ProductLabelService.LabelFormat.THERMAL_62MM);
        Files.write(Path.of(System.getenv("LABEL_OUT")), pdf);
        TenantContext.clear();
    }
}
