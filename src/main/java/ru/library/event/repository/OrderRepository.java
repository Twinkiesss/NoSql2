package ru.library.event.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.library.event.model.Order;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") String id);

    long countByUserIdAndIssuedAtNotNull(String userId);

    long countByUserIdAndReturnedAtNotNull(String userId);

    long countByUserIdAndStatus(String userId, Order.Status status);
}
