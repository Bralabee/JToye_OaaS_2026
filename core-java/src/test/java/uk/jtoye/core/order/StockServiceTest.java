package uk.jtoye.core.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.exception.InsufficientStockException;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StockService} — the CQ-01 optimistic-lock-gated stock
 * decrement. Fast Mockito tests (no Spring context). The retry + @Recover
 * wiring itself is exercised end-to-end by
 * {@link ConcurrentStockDecrementIntegrationTest} against real Postgres.
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock ProductRepository productRepository;
    @InjectMocks StockService stockService;

    /** Product.id is JPA-managed (no setter) — use ReflectionTestUtils. */
    private static Product productWith(UUID id, Integer stock, String title) {
        Product p = new Product();
        ReflectionTestUtils.setField(p, "id", id);
        p.setQuantityInStock(stock);
        p.setTitle(title);
        p.setSku(title == null ? "SKU-" + id : "SKU-" + title);
        return p;
    }

    @Test
    void nullStockBypassesVersionAndDecrement() {
        UUID pid = UUID.randomUUID();
        Product unlimited = productWith(pid, /*stock=*/ null, "Coffee");
        when(productRepository.findAllById(List.of(pid))).thenReturn(List.of(unlimited));

        OrderItem item = new OrderItem(pid, 5, 100L);
        stockService.decrementForOrder(List.of(item));

        // No decrement applied — unlimited products bypass the version check.
        assertThat(unlimited.getQuantityInStock()).isNull();
        // saveAll still fires (benign — no changed state means no UPDATE).
        verify(productRepository).saveAll(anyCollection());
    }

    @Test
    void insufficientStockThrows() {
        UUID pid = UUID.randomUUID();
        Product scarce = productWith(pid, /*stock=*/ 2, "Croissant");
        when(productRepository.findAllById(List.of(pid))).thenReturn(List.of(scarce));

        OrderItem item = new OrderItem(pid, 5, 100L);

        assertThatThrownBy(() -> stockService.decrementForOrder(List.of(item)))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Croissant")
                .hasMessageContaining("requested 5")
                .hasMessageContaining("available 2");

        // Never got to the saveAll — the throw happened mid-loop.
        verify(productRepository, never()).saveAll(anyCollection());
        // Stock was not mutated on the way to the throw.
        assertThat(scarce.getQuantityInStock()).isEqualTo(2);
    }

    @Test
    void sufficientStockDecrements() {
        UUID pid = UUID.randomUUID();
        Product ok = productWith(pid, /*stock=*/ 10, "Bagel");
        when(productRepository.findAllById(List.of(pid))).thenReturn(List.of(ok));

        OrderItem item = new OrderItem(pid, 3, 100L);
        stockService.decrementForOrder(List.of(item));

        assertThat(ok.getQuantityInStock()).isEqualTo(7);
        verify(productRepository).saveAll(anyCollection());
    }

    @Test
    void recoverFromOptimisticLockThrowsInsufficientStock() {
        OrderItem item = new OrderItem(UUID.randomUUID(), 1, 100L);

        assertThatThrownBy(() ->
                stockService.recoverFromOptimisticLock(
                        new ObjectOptimisticLockingFailureException(Product.class, UUID.randomUUID()),
                        List.of(item)))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("3 retries");
    }

    @Test
    void emptyItemsListIsNoOp() {
        stockService.decrementForOrder(List.of());
        verify(productRepository, never()).findAllById(anyCollection());
        verify(productRepository, never()).saveAll(anyCollection());
    }
}
