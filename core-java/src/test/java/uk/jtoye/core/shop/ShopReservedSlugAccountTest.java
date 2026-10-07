package uk.jtoye.core.shop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.exception.ReservedSlugException;
import uk.jtoye.core.geo.PostcodeGeocoder;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;
import uk.jtoye.core.storage.StorageService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pitfall 14 (31.1-26, #838): {@code /shop/account} is a STATIC storefront route beside the dynamic
 * {@code /shop/[slug]}, and Next.js resolves the static segment first. A shop slugged {@code account}
 * would be unreachable at its own URL — and, the other way round, nothing may ever shadow the page a
 * customer uses to exercise their data rights.
 *
 * <p>The existing {@code ShopServiceTest} reserved-slug tests inject a hardcoded set by reflection, so
 * they prove the GUARD and say nothing about the LIST. This test reads the list from the two places
 * production reads it — the {@code @Value} default and {@code application.yml} — through real Spring
 * binding (a comma-separated value bound to {@code Set<String>}), so removing {@code account} from
 * either source turns it red. A third arm walks {@code frontend/app/shop/} and requires every static
 * segment there to be reserved in both sources, which is the drift the application.yml comment asks a
 * human to remember.
 */
class ShopReservedSlugAccountTest {

    private static final Path STOREFRONT_SHOP_ROUTES = Path.of("..", "frontend", "app", "shop");

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** A ShopService bean built by Spring, so the {@code @Value} field is resolved, not injected by hand. */
    private ApplicationContextRunner runner() {
        ShopRepository repository = mock(ShopRepository.class);
        ShopMapper mapper = mock(ShopMapper.class);
        PostcodeGeocoder geocoder = mock(PostcodeGeocoder.class);
        when(geocoder.locate(any())).thenReturn(Optional.empty());
        when(mapper.toEntity(any(CreateShopRequest.class))).thenAnswer(inv -> {
            CreateShopRequest req = inv.getArgument(0);
            Shop shop = new Shop();
            shop.setName(req.getName());
            shop.setAddress(req.getAddress());
            shop.setSlug(req.getSlug());
            return shop;
        });
        when(mapper.toDto(any(Shop.class))).thenAnswer(inv -> {
            Shop shop = inv.getArgument(0);
            ShopDto dto = new ShopDto();
            dto.setName(shop.getName());
            dto.setSlug(shop.getSlug());
            return dto;
        });
        when(repository.saveAndFlush(any(Shop.class))).thenAnswer(inv -> inv.getArgument(0));
        return new ApplicationContextRunner()
                // The conversion service Spring Boot installs on every application's bean factory: it
                // binds the comma-separated value to Set<String> exactly as production does (and the
                // Duration-typed @Value fields a Mockito subclass of ShopAccessService still carries).
                .withInitializer(ctx -> ctx.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(ShopRepository.class, () -> repository)
                .withBean(ShopMapper.class, () -> mapper)
                .withBean(StorageService.class, () -> mock(StorageService.class))
                .withBean(TenantCacheEvictor.class, () -> mock(TenantCacheEvictor.class))
                .withBean(ShopAccessService.class, () -> mock(ShopAccessService.class))
                .withBean(ShopService.ShopCacheLoader.class, () -> mock(ShopService.ShopCacheLoader.class))
                .withBean(PostcodeGeocoder.class, () -> geocoder)
                .withBean(ShopService.class);
    }

    /** The base document of application.yml, with the OS environment removed so the file's own default applies. */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> applicationYml() {
        return ctx -> {
            ConfigurableEnvironment env = ctx.getEnvironment();
            env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            try {
                List<PropertySource<?>> docs = new YamlPropertySourceLoader()
                        .load("application.yml", new ClassPathResource("application.yml"));
                env.getPropertySources().addFirst(docs.get(0));
            } catch (IOException e) {
                throw new IllegalStateException("application.yml could not be read", e);
            }
        };
    }

    private static CreateShopRequest request(String slug) {
        CreateShopRequest req = new CreateShopRequest();
        req.setName("Account Kitchen");
        req.setAddress("1 High Street, London");
        req.setSlug(slug);
        return req;
    }

    private static void assertAccountReservedAndAccountsAllowed(ShopService service, ShopRepository repository) {
        TenantContext.set(UUID.randomUUID());
        ReservedSlugException ex = assertThrows(ReservedSlugException.class,
                () -> service.createShop(request("account")));
        assertThat(ex.getMessage()).contains("account");
        // Exact match after trim and lower-case, as the guard decides it for every reserved word.
        assertThrows(ReservedSlugException.class, () -> service.createShop(request("  Account ")));
        verify(repository, never()).saveAndFlush(any(Shop.class));

        // Exact-match, not prefix-match: "accounts" collides with no route and must stay available.
        ShopDto created = assertDoesNotThrow(() -> service.createShop(request("accounts")));
        assertThat(created.getSlug()).isEqualTo("accounts");
    }

    @Test
    @DisplayName("@Value default: a shop may not be created with the slug 'account'; 'accounts' is allowed")
    void valueDefaultReservesAccount() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertAccountReservedAndAccountsAllowed(ctx.getBean(ShopService.class), ctx.getBean(ShopRepository.class));
        });
    }

    @Test
    @DisplayName("application.yml: a shop may not be created with the slug 'account'; 'accounts' is allowed")
    void applicationYmlReservesAccount() {
        runner().withInitializer(applicationYml()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // Positive control: the file really was the source, not the @Value default.
            assertThat(ctx.getEnvironment().getProperty("jtoye.shop.reserved-slugs")).isNotNull();
            assertAccountReservedAndAccountsAllowed(ctx.getBean(ShopService.class), ctx.getBean(ShopRepository.class));
        });
    }

    @Test
    @DisplayName("application.yml: renaming an existing shop to 'account' is refused")
    void renameToAccountIsRefused() {
        runner().withInitializer(applicationYml()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            UUID tenantId = UUID.randomUUID();
            UUID shopId = UUID.randomUUID();
            TenantContext.set(tenantId);
            Shop existing = new Shop();
            existing.setTenantId(tenantId);
            existing.setName("Account Kitchen");
            existing.setSlug("account-kitchen");
            ShopRepository repository = ctx.getBean(ShopRepository.class);
            when(repository.findByIdAndTenantId(shopId, tenantId)).thenReturn(Optional.of(existing));
            when(ctx.getBean(ShopAccessService.class).isGroupAdmin()).thenReturn(true);

            assertThrows(ReservedSlugException.class,
                    () -> ctx.getBean(ShopService.class).updateShop(shopId, request("account")));
            verify(repository, never()).saveAndFlush(any(Shop.class));
        });
    }

    @Test
    @DisplayName("every static segment under frontend/app/shop/ is reserved in BOTH sources")
    void everyStaticStorefrontSegmentIsReserved() throws IOException {
        assertThat(STOREFRONT_SHOP_ROUTES)
                .as("the storefront route table must be readable from core-java's working directory")
                .isDirectory();
        Set<String> staticSegments;
        try (Stream<Path> children = Files.list(STOREFRONT_SHOP_ROUTES)) {
            staticSegments = children
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    // [slug] is the dynamic route; (group) and _private and __tests__ are not URL segments.
                    .filter(name -> !name.startsWith("[") && !name.startsWith("(") && !name.startsWith("_"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        // Positive control: the walk sees the segments that have existed since Phase 18.
        assertThat(staticSegments).contains("orders", "account");

        String valueDefault = valueDefault();
        Set<String> fromValueDefault = split(valueDefault);
        assertThat(fromValueDefault).as("@Value default %s", valueDefault).containsAll(staticSegments);

        runner().withInitializer(applicationYml()).run(ctx -> {
            String yml = ctx.getEnvironment().getProperty("jtoye.shop.reserved-slugs");
            assertThat(split(yml)).as("application.yml jtoye.shop.reserved-slugs = %s", yml)
                    .containsAll(staticSegments);
        });
    }

    private static String valueDefault() {
        try {
            String expr = ShopService.class.getDeclaredField("reservedSlugs")
                    .getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();
            // "${jtoye.shop.reserved-slugs:account,auth,orders,signin}" -> "account,auth,orders,signin"
            return expr.substring(expr.indexOf(':') + 1, expr.lastIndexOf('}'));
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("ShopService.reservedSlugs is gone", e);
        }
    }

    private static Set<String> split(String csv) {
        if (csv == null) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
