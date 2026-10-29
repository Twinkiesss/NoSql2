package ru.library.event.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.library.event.exception.EventNotFoundException;
import ru.library.event.exception.InvalidOrderStateException;
import ru.library.event.exception.NoAvailableCopiesException;
import ru.library.event.exception.OrderNotFoundException;
import ru.library.event.model.Order;
import ru.library.event.model.TemporaryRequest;
import ru.library.event.repository.OrderRepository;
import ru.library.event.repository.TemporaryRequestRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final long DEFAULT_TEMP_TTL_SECONDS = 300;
    private final OrderRepository orderRepository;
    private final TemporaryRequestRepository tempRequestRepository;
    private final EventService eventService;

    public OrderService(OrderRepository orderRepository, TemporaryRequestRepository tempRequestRepository,
                        EventService eventService) {
        this.orderRepository = orderRepository;
        this.tempRequestRepository = tempRequestRepository;
        this.eventService = eventService;
    }

    @Transactional
    public Order createOrder(String eventId, String userId) {
        if (eventService.getEvent(eventId).isEmpty()) throw new EventNotFoundException(eventId);
        int reserved = eventService.reserveOneCopy(eventId);
        if (reserved == 0) throw new NoAvailableCopiesException(eventId);
        Order order = orderRepository.save(new Order(eventId, userId, userId));
        log.info("PENDING request created and one copy reserved: orderId={}, eventId={}, userId={}",
                order.getId(), eventId, userId);
        return order;
    }

    @Transactional(readOnly = true)
    public Optional<Order> getOrder(String id, String userId, boolean admin) {
        Optional<Order> found = orderRepository.findById(id);
        if (!admin && found.isPresent() && !found.get().getUserId().equals(userId)) {
            throw new AccessDeniedException("Нельзя просматривать чужую заявку");
        }
        return found;
    }

    @Transactional(readOnly = true)
    public List<Order> getAllOrders(String userId, boolean admin) {
        return admin ? orderRepository.findAll() : orderRepository.search(userId, null, PageRequest.of(0, 10000, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    @Transactional(readOnly = true)
    public Page<Order> getOrdersPage(String userId, boolean admin, Order.Status status,
                                     int page, int size, String sort, String direction) {
        String requestedSort = sort == null ? "createdAt" : sort;
        String safeSort = switch (requestedSort) {
            case "createdAt", "updatedAt", "status", "userId", "eventId", "issuedAt", "returnedAt" -> requestedSort;
            default -> "createdAt";
        };
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)),
                Sort.by(sortDirection, safeSort));
        return orderRepository.search(admin ? null : userId, status, pageable);
    }

    @Transactional
    public Order updateOrderStatus(String orderId, String userId, Order.Status status, boolean admin) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (!admin) {
            if (!order.getUserId().equals(userId)) throw new AccessDeniedException("Нельзя изменять чужую заявку");
            if (status != Order.Status.CANCELLED || order.getStatus() != Order.Status.PENDING) {
                throw new AccessDeniedException("Читатель может только отменить собственную заявку со статусом PENDING");
            }
        } else if (status != Order.Status.CONFIRMED && status != Order.Status.CANCELLED) {
            throw new InvalidOrderStateException("Администратор может выдать или отменить заявку");
        }

        if (order.getStatus() != Order.Status.PENDING) {
            throw new InvalidOrderStateException("Выдать или отменить можно только заявку со статусом PENDING");
        }
        if (status == Order.Status.COMPLETED) {
            throw new InvalidOrderStateException("Для возврата используйте отдельную операцию");
        }
        if (status == Order.Status.CANCELLED) eventService.releaseOneCopy(order.getEventId());
        if (status == Order.Status.CONFIRMED) order.setIssuedAt(LocalDateTime.now());
        order.setStatus(status);
        order.setUpdatedAt(LocalDateTime.now());
        Order saved = orderRepository.save(order);
        log.info("Order status changed: id={}, {} -> {}, actor={}, admin={}",
                orderId, Order.Status.PENDING, status, userId, admin);
        return saved;
    }

    @Transactional
    public Order returnBook(String orderId, String userId, boolean admin) {
        if (!admin) throw new AccessDeniedException("Возврат книги доступен только администратору");
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != Order.Status.CONFIRMED) {
            throw new InvalidOrderStateException("Вернуть можно только выданную книгу");
        }
        eventService.releaseOneCopy(order.getEventId());
        order.setStatus(Order.Status.COMPLETED);
        order.setReturnedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        log.info("Book returned by admin {}: orderId={}", userId, orderId);
        return orderRepository.save(order);
    }

    public TemporaryRequest createTemporaryRequest(String eventId, String userId, String purpose, long ttlSeconds) {
        long effectiveTtl = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TEMP_TTL_SECONDS;
        TemporaryRequest request = new TemporaryRequest(eventId, userId, purpose, effectiveTtl);
        tempRequestRepository.saveWithTtl(request, effectiveTtl);
        log.info("Temporary request created: id={}, eventId={}, userId={}, ttl={}s",
                request.getId(), eventId, userId, effectiveTtl);
        return request;
    }
    public Optional<TemporaryRequest> getTemporaryRequest(String id) { return tempRequestRepository.findById(id); }
    public List<TemporaryRequest> getAllTemporaryRequests() { return tempRequestRepository.findAll(); }
    public void deleteTemporaryRequest(String id) { tempRequestRepository.deleteById(id); }
}
