package ru.library.event.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.library.event.model.CreateOrderRequest;
import ru.library.event.model.Order;
import ru.library.event.model.OrderResponse;
import ru.library.event.model.PageResponse;
import ru.library.event.service.OrderService;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "*")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request,
                                                     Authentication authentication) {
        boolean admin = isAdmin(authentication);
        String readerId = admin ? request.getUserId() : authentication.getName();
        return ResponseEntity.ok(OrderResponse.from(orderService.createOrder(
                request.getEventId(), readerId, authentication.getName(), admin)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String id, Authentication authentication) {
        return orderService.getOrder(id, authentication.getName(), isAdmin(authentication))
                .map(order -> ResponseEntity.ok(OrderResponse.from(order)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/orders?status=&sort=createdAt&direction=desc&page=0&size=10
     * Для читателя userId подставляется автоматически (он видит только свои).
     */
    @GetMapping
    public ResponseEntity<PageResponse<OrderResponse>> getAllOrders(
            @RequestParam(required = false) Order.Status status,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication authentication) {
        boolean descending = !"asc".equalsIgnoreCase(direction);
        PageResponse<Order> pageResult = orderService.searchOrders(
                authentication.getName(), isAdmin(authentication),
                status, sort, descending, page, size);
        List<OrderResponse> items = pageResult.items().stream().map(OrderResponse::from).toList();
        return ResponseEntity.ok(PageResponse.of(
                items, pageResult.total(), pageResult.page(), pageResult.size()));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<OrderResponse> updateOrderStatus(@PathVariable String id,
                                                           @RequestParam Order.Status status,
                                                           Authentication auth) {
        Order updated = orderService.updateOrderStatus(
                id, auth.getName(), isAdmin(auth), status);
        return ResponseEntity.ok(OrderResponse.from(updated));
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrderResponse> returnBook(@PathVariable String id, Authentication authentication) {
        return ResponseEntity.ok(OrderResponse.from(
                orderService.returnBook(id, authentication.getName(), true)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteOrder(@PathVariable String id, Authentication authentication) {
        orderService.deleteOrder(id, isAdmin(authentication));
        return ResponseEntity.ok().build();
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}