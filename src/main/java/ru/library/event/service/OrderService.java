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
import ru.library.event.model.PageResponse;
import ru.library.event.model.TemporaryRequest;
import ru.library.event.model.UserAccount;
import ru.library.event.model.UserRole;
import ru.library.event.repository.OrderRepository;
import ru.library.event.repository.TemporaryRequestRepository;
import ru.library.event.repository.UserAccountRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final long DEFAULT_TEMP_TTL_SECONDS = 300;
    private static final long MAX_TEMP_TTL_SECONDS = 3600;

    private final OrderRepository orderRepository;
    private final TemporaryRequestRepository tempRequestRepository;
    private final EventService eventService;
    private final UserAccountRepository userAccountRepository;
    private final EtcdQueryCacheService cache;

    public OrderService(OrderRepository orderRepository,
                        TemporaryRequestRepository tempRequestRepository,
                        EventService eventService,
                        UserAccountRepository userAccountRepository,
                        EtcdQueryCacheService cache) {
        this.orderRepository = orderRepository;
        this.tempRequestRepository = tempRequestRepository;
        this.eventService = eventService;
        this.userAccountRepository = userAccountRepository;
        this.cache = cache;
    }

    // ---------- create ----------

    @Transactional
    public Order createOrder(String eventId, String readerId, String actorId, boolean admin) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Book is required");
        }
        if (readerId == null || readerId.isBlank()) {
            throw new IllegalArgumentException("Reader is required");
        }
        if (!admin && !readerId.equals(actorId)) {
            throw new AccessDeniedException("A reader can create an order only for himself");
        }
        UserAccount reader = userAccountRepository.findById(readerId)
                .orElseThrow(() -> new IllegalArgumentException("Reader not found: " + readerId));
        if (reader.getRole() != UserRole.USER) {
            throw new IllegalArgumentException("Orders can be created only for readers");
        }

        int reserved = eventService.reserveOneCopy(eventId);
        if (reserved == 0) {
            if (eventService.getEvent(eventId).isEmpty()) {
                throw new EventNotFoundException(eventId);
            }
            throw new NoAvailableCopiesException(eventId);
        }

        Order order = new Order(eventId, reader.getId(), reader.getDisplayName());
        order.setStatus(Order.Status.PENDING); // PENDING всегда
        if (admin) {
            // Если заявку создал админ, он сразу отображается в колонке «Админ».
            order.setManagerId(actorId);
            order.setManagerName(displayName(actorId));
        }
        Order saved = orderRepository.save(order);
        cache.invalidate();
        log.info("Order created: orderId={}, eventId={}, reader={}, createdBy={}, admin={}",
                saved.getId(), eventId, readerId, actorId, admin);
        return saved;
    }

    // ---------- read ----------

    @Transactional(readOnly = true)
    public Optional<Order> getOrder(String id, String userId, boolean admin) {
        Optional<Order> found = orderRepository.findById(id);
        if (!admin && found.isPresent() && !found.get().getUserId().equals(userId)) {
            throw new AccessDeniedException("Нельзя просматривать чужую заявку");
        }
        return found;
    }

    /**
     * Пагинированный поиск заявок.
     * Читатель видит только свои (userId подставляется автоматически),
     * админ — все. status == null — без фильтра по статусу.
     */
    @Transactional(readOnly = true)
    public PageResponse<Order> searchOrders(String actorId, boolean admin,
                                            Order.Status status, String sortField,
                                            boolean descending, int page, int size) {
        Sort.Direction direction = descending ? Sort.Direction.DESC : Sort.Direction.ASC;
        String field = sortField == null || sortField.isBlank() ? "createdAt" : sortField;
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.max(1, Math.min(100, size)),
                Sort.by(direction, field));
        String userId = admin ? null : actorId;
        Page<Order> result = orderRepository.search(userId, status, pageable);
        return PageResponse.of(result.getContent(), result.getTotalElements(),
                result.getNumber(), result.getSize());
    }

    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    public List<Order> getOrders(String actorId, boolean admin) {
        return admin
                ? orderRepository.findAllByOrderByCreatedAtDesc()
                : orderRepository.findByUserIdOrderByCreatedAtDesc(actorId);
    }

    // ---------- status changes ----------

    @Transactional
    public Order updateOrderStatus(String orderId, String actorId, boolean admin, Order.Status status) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != Order.Status.PENDING) {
            throw new InvalidOrderStateException(
                    "Выдать или отменить можно только заявку со статусом PENDING");
        }
        if (status == Order.Status.COMPLETED) {
            throw new InvalidOrderStateException(
                    "Статус COMPLETED можно установить только возвратом: POST /api/orders/" + orderId + "/return");
        }
        if (status != Order.Status.CONFIRMED && status != Order.Status.CANCELLED) {
            throw new InvalidOrderStateException("Недопустимый переход статуса: " + status);
        }

        if (status == Order.Status.CONFIRMED) {
            if (!admin) {
                throw new AccessDeniedException("Only an administrator can issue a book");
            }
            order.setIssuedAt(LocalDateTime.now());
            order.setManagerId(actorId);
            order.setManagerName(displayName(actorId));
        } else { // CANCELLED
            if (!admin && !order.getUserId().equals(actorId)) {
                throw new AccessDeniedException("Order belongs to another user");
            }
            eventService.releaseOneCopy(order.getEventId());
        }

        Order.Status previous = order.getStatus();
        order.setStatus(status);
        order.setUpdatedAt(LocalDateTime.now());
        Order updated = orderRepository.save(order);
        cache.invalidate();
        log.info("Order status changed: orderId={}, {} -> {}, actor={}, admin={}",
                orderId, previous, status, actorId, admin);
        return updated;
    }

    /**
     * Принять возврат книги. Может любой администратор (не только выдавший) —
     * иначе заявки зависают при смене/увольнении администратора.
     */
    @Transactional
    public Order returnBook(String orderId, String actorId, boolean admin) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (!admin) {
            throw new AccessDeniedException("Only an administrator can accept a returned book");
        }
        if (order.getStatus() == Order.Status.COMPLETED) {
            throw new InvalidOrderStateException("Книга по событию " + orderId + " уже возвращена");
        }
        if (order.getStatus() == Order.Status.CANCELLED) {
            throw new InvalidOrderStateException(
                    "Событие " + orderId + " отменено, книга уже возвращена в каталог");
        }
        if (order.getStatus() != Order.Status.CONFIRMED) {
            throw new InvalidOrderStateException("Вернуть можно только выданную книгу");
        }

        eventService.releaseOneCopy(order.getEventId());
        order.setStatus(Order.Status.COMPLETED);
        order.setReturnedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        if (order.getManagerId() == null) {
            // Заявки, выданные до появления ролей, не имеют известного администратора.
            order.setManagerId(actorId);
            order.setManagerName(displayName(actorId));
            log.info("Legacy order without issuer returned by admin: orderId={}, acceptedBy={}",
                    orderId, actorId);
        }
        Order updated = orderRepository.save(order);
        cache.invalidate();
        log.info("Book returned: orderId={}, eventId={}, acceptedBy={}",
                orderId, order.getEventId(), actorId);
        return updated;
    }

    /**
     * Удалить заявку. Только админ и только если она не выдана (CONFIRMED).
     * CANCELLED/COMPLETED можно удалять (история), PENDING — можно.
     */
    @Transactional
    public void deleteOrder(String orderId, boolean admin) {
        if (!admin) {
            throw new AccessDeniedException("Only an administrator can delete an order");
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() == Order.Status.CONFIRMED) {
            throw new InvalidOrderStateException(
                    "Нельзя удалить выданную заявку — сначала верните книгу");
        }
        orderRepository.deleteById(orderId);
        cache.invalidate();
        log.info("Order deleted by admin: orderId={}, status={}", orderId, order.getStatus());
    }

    // ---------- temporary requests ----------

    public TemporaryRequest createTemporaryRequest(String eventId, String readerId, String createdById,
                                                   String purpose, long ttlSeconds) {
        UserAccount reader = userAccountRepository.findById(readerId)
                .orElseThrow(() -> new IllegalArgumentException("Reader not found: " + readerId));
        if (reader.getRole() != UserRole.USER) {
            throw new IllegalArgumentException("Temporary requests can be created only for readers");
        }
        long effectiveTtl = ttlSeconds > 0 ? Math.min(ttlSeconds, MAX_TEMP_TTL_SECONDS) : DEFAULT_TEMP_TTL_SECONDS;
        TemporaryRequest request = new TemporaryRequest(
                eventId, reader.getId(), reader.getDisplayName(),
                createdById, displayName(createdById), purpose, effectiveTtl);
        tempRequestRepository.saveWithTtl(request, effectiveTtl);
        log.info("Temporary request created: id={}, eventId={}, reader={}, createdBy={}, ttl={}s",
                request.getId(), eventId, readerId, createdById, effectiveTtl);
        return request;
    }

    public Optional<TemporaryRequest> getTemporaryRequest(String id) {
        return tempRequestRepository.findById(id);
    }

    public Optional<TemporaryRequest> getTemporaryRequest(String id, String actorId, boolean admin) {
        Optional<TemporaryRequest> request = tempRequestRepository.findById(id);
        if (request.isPresent() && !admin && !request.get().getUserId().equals(actorId)) {
            throw new AccessDeniedException("Temporary request belongs to another user");
        }
        return request;
    }

    public List<TemporaryRequest> getAllTemporaryRequests() {
        return tempRequestRepository.findAll();
    }

    public List<TemporaryRequest> getTemporaryRequests(String actorId, boolean admin) {
        return tempRequestRepository.findAll().stream()
                .filter(request -> !request.isExpired())
                .filter(request -> admin || request.getUserId().equals(actorId))
                .toList();
    }

    public void deleteTemporaryRequest(String id) {
        tempRequestRepository.deleteById(id);
    }

    private String displayName(String userId) {
        return userAccountRepository.findById(userId)
                .map(UserAccount::getDisplayName)
                .orElse(userId);
    }
}