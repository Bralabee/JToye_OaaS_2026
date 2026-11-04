package uk.jtoye.core.storefront;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.exception.CustomerProfileUnavailableException;
import uk.jtoye.core.security.CustomerJwtVerifier;
import uk.jtoye.core.tenant.keycloak.CustomerProfileService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SW-JRD-07 (owner ruling 2026-11-04): {@code PUT /public/customer/profile}. The account is chosen by the
 * verified token alone; the body carries only the name.
 */
@WebMvcTest(CustomerProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, uk.jtoye.core.security.ClientIpResolver.class})
class CustomerProfileControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CustomerJwtVerifier verifier;
    @MockitoBean CustomerProfileService service;

    private static final String SUB = "7d6f6b1e-0000-4000-8000-000000000001";

    @Test
    @DisplayName("a valid token and a name: 200 with the name as saved, no-store; the service gets the TOKEN's subject and email")
    void changesTheName() throws Exception {
        when(verifier.verifiedIdentity("good")).thenReturn(new CustomerJwtVerifier.CustomerIdentity("ada@example.com", SUB));
        when(service.updateDisplayName(SUB, "ada@example.com", "Ada King")).thenReturn("Ada King");

        mockMvc.perform(put("/public/customer/profile").header("X-Customer-Token", "good")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Ada King\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.displayName").value("Ada King"));
    }

    @Test
    @DisplayName("a body that tries to name an account (email, sub) changes nothing about whose account is edited")
    void bodyCannotChooseTheAccount() throws Exception {
        when(verifier.verifiedIdentity("good")).thenReturn(new CustomerJwtVerifier.CustomerIdentity("ada@example.com", SUB));
        when(service.updateDisplayName(SUB, "ada@example.com", "Ada")).thenReturn("Ada");

        mockMvc.perform(put("/public/customer/profile").header("X-Customer-Token", "good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Ada\",\"email\":\"victim@example.com\",\"sub\":\"other\"}"))
                .andExpect(status().isOk());

        verify(service).updateDisplayName(SUB, "ada@example.com", "Ada");
    }

    @Test
    @DisplayName("no token, or a bad one, is a 401 and the service is never called")
    void unauthenticated() throws Exception {
        when(verifier.verifiedIdentity(any())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no"));

        mockMvc.perform(put("/public/customer/profile")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Ada\"}"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).updateDisplayName(any(), any(), any());
    }

    @Test
    @DisplayName("a token with no subject is a 401")
    void noSubject() throws Exception {
        when(verifier.verifiedIdentity("odd")).thenReturn(new CustomerJwtVerifier.CustomerIdentity("ada@example.com", null));

        mockMvc.perform(put("/public/customer/profile").header("X-Customer-Token", "odd")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Ada\"}"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).updateDisplayName(any(), any(), any());
    }

    @Test
    @DisplayName("a missing or over-long name is a 400")
    void invalidBody() throws Exception {
        when(verifier.verifiedIdentity("good")).thenReturn(new CustomerJwtVerifier.CustomerIdentity("ada@example.com", SUB));

        mockMvc.perform(put("/public/customer/profile").header("X-Customer-Token", "good")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/public/customer/profile").header("X-Customer-Token", "good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + "x".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("when the admin seam is off the answer is the typed 503 profile-update-unavailable")
    void unavailable() throws Exception {
        when(verifier.verifiedIdentity("good")).thenReturn(new CustomerJwtVerifier.CustomerIdentity("ada@example.com", SUB));
        when(service.updateDisplayName(any(), any(), any())).thenThrow(new CustomerProfileUnavailableException(null));

        mockMvc.perform(put("/api/v1/public/customer/profile").header("X-Customer-Token", "good")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Ada\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/profile-update-unavailable"))
                .andExpect(jsonPath("$.code").value("PROFILE_UPDATE_UNAVAILABLE"));
    }
}
