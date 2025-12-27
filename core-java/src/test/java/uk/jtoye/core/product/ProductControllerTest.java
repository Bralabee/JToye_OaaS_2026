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
    private ProductRepository productRepository;

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
    }

    @Test
    void listShouldReturnPaginatedProducts() {
        // Given
        TenantContext.set(testTenantId);
        Product product = createTestProduct();
        Page<Product> productPage = new PageImpl<>(List.of(product));
        when(productRepository.findAll(any(Pageable.class))).thenReturn(productPage);

        // When
        Page<ProductDto> result = productController.list(PageRequest.of(0, 20));

        // Then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSku()).isEqualTo("TEST-SKU");
        verify(productRepository, times(1)).findAll(any(Pageable.class));
    }

    @Test
    void createShouldReturnCreatedProduct() {
        // Given
        TenantContext.set(testTenantId);
        CreateProductRequest request = new CreateProductRequest();
        request.setSku("NEW-SKU");
        request.setTitle("New Product");
        request.setIngredientsText("Test ingredients");
        request.setAllergenMask(0);

        Product savedProduct = createTestProduct();
        when(productRepository.save(any(Product.class))).thenReturn(savedProduct);

        // When
        ResponseEntity<ProductDto> response = productController.create(request);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSku()).isEqualTo("TEST-SKU");
        verify(productRepository, times(1)).save(any(Product.class));
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

        // When/Then
        assertThrows(IllegalStateException.class, () -> productController.create(request));
        verify(productRepository, never()).save(any(Product.class));
    }

    private Product createTestProduct() {
        Product product = new Product();
        product.setTenantId(testTenantId);
        product.setSku("TEST-SKU");
        product.setTitle("Test Product");
        product.setIngredientsText("Flour, Water, Salt");
        product.setAllergenMask(1);
        return product;
    }
}
