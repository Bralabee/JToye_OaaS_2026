package uk.jtoye.core.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.jtoye.core.exception.ResourceNotFoundException;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProductLabelService.
 * Tests PDF label generation logic with mocked ProductRepository.
 */
@ExtendWith(MockitoExtension.class)
class ProductLabelServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductLabelService productLabelService;

    private UUID productId;
    private Product testProduct;

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
        productId = UUID.randomUUID();

        testProduct = new Product();
        setField(testProduct, "id", productId);
        testProduct.setTenantId(UUID.randomUUID());
        testProduct.setSku("YAM-5KG");
        testProduct.setTitle("Yam 5kg");
        testProduct.setIngredientsText("Yam (100%)");
        testProduct.setAllergenMask(0);
        testProduct.setPricePennies(999L);
        setField(testProduct, "createdAt", OffsetDateTime.now());
    }

    @Test
    @DisplayName("generateLabel - Returns valid PDF bytes for product with no allergens")
    void testGenerateLabel_NoAllergens() {
        // Given
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));

        // When
        byte[] result = productLabelService.generateLabel(productId);

        // Then
        assertNotNull(result);
        assertTrue(result.length > 0, "PDF bytes should not be empty");
        // PDF files start with %PDF
        String header = new String(result, 0, 4);
        assertEquals("%PDF", header, "Output should be a valid PDF");
        verify(productRepository).findById(productId);
    }

    @Test
    @DisplayName("generateLabel - Returns valid PDF bytes for product with allergens")
    void testGenerateLabel_WithAllergens() {
        // Given - Gluten (bit 0) + Eggs (bit 2) + Milk (bit 6) = 0b1000101 = 69
        testProduct.setAllergenMask(69);
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));

        // When
        byte[] result = productLabelService.generateLabel(productId);

        // Then
        assertNotNull(result);
        assertTrue(result.length > 0);
        String header = new String(result, 0, 4);
        assertEquals("%PDF", header);
        verify(productRepository).findById(productId);
    }

    @Test
    @DisplayName("generateLabel - Throws ResourceNotFoundException when product not found")
    void testGenerateLabel_ProductNotFound() {
        // Given
        UUID missingId = UUID.randomUUID();
        when(productRepository.findById(missingId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            productLabelService.generateLabel(missingId);
        });

        assertTrue(exception.getMessage().contains("Product not found"));
        assertTrue(exception.getMessage().contains(missingId.toString()));
        verify(productRepository).findById(missingId);
    }

    @Test
    @DisplayName("generateLabel - Produces larger PDF when price is set vs null price")
    void testGenerateLabel_WithPrice() {
        // Given - generate label without price
        testProduct.setPricePennies(null);
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        byte[] withoutPrice = productLabelService.generateLabel(productId);

        // Given - generate label with price
        testProduct.setPricePennies(1599L);
        byte[] withPrice = productLabelService.generateLabel(productId);

        // Then - PDF with price should be larger (more content)
        assertNotNull(withPrice);
        assertTrue(withPrice.length > withoutPrice.length,
                "PDF with price should be larger than PDF without price");
    }

    @Test
    @DisplayName("generateLabel - Handles null pricePennies gracefully")
    void testGenerateLabel_NullPrice() {
        // Given
        testProduct.setPricePennies(null);
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));

        // When
        byte[] result = productLabelService.generateLabel(productId);

        // Then
        assertNotNull(result);
        assertTrue(result.length > 0);
        String header = new String(result, 0, 4);
        assertEquals("%PDF", header, "Should still produce valid PDF without price");
        verify(productRepository).findById(productId);
    }

    @Test
    @DisplayName("generateLabel - All 14 allergens produces larger PDF than no allergens")
    void testGenerateLabel_AllAllergens() {
        // Given - no allergens
        testProduct.setAllergenMask(0);
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        byte[] noAllergens = productLabelService.generateLabel(productId);

        // Given - all 14 allergens: bits 0-13 = 16383
        testProduct.setAllergenMask(16383);
        byte[] allAllergens = productLabelService.generateLabel(productId);

        // Then - PDF with all allergens should be larger
        assertNotNull(allAllergens);
        assertTrue(allAllergens.length > noAllergens.length,
                "PDF with all allergens should be larger than PDF with none");
    }

    @Test
    @DisplayName("generateLabel - Different titles produce different PDFs")
    void testGenerateLabel_DifferentTitlesProduceDifferentPdfs() {
        // Given
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));

        testProduct.setTitle("Short");
        byte[] shortTitle = productLabelService.generateLabel(productId);

        testProduct.setTitle("A Much Longer Product Title That Should Generate Different Content");
        byte[] longTitle = productLabelService.generateLabel(productId);

        // Then
        assertNotNull(shortTitle);
        assertNotNull(longTitle);
        assertNotEquals(shortTitle.length, longTitle.length,
                "Different titles should produce different PDF sizes");
    }

    @Test
    @DisplayName("generateLabel - Different SKUs produce different PDFs")
    void testGenerateLabel_DifferentSkus() {
        // Given
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));

        testProduct.setSku("A");
        byte[] shortSku = productLabelService.generateLabel(productId);

        testProduct.setSku("VERY-LONG-SKU-IDENTIFIER-12345");
        byte[] longSku = productLabelService.generateLabel(productId);

        // Then
        assertNotNull(shortSku);
        assertNotNull(longSku);
        assertNotEquals(shortSku.length, longSku.length,
                "Different SKUs should produce different PDF sizes");
    }

    @Test
    @DisplayName("generateLabel - Different ingredients produce different PDFs")
    void testGenerateLabel_DifferentIngredients() {
        // Given
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));

        testProduct.setIngredientsText("Water");
        byte[] shortIngredients = productLabelService.generateLabel(productId);

        testProduct.setIngredientsText("Water, Sugar, Flour, Salt, Yeast, Butter, Eggs, Vanilla Extract, Baking Powder");
        byte[] longIngredients = productLabelService.generateLabel(productId);

        // Then
        assertNotNull(shortIngredients);
        assertNotNull(longIngredients);
        assertNotEquals(shortIngredients.length, longIngredients.length,
                "Different ingredients should produce different PDF sizes");
    }

    @Test
    @DisplayName("generateLabel - Single allergen produces larger PDF than no allergens")
    void testGenerateLabel_SingleAllergen() {
        // Given
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));

        testProduct.setAllergenMask(0);
        byte[] noAllergens = productLabelService.generateLabel(productId);

        // only Peanuts (bit 4) = 16
        testProduct.setAllergenMask(16);
        byte[] withPeanuts = productLabelService.generateLabel(productId);

        // Then - single allergen should produce more content than "No allergens declared"
        assertNotNull(withPeanuts);
        assertNotEquals(noAllergens.length, withPeanuts.length,
                "Single allergen PDF should differ from no-allergen PDF");
    }
}
