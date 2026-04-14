package uk.jtoye.core.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.StorageService.ImageType;
import uk.jtoye.core.storage.StorageService;

import java.util.List;
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
    private final StorageService storageService;
    private final TenantCacheEvictor cacheEvictor;

    public ProductService(ProductRepository productRepository,
                          ProductMapper productMapper,
                          StorageService storageService,
                          TenantCacheEvictor cacheEvictor) {
        this.productRepository = productRepository;
        this.productMapper = productMapper;
        this.storageService = storageService;
        this.cacheEvictor = cacheEvictor;
    }

    /**
     * Create a new product.
     * Automatically assigns tenant from context.
     * Validates required fields per Natasha's Law (ingredients_text, allergen_mask, price).
     *
     * <p>No cache eviction: a brand-new product cannot have an existing cached entry
     * under any previous id, so there is nothing to invalidate. (Previous
     * {@code @CacheEvict(allEntries=true)} nuked every tenant's cache on every create.)
     */
    public ProductDto createProduct(CreateProductRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating product for tenant {}: SKU={}, title={}",
                tenantId, request.getSku(), request.getTitle());

        // Create product entity via mapper (handles all fields including storefront fields)
        Product product = productMapper.toEntity(request);
        product.setTenantId(tenantId);

        // Apply defaults for storefront fields if not provided
        if (product.getAvailable() == null) product.setAvailable(true);
        if (product.getFeatured() == null) product.setFeatured(false);
        if (product.getDisplayOrder() == null) product.setDisplayOrder(0);

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
     * Search products by title or SKU (tenant-scoped).
     */
    @Transactional(readOnly = true)
    public List<ProductDto> search(String query) {
        log.debug("Searching products with query: {}", query);
        return productRepository.search(query).stream()
                .map(productMapper::toDto)
                .toList();
    }

    /**
     * Update an existing product (tenant-scoped).
     * RLS ensures we can only update products belonging to our tenant.
     * Evicts all product caches for the tenant to maintain consistency.
     */
    public ProductDto updateProduct(UUID productId, CreateProductRequest request) {
        log.debug("Updating product {}: SKU={}, title={}",
                productId, request.getSku(), request.getTitle());

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        // Update all fields via mapper (handles both core and storefront fields)
        productMapper.updateEntity(request, product);

        // Save with flush to ensure immediate persistence
        product = productRepository.saveAndFlush(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Updated product {} with SKU '{}', price: {} pennies",
                product.getId(), product.getSku(), product.getPricePennies());

        return productMapper.toDto(product);
    }

    /**
     * Upload an image for a product. Replaces any existing image.
     */
    public ProductDto uploadImage(UUID productId, MultipartFile file) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        // Delete old image if exists
        storageService.delete(product.getImageUrl());

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        String url = storageService.upload(tenantId, "products", productId, file);
        product.setImageUrl(url);
        product = productRepository.saveAndFlush(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Uploaded image for product {} (SKU: {})", productId, product.getSku());
        return productMapper.toDto(product);
    }

    /**
     * Remove the image from a product.
     */
    public ProductDto removeImage(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        storageService.delete(product.getImageUrl());
        product.setImageUrl(null);
        product = productRepository.saveAndFlush(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Removed image for product {} (SKU: {})", productId, product.getSku());
        return productMapper.toDto(product);
    }

    /**
     * Add an additional image to the product gallery (max 5).
     */
    public ProductDto addAdditionalImage(UUID productId, MultipartFile file) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        if (product.getAdditionalImageUrls().size() >= 5) {
            throw new IllegalStateException("Maximum 5 additional images allowed per product");
        }

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        String url = storageService.upload(tenantId, "products", productId, file, ImageType.PRODUCT);
        product.getAdditionalImageUrls().add(url);
        product = productRepository.saveAndFlush(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Added additional image for product {} ({} total)", productId, product.getAdditionalImageUrls().size());
        return productMapper.toDto(product);
    }

    /**
     * Remove an additional image by index.
     */
    public ProductDto removeAdditionalImage(UUID productId, int index) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        List<String> urls = product.getAdditionalImageUrls();
        if (index < 0 || index >= urls.size()) {
            throw new ResourceNotFoundException("Image index out of range: " + index);
        }

        String removedUrl = urls.remove(index);
        storageService.delete(removedUrl);
        product = productRepository.saveAndFlush(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Removed additional image {} for product {}", index, productId);
        return productMapper.toDto(product);
    }

    /**
     * Delete product by ID (tenant-scoped).
     * RLS ensures we can only delete products belonging to our tenant.
     * Evicts ONLY this product's cache entry under the current tenant.
     */
    public void deleteProduct(UUID productId) {
        log.debug("Deleting product {}", productId);

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        // Clean up all images from storage
        storageService.delete(product.getImageUrl());
        product.getAdditionalImageUrls().forEach(storageService::delete);

        productRepository.delete(product);
        cacheEvictor.evictEntity("products", "getProductById", productId);

        log.info("Deleted product {} with SKU '{}'", product.getId(), product.getSku());
    }

}
