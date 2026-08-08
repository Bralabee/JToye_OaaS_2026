package uk.jtoye.core.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.exception.PublishStateNotAcceptedException;
import uk.jtoye.core.geo.PostcodeGeocoder;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;
import uk.jtoye.core.storage.StorageService;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Link 3a of #460: the shop write path is where a coordinate is decided, and today it decides
 * nothing — {@code CreateShopRequest} declares {@code latitude}/{@code longitude} with no range
 * validation at all, and the generated {@code ShopMapperImpl} writes whatever arrives straight
 * onto the entity. A tenant can POST {@code latitude: 999} and it persists.
 *
 * <p>The arms in {@link OutOfRangeCoordinates} are the CONTROL ARM for this plan, and they were
 * run against the pre-change tree first: the {@code latitude: 999} request returned <strong>201
 * Created</strong> and the DTO produced <strong>zero</strong> constraint violations. They are
 * written before the fix so that the fix has something to be true against.
 */
class ShopServiceGeocodeTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    private static CreateShopRequest request(String address, Double latitude, Double longitude) {
        CreateShopRequest request = new CreateShopRequest();
        request.setName("Coordinate Probe");
        request.setAddress(address);
        request.setLatitude(latitude);
        request.setLongitude(longitude);
        return request;
    }

    // =====================================================================================
    // The control arm — an out-of-range coordinate must not reach persistence
    // =====================================================================================

    @Nested
    @DisplayName("out-of-range coordinates are refused before persistence")
    class OutOfRangeCoordinates {

        /**
         * Standalone MockMvc over the REAL controller with a mocked service, so the assertion is
         * about the request pipeline (@Valid -> MethodArgumentNotValidException -> the RFC 7807
         * handler) and not about anything the service does. The service mock is verified as
         * NEVER called, which is the "before persistence" half of the claim: a 400 that arrived
         * after the row was written would satisfy a status assertion and still be the bug.
         */
        private MockMvc mockMvcWith(ShopService shopService) {
            return MockMvcBuilders.standaloneSetup(new ShopController(shopService))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();
        }

        @Test
        @DisplayName("POST latitude 999 is a typed 400 and the service is never reached")
        void latitude999IsRejectedBeforePersistence() throws Exception {
            ShopService shopService = mock(ShopService.class);
            when(shopService.createShop(any())).thenReturn(new ShopDto());

            mockMvcWith(shopService).perform(post("/shops")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(
                                    request("48 Rye Lane, Peckham, London SE15 5BS", 999.0, -0.07))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/validation"))
                    .andExpect(jsonPath("$.errors.latitude").exists());

            verify(shopService, never()).createShop(any());
        }

        @Test
        @DisplayName("POST longitude 999 is a typed 400 and the service is never reached")
        void longitude999IsRejectedBeforePersistence() throws Exception {
            ShopService shopService = mock(ShopService.class);
            when(shopService.createShop(any())).thenReturn(new ShopDto());

            mockMvcWith(shopService).perform(post("/shops")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(
                                    request("48 Rye Lane, Peckham, London SE15 5BS", 51.47, 999.0))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.longitude").exists());

            verify(shopService, never()).createShop(any());
        }

        @Test
        @DisplayName("latitude 999 violates a bean-validation constraint on the DTO itself")
        void latitudeAboveNinetyViolatesTheDto() {
            Set<ConstraintViolation<CreateShopRequest>> violations =
                    validator.validate(request("anywhere", 999.0, -0.07));

            assertThat(violations)
                    .as("constraint violations on latitude=999")
                    .isNotEmpty();
            assertThat(violations)
                    .extracting(v -> v.getPropertyPath().toString())
                    .contains("latitude");
        }

        @Test
        @DisplayName("every out-of-range spelling is refused, in both directions on both axes")
        void bothAxesAreBoundedInBothDirections() {
            assertThat(validator.validate(request("anywhere", 90.001, 0.0)))
                    .as("latitude just above +90").isNotEmpty();
            assertThat(validator.validate(request("anywhere", -90.001, 0.0)))
                    .as("latitude just below -90").isNotEmpty();
            assertThat(validator.validate(request("anywhere", 0.0, 180.001)))
                    .as("longitude just above +180").isNotEmpty();
            assertThat(validator.validate(request("anywhere", 0.0, -180.001)))
                    .as("longitude just below -180").isNotEmpty();
        }

        @Test
        @DisplayName("a real London coordinate is accepted — the bound is a range, not a ban")
        void inRangeCoordinatesAreAccepted() {
            // Non-vacuity for the four arms above: if the constraint were "reject everything",
            // they would all pass and the field would be unusable. This is the other direction.
            assertThat(validator.validate(request("48 Rye Lane, Peckham, London SE15 5BS", 51.47, -0.07)))
                    .as("violations on a valid London coordinate")
                    .isEmpty();
        }

        @Test
        @DisplayName("the exact boundary values are accepted, not off-by-one rejected")
        void boundaryValuesAreAccepted() {
            assertThat(validator.validate(request("anywhere", 90.0, 180.0))).isEmpty();
            assertThat(validator.validate(request("anywhere", -90.0, -180.0))).isEmpty();
        }

        @Test
        @DisplayName("absent coordinates stay legal — the write path geocodes them instead")
        void absentCoordinatesAreStillValid() {
            assertThat(validator.validate(request("48 Rye Lane, Peckham, London SE15 5BS", null, null)))
                    .isEmpty();
        }
    }

    // =====================================================================================
    // The write path itself — geocode on create and update, and the precedence rule
    // =====================================================================================

    @Nested
    @DisplayName("the write path geocodes from the address")
    class WritePathGeocoding {

        private static final String REAL = "48 Rye Lane, Peckham, London SE15 5BS";
        private static final String UNKNOWN = "12 Bellenden Road, Peckham, London SE15 4QA";

        /** The real SE15 5BS centroid, from the committed Code-Point Open fixture. */
        private static final double SE15_5BS_LAT = 51.472435;
        private static final double SE15_5BS_LON = -0.070047;

        private final ShopRepository shopRepository = mock(ShopRepository.class);
        private final ShopMapper shopMapper = mock(ShopMapper.class);
        private final StorageService storageService = mock(StorageService.class);
        private final TenantCacheEvictor cacheEvictor = mock(TenantCacheEvictor.class);
        private final ShopAccessService shopAccessService = mock(ShopAccessService.class);
        private final PostcodeGeocoder geocoder = mock(PostcodeGeocoder.class);

        private final UUID tenantId = UUID.randomUUID();
        private ShopService shopService;

        @BeforeEach
        void wire() {
            shopService = new ShopService(shopRepository, shopMapper, storageService, cacheEvictor,
                    shopAccessService,
                    new ShopService.ShopCacheLoader(shopRepository, shopMapper),
                    geocoder);
            TenantContext.set(tenantId);

            // The geocoder is the REAL contract from 33-02: table-authoritative, Optional out,
            // never (0,0). Only the two addresses this suite uses are stubbed; anything else
            // resolves empty, which is the geocoder's own behaviour for an unknown postcode.
            lenient().when(geocoder.locate(any())).thenReturn(Optional.empty());
            lenient().when(geocoder.locate(REAL))
                    .thenReturn(Optional.of(new PostcodeGeocoder.Coordinate(SE15_5BS_LAT, SE15_5BS_LON)));

            // Mimic the generated ShopMapperImpl: toEntity copies every field including the
            // coordinate; updateEntity is IGNORE-null, so an omitted field does not overwrite.
            lenient().when(shopMapper.toEntity(any(CreateShopRequest.class))).thenAnswer(inv -> {
                CreateShopRequest req = inv.getArgument(0);
                Shop shop = new Shop();
                shop.setName(req.getName());
                shop.setAddress(req.getAddress());
                shop.setSlug(req.getSlug());
                shop.setLatitude(req.getLatitude());
                shop.setLongitude(req.getLongitude());
                shop.setPublished(req.getPublished());
                return shop;
            });
            lenient().doAnswer(inv -> {
                CreateShopRequest req = inv.getArgument(0);
                Shop shop = inv.getArgument(1);
                if (req.getName() != null) shop.setName(req.getName());
                if (req.getAddress() != null) shop.setAddress(req.getAddress());
                if (req.getSlug() != null) shop.setSlug(req.getSlug());
                if (req.getLatitude() != null) shop.setLatitude(req.getLatitude());
                if (req.getLongitude() != null) shop.setLongitude(req.getLongitude());
                if (req.getPublished() != null) shop.setPublished(req.getPublished());
                return null;
            }).when(shopMapper).updateEntity(any(CreateShopRequest.class), any(Shop.class));

            lenient().when(shopRepository.saveAndFlush(any(Shop.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            lenient().when(shopMapper.toDto(any(Shop.class))).thenReturn(new ShopDto());
            lenient().when(shopAccessService.isGroupAdmin()).thenReturn(true);
        }

        @AfterEach
        void clearTenant() {
            TenantContext.clear();
        }

        private boolean isNullIsland(Shop shop) {
            return shop.getLatitude() != null && shop.getLongitude() != null
                    && shop.getLatitude() == 0.0 && shop.getLongitude() == 0.0;
        }

        /** The entity as it was handed to the repository — the only thing that persists. */
        private Shop persisted() {
            ArgumentCaptor<Shop> captor = ArgumentCaptor.forClass(Shop.class);
            verify(shopRepository).saveAndFlush(captor.capture());
            return captor.getValue();
        }

        private Shop existingShop(UUID shopId, String address, Double lat, Double lon) {
            Shop shop = new Shop();
            try {
                java.lang.reflect.Field field = Shop.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(shop, shopId);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            shop.setTenantId(tenantId);
            shop.setName("Existing");
            shop.setSlug("existing");
            shop.setAddress(address);
            shop.setLatitude(lat);
            shop.setLongitude(lon);
            shop.setPublished(true);
            return shop;
        }

        // ---- create ------------------------------------------------------------------

        @Test
        @DisplayName("create with a real postcode persists the geocoded coordinate")
        void createGeocodesFromTheAddress() {
            shopService.createShop(request(REAL, null, null));

            Shop shop = persisted();
            assertThat(shop.getLatitude()).isEqualTo(SE15_5BS_LAT);
            assertThat(shop.getLongitude()).isEqualTo(SE15_5BS_LON);
        }

        @Test
        @DisplayName("create with an unknown postcode persists NULL — not (0,0), and no throw")
        void createWithUnknownPostcodeLeavesNullNotNullIsland() {
            shopService.createShop(request(UNKNOWN, null, null));

            Shop shop = persisted();
            assertThat(shop.getLatitude()).as("latitude for an unresolvable postcode").isNull();
            assertThat(shop.getLongitude()).as("longitude for an unresolvable postcode").isNull();
            // Stated separately from isNull() on purpose: (0,0) is the specific catastrophe —
            // a shop at Null Island outranks every real shop under a distance sort. Written
            // null-safely, because AssertJ's isNotEqualTo(double) fails on a null actual and
            // would have made this limb pass for the wrong reason.
            assertThat(isNullIsland(shop))
                    .as("the shop landed at Null Island (0,0)")
                    .isFalse();
        }

        @Test
        @DisplayName("create with no extractable postcode at all is the same: NULL, no throw")
        void createWithNoPostcodeLeavesNull() {
            shopService.createShop(request("1 Probe Lane, London", null, null));

            assertThat(persisted().getLatitude()).isNull();
        }

        // ---- the precedence rule, asserted rather than implied -------------------------

        @Test
        @DisplayName("PRECEDENCE: a geocoded postcode OVERRIDES a client-supplied coordinate")
        void geocodeBeatsClientSuppliedCoordinate() {
            // Both are in range, so validation cannot be what decides this — the rule is.
            shopService.createShop(request(REAL, 10.0, 10.0));

            Shop shop = persisted();
            assertThat(shop.getLatitude())
                    .as("the postcode centroid, not the client's 10.0")
                    .isEqualTo(SE15_5BS_LAT);
            assertThat(shop.getLongitude()).isEqualTo(SE15_5BS_LON);
        }

        @Test
        @DisplayName("PRECEDENCE: a client coordinate STANDS when the postcode does not resolve")
        void clientCoordinateSurvivesAGeocodeMiss() {
            shopService.createShop(request(UNKNOWN, 51.47, -0.07));

            Shop shop = persisted();
            assertThat(shop.getLatitude())
                    .as("the fallback limb — a range-validated client value, since nothing better exists")
                    .isEqualTo(51.47);
            assertThat(shop.getLongitude()).isEqualTo(-0.07);
        }

        // ---- update -------------------------------------------------------------------

        @Test
        @DisplayName("update that changes the address re-geocodes")
        void updateReGeocodesOnAddressChange() {
            UUID shopId = UUID.randomUUID();
            when(shopRepository.findByIdAndTenantId(shopId, tenantId))
                    .thenReturn(Optional.of(existingShop(shopId, UNKNOWN, null, null)));

            shopService.updateShop(shopId, request(REAL, null, null));

            assertThat(persisted().getLatitude()).isEqualTo(SE15_5BS_LAT);
        }

        @Test
        @DisplayName("update whose re-geocode MISSES does not NULL an existing coordinate")
        void updateDoesNotClobberAnExistingCoordinateOnAMiss() {
            // The regression this arm exists for: edit a shop's address to a postcode that is
            // not in the dataset, and a naive implementation writes null over a good coordinate
            // — silently removing a live storefront from every distance result.
            UUID shopId = UUID.randomUUID();
            when(shopRepository.findByIdAndTenantId(shopId, tenantId))
                    .thenReturn(Optional.of(existingShop(shopId, REAL, SE15_5BS_LAT, SE15_5BS_LON)));

            shopService.updateShop(shopId, request(UNKNOWN, null, null));

            Shop shop = persisted();
            assertThat(shop.getLatitude())
                    .as("the coordinate the shop already had")
                    .isEqualTo(SE15_5BS_LAT);
            assertThat(shop.getLongitude()).isEqualTo(SE15_5BS_LON);
        }

        @Test
        @DisplayName("update that leaves the address alone keeps the coordinate")
        void updateWithoutAddressChangeKeepsTheCoordinate() {
            UUID shopId = UUID.randomUUID();
            when(shopRepository.findByIdAndTenantId(shopId, tenantId))
                    .thenReturn(Optional.of(existingShop(shopId, REAL, SE15_5BS_LAT, SE15_5BS_LON)));

            shopService.updateShop(shopId, request(REAL, null, null));

            assertThat(persisted().getLatitude()).isEqualTo(SE15_5BS_LAT);
        }

        @Test
        @DisplayName("the sole-writer invariant survives: update still cannot publish a shop")
        void updateStillCannotAlterPublished() {
            // T-18-05-T. This plan adds a second write to the update path, so the invariant is
            // re-asserted HERE rather than assumed to have survived the edit.
            UUID shopId = UUID.randomUUID();
            when(shopRepository.findByIdAndTenantId(shopId, tenantId))
                    .thenReturn(Optional.of(existingShop(shopId, REAL, null, null)));

            CreateShopRequest unpublishMe = request(REAL, null, null);
            unpublishMe.setPublished(false);

            assertThatThrownBy(() -> shopService.updateShop(shopId, unpublishMe))
                    .isInstanceOf(PublishStateNotAcceptedException.class);
            verify(shopRepository, never()).saveAndFlush(any(Shop.class));
        }
    }
}
