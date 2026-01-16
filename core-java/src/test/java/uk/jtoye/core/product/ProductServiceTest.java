package uk.jtoye.core.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProductService.
 * Tests service layer business logic with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private ProductService productService;

    private UUID tenantId;
    private UUID productId;
    private Product testProduct;
    private CreateProductRequest validRequest;

    /**
     * Helper method to set private fields using reflection.
     * Needed for auto-generated fields like id and createdAt.
     */
    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        productId = UUID.randomUUID();

        // Set up tenant context
        TenantContext.set(tenantId);

        // Create test product (using reflection to set auto-generated fields)
        testProduct = new Product();
        setField(testProduct, "id", productId);
        testProduct.setTenantId(tenantId);
        testProduct.setSku("YAM-5KG");
        testProduct.setTitle("Yam 5kg");
        testProduct.setIngredientsText("Yam (100%)");
        testProduct.setAllergenMask(0);
        testProduct.setPricePennies(999L);
        setField(testProduct, "createdAt", OffsetDateTime.now());

        // Create valid request
        validRequest = new CreateProductRequest();
        validRequest.setSku("YAM-5KG");
        validRequest.setTitle("Yam 5kg");
        validRequest.setIngredientsText("Yam (100%)");
        validRequest.setAllergenMask(0);
        validRequest.setPricePennies(999L);

        // Mock ProductMapper behavior to mimic actual MapStruct implementation
        // Use lenient() to avoid UnnecessaryStubbingException in tests that don't use the mapper
        lenient().when(productMapper.toDto(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            ProductDto dto = new ProductDto();
            dto.setId(product.getId());
            dto.setSku(product.getSku());
            dto.setTitle(product.getTitle());
            dto.setIngredientsText(product.getIngredientsText());
            dto.setAllergenMask(product.getAllergenMask());
            dto.setPricePennies(product.getPricePennies());
            dto.setCreatedAt(product.getCreatedAt());
            return dto;
        });
    }

    @Test
    @DisplayName("createProduct - Success with valid request")
    void testCreateProduct_Success() {
        // Given
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            setField(product, "id", productId);
            setField(product, "createdAt", OffsetDateTime.now());
            return product;
        });

        // When
        ProductDto result = productService.createProduct(validRequest);

        // Then
        assertNotNull(result);
        assertEquals(productId, result.getId());
        assertEquals("YAM-5KG", result.getSku());
        assertEquals("Yam 5kg", result.getTitle());
        assertEquals("Yam (100%)", result.getIngredientsText());
        assertEquals(0, result.getAllergenMask());
        assertEquals(999L, result.getPricePennies());

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(productCaptor.capture());

        Product savedProduct = productCaptor.getValue();
        assertEquals(tenantId, savedProduct.getTenantId());
        assertEquals("YAM-5KG", savedProduct.getSku());
    }

    @Test
    @DisplayName("createProduct - Fails when tenant context not set")
    void testCreateProduct_MissingTenant() {
        // Given
        TenantContext.clear();

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            productService.createProduct(validRequest);
        });

        assertEquals("Tenant context not set", exception.getMessage());
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    @DisplayName("createProduct - Sets tenant ID correctly")
    void testCreateProduct_SetsTenantId() {
        // Given
        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        when(productRepository.save(productCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        productService.createProduct(validRequest);

        // Then
        Product savedProduct = productCaptor.getValue();
        assertEquals(tenantId, savedProduct.getTenantId());
    }

    @Test
    @DisplayName("createProduct - Handles all allergen mask values")
    void testCreateProduct_AllergenMaskValidation() {
        // Given
        validRequest.setAllergenMask(16383); // Maximum valid value (14 allergens)
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ProductDto result = productService.createProduct(validRequest);

        // Then
        assertEquals(16383, result.getAllergenMask());
        verify(productRepository).save(any(Product.class));
    }

    @Test
    @DisplayName("getProductById - Success when product exists")
    void testGetProductById_Success() {
        // Given
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));

        // When
        Optional<ProductDto> result = productService.getProductById(productId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(productId, result.get().getId());
        assertEquals("YAM-5KG", result.get().getSku());
        verify(productRepository).findById(productId);
    }

    @Test
    @DisplayName("getProductById - Returns empty when product not found")
    void testGetProductById_NotFound() {
        // Given
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        // When
        Optional<ProductDto> result = productService.getProductById(productId);

        // Then
        assertFalse(result.isPresent());
        verify(productRepository).findById(productId);
    }

    @Test
    @DisplayName("getAllProducts - Returns paginated results")
    void testGetAllProducts_Paginated() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        Page<Product> productPage = new PageImpl<>(List.of(testProduct), pageable, 1);
        when(productRepository.findAll(pageable)).thenReturn(productPage);

        // When
        Page<ProductDto> result = productService.getAllProducts(pageable);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(productId, result.getContent().get(0).getId());
        verify(productRepository).findAll(pageable);
    }

    @Test
    @DisplayName("updateProduct - Success when product exists")
    void testUpdateProduct_Success() {
        // Given
        CreateProductRequest updateRequest = new CreateProductRequest();
        updateRequest.setSku("YAM-10KG");
        updateRequest.setTitle("Yam 10kg");
        updateRequest.setIngredientsText("Yam (100%), New ingredients");
        updateRequest.setAllergenMask(5);
        updateRequest.setPricePennies(1999L);

        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ProductDto result = productService.updateProduct(productId, updateRequest);

        // Then
        assertNotNull(result);
        assertEquals("YAM-10KG", result.getSku());
        assertEquals("Yam 10kg", result.getTitle());
        assertEquals("Yam (100%), New ingredients", result.getIngredientsText());
        assertEquals(5, result.getAllergenMask());
        assertEquals(1999L, result.getPricePennies());

        verify(productRepository).findById(productId);
        verify(productRepository).saveAndFlush(any(Product.class));
    }

    @Test
    @DisplayName("updateProduct - Fails when product not found")
    void testUpdateProduct_NotFound() {
        // Given
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            productService.updateProduct(productId, validRequest);
        });

        assertTrue(exception.getMessage().contains("Product not found"));
        verify(productRepository).findById(productId);
        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    @DisplayName("updateProduct - Updates all fields correctly")
    void testUpdateProduct_UpdatesAllFields() {
        // Given
        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(productRepository.saveAndFlush(productCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        CreateProductRequest updateRequest = new CreateProductRequest();
        updateRequest.setSku("NEW-SKU");
        updateRequest.setTitle("New Title");
        updateRequest.setIngredientsText("New Ingredients");
        updateRequest.setAllergenMask(100);
        updateRequest.setPricePennies(5000L);

        // When
        productService.updateProduct(productId, updateRequest);

        // Then
        Product updatedProduct = productCaptor.getValue();
        assertEquals("NEW-SKU", updatedProduct.getSku());
        assertEquals("New Title", updatedProduct.getTitle());
        assertEquals("New Ingredients", updatedProduct.getIngredientsText());
        assertEquals(100, updatedProduct.getAllergenMask());
        assertEquals(5000L, updatedProduct.getPricePennies());
    }

    @Test
    @DisplayName("deleteProduct - Success when product exists")
    void testDeleteProduct_Success() {
        // Given
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));

        // When
        productService.deleteProduct(productId);

        // Then
        verify(productRepository).findById(productId);
        verify(productRepository).delete(testProduct);
    }

    @Test
    @DisplayName("deleteProduct - Fails when product not found")
    void testDeleteProduct_NotFound() {
        // Given
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            productService.deleteProduct(productId);
        });

        assertTrue(exception.getMessage().contains("Product not found"));
        verify(productRepository).findById(productId);
        verify(productRepository, never()).delete(any(Product.class));
    }

    @Test
    @DisplayName("DTO mapping - Converts Product entity to DTO correctly")
    void testDtoMapping_CorrectFieldMapping() {
        // Given
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));

        // When
        Optional<ProductDto> result = productService.getProductById(productId);

        // Then
        assertTrue(result.isPresent());
        ProductDto dto = result.get();
        assertEquals(testProduct.getId(), dto.getId());
        assertEquals(testProduct.getSku(), dto.getSku());
        assertEquals(testProduct.getTitle(), dto.getTitle());
        assertEquals(testProduct.getIngredientsText(), dto.getIngredientsText());
        assertEquals(testProduct.getAllergenMask(), dto.getAllergenMask());
        assertEquals(testProduct.getPricePennies(), dto.getPricePennies());
        assertEquals(testProduct.getCreatedAt(), dto.getCreatedAt());
    }

    @Test
    @DisplayName("createProduct - Handles zero price correctly")
    void testCreateProduct_ZeroPrice() {
        // Given
        validRequest.setPricePennies(0L);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ProductDto result = productService.createProduct(validRequest);

        // Then
        assertEquals(0L, result.getPricePennies());
        verify(productRepository).save(any(Product.class));
    }

    @Test
    @DisplayName("createProduct - Handles maximum valid price")
    void testCreateProduct_MaxPrice() {
        // Given
        validRequest.setPricePennies(1000000000L); // £10,000,000
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ProductDto result = productService.createProduct(validRequest);

        // Then
        assertEquals(1000000000L, result.getPricePennies());
        verify(productRepository).save(any(Product.class));
    }

    @Test
    @DisplayName("createProduct - Handles long ingredient text")
    void testCreateProduct_LongIngredientsText() {
        // Given
        String longIngredients = "A".repeat(2000); // Maximum length
        validRequest.setIngredientsText(longIngredients);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ProductDto result = productService.createProduct(validRequest);

        // Then
        assertEquals(longIngredients, result.getIngredientsText());
        verify(productRepository).save(any(Product.class));
    }

    @Test
    @DisplayName("updateProduct - Preserves tenant ID")
    void testUpdateProduct_PreservesTenantId() {
        // Given
        UUID originalTenantId = testProduct.getTenantId();
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        productService.updateProduct(productId, validRequest);

        // Then
        assertEquals(originalTenantId, testProduct.getTenantId()); // Tenant ID should not change
    }
}
