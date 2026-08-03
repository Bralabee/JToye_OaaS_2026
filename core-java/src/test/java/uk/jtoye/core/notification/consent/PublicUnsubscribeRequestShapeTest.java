package uk.jtoye.core.notification.consent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #278 — the REQUEST SHAPE contract of the public unsubscribe endpoint.
 *
 * <p>The defect: the browser POST carried {@code tenant}/{@code email}/
 * {@code category}/{@code token} in the query string, so the recipient's email
 * and the HMAC token were copied verbatim into every access log on the path
 * (nginx-ingress logs the whole request line as {@code $request}; APM agents tag
 * full-URL spans). The fix moves the browser POST to a JSON body.
 *
 * <p>The dangerous half of that fix is what it must NOT break. Every message
 * already in a customer's inbox carries an RFC 8058
 * {@code List-Unsubscribe: <…?tenant=&email=&category=&token=>} header, and RFC
 * 8058 §3.1 fixes the one-click POST body to the literal string
 * {@code List-Unsubscribe=One-Click} — there is no body slot for the token, so
 * the query-param POST can never be retired. These tests pin BOTH shapes.
 *
 * <p>Deliberately a standalone {@link MockMvc} test, not a Testcontainers one:
 * the question here is HTTP argument binding and {@code consumes} dispatch, which
 * a real Postgres cannot make more true. The tenant-scoped write itself is
 * covered end-to-end under RLS by {@code PublicUnsubscribeControllerIntegrationTest}.
 * No {@code @Tag("testcontainers")}, so this runs in the fast {@code test} task.
 */
class PublicUnsubscribeRequestShapeTest {

    private static final UUID TENANT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String EMAIL = "recipient@example.com";
    private static final NotificationCategory CATEGORY = NotificationCategory.MARKETING;
    private static final String VALID_TOKEN = "V4LID-hmac-t0ken";
    private static final String ENDPOINT = "/api/v1/public/unsubscribe";

    private UnsubscribeTokenService tokenService;
    private SuppressionService suppressionService;
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tokenService = mock(UnsubscribeTokenService.class);
        suppressionService = mock(SuppressionService.class);
        // Only the exact (tenant, email, category, VALID_TOKEN) tuple verifies.
        when(tokenService.verify(any(), anyString(), any(), anyString())).thenReturn(false);
        when(tokenService.verify(eq(TENANT), eq(EMAIL), eq(CATEGORY), eq(VALID_TOKEN))).thenReturn(true);
        when(suppressionService.suppress(eq(TENANT), eq(EMAIL), eq(CATEGORY))).thenReturn(true);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new PublicUnsubscribeController(tokenService, suppressionService))
                .build();
    }

    private String validBody() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant", TENANT.toString());
        m.put("email", EMAIL);
        m.put("category", CATEGORY.name());
        m.put("token", VALID_TOKEN);
        return json.writeValueAsString(m);
    }

    // ---------------------------------------------------------------- the fix

    @Test
    @DisplayName("#278: a JSON-body POST unsubscribes, with an EMPTY query string")
    void jsonBodyPost_unsubscribes_andLeavesTheRequestLineClean() throws Exception {
        var result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unsubscribed"))
                .andReturn();

        verify(suppressionService).suppress(TENANT, EMAIL, CATEGORY);

        // The point of the whole issue: an access log records the request line,
        // and the request line is method + URI + query. Assert the secrets are
        // in NEITHER half of it.
        String queryString = result.getRequest().getQueryString();
        assertThat(queryString).as("the JSON POST must carry no query string at all").isNull();
        String requestLine = result.getRequest().getRequestURI()
                + (queryString == null ? "" : "?" + queryString);
        assertThat(requestLine)
                .as("nginx $request / Tomcat %%r must not contain the HMAC token")
                .doesNotContain(VALID_TOKEN);
        assertThat(requestLine)
                .as("nor the recipient email")
                .doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("#278: a tampered token in the JSON body writes nothing")
    void jsonBodyPost_withTamperedToken_writesNothing() throws Exception {
        String tampered = validBody().replace(VALID_TOKEN, "tampered-not-a-valid-hmac");

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tampered))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("invalid"));

        verify(suppressionService, never()).suppress(any(), anyString(), any());
    }

    @Test
    @DisplayName("an incomplete request — either shape — is 'invalid' without a write")
    void incompleteRequest_isInvalid_andWritesNothing() throws Exception {
        // Partial JSON body.
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"MARKETING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("invalid"));

        // Empty JSON body.
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("invalid"));

        // Bare POST, no body and no params. The one-click handler's params are
        // `required = false` so the merged OpenAPI operation can honestly say
        // the query params are optional (a JSON caller sends none) — which means
        // this lands on the same PII-free "invalid" answer rather than a 400
        // naming the first missing parameter.
        mockMvc.perform(post(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("invalid"));

        // Partial query params.
        mockMvc.perform(post(ENDPOINT).param("category", CATEGORY.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("invalid"));

        verify(suppressionService, never()).suppress(any(), anyString(), any());
    }

    // -------------------------------------------- what the fix must NOT break

    @Test
    @DisplayName("RFC 8058 one-click POST from a mail provider still works (links already in inboxes)")
    void rfc8058OneClickPost_stillWorks() throws Exception {
        // Exactly what Gmail/Yahoo/Apple send for List-Unsubscribe-Post: the
        // URI from the header verbatim, plus the RFC-fixed form-encoded body.
        // There is no body slot for the token — this shape can never be retired.
        mockMvc.perform(post(ENDPOINT)
                        .param("tenant", TENANT.toString())
                        .param("email", EMAIL)
                        .param("category", CATEGORY.name())
                        .param("token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unsubscribed"));

        verify(suppressionService).suppress(TENANT, EMAIL, CATEGORY);
    }

    @Test
    @DisplayName("a bare query-param POST with no Content-Type still works")
    void queryParamPost_withNoContentType_stillWorks() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .param("tenant", TENANT.toString())
                        .param("email", EMAIL)
                        .param("category", CATEGORY.name())
                        .param("token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unsubscribed"));

        verify(suppressionService).suppress(TENANT, EMAIL, CATEGORY);
    }

    @Test
    @DisplayName("an already-open tab on the PREVIOUS bundle (JSON content-type, null body, query params) still works")
    void previousFrontendBundleShape_stillWorks() throws Exception {
        // axios `post(url, null, { params })` sends Content-Type: application/json
        // with the four bytes `null` as the body — measured, not assumed. That
        // routes to the JSON handler, whose @RequestParam fallback catches it.
        mockMvc.perform(post(ENDPOINT)
                        .param("tenant", TENANT.toString())
                        .param("email", EMAIL)
                        .param("category", CATEGORY.name())
                        .param("token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unsubscribed"));

        verify(suppressionService).suppress(TENANT, EMAIL, CATEGORY);
    }

    @Test
    @DisplayName("the GET click-through variant is untouched (explicitly out of scope for #278)")
    void getVariant_isUnchanged() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("tenant", TENANT.toString())
                        .param("email", EMAIL)
                        .param("category", CATEGORY.name())
                        .param("token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unsubscribed"));

        verify(suppressionService).suppress(TENANT, EMAIL, CATEGORY);
    }
}
