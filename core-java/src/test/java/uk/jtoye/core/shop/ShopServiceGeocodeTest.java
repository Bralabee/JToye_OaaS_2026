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
    // WR-02 (phase-33 code review): a single axis is not a coordinate
    // =====================================================================================

    @Nested
    @DisplayName("an unpaired coordinate axis is refused — never half a position")
    class UnpairedCoordinates {

        /**
         * Review WR-02: latitude and longitude were range-validated INDEPENDENTLY, so a
         * request carrying only one axis validated clean, and on update the IGNORE-null
         * mapper then merged the client's half with the persisted other half — a
         * coordinate nobody supplied, range-valid, ranked in public distance results.
         *
         * <p>These arms were run against the pre-fix tree first and FAILED there: a lone
         * latitude produced zero constraint violations and the POST returned 201. That
         * broken-direction run is recorded in 33-REVIEW-FIX.md.
         */
        @Test
        @DisplayName("a lone latitude violates the pairing constraint on the DTO")
        void loneLatitudeViolatesTheDto() {
            Set<ConstraintViolation<CreateShopRequest>> violations =
                    validator.validate(request("anywhere", 51.47, null));

            assertThat(violations)
                    .as("constraint violations for latitude without longitude")
                    .isNotEmpty();
            assertThat(violations)
                    .extracting(v -> v.getPropertyPath().toString())
                    .contains("coordinatePaired");
        }

        @Test
        @DisplayName("a lone longitude violates the pairing constraint on the DTO")
        void loneLongitudeViolatesTheDto() {
            assertThat(validator.validate(request("anywhere", null, -0.07)))
                    .as("constraint violations for longitude without latitude")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("POST with only a latitude is a typed 400 and the service is never reached")
        void postWithLoneLatitudeIsRejectedBeforePersistence() throws Exception {
            ShopService shopService = mock(ShopService.class);
            when(shopService.createShop(any())).thenReturn(new ShopDto());

            MockMvcBuilders.standaloneSetup(new ShopController(shopService))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build()
                    .perform(post("/shops")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(
                                    request("12 Bellenden Road, Peckham, London SE15 4QA", 51.47, null))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/validation"))
                    .andExpect(jsonPath("$.errors.coordinatePaired").exists());

            verify(shopService, never()).createShop(any());
        }

        @Test
        @DisplayName("a full pair and an absent pair both still validate — the constraint is about pairing, not presence")
        void pairedAndAbsentCoordinatesStayLegal() {
            // Non-vacuity for the arms above: a constraint that rejected every request
            // would satisfy all three while breaking the field. Both legal shapes are
            // asserted here, next to the arms whose honesty depends on them.
            assertThat(validator.validate(request("anywhere", 51.47, -0.07)))
                    .as("violations on a full pair").isEmpty();
            assertThat(validator.validate(request("anywhere", null, null)))
                    .as("violations on an absent pair").isEmpty();
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

        // ---- WR-03 (phase-33 code review): the client fallback is bounded to the UK ----

        @Test
        @DisplayName("WR-03: a client fallback OUTSIDE the UK box is discarded on create")
        void clientFallbackOutsideTheUkIsDiscardedOnCreate() {
            // A geocode miss is under the vendor's control (append a suffix the
            // end-anchored extractor cannot see, or use a well-formed non-existent
            // unit), so before this fix any SHOP_MANAGER could force the fallback and
            // place their shop at an arbitrary valid point on Earth — run against the
            // pre-fix tree this arm FAILED by persisting New York (40.7128, -74.006).
            shopService.createShop(request(UNKNOWN, 40.7128, -74.0060));

            Shop shop = persisted();
            assertThat(shop.getLatitude()).as("latitude for a non-UK client fallback").isNull();
            assertThat(shop.getLongitude()).as("longitude for a non-UK client fallback").isNull();
        }

        @Test
        @DisplayName("WR-03: a client fallback OUTSIDE the UK box cannot displace a persisted coordinate")
        void clientFallbackOutsideTheUkCannotDisplaceAPersistedCoordinate() {
            UUID shopId = UUID.randomUUID();
            when(shopRepository.findByIdAndTenantId(shopId, tenantId))
                    .thenReturn(Optional.of(existingShop(shopId, REAL, SE15_5BS_LAT, SE15_5BS_LON)));

            shopService.updateShop(shopId, request(UNKNOWN, 35.6762, 139.6503));

            Shop shop = persisted();
            assertThat(shop.getLatitude())
                    .as("the coordinate the shop already had, not Tokyo")
                    .isEqualTo(SE15_5BS_LAT);
            assertThat(shop.getLongitude()).isEqualTo(SE15_5BS_LON);
        }

        @Test
        @DisplayName("WR-03: a Northern Ireland fallback still stands, and is WARN-logged for operator review")
        void northernIrelandClientFallbackStillStandsAndIsLogged() {
            // The legitimate population the fallback exists for: Code-Point Open is
            // GB-only, so an NI postcode NEVER geocodes and the vendor's own pair is
            // all there is. The box (lat 49.8–60.9, lon −8.7–1.8) includes NI, so this
            // arm is the accept-direction control proving the containment is a bound,
            // not a ban. The WARN is asserted here too: an unverified vendor-supplied
            // position on the public ranking surface must leave an operator-visible
            // trace (event=client_coordinate_accepted) — run against the pre-fix tree
            // this arm failed on the absent log event.
            ch.qos.logback.classic.Logger serviceLogger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ShopService.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            serviceLogger.addAppender(appender);
            try {
                shopService.createShop(request("14 Ormeau Road, Belfast BT7 1SH", 54.5973, -5.9301));

                Shop shop = persisted();
                assertThat(shop.getLatitude()).isEqualTo(54.5973);
                assertThat(shop.getLongitude()).isEqualTo(-5.9301);
                assertThat(appender.list)
                        .as("the operator-review WARN for an accepted client fallback")
                        .anySatisfy(event -> {
                            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                            assertThat(event.getFormattedMessage())
                                    .contains("event=client_coordinate_accepted")
                                    .contains("coordinate-probe");
                        });
            } finally {
                serviceLogger.detachAppender(appender);
            }
        }

        @Test
        @DisplayName("UF-33-01: a rejected client fallback is WARN-logged WITHOUT the raw coordinate pair")
        void rejectedClientFallbackIsLoggedWithoutTheRawPair() {
            // The rejected pair never becomes public and could be a residential position
            // (a home kitchen's real location), so the operator trace must be coarse:
            // integer degrees (~111 km) says "New York, not a typo near Calais" without
            // fixing an address. Run against the pre-fix tree this arm FAILED with the
            // raw pair (40.7128, -74.006) in the formatted message.
            ch.qos.logback.classic.Logger serviceLogger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ShopService.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            serviceLogger.addAppender(appender);
            try {
                shopService.createShop(request(UNKNOWN, 40.7128, -74.0060));

                assertThat(appender.list)
                        .as("the rejection WARN exists and is coarse")
                        .anySatisfy(event -> {
                            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                            assertThat(event.getFormattedMessage())
                                    .contains("event=client_coordinate_rejected")
                                    .doesNotContain("40.7128")
                                    .doesNotContain("-74.006")
                                    .contains("41")
                                    .contains("-74");
                        });
            } finally {
                serviceLogger.detachAppender(appender);
            }
        }

        @Test
        @DisplayName("WR-02: create with a lone axis persists NO coordinate at all, not half of one")
        void createWithALoneAxisPersistsNoCoordinate() {
            // The DTO's pairing constraint refuses this at the HTTP boundary; this arm
            // pins the service's own last line for any caller that arrives unvalidated.
            shopService.createShop(request(UNKNOWN, 51.47, null));

            Shop shop = persisted();
            assertThat(shop.getLatitude()).as("latitude from an unpaired request").isNull();
            assertThat(shop.getLongitude()).as("longitude from an unpaired request").isNull();
        }

        @Test
        @DisplayName("WR-02: update with a lone axis cannot mint a Frankenstein pair from a persisted half")
        void updateWithALoneAxisKeepsThePersistedPairIntact() {
            // The review's headline defect: PUT carrying only latitude, address failing to
            // geocode — the IGNORE-null mapper merged the client's latitude with the
            // PERSISTED longitude, publishing a coordinate nobody supplied. Run against
            // the pre-fix tree this arm failed with exactly that pair (51.5074, -0.070047).
            UUID shopId = UUID.randomUUID();
            when(shopRepository.findByIdAndTenantId(shopId, tenantId))
                    .thenReturn(Optional.of(existingShop(shopId, REAL, SE15_5BS_LAT, SE15_5BS_LON)));

            shopService.updateShop(shopId, request(UNKNOWN, 51.5074, null));

            Shop shop = persisted();
            assertThat(shop.getLatitude())
                    .as("the coordinate the shop already had, never the client's half")
                    .isEqualTo(SE15_5BS_LAT);
            assertThat(shop.getLongitude()).isEqualTo(SE15_5BS_LON);
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
