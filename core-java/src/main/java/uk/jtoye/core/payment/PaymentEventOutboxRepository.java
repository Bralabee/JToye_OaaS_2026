package uk.jtoye.core.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentEventOutboxRepository extends JpaRepository<PaymentEventOutbox, UUID> {

    /**
     * Pull the next batch of PENDING events for the flusher, oldest first.
     * Capped at 100 to keep each flusher tick bounded.
     */
    List<PaymentEventOutbox> findTop100ByStatusOrderByCreatedAtAsc(PaymentEventOutbox.Status status);
}
