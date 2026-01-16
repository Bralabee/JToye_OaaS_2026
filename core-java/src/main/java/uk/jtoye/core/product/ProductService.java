package uk.jtoye.core.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for product management operations.
 * All operations are automatically tenant-scoped via RLS policies.
 */
@Service
@Transactional
public class ProductService {
    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    public ProductService(ProductRepository productRepository, ProductMapper productMapper) {
        this.productRepository = productRepository;
        this.productMapper = productMapper;
    }

    /**
     * Create a new product.
     * Automatically assigns tenant from context.
     * Validates required fields per Natasha's Law (ingredients_text, allergen_mask, price).
     * Evicts all product caches for the tenant to maintain consistency.
     */
    @CacheEvict(value = "products", allEntries = true, beforeInvocation = false)
    public ProductDto createProduct(CreateProductRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating product for tenant {}: SKU={}, title={}",
                tenantId, request.getSku(), request.getTitle());

        // Create product entity
        Product product = new Product();
        product.setTenantId(tenantId);
        product.setSku(request.getSku());
        product.setTitle(request.getTitle());
        product.setIngredientsText(request.getIngredientsText());
        product.setAllergenMask(request.getAllergenMask());
        product.setPricePennies(request.getPricePennies());

        // Save product
        product = productRepository.save(product);

        log.info("Created product {} with SKU '{}', price: {} pennies",
                product.getId(), product.getSku(), product.getPricePennies());

        return productMapper.toDto(product);
    }

    /**
     * Get product by ID (tenant-scoped).
     * Results are cached with tenant-aware key generation (TTL: 10 minutes).
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "products", keyGenerator = "tenantAwareCacheKeyGenerator", unless = "#result == null")
    public Optional<ProductDto> getProductById(UUID productId) {
        log.debug("Fetching product by ID: {}", productId);
        return productRepository.findById(productId)
                .map(productMapper::toDto);
    }

    /**
     * Get all products (tenant-scoped, pageable).
     */
    @Transactional(readOnly = true)
    public Page<ProductDto> getAllProducts(Pageable pageable) {
        log.debug("Fetching all products with pagination: page={}, size={}",
                pageable.getPageNumber(), pageable.getPageSize());
        return productRepository.findAll(pageable)
                .map(productMapper::toDto);
    }

    /**
     * Update an existing product (tenant-scoped).
     * RLS ensures we can only update products belonging to our tenant.
     * Evicts all product caches for the tenant to maintain consistency.
     */
    @CacheEvict(value = "products", allEntries = true, beforeInvocation = false)
    public ProductDto updateProduct(UUID productId, CreateProductRequest request) {
        log.debug("Updating product {}: SKU={}, title={}",
                productId, request.getSku(), request.getTitle());

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        // Update product fields
        product.setSku(request.getSku());
        product.setTitle(request.getTitle());
        product.setIngredientsText(request.getIngredientsText());
        product.setAllergenMask(request.getAllergenMask());
        product.setPricePennies(request.getPricePennies());

        // Save with flush to ensure immediate persistence
        product = productRepository.saveAndFlush(product);

        log.info("Updated product {} with SKU '{}', price: {} pennies",
                product.getId(), product.getSku(), product.getPricePennies());

        return productMapper.toDto(product);
    }

    /**
     * Delete product by ID (tenant-scoped).
     * RLS ensures we can only delete products belonging to our tenant.
     * Evicts all product caches for the tenant to maintain consistency.
     */
    @CacheEvict(value = "products", allEntries = true, beforeInvocation = false)
    public void deleteProduct(UUID productId) {
        log.debug("Deleting product {}", productId);

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        productRepository.delete(product);

        log.info("Deleted product {} with SKU '{}'", product.getId(), product.getSku());
    }

}
