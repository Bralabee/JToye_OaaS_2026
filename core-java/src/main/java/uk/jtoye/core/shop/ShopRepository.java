package uk.jtoye.core.shop;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ShopRepository extends JpaRepository<Shop, UUID> {
}
