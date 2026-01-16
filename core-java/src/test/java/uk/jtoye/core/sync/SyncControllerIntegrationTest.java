package uk.jtoye.core.sync;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import uk.jtoye.core.sync.dto.BatchSyncRequest;
import uk.jtoye.core.sync.dto.BatchSyncResponse;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SyncController.class)
@AutoConfigureMockMvc(addFilters = false) // Disable security filters for simplicity in this unit test
class SyncControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
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

        mockMvc.perform(post("/sync/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.processedCount").value(2));
    }
}
