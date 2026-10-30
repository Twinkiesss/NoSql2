package ru.library.event.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.library.event.model.Order;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") String id);

    List<Order> findByUserIdOrderByCreatedAtDesc(String userId);
    @Query("select o from Order o where (:userId is null or o.userId = :userId) and (:status is null or o.status = :status)")
    Page<Order> search(@Param("userId") String userId, @Param("status") Order.Status status, Pageable pageable);

    long countByStatus(Order.Status status);

    @Query("select o.userId, count(o) from Order o group by o.userId order by count(o) desc")
    List<Object[]> countOrdersByUser();

    long countByUserIdAndIssuedAtNotNull(String userId);

    List<Order> findAllByOrderByCreatedAtDesc();

    @Query("""
            select count(o.issuedAt) as issued,
                   count(o.returnedAt) as returned,
                   count(case when o.status = :confirmed then 1 end) as active
            from Order o
            where o.userId = :userId
            """)
    UserOrderStatsProjection findUserStats(@Param("userId") String userId,
                                           @Param("confirmed") Order.Status confirmed);

    @Query(value = """
            select count(*) as total,
                   count(issued_at) as issued,
                   count(returned_at) as returned,
                   (select count(*) from users where role = 'USER') as readers
            from orders
            """, nativeQuery = true)
    LibraryStatsProjection findLibraryStats();
}