package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.InsufficientStockException;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Optimistic-lock-gated stock decrement for CQ-01 stock race fix.
 *
 * <p><b>MUST NOT be self-invoked by OrderService</b> — Spring Retry AOP proxy
 * bypasses same-bean calls. Always reached via the constructor-injected
 * {@code stockService} field in {@link OrderService#transitionOrder}'s
 * CONFIRMED branch (RESEARCH §Common Pitfalls #2).
 *
 * <p>Retry semantics: on {@link ObjectOptimisticLockingFailureException} a
 * retry re-invokes the annotated method, which re-reads the latest
 * {@code quantity_in_stock} + {@code version} from the database. After 3
 * total attempts the {@link Recover} method fires and maps to
 * {@link InsufficientStockException} so callers get a stable 409 contract
 * regardless of whether exhaustion came from literal insufficient stock or
 * from persistent contention.
 *
 * <p>Unlimited-stock products ({@code quantity_in_stock == null}) bypass the
 * version check and decrement — matches the existing
 * {@link Product#hasStock(int)} contract.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final ProductRepository productRepository;

    public StockService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Decrement stock for all items of an order inside an optimistic-lock
     * gated retry loop. Reads every product, decrements its
     * {@code quantity_in_stock}, then calls {@code saveAll} which fires
     * {@code UPDATE ... WHERE version = ?} per product. If any product's
     * version has moved, Hibernate raises
     * {@link ObjectOptimisticLockingFailureException} which Spring Retry
     * catches and re-invokes this method (up to 3 total attempts).
     *
     * @throws InsufficientStockException when a finite-stock product has less
     *         than the requested quantity
     * @throws ObjectOptimisticLockingFailureException rethrown after 3 attempts;
     *         converted to {@link InsufficientStockException} by
     *         {@link #recoverFromOptimisticLock}
     */
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50))
    @Transactional
    public void decrementForOrder(List<OrderItem> items) {
        if (items == null || items.isEmpty()) return;
        // Re-read inside retry boundary — retry re-invokes the whole method so
        // findAllById reloads the current versions from the DB.
        List<UUID> productIds = items.stream()
                .map(OrderItem::getProductId)
                .distinct()
                .toList();
        Map<UUID, Product> byId = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        for (OrderItem item : items) {
            Product product = byId.get(item.getProductId());
            if (product == null) continue;
            if (product.getQuantityInStock() == null) continue; // unlimited — bypass version
            int current = product.getQuantityInStock();
            int requested = item.getQuantity();
            if (current < requested) {
                throw new InsufficientStockException(
                        "Insufficient stock for product '" + product.getTitle()
                                + "': requested " + requested + ", available " + current);
            }
            product.setQuantityInStock(current - requested);
            log.info("Decremented stock for product {}: {} -> {}",
                    product.getSku(), current, current - requested);
        }
        // Fires UPDATE products SET ..., version = version + 1 WHERE id = ? AND version = ?
        productRepository.saveAll(byId.values());
    }

    /**
     * Retry exhaustion handler — signature must match the @Retryable method's
     * parameters preceded by the exception type for Spring Retry to resolve it.
     */
    @Recover
    public void recoverFromOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                          List<OrderItem> items) {
        log.warn("Stock conflict after 3 retries for items {}", items, ex);
        throw new InsufficientStockException(
                "Stock conflict after 3 retries — concurrent orders depleted inventory");
    }

    /**
     * Cancel-path stock restore. No @Retryable — restore is additive; a
     * collision just re-reads and adds. Not the primary correctness path.
     */
    @Transactional
    public void restoreForOrder(List<OrderItem> items) {
        if (items == null || items.isEmpty()) return;
        List<UUID> productIds = items.stream()
                .map(OrderItem::getProductId)
                .distinct()
                .toList();
        Map<UUID, Product> byId = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (OrderItem item : items) {
            Product product = byId.get(item.getProductId());
            if (product == null || product.getQuantityInStock() == null) continue;
            product.setQuantityInStock(product.getQuantityInStock() + item.getQuantity());
            log.info("Restored stock for product {}: +{}", product.getSku(), item.getQuantity());
        }
        productRepository.saveAll(byId.values());
    }
}
