package uk.jtoye.core.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
}
