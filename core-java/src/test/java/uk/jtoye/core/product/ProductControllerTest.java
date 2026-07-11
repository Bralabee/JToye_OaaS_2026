package uk.jtoye.core.product;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private ProductService productService;

    @Mock
    private ProductLabelService labelService;

    @InjectMocks
    private ProductController productController;

    private UUID testTenantId;

    @BeforeEach
    void setup() {
        testTenantId = UUID.randomUUID();
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void listShouldReturnPaginatedProducts() {
        // Given
        TenantContext.set(testTenantId);
        ProductDto productDto = createTestProductDto();
        Page<ProductDto> productPage = new PageImpl<>(List.of(productDto));
        when(productService.getAllProducts(any(Pageable.class))).thenReturn(productPage);

        // When
        Page<ProductDto> result = productController.list(PageRequest.of(0, 20));

        // Then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSku()).isEqualTo("TEST-SKU");
        verify(productService, times(1)).getAllProducts(any(Pageable.class));
    }

    @Test
    void createShouldReturnCreatedProduct() {
        // Given
        TenantContext.set(testTenantId);
        // issue #97 [P2-6]: create() now builds Location via
        // ServletUriComponentsBuilder.fromCurrentRequest(), which needs a bound
        // request. Bind a mock one at the real (WebConfig-prefixed) path.
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/v1/products");
        servletRequest.setRequestURI("/api/v1/products");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));

        CreateProductRequest request = new CreateProductRequest();
        request.setSku("NEW-SKU");
        request.setTitle("New Product");
        request.setIngredientsText("Test ingredients");
        request.setAllergenMask(0);

        ProductDto savedDto = createTestProductDto();
        when(productService.createProduct(any(CreateProductRequest.class))).thenReturn(savedDto);

        // When
        ResponseEntity<ProductDto> response = productController.create(request);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSku()).isEqualTo("TEST-SKU");
        assertThat(response.getHeaders().getLocation())
                .as("Location must carry the /api/v1 prefix of the real request path")
                .hasPath("/api/v1/products/" + savedDto.getId());
        verify(productService, times(1)).createProduct(any(CreateProductRequest.class));
    }

    @Test
    void createWithoutTenantContextShouldThrowException() {
        // Given
        TenantContext.clear(); // No tenant set
        CreateProductRequest request = new CreateProductRequest();
        request.setSku("NEW-SKU");
        request.setTitle("New Product");
        request.setIngredientsText("Test ingredients");
        request.setAllergenMask(0);

        when(productService.createProduct(any(CreateProductRequest.class)))
                .thenThrow(new IllegalStateException("Tenant context not set"));

        // When/Then
        assertThrows(IllegalStateException.class, () -> productController.create(request));
        verify(productService, times(1)).createProduct(any(CreateProductRequest.class));
    }

    private ProductDto createTestProductDto() {
        ProductDto dto = new ProductDto();
        dto.setId(UUID.randomUUID());
        dto.setSku("TEST-SKU");
        dto.setTitle("Test Product");
        dto.setIngredientsText("Flour, Water, Salt");
        dto.setAllergenMask(1);
        return dto;
    }
}
