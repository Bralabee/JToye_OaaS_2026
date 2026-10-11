package uk.jtoye.core.common;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.exception.InsufficientStockException;
import uk.jtoye.core.order.OrderItem;
import uk.jtoye.core.order.StockService;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc test pinning the CQ-01 HTTP 409 ProblemDetail contract:
 * a controller that throws {@link InsufficientStockException} is rendered by
 * {@link GlobalExceptionHandler} into an RFC 7807 Problem response with
 * status 409, title "Insufficient Stock", and the configured type URI.
 *
 * <p>No Spring context, no Testcontainers — runs in the default unit test
 * suite (not tagged {@code testcontainers}). Fast regression guard.
 */
class InsufficientStockExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Register a Jackson-backed HTTP message converter explicitly so the
        // ProblemDetail returned from @ExceptionHandler serializes to JSON as an
        // object (not a String). Boot's default auto-config provides this, but
        // standaloneSetup deliberately omits it — plug it back in for the test.
        // 37-16: WITH Boot's ProblemDetailJacksonMixin, as the running app has it. Without the
        // mixin, extension members serialise nested under "properties", so a test of them would
        // assert a shape no client ever receives (ProblemDetailAuthenticationEntryPointTest
        // registers it for the same reason).
        JacksonJsonHttpMessageConverter jackson = new JacksonJsonHttpMessageConverter(
                JsonMapper.builder().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build());
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController(), new StockRefusingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(jackson)
                .build();
    }

    @Test
    void throwInsufficientStockReturns409() throws Exception {
        mockMvc.perform(get("/test-throw"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Insufficient Stock"))
                .andExpect(jsonPath("$.detail").value("test-detail"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/insufficient-stock"));
    }

    /**
     * Phase 37-16 (UI-SPEC § K1 Copywriting "Accept refused: stock"). The kitchen's Accept is
     * {@code POST /orders/{id}/confirm}, and CONFIRMED is where stock is decremented, so a refusal
     * must say WHICH product and HOW MANY are left for the board to render "Can't accept: only
     * {available} {product} left in stock." The detail is prose for a person; these two properties
     * are the machine contract (RFC 7807 extension members), additive, with type and status
     * unchanged. Driven through the REAL {@link StockService} refusal rather than a hand-built
     * exception, so the test proves the values the decrement actually knew reach the response.
     */
    @Test
    void insufficientStockCarriesProductNameAndAvailableAsProblemProperties() throws Exception {
        mockMvc.perform(get("/test-stock-refusal"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/insufficient-stock"))
                .andExpect(jsonPath("$.title").value("Insufficient Stock"))
                .andExpect(jsonPath("$.productName").value("Jollof Rice"))
                .andExpect(jsonPath("$.available").value(1));
    }

    @RestController
    static class StockRefusingController {
        @GetMapping("/test-stock-refusal")
        public String refuse() {
            UUID productId = UUID.randomUUID();
            Product product = new Product();
            ReflectionTestUtils.setField(product, "id", productId);
            product.setTitle("Jollof Rice");
            product.setSku("SKU-JOLLOF");
            product.setQuantityInStock(1);
            ProductRepository products = mock(ProductRepository.class);
            when(products.findAllById(List.of(productId))).thenReturn(List.of(product));
            new StockService(products).decrementForOrder(List.of(new OrderItem(productId, 3, 500L)));
            return "unreachable";
        }
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/test-throw")
        public String throwIt() {
            throw new InsufficientStockException("test-detail");
        }
    }
}
