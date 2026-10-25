package ru.library.event.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.library.event.exception.EventNotFoundException;
import ru.library.event.exception.InvalidOrderStateException;
import ru.library.event.exception.NoAvailableCopiesException;
import ru.library.event.exception.OrderNotFoundException;
import ru.library.event.model.Order;
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

    public OrderService(OrderRepository orderRepository,
                        TemporaryRequestRepository tempRequestRepository,
                        EventService eventService,
                        UserAccountRepository userAccountRepository) {
        this.orderRepository = orderRepository;
        this.tempRequestRepository = tempRequestRepository;
        this.eventService = eventService;
        this.userAccountRepository = userAccountRepository;
    }

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
        if (admin) {
            // Если заявку создал админ, он сразу отображается в колонке «Админ».
            order.setManagerId(actorId);
            order.setManagerName(displayName(actorId));
        }
        Order saved = orderRepository.save(order);
        log.info("Order created: orderId={}, eventId={}, reader={}, createdBy={}, admin={}",
                saved.getId(), eventId, readerId, actorId, admin);
        return saved;
    }

    public Optional<Order> getOrder(String id) {
        return orderRepository.findById(id);
    }

    public Optional<Order> getOrder(String id, String actorId, boolean admin) {
        Optional<Order> order = orderRepository.findById(id);
        if (order.isPresent() && !admin && !order.get().getUserId().equals(actorId)) {
            throw new AccessDeniedException("Order belongs to another user");
        }
        return order;
    }

    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    public List<Order> getOrders(String actorId, boolean admin) {
        return admin
                ? orderRepository.findAllByOrderByCreatedAtDesc()
                : orderRepository.findByUserIdOrderByCreatedAtDesc(actorId);
    }

    @Transactional
    public Order updateOrderStatus(String orderId, String actorId, boolean admin, Order.Status status) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() == Order.Status.COMPLETED || order.getStatus() == Order.Status.CANCELLED) {
            throw new InvalidOrderStateException(
                    "Событие " + orderId + " уже завершено со статусом " + order.getStatus());
        }
        if (status == Order.Status.COMPLETED) {
            throw new InvalidOrderStateException(
                    "Статус COMPLETED можно установить только возвратом: POST /api/orders/" + orderId + "/return");
        }
        if (order.getStatus() == Order.Status.CONFIRMED && status == Order.Status.CANCELLED) {
            throw new InvalidOrderStateException(
                    "Книга по событию " + orderId + " уже выдана, отменить выдачу нельзя: верните книгу: POST /api/orders/"
                            + orderId + "/return");
        }
        if (order.getStatus() == status) {
            if (status == Order.Status.CONFIRMED) {
                throw new InvalidOrderStateException(
                        "Книга уже выдана администратором " + (order.getManagerName() == null
                                ? order.getManagerId() : order.getManagerName()));
            }
            return order;
        }

        if (status == Order.Status.CONFIRMED) {
            if (!admin) {
                throw new AccessDeniedException("Only an administrator can issue a book");
            }
            if (isAssignedToAnotherAdmin(order, actorId)) {
                throw new AccessDeniedException("Order is assigned to another administrator");
            }
            if (order.getStatus() != Order.Status.PENDING) {
                throw new InvalidOrderStateException("Выдать книгу можно только для запрошенного события " + orderId);
            }
            order.setIssuedAt(LocalDateTime.now());
            order.setManagerId(actorId);
            order.setManagerName(displayName(actorId));
        } else if (status == Order.Status.CANCELLED) {
            if (admin) {
                if (isAssignedToAnotherAdmin(order, actorId)) {
                    throw new AccessDeniedException("Order is assigned to another administrator");
                }
                if (order.getManagerId() == null) {
                    order.setManagerId(actorId);
                    order.setManagerName(displayName(actorId));
                }
            } else if (!order.getUserId().equals(actorId)) {
                throw new AccessDeniedException("Order belongs to another user");
            }
            eventService.releaseOneCopy(order.getEventId());
        } else {
            throw new InvalidOrderStateException("Недопустимый переход статуса: " + status);
        }

        Order.Status previousStatus = order.getStatus();
        order.setStatus(status);
        order.setUpdatedAt(LocalDateTime.now());
        Order updated = orderRepository.save(order);
        log.info("Order status changed: orderId={}, {} -> {}, actor={}, admin={}",
                orderId, previousStatus, status, actorId, admin);
        return updated;
    }

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
            throw new InvalidOrderStateException(
                    "Книгу по событию " + orderId + " ещё не выдали: сначала подтвердите выдачу");
        }
        if (order.getManagerId() == null) {
            // Заказы, выданные до появления ролей, не имеют известного администратора.
            order.setManagerId(actorId);
            order.setManagerName(displayName(actorId));
            log.info("Legacy order without issuer returned by admin: orderId={}, acceptedBy={}", orderId, actorId);
        } else if (!order.getManagerId().equals(actorId)) {
            throw new AccessDeniedException(
                    "Return can be accepted only by the administrator who issued this book");
        }

        eventService.releaseOneCopy(order.getEventId());
        order.setStatus(Order.Status.COMPLETED);
        order.setReturnedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        Order updated = orderRepository.save(order);
        log.info("Book returned: orderId={}, eventId={}, acceptedBy={}", orderId, order.getEventId(), actorId);
        return updated;
    }

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
        log.info("Temporary request deleted: id={}", id);
    }

    private String displayName(String userId) {
        return userAccountRepository.findById(userId)
                .map(UserAccount::getDisplayName)
                .orElse(userId);
    }

    private static boolean isAssignedToAnotherAdmin(Order order, String actorId) {
        return order.getManagerId() != null && !order.getManagerId().equals(actorId);
    }
}
