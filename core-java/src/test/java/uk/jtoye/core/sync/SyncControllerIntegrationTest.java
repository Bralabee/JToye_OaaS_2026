package uk.jtoye.core.sync;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.sync.dto.BatchSyncRequest;
import uk.jtoye.core.sync.dto.BatchSyncResponse;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice for {@code POST /api/v1/sync/batch}: request binding + Jakarta validation
 * of each batch item (QA-council 20260902 API-2), with the service mocked.
 *
 * <p>{@code addFilters = false} — the security filter chain and the
 * {@code @PreAuthorize("hasAuthority('SCOPE_catalog:write')")} gate (API-1) are deliberately
 * NOT exercised here; method security is not active in a {@code @WebMvcTest} slice (see
 * {@code GdprControllerTest}, same shape). The real-chain proof — scope gate, shop-grant gate,
 * validation through the real handler against Postgres — is
 * {@link SyncBatchAuthorizationIntegrationTest}.
 *
 * <p>{@code @Import(GlobalExceptionHandler.class)} mirrors {@code RefundControllerIntegrationTest}
 * so the RFC 7807 {@code validation} body is the real one, not Spring's bare 400.
 */
@WebMvcTest(SyncController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class SyncControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SyncService syncService;

    @Test
    void testBatchSync_Success() throws Exception {
        UUID tenantId = UUID.randomUUID();
        
        when(syncService.processBatch(any(BatchSyncRequest.class)))
                .thenReturn(BatchSyncResponse.builder()
                        .status("SUCCESS")
                        .processedCount(2)
                        .build());

        String json = """
                {
                  "tenantId": "%s",
                  "items": [
                    { "type": "PRODUCT", "sku": "PROD-1", "title": "Product 1" },
                    { "type": "SHOP", "name": "Shop 1" }
                  ]
                }
                """.formatted(tenantId);

        mockMvc.perform(post("/api/v1/sync/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.processedCount").value(2));
    }

    // --- API-2: each item carries the CreateProductRequest bounds; the 400 names the field ---

    /** RED on the unfixed tree: the untyped Map item binds anything, so the service is reached with -500. */
    @Test
    void negativePricePenniesIs400NamingTheField_andNeverReachesTheService() throws Exception {
        String json = """
                {"items":[{"type":"product","sku":"S-1","title":"x","ingredientsText":"rice",
                           "allergenMask":0,"pricePennies":-500}]}
                """;
        mockMvc.perform(post("/api/v1/sync/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/validation"))
                .andExpect(jsonPath("$.errors['items[0].pricePennies']").value("Price must be non-negative"));
        verify(syncService, never()).processBatch(any());
    }

    /** RED on the unfixed tree: 16384 is one past the 14-bit UK FSA catalogue and was stored verbatim. */
    @Test
    void allergenMaskAbove16383Is400NamingTheField() throws Exception {
        String json = """
                {"items":[{"type":"product","sku":"S-1","title":"x","ingredientsText":"rice",
                           "allergenMask":16384,"pricePennies":100}]}
                """;
        mockMvc.perform(post("/api/v1/sync/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/validation"))
                .andExpect(jsonPath("$.errors['items[0].allergenMask']")
                        .value("Allergen mask must not exceed 16383 (14 allergens max)"));
        verify(syncService, never()).processBatch(any());
    }

    /** The field path carries the ITEM INDEX, so a caller can locate the bad row in a large batch. */
    @Test
    void validationNamesTheOffendingItemIndex() throws Exception {
        String json = """
                {"items":[
                  {"type":"product","sku":"S-1","title":"ok","ingredientsText":"rice","allergenMask":0,"pricePennies":100},
                  {"type":"product","sku":"S-2","title":"bad","ingredientsText":"rice","allergenMask":-1,"pricePennies":100}
                ]}
                """;
        mockMvc.perform(post("/api/v1/sync/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['items[1].allergenMask']").value("Allergen mask must be non-negative"))
                .andExpect(jsonPath("$.errors['items[0].allergenMask']").doesNotExist());
        verify(syncService, never()).processBatch(any());
    }
}
